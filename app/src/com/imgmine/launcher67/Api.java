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
    /** True when the last catalog fetch failed because the VIP gate rejected/missing the token. */
    static volatile boolean lastFailAuth;
    /** True when the last catalog fetch failed because the gate itself is off (503 gate_off). */
    static volatile boolean lastFailGateOff;

    interface CB { void ok(List<Addon> list); void fail(); }

    /** VIP-mode catalog fetch: one HTTPS GET to the gate with the token header.
     *  Returns the catalog body, or null on failure (401 also sets lastFailAuth). */
    private static byte[] gateCatalog(String tok) {
        HttpURLConnection c = null;
        try {
            c = Net.connections.connect(new URL(Vip.GATE + "/vip/catalog"));
            c.setConnectTimeout(5000);
            c.setReadTimeout(7000);
            c.setRequestProperty("User-Agent", "67Launcher/1.5");
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("X-VIP-Token", tok);
            int rc = c.getResponseCode();
            if (rc == 401) { lastFailAuth = true; return null; }
            if (rc == 503) { lastFailGateOff = true; return null; }   // fail closed: gate off serves nothing
            if (rc != 200) return null;
            byte[] b = readAll(c.getInputStream(), 8 << 20);
            new JSONObject(new String(b, "UTF-8"));   // must be valid
            return b;
        } catch (Exception e) { return null; }
        finally { if (c != null) c.disconnect(); }
    }

    /** Download URL acceptance. Public catalog: https only. VIP mode: the resolved URL
     *  must be same-origin with the configured gate (https in production; plain http is
     *  accepted only for a loopback test gate). Anything else is dropped, never followed. */
    static boolean acceptDownload(String u) {
        if (Vip.ENABLED) return sameOrigin(Vip.GATE, u);
        return u.startsWith("https://");
    }

    /** Same scheme, host (case-insensitive) and effective port. */
    static boolean sameOrigin(String base, String u) {
        try {
            java.net.URI b = new java.net.URI(base), x = new java.net.URI(u);
            if (b.getScheme() == null || b.getHost() == null || x.getScheme() == null || x.getHost() == null) return false;
            if (!b.getScheme().equalsIgnoreCase(x.getScheme())) return false;
            if (!b.getHost().equalsIgnoreCase(x.getHost())) return false;
            return effPort(b) == effPort(x);
        } catch (Exception e) { return false; }
    }

    private static int effPort(java.net.URI u) {
        if (u.getPort() >= 0) return u.getPort();
        return "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
    }

    /** VIP mode: gate signed URLs live 10 minutes (contract v3). When a gate download
     *  hits 404 (expired signature), re-fetch the catalog and return the fresh signed URL
     *  for the same file id, or null when the file, token or catalog is unavailable.
     *  Never returns an off-origin URL. */
    static String refreshGateUrl(Context c, String stale) {
        if (!Vip.ENABLED || !sameOrigin(Vip.GATE, stale)) return null;
        String fid;
        try {
            String path = new URL(stale).getPath();
            fid = path.substring(path.lastIndexOf('/') + 1);
        } catch (Exception e) { return null; }
        if (fid.length() == 0) return null;
        String tok = Vip.token(c);
        if (tok.length() == 0) return null;
        byte[] b = gateCatalog(tok);
        if (b == null) return null;
        try {
            JSONArray a = new JSONObject(new String(b, "UTF-8")).optJSONArray("addons");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject j = a.optJSONObject(i);
                if (j == null || !fid.equals(j.optString("id"))) continue;
                String d = j.optString("download");
                if (d.startsWith("/")) d = Vip.GATE + d;
                return acceptDownload(d) ? d : null;
            }
        } catch (Exception ignored) {}
        return null;
    }
    /** True only for a gate explicitly configured on this machine (test builds). */
    static boolean loopbackGate(String u) {
        if (!sameOrigin(Vip.GATE, u)) return false;
        try {
            String h = new java.net.URI(Vip.GATE).getHost();
            return "127.0.0.1".equals(h) || "localhost".equalsIgnoreCase(h);
        } catch (Exception e) { return false; }
    }

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
            // VIP gate catalogs use root-relative file URLs; resolve against the gate base.
            if (d.download.startsWith("/")) d.download = Vip.GATE + d.download;
            d.type = j.optString("type", "file");
            if (d.type.equals("map")) d.type = "world";
            d.filename = j.optString("filename");
            d.size = j.optLong("size");
            d.icon = j.optBoolean("icon");
            String b = j.isNull("banner") ? "" : j.optString("banner");
            d.banner = b.startsWith("https://") ? b : null;
            try { d.added = f.parse(j.optString("added")).getTime(); } catch (Exception ignored) {}
            if (d.name.length() == 0 || !acceptDownload(d.download)) continue;
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
        if(Vip.feedLocked(c))return null;
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
                lastFailAuth = false;
                lastFailGateOff = false;
                try {
                    byte[] b;
                    if (Vip.ENABLED) {
                        // VIP mode: the gate is the only catalog source. No silent
                        // fallback to the public mirrors (contract: honest failure).
                        String tok = Vip.token(app);
                        if (tok.length() == 0) { lastFailAuth = true; b = null; }
                        else b = gateCatalog(tok);
                    } else {
                        // Owner requested no database discovery before verified VIP.
                        // Gate not deployed: return an empty DB, never public mirrors.
                        res = new ArrayList<Addon>(); b = null;
                    }
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
