package com.imgmine.launcher67;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

public class MainActivity extends Activity {
    private TextView allow, notif, start, stop, hint, usage, mcInfo;

    private boolean canOverlay() { return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this); }

    private TextView label(String t, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(Ui.fix(t));
        v.setTypeface(Ui.PX);
        v.setTextSize(sp);
        v.setTextColor(color);
        return v;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(Ui.BG);
            getWindow().setNavigationBarColor(Ui.BG);
        }
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        sv.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(18), Ui.dp(24), Ui.dp(18), Ui.dp(24));
        sv.addView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout logo = new LinearLayout(this);
        logo.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark = label("67", 22, 0xFF0C1A0E);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(new Ui.Bevel(Ui.GREEN, 0xFF8BE592, Ui.GREEN_D, 0xFF000000));
        logo.addView(mark, new LinearLayout.LayoutParams(Ui.dp(56), Ui.dp(56)));
        TextView title = label("  LAUNCHER", 26, Ui.INK);
        logo.addView(title);
        col.addView(logo);

        TextView sub = label("Addons for Minecraft Bedrock, one tap away over your game.", 14, Ui.MUTE);
        sub.setTypeface(null);
        sub.setPadding(0, Ui.dp(12), 0, Ui.dp(10));
        col.addView(sub);

        mcInfo = label("", 12, Ui.MUTE);
        mcInfo.setTypeface(null);
        mcInfo.setPadding(0, 0, 0, Ui.dp(16));
        col.addView(mcInfo);

        final android.content.SharedPreferences prefs = getSharedPreferences("ui", MODE_PRIVATE);

        // FLOATING BALL card
        col.addView(section("START HERE"));
        LinearLayout ball = cardBox();
        start = Ui.button(this, "START + OPEN MINECRAFT", Ui.GREEN, 0xFF0C1A0E);
        start.setPadding(Ui.dp(14), Ui.dp(10), Ui.dp(14), Ui.dp(12));
        start.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { go(); }
        });
        ball.addView(start, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        stop = Ui.button(this, "STOP FLOATING BALL", Ui.CARD, Ui.INK);
        stop.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { stopService(new Intent(MainActivity.this, OverlayService.class)); refresh(); }
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = Ui.dp(8);
        ball.addView(stop, sl);
        hint = label("", 12, Ui.MUTE);
        hint.setTypeface(null);
        hint.setPadding(0, Ui.dp(8), 0, 0);
        ball.addView(hint);
        col.addView(ball, wrap(4));

        // SETUP card: the three permissions as compact label+button rows
        col.addView(section("FIRST: ALLOW THESE"));
        LinearLayout setup = cardBox();
        allow = Ui.button(this, "ALLOW", Ui.GOLD, 0xFF1B1400);
        allow.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
                startActivity(i);
            }
        });
        setup.addView(permRow("1  Show the ball over other apps", allow));
        notif = Ui.button(this, "ALLOW NOTIFICATIONS", Ui.CARD, Ui.INK);
        if (Build.VERSION.SDK_INT >= 33) {
            notif.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7); }
            });
            setup.addView(permRow("2  Stop button in notifications", notif));
        }
        usage = Ui.button(this, "ALLOW USAGE ACCESS", Ui.CARD, Ui.INK);
        usage.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { try { startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); } catch (Exception ignored) {} }
        });
        setup.addView(permRow(Build.VERSION.SDK_INT >= 33 ? "3  Hide ball when Minecraft is closed" : "2  Hide ball when Minecraft is closed", usage));
        col.addView(setup, wrap(4));

        // VIP card: entry point for the gated addon database (dark while Vip.ENABLED is false)
        col.addView(section("VIP"));
        LinearLayout vip = cardBox();
        vipStatus = label(vipStatusText(), 12, Ui.MUTE);
        vip.addView(vipStatus);
        TextView vipBtn = Ui.button(this, "ENTER VIP CODE", Ui.CARD, Ui.INK);
        vipBtn.setTextSize(12);
        vipBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showVipDialog(); }
        });
        LinearLayout.LayoutParams vb = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vb.topMargin = Ui.dp(8);
        vip.addView(vipBtn, vb);
        col.addView(vip, wrap(4));

        // STREAM CHAT card: bridge + addon + YouTube + bubble time
        TextView streamToggle = Ui.button(this, "STREAM CHAT  +", 0xFFAD2424, 0xFFFFFFFF);
        streamToggle.setTextSize(14);
        col.addView(streamToggle, wrap(12));
        final LinearLayout stream = cardBox();
        stream.setVisibility(prefs.getBoolean("stream_settings_open", false) ? View.VISIBLE : View.GONE);
        streamToggle.setText(stream.getVisibility() == View.VISIBLE ? "STREAM CHAT  -" : "STREAM CHAT  +");
        streamToggle.setOnClickListener(v -> {
            boolean open = stream.getVisibility() != View.VISIBLE;
            stream.setVisibility(open ? View.VISIBLE : View.GONE);
            streamToggle.setText(open ? "STREAM CHAT  -" : "STREAM CHAT  +");
            prefs.edit().putBoolean("stream_settings_open", open).apply();
        });
        LinearLayout brRow = new LinearLayout(this);
        TextView bridge = Ui.button(this, "START BRIDGE", Ui.RED, 0xFFFFFFFF);
        bridge.setTextSize(12);
        bridge.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!canOverlay()) {
                    Toast.makeText(MainActivity.this, "First allow display over other apps.", Toast.LENGTH_LONG).show();
                    allow.performClick();
                    return;
                }
                Intent i = new Intent(MainActivity.this, StreamChatService.class);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            }
        });
        brRow.addView(bridge, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));
        TextView bridgeStop = Ui.button(this, "STOP", Ui.CARD, Ui.INK);
        bridgeStop.setTextSize(12);
        bridgeStop.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { stopService(new Intent(MainActivity.this, StreamChatService.class)); }
        });
        LinearLayout.LayoutParams bsl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bsl.leftMargin = Ui.dp(8);
        brRow.addView(bridgeStop, bsl);
        stream.addView(brRow);
        TextView addonBtn = Ui.button(this, "INSTALL STREAM CHAT ADDON", 0xFFAD2424, 0xFFFFFFFF);
        addonBtn.setTextSize(12);
        addonBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { installStreamAddon(); }
        });
        LinearLayout.LayoutParams ab = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ab.topMargin = Ui.dp(8);
        stream.addView(addonBtn, ab);
        stream.addView(YtUi.controls(this));

        // Bubble lifetime (contract tysc:lifetime, clamped 1-10s, persisted by the addon)
        LinearLayout lt = new LinearLayout(this);
        lt.setGravity(Gravity.CENTER_VERTICAL);
        TextView ltLabel = label("Bubble show time", 12, Ui.INK);
        lt.addView(ltLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView minus = Ui.button(this, "-", Ui.CARD, Ui.INK);
        final TextView ltVal = label("", 14, Ui.GREEN);
        ltVal.setGravity(Gravity.CENTER);
        final TextView plus = Ui.button(this, "+", Ui.CARD, Ui.INK);
        ltVal.setText(Ui.fix(prefs.getInt("stream_lifetime", 5) + "s"));
        View.OnClickListener bump = new View.OnClickListener() {
            public void onClick(View v) {
                int cur = prefs.getInt("stream_lifetime", 5) + (v == plus ? 1 : -1);
                cur = Math.max(1, Math.min(10, cur));
                prefs.edit().putInt("stream_lifetime", cur).apply();
                ltVal.setText(Ui.fix(cur + "s"));
                StreamChatService.postControl(Ws.scriptLifetime(cur));
            }
        };
        minus.setOnClickListener(bump);
        plus.setOnClickListener(bump);
        lt.addView(minus, new LinearLayout.LayoutParams(Ui.dp(44), ViewGroup.LayoutParams.WRAP_CONTENT));
        lt.addView(ltVal, new LinearLayout.LayoutParams(Ui.dp(52), ViewGroup.LayoutParams.WRAP_CONTENT));
        lt.addView(plus, new LinearLayout.LayoutParams(Ui.dp(44), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams ltp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ltp.topMargin = Ui.dp(8);
        stream.addView(lt, ltp);

        TextView bridgeNote = label("1) INSTALL STREAM CHAT ADDON above, activate BOTH packs on a cheats-ON world.\n2) Minecraft settings: WebSockets ON, encrypted websockets OFF.\n3) START BRIDGE, open the world, tap the 67 ball, then the chat icon.\n4) COPY the /connect command there, run it in Minecraft chat.\n5) Choose YouTube API with your key, or phone-local without a key. Paste live URL, SAVE. Local also accepts @handle. TEST works without YouTube. If local stops, SAVE retries manually, never bypasses access limits.", 12, Ui.MUTE);
        bridgeNote.setTypeface(null);
        bridgeNote.setPadding(0, Ui.dp(8), 0, 0);
        stream.addView(bridgeNote);
        col.addView(stream, wrap(4));

        Images.init(this);
        setContentView(sv);
    }

    private TextView vipStatus;

    private String vipStatusText() {
        if (!Vip.ENABLED) return "Database hidden. VIP unlock service is not active yet.";
        String tok = Vip.token(this);
        if (tok.length() == 0) return "Enter your VIP code to unlock. Protected catalog may be empty until approved files are added.";
        long exp = Vip.tokenExp(tok);
        String day = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date(exp * 1000));
        return "VIP active until " + day + ".";
    }

    private void showVipDialog() {
        if (!Vip.ENABLED) {
            Toast.makeText(this, "Database is hidden. Secure VIP unlock service is not ready yet.", Toast.LENGTH_LONG).show();
            return;
        }
        final EditText in = input("VIP code", "");
        in.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Enter VIP code")
                .setView(in)
                .setPositiveButton("UNLOCK", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        String code = in.getText().toString().trim();
                        if (code.length() == 0) return;
                        Vip.check(getApplicationContext(), code, new Vip.CB() {
                            public void ok() {
                                vipStatus.setText(vipStatusText());
                                Toast.makeText(MainActivity.this, "VIP unlocked.", Toast.LENGTH_SHORT).show();
                            }
                            public void badCode() { Toast.makeText(MainActivity.this, "Wrong code.", Toast.LENGTH_LONG).show(); }
                            public void error() { Toast.makeText(MainActivity.this, "VIP server unreachable. Try again later.", Toast.LENGTH_LONG).show(); }
                        });
                    }
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private LinearLayout.LayoutParams wrap(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = top;
        return p;
    }

    private View section(String name) {
        TextView t = label(name, 13, Ui.GOLD);
        t.setPadding(0, Ui.dp(22), 0, 0);
        return t;
    }

    private LinearLayout cardBox() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.panel(Ui.CARD));
        c.setPadding(Ui.dp(12), Ui.dp(10), Ui.dp(12), Ui.dp(12));
        return c;
    }

    /** Compact label + action row used inside cards. */
    private View permRow(String text, TextView button) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = label(text, 12, Ui.INK);
        r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        button.setTextSize(11);
        button.setPadding(Ui.dp(10), Ui.dp(6), Ui.dp(10), Ui.dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(10);
        r.addView(button, lp);
        r.setPadding(0, Ui.dp(3), 0, Ui.dp(3));
        return r;
    }

    private EditText input(String hintText, String value) {
        EditText e = new EditText(this);
        e.setHint(hintText);
        e.setHintTextColor(Ui.MUTE);
        e.setText(value);
        e.setTextColor(Ui.INK);
        e.setTextSize(13);
        e.setSingleLine(true);
        e.setBackground(Ui.panel(Ui.CARD));
        e.setPadding(Ui.dp(12), Ui.dp(8), Ui.dp(12), Ui.dp(8));
        return e;
    }

    /** Copies the bundled stream-chat addon into the app download library and hands it to Minecraft. */
    private void installStreamAddon() { YtUi.installAddon(this); }

    private void go() {
        if (!canOverlay()) {
            Toast.makeText(this, "First allow display over other apps.", Toast.LENGTH_LONG).show();
            allow.performClick();
            return;
        }
        Intent s = new Intent(this, OverlayService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(s); else startService(s);
        Intent mc = getPackageManager().getLaunchIntentForPackage(McVer.MC);
        if (mc == null) mc = getPackageManager().getLaunchIntentForPackage(McVer.PREVIEW);
        if (mc == null) {
            Toast.makeText(this, "Minecraft is not installed on this phone.", Toast.LENGTH_LONG).show();
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + McVer.MC))); } catch (Exception ignored) {}
        } else {
            startActivity(mc);
        }
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
        Api.cached(this);
        Api.fetch(getApplicationContext(), new Api.CB() {
            public void ok(java.util.List<Addon> l) { }
            public void fail() {}
        });
    }


    private void refresh() {
        boolean ok = canOverlay();
        allow.setText(ok ? "ALLOWED" : "ALLOW");
        allow.setAlpha(ok ? 0.5f : 1f);
        start.setAlpha(ok ? 1f : 0.5f);
        boolean us = OverlayService.hasUsage(this);
        usage.setText(us ? "USAGE ACCESS ON" : "ALLOW USAGE ACCESS");
        usage.setAlpha(us ? 0.5f : 1f);
        String mv = McVer.status(this);
        mcInfo.setText(mv);
        mcInfo.setTextColor(McVer.raw(this) == null && McVer.previewRaw(this) == null ? Ui.RED : Ui.MUTE);
        stop.setVisibility(OverlayService.running ? View.VISIBLE : View.GONE);
        if (Build.VERSION.SDK_INT >= 33) {
            boolean n = checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
            notif.setText(n ? "NOTIFICATIONS ON" : "ALLOW NOTIFICATIONS");
            notif.setAlpha(n ? 0.5f : 1f);
        }
        hint.setText(OverlayService.running
                ? "The small ball is on. Open Minecraft and tap the ball to browse addons."
                : "After start, a small ball floats over Minecraft. Tap it, pick an addon, and it installs into the game.");
    }
}
