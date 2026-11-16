package com.imgmine.agentic;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;
import java.util.regex.Pattern;

/** The agent loop: screen dump -> AI -> JSON action -> gates -> execute -> repeat. */
public class AgentLoop extends Thread {
    public interface Ui {
        void aiMessage(String text);
        void systemMessage(String text);
        void thinking(boolean on);
        void thinkingProgress(int chars);
        void askUser(String question, YesNo cb);
        void loopEnded();
    }
    public interface YesNo { void answer(boolean yes); }

    private static final int MAX_STEPS = 30;
    private static final Pattern MONEY = Pattern.compile(
        "(?i)(pay|payment|paisa|paise|rupee|rupees|rs\\.?|inr|\u20B9|upi|buy|buying|purchase|khareed|kharid|order|debit|credit card|bank transfer|send money|paise bhej|recharge|bill|checkout|subscribe|subscription)");

    private final Context ctx;
    private final Ui ui;
    private final String instruction;
    private final boolean moneyMode;
    public volatile boolean stopped = false;
    private static String cachedApps;

    public AgentLoop(Context ctx, Ui ui, String instruction) {
        this.ctx = ctx.getApplicationContext();
        this.ui = ui;
        this.instruction = instruction;
        this.moneyMode = MONEY.matcher(instruction).find();
    }

    private static String systemPrompt() {
        return "You are Agentic, an expert Android phone-control agent. You receive a text dump of the current screen and act by replying with EXACTLY ONE raw JSON object (no markdown, no prose outside JSON).\n\n" +
        "SCREEN DUMP FORMAT:\n" +
        "App: <foreground app package>\n" +
        "Screen WxH\n" +
        "#id [TYPE] \"text\" (description) @cx,cy flags\n" +
        "TYPE: BTN (clickable), INPUT (editable field - type here), LIST (scrollable area), TXT (static text), IMG (image). flags: focused/selected. @cx,cy is the element center in pixels.\n\n" +
        "ACTIONS (exactly one per reply):\n" +
        "{\"thought\":\"...\",\"action\":\"plan\",\"plan\":\"1) ... 2) ... 3) ...\"} - REQUIRED as your FIRST reply for any multi-step task\n" +
        "{\"thought\":\"...\",\"action\":\"tap_id\",\"id\":N} - tap element N (ALWAYS prefer over coordinates)\n" +
        "{\"thought\":\"...\",\"action\":\"tap\",\"x\":X,\"y\":Y} - tap raw pixel, only when no id fits\n" +
        "{\"thought\":\"...\",\"action\":\"type\",\"id\":N,\"text\":\"...\"} - set text of INPUT element N\n" +
        "{\"thought\":\"...\",\"action\":\"swipe\",\"x1\":A,\"y1\":B,\"x2\":C,\"y2\":D} - scroll down: swipe from lower screen to upper, e.g. (540,1800)->(540,600)\n" +
        "{\"thought\":\"...\",\"action\":\"back\"} | {\"action\":\"home\"} | {\"action\":\"recents\"}\n" +
        "{\"thought\":\"...\",\"action\":\"open_app\",\"package\":\"...\"} - ONLY package names from the installed list, never invent\n" +
        "{\"thought\":\"...\",\"action\":\"wait\",\"ms\":N} - wait for loading (1000-3000 after open_app or big taps)\n" +
        "{\"thought\":\"...\",\"action\":\"scroll\",\"direction\":\"up|down|left|right\"} - scroll a list/feed\n" +
        "{\"thought\":\"...\",\"action\":\"long_press_id\",\"id\":N} / {\"action\":\"long_press\",\"x\":X,\"y\":Y} - long-press (context menus)\n" +
        "{\"thought\":\"...\",\"action\":\"notifications\"} / {\"action\":\"quick_settings\"} - pull down shade / quick tiles\n" +
        "{\"thought\":\"...\",\"action\":\"volume_up\"} / {\"action\":\"volume_down\"}\n" +
        "{\"thought\":\"...\",\"action\":\"rotate\",\"mode\":\"auto|portrait|landscape|portrait_reverse|landscape_reverse\"} - rotate the screen\n" +
        "{\"thought\":\"...\",\"action\":\"power_menu\"} / {\"action\":\"lock_screen\"}\n" +
        "{\"thought\":\"...\",\"action\":\"ask\",\"text\":\"...\"} - ask the user. REQUIRED when the task is ambiguous or a choice matters (what to send, which item, payments). Never guess for payments or personal messages.\n" +
        "{\"thought\":\"...\",\"action\":\"done\",\"text\":\"...\"} - task complete; short friendly summary in the user's language.\n\n" +
        "RULES:\n" +
        "1. \"thought\" is MANDATORY in every reply: one short sentence - what you see and why this action. Think before acting.\n" +
        "2. Multi-step task -> first reply is plan, then act step by step, re-checking the screen each turn.\n" +
        "3. Recovery: if LAST RESULT is an error or the screen did not change, do NOT repeat blindly - wait, scroll, try coordinates, or pick another element. After 2 failed retries, ask the user.\n" +
        "4. ids come from the LATEST dump only; they change every screen.\n" +
        "5. After typing into an INPUT, the submit/send button is usually a nearby BTN - tap it to confirm.\n" +
        "6. Never confirm purchases/payments yourself; use ask for every such step.\n" +
        "7. Keep acting until the task is truly done, then reply done with a summary.\n\n" +
        "EXAMPLE (task: whatsapp pe Rahul ko 'hello' bhejo; dump shows App: com.whatsapp, #7 [INPUT] \"Message\" @300,2200, #9 [BTN] \"Send\" @1000,2200):\n" +
        "Turn 1: {\"thought\":\"Rahul ki chat khuli hai; pehle message type karta hu.\",\"action\":\"type\",\"id\":7,\"text\":\"hello\"}\n" +
        "Turn 2: {\"thought\":\"Message type ho gaya; ab Send dabata hu.\",\"action\":\"tap_id\",\"id\":9}\n" +
        "Turn 3: {\"thought\":\"Message send ho gaya, tick aa gayi.\",\"action\":\"done\",\"text\":\"Ho gaya! Rahul ko 'hello' bhej diya.\"}";
    }

