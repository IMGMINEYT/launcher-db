package com.imgmine.launcher67;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.*;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.*;
import android.view.animation.DecelerateInterpolator;

public class OverlayService extends Service {
    static volatile boolean running;
    private WindowManager wm;
    private BubbleView bubble;
    private WindowManager.LayoutParams blp;
    private Panel panel;
    private SharedPreferences sp;
    private int size;
    private final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean mcFg = true;
    private long lastQ;
    private int away;
    private final Runnable poll = new Runnable() {
        public void run() {
            try { tick(); } catch (Exception ignored) {}
            h.postDelayed(this, 1500);
        }
    };

    static boolean hasUsage(Context c) {
        try {
            AppOpsManager a = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            return a.checkOpNoThrow("android:get_usage_stats", android.os.Process.myUid(), c.getPackageName()) == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) { return false; }
    }

    /** Show the bubble only while Minecraft is the app on screen. Needs Usage access; without it the bubble stays. */
    private void tick() {
        if (bubble == null) return;
        if (!hasUsage(this)) { setPresent(true); return; }
        android.app.usage.UsageStatsManager u = (android.app.usage.UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
        long now = System.currentTimeMillis();
        long from = lastQ == 0 ? now - 10 * 60000L : lastQ - 2000;
        android.app.usage.UsageEvents ev = u.queryEvents(from, now);
        android.app.usage.UsageEvents.Event e = new android.app.usage.UsageEvents.Event();
        boolean seen = lastQ != 0, any = false;
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            int t = e.getEventType();
            boolean fg = t == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND;
            boolean bg = t == android.app.usage.UsageEvents.Event.MOVE_TO_BACKGROUND;
            if (!fg && !bg) continue;
            String pk = e.getPackageName();
            if (Downloader.MC.equals(pk) || McVer.PREVIEW.equals(pk)) { mcFg = fg; any = true; }
            else if (fg && !pk.equals(getPackageName()) && !pk.startsWith("com.android.systemui") && !pk.contains("inputmethod")) { mcFg = false; any = true; }
        }
        lastQ = now;
        if (mcFg) away = 0; else away++;
        setPresent(mcFg || away < 2);
    }

    private void setPresent(boolean on) {
        if (panel != null && panel.isShown()) {
            if (!on) panel.hide();
            return;
        }
        if (bubble != null) bubble.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    private final class BubbleView extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        BubbleView(Context c) { super(c); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), r = w / 2f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF000000);
            c.drawCircle(r, r, r, p);
            p.setColor(Ui.GREEN_D);
            c.drawCircle(r, r, r - Ui.dp(2), p);
            p.setColor(Ui.GREEN);
            c.drawCircle(r - Ui.dp(1), r - Ui.dp(1), r - Ui.dp(4), p);
            p.setColor(0xFF0C1A0E);
            p.setTypeface(Ui.PX);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(w * 0.42f);
            c.drawText("67", r, r + w * 0.15f, p);
        }
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        Ui.init(this);
        sp = getSharedPreferences("ui", MODE_PRIVATE);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        size = Ui.dp(44);
        startForeground(1, notification());
        panel = new Panel(this, new Runnable() { public void run() { if (bubble != null) bubble.setVisibility(View.VISIBLE); } });
        addBubble();
        running = true;
        h.postDelayed(poll, 1500);
    }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        if (i != null && "stop".equals(i.getAction())) { stopSelf(); return START_NOT_STICKY; }
        return START_STICKY;
    }

    private Notification notification() {
        String ch = "overlay";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(ch, "Floating button", NotificationManager.IMPORTANCE_LOW);
            c.setShowBadge(false);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
        }
        int fl = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, OverlayService.class).setAction("stop"), fl);
        PendingIntent open = PendingIntent.getActivity(this, 2, new Intent(this, MainActivity.class), fl);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, ch) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat).setContentTitle("67 Launcher is on")
                .setContentText("Tap the small ball over Minecraft to open addons")
                .setContentIntent(open).setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build());
        return b.build();
    }

    private DisplayMetrics real() {
        DisplayMetrics m = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(m);
        return m;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void addBubble() {
        bubble = new BubbleView(this);
        bubble.setAlpha(0.6f);
        DisplayMetrics m = real();
        blp = new WindowManager.LayoutParams(size, size,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        blp.gravity = Gravity.TOP | Gravity.START;
        blp.x = sp.getInt("bx", 0);
        blp.y = sp.getInt("by", m.heightPixels / 3);
        clamp();
        final int slop = Ui.dp(8);
        final float[] s = new float[4];
        final boolean[] drag = new boolean[1];
        bubble.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        s[0] = e.getRawX(); s[1] = e.getRawY(); s[2] = blp.x; s[3] = blp.y;
                        drag[0] = false;
                        bubble.setAlpha(1f);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - s[0], dy = e.getRawY() - s[1];
                        if (!drag[0] && Math.abs(dx) + Math.abs(dy) > slop) drag[0] = true;
                        if (drag[0]) { blp.x = (int) (s[2] + dx); blp.y = (int) (s[3] + dy); clamp(); upd(); }
                        return true;
                    case MotionEvent.ACTION_UP:
                        bubble.setAlpha(0.6f);
                        if (drag[0]) snap(); else openPanel();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        bubble.setAlpha(0.6f);
                        return true;
                }
                return false;
            }
        });
        try { wm.addView(bubble, blp); } catch (Exception e) { stopSelf(); }
    }

    private void openPanel() {
        bubble.setVisibility(View.GONE);
        panel.show();
        if (!panel.isShown()) bubble.setVisibility(View.VISIBLE);
    }

    private void upd() { try { wm.updateViewLayout(bubble, blp); } catch (Exception ignored) {} }

    private void clamp() {
        DisplayMetrics m = real();
        blp.x = Math.max(0, Math.min(m.widthPixels - size, blp.x));
        blp.y = Math.max(0, Math.min(m.heightPixels - size, blp.y));
    }

    private void snap() {
        DisplayMetrics m = real();
        final int from = blp.x, to = (blp.x + size / 2 < m.widthPixels / 2) ? 0 : m.widthPixels - size;
        ValueAnimator a = ValueAnimator.ofInt(from, to);
        a.setDuration(160);
        a.setInterpolator(new DecelerateInterpolator());
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator v) { blp.x = (Integer) v.getAnimatedValue(); upd(); }
        });
        a.start();
        sp.edit().putInt("bx", to).putInt("by", blp.y).apply();
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        if (bubble != null) { clamp(); upd(); }
        if (panel != null) panel.relayout();
    }

    @Override public void onDestroy() {
        running = false;
        h.removeCallbacks(poll);
        if (panel != null) panel.destroy();
        if (bubble != null) { try { wm.removeView(bubble); } catch (Exception ignored) {} bubble = null; }
        super.onDestroy();
    }
}
