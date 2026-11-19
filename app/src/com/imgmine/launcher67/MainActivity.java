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
    private TextView allow, notif, start, stop, hint, dnsNote, usage, mcInfo;
    private LinearLayout promoBox;

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
        col.setPadding(Ui.dp(22), Ui.dp(40), Ui.dp(22), Ui.dp(28));
        sv.addView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout logo = new LinearLayout(this);
        logo.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark = label("67", 22, 0xFF0C1A0E);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(new Ui.Bevel(Ui.GREEN, 0xFF8BE592, Ui.GREEN_D, 0xFF000000));
        logo.addView(mark, new LinearLayout.LayoutParams(Ui.dp(56), Ui.dp(56)));
        TextView title = label("  LAUNCHER", 30, Ui.INK);
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

        // SETUP card: the three permissions as compact label+button rows
        col.addView(section("SETUP"));
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

        // FLOATING BALL card
        col.addView(section("FLOATING BALL"));
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

        // STREAM CHAT card: bridge + addon + YouTube + bubble time
        col.addView(section("STREAM CHAT"));
        LinearLayout stream = cardBox();
        LinearLayout brRow = new LinearLayout(this);
        TextView bridge = Ui.button(this, "START BRIDGE", Ui.GREEN, 0xFF0C1A0E);
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
        TextView addonBtn = Ui.button(this, "INSTALL STREAM CHAT ADDON", Ui.CARD, Ui.INK);
        addonBtn.setTextSize(12);
        addonBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { installStreamAddon(); }
        });
        LinearLayout.LayoutParams ab = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ab.topMargin = Ui.dp(8);
        stream.addView(addonBtn, ab);
        final EditText ytUrl = input("YouTube live URL", prefs.getString("stream_yt_url", ""));
        LinearLayout.LayoutParams yu = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        yu.topMargin = Ui.dp(8);
        stream.addView(ytUrl, yu);
        final EditText ytKey = input("YouTube Data API key", "");
        ytKey.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams yk = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        yk.topMargin = Ui.dp(6);
        stream.addView(ytKey, yk);
        TextView ytSave = Ui.button(this, "SAVE YOUTUBE", Ui.CARD, Ui.INK);
        ytSave.setTextSize(12);
        ytSave.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String k = ytKey.getText().toString().trim();
                android.content.SharedPreferences.Editor ed = prefs.edit()
                        .putString("stream_yt_url", ytUrl.getText().toString().trim());
                if (k.length() > 0) {
                    try {
                        ed.putString("stream_yt_key_enc", KeyBox.protect(MainActivity.this, k)).remove("stream_yt_key");
                        ytKey.setText("");
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "Secure storage unavailable, key not saved.", Toast.LENGTH_LONG).show();
                        return;
                    }
                }
                ed.apply();
                StreamChatService.ytQuotaStop = false;
                Toast.makeText(MainActivity.this, "Saved. Bridge picks it up within seconds.", Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams ys = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ys.topMargin = Ui.dp(6);
        stream.addView(ytSave, ys);

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

        TextView bridgeNote = label("Steps: 1) install the addon and activate BOTH packs on a cheats-ON world. 2) In Minecraft settings turn WebSockets ON, encrypted websockets OFF. 3) START BRIDGE, open the world, tap the 67 ball, then the chat icon for bridge controls. 4) COPY the /connect command there and run it in Minecraft chat. 5) For live chat paste your YouTube live URL and API key above and SAVE. TEST sends a sample message without YouTube.", 12, Ui.MUTE);
        bridgeNote.setTypeface(null);
        bridgeNote.setPadding(0, Ui.dp(8), 0, 0);
        stream.addView(bridgeNote);
        col.addView(stream, wrap(4));

        dnsNote = label("Ad-blocking Private DNS detected. Promos and support may not load or work while it is on.", 12, 0xFF1B1400);
        dnsNote.setTypeface(null);
        dnsNote.setBackground(Ui.panel(Ui.GOLD));
        dnsNote.setPadding(Ui.dp(10), Ui.dp(8), Ui.dp(10), Ui.dp(8));
        dnsNote.setVisibility(View.GONE);
        LinearLayout.LayoutParams dl = wrap(Ui.dp(18));
        col.addView(dnsNote, dl);
        promoBox = new LinearLayout(this);
        promoBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(promoBox, wrap(Ui.dp(8)));
        Images.init(this);
        setContentView(sv);
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
    private void installStreamAddon() {
        try {
            java.io.File dir = new java.io.File(getFilesDir(), "dl");
            dir.mkdirs();
            java.io.File out = new java.io.File(dir, "StreamChat.mcaddon");
            java.io.InputStream in = getAssets().open("streamchat.mcaddon");
            java.io.FileOutputStream fo = new java.io.FileOutputStream(out);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
            fo.close(); in.close();
            Downloader.install(this, "StreamChat.mcaddon");
        } catch (Exception e) {
            Toast.makeText(this, "Addon copy failed", Toast.LENGTH_LONG).show();
        }
    }

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
        showPromos(Api.promos);
        Api.cached(this);
        Api.fetch(getApplicationContext(), new Api.CB() {
            public void ok(java.util.List<Addon> l) { showPromos(Api.promos); }
            public void fail() {}
        });
    }

    private static final String[] BLOCK_DNS = {"adguard", "mullvad", "nextdns", "controld", "adblock", "blahdns", "dnswarden", "ads.", "libredns", "dns.sb", "rethinkdns", "ahadns", "adfree", "noads", "pi-hole", "pihole", "seby.io", "decloudus", "uncensoreddns"};

    private boolean adBlockDns() {
        try {
            String host = Settings.Global.getString(getContentResolver(), "private_dns_specifier");
            String mode = Settings.Global.getString(getContentResolver(), "private_dns_mode");
            if (host == null || !"hostname".equals(mode)) return false;
            host = host.toLowerCase();
            for (String k : BLOCK_DNS) if (host.contains(k)) return true;
        } catch (Exception ignored) {}
        return false;
    }

    private void showPromos(java.util.List<String[]> pr) {
        if (promoBox == null) return;
        promoBox.removeAllViews();
        for (final String[] p : pr) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackground(Ui.panel(Ui.CARD));
            iv.setPadding(Ui.dp(3), Ui.dp(3), Ui.dp(3), Ui.dp(3));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(130));
            lp.topMargin = Ui.dp(10);
            promoBox.addView(iv, lp);
            Images.load(iv, p[0], false);
            if (p[1].length() > 0) iv.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(p[1]))); } catch (Exception ignored) {}
                }
            });
        }
    }

    private void refresh() {
        boolean ok = canOverlay();
        allow.setText(ok ? "ALLOWED" : "ALLOW");
        allow.setAlpha(ok ? 0.5f : 1f);
        start.setAlpha(ok ? 1f : 0.5f);
        boolean us = OverlayService.hasUsage(this);
        usage.setText(us ? "USAGE ACCESS ON" : "ALLOW USAGE ACCESS");
        usage.setAlpha(us ? 0.5f : 1f);
        dnsNote.setVisibility(adBlockDns() ? View.VISIBLE : View.GONE);
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
