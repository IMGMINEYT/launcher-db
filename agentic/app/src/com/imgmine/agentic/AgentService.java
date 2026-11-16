package com.imgmine.agentic;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
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
        nodes.clear();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "(screen unavailable)";
        StringBuilder sb = new StringBuilder();
        int[] sz = screenSize();
        sb.append("Screen ").append(sz[0]).append("x").append(sz[1]).append('\n');
        int[] count = {0};
        walk(root, sb, count);
        if (count[0] == 0) return sb.append("(no interactive elements)\n").toString();
        return sb.toString();
    }

    private void walk(AccessibilityNodeInfo n, StringBuilder sb, int[] count) {
        if (n == null || count[0] >= 140) return;
        CharSequence t = n.getText(), d = n.getContentDescription();
        boolean clickable = n.isClickable(), editable = n.isEditable(), scrollable = n.isScrollable();
        if ((clickable || editable || scrollable || (t != null && t.length() > 0) || (d != null && d.length() > 0)) && n.isVisibleToUser()) {
            count[0]++;
            int id = count[0];
            nodes.put(id, n);
            Rect r = new Rect(); n.getBoundsInScreen(r);
            String cls = n.getClassName() != null ? n.getClassName().toString() : "?";
            int dot = cls.lastIndexOf('.'); if (dot >= 0) cls = cls.substring(dot + 1);
            sb.append('#').append(id).append(" [").append(cls).append(']');
            if (t != null && t.length() > 0) sb.append(" \"").append(cut(t.toString(), 60)).append('"');
            if (d != null && d.length() > 0) sb.append(" (").append(cut(d.toString(), 40)).append(')');
            sb.append(" @").append(r.centerX()).append(',').append(r.centerY());
            if (clickable) sb.append(" clickable");
            if (editable) sb.append(" editable");
            if (scrollable) sb.append(" scrollable");
            sb.append('\n');
        }
        for (int i = 0; i < n.getChildCount() && count[0] < 140; i++) walk(n.getChild(i), sb, count);
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
                    return n.performAction(AccessibilityNodeInfo.ACTION_CLICK) ? "ok: tapped #" + a.getInt("id") : tapCenter(n);
                }
                case "type": {
                    AccessibilityNodeInfo n = nodes.get(a.getInt("id"));
                    if (n == null) return "error: no such element id (screen changed)";
                    Bundle b = new Bundle();
                    b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, a.getString("text"));
                    n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                    return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b) ? "ok: typed into #" + a.getInt("id") : "error: could not set text";
                }
                case "swipe": return swipe(a.getInt("x1"), a.getInt("y1"), a.getInt("x2"), a.getInt("y2"), a.optInt("ms", 350));
                case "back": return performGlobalAction(GLOBAL_ACTION_BACK) ? "ok: back" : "error: back failed";
                case "home": return performGlobalAction(GLOBAL_ACTION_HOME) ? "ok: home" : "error: home failed";
                case "recents": return performGlobalAction(GLOBAL_ACTION_RECENTS) ? "ok: recents" : "error: recents failed";
                case "wait": return "ok: waited";
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

    private String tap(int x, int y) {
        Path p = new Path(); p.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 80)).build();
        return dispatchGesture(g, null, null) ? "ok: tapped " + x + "," + y : "error: tap gesture rejected";
    }

    private String swipe(int x1, int y1, int x2, int y2, int ms) {
        Path p = new Path(); p.moveTo(x1, y1); p.lineTo(x2, y2);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, Math.max(50, ms))).build();
        return dispatchGesture(g, null, null) ? "ok: swiped" : "error: swipe gesture rejected";
    }
}
