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

        col.addView(step("1", "Allow display over other apps", true));
        allow = Ui.button(this, "ALLOW", Ui.GOLD, 0xFF1B1400);
        allow.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
                startActivity(i);
            }
        });
        col.addView(allow, wrap(0));

        notif = Ui.button(this, "ALLOW NOTIFICATIONS", Ui.CARD, Ui.INK);
        if (Build.VERSION.SDK_INT >= 33) {
            col.addView(step("2", "Show the Stop button (optional)", false));
            notif.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7); }
            });
            col.addView(notif, wrap(0));
        }

        col.addView(step(Build.VERSION.SDK_INT >= 33 ? "3" : "2", "Hide the ball when Minecraft is closed (Usage access)", false));
        usage = Ui.button(this, "ALLOW USAGE ACCESS", Ui.CARD, Ui.INK);
        usage.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { try { startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); } catch (Exception ignored) {} }
        });
        col.addView(usage, wrap(0));
        col.addView(step(Build.VERSION.SDK_INT >= 33 ? "4" : "3", "Start", false));
        start = Ui.button(this, "START + OPEN MINECRAFT", Ui.GREEN, 0xFF0C1A0E);
        start.setTextSize(16);
        start.setPadding(Ui.dp(14), Ui.dp(14), Ui.dp(14), Ui.dp(16));
        start.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { go(); } });
        col.addView(start, wrap(0));

        stop = Ui.button(this, "STOP FLOATING BALL", Ui.CARD, Ui.INK);
        stop.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { stopService(new Intent(MainActivity.this, OverlayService.class)); refresh(); }
        });
        LinearLayout.LayoutParams sl = wrap(0);
        sl.topMargin = Ui.dp(12);
        col.addView(stop, sl);

        // Stream-chat bridge (test increment): local loopback server + floating mini control panel.
        col.addView(step("+", "Stream chat bridge (test)", false));
        TextView bridge = Ui.button(this, "START BRIDGE + PANEL", Ui.CARD, Ui.INK);
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
        col.addView(bridge, wrap(0));
        TextView bridgeStop = Ui.button(this, "STOP BRIDGE", Ui.CARD, Ui.INK);
        bridgeStop.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { stopService(new Intent(MainActivity.this, StreamChatService.class)); }
        });
        LinearLayout.LayoutParams bs = wrap(0);
        bs.topMargin = Ui.dp(8);
        col.addView(bridgeStop, bs);
        TextView addonBtn = Ui.button(this, "INSTALL STREAM CHAT ADDON", Ui.CARD, Ui.INK);
        addonBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { installStreamAddon(); }
        });
        LinearLayout.LayoutParams ab = wrap(0);
        ab.topMargin = Ui.dp(8);
        col.addView(addonBtn, ab);
        TextView bridgeNote = label("Steps: 1) install the addon and activate BOTH packs on a cheats-ON world. 2) In Minecraft settings turn WebSockets ON, encrypted websockets OFF. 3) Start bridge, then in Minecraft chat run /connect 127.0.0.1:PORT shown on the panel. Live YouTube chat is not in this build yet; TEST sends a sample message.", 12, Ui.MUTE);
        bridgeNote.setTypeface(null);
        bridgeNote.setPadding(0, Ui.dp(8), 0, 0);
        col.addView(bridgeNote);

        hint = label("", 13, Ui.MUTE);
        hint.setTypeface(null);
        hint.setPadding(0, Ui.dp(18), 0, 0);
        col.addView(hint);
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

    private View step(String n, String text, boolean first) {
        TextView t = label(n + "   " + text, 15, Ui.INK);
        t.setPadding(0, first ? 0 : Ui.dp(20), 0, Ui.dp(8));
        return t;
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