    private String installedApps() {
        if (cachedApps != null) return cachedApps;
        StringBuilder sb = new StringBuilder("Installed apps (label | package):\n");
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = ctx.getPackageManager().queryIntentActivities(i, 0);
        int n = 0;
        for (ResolveInfo r : apps) {
            if (n++ >= 120) { sb.append("...(more)\n"); break; }
            sb.append(r.loadLabel(ctx.getPackageManager())).append(" | ")
              .append(r.activityInfo.packageName).append('\n');
        }
        cachedApps = sb.toString();
        return cachedApps;
    }

    @Override public void run() {
        AgentService svc = AgentService.instance;
        if (svc == null) { ui.systemMessage("Accessibility service is off - enable it first, then try again."); ui.loopEnded(); return; }
        if (moneyMode) ui.systemMessage("Payment-related task: I will confirm every step with you.");
        try {
            String root = SecureStore.baseUrl(ctx), model = SecureStore.model(ctx), key;
            try {
                key = SecureStore.apiKey(ctx);
            } catch (Exception e) {
                ui.systemMessage("Saved API key unreadable (" + e.getMessage() + ") - open the Agentic app, tap 'change API / model' and re-enter your key.");
                ui.loopEnded();
                return;
            }
            if (root == null || key == null || model == null) {
                ui.systemMessage("API setup missing - open the Agentic app and connect your API first.");
                ui.loopEnded();
                return;
            }
            JSONArray msgs = new JSONArray();
            msgs.put(new JSONObject().put("role", "system").put("content", systemPrompt()));
            StringBuilder task = new StringBuilder("TASK: ").append(instruction).append("\n\n").append(installedApps());
            int steps = 0, retries = 0;
            String lastResult = "(start)";
            while (!stopped && steps < MAX_STEPS) {
                steps++;
                String screen = svc.dump();
                String userMsg = task.toString() + "\n\nSCREEN:\n" + screen + "\nLAST RESULT: " + lastResult +
                        (moneyMode ? "\nREMINDER: money-related - confirm steps with the user via ask." : "");
                task.setLength(0);
                msgs.put(new JSONObject().put("role", "user").put("content", userMsg));
                trim(msgs);
                ui.thinking(true);
                String reply;
                try {
                    reply = Api.chat(root, key, model, msgs, new Api.Progress() {
                        @Override public void onChars(int n) { ui.thinkingProgress(n); }
                    });
                } finally { ui.thinking(false); }
                if (stopped) break;
                msgs.put(new JSONObject().put("role", "assistant").put("content", reply));
                JSONObject a = parse(reply);
                if (a == null) { lastResult = "error: reply was not one JSON action. Reply with exactly one raw JSON object."; continue; }
                String thought = a.optString("thought", "");
                if (thought.length() > 0) ui.systemMessage(thought);
                String act = a.optString("action");
                if ("done".equals(act)) { ui.aiMessage(a.optString("text", "Done.")); break; }
                if ("plan".equals(act)) {
                    String plan = a.optString("plan", "");
                    if (plan.length() > 0) ui.aiMessage("Plan:\n" + plan);
                    lastResult = "plan noted; now execute step 1.";
                    continue;
                }
                if ("ask".equals(act)) {
                    ui.aiMessage(a.optString("text", "The AI is asking:"));
                    break; // user's next chat message resumes as a new instruction
                }
                if ("open_app".equals(act)) {
                    String pkg = a.optString("package", "");
                    if (!SecureStore.appApproved(ctx, pkg)) {
                        Boolean ok = askSync("AI nayi app kholna chahta hai: " + pkg + "\nAllow?");
                        if (stopped) break;
                        if (ok == null || !ok) { lastResult = "user DENIED opening " + pkg + ". Do not retry; report back with done or ask."; continue; }
                        SecureStore.approveApp(ctx, pkg);
                    }
                }
                if (moneyMode) {
                    Boolean ok = askSync("Step " + steps + " allow kare?\n" + describe(a));
                    if (stopped) break;
                    if (ok == null || !ok) { lastResult = "user DENIED this step. Use ask or done now."; continue; }
                }
                ui.thinking(true);
                try { lastResult = svc.execBlocking(a); }
                finally { ui.thinking(false); }
                if (lastResult.startsWith("error")) {
                    retries++;
                    if (retries >= 4) { lastResult += " | hint: try a different approach (scroll, coordinates, or ask the user)."; }
                } else retries = 0;
                int wait = "wait".equals(act) ? Math.min(a.optInt("ms", 1000), 10000) : 400;
                try { Thread.sleep(wait); } catch (InterruptedException ignored) { break; }
            }
            if (!stopped && steps >= MAX_STEPS) ui.systemMessage("Step limit (" + MAX_STEPS + ") reached - agent paused. Send a message to continue.");
        } catch (Api.ApiError e) {
            ui.systemMessage("API error: " + e.getMessage());
        } catch (Throwable e) {
            ui.systemMessage("Agent error (" + e.getClass().getSimpleName() + ") - agent stopped, ball/window chalte rahenge. Dobara try karo.");
        } finally {
            ui.loopEnded();
        }
    }

