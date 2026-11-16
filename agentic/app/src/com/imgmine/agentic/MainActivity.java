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
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

/** Onboarding: permissions -> API setup -> START AGENTIC. */
public class MainActivity extends Activity {
    private static final int BG = 0xFF0F1319, CARD = 0xFF1A212B, FIELD = 0xFF232B36,
            ACCENT = 0xFF5FD068, TEAL = 0xFF2DD4BF, DIM = 0xFF8A94A0, BTN2 = 0xFF2A3340;
    private static final Typeface BOLD = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private LinearLayout root;
    private TextView status;

    @Override protected void onResume() {
        super.onResume();
        render();
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

        if (!overlayOk() || !a11yOk() || !notifOk() || !writeSettingsOk()) stepPermissions();
        else if (!SecureStore.configured(this)) stepApi();
        else stepStart();
        setContentView(sv);
    }

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
                    runOnUiThread(() -> {
                        spin.setVisibility(View.GONE); connect.setEnabled(true);
                        new AlertDialog.Builder(this)
                            .setTitle("Select model (" + models.length + ")")
                            .setItems(models, (d, which) -> {
                                try {
                                    SecureStore.saveConfig(this, root0, k, models[which]);
                                    render();
                                } catch (Exception e) { err.setText("Key save nahi ho payi - dobara try karo"); }
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                    });
                } catch (Exception e) {
                    String m = e.getMessage() != null ? e.getMessage() : e.toString();
                    runOnUiThread(() -> { spin.setVisibility(View.GONE); connect.setEnabled(true); err.setText(m); });
                }
            }).start();
        });
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

    private void stepStart() {
        status.setText("Step 3 of 3 - Ready");
        LinearLayout c = card();
        c.addView(label("Connected"));
        TextView m = desc("Model: " + SecureStore.model(this));
        m.setPadding(0, dp(3), 0, dp(4));
        c.addView(m);
        TextView keys = new TextView(this);
        int kc = SecureStore.keyCount(this);
        keys.setText("API keys: " + kc + (kc > 1 ? " (rate-limit pe auto-switch)" : " - tap to add more (auto-switch on rate limit)"));
        keys.setTextColor(TEAL); keys.setTextSize(13);
        keys.setPadding(0, dp(6), 0, 0);
        keys.setOnClickListener(v -> manageKeys());
        c.addView(keys);
        TextView change = new TextView(this);
        change.setText("Change API / model"); change.setTextColor(TEAL); change.setTextSize(13);
        change.setPadding(0, dp(6), 0, 0);
        change.setOnClickListener(v -> { SecureStore.clear(this); render(); });
        c.addView(change);
        root.addView(c);

        Button start = btn("START AGENTIC", ACCENT, 0xFF0B0F13);
        start.setTextSize(16);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(56));
        lp.setMargins(0, dp(6), 0, dp(14));
        start.setLayoutParams(lp);
        start.setOnClickListener(v -> {
            if (!overlayOk() || !a11yOk()) { render(); return; }
            Intent svc = new Intent(this, OverlayService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc); else startService(svc);
            moveTaskToBack(true);
        });
        root.addView(start);

        TextView tips = new TextView(this);
        tips.setText("Tap the ball to chat. Long-press it 5s to hide. Red OFF stops everything instantly.");
        tips.setTextColor(DIM); tips.setTextSize(12); tips.setLineSpacing(0, 1.15f);
        root.addView(tips);
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
        sub.setText("Same provider (" + SecureStore.baseUrl(this) + "). Rate-limit pe keys auto-switch hongi.");
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
                    Api.fetchModels(SecureStore.baseUrl(this), k); // verify only
                    SecureStore.addKey(this, k);
                    runOnUiThread(() -> render());
                } catch (Exception e) {
                    String m2 = e.getMessage() != null ? e.getMessage() : "Key verify nahi hui";
                    runOnUiThread(() -> { add.setEnabled(true); err.setText(m2); });
                }
            }).start();
        });
    }
}
