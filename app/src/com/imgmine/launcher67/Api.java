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
    static final String[] SRC = {CDN + "data/addons.json", RAW + "data/addons.json"};
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
            if (im.startsWith(RAW)) im = CDN + im.substring(RAW.length());
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
            if (b.startsWith(RAW)) b = CDN + b.substring(RAW.length());
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

    static void fetch(final Context app, final CB cb) {
        IO.execute(new Runnable() {
            public void run() {
                List<Addon> res = null;
                for (int i = 0; i < SRC.length && res == null; i++) {
                    try {
                        String u = SRC[i] + (i > 0 ? "?t=" + System.currentTimeMillis() / 60000 : "");
                        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                        c.setConnectTimeout(8000);
                        c.setReadTimeout(10000);
                        c.setRequestProperty("User-Agent", "67Launcher/1.0");
                        if (c.getResponseCode() != 200) continue;
                        byte[] b = readAll(c.getInputStream(), 8 << 20);
                        res = parse(new String(b, "UTF-8"));
                        FileOutputStream fo = new FileOutputStream(new File(app.getFilesDir(), "addons.json"));
                        fo.write(b);
                        fo.close();
                    } catch (Exception ignored) {}
                }
                final List<Addon> r = res;
                if (r != null) lastFetch = System.currentTimeMillis();
                MAIN.post(new Runnable() { public void run() { if (r != null) cb.ok(r); else cb.fail(); } });
            }
        });
    }
}
