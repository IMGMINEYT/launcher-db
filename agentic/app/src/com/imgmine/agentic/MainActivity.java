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
import android.widget.TextView;

/** Onboarding: permissions -> API setup -> START AGENTIC. */
public class MainActivity extends Activity {
    private static final int BG = 0xFF10141A, PANEL = 0xFF1E242C, ACCENT = 0xFF5FD068, TEAL = 0xFF2DD4BF, DIM = 0xFF8A94A0;
    private Typeface pixel;
    private LinearLayout root;
    private TextView status;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        pixel = Typeface.createFromAsset(getAssets(), "pixelify.ttf");
    }
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

    private void render() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(24), dp(48), dp(24), dp(24));
        TextView h = new TextView(this);
        h.setText("AGENTIC"); h.setTypeface(pixel); h.setTextSize(34); h.setTextColor(TEAL);
        h.setGravity(Gravity.CENTER);
        root.addView(h);
        status = new TextView(this);
        status.setTextColor(DIM); status.setTextSize(13); status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(6), 0, dp(18));
        root.addView(status);
        if (!overlayOk() || !a11yOk() || !notifOk()) stepPermissions();
        else if (!SecureStore.configured(this)) stepApi();
        else stepStart();
        setContentView(root);
    }

    private Button btn(String t, int color) {
        Button b = new Button(this);
        b.setText(t); b.setTextColor(Color.WHITE); b.setTextSize(15); b.setTypeface(pixel);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color); g.setCornerRadius(dp(12));
        b.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(52));
        lp.setMargins(0, dp(8), 0, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void stepPermissions() {
        status.setText("Step 1/3 - permissions chahiye");
        if (!notifOk()) {
            Button b = btn("ALLOW NOTIFICATIONS", 0xFF37414C);
            b.setOnClickListener(v -> { if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1); });
            root.addView(b);
        }
        if (!overlayOk()) {
            Button b = btn("ALLOW FLOATING BALL", 0xFF37414C);
            b.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
            root.addView(b);
        }
        if (!a11yOk()) {
            Button b = btn("ENABLE ACCESSIBILITY", 0xFF37414C);
            b.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
            root.addView(b);
            TextView tip = new TextView(this);
            tip.setText("Settings me 'Agentic' dhundho aur ON karo, phir wapas aao");
            tip.setTextColor(DIM); tip.setTextSize(12); tip.setGravity(Gravity.CENTER);
            tip.setPadding(0, dp(10), 0, 0);
            root.addView(tip);
        }
    }

    private void stepApi() {
        status.setText("Step 2/3 - apni AI connect karo (koi bhi OpenAI-compatible API)");
        EditText base = field("Base URL  (e.g. https://api.openai.com)", false);
        EditText key = field("API Key", true);
        root.addView(base); root.addView(key);
        Button connect = btn("CONNECT", TEAL);
        connect.setTextColor(0xFF0B0F13);
        root.addView(connect);
        ProgressBar spin = new ProgressBar(this);
        spin.setVisibility(View.GONE);
        root.addView(spin);
        TextView err = new TextView(this);
        err.setTextColor(0xFFE53935); err.setTextSize(12); err.setGravity(Gravity.CENTER);
        root.addView(err);
        connect.setOnClickListener(v -> {
            String b = base.getText().toString().trim(), k = key.getText().toString().trim();
            if (b.isEmpty() || k.isEmpty()) { err.setText("Base URL aur API key dono daalo"); return; }
            err.setText(""); spin.setVisibility(View.VISIBLE); connect.setEnabled(false);
            new Thread(() -> {
                try {
                    String[] res = Api.fetchModels(b, k);
                    String root0 = res[0];
                    String[] models = new String[res.length - 1];
                    System.arraycopy(res, 1, models, 0, models.length);
                    final String fb = root0;
                    runOnUiThread(() -> {
                        spin.setVisibility(View.GONE); connect.setEnabled(true);
                        new AlertDialog.Builder(this)
                            .setTitle("Model select karo (" + models.length + ")")
                            .setItems(models, (d, which) -> {
                                try {
                                    SecureStore.saveConfig(this, fb, k, models[which]);
                                    render();
                                } catch (Exception e) { err.setText("Save failed: " + e.getMessage()); }
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                    });
                } catch (Exception e) {
                    String m = e.getMessage() != null ? e.getMessage() : e.toString();
                    runOnUiThread(() -> { spin.setVisibility(View.GONE); connect.setEnabled(true); err.setText("Connect failed: " + m); });
                }
            }).start();
        });
    }

    private EditText field(String hint, boolean secret) {
        EditText e = new EditText(this);
        e.setHint(hint); e.setHintTextColor(0xFF5A6572);
        e.setTextColor(Color.WHITE); e.setTextSize(14);
        e.setBackgroundColor(PANEL); e.setPadding(dp(12), 0, dp(12), 0);
        e.setSingleLine(true);
        if (secret) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48));
        lp.setMargins(0, dp(8), 0, 0);
        e.setLayoutParams(lp);
        return e;
    }

    private void stepStart() {
        status.setText("Step 3/3 - ready. Model: " + SecureStore.model(this));
        Button start = btn("START AGENTIC", ACCENT);
        start.setTextColor(0xFF0B0F13); start.setTextSize(19);
        start.setOnClickListener(v -> {
            if (!overlayOk() || !a11yOk()) { render(); return; }
            Intent svc = new Intent(this, OverlayService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc); else startService(svc);
            moveTaskToBack(true);
        });
        root.addView(start);
        TextView change = new TextView(this);
        change.setText("change API / model"); change.setTextColor(DIM); change.setTextSize(12);
        change.setGravity(Gravity.CENTER); change.setPadding(0, dp(16), 0, 0);
        change.setOnClickListener(v -> { SecureStore.clear(this); render(); });
        root.addView(change);
    }
}
