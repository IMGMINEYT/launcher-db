package com.imgmine.launcher67;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

final class Api {
    static final String RAW = "https://raw.githubusercontent.com/IMGMINEYT/launcher-db/main/";
    static final String CDN = "https://cdn.jsdelivr.net/gh/IMGMINEYT/launcher-db@main/";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final java.util.concurrent.ExecutorService IO = java.util.concurrent.Executors.newSingleThreadExecutor();
    static volatile long lastFetch;
    static volatile List<String[]> promos = new ArrayList<>();

    interface CB { void ok(List<Addon> list); void fail(); }

    static List<Addon> parse(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        JSONArray a = o.optJSONArray("addons");
        List<Addon> out = new ArrayList<>();
        List<String[]> pr = new ArrayList<>();
        JSONArray pa = o.optJSONArray("promos");
        for (int k = 0; pa != null && k < pa.length() && pr.size() < 2; k++) {
            JSONObject p = pa.optJSONObject(k);
            if (p == null) continue;
            String im = p.optString("image"), lk = p.optString("link");
            if (!im.startsWith("https://")) continue;
            pr.add(new String[]{im, lk.startsWith("https://") || lk.startsWith("http://") ? lk : ""});
        }
        promos = pr;
        if (a == null) return out;
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        for (int i = 0; i < a.length(); i++) {
            JSONObject j = a.optJSONObject(i);
            if (j == null) continue;
            Addon d = new Addon();
            d.id = j.optString("id");
            d.name = j.optString("name");
            d.desc = j.optString("description");
            d.download = j.optString("download");
            d.type = j.optString("type", "file");
            if (d.type.equals("map")) d.type = "world";
            d.filename = j.optString("filename");
            d.size = j.optLong("size");
            d.icon = j.optBoolean("icon");
            String b = j.isNull("banner") ? "" : j.optString("banner");
            d.banner = b.startsWith("https://") ? b : null;
            try { d.added = f.parse(j.optString("added")).getTime(); } catch (Exception ignored) {}
            if (d.name.length() == 0 || !d.download.startsWith("https://")) continue;
            if (d.id.length() == 0) d.id = "a" + i;
            d.lname = Fuzzy.norm(d.name);
            d.ldesc = Fuzzy.norm(d.desc);
            out.add(d);
        }
        Collections.sort(out, new Comparator<Addon>() {
            public int compare(Addon x, Addon y) { return Long.compare(y.added, x.added); }
        });
        return out;
    }

    static List<Addon> cached(Context c) {
        try {
            File f = new File(c.getFilesDir(), "addons.json");
            if (!f.exists()) return null;
            return parse(new String(readAll(new FileInputStream(f), 4 << 20), "UTF-8"));
        } catch (Exception e) { return null; }
    }

    static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        try {
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > max) throw new IOException("too big");
            }
        } finally { in.close(); }
        return bo.toByteArray();
    }

    private static final String[] SOURCES = {
            "https://raw.githubusercontent.com/IMGMINEYT/launcher-db/main/data/addons.json?t=",
            "https://github.com/IMGMINEYT/launcher-db/raw/main/data/addons.json?t=",
            "https://api.github.com/repos/IMGMINEYT/launcher-db/contents/data/addons.json?ref=main&t=",
            "https://fastly.jsdelivr.net/gh/IMGMINEYT/launcher-db@main/data/addons.json?t=",
            "https://cdn.jsdelivr.net/gh/IMGMINEYT/launcher-db/data/addons.json?t="};

    private static byte[] get(String base) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(base + System.currentTimeMillis() / 60000).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(7000);
            c.setRequestProperty("User-Agent", "67Launcher/1.5");
            c.setRequestProperty("Accept", base.contains("api.github.com") ? "application/vnd.github.raw" : "application/json");
            if (c.getResponseCode() != 200) return null;
            byte[] b = readAll(c.getInputStream(), 8 << 20);
            new JSONObject(new String(b, "UTF-8"));   // must be valid
            return b;
        } catch (Exception e) { return null; }
    }

    /** Asks every source at the same time and keeps the newest answer, so one blocked or stale host cannot hang or outdate the list. */
    static byte[] race() {
        final java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newFixedThreadPool(SOURCES.length);
        java.util.concurrent.ExecutorCompletionService<byte[]> cs = new java.util.concurrent.ExecutorCompletionService<>(ex);
        for (final String s : SOURCES) cs.submit(new java.util.concurrent.Callable<byte[]>() { public byte[] call() { return get(s); } });
        byte[] best = null;
        String bestU = "";
        long start = System.currentTimeMillis(), firstOk = 0;
        try {
            for (int n = 0; n < SOURCES.length; n++) {
                long left = 10000 - (System.currentTimeMillis() - start);
                if (firstOk > 0) left = Math.min(left, 1500 - (System.currentTimeMillis() - firstOk));
                if (left <= 0) break;
                java.util.concurrent.Future<byte[]> f = cs.poll(left, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (f == null) break;
                byte[] b = f.get();
                if (b == null) continue;
                String u = new JSONObject(new String(b, "UTF-8")).optString("updated");
                if (best == null || u.compareTo(bestU) > 0) { best = b; bestU = u; }
                if (firstOk == 0) firstOk = System.currentTimeMillis();
            }
        } catch (Exception ignored) {}
        ex.shutdownNow();
        return best;
    }

    static void fetch(final Context app, final CB cb) {
        IO.execute(new Runnable() {
            public void run() {
                List<Addon> res = null;
                try {
                    byte[] b = race();
                    if (b != null) {
                        res = parse(new String(b, "UTF-8"));
                        FileOutputStream fo = new FileOutputStream(new File(app.getFilesDir(), "addons.json"));
                        fo.write(b);
                        fo.close();
                    }
                } catch (Exception ignored) {}
                final List<Addon> r = res;
                if (r != null) lastFetch = System.currentTimeMillis();
                MAIN.post(new Runnable() { public void run() { if (r != null) cb.ok(r); else cb.fail(); } });
            }
        });
    }
}
