package com.imgmine.agentic;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityWindowInfo;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Build;
import android.provider.Settings;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Accessibility backbone: screen dumps + gestures. All actions run through exec() on the main thread. */
public class AgentService extends AccessibilityService {
    public static volatile AgentService instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Map<Integer, AccessibilityNodeInfo> nodes = new HashMap<>();
    public volatile long lastEventAt = 0;
    public volatile boolean agentActive = false;
    public volatile android.graphics.Bitmap lastShot; // last agent-step screenshot (for Live preview)

    @Override public void onServiceConnected() { instance = this; }
    // ---- live screen state: refreshed on accessibility events (debounced), invisible to user ----
    private volatile String liveDump;
    private volatile long liveAt;
    private final Runnable liveRefresh = new Runnable() { @Override public void run() {
        try { liveDump = buildDump(); liveAt = System.currentTimeMillis(); } catch (Throwable ignored) {}
    } };

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        lastEventAt = System.currentTimeMillis();
        main.removeCallbacks(liveRefresh);
        main.postDelayed(liveRefresh, agentActive ? 200 : 400); // debounced: fast while the agent runs, gentle when idle
    }
    @Override public void onInterrupt() {}
    @Override public void onDestroy() { instance = null; super.onDestroy(); }
    @Override public boolean onUnbind(Intent i) { instance = null; return super.onUnbind(i); }

    public int[] screenSize() {
        DisplayMetrics m = new DisplayMetrics();
        ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(m);
        return new int[]{ m.widthPixels, m.heightPixels };
    }

    public String currentApp() {
        // Do not rebuild the node-id map while a pending action may use it.
        AccessibilityNodeInfo r = pickRoot();
        return r != null && r.getPackageName() != null ? r.getPackageName().toString() : "";
    }
    public volatile long lastShotAt;
    public void updatePreview(Bitmap shot) {
        if (shot == null) return;
        int w = Math.min(480, shot.getWidth());
        int h = Math.max(1, Math.round(shot.getHeight() * (w / (float)shot.getWidth())));
        lastShot = Bitmap.createScaledBitmap(shot, w, h, true).copy(Bitmap.Config.RGB_565, false);
        lastShotAt = System.currentTimeMillis();
    }
    public void clearPreview() { lastShot = null; lastShotAt = 0; }

    /** Live dump: fresh build while the agent runs (change detection + valid ids need it), cached when idle. */
    public String dump() {
        if (!agentActive && liveDump != null && System.currentTimeMillis() - liveAt < 1500) return liveDump;
        String d = buildDump();
        liveDump = d; liveAt = System.currentTimeMillis();
        return d;
    }

    /** Full-screen screenshot (API 30+), overlay hidden during capture. Null when unavailable. */
    public Bitmap screenshotBitmap() {
        if (Build.VERSION.SDK_INT < 30) return null;
        OverlayService os = OverlayService.instance;
        if (os != null) os.setUiVisible(false);
        try { Thread.sleep(110); } catch (InterruptedException ie) { if (os != null) os.setUiVisible(true); return null; }
        final CountDownLatch latch = new CountDownLatch(1);
        final Bitmap[] out = {null};
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult r) {
                    try {
                        Bitmap hw = Bitmap.wrapHardwareBuffer(r.getHardwareBuffer(), r.getColorSpace());
                        r.getHardwareBuffer().close();
                        if (hw != null) out[0] = hw.copy(Bitmap.Config.ARGB_8888, false);
                    } catch (Throwable ignored) {}
                    latch.countDown();
                }
                @Override public void onFailure(int i) { latch.countDown(); }
            });
            latch.await(4, TimeUnit.SECONDS);
        } catch (Throwable ignored) {}
        if (os != null) os.setUiVisible(true);
        return out[0];
    }

    /** Compact dump of the interactive screen. ids are valid until the next dump. */
    public synchronized String buildDump() {
        try {
            nodes.clear();
            AccessibilityNodeInfo root = pickRoot();
            if (root == null) return "(screen unavailable)";
            StringBuilder sb = new StringBuilder();
            int[] sz = screenSize();
            sb.append("App: ").append(root.getPackageName() != null ? root.getPackageName() : "?").append('\n');
            sb.append("Screen ").append(sz[0]).append("x").append(sz[1]).append('\n');
            int[] count = {0};
            walk(root, sb, count, 0);
            if (count[0] == 0) return sb.append("(no interactive elements)\n").toString();
            return sb.toString();
        } catch (Throwable t) {
            return "(screen read error: " + t.getClass().getSimpleName() + ")";
        }
    }

    /** Best foreground root, excluding our own windows so the agent never sees its own UI. */
    private AccessibilityNodeInfo pickRoot() {
        String self = getPackageName();
        try {
            List<AccessibilityWindowInfo> wins = getWindows();
            AccessibilityNodeInfo appFallback = null, anyFallback = null;
            for (AccessibilityWindowInfo w : wins) {
                if (w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null) continue;
                CharSequence p = r.getPackageName();
                if (p != null && self.contentEquals(p)) continue;
                boolean app = w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION;
                if (app && (w.isActive() || w.isFocused())) return r;
                if (app && appFallback == null) appFallback = r;
                if (anyFallback == null) anyFallback = r;
            }
            if (appFallback != null) return appFallback;
            if (anyFallback != null) return anyFallback;
        } catch (Throwable ignored) {}
        AccessibilityNodeInfo r = getRootInActiveWindow();
        if (r != null && r.getPackageName() != null && self.contentEquals(r.getPackageName())) return null;
        return r;
    }

    private void walk(AccessibilityNodeInfo n, StringBuilder sb, int[] count, int depth) {
        if (n == null || count[0] >= 60 || depth > 25) return;
        CharSequence pk = n.getPackageName();
        if (pk != null && getPackageName().contentEquals(pk)) return; // never expose our own UI
        CharSequence t = n.getText(), d = n.getContentDescription();
        if (t != null && n.isPassword()) t = "\u2022\u2022\u2022"; // never expose typed secrets
        CharSequence hint = Build.VERSION.SDK_INT >= 26 ? n.getHintText() : null;
        String rid = null;
        try {
            String full = n.getViewIdResourceName();
            if (full != null) { int sl = full.lastIndexOf('/'); rid = full.substring(sl + 1); }
        } catch (Throwable ignored) {}
        boolean clickable = n.isClickable(), editable = n.isEditable(), scrollable = n.isScrollable();
        if ((clickable || editable || scrollable || (t != null && t.length() > 0) || (d != null && d.length() > 0)
                || (hint != null && hint.length() > 0)) && n.isVisibleToUser()) {
            count[0]++;
            int id = count[0];
            nodes.put(id, n);
            Rect r = new Rect(); n.getBoundsInScreen(r);
            String cls = n.getClassName() != null ? n.getClassName().toString() : "";
            String role;
            if (editable) role = "INPUT";
            else if (cls.contains("Button")) role = "BTN";
            else if (scrollable || cls.contains("RecyclerView") || cls.contains("ListView") || cls.contains("ScrollView")) role = "LIST";
            else if (cls.contains("Image")) role = "IMG";
            else if (clickable) role = "BTN";
            else role = "TXT";
            sb.append('#').append(id).append(" [").append(role).append(']');
            if (t != null && t.length() > 0) sb.append(" \"").append(cut(t.toString(), 40)).append('"');
            if (d != null && d.length() > 0) sb.append(" (").append(cut(d.toString(), 30)).append(')');
            if (hint != null && hint.length() > 0) sb.append(" hint:").append(cut(hint.toString(), 30));
            if (rid != null && rid.length() > 0) sb.append(" id:").append(cut(rid, 20));
            sb.append(" @").append(r.centerX()).append(',').append(r.centerY());
            if (n.isFocused()) sb.append(" focused");
            if (n.isSelected()) sb.append(" selected");
            if (n.isChecked()) sb.append(" checked");
            if (!n.isEnabled()) sb.append(" disabled");
            sb.append('\n');
        }
        for (int i = 0; i < n.getChildCount() && count[0] < 60; i++) walk(n.getChild(i), sb, count, depth + 1);
    }

    private static String cut(String s, int max) {
        s = s.replace('\n', ' ').trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    public interface ResultCb { void onResult(String res); }

    /** Run an action JSON on the main thread; callback with a short result string. */
    public void exec(org.json.JSONObject a, ResultCb cb) {
        main.post(() -> cb.onResult(execSync(a)));
    }

    /** Run an action JSON synchronously (call from a worker thread; blocks up to 8s). */
    public String execBlocking(org.json.JSONObject a) {
        final String[] out = {"error: timeout"};
        CountDownLatch l = new CountDownLatch(1);
        exec(a, res -> { out[0] = res; l.countDown(); });
        try { l.await(8, TimeUnit.SECONDS); } catch (InterruptedException e) { return "error: interrupted"; }
        return out[0];
    }

    private String execSync(org.json.JSONObject a) {
        try {
            String act = a.optString("action");
            switch (act) {
                case "tap": return tap(a.getInt("x"), a.getInt("y"));
                case "tap_id": {
                    AccessibilityNodeInfo n = nodes.get(a.getInt("id"));
                    if (n == null) return "error: no such element id (screen changed)";
                    return clickNode(n, a.getInt("id"));
                }
                case "type": {
                    AccessibilityNodeInfo n = nodes.get(a.getInt("id"));
                    if (n == null) return "error: no such element id (screen changed)";
                    String text = a.getString("text");
                    Bundle b = new Bundle();
                    b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                    n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                    if (n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) return "ok: typed into #" + a.getInt("id");
                    // fallback: clipboard paste (WebViews and fields that reject SET_TEXT)
                    try {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("agentic", text));
                        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                        if (n.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return "ok: pasted into #" + a.getInt("id");
                    } catch (Exception ignored) {}
                    return "error: could not set text (field blocked both methods)";
                }
                case "swipe": return swipe(a.getInt("x1"), a.getInt("y1"), a.getInt("x2"), a.getInt("y2"), a.optInt("ms", 350));
                case "back": return performGlobalAction(GLOBAL_ACTION_BACK) ? "ok: back" : "error: back failed";
                case "home": return performGlobalAction(GLOBAL_ACTION_HOME) ? "ok: home" : "error: home failed";
                case "recents": return performGlobalAction(GLOBAL_ACTION_RECENTS) ? "ok: recents" : "error: recents failed";
                case "wait": return "ok: waited";
                case "double_tap": return doubleTap(a.getInt("x"), a.getInt("y"));
                case "double_tap_id": {
                    AccessibilityNodeInfo n = nodes.get(a.getInt("id"));
                    if (n == null) return "error: no such element id (screen changed)";
                    Rect r = new Rect(); n.getBoundsInScreen(r);
                    return doubleTap(r.centerX(), r.centerY());
                }
                case "drag": return drag(a.getInt("x1"), a.getInt("y1"), a.getInt("x2"), a.getInt("y2"));
                case "pinch": {
                    int[] sz = screenSize();
                    return pinch("out".equals(a.optString("mode", "in")),
                        a.has("x") ? a.getInt("x") : sz[0] / 2, a.has("y") ? a.getInt("y") : sz[1] / 2);
                }
                case "clear": {
                    AccessibilityNodeInfo n = nodes.get(a.getInt("id"));
                    if (n == null) return "error: no such element id (screen changed)";
                    Bundle b = new Bundle();
                    b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "");
                    n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                    return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
                        ? "ok: cleared #" + a.getInt("id") : "error: could not clear field";
                }
                case "long_press_id": {
                    AccessibilityNodeInfo n = nodes.get(a.getInt("id"));
                    if (n == null) return "error: no such element id (screen changed)";
                    return n.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) ? "ok: long-pressed #" + a.getInt("id") : longPressCenter(n);
                }
                case "long_press": return longPress(a.getInt("x"), a.getInt("y"));
                case "scroll": {
                    String dir = a.optString("direction", "down");
                    int nodeAction = "up".equals(dir) ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
                    if ("left".equals(dir) || "right".equals(dir)) {
                        int[] sz = screenSize();
                        int cy = sz[1] / 2;
                        return "left".equals(dir) ? swipe(sz[0] * 4 / 5, cy, sz[0] / 5, cy, 300) : swipe(sz[0] / 5, cy, sz[0] * 4 / 5, cy, 300);
                    }
                    java.util.List<AccessibilityNodeInfo> list = new java.util.ArrayList<>(nodes.values());
                    for (int i = list.size() - 1; i >= 0; i--) {
                        AccessibilityNodeInfo n = list.get(i);
                        if (n != null && n.isScrollable() && n.isVisibleToUser() && n.performAction(nodeAction))
                            return "ok: scrolled " + dir;
                    }
                    int[] sz = screenSize();
                    return "up".equals(dir) ? swipe(sz[0] / 2, sz[1] / 3, sz[0] / 2, sz[1] * 3 / 4, 350)
                                            : swipe(sz[0] / 2, sz[1] * 3 / 4, sz[0] / 2, sz[1] / 3, 350);
                }
                case "notifications": return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) ? "ok: notification shade" : "error: failed";
                case "quick_settings": return performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS) ? "ok: quick settings" : "error: failed";
                case "power_menu": return performGlobalAction(GLOBAL_ACTION_POWER_DIALOG) ? "ok: power menu" : "error: failed";
                case "lock_screen":
                    if (Build.VERSION.SDK_INT >= 28)
                        return performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) ? "ok: locked" : "error: failed";
                    return "error: needs Android 9+";
                case "volume_up": return volume(AudioManager.ADJUST_RAISE);
                case "volume_down": return volume(AudioManager.ADJUST_LOWER);
                case "rotate": {
                    if (!Settings.System.canWrite(this)) return "error: Modify Settings permission off - user must enable it in the Agentic app";
                    String mode = a.optString("mode", "auto");
                    try {
                        if ("auto".equals(mode)) {
                            Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 1);
                            return "ok: auto-rotate on";
                        }
                        Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0);
                        int rot = "landscape".equals(mode) ? 1 : "landscape_reverse".equals(mode) ? 3 : "portrait_reverse".equals(mode) ? 2 : 0;
                        Settings.System.putInt(getContentResolver(), Settings.System.USER_ROTATION, rot);
                        return "ok: rotated " + mode;
                    } catch (Exception e) { return "error: " + e.getMessage(); }
                }
                case "open_app": {
                    String pkg = a.getString("package");
                    Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                    if (i == null) return "error: app not installed: " + pkg;
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    return "ok: opened " + pkg;
                }
                default: return "error: unknown action '" + act + "'";
            }
        } catch (Exception e) {
            return "error: " + e.getClass().getSimpleName() + " " + (e.getMessage() != null ? e.getMessage() : "");
        }
    }

    private String tapCenter(AccessibilityNodeInfo n) {
        Rect r = new Rect(); n.getBoundsInScreen(r);
        return tap(r.centerX(), r.centerY());
    }

    private String longPressCenter(AccessibilityNodeInfo n) {
        Rect r = new Rect(); n.getBoundsInScreen(r);
        return longPress(r.centerX(), r.centerY());
    }

    private String longPress(int x, int y) {
        x = clampX(x); y = clampY(y);
        Path p = new Path(); p.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 700)).build();
        return dispatchGesture(g, null, null) ? "ok: long-pressed " + x + "," + y : "error: long-press rejected";
    }

    private String volume(int dir) {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI);
            return "ok: volume " + (dir == AudioManager.ADJUST_RAISE ? "up" : "down");
        } catch (Exception e) { return "error: " + e.getMessage(); }
    }

    private String doubleTap(int x, int y) {
        x = clampX(x); y = clampY(y);
        Path p = new Path(); p.moveTo(x, y);
        GestureDescription.StrokeDescription s1 = new GestureDescription.StrokeDescription(p, 0, 60);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(s1).addStroke(s1.continueStroke(p, 90, 60, false)).build();
        return dispatchGesture(g, null, null) ? "ok: double-tapped " + x + "," + y : "error: double-tap rejected";
    }

    /** Press-hold (500ms) then move - for reorder handles and sliders. */
    private String drag(int x1, int y1, int x2, int y2) {
        x1 = clampX(x1); y1 = clampY(y1); x2 = clampX(x2); y2 = clampY(y2);
        Path hold = new Path(); hold.moveTo(x1, y1);
        GestureDescription.StrokeDescription s1 = new GestureDescription.StrokeDescription(hold, 0, 500, true);
        Path move = new Path(); move.moveTo(x1, y1); move.lineTo(x2, y2);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(s1.continueStroke(move, 0, 600, false)).build();
        return dispatchGesture(g, null, null) ? "ok: dragged to " + x2 + "," + y2 : "error: drag rejected";
    }

    /** Two-finger pinch centered at (x,y). zoomIn=true spreads fingers apart. */
    private String pinch(boolean zoomIn, int x, int y) {
        int[] sz = screenSize();
        x = clampX(x); y = clampY(y);
        int near = Math.min(sz[0], sz[1]) / 10, far = Math.min(sz[0], sz[1]) / 3;
        Path l = new Path(), r = new Path();
        if (zoomIn) { l.moveTo(x - near, y); l.lineTo(x - far, y); r.moveTo(x + near, y); r.lineTo(x + far, y); }
        else { l.moveTo(x - far, y); l.lineTo(x - near, y); r.moveTo(x + far, y); r.lineTo(x + near, y); }
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(l, 0, 350))
                .addStroke(new GestureDescription.StrokeDescription(r, 0, 350)).build();
        return dispatchGesture(g, null, null) ? "ok: pinch " + (zoomIn ? "spread" : "close") : "error: pinch rejected";
    }

    private int clampX(int x) { return Math.max(0, Math.min(x, screenSize()[0] - 1)); }
    private int clampY(int y) { return Math.max(0, Math.min(y, screenSize()[1] - 1)); }

    /** Node click with clickable-ancestor climb, then gesture fallback. Works in apps that block direct clicks. */
    private String clickNode(AccessibilityNodeInfo n, int id) {
        AccessibilityNodeInfo cur = n;
        for (int up = 0; cur != null && up < 6; up++) {
            if (cur.isClickable() && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                return "ok: tapped #" + id;
            AccessibilityNodeInfo parent = cur.getParent();
            if (cur != n) cur.recycle();
            cur = parent;
        }
        return tapCenter(n);
    }

    private String tap(int x, int y) {
        if (tapOnce(x, y)) return "ok: tapped " + x + "," + y;
        // local helper: one retry with adjacent coordinates before giving up
        if (tapOnce(x + 12, y + 12)) return "ok: tapped " + (x + 12) + "," + (y + 12) + " (retry)";
        return "error: tap gesture rejected";
    }

    private boolean tapOnce(int x, int y) {
        x = clampX(x); y = clampY(y);
        Path p = new Path(); p.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 80)).build();
        return dispatchGesture(g, null, null);
    }

    private String swipe(int x1, int y1, int x2, int y2, int ms) {
        ms = Math.max(50, Math.min(ms, 1500)); // clamp nonsense durations
        x1 = clampX(x1); y1 = clampY(y1); x2 = clampX(x2); y2 = clampY(y2);
        Path p = new Path(); p.moveTo(x1, y1); p.lineTo(x2, y2);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, Math.max(50, ms))).build();
        return dispatchGesture(g, null, null) ? "ok: swiped" : "error: swipe gesture rejected";
    }
}
