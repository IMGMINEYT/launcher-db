package com.imgmine.agentic;

import android.content.Context;
import android.graphics.Bitmap;
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
        void systemMessage(String text); // narration (collapsible, no cost)
        void notice(String text);        // important: always visible
        void thinking(boolean on);
        void askUser(String question, YesNo cb);
        void loopEnded();
        /** Queued user messages sent while busy; null when none. */
        String pollPending();
    }
    public interface YesNo { void answer(boolean yes); }

    private static final int MAX_STEPS = 60;
    private static final Pattern THOUGHT = Pattern.compile("\"thought\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.){2,})");
    private final String[] streamedThought = {null};
    private static final Pattern MONEY = Pattern.compile(
        "(?i)(pay|payment|paisa|paise|rupee|rupees|rs\\.?|inr|\u20B9|upi|buy|buying|purchase|khareed|kharid|order|debit|credit card|bank transfer|send money|paise bhej|recharge|bill|checkout|subscribe|subscription)");

    private final Context ctx;
    private final Ui ui;
    private final String instruction;
    private final boolean moneyMode;
    private final int taskRisk; // 0 = LOW, 1 = MEDIUM (confirm once per task), 2 = HIGH (confirm every risky action)
    private boolean mediumApproved = false;
    public volatile boolean stopped = false;
    public volatile boolean paused = false;
    private volatile int pauseEpoch;
    private volatile long activeTaskId;
    public void setPaused(boolean value) {
        if (stopped || !isAlive()) return;
        paused = value;
        if (value) pauseEpoch++;
        if (activeTaskId != 0) TaskStore.update(ctx, activeTaskId,
            value ? TaskStore.PAUSED : TaskStore.RUNNING, value ? "User paused" : "Resumed");
    }
    private void awaitResume() throws InterruptedException {
        while ((paused || MainActivity.foreground) && !stopped) Thread.sleep(150);
    }
    private static String cachedApps;
    private static long cachedAppsAt;
    private final JSONArray mem; // cross-instruction session memory (owned by OverlayService)
    private String lastAiText = "";

    public AgentLoop(Context ctx, Ui ui, String instruction, JSONArray mem) {
        this.ctx = ctx.getApplicationContext();
        this.ui = ui;
        this.instruction = instruction;
        this.moneyMode = MONEY.matcher(instruction).find();
        this.taskRisk = classifyRisk(instruction);
        this.mem = mem;
    }

    /** Known vision-capable model ids (OpenAI-compatible multimodal). */
    public static boolean isVisionModel(String id) {
        if (id == null) return false;
        String s = id.toLowerCase();
        String[] pats = {"vision", "gpt-4o", "gpt-4.1", "gpt-5", "claude-3", "claude-4", "sonnet-4", "opus-4",
            "gemini", "llava", "pixtral", "minicpm", "molmo", "internvl", "grok-2", "grok-4"};
        for (String p : pats) if (s.contains(p)) return true;
        if (s.contains("qwen") && s.contains("vl")) return true;
        if (s.contains("llama-3.2") && (s.contains("11b") || s.contains("90b"))) return true;
        return false;
    }

    // ---- context/token management ----
    private static int estTokens(JSONArray msgs) {
        int chars = 0;
        for (int i = 0; i < msgs.length(); i++) {
            JSONObject o = msgs.optJSONObject(i);
            if (o == null) continue;
            Object c = o.opt("content");
            if (c instanceof JSONArray) {
                JSONArray parts = (JSONArray) c;
                for (int j = 0; j < parts.length(); j++) {
                    JSONObject p = parts.optJSONObject(j);
                    if (p == null) continue;
                    chars += "image_url".equals(p.optString("type")) ? 4400 : p.optString("text").length();
                }
            } else chars += o.optString("content").length();
        }
        return chars / 4 + msgs.length() * 4;
    }

    private static final int TOKEN_BUDGET = 5000;

    /** Replace image parts in all but the newest message with their text (images cost the most). */
    private static void stripOldImages(JSONArray msgs) throws Exception {
        for (int i = 1; i < msgs.length() - 1; i++) {
            JSONObject o = msgs.optJSONObject(i);
            if (o == null) continue;
            Object c = o.opt("content");
            if (!(c instanceof JSONArray)) continue;
            JSONArray parts = (JSONArray) c;
            for (int j = parts.length() - 1; j >= 0; j--) {
                JSONObject p = parts.optJSONObject(j);
                if (p != null && "image_url".equals(p.optString("type"))) parts.remove(j);
            }
            if (parts.length() == 1) {
                JSONObject p0 = parts.optJSONObject(0);
                if (p0 != null) o.put("content", p0.optString("text"));
            }
        }
    }

    /** Drop oldest turns (after system) until under budget; leaves a marker so the model knows. */
    private static void ensureBudget(JSONArray msgs) throws Exception {
        if (estTokens(msgs) <= TOKEN_BUDGET) return;
        stripOldImages(msgs);
        if (estTokens(msgs) <= TOKEN_BUDGET) return;
        if (msgs.length() > 2)
            msgs.put(1, new JSONObject().put("role", "user").put("content", "[purani conversation memory limit ke liye compress ho gayi]"));
        while (msgs.length() > 2 && estTokens(msgs) > TOKEN_BUDGET) msgs.remove(2);
    }

    /** Hard compress after a provider context-length error: strip images, keep system + last 4. */
    private static void compressHard(JSONArray msgs) {
        try { stripOldImages(msgs); } catch (Exception ignored) {}
        while (msgs.length() > 5) msgs.remove(1);
    }

    /** Chat call with key rotation on rate-limit and compression on context overflow. */
    private String callWithRetry(SecureStore.Prov pv, JSONArray msgs) throws Exception {
        int keys = Math.max(1, pv.keyCount());
        int compressions = 0, netRetries = 0;
        for (int attempt = 0; attempt <= keys; attempt++) {
            String key = pv.currentKey();
            try {
                return Api.chat(pv.base, key, pv.model, msgs, new Api.Progress() {
                    private boolean thoughtPosted = false;
                    @Override public void onPartial(String soFar) {
                        if (thoughtPosted) return;
                        java.util.regex.Matcher m = THOUGHT.matcher(soFar);
                        if (m.find()) {
                            String t = m.group(1);
                            int end = Api.jsonStringEnd(soFar, m.start(1));
                            if (end > 0 && t.length() > 1) {
                                thoughtPosted = true;
                                streamedThought[0] = t;
                                ui.systemMessage(t); // thought visible the moment it streams in
                            }
                        }
                    }
                });
            } catch (Api.ContextTooLong c) {
                if (++compressions > 3)
                    throw new Api.ApiError("Model ki memory chhoti hai - CLR dabake naya session shuru karo");
                compressHard(msgs);
                ui.systemMessage("Model ki memory bhar gayi - purani chat samet ke continue...");
                attempt--; // same key, retry compressed
            } catch (Api.RateLimited r) {
                if (keys > 1 && attempt < keys - 1) {
                    pv.rotate();
                    ui.systemMessage("Ek key busy hai - doosri key se continue...");
                } else {
                    throw new Api.ApiError(keys > 1
                        ? "Saari keys abhi busy hain - 1-2 min baad try karo"
                        : r.getMessage() + (pv.keyCount() <= 1 ? " (tip: app me doosri key add karo - busy hone par khud switch ho jayega)" : ""));
                }
            } catch (Api.ApiError e) {
                String m = e.getMessage() != null ? e.getMessage() : "";
                boolean transientNet = (m.contains("timeout") || m.contains("internet") || m.contains("khaali reply")
                    || m.contains("network") || m.contains("connect")) && !m.contains("host nahi mila");
                if (transientNet && netRetries < 2) {
                    netRetries++;
                    ui.systemMessage("Network hiccup - dobara koshish...");
                    try { Thread.sleep(1500); } catch (InterruptedException ie) { throw e; }
                    attempt--; // same key, one more try
                    continue;
                }
                throw e;
            }
        }
        throw new Api.ApiError("Saari keys abhi busy hain - 1-2 min baad try karo");
    }

    private static String systemPrompt() {
        return "You are Agentic, an expert Android phone-control agent. You receive a text dump of the current screen and act by replying with EXACTLY ONE raw JSON object (no markdown, no prose outside JSON).\n\n" +
        "SCREEN DUMP FORMAT:\n" +
        "App: <foreground app package>\n" +
        "Screen WxH\n" +
        "#id [TYPE] \"text\" (description) @cx,cy flags\n" +
        "TYPE: BTN (clickable), INPUT (editable field - type here), LIST (scrollable area), TXT (static text), IMG (image). flags: focused/selected. @cx,cy is the element center in pixels.\n\n" +
        "ANDROID CONTROL ESSENTIALS:\n" +
        "- Gestures: tap_id/tap (single), double_tap (like/open/zoom), long_press (menus/select), swipe (fast flick), drag (move items, slow), scroll (lists/feeds; chain for long lists), pinch (zoom).\n" +
        "- Swipe physics: short+fast = fling (may overshoot); long+slower = controlled scroll. To see content BELOW, swipe up (start low, end high).\n" +
        "- Lists load more as you scroll - after scrolling, new elements have new ids from the fresh dump.\n" +
        "- Text input: type replaces the field text (clear first to empty); after typing, submit via the app's nearby BTN (send arrow/search).\n" +
        "- Popups/ads/dialogs/permission prompts: handle FIRST - look for X, Close, Skip, Not now. Never Allow things the user did not ask for; use ask when unsure.\n" +
        "- Spinners/dropdowns: tap to open, then tap the option. Tabs: bottom bar = main sections, top tabs = sub-sections.\n" +
        "- Toasts are temporary hints, not buttons - ignore them.\n" +
        "- WebView/image/game screens may expose few ids: use look, then tap coordinates from the photo.\n" +
        "- Empty dump = image/video/game screen: look + coordinates, or scroll.\n" +
        "- Coordinates change on rotation/layout shift: always use ids from the LATEST dump. Use back/home/recents actions instead of tapping the system nav bar.\n\n" +
        "ACTIONS (exactly one per reply):\n" +
        "{\"thought\":\"...\",\"action\":\"plan\",\"plan\":\"1) ... 2) ... 3) ...\"} - first reply for tasks with 3+ steps (skip for trivial ones)\n" +
        "{\"thought\":\"...\",\"action\":\"tap_id\",\"id\":N} - tap element N (ALWAYS prefer over coordinates)\n" +
        "{\"thought\":\"...\",\"action\":\"tap\",\"x\":X,\"y\":Y} - tap raw pixel, only when no id fits\n" +
        "{\"thought\":\"...\",\"action\":\"type\",\"id\":N,\"text\":\"...\"} - set text of INPUT element N\n" +
        "{\"thought\":\"...\",\"action\":\"swipe\",\"x1\":A,\"y1\":B,\"x2\":C,\"y2\":D} - scroll down: swipe from lower screen to upper, e.g. (540,1800)->(540,600)\n" +
        "{\"thought\":\"...\",\"action\":\"back\"} | {\"action\":\"home\"} | {\"action\":\"recents\"}\n" +
        "{\"thought\":\"...\",\"action\":\"open_app\",\"package\":\"...\"} - ONLY package names from the installed list, never invent\n" +
        "{\"thought\":\"...\",\"action\":\"wait\",\"ms\":N} - wait for loading (1000-3000 after open_app or big taps)\n" +
        "{\"thought\":\"...\",\"action\":\"scroll\",\"direction\":\"up|down|left|right\"} - scroll a list/feed\n" +
        "{\"thought\":\"...\",\"action\":\"long_press_id\",\"id\":N} / {\"action\":\"long_press\",\"x\":X,\"y\":Y} - long-press (context menus)\n" +
        "{\"thought\":\"...\",\"action\":\"double_tap\",\"x\":X,\"y\":Y} / {\"action\":\"double_tap_id\",\"id\":N} - double-tap (likes, zoom)\n" +
        "{\"thought\":\"...\",\"action\":\"drag\",\"x1\":A,\"y1\":B,\"x2\":C,\"y2\":D} - press-hold then move (reorder/sliders)\n" +
        "{\"thought\":\"...\",\"action\":\"pinch\",\"mode\":\"in|out\",\"x\":X,\"y\":Y} - zoom (x,y = center; omit for screen center)\n" +
        "{\"thought\":\"...\",\"action\":\"clear\",\"id\":N} - empty an INPUT field\n" +
        "{\"thought\":\"...\",\"action\":\"notifications\"} / {\"action\":\"quick_settings\"} - pull down shade / quick tiles\n" +
        "{\"thought\":\"...\",\"action\":\"volume_up\"} / {\"action\":\"volume_down\"}\n" +
        "{\"thought\":\"...\",\"action\":\"rotate\",\"mode\":\"auto|portrait|landscape|portrait_reverse|landscape_reverse\"} - rotate the screen\n" +
        "{\"thought\":\"...\",\"action\":\"power_menu\"} / {\"action\":\"lock_screen\"}\n" +
        "{\"thought\":\"...\",\"action\":\"look\"} - need to SEE the screen (photo attached next observation; use only when the text dump is not enough)\n" +
        "{\"thought\":\"...\",\"action\":\"ask\",\"text\":\"...\"} - ask the user. REQUIRED when the task is ambiguous or a choice matters (what to send, which item, payments). Never guess for payments or personal messages.\n" +
        "{\"thought\":\"...\",\"action\":\"done\",\"text\":\"...\"} - task complete; short friendly summary in the user's language.\n\n" +
        "SPEED RULES (always on):\n" +
        "A. Act FAST and decisively - do not deliberate. If the next move is obvious (back, tap the visible button, scroll), do it NOW.\n" +
        "B. Never re-read or re-verify a screen you just read unless it changed or your action failed.\n" +
        "C. Keep \"thought\" to ONE short line. No long reasoning.\n" +
        "D. Use the fewest steps possible. Skip plan for trivial 1-2 step tasks.\n\n" +
        "RULES:\n" +
        "1. \"thought\" is MANDATORY in every reply: one short sentence - what you see and why this action. Think before acting.\n" +
        "2. Tasks with 3+ steps -> first reply is plan, then act step by step, re-checking the screen each turn.\n" +
        "3. Persistence: NEVER give up on the user's task - if something is not found, vary the approach: scroll more, use the app's search, try coordinates, go back and retry. Only stop for done, a hard error, or when truly stuck after several different attempts (then ask).\n" +
        "4. ids come from the LATEST dump only; they change every screen.\n" +
        "5. After typing into an INPUT, the submit/send button is usually a nearby BTN - tap it to confirm.\n" +
        "6. Never confirm purchases/payments yourself; use ask for every such step.\n" +
        "7. Keep acting until the task is truly done, then reply done with a summary.\n" +
        "8. Only open apps from the installed list. If the user asks for an app that is not in the list, say it is not installed (done/ask) instead of guessing a package. For apps without a guide, use standard Android patterns: search icons, bottom tabs, 3-dot menus, long-press for options.\n" +
        "9. The dump NEVER shows Agentic's own chat window, ball, or YES/NO dialogs - they are invisible to you. Never try to tap OFF/CLR/X/YES/NO; those belong to Agentic, not the target app.\n" +
        "10. If a NOTE says the app you opened is now in the foreground, that open step succeeded - continue the plan, or reply done if the task was only to open it.\n" +
        "11. If a VERIFY note says the screen did not change, your last action had no effect - never repeat the identical tap; try another element, coordinates, scroll or wait.\n" +
        "12. When the user states a durable fact, preference or standing instruction (his name, language choice, 'hamesha...', 'never...'), add \"remember\":\"<short fact, max 15 words>\" to that reply's JSON so it is remembered across sessions.\n\n" +
        "EXAMPLE (task: whatsapp pe Rahul ko 'hello' bhejo; dump shows App: com.whatsapp, #7 [INPUT] \"Message\" @300,2200, #9 [BTN] \"Send\" @1000,2200):\n" +
        "Turn 1: {\"thought\":\"Rahul ki chat khuli hai; pehle message type karta hu.\",\"action\":\"type\",\"id\":7,\"text\":\"hello\"}\n" +
        "Turn 2: {\"thought\":\"Message type ho gaya; ab Send dabata hu.\",\"action\":\"tap_id\",\"id\":9}\n" +
        "Turn 3: {\"thought\":\"Message send ho gaya, tick aa gayi.\",\"action\":\"done\",\"text\":\"Ho gaya! Rahul ko 'hello' bhej diya.\"}";
    }

    private String installedApps() {
        if (cachedApps != null && System.currentTimeMillis() - cachedAppsAt < 600000) return cachedApps;
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
        cachedAppsAt = System.currentTimeMillis();
        return cachedApps;
    }

    @Override public void run() {
        AgentService svc = AgentService.instance;
        if (svc == null) { ui.notice("Accessibility service is off - enable it first, then try again."); ui.loopEnded(); return; }
        svc.agentActive = true;
        if (moneyMode) ui.notice("Payment-related task: main har step pehle aapse confirm karunga.");
        long taskId = TaskStore.begin(ctx, instruction);
        activeTaskId = taskId;
        // Local instant commands (rules, not AI): zero API call, zero tokens.
        try {
            org.json.JSONObject quick = LocalAssist.match(instruction);
            if (quick != null) {
                String res = svc.execBlocking(quick);
                boolean ok = res.startsWith("ok");
                lastAiText = ok ? "Ho gaya." : "Ye abhi nahi ho paya - " + res;
                ui.aiMessage(lastAiText);
                TaskStore.update(ctx, taskId, ok ? TaskStore.COMPLETED : TaskStore.FAILED, res);
                svc.agentActive = false;
                remember();
                ui.loopEnded();
                return;
            }
        } catch (Throwable ignored) {}
        String taskState = TaskStore.RUNNING, taskNote = null;
        try {
            SecureStore.Prov prov = SecureStore.active(ctx);
            if (prov == null || prov.base == null || prov.model == null || prov.keyCount() == 0) {
                ui.notice("API setup missing - open the Agentic app and connect your API first.");
                ui.loopEnded();
                return;
            }
            try {
                if (prov.currentKey() == null) throw new Exception();
            } catch (Exception e) {
                ui.notice("Saved key read nahi ho payi - app kholo, provider ki key dobara daalo.");
                ui.loopEnded();
                return;
            }
            boolean visionOn = isVisionModel(prov.model);
            StringBuilder sys = new StringBuilder(systemPrompt());
            java.util.List<String> longMem = SecureStore.loadMemory(ctx);
            if (!longMem.isEmpty()) {
                sys.append("\n\nUSER MEMORY (things he told you before - respect them):\n");
                for (String f : longMem) sys.append("- ").append(f).append('\n');
            }
            JSONArray msgs = new JSONArray();
            msgs.put(new JSONObject().put("role", "system").put("content", sys.toString()));
            for (int i = 0; i < mem.length(); i++) msgs.put(mem.get(i)); // session memory
            StringBuilder task = new StringBuilder("TASK: ").append(instruction).append("\n\n").append(installedApps());
            int steps = 0, retries = 0;
            String lastResult = "(start)";
            String prevSig = null, pendingOpenPkg = null, lastAction = "";
            String prevShotSig = null, ocrCacheSig = null, ocrCache = null;
            boolean forceVision = false;
            JSONObject lastObs = null;
            String prevDims = null;
            int parseErrs = 0;
            int sameCount = 0;
            while (!stopped && steps < MAX_STEPS) {
                awaitResume();
                if (stopped) break;
                int observedPauseEpoch = pauseEpoch;
                steps++;
                String pending = ui.pollPending();
                if (pending != null)
                    msgs.put(new JSONObject().put("role", "user").put("content",
                        "NEW MESSAGE FROM USER (steer the current task with it): " + pending));
                long t0 = System.currentTimeMillis();
                String screen = svc.dump();
                long tDump = System.currentTimeMillis() - t0;
                String sig = Integer.toHexString(screen.hashCode());
                // Speed: photo only when it can add info - vision model's first look / explicit "look",
                // or a text-poor screen (games, image feeds) where OCR is the only way to read it.
                if (screen.contains("(no interactive elements)") || screen.contains("(screen unavailable)"))
                    forceVision = true; // tree is blind here - a photo is the only way forward
                String dims = "";
                if (screen.startsWith("App: ")) {
                    int i = screen.indexOf("Screen "), j = i >= 0 ? screen.indexOf('\n', i) : -1;
                    if (j > i) dims = screen.substring(i + 7, j);
                }
                if (prevDims != null && !dims.equals(prevDims)) { prevShotSig = null; ocrCache = null; ocrCacheSig = null; } // rotation/resize: old captures invalid
                prevDims = dims;
                boolean sparse = screen.length() < 900;
                boolean wantShot = (visionOn && (steps == 1 || forceVision)) || sparse;
                forceVision = false;
                Bitmap shot = null;
                long tShot = 0, tOcr = 0;
                if (sig.equals(prevShotSig) || sig.equals(prevSig)) {
                    // unchanged screen: reuse cached OCR, no new capture
                    if (ocrCache != null && sig.equals(ocrCacheSig)) screen += ocrCache;
                } else if (wantShot) {
                    long ts = System.currentTimeMillis();
                    shot = svc.screenshotBitmap();
                    tShot = System.currentTimeMillis() - ts;
                    if (shot != null) {
                        prevShotSig = sig;
                        try { svc.updatePreview(shot); } catch (RuntimeException previewError) { svc.clearPreview(); }
                    }
                }
                boolean sendImage = visionOn && shot != null;
                if (shot != null && !sendImage) {
                    long to = System.currentTimeMillis();
                    String ocr = Ocr.recognize(shot);
                    tOcr = System.currentTimeMillis() - to;
                    if (ocr != null && ocr.length() > 0) {
                        String block = "OCR TEXT VISIBLE ON SCREEN (text inside images/web the tree missed):\n" + ocr;
                        screen += block;
                        ocrCache = block; ocrCacheSig = sig;
                    }
                }
                String fg = foregroundOf(screen);
                if (!fg.equals(lastGuidedPkg)) {
                    String guide = AppKnowledge.guideFor(fg);
                    if (guide != null) {
                        msgs.put(new JSONObject().put("role", "user").put("content",
                            "APP GUIDE for the app now in foreground (" + fg + "):\n" + guide));
                        lastGuidedPkg = fg;
                    }
                }
                StringBuilder note = new StringBuilder();
                if (pendingOpenPkg != null) {
                    if (pendingOpenPkg.equals(fg)) {
                        note.append("\nNOTE: ").append(pendingOpenPkg).append(" is now open in the foreground - the open step succeeded.");
                        lastResult += " | verified foreground: " + fg;
                        ui.notice("App khul gayi.");
                    } else {
                        note.append("\nNOTE: ").append(pendingOpenPkg).append(" is NOT in the foreground yet (current foreground: ").append(fg).append(").");
                    }
                    pendingOpenPkg = null;
                }
                if (sig.equals(prevSig)) {
                    sameCount++;
                    note.append("\nVERIFY: the screen did NOT change after your last action (").append(lastAction)
                        .append(") - it had no visible effect; do not repeat it.");
                    if (sameCount == 2)
                        note.append("\nSTUCK-WARNING: screen unchanged for 2 steps. CHANGE STRATEGY NOW: use the app's search, scroll, a different element, or coordinates - never repeat the same tap.");
                    if (sameCount == 4)
                        note.append("\nSTUCK-CRITICAL: screen unchanged for 4 steps. Try a completely different route (back out, search bar, recents) - keep going, do not stop.");
                } else sameCount = 0;
                prevSig = sig;
                if (sameCount >= 6) {
                    lastAiText = "Main kahin atak gaya hoon - screen kai steps se same hai. Thoda guide karo (kya dikh raha hai?) ya naya instruction do.";
                    taskState = TaskStore.PAUSED; taskNote = "stuck";
                    ui.aiMessage(lastAiText);
                    break;
                }
                String userMsg = task.toString() + "\n\nSCREEN:\n" + screen + "\nLAST RESULT: " + lastResult + note +
                        (moneyMode ? "\nREMINDER: money-related - confirm steps with the user via ask." : "");
                task.setLength(0);
                if (lastObs != null) { // drop the previous screen dump - it is stale
                    for (int i = msgs.length() - 1; i > 0; i--)
                        if (msgs.get(i) == lastObs) { msgs.remove(i); break; }
                }
                JSONObject um = new JSONObject().put("role", "user");
                String shot64 = sendImage ? Ocr.jpeg64(shot) : null;
                if (shot != null) shot.recycle();
                if (shot64 != null) {
                    JSONArray parts = new JSONArray();
                    parts.put(new JSONObject().put("type", "text").put("text", userMsg));
                    parts.put(new JSONObject().put("type", "image_url").put("image_url",
                        new JSONObject().put("url", "data:image/jpeg;base64," + shot64)));
                    um.put("content", parts);
                } else um.put("content", userMsg);
                msgs.put(um);
                lastObs = um;
                ensureBudget(msgs);
                ui.thinking(true);
                long tApi = System.currentTimeMillis();
                String reply;
                try {
                    try {
                        reply = callWithRetry(prov, msgs);
                    } catch (Api.VisionRejected vr) {
                        visionOn = false;
                        ui.systemMessage("Ye model photos nahi dekh sakta - text se kaam continue...");
                        JSONObject last = msgs.getJSONObject(msgs.length() - 1);
                        last.put("content", userMsg); // resend text-only
                        reply = callWithRetry(prov, msgs);
                    }
                } catch (Api.ApiError primary) {
                    SecureStore.Prov fb = SecureStore.fallback(ctx);
                    if (fb != null && fb.index != prov.index && fb.keyCount() > 0 && fb.model != null && fb.base != null) {
                        ui.systemMessage("Primary provider fail hua - backup (" + fb.name + ") se continue...");
                        prov = fb; // task state (msgs) carries over - nothing is lost
                        visionOn = isVisionModel(fb.model);
                        reply = callWithRetry(fb, msgs);
                    } else throw primary;
                } finally { ui.thinking(false); }
                LogStore.addTokens(ctx, estTokens(msgs));
                ui.systemMessage("step " + steps + ": screen " + tDump + "ms"
                    + (tShot > 0 ? ", photo " + tShot + "ms" : "")
                    + (tOcr > 0 ? ", ocr " + tOcr + "ms" : "")
                    + ", AI " + ((System.currentTimeMillis() - tApi) / 1000f) + "s");
                if (stopped) break;
                if (paused || MainActivity.foreground || observedPauseEpoch != pauseEpoch) {
                    awaitResume();
                    if (stopped) break;
                    // Re-observe after a user pause, never execute a pre-pause target.
                    lastResult = "User paused and resumed. Observe the current screen again.";
                    continue;
                }
                msgs.put(new JSONObject().put("role", "assistant").put("content", reply));
                JSONObject a = parse(reply);
                if (a == null) {
                    parseErrs++;
                    lastResult = "error: reply was not one JSON action. Reply with exactly one raw JSON object."
                        + (parseErrs >= 2 ? " HARD RULE: your reply must START with { and END with } - no text before or after." : "");
                    if (parseErrs >= 4) {
                        taskState = TaskStore.FAILED; taskNote = "Model ne 4 baar invalid reply diya";
                        ui.aiMessage("Ye model instructions follow nahi kar pa raha - Settings me doosra model ya provider try karo.");
                        break;
                    }
                    continue;
                }
                parseErrs = 0;
                String rem = a.optString("remember", "");
                if (rem.length() > 2) SecureStore.addMemory(ctx, rem);
                String thought = a.optString("thought", "");
                if (thought.length() > 0 && !thought.equals(streamedThought[0])) ui.systemMessage(thought);
                streamedThought[0] = null;
                String act = a.optString("action");
                String openedPkg = null;
                if ("done".equals(act)) { lastAiText = a.optString("text", "Done."); taskState = TaskStore.COMPLETED; taskNote = lastAiText; ui.aiMessage(lastAiText); break; }
                if ("plan".equals(act)) {
                    String plan = a.optString("plan", "");
                    if (plan.length() > 0) ui.aiMessage("Plan:\n" + plan);
                    lastResult = "plan noted; now execute step 1.";
                    continue;
                }
                if ("look".equals(act)) {
                    forceVision = true;
                    lastResult = "ok: fresh photo attached to this observation.";
                    continue;
                }
                if ("ask".equals(act)) {
                    lastAiText = a.optString("text", "The AI is asking:");
                    taskState = TaskStore.WAITING; taskNote = lastAiText;
                    ui.aiMessage(lastAiText);
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
                    openedPkg = pkg;
                }
                if (moneyMode || paymentApp(fg)) {
                    Boolean ok = askSync("Step " + steps + " allow kare?\n" + describe(a));
                    if (stopped) break;
                    if (ok == null || !ok) { lastResult = "user DENIED this step. Use ask or done now."; continue; }
                } else {
                    int risk = actionRisk(a, svc, taskRisk);
                    if (risk == 2) {
                        Boolean ok = askSync("SAVDHAN - AI ye karna chahta hai:\n" + describe(a) + "\nAllow? (har baar poochhunga)");
                        if (stopped) break;
                        if (ok == null || !ok) { lastResult = "user DENIED this action (" + describe(a) + "). Do NOT retry it - use ask or done."; continue; }
                    } else if (risk == 1 && !mediumApproved) {
                        Boolean ok = askSync("Is task me AI likhne/bhejne jaisa kaam karega - pehla step:\n" + describe(a) + "\nAllow? (is task ke liye ek baar)");
                        if (stopped) break;
                        if (ok == null || !ok) { lastResult = "user DENIED this kind of action. Use ask or done now."; continue; }
                        mediumApproved = true;
                    }
                }
                if (paused || MainActivity.foreground || observedPauseEpoch != pauseEpoch) {
                    awaitResume();
                    if (stopped) break;
                    lastResult = "User paused. Re-observe before acting.";
                    continue;
                }
                String actLine = actionLine(a);
                if (actLine != null) ui.systemMessage(actLine); // visible BEFORE the action executes
                ui.thinking(true);
                long tExec = System.currentTimeMillis();
                try { lastResult = svc.execBlocking(a); }
                finally { ui.thinking(false); }
                LogStore.log(ctx, taskId, steps, describe(a), lastResult, System.currentTimeMillis() - tExec);
                lastAction = describe(a);
                if (openedPkg != null && !lastResult.startsWith("error")) pendingOpenPkg = openedPkg;
                if (lastResult.startsWith("error")) {
                    retries++;
                    if (retries >= 4) { lastResult += " | hint: try a different approach (scroll, coordinates, or ask the user)."; }
                } else retries = 0;
                int wait = "wait".equals(act) ? Math.min(a.optInt("ms", 1000), 10000) : 150;
                try { Thread.sleep(wait); } catch (InterruptedException ignored) { break; }
            }
            if (!stopped && steps >= MAX_STEPS) {
                taskState = TaskStore.PAUSED; taskNote = "step limit";
                ui.aiMessage("Bahut lamba task hai - ruk gaya hoon beech me. 'continue' bolo to wahi se aage badhunga, ya naya instruction do.");
            }
        } catch (InterruptedException e) {
            taskState = TaskStore.CANCELLED; taskNote = "Stopped";
        } catch (Api.ApiError e) {
            taskState = TaskStore.FAILED; taskNote = e.getMessage();
            LogStore.log(ctx, taskId, -1, "api_error", e.getMessage() != null ? e.getMessage() : "error", 0);
            ui.notice(e.getMessage());
        } catch (Throwable e) {
            taskState = TaskStore.FAILED; taskNote = "error";
            ui.notice("Agent me error aaya - agent rok diya gaya, ball/window chalte rahenge. Dobara try karo.");
        } finally {
            if (stopped) { taskState = TaskStore.CANCELLED; taskNote = "OFF"; }
            TaskStore.update(ctx, taskId, taskState, taskNote);
            svc.agentActive = false;
            remember();
            ui.loopEnded();
        }
    }

    private String lastGuidedPkg = "";

    private static String actionLine(JSONObject a) {
        String act = a.optString("action");
        switch (act) {
            case "open_app": return "App khol raha hoon…";
            case "tap": case "tap_id": return "Tap kar raha hoon…";
            case "long_press": case "long_press_id": return "Long-press kar raha hoon…";
            case "double_tap": case "double_tap_id": return "Double-tap kar raha hoon…";
            case "drag": return "Drag kar raha hoon…";
            case "pinch": return "Zoom kar raha hoon…";
            case "clear": return "Field saaf kar raha hoon…";
            case "type": return "Likh raha hoon…";
            case "swipe": case "scroll": return "Scroll kar raha hoon…";
            case "back": return "Back ja raha hoon…";
            case "home": return "Home ja raha hoon…";
            case "recents": return "Recent apps khol raha hoon…";
            default: return null; // wait/rotate/volume/etc: no extra line
        }
    }

    private static String foregroundOf(String screen) {
        if (screen != null && screen.startsWith("App: ")) {
            int nl = screen.indexOf('\n');
            return (nl > 5 ? screen.substring(5, nl) : screen.substring(5)).trim();
        }
        return "";
    }

    private static String describe(JSONObject a) {
        String act = a.optString("action");
        switch (act) {
            case "tap": return "Tap at " + a.optInt("x") + "," + a.optInt("y");
            case "tap_id": return "Tap element #" + a.optInt("id");
            case "type": return "Type \"" + a.optString("text") + "\" into element #" + a.optInt("id");
            case "swipe": return "Swipe " + a.optInt("x1") + "," + a.optInt("y1") + " -> " + a.optInt("x2") + "," + a.optInt("y2");
            case "open_app": return "Open app " + a.optString("package");
            case "double_tap_id": return "Double-tap element #" + a.optInt("id");
            case "clear": return "Clear field #" + a.optInt("id");
            case "pinch": return "Pinch " + a.optString("mode", "in");
            default: return act;
        }
    }

    /** Task-level risk from the instruction text. Payments are already covered by moneyMode (every-step). */
    private static int classifyRisk(String instr) {
        String t = instr.toLowerCase(java.util.Locale.ROOT);
        if (t.matches(".*(pay\\b|payment|paise|paisa|upi|transfer|khareed|buy|purchase|order kar|book kar|delete|call kar|dial).*")) return 2;
        if (t.matches(".*(send|bhej|message|msg|post|share|upload|form|fill|register|signup|sign up|copy|subscribe|comment|follow kar|like kar).*")) return 1;
        return 0;
    }

    /** Payment-app foreground: same every-step protection as a payment task. */
    private static boolean paymentApp(String fg) {
        if (fg == null) return false;
        String p = fg.toLowerCase(java.util.Locale.ROOT);
        return p.contains("paytm") || p.contains("phonepe") || p.contains("paisa") || p.contains("bhim") || p.contains("mobikwik");
    }

    private static final String[] HIGH_LABELS = {"send", "bhejo", "bhej", "post", "share", "submit", "pay", "buy now",
        "purchase", "place order", "confirm order", "transfer", "delete", "call", "book now", "donate", "pay now"};

    /** Per-action risk: HIGH labels confirm every time; typing in a risky task confirms once. */
    private static int actionRisk(org.json.JSONObject a, AgentService svc, int taskRisk) {
        String act = a.optString("action");
        if (act == null) return 0;
        switch (act) {
            case "done": case "ask": case "plan": case "look": case "wait": case "scroll": case "swipe":
            case "back": case "home": case "recents": case "open_app": case "notifications": case "quick_settings":
            case "volume_up": case "volume_down": case "rotate": case "power_menu":
                return 0;
            case "tap_id": case "long_press_id": case "double_tap_id": case "clear": {
                String lbl = svc.nodeLabel(a.optInt("id"));
                for (String h : HIGH_LABELS) if (lbl.contains(h)) return 2;
                return taskRisk == 2 ? 1 : 0;
            }
            case "tap": case "long_press": case "double_tap": case "drag": case "pinch":
                return taskRisk == 2 ? 1 : 0;
            case "type":
                return taskRisk >= 1 ? 1 : 0;
            default:
                return 0;
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

    /** Persist this instruction + outcome into session memory (bounded). */
    private void remember() {
        try {
            if (lastAiText.isEmpty()) return;
            mem.put(new JSONObject().put("role", "user").put("content", instruction));
            mem.put(new JSONObject().put("role", "assistant").put("content", lastAiText));
            while (mem.length() > 12) mem.remove(0);
        } catch (Exception ignored) {}
    }

    private static JSONObject parse(String reply) {
        try {
            int s = reply.indexOf('{'), e = reply.lastIndexOf('}');
            if (s < 0 || e <= s) return null;
            return new JSONObject(reply.substring(s, e + 1));
        } catch (Exception e) { return null; }
    }
}