    private static String describe(JSONObject a) {
        String act = a.optString("action");
        switch (act) {
            case "tap": return "Tap at " + a.optInt("x") + "," + a.optInt("y");
            case "tap_id": return "Tap element #" + a.optInt("id");
            case "type": return "Type \"" + a.optString("text") + "\" into element #" + a.optInt("id");
            case "swipe": return "Swipe " + a.optInt("x1") + "," + a.optInt("y1") + " -> " + a.optInt("x2") + "," + a.optInt("y2");
            case "open_app": return "Open app " + a.optString("package");
            default: return act;
        }
    }

    private Boolean askSync(String question) {
        final Boolean[] out = new Boolean[1];
        final Object lock = new Object();
        ui.askUser(question, new YesNo() {
            @Override public void answer(boolean yes) { synchronized (lock) { out[0] = yes; lock.notify(); } }
        });
        synchronized (lock) {
            long deadline = System.currentTimeMillis() + 120000;
            while (out[0] == null && !stopped) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;
                try { lock.wait(Math.min(left, 500)); } catch (InterruptedException e) { break; }
            }
        }
        return out[0];
    }

    private static void trim(JSONArray msgs) {
        while (msgs.length() > 10) msgs.remove(2);
    }

    private static JSONObject parse(String reply) {
        try {
            int s = reply.indexOf('{'), e = reply.lastIndexOf('}');
            if (s < 0 || e <= s) return null;
            return new JSONObject(reply.substring(s, e + 1));
        } catch (Exception e) { return null; }
    }
}
