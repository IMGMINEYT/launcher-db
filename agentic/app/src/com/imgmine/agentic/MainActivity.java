package com.imgmine.agentic;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Onboarding (permissions -> API) then tabbed main UI: Home / Live / History / Settings. */
public class MainActivity extends Activity {
    public static volatile boolean foreground;
    private static final int BG = 0xFF0F1319, CARD = 0xFF1A212B, FIELD = 0xFF232B36,
            ACCENT = 0xFF5FD068, TEAL = 0xFF2DD4BF, DIM = 0xFF8A94A0, BTN2 = 0xFF2A3340,
            RED = 0xFFE5484D, NAVBG = 0xFF141A22;
    private static final Typeface BOLD = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private LinearLayout root;
    private TextView status;
    private String tab = "home";
    private FrameLayout contentFrame;
    private final Handler ui = new Handler();
    private Runnable liveTick;

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        render();
    }

    @Override protected void onPause() {
        foreground = false;
        super.onPause();
        stopLiveTick();
    }

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private boolean overlayOk() { return Settings.canDrawOverlays(this); }
    private boolean a11yOk() {
        String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains(getPackageName());
    }
    private boolean notifOk() {
        return Build.VERSION.SDK_INT < 33 || checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
    }
    private boolean writeSettingsOk() { return Settings.System.canWrite(this); }

    private void render() {
        stopLiveTick();
        if (!overlayOk() || !a11yOk() || !notifOk() || !writeSettingsOk()) { onboardingShell(); stepPermissions(); }
        else if (!SecureStore.configured(this)) { onboardingShell(); stepApi(); }
        else mainUi();
    }

    private void onboardingShell() {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(24), dp(56), dp(24), dp(32));
        sv.addView(root);

        TextView h = new TextView(this);
        h.setText("Agentic"); h.setTypeface(BOLD); h.setTextSize(32); h.setTextColor(Color.WHITE);
        root.addView(h);
        TextView sub = new TextView(this);
        sub.setText("Your phone, controlled by AI"); sub.setTextColor(DIM); sub.setTextSize(14);
        sub.setPadding(0, dp(4), 0, dp(28));
        root.addView(sub);
        status = new TextView(this);
        status.setTextColor(TEAL); status.setTextSize(13); status.setTypeface(BOLD);
        status.setPadding(0, 0, 0, dp(14));
        root.addView(status);
        setContentView(sv);
    }

    private void mainUi() {
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(BG);
        contentFrame = new FrameLayout(this);
        shell.addView(contentFrame, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setBackgroundColor(NAVBG);
        nav.setPadding(0, dp(6), 0, dp(10));
        String[][] tabs = {{"home", "Home"}, {"live", "Live"}, {"history", "History"}, {"settings", "Settings"}};
        for (final String[] t : tabs) {
            TextView b = new TextView(this);
            b.setText(t[1]);
            b.setGravity(Gravity.CENTER);
            b.setTextSize(14);
            b.setTypeface(BOLD);
            b.setTextColor(tab.equals(t[0]) ? TEAL : DIM);
            b.setPadding(0, dp(8), 0, dp(8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> { tab = t[0]; render(); });
            nav.addView(b);
        }
        shell.addView(nav);
        setContentView(shell);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(20), dp(28), dp(20), dp(24));
        sv.addView(root);
        contentFrame.addView(sv);

        if ("live".equals(tab)) tabLive();
        else if ("history".equals(tab)) tabHistory();
        else if ("settings".equals(tab)) tabSettings();
        else tabHome();
    }

    private void stopLiveTick() {
        if (liveTick != null) ui.removeCallbacks(liveTick);
        liveTick = null;
    }

    // ---------------- Home ----------------

    private void tabHome() {
        TextView h = new TextView(this);
        h.setText("Agentic"); h.setTypeface(BOLD); h.setTextSize(30); h.setTextColor(Color.WHITE);
        root.addView(h);
        TextView sub = new TextView(this);
        sub.setText("Kya karwaoge aaj? Bolo - phone AI chalayega.");
        sub.setTextColor(DIM); sub.setTextSize(14);
        sub.setPadding(0, dp(4), 0, dp(20));
        root.addView(sub);

        LinearLayout c = card();
        c.addView(label("Connected"));
        SecureStore.Prov act = SecureStore.active(this);
        TextView pv = desc("Provider: " + (act != null ? act.name + " (" + act.type + ")" : "?")
            + (SecureStore.fallback(this) != null ? "  +backup: " + SecureStore.fallback(this).name : ""));
        pv.setPadding(0, dp(3), 0, 0);
        c.addView(pv);
        TextView m = desc("Model: " + SecureStore.model(this));
        m.setPadding(0, 0, 0, dp(4));
        c.addView(m);
        TextView keys = desc("API keys: " + SecureStore.keyCount(this)
            + (SecureStore.keyCount(this) > 1 ? " (busy ho to key khud badlegi)" : ""));
        keys.setPadding(0, 0, 0, 0);
        c.addView(keys);
        root.addView(c);

        LinearLayout ic = card();
        ic.addView(label("Instruction do"));
        ic.addView(desc("Type karo - agent ball pe chalta rahega, progress wahi dikhega."));
        final EditText in = new EditText(this);
        in.setHint("e.g. WhatsApp pe mummy ko 'pahunch gaya' bhejo");
        in.setHintTextColor(0xFF5A6572);
        in.setTextColor(Color.WHITE); in.setTextSize(14);
        GradientDrawable g = new GradientDrawable();
        g.setColor(FIELD); g.setCornerRadius(dp(10));
        in.setBackground(g);
        in.setPadding(dp(14), dp(10), dp(14), dp(10));
        in.setMinLines(2);
        in.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-1, -2);
        ilp.setMargins(0, dp(4), 0, 0);
        in.setLayoutParams(ilp);
        ic.addView(in);
        Button send = btn("Bhejo", ACCENT, 0xFF0B0F13);
        send.setOnClickListener(v -> {
            String t = in.getText().toString().trim();
            if (t.isEmpty()) return;
            sendInstruction(t);
            in.setText("");
        });
        ic.addView(send);
        root.addView(ic);

        OverlayService os = OverlayService.instance;
        if (os == null) {
            Button start = btn("START AGENTIC", ACCENT, 0xFF0B0F13);
            start.setTextSize(16);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(56));
            lp.setMargins(0, dp(6), 0, dp(14));
            start.setLayoutParams(lp);
            start.setOnClickListener(v -> startAgentService(true));
            root.addView(start);
        } else {
            TextView run = new TextView(this);
            run.setText(os.agentRunning()
                ? (os.agentPaused() ? "Agent paused hai - Live tab pe resume karo" : "Agent abhi kaam kar raha hai - ball pe dekho")
                : "Agentic chal raha hai (ball dikhega). Live tab pe control hai.");
            run.setTextColor(TEAL); run.setTextSize(13);
            run.setPadding(0, dp(2), 0, dp(10));
            root.addView(run);
            Button stop = btn("STOP AGENTIC", RED, Color.WHITE);
            stop.setOnClickListener(v -> {
                OverlayService o2 = OverlayService.instance;
                if (o2 != null) o2.shutdownNow();
                Toast.makeText(this, "Agentic band - sab UI hat gaya", Toast.LENGTH_SHORT).show();
                ui.postDelayed(() -> render(), 400);
            });
            root.addView(stop);
        }

        TextView tips = new TextView(this);
        tips.setText("Tap the ball to chat. Long-press it 5s to hide. Red OFF stops everything instantly.");
        tips.setTextColor(DIM); tips.setTextSize(12); tips.setLineSpacing(0, 1.15f);
        tips.setPadding(0, dp(10), 0, 0);
        root.addView(tips);
    }

    private void startAgentService(boolean goBack) {
        if (!overlayOk() || !a11yOk()) { render(); return; }
        Intent svc = new Intent(this, OverlayService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc); else startService(svc);
        if (goBack) moveTaskToBack(true);
    }

    private void sendInstruction(final String text) {
        if (!overlayOk() || !a11yOk()) { render(); return; }
        Intent svc = new Intent(this, OverlayService.class);
        svc.putExtra("instruction", text);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc); else startService(svc);
            moveTaskToBack(true);
        } catch (Exception e) {
            Toast.makeText(this, "Agentic start nahi hua - dobara try karo", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------- Live ----------------

    private void tabLive() {
        TextView h = new TextView(this);
        h.setText("Live Control"); h.setTypeface(BOLD); h.setTextSize(24); h.setTextColor(Color.WHITE);
        h.setPadding(0, 0, 0, dp(16));
        root.addView(h);

        OverlayService os = OverlayService.instance;
        if (os == null) {
            LinearLayout c = card();
            c.addView(label("Agentic running nahi hai"));
            c.addView(desc("START dabao - ball aa jayega, phir yahan live status aur screen preview dikhega."));
            root.addView(c);
            Button start = btn("START AGENTIC", ACCENT, 0xFF0B0F13);
            start.setOnClickListener(v -> { startAgentService(false); ui.postDelayed(() -> render(), 800); });
            root.addView(start);
            return;
        }

        LinearLayout c = card();
        c.addView(label("Status"));
        final TextView stateTv = desc("");
        c.addView(stateTv);
        final TextView appTv = desc("");
        c.addView(appTv);
        final TextView stepTv = desc("");
        stepTv.setPadding(0, 0, 0, 0);
        c.addView(stepTv);
        root.addView(c);

        LinearLayout pc = card();
        pc.addView(label("Screen preview"));
        pc.addView(desc("Last captured screen, live video nahi. Yahan app khuli ho to phone control rukta hai. Android 11+ par available."));
        final ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setAdjustViewBounds(true);
        GradientDrawable ig = new GradientDrawable();
        ig.setColor(FIELD); ig.setCornerRadius(dp(10));
        iv.setBackground(ig);
        LinearLayout.LayoutParams ivlp = new LinearLayout.LayoutParams(-1, dp(300));
        ivlp.setMargins(0, dp(4), 0, 0);
        iv.setLayoutParams(ivlp);
        AgentService as0 = AgentService.instance;
        if (as0 != null && as0.lastShot != null) iv.setImageBitmap(as0.lastShot);
        pc.addView(iv);
        root.addView(pc);

        final Button pauseBtn = btn("Pause task", BTN2, Color.WHITE);
        pauseBtn.setOnClickListener(v -> {
            OverlayService o3 = OverlayService.instance;
            if (o3 != null && o3.agentRunning()) {
                o3.pauseAgent(true);
                Toast.makeText(this, "Pause maanga - chal raha step khatam hone pe rukega", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(pauseBtn);
        Button resumeBtn = btn("Continue on phone", ACCENT, 0xFF0B0F13);
        resumeBtn.setOnClickListener(v -> {
            OverlayService o3 = OverlayService.instance;
            if (o3 != null && o3.agentRunning()) {
                o3.pauseAgent(false);
                moveTaskToBack(true);
            } else Toast.makeText(this, "Koi task nahi chal raha", Toast.LENGTH_SHORT).show();
        });
        root.addView(resumeBtn);

        Button stop = btn("Emergency stop (sab band)", RED, Color.WHITE);
        stop.setOnClickListener(v -> {
            OverlayService o4 = OverlayService.instance;
            if (o4 != null) o4.shutdownNow();
            Toast.makeText(this, "Agentic band", Toast.LENGTH_SHORT).show();
            ui.postDelayed(() -> render(), 400);
        });
        root.addView(stop);

        startLiveTick(stateTv, appTv, stepTv, iv, pauseBtn);
    }

    private void startLiveTick(final TextView stateTv, final TextView appTv, final TextView stepTv,
                               final ImageView iv, final Button pauseBtn) {
        stopLiveTick();
        liveTick = new Runnable() { @Override public void run() {
            if (!"live".equals(tab) || contentFrame == null) return;
            OverlayService os = OverlayService.instance;
            AgentService as = AgentService.instance;
            if (os == null) { render(); return; }
            boolean running = os.agentRunning();
            boolean paused = os.agentPaused();
            stateTv.setText(MainActivity.foreground && running ? "App khuli hai - phone control wait par. Continue dabao."
                : paused ? "Agent: pause requested (current step may finish)"
                : running ? "Agent: kaam kar raha hai" : "Agent: idle - instruction ka wait");
            if (as != null && as.lastShotAt > 0) appTv.setText("Current app: " + as.currentApp() + "\nLast screen: " +
                Math.max(0, (System.currentTimeMillis() - as.lastShotAt) / 1000) + "s pehle");
            else appTv.setText("");
            String ls = os.latestStep();
            stepTv.setText(ls.isEmpty() ? "" : "Last step: " + ls);
            if (as != null && as.lastShot != null) iv.setImageBitmap(as.lastShot);
            else iv.setImageDrawable(null);
            pauseBtn.setText(paused ? "Task paused" : "Pause task");
            pauseBtn.setEnabled(running);
            ui.postDelayed(this, 2000);
        } };
        ui.postDelayed(liveTick, 700);
    }

    // ---------------- History ----------------

    private void tabHistory() {
        TextView h = new TextView(this);
        h.setText("History"); h.setTypeface(BOLD); h.setTextSize(24); h.setTextColor(Color.WHITE);
        h.setPadding(0, 0, 0, dp(16));
        root.addView(h);

        org.json.JSONArray tasks = TaskStore.list(this);
        if (tasks.length() == 0) {
            LinearLayout c = card();
            c.addView(label("Koi task nahi abhi tak"));
            c.addView(desc("Jo bhi agent karega, uska record yahan dikhega - app band ho ke bhi saved rahega."));
            root.addView(c);
            return;
        }
        LinearLayout c = card();
        c.addView(label("Tasks (" + tasks.length() + ")"));
        c.addView(desc("running / completed / failed / cancelled / paused - sab yahan."));
        for (int i = 0; i < tasks.length(); i++) {
            org.json.JSONObject t = tasks.optJSONObject(i);
            if (t == null) continue;
            String st = t.optString("state");
            String txt = t.optString("text");
            TextView row = new TextView(this);
            row.setText(TaskStore.icon(st) + "  " + txt);
            row.setTextColor("completed".equals(st) ? DIM : Color.WHITE);
            row.setTextSize(14);
            row.setPadding(0, dp(6), 0, 0);
            c.addView(row);
            String note = t.optString("note");
            if (!note.isEmpty()) {
                TextView nv = new TextView(this);
                nv.setText("      " + note);
                nv.setTextColor(DIM); nv.setTextSize(12);
                c.addView(nv);
            }
        }
        root.addView(c);
        Button clr = btn("Clear history", BTN2, Color.WHITE);
        clr.setOnClickListener(v -> { TaskStore.clear(this); render(); });
        root.addView(clr);
    }

    // ---------------- Settings ----------------

    private void tabSettings() {
        TextView h = new TextView(this);
        h.setText("Settings"); h.setTypeface(BOLD); h.setTextSize(24); h.setTextColor(Color.WHITE);
        h.setPadding(0, 0, 0, dp(16));
        root.addView(h);

        LinearLayout c = card();
        c.addView(label("AI connection"));
        SecureStore.Prov act = SecureStore.active(this);
        TextView pv = desc("Provider: " + (act != null ? act.name + " (" + act.type + ")" : "?")
            + (SecureStore.fallback(this) != null ? "  +backup: " + SecureStore.fallback(this).name : ""));
        pv.setPadding(0, dp(3), 0, 0);
        c.addView(pv);
        TextView m = desc("Model: " + SecureStore.model(this));
        m.setPadding(0, 0, 0, dp(4));
        c.addView(m);
        TextView keys = new TextView(this);
        int kc = SecureStore.keyCount(this);
        keys.setText("API keys: " + kc + (kc > 1 ? " (busy ho to key khud badlegi)" : " - tap to add more (busy ho to key khud badlegi)"));
        keys.setTextColor(TEAL); keys.setTextSize(13);
        keys.setPadding(0, dp(6), 0, 0);
        keys.setOnClickListener(v -> manageKeys());
        c.addView(keys);
        TextView change = new TextView(this);
        change.setText("Providers manage karo (add / backup / change)"); change.setTextColor(TEAL); change.setTextSize(13);
        change.setPadding(0, dp(6), 0, 0);
        change.setOnClickListener(v -> manageProviders());
        c.addView(change);
        root.addView(c);

        LinearLayout mc = card();
        mc.addView(label("Data"));
        TextView memRow = new TextView(this);
        memRow.setText("AI ki yaadein (memory) clear karo");
        memRow.setTextColor(Color.WHITE); memRow.setTextSize(14);
        memRow.setPadding(0, dp(4), 0, 0);
        memRow.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle("AI memory clear karein?")
            .setMessage("Jo cheezein AI ne aapke baare me yaad rakhi hain, sab delete ho jayengi. Chat aur keys safe rahenge.")
            .setPositiveButton("Clear", (dd, w2) -> { SecureStore.clearMemory(this); render(); })
            .setNegativeButton("Cancel", null)
            .show());
        mc.addView(memRow);
        TextView hisRow = new TextView(this);
        hisRow.setText("Task history clear karo");
        hisRow.setTextColor(Color.WHITE); hisRow.setTextSize(14);
        hisRow.setPadding(0, dp(8), 0, 0);
        hisRow.setOnClickListener(v -> { TaskStore.clear(this); Toast.makeText(this, "History clear", Toast.LENGTH_SHORT).show(); });
        mc.addView(hisRow);
        root.addView(mc);

        LinearLayout dc = card();
        dc.addView(label("Diagnostics"));
        dc.addView(desc("Aaj ka token estimate: " + LogStore.tokensToday(this) + " (estimate hai, provider ka bill alag ho sakta hai)."));
        org.json.JSONArray logs = LogStore.list(this);
        TextView lh = new TextView(this);
        lh.setText("Action log (" + logs.length() + "):");
        lh.setTextColor(Color.WHITE); lh.setTextSize(13);
        lh.setPadding(0, dp(2), 0, 0);
        dc.addView(lh);
        int shownL = Math.min(10, logs.length());
        for (int i = 0; i < shownL; i++) {
            org.json.JSONObject o = logs.optJSONObject(i);
            if (o == null) continue;
            TextView row = new TextView(this);
            row.setText("  " + o.optString("action") + "  ->  " + o.optString("result") + "  (" + o.optLong("ms") + "ms)");
            row.setTextColor(o.optString("result").startsWith("error") ? 0xFFE57373 : DIM);
            row.setTextSize(11.5f);
            dc.addView(row);
        }
        TextView clrL = new TextView(this);
        clrL.setText("Log clear karo");
        clrL.setTextColor(DIM); clrL.setTextSize(12);
        clrL.setPadding(0, dp(8), 0, 0);
        clrL.setOnClickListener(v -> { LogStore.clear(this); render(); });
        dc.addView(clrL);
        root.addView(dc);

        TextView tips = new TextView(this);
        tips.setText("Tap the ball to chat. Long-press it 5s to hide. Red OFF stops everything instantly. Keys sirf is phone pe encrypted rehti hain.");
        tips.setTextColor(DIM); tips.setTextSize(12); tips.setLineSpacing(0, 1.15f);
        tips.setPadding(0, dp(10), 0, 0);
        root.addView(tips);
    }

    // ---------------- shared widgets ----------------

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD); g.setCornerRadius(dp(16));
        c.setBackground(g);
        c.setPadding(dp(16), dp(14), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(12));
        c.setLayoutParams(lp);
        return c;
    }

    private TextView label(String t) {
        TextView v = new TextView(this);
        v.setText(t); v.setTextColor(Color.WHITE); v.setTextSize(15); v.setTypeface(BOLD);
        return v;
    }
    private TextView desc(String t) {
        TextView v = new TextView(this);
        v.setText(t); v.setTextColor(DIM); v.setTextSize(13);
        v.setPadding(0, dp(3), 0, dp(10));
        return v;
    }

    private Button btn(String t, int bg, int fg) {
        Button b = new Button(this);
        b.setText(t); b.setTextColor(fg); b.setTextSize(14); b.setTypeface(BOLD); b.setAllCaps(false);
        GradientDrawable g = new GradientDrawable();
        g.setColor(bg); g.setCornerRadius(dp(12));
        b.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48));
        lp.setMargins(0, dp(6), 0, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private EditText field(String hint, boolean secret) {
        EditText e = new EditText(this);
        e.setHint(hint); e.setHintTextColor(0xFF5A6572);
        e.setTextColor(Color.WHITE); e.setTextSize(14);
        GradientDrawable g = new GradientDrawable();
        g.setColor(FIELD); g.setCornerRadius(dp(10));
        e.setBackground(g);
        e.setPadding(dp(14), 0, dp(14), 0);
        e.setSingleLine(true);
        if (secret) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48));
        lp.setMargins(0, dp(8), 0, 0);
        e.setLayoutParams(lp);
        return e;
    }

    // ---------------- onboarding steps ----------------

    private void stepPermissions() {
        status.setText("Step 1 of 3 - Permissions");
        if (!notifOk()) {
            LinearLayout c = card();
            c.addView(label("Notifications"));
            c.addView(desc("Status updates while the agent runs."));
            Button b = btn("Allow notifications", BTN2, Color.WHITE);
            b.setOnClickListener(v -> { if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1); });
            c.addView(b); root.addView(c);
        }
        if (!overlayOk()) {
            LinearLayout c = card();
            c.addView(label("Floating ball"));
            c.addView(desc("Lets Agentic show the draggable ball and chat window over other apps."));
            Button b = btn("Allow display over apps", BTN2, Color.WHITE);
            b.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
            c.addView(b); root.addView(c);
        }
        if (!writeSettingsOk()) {
            LinearLayout c = card();
            c.addView(label("Screen rotation"));
            c.addView(desc("Lets the AI rotate the screen when you ask."));
            Button b = btn("Allow modify settings", BTN2, Color.WHITE);
            b.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName()))));
            c.addView(b); root.addView(c);
        }
        if (!a11yOk()) {
            LinearLayout c = card();
            c.addView(label("Accessibility (device control)"));
            c.addView(desc("Required: Agentic reads the screen and taps/swipes/types for you. In Settings, find 'Agentic' and turn it ON, then come back."));
            Button b = btn("Open accessibility settings", ACCENT, 0xFF0B0F13);
            b.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
            c.addView(b); root.addView(c);
        }
    }

    private void stepApi() {
        status.setText("Step 2 of 3 - Connect your AI");
        LinearLayout c = card();
        c.addView(label("API connection"));
        c.addView(desc("Works with any OpenAI-compatible API - OpenAI, OpenRouter, Groq, etc."));
        TextView other = new TextView(this);
        other.setText("Anthropic ya Gemini? Tap here"); other.setTextColor(TEAL); other.setTextSize(12);
        other.setPadding(0, dp(6), 0, 0);
        other.setOnClickListener(v -> stepAddProvider());
        c.addView(other);
        EditText base = field("Base URL - e.g. https://api.openai.com", false);
        EditText key = field("API key", true);
        c.addView(base); c.addView(key);
        Button connect = btn("Connect", ACCENT, 0xFF0B0F13);
        c.addView(connect);
        ProgressBar spin = new ProgressBar(this);
        spin.setVisibility(View.GONE);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.gravity = Gravity.CENTER_HORIZONTAL; slp.setMargins(0, dp(10), 0, 0);
        c.addView(spin, slp);
        TextView err = new TextView(this);
        err.setTextColor(0xFFE57373); err.setTextSize(12);
        err.setPadding(0, dp(6), 0, 0);
        c.addView(err);
        root.addView(c);
        TextView note = new TextView(this);
        note.setText("Your key is stored encrypted on this device only.");
        note.setTextColor(DIM); note.setTextSize(12);
        root.addView(note);

        connect.setOnClickListener(v -> {
            String b = base.getText().toString().trim(), k = key.getText().toString().trim();
            if (b.isEmpty() || k.isEmpty()) { err.setText("Enter both base URL and API key"); return; }
            err.setText(""); spin.setVisibility(View.VISIBLE); connect.setEnabled(false);
            new Thread(() -> {
                try {
                    String[] res = Api.fetchModels(b, k);
                    final String root0 = res[0];
                    final String[] models = new String[res.length - 1];
                    System.arraycopy(res, 1, models, 0, models.length);
                    final String[] display = new String[models.length];
                    for (int i = 0; i < models.length; i++)
                        display[i] = models[i] + (AgentLoop.isVisionModel(models[i]) ? "   [vision]" : "");
                    runOnUiThread(() -> {
                        spin.setVisibility(View.GONE); connect.setEnabled(true);
                        new AlertDialog.Builder(this)
                            .setTitle("Select model (" + models.length + ") - [vision] = photos/samajh sakta hai")
                            .setItems(display, (d, which) -> {
                                try {
                                    SecureStore.saveConfig(this, root0, k, models[which]);
                                    render();
                                } catch (Exception e) { err.setText("Key save nahi ho payi - dobara try karo"); }
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                    });
                } catch (Exception e) {
                    String m = "Connect nahi hua - URL, key aur internet check karo.";
                    runOnUiThread(() -> { spin.setVisibility(View.GONE); connect.setEnabled(true); err.setText(m); });
                }
            }).start();
        });
    }
    private void manageProviders() {
        int n = SecureStore.providerCount(this);
        int act = SecureStore.activeIndex(this), fb = SecureStore.fallbackIndex(this);
        String[] items = new String[n + 1];
        for (int i = 0; i < n; i++) {
            SecureStore.Prov pv = SecureStore.providerAt(this, i);
            items[i] = pv.name + " (" + pv.type + ") - " + pv.model + " [keys: " + pv.keyCount() + "]"
                + (i == act ? "  *ACTIVE*" : "") + (i == fb ? "  *BACKUP*" : "");
        }
        items[n] = "+ Add provider";
        new AlertDialog.Builder(this)
            .setTitle("AI providers")
            .setItems(items, (d, which) -> {
                if (which == n) { stepAddProvider(); return; }
                final int idx = which;
                String[] ops = idx == act
                    ? new String[]{"Change model", "Set as backup", "Remove provider"}
                    : new String[]{"Set active", "Set as backup", "Change model", "Remove provider"};
                new AlertDialog.Builder(this)
                    .setTitle(items[idx])
                    .setItems(ops, (dd, op) -> {
                        String choice = ops[op];
                        if ("Set active".equals(choice)) SecureStore.setActive(this, idx);
                        else if ("Set as backup".equals(choice))
                            SecureStore.setFallback(this, SecureStore.fallbackIndex(this) == idx ? -1 : idx);
                        else if ("Change model".equals(choice)) changeModel(idx);
                        else if ("Remove provider".equals(choice)) {
                            if (n <= 1) return; // keep at least one
                            new AlertDialog.Builder(this)
                                .setTitle("Remove provider?")
                                .setMessage("Is provider ki keys bhi delete ho jayengi.")
                                .setPositiveButton("Remove", (d3, w) -> { SecureStore.removeProvider(this, idx); render(); })
                                .setNegativeButton("Cancel", null)
                                .show();
                            return;
                        }
                        render();
                    })
                    .setNegativeButton("Close", null)
                    .show();
            })
            .setNegativeButton("Close", null)
            .show();
    }

    private void changeModel(int idx) {
        SecureStore.Prov pv = SecureStore.providerAt(this, idx);
        if (pv == null) return;
        new Thread(() -> {
            try {
                String key = pv.currentKey();
                String[] res = Api.fetchModels(pv.base, key, pv.type);
                final String[] models = new String[res.length - 1];
                System.arraycopy(res, 1, models, 0, models.length);
                final String[] display = new String[models.length];
                for (int i = 0; i < models.length; i++)
                    display[i] = models[i] + (AgentLoop.isVisionModel(models[i]) ? "   [vision]" : "");
                runOnUiThread(() -> new AlertDialog.Builder(this)
                    .setTitle("Select model - [vision] = photos/samajh sakta hai")
                    .setItems(display, (d, w) -> { SecureStore.setModel(this, pv, models[w]); render(); })
                    .setNegativeButton("Cancel", null)
                    .show());
            } catch (Exception e) {
                String m2 = "Models nahi mile - key aur internet check karo.";
                runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("Error").setMessage(m2).setPositiveButton("OK", null).show());
            }
        }).start();
    }

    private static final String[][] PROVIDER_TYPES = {
        {"OpenAI-compatible (OpenAI, OpenRouter, Groq...)", "openai", "https://api.openai.com"},
        {"Anthropic Claude", "anthropic", "https://api.anthropic.com"},
        {"Google Gemini", "gemini", "https://generativelanguage.googleapis.com"},
    };

    private void stepAddProvider() {
        String[] names = new String[PROVIDER_TYPES.length];
        for (int i = 0; i < PROVIDER_TYPES.length; i++) names[i] = PROVIDER_TYPES[i][0];
        new AlertDialog.Builder(this)
            .setTitle("Kaunsa provider?")
            .setItems(names, (d, which) -> addProviderForm(PROVIDER_TYPES[which][1], PROVIDER_TYPES[which][0], PROVIDER_TYPES[which][2]))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void addProviderForm(String type, String label, String defBase) {
        root.removeAllViews();
        TextView h = new TextView(this);
        h.setText("Add provider"); h.setTypeface(BOLD); h.setTextSize(24); h.setTextColor(Color.WHITE);
        root.addView(h);
        TextView sub = new TextView(this);
        sub.setText(label);
        sub.setTextColor(DIM); sub.setTextSize(13); sub.setPadding(0, dp(4), 0, dp(20));
        root.addView(sub);
        LinearLayout c = card();
        c.addView(label("Connection"));
        EditText base = field("Base URL", false);
        base.setText(defBase);
        EditText key = field("API key", true);
        c.addView(base); c.addView(key);
        Button add = btn("Verify & add", ACCENT, 0xFF0B0F13);
        c.addView(add);
        TextView err = new TextView(this);
        err.setTextColor(0xFFE57373); err.setTextSize(12); err.setPadding(0, dp(6), 0, 0);
        c.addView(err);
        root.addView(c);
        TextView back = new TextView(this);
        back.setText("< Back"); back.setTextColor(TEAL); back.setTextSize(13);
        back.setOnClickListener(v -> render());
        root.addView(back);
        add.setOnClickListener(v -> {
            String b = base.getText().toString().trim(), k = key.getText().toString().trim();
            if (b.isEmpty() || k.isEmpty()) { err.setText("Base URL aur key dono daalo"); return; }
            err.setText(""); add.setEnabled(false);
            new Thread(() -> {
                try {
                    String[] res = Api.fetchModels(b, k, type);
                    final String root0 = res[0];
                    final String[] models = new String[res.length - 1];
                    System.arraycopy(res, 1, models, 0, models.length);
                    final String[] display = new String[models.length];
                    for (int i = 0; i < models.length; i++)
                        display[i] = models[i] + (AgentLoop.isVisionModel(models[i]) ? "   [vision]" : "");
                    runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("Select model - [vision] = photos/samajh sakta hai")
                        .setItems(display, (d2, w) -> {
                            try {
                                int i = SecureStore.addProvider(this, label.split(" ")[0], type, root0, k, models[w]);
                                if (SecureStore.providerCount(this) == 1) SecureStore.setActive(this, i);
                                render();
                            } catch (Exception e) { err.setText("Save nahi hua - dobara try karo"); }
                        })
                        .setNegativeButton("Cancel", null)
                        .show());
                } catch (Exception e) {
                    String m2 = "Connect nahi hua - URL, key aur internet check karo.";
                    runOnUiThread(() -> { add.setEnabled(true); err.setText(m2); });
                }
            }).start();
        });
    }

    private void manageKeys() {
        String[] labels = SecureStore.keyLabels(this);
        String[] items = new String[labels.length + 1];
        System.arraycopy(labels, 0, items, 0, labels.length);
        items[labels.length] = "+ Add another key";
        new AlertDialog.Builder(this)
            .setTitle("API keys")
            .setItems(items, (d, which) -> {
                if (which == labels.length) { stepAddKey(); return; }
                if (labels.length <= 1) { return; } // keep at least one
                final int idx = which;
                new AlertDialog.Builder(this)
                    .setTitle("Remove " + labels[idx] + "?")
                    .setPositiveButton("Remove", (dd, w2) -> {
                        try { SecureStore.removeKey(this, idx); } catch (Exception ignored) {}
                        render();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            })
            .setNegativeButton("Close", null)
            .show();
    }

    private void stepAddKey() {
        root.removeAllViews();
        TextView h = new TextView(this);
        h.setText("Add API key"); h.setTypeface(BOLD); h.setTextSize(24); h.setTextColor(Color.WHITE);
        root.addView(h);
        TextView sub = new TextView(this);
        sub.setText("Same provider (" + SecureStore.baseUrl(this) + "). Busy hone pe key khud badlegi.");
        sub.setTextColor(DIM); sub.setTextSize(13); sub.setPadding(0, dp(4), 0, dp(20));
        root.addView(sub);
        LinearLayout c = card();
        c.addView(label("New key"));
        EditText key = field("API key", true);
        c.addView(key);
        Button add = btn("Verify & add", ACCENT, 0xFF0B0F13);
        c.addView(add);
        TextView err = new TextView(this);
        err.setTextColor(0xFFE57373); err.setTextSize(12); err.setPadding(0, dp(6), 0, 0);
        c.addView(err);
        root.addView(c);
        TextView back = new TextView(this);
        back.setText("< Back"); back.setTextColor(TEAL); back.setTextSize(13);
        back.setOnClickListener(v -> render());
        root.addView(back);
        add.setOnClickListener(v -> {
            String k = key.getText().toString().trim();
            if (k.isEmpty()) { err.setText("Key daalo"); return; }
            err.setText(""); add.setEnabled(false);
            new Thread(() -> {
                try {
                    SecureStore.Prov pv2 = SecureStore.active(this);
                    Api.fetchModels(SecureStore.baseUrl(this), k, pv2 != null ? pv2.type : "openai"); // verify only
                    SecureStore.addKey(this, k);
                    runOnUiThread(() -> render());
                } catch (Exception e) {
                    String m2 = "Key check nahi hui - key aur internet check karo.";
                    runOnUiThread(() -> { add.setEnabled(true); err.setText(m2); });
                }
            }).start();
        });
    }
}
