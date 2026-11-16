package com.imgmine.launcher67;

import android.content.Context;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** MCPEDL addons + texture packs through the same public JSON search their website uses. */
final class Mc {
    private static final String API = "https://api.mcpedl.com/api/search/advanced?";
    private static final String UA = "67Launcher/1.6 (+https://67-launcher.netlify.app)";
    private static final long TTL = 10 * 60 * 1000L;
    private static final Map<String, Object[]> CACHE = new HashMap<>();
    private static long lastCall;

    private static byte[] get(String url, int max) throws Exception {
        synchronized (Mc.class) {   // be gentle: at most ~2 requests per second
            long w = 500 - (System.currentTimeMillis() - lastCall);
            if (w > 0) Thread.sleep(w);
            lastCall = System.currentTimeMillis();
        }
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(9000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "application/json,text/html");
        if (c.getResponseCode() != 200) throw new java.io.IOException("http " + c.getResponseCode());
        return Api.readAll(c.getInputStream(), max);
    }

    /** Blocking. Call off the main thread. [] when nothing found; null on error. */
    static List<Addon> search(String query) {
        String q = query.trim();
        if (q.length() < 2) return new ArrayList<>();
        String ck = q.toLowerCase(Locale.ROOT);
        synchronized (CACHE) {
            Object[] hit = CACHE.get(ck);
            if (hit != null && System.currentTimeMillis() - (Long) hit[0] < TTL) {
                @SuppressWarnings("unchecked") List<Addon> l = (List<Addon>) hit[1];
                return l;
            }
        }
        List<Addon> out = new ArrayList<>();
        try {
            for (int t : new int[]{1, 3}) {
                String u = API + "q=" + URLEncoder.encode(q, "UTF-8") + "&type_id=" + t + "&page=1&per_page=15";
                JSONObject r = new JSONObject(new String(get(u, 2 << 20), "UTF-8"));
                out.addAll(parse(r.optJSONArray("results"), t == 1 ? "addon" : "texture"));
            }
        } catch (Exception e) { return null; }
        synchronized (CACHE) { CACHE.put(ck, new Object[]{System.currentTimeMillis(), out}); }
        return out;
    }

    static List<Addon> parse(JSONArray a, String type) {
        List<Addon> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject j = a.optJSONObject(i);
            if (j == null) continue;
            Addon d = new Addon();
            d.cf = true;
            String slug = j.optString("slug");
            d.id = "mc" + j.optLong("id");
            d.name = j.optString("title");
            d.desc = j.optString("summary").trim();
            d.type = type;
            d.author = j.optString("display_name").trim();
            String im = j.optString("image");
            d.banner = im.startsWith("https://") ? im : null;
            d.icon = true;
            d.page = slug.length() > 0 ? "https://mcpedl.com/" + slug + "/" : "";
            d.download = "";
            if (d.name.length() == 0 || d.page.length() == 0) continue;
            d.lname = Fuzzy.norm(d.name);
            d.ldesc = Fuzzy.norm(d.desc);
            out.add(d);
        }
        return out;
    }

    /** Blocking. Reads the post page and returns its newest file link, or null. */
    static String[] fileFor(Addon a) {
        try {
            String h = new String(get(a.page, 3 << 20), "UTF-8").replace("\\u002F", "/");
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("https://edge\\.mcpedl\\.com/files/[A-Za-z0-9_/.%()+-]+\\.(mcaddon|mcpack|mcworld|mctemplate)").matcher(h);
            if (m.find()) {
                String u = m.group();
                return new String[]{u, u.substring(u.lastIndexOf('/') + 1)};
            }
        } catch (Exception ignored) {}
        return null;
    }
}
