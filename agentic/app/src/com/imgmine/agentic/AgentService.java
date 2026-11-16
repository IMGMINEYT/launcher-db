package com.imgmine.agentic;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Build;
import android.provider.Settings;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Accessibility backbone: screen dumps + gestures. All actions run through exec() on the main thread. */
public class AgentService extends AccessibilityService {
    public static volatile AgentService instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Map<Integer, AccessibilityNodeInfo> nodes = new HashMap<>();
    public volatile long lastEventAt = 0;

    @Override public void onServiceConnected() { instance = this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) { lastEventAt = System.currentTimeMillis(); }
    @Override public void onInterrupt() {}
    @Override public void onDestroy() { instance = null; super.onDestroy(); }
    @Override public boolean onUnbind(Intent i) { instance = null; return super.onUnbind(i); }

    public int[] screenSize() {
        DisplayMetrics m = new DisplayMetrics();
        ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(m);
        return new int[]{ m.widthPixels, m.heightPixels };
    }

    /** Compact dump of the interactive screen. ids are valid until the next dump. */
    public synchronized String dump() {
        try {
            nodes.clear();
            AccessibilityNodeInfo root = getRootInActiveWindow();
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

    private void walk(AccessibilityNodeInfo n, StringBuilder sb, int[] count, int depth) {
        if (n == null || count[0] >= 100 || depth > 25) return;
        CharSequence t = n.getText(), d = n.getContentDescription();
        boolean clickable = n.isClickable(), editable = n.isEditable(), scrollable = n.isScrollable();
        if ((clickable || editable || scrollable || (t != null && t.length() > 0) || (d != null && d.length() > 0)) && n.isVisibleToUser()) {
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
            if (t != null && t.length() > 0) sb.append(" \"").append(cut(t.toString(), 60)).append('"');
            if (d != null && d.length() > 0) sb.append(" (").append(cut(d.toString(), 40)).append(')');
            sb.append(" @").append(r.centerX()).append(',').append(r.centerY());
            if (n.isFocused()) sb.append(" focused");
            if (n.isSelected()) sb.append(" selected");
            sb.append('\n');
        }
        for (int i = 0; i < n.getChildCount() && count[0] < 100; i++) walk(n.getChild(i), sb, count, depth + 1);
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
        x = clampX(x); y = clampY(y);
        Path p = new Path(); p.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 80)).build();
        return dispatchGesture(g, null, null) ? "ok: tapped " + x + "," + y : "error: tap gesture rejected";
    }

    private String swipe(int x1, int y1, int x2, int y2, int ms) {
        x1 = clampX(x1); y1 = clampY(y1); x2 = clampX(x2); y2 = clampY(y2);
        Path p = new Path(); p.moveTo(x1, y1); p.lineTo(x2, y2);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, Math.max(50, ms))).build();
        return dispatchGesture(g, null, null) ? "ok: swiped" : "error: swipe gesture rejected";
    }
}
