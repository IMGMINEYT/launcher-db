package com.imgmine.agentic;

import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** Floating ball + mini chat window + agent lifecycle. Red OFF halts everything instantly. */
public class OverlayService extends Service implements AgentLoop.Ui {
    private static final int NOTIF_ID = 7;
    private WindowManager wm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private View ball, chat;
    private WindowManager.LayoutParams ballLp, chatLp;
    private LinearLayout msgBox;
    private ScrollView msgScroll;
    private EditText input;
    private Button sendBtn;
    private TextView titleView;
    private AgentLoop loop;
    private final List<String> transcript = new ArrayList<>();
    private OrientationEventListener orient;
    private BroadcastReceiver screenOff;
    private Typeface pixel;
    private int lastOrientation = -1;

    private static final int BG = 0xF214181F, PANEL = 0xFF1E242C, ACCENT = 0xFF5FD068, RED = 0xFFE53935, TEAL = 0xFF2DD4BF;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        pixel = Typeface.createFromAsset(getAssets(), "pixelify.ttf");
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try { stopAgent(); } catch (Throwable ignored) {}
            try { wm.removeViewImmediate(ball); } catch (Throwable ignored) {}
            try { wm.removeViewImmediate(chat); } catch (Throwable ignored) {}
            Process.killProcess(Process.myPid());
            System.exit(10);
        });
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("agentic", "Agentic", NotificationManager.IMPORTANCE_MIN));
        }
        screenOff = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { shutdownNow(); }
        };
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33)
                registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED);
            else
                registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        } catch (Exception ignored) {}
        orient = new OrientationEventListener(this) {
            @Override public void onOrientationChanged(int deg) {
                Display d = wm.getDefaultDisplay();
                int rot = d.getRotation();
                if (rot != lastOrientation) { lastOrientation = rot; ui.postDelayed(() -> clampAll(), 250); }
            }
        };
        if (orient.canDetectOrientation()) orient.enable();
    }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        Notification.Builder nb = android.os.Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, "agentic") : new Notification.Builder(this);
        Notification n = nb.setContentTitle("Agentic").setContentText("Floating ball active")
                .setSmallIcon(R.drawable.ic_stat).build();
        startForeground(NOTIF_ID, n);
        if (ball == null && chat == null) showBall();
        return START_NOT_STICKY;
    }

    private int[] size() {
        DisplayMetrics m = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(m);
        return new int[]{ m.widthPixels, m.heightPixels };
    }
    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    // ---------- ball ----------
    private void showBall() {
        hideChat(false);
        if (ball != null) return;
        int s = dp(56);
        ball = new View(this) {
            private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
            { ring.setColor(ACCENT); core.setColor(0xFF14181F); glyph.setColor(TEAL); }
            @Override protected void onDraw(Canvas c) {
                float w = getWidth(), r = w / 2f;
                c.drawCircle(r, r, r - dp(1), ring);
                c.drawCircle(r, r, r - dp(3), core);
                // pixel bot glyph
                float u = w / 16f;
                c.drawRect(4*u, 6*u, 12*u, 11*u, glyph);                 // visor
                glyph.setColor(0xFF14181F);
                c.drawRect(5.5f*u, 7.5f*u, 7.5f*u, 9.5f*u, glyph);       // eyes
                c.drawRect(8.5f*u, 7.5f*u, 10.5f*u, 9.5f*u, glyph);
                glyph.setColor(ACCENT);
                c.drawRect(7.25f*u, 3*u, 8.75f*u, 5*u, glyph);           // antenna
                c.drawRect(5*u, 12*u, 11*u, 13*u, glyph);                // mouth
            }
        };
        ballLp = new WindowManager.LayoutParams(s, s, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        ballLp.gravity = Gravity.TOP | Gravity.START;
        ballLp.x = size()[0] - s - dp(8); ballLp.y = size()[1] / 3;
        attachBallTouch();
        ball.setAlpha(0f);
        wm.addView(ball, ballLp);
        ball.animate().alpha(1f).setDuration(180).start();
    }

    private void attachBallTouch() {
        final Handler h = new Handler(Looper.getMainLooper());
        final Runnable longPress = () -> { shutdownKeepConfig(); };
        ball.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setTag(new float[]{ ev.getRawX(), ev.getRawY(), ballLp.x, ballLp.y });
                    v.setTag(R.id.tag_moved, Boolean.FALSE);
                    h.postDelayed(longPress, 5000);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float[] d = (float[]) v.getTag();
                    float dx = ev.getRawX() - d[0], dy = ev.getRawY() - d[1];
                    if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) {
                        v.setTag(R.id.tag_moved, Boolean.TRUE);
                        h.removeCallbacks(longPress);
                    }
                    ballLp.x = (int) (d[2] + dx); ballLp.y = (int) (d[3] + dy);
                    wm.updateViewLayout(ball, ballLp);
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    h.removeCallbacks(longPress);
                    boolean moved = Boolean.TRUE.equals(v.getTag(R.id.tag_moved));
                    if (moved) snapBall(); else openChat();
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    h.removeCallbacks(longPress);
                    return true;
            }
            return false;
        });
    }

    private void snapBall() {
        int[] s = size();
        int target = (ballLp.x + ballLp.width / 2 < s[0] / 2) ? dp(4) : s[0] - ballLp.width - dp(4);
        ValueAnimator va = ValueAnimator.ofInt(ballLp.x, target);
        va.setDuration(220);
        va.addUpdateListener(a -> { ballLp.x = (Integer) a.getAnimatedValue(); safeUpdate(ball, ballLp); });
        va.start();
    }

    private void hideBall() {
        if (ball != null) { try { wm.removeView(ball); } catch (Exception ignored) {} ball = null; }
    }

    // ---------- chat window ----------
    private void openChat() {
        if (chat != null) return;
        int[] s = size();
        int w = Math.min((int) (s[0] * 0.86), dp(340));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bgd = new GradientDrawable();
        bgd.setColor(BG); bgd.setCornerRadius(dp(14)); bgd.setStroke(dp(1), 0xFF2A313A);
        root.setBackground(bgd);
        root.setPadding(dp(8), dp(6), dp(8), dp(8));

        // header: drag handle + title + OFF + X
        LinearLayout header = new LinearLayout(this);
        titleView = new TextView(this);
        String model = SecureStore.model(this);
        titleView.setText(model != null ? model : "Agentic");
        titleView.setTextColor(TEAL); titleView.setTextSize(13); titleView.setTypeface(pixel);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(titleView, tlp);
        Button off = smallBtn("OFF", RED);
        Button x = smallBtn("X", 0xFF37414C);
        header.addView(off); header.addView(x);
        root.addView(header);
        attachChatDrag(header);

        msgScroll = new ScrollView(this);
        msgBox = new LinearLayout(this);
        msgBox.setOrientation(LinearLayout.VERTICAL);
        msgScroll.addView(msgBox);
        root.addView(msgScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout row = new LinearLayout(this);
        input = new EditText(this);
        input.setHint("Type instruction..."); input.setHintTextColor(0xFF6B7683);
        input.setTextColor(Color.WHITE); input.setTextSize(14);
        input.setBackgroundColor(PANEL); input.setPadding(dp(8), 0, dp(8), 0);
        row.addView(input, new LinearLayout.LayoutParams(0, dp(40), 1f));
        sendBtn = smallBtn(">", ACCENT);
        sendBtn.setTextColor(0xFF0B0F13);
        row.addView(sendBtn);
        root.addView(row);

        off.setOnClickListener(v -> shutdownNow());
        x.setOnClickListener(v -> { hideKeyboard(); hideChat(true); });
        sendBtn.setOnClickListener(v -> sendInput());

        chatLp = new WindowManager.LayoutParams(w, Math.min((int) (s[1] * 0.52), dp(380)),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        chatLp.gravity = Gravity.TOP | Gravity.START;
        chatLp.x = (s[0] - w) / 2; chatLp.y = s[1] / 5;
        chat = root;
        chat.setAlpha(0f); chat.setScaleX(0.9f); chat.setScaleY(0.9f);
        wm.addView(chat, chatLp);
        chat.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start();
        hideBall();
        replayTranscript();
    }

    private Button smallBtn(String t, int color) {
        Button b = new Button(this);
        b.setText(t); b.setTextSize(12); b.setTextColor(Color.WHITE);
        b.setMinWidth(0); b.setMinimumWidth(0); b.setMinHeight(0); b.setMinimumHeight(0);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color); g.setCornerRadius(dp(8));
        b.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(32));
        lp.setMargins(dp(4), 0, 0, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void attachChatDrag(View handle) {
        handle.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setTag(new float[]{ ev.getRawX(), ev.getRawY(), chatLp.x, chatLp.y });
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float[] d = (float[]) v.getTag();
                    chatLp.x = (int) (d[2] + ev.getRawX() - d[0]);
                    chatLp.y = (int) (d[3] + ev.getRawY() - d[1]);
                    safeUpdate(chat, chatLp);
                    return true;
                }
            }
            return false;
        });
    }

    private void hideChat(boolean backToBall) {
        hideKeyboard();
        if (chat != null) { try { wm.removeView(chat); } catch (Exception ignored) {} chat = null; msgBox = null; input = null; }
        if (backToBall) showBall();
    }

    private void hideKeyboard() {
        if (input != null) {
            InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            im.hideSoftInputFromWindow(input.getWindowToken(), 0);
        }
    }

    private void clampAll() {
        int[] s = size();
        if (ball != null) {
            ballLp.x = Math.max(0, Math.min(ballLp.x, s[0] - ballLp.width));
            ballLp.y = Math.max(0, Math.min(ballLp.y, s[1] - ballLp.height));
            safeUpdate(ball, ballLp);
        }
        if (chat != null) {
            chatLp.x = Math.max(0, Math.min(chatLp.x, s[0] - dp(80)));
            chatLp.y = Math.max(0, Math.min(chatLp.y, s[1] - dp(120)));
            safeUpdate(chat, chatLp);
        }
    }

    private void safeUpdate(View v, WindowManager.LayoutParams lp) {
        try { wm.updateViewLayout(v, lp); } catch (Exception ignored) {}
    }

    // ---------- chat messages ----------
    private void sendInput() {
        if (input == null) return;
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        input.setText("");
        if (loop != null && loop.isAlive()) { systemMessage("AI is working - press OFF to stop it first."); return; }
        userMessage(text);
        loop = new AgentLoop(this, this, text);
        loop.start();
    }

    private void bubble(String text, int color, int textColor) {
        if (msgBox == null) { transcript.add((color == TEAL ? "AI: " : color == ACCENT ? "YOU: " : "SYS: ") + text); return; }
        TextView tv = new TextView(this);
        tv.setText(text); tv.setTextColor(textColor); tv.setTextSize(13);
        tv.setPadding(dp(8), dp(6), dp(8), dp(6));
        GradientDrawable g = new GradientDrawable();
        g.setColor(PANEL); g.setCornerRadius(dp(10)); g.setStroke(dp(1), color);
        tv.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(3), 0, dp(3));
        msgBox.addView(tv, lp);
        msgScroll.post(() -> msgScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void userMessage(String t) { bubble(t, ACCENT, Color.WHITE); }
    @Override public void aiMessage(String t) { ui.post(() -> bubble(t, TEAL, Color.WHITE)); }
    @Override public void systemMessage(String t) { ui.post(() -> bubble(t, 0xFF5A6572, 0xFFB8C0CA)); }
    @Override public void thinking(boolean on) {
        ui.post(() -> { if (titleView != null) titleView.setText(on ? "thinking..." : (SecureStore.model(this) != null ? SecureStore.model(this) : "Agentic")); });
    }

    @Override public void askUser(String question, AgentLoop.YesNo cb) {
        ui.post(() -> {
            systemMessage(question);
            if (msgBox == null) { openChat(); }
            LinearLayout row = new LinearLayout(this);
            Button yes = smallBtn("YES", ACCENT); yes.setTextColor(0xFF0B0F13);
            Button no = smallBtn("NO", RED);
            final boolean[] answered = {false};
            yes.setOnClickListener(v -> { if (answered[0]) return; answered[0] = true; row.setEnabled(false); yes.setAlpha(0.4f); no.setAlpha(0.4f); cb.answer(true); });
            no.setOnClickListener(v -> { if (answered[0]) return; answered[0] = true; row.setEnabled(false); yes.setAlpha(0.4f); no.setAlpha(0.4f); cb.answer(false); });
            row.addView(yes); row.addView(no);
            msgBox.addView(row);
            msgScroll.post(() -> msgScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    @Override public void loopEnded() { ui.post(() -> {}); }

    private void replayTranscript() {
        List<String> copy = new ArrayList<>(transcript);
        transcript.clear();
        for (String line : copy) {
            if (line.startsWith("AI: ")) bubble(line.substring(4), TEAL, Color.WHITE);
            else if (line.startsWith("YOU: ")) bubble(line.substring(5), ACCENT, Color.WHITE);
            else bubble(line.substring(5), 0xFF5A6572, 0xFFB8C0CA);
        }
    }

    // ---------- shutdown ----------
    private void stopAgent() {
        if (loop != null) { loop.stopped = true; loop.interrupt(); loop = null; }
    }

    /** Red OFF / screen off / glitch: halt AI mid-action, remove ball+window. */
    private void shutdownNow() {
        stopAgent();
        ui.post(() -> {
            hideChat(false);
            hideBall();
            stopSelf();
        });
    }

    /** 5s long-press: remove UI, keep saved API config, do nothing else. */
    private void shutdownKeepConfig() { shutdownNow(); }

    @Override public void onDestroy() {
        stopAgent();
        try { unregisterReceiver(screenOff); } catch (Exception ignored) {}
        if (orient != null) orient.disable();
        ui.post(() -> {
            if (ball != null) { try { wm.removeView(ball); } catch (Exception ignored) {} ball = null; }
            if (chat != null) { try { wm.removeView(chat); } catch (Exception ignored) {} chat = null; }
        });
        super.onDestroy();
    }
}
