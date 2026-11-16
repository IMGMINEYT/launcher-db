package com.imgmine.agentic;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
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
    private final JSONArray history = new JSONArray(); // prior user/assistant turns this session

    public AgentLoop(Context ctx, Ui ui, String instruction) {
        this.ctx = ctx.getApplicationContext();
        this.ui = ui;
        this.instruction = instruction;
        this.moneyMode = MONEY.matcher(instruction).find();
    }

    private static String systemPrompt() {
        return "You are Agentic, an Android phone-control agent. Each turn you get a text dump of the current screen: " +
        "numbered elements with their text and center pixel (x,y), plus the screen size. " +
        "Reply with EXACTLY ONE raw JSON object, no markdown, no prose. Actions:\n" +
        "{\"action\":\"tap_id\",\"id\":N} tap element N from the dump (PREFERRED)\n" +
        "{\"action\":\"tap\",\"x\":X,\"y\":Y} tap pixel\n" +
        "{\"action\":\"type\",\"id\":N,\"text\":\"...\"} replace text of editable element N\n" +
        "{\"action\":\"swipe\",\"x1\":A,\"y1\":B,\"x2\":C,\"y2\":D} swipe (scroll: swipe from lower screen to upper)\n" +
        "{\"action\":\"back\"} / {\"action\":\"home\"} / {\"action\":\"recents\"}\n" +
        "{\"action\":\"open_app\",\"package\":\"pkg.name\"} open an app (ONLY package names from the installed list)\n" +
        "{\"action\":\"wait\",\"ms\":N} wait for the screen to load\n" +
        "{\"action\":\"ask\",\"text\":\"...\"} ask the user something and stop\n" +
        "{\"action\":\"done\",\"text\":\"...\"} task complete, short summary for the user\n" +
        "Rules: think briefly then emit JSON only; prefer tap_id over coordinates; if the screen did not change after an action, wait or scroll instead of repeating; " +
        "never invent package names; for anything involving money/payment/orders, use ask to confirm with the user before every such step; if asked to open an app, check the installed list first.";
    }

    private String installedApps() {
        StringBuilder sb = new StringBuilder("Installed apps (label | package):\n");
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = ctx.getPackageManager().queryIntentActivities(i, 0);
        int n = 0;
        for (ResolveInfo r : apps) {
            if (n++ >= 120) { sb.append("...(more)\n"); break; }
            sb.append(r.loadLabel(ctx.getPackageManager())).append(" | ")
              .append(r.activityInfo.packageName).append('\n');
        }
        return sb.toString();
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
            for (int i = 0; i < history.length(); i++) msgs.put(history.get(i));
            StringBuilder task = new StringBuilder("TASK: ").append(instruction).append("\n\n").append(installedApps());
            int steps = 0;
            String lastResult = "(start)";
            while (!stopped && steps < MAX_STEPS) {
                steps++;
                String screen = svc.dump();
                String userMsg = task.toString() + "\n\nSCREEN:\n" + screen + "\nLAST RESULT: " + lastResult +
                        (moneyMode ? "\nREMINDER: money-related - confirm steps with the user via ask." : "");
                task.setLength(0); // only first turn carries the full task + app list
                msgs.put(new JSONObject().put("role", "user").put("content", userMsg));
                trim(msgs);
                ui.thinking(true);
                String reply;
                try { reply = Api.chat(root, key, model, msgs); }
                finally { ui.thinking(false); }
                if (stopped) break;
                msgs.put(new JSONObject().put("role", "assistant").put("content", reply));
                JSONObject a = parse(reply);
                if (a == null) { lastResult = "error: reply was not one JSON action. Reply with exactly one raw JSON object."; continue; }
                String act = a.optString("action");
                if ("done".equals(act)) { ui.aiMessage(a.optString("text", "Done.")); break; }
                if ("ask".equals(act)) {
                    String q = a.optString("text", "The AI is asking:");
                    ui.aiMessage(q);
                    break; // user's next chat message resumes as a new instruction
                }
                // gates
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
                int wait = "wait".equals(act) ? Math.min(a.optInt("ms", 1000), 10000) : 700;
                try { Thread.sleep(wait); } catch (InterruptedException ignored) { break; }
            }
            if (!stopped && steps >= MAX_STEPS) ui.systemMessage("Step limit (" + MAX_STEPS + ") reached - agent paused. Send a message to continue.");
        } catch (Api.ApiError e) {
            ui.systemMessage("API error: " + e.getMessage());
        } catch (Exception e) {
            ui.systemMessage("Glitch detected (" + e.getClass().getSimpleName() + ") - agent stopped for safety.");
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

    /** Ask the user yes/no and block the loop (max 120s; null = timeout). */
    private Boolean askSync(String question) {
        final Boolean[] out = new Boolean[1];
        final Object lock = new Object();
        ui.askUser(question, yes -> { synchronized (lock) { out[0] = yes; lock.notify(); } });
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
        // keep system + first user (task) + last 8 messages
        while (msgs.length() > 10) msgs.remove(2);
    }

    private static JSONObject parse(String reply) {
        try {
            int s = reply.indexOf('{'), e = reply.lastIndexOf('}');
            if (s < 0 || e <= s) return null;
            return new JSONObject(reply.substring(s, e + 1));
        } catch (Exception e) { return null; }
    }

    /** Persist a short exchange so later instructions keep context. */
    public void remember(String userText, String aiText) {
        try {
            history.put(new JSONObject().put("role", "user").put("content", userText));
            history.put(new JSONObject().put("role", "assistant").put("content", aiText));
            while (history.length() > 8) history.remove(0);
        } catch (Exception ignored) {}
    }
}
