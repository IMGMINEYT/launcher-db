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
import android.os.Build;
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
    private View sendBtn;
    private TextView titleView, statusView;
    private AgentLoop loop;
    private final java.util.List<String> pendingUser = new java.util.ArrayList<>(); // messages sent while busy
    private boolean busyNoted = false;
    private final org.json.JSONArray mem = new org.json.JSONArray(); // session memory across instructions
    private final List<String> transcript = new ArrayList<>();
    private OrientationEventListener orient;
    private BroadcastReceiver screenOff;
    private Typeface pixel;
    private int lastOrientation = -1;
    private String statusText = "online";

    private static final int BG = 0xF214181F, PANEL = 0xFF1E242C, PANEL2 = 0xFF232B34,
            ACCENT = 0xFF5FD068, RED = 0xFFE53935, TEAL = 0xFF2DD4BF, DIM = 0xFF8A94A0;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        pixel = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        final Thread mainThread = Looper.getMainLooper().getThread();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                if (t == mainThread) {
                    // UI thread is dead - nothing can render; remove + exit per spec.
                    try { stopAgent(); } catch (Throwable ignored) {}
                    try { wm.removeViewImmediate(ball); } catch (Throwable ignored) {}
                    try { wm.removeViewImmediate(chat); } catch (Throwable ignored) {}
                    Process.killProcess(Process.myPid());
                    System.exit(10);
                } else {
                    // Background error: stop the agent, keep ball/window alive.
                    try { stopAgent(); } catch (Throwable ignored) {}
                    ui.post(new Runnable() { @Override public void run() {
                        systemMessage("Agent me error aaya - agent rok diya, ball/window chalte rahenge. Dobara try karo.");
                    }});
                }
            }
        });
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("agentic", "Agentic", NotificationManager.IMPORTANCE_LOW));
        }
        screenOff = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { shutdownNow(); }
        };
        try {
            if (Build.VERSION.SDK_INT >= 33)
                registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED);
            else
                registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        } catch (Exception ignored) {}
        orient = new OrientationEventListener(this) {
            @Override public void onOrientationChanged(int deg) {
                Display d = wm.getDefaultDisplay();
                int rot = d.getRotation();
                if (rot != lastOrientation) { lastOrientation = rot; ui.postDelayed(new Runnable() { @Override public void run() { clampAll(); } }, 250); }
            }
        };
        if (orient.canDetectOrientation()) orient.enable();
    }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, "agentic") : new Notification.Builder(this);
        Notification n = nb.setContentTitle("Agentic").setContentText("Floating ball active")
                .setSmallIcon(R.drawable.ic_stat).build();
        startForeground(NOTIF_ID, n);
        if (ball == null && chat == null) showBall();
        return START_STICKY;
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
                float u = w / 16f;
                c.drawRect(4*u, 6*u, 12*u, 11*u, glyph);
                glyph.setColor(0xFF14181F);
                c.drawRect(5.5f*u, 7.5f*u, 7.5f*u, 9.5f*u, glyph);
                c.drawRect(8.5f*u, 7.5f*u, 10.5f*u, 9.5f*u, glyph);
                glyph.setColor(ACCENT);
                c.drawRect(7.25f*u, 3*u, 8.75f*u, 5*u, glyph);
                c.drawRect(5*u, 12*u, 11*u, 13*u, glyph);
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
        final Runnable longPress = new Runnable() { @Override public void run() { shutdownKeepConfig(); } };
        ball.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.setTag(new float[]{ ev.getRawX(), ev.getRawY(), ballLp.x, ballLp.y });
                        v.setTag(R.id.tag_moved, Boolean.FALSE);
                        v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(100).start();
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
                        v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                        boolean moved = Boolean.TRUE.equals(v.getTag(R.id.tag_moved));
                        if (moved) snapBall(); else openChat();
                        return true;
                    }
                    case MotionEvent.ACTION_CANCEL:
                        h.removeCallbacks(longPress);
                        v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                        return true;
                }
                return false;
            }
        });
    }

    private void snapBall() {
        int[] s = size();
        int target = (ballLp.x + ballLp.width / 2 < s[0] / 2) ? dp(4) : s[0] - ballLp.width - dp(4);
        ValueAnimator va = ValueAnimator.ofInt(ballLp.x, target);
        va.setDuration(220);
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                ballLp.x = (Integer) a.getAnimatedValue(); safeUpdate(ball, ballLp);
            }
        });
        va.start();
    }

    private void hideBall() {
        if (ball != null) { try { wm.removeView(ball); } catch (Exception ignored) {} ball = null; }
    }

    // ---------- chat window ----------
    private void openChat() {
        if (chat != null) return;
        int[] s = size();
        int w = Math.min((int) (s[0] * 0.92), dp(380));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bgd = new GradientDrawable();
        bgd.setColor(BG); bgd.setCornerRadius(dp(16)); bgd.setStroke(dp(1), 0xFF2A313A);
        root.setBackground(bgd);
        root.setPadding(dp(10), dp(8), dp(10), dp(10));

        // header: drag zone, title + status, OFF, X
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(2), dp(2), dp(2), dp(6));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        String model = SecureStore.model(this);
        titleView = new TextView(this);
        titleView.setText(model != null ? model : "Agentic");
        titleView.setTextColor(Color.WHITE); titleView.setTextSize(15); titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        statusView = new TextView(this);
        statusView.setText(statusText); statusView.setTextColor(DIM); statusView.setTextSize(11);
        texts.addView(titleView); texts.addView(statusView);
        header.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button clr = smallBtn("CLR", 0xFF37414C);
        Button off = smallBtn("OFF", RED);
        Button x = smallBtn("X", 0xFF37414C);
        header.addView(clr); header.addView(off); header.addView(x);
        root.addView(header);
        attachChatDrag(header);

        msgScroll = new ScrollView(this);
        msgScroll.setVerticalScrollBarEnabled(false);
        msgBox = new LinearLayout(this);
        msgBox.setOrientation(LinearLayout.VERTICAL);
        msgBox.setPadding(dp(2), dp(2), dp(2), dp(2));
        msgScroll.addView(msgBox);
        root.addView(msgScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        // input row: rounded pill + send
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable rowBg = new GradientDrawable();
        rowBg.setColor(PANEL); rowBg.setCornerRadius(dp(20));
        row.setBackground(rowBg);
        row.setPadding(dp(12), 0, dp(4), 0);
        input = new EditText(this);
        input.setHint("Message Agentic..."); input.setHintTextColor(0xFF5A6572);
        input.setTextColor(Color.WHITE); input.setTextSize(14);
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setMaxLines(3); input.setMinHeight(dp(40));
        row.addView(input, new LinearLayout.LayoutParams(0, -2, 1f));
        sendBtn = circleBtn(ACCENT);
        row.addView(sendBtn);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.setMargins(0, dp(6), 0, 0);
        root.addView(row, rowLp);

        clr.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { clearChat(); } });
        off.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { shutdownNow(); } });
        x.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { hideKeyboard(); hideChat(true); } });
        sendBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { sendInput(); } });

        chatLp = new WindowManager.LayoutParams(w, Math.min((int) (s[1] * 0.60), dp(520)),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        chatLp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN;
        chatLp.gravity = Gravity.TOP | Gravity.START;
        chatLp.x = (s[0] - w) / 2; chatLp.y = s[1] / 5;
        chat = root;
        chat.setAlpha(0f); chat.setScaleX(0.92f); chat.setScaleY(0.92f);
        wm.addView(chat, chatLp);
        chat.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start();
        hideBall();
        replayTranscript();
    }

    private Button smallBtn(String t, int color) {
        Button b = new Button(this);
        b.setText(t); b.setTextSize(12); b.setTextColor(Color.WHITE);
        b.setMinWidth(0); b.setMinimumWidth(0); b.setMinHeight(0); b.setMinimumHeight(0);
        b.setPadding(0, 0, 0, 0); b.setAllCaps(false);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color); g.setCornerRadius(dp(10));
        b.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(54), dp(32));
        lp.setMargins(dp(4), 0, 0, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private View circleBtn(int color) {
        TextView b = new TextView(this);
        b.setText("\u27A4"); b.setTextSize(15); b.setTextColor(0xFF0B0F13);
        b.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color); g.setShape(GradientDrawable.OVAL);
        b.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
        lp.setMargins(dp(6), 0, dp(2), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void attachChatDrag(View handle) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.setTag(new float[]{ ev.getRawX(), ev.getRawY(), chatLp.x, chatLp.y });
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float[] d = (float[]) v.getTag();
                        int[] s = size();
                        chatLp.x = clamp((int) (d[2] + ev.getRawX() - d[0]), -chatLp.width / 2, s[0] - chatLp.width / 2);
                        chatLp.y = clamp((int) (d[3] + ev.getRawY() - d[1]), 0, s[1] - dp(80));
                        safeUpdate(chat, chatLp);
                        return true;
                    }
                }
                return false;
            }
        });
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(v, hi)); }

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
            ballLp.x = clamp(ballLp.x, 0, s[0] - ballLp.width);
            ballLp.y = clamp(ballLp.y, 0, s[1] - ballLp.height);
            safeUpdate(ball, ballLp);
        }
        if (chat != null) {
            chatLp.x = clamp(chatLp.x, -chatLp.width / 2, s[0] - chatLp.width / 2);
            chatLp.y = clamp(chatLp.y, 0, s[1] - dp(80));
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
        if (loop != null && loop.isAlive()) {
            synchronized (pendingUser) { pendingUser.add(text); }
            userMessage(text);
            if (!busyNoted) { busyNoted = true; systemMessage("Message queue ho gaya - AI agle step me ise padh lega."); }
            return;
        }
        busyNoted = false;
        userMessage(text);
        loop = new AgentLoop(this, this, text, mem);
        loop.start();
    }

    private static final int YOU = 1, AI = 2, SYS = 3;

    private void bubble(String text, int who) {
        if (msgBox == null) { transcript.add(who + "|" + text); return; }
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(who == SYS ? 11.5f : 14);
        tv.setPadding(dp(10), dp(7), dp(10), dp(7));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(who == YOU ? dp(48) : 0, dp(3), who == AI ? dp(48) : 0, dp(3));
        if (who == YOU) {
            lp.gravity = Gravity.END;
            g.setColor(PANEL2); g.setStroke(dp(1), ACCENT);
            tv.setTextColor(Color.WHITE);
        } else if (who == AI) {
            lp.gravity = Gravity.START;
            g.setColor(PANEL); g.setStroke(dp(1), TEAL);
            tv.setTextColor(Color.WHITE);
        } else {
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            g.setColor(Color.TRANSPARENT);
            tv.setTextColor(DIM);
        }
        tv.setBackground(g);
        msgBox.addView(tv, lp);
        tv.setAlpha(0f); tv.setTranslationY(dp(8));
        tv.animate().alpha(1f).translationY(0).setDuration(150).start();
        scrollDown(false);
    }

    private void scrollDown(boolean smooth) {
        if (msgScroll == null) return;
        msgScroll.post(new Runnable() { @Override public void run() {
            if (msgScroll != null && msgBox != null) msgScroll.smoothScrollTo(0, msgBox.getBottom());
        }});
    }

    private void userMessage(String t) { bubble(t, YOU); }
    @Override public void aiMessage(String t) { ui.post(new Runnable() { @Override public void run() { bubble(t, AI); } }); }
    @Override public void systemMessage(String t) { ui.post(new Runnable() { @Override public void run() { bubble(t, SYS); } }); }

    private void setStatus(String s) {
        statusText = s;
        if (statusView != null) statusView.setText(s);
    }
    @Override public void thinking(boolean on) {
        ui.post(new Runnable() { @Override public void run() { setStatus(on ? "working..." : "online"); } });
    }
    @Override public void thinkingProgress(final int chars) {
        ui.post(new Runnable() { @Override public void run() { setStatus("receiving " + chars + "..."); } });
    }

    @Override public void askUser(final String question, final AgentLoop.YesNo cb) {
        ui.post(new Runnable() { @Override public void run() {
            systemMessage(question);
            if (msgBox == null) { openChat(); systemMessage(question); }
            LinearLayout row = new LinearLayout(OverlayService.this);
            row.setGravity(Gravity.CENTER);
            Button yes = smallBtn("YES", ACCENT); yes.setTextColor(0xFF0B0F13);
            Button no = smallBtn("NO", RED);
            LinearLayout.LayoutParams ylp = new LinearLayout.LayoutParams(dp(90), dp(36));
            ylp.setMargins(dp(6), dp(4), dp(6), dp(4));
            yes.setLayoutParams(ylp);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(dp(90), dp(36));
            nlp.setMargins(dp(6), dp(4), dp(6), dp(4));
            no.setLayoutParams(nlp);
            final boolean[] answered = {false};
            yes.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                if (answered[0]) return; answered[0] = true; yes.setAlpha(0.4f); no.setAlpha(0.4f); cb.answer(true);
            }});
            no.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                if (answered[0]) return; answered[0] = true; yes.setAlpha(0.4f); no.setAlpha(0.4f); cb.answer(false);
            }});
            row.addView(yes); row.addView(no);
            msgBox.addView(row);
            scrollDown(true);
        }});
    }

    @Override public void loopEnded() { ui.post(new Runnable() { @Override public void run() {
        setStatus("online");
        busyNoted = false;
        loop = null;
        String next = drainPending();
        if (next != null) {
            loop = new AgentLoop(OverlayService.this, OverlayService.this, next, mem);
            loop.start();
        }
    } }); }

    private String drainPending() {
        synchronized (pendingUser) {
            if (pendingUser.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            for (String m : pendingUser) { if (sb.length() > 0) sb.append(" | "); sb.append(m); }
            pendingUser.clear();
            return sb.toString();
        }
    }

    @Override public String pollPending() { return drainPending(); }

    private void replayTranscript() {
        List<String> copy = new ArrayList<>(transcript);
        transcript.clear();
        for (String line : copy) {
            int sep = line.indexOf('|');
            int who = Integer.parseInt(line.substring(0, sep));
            bubble(line.substring(sep + 1), who);
        }
    }

    /** CLR: wipe bubbles, transcript and session memory. Agent keeps running if active. */
    private void clearChat() {
        transcript.clear();
        while (mem.length() > 0) mem.remove(0);
        if (msgBox != null) msgBox.removeAllViews();
        systemMessage("Chat clear ho gayi - naya session.");
    }

    // ---------- shutdown ----------
    private void stopAgent() {
        if (loop != null) { loop.stopped = true; loop.interrupt(); loop = null; }
    }

    /** Red OFF / screen off / fatal glitch: halt AI mid-action, remove ball+window. */
    private void shutdownNow() {
        stopAgent();
        ui.post(new Runnable() { @Override public void run() {
            hideChat(false);
            hideBall();
            stopSelf();
        }});
    }

    /** 5s long-press: remove UI, keep saved API config, do nothing else. */
    private void shutdownKeepConfig() { shutdownNow(); }

    @Override public void onDestroy() {
        stopAgent();
        try { unregisterReceiver(screenOff); } catch (Exception ignored) {}
        if (orient != null) orient.disable();
        ui.post(new Runnable() { @Override public void run() {
            if (ball != null) { try { wm.removeView(ball); } catch (Exception ignored) {} ball = null; }
            if (chat != null) { try { wm.removeView(chat); } catch (Exception ignored) {} chat = null; }
        }});
        super.onDestroy();
    }
}
