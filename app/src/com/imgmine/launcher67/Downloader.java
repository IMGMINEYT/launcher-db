package com.imgmine.launcher67;

import android.content.*;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;

final class Downloader {
    static final String MC = "com.mojang.minecraftpe";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);

    interface Listener { void progress(int pct); void done(boolean ok); }

    static boolean isAddonUrl(String url) {
        if (url == null) return false;
        String u = url.toLowerCase();
        int q = u.indexOf('?');
        if (q > 0) u = u.substring(0, q);
        return u.startsWith("https://") && (u.endsWith(".mcaddon") || u.endsWith(".mcpack") || u.endsWith(".mcworld")
                || u.endsWith(".mctemplate") || u.contains("/releases/download/"));
    }

    static boolean have(Context c, Addon a) {
        File f = new File(new File(c.getFilesDir(), "dl"), a.safeFile());
        return f.isFile() && (a.size <= 0 || f.length() == a.size);
    }

    static String fileName(String url) {
        String p = Uri.parse(url).getLastPathSegment();
        if (p == null || p.length() == 0) p = "addon.mcaddon";
        p = p.replaceAll("[^A-Za-z0-9._-]", "_");
        if (!p.contains(".")) p += ".mcaddon";
        return p.length() > 100 ? p.substring(p.length() - 100) : p;
    }

    static void start(final Context app, final String url, final String name, final Listener l) {
        POOL.execute(new Runnable() {
            public void run() {
                boolean ok = false;
                File dir = new File(app.getFilesDir(), "dl");
                dir.mkdirs();
                File out = new File(dir, name);
                File tmp = new File(dir, name + ".part");
                try {
                    HttpURLConnection c = open(url);
                    long total = c.getContentLengthLong();
                    InputStream in = c.getInputStream();
                    FileOutputStream fo = new FileOutputStream(tmp);
                    byte[] buf = new byte[65536];
                    long got = 0, last = 0;
                    int n, lastPct = -1;
                    while ((n = in.read(buf)) > 0) {
                        fo.write(buf, 0, n);
                        got += n;
                        long now = System.currentTimeMillis();
                        if (now - last > 120) {
                            last = now;
                            final int pct = total > 0 ? (int) (got * 100 / total) : -1;
                            if (pct != lastPct) { lastPct = pct; post(l, pct); }
                        }
                    }
                    fo.close();
                    in.close();
                    if (total > 0 && got != total) throw new IOException("short");
                    if (out.exists()) out.delete();
                    ok = tmp.renameTo(out);
                } catch (Exception e) {
                    tmp.delete();
                }
                final boolean fok = ok;
                MAIN.post(new Runnable() {
                    public void run() {
                        if (l != null) l.done(fok);
                        if (fok) install(app, name); else Toast.makeText(app, "Download failed. Try again.", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
        trim(app);
    }

    private static void post(final Listener l, final int pct) {
        if (l == null) return;
        MAIN.post(new Runnable() { public void run() { l.progress(pct); } });
    }

    private static HttpURLConnection open(String url) throws IOException {
        for (int i = 0; i < 6; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(12000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", "67Launcher/1.0");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new IOException("redirect");
                url = new URL(new URL(url), loc).toString();
                if (!url.startsWith("https://")) throw new IOException("insecure redirect");
                continue;
            }
            if (code != 200) throw new IOException("http " + code);
            return c;
        }
        throw new IOException("redirects");
    }

    private static void trim(Context app) {
        try {
            File[] fs = new File(app.getFilesDir(), "dl").listFiles();
            if (fs == null || fs.length <= 12) return;
            java.util.Arrays.sort(fs, new java.util.Comparator<File>() {
                public int compare(File a, File b) { return Long.compare(a.lastModified(), b.lastModified()); }
            });
            for (int i = 0; i < fs.length - 12; i++) fs[i].delete();
        } catch (Exception ignored) {}
    }

    /** Hands the downloaded file to Minecraft so Bedrock imports it. */
    static void install(Context c, String fileName) {
        Uri u = Uri.parse("content://" + ShareProvider.AUTH + "/dl/" + Uri.encode(fileName));
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(u, "application/octet-stream");
        i.setClipData(ClipData.newRawUri("addon", u));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        i.setPackage(MC);
        try {
            c.startActivity(i);
            return;
        } catch (ActivityNotFoundException e) { /* fall through */ }
        try {
            i.setPackage(null);
            Intent ch = Intent.createChooser(i, "Open addon with");
            ch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(ch);
        } catch (Exception e) {
            Toast.makeText(c, "Minecraft is not installed.", Toast.LENGTH_LONG).show();
        }
    }
}
