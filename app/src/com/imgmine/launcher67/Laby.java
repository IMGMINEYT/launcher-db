package com.imgmine.launcher67;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Laby.net single skins: search + trending list, render previews, Mojang CDN skin files. */
final class Laby {
    private static final String API = "https://laby.net/api/v3/";
    private static final String UA = "67Launcher/2.0 (+https://67-launcher.netlify.app)";
    private static final long TTL = 10 * 60 * 1000L;
    private static final Map<String, Object[]> CACHE = new HashMap<>();
    private static long lastCall;

    private static byte[] get(String url, int max) throws Exception {
        synchronized (Laby.class) {
            long w = 500 - (System.currentTimeMillis() - lastCall);
            if (w > 0) Thread.sleep(w);
            lastCall = System.currentTimeMillis();
        }
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(9000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "application/json,image/webp,image/png");
        if (c.getResponseCode() != 200) throw new java.io.IOException("http " + c.getResponseCode());
        return Api.readAll(c.getInputStream(), max);
    }

    /** Blocking. Single skins, most-used first. Empty query returns the trending list. null on error. */
    static List<Addon> search(String query, int size) {
        String q = query.trim();
        String ck = "l:" + size + ":" + q.toLowerCase(Locale.ROOT);
        synchronized (CACHE) {
            Object[] hit = CACHE.get(ck);
            if (hit != null && System.currentTimeMillis() - (Long) hit[0] < TTL) {
                @SuppressWarnings("unchecked") List<Addon> l = (List<Addon>) hit[1];
                return l;
            }
        }
        try {
            String u = API + "search/textures/skin?order=most_used&size=" + size;
            if (q.length() > 0) u += "&input=" + URLEncoder.encode(q, "UTF-8");
            JSONObject r = new JSONObject(new String(get(u, 1 << 20), "UTF-8"));
            JSONArray a = r.optJSONArray("results");
            List<Addon> out = new ArrayList<>();
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject j = a.optJSONObject(i);
                if (j == null) continue;
                String hash = j.optString("image_hash");
                if (hash.length() == 0) continue;
                Addon d = new Addon();
                d.laby = true;
                d.id = "lb" + hash;
                d.labyHash = hash;
                d.slim = j.optBoolean("slim");
                d.type = "skin";
                d.name = name(j);
                d.desc = "";
                d.banner = API + "render/skin/" + hash + ".webp?height=200&width=200";
                d.icon = true;
                d.page = "https://laby.net/skins/" + hash;
                d.download = "";
                d.filename = "laby-" + hash.substring(0, 8) + ".mcpack";
                d.lname = Fuzzy.norm(d.name);
                d.ldesc = "";
                out.add(d);
            }
            synchronized (CACHE) { CACHE.put(ck, new Object[]{System.currentTimeMillis(), out}); }
            return out;
        } catch (Exception e) { return null; }
    }

    private static String name(JSONObject j) {
        String tags = j.optString("tags").trim();
        StringBuilder s = new StringBuilder();
        for (String t : tags.split("\\s+")) {
            if (t.length() == 0) continue;
            if (s.length() > 0) s.append(' ');
            s.append(Character.toUpperCase(t.charAt(0))).append(t.substring(1));
            if (s.length() > 30) break;
        }
        if (s.length() == 0) {
            JSONArray tt = j.optJSONArray("top_tags");
            for (int i = 0; tt != null && i < tt.length() && i < 3; i++) {
                if (s.length() > 0) s.append(' ');
                s.append(tt.optString(i));
            }
        }
        return s.length() == 0 ? "Skin" : s.toString();
    }

    /** Blocking. The raw 64x64 skin PNG bytes, fetched via Mojang's texture CDN. null on error. */
    static byte[] skinPng(Addon a) {
        try {
            JSONObject meta = new JSONObject(new String(get(API + "texture/" + a.labyHash + "/skin/meta", 1 << 20), "UTF-8"));
            String th = meta.optString("minecraft_texture_hash");
            if (th.length() == 0) return null;
            a.slim = meta.optBoolean("slim", a.slim);
            return get("https://textures.minecraft.net/texture/" + th, 1 << 20);
        } catch (Exception e) { return null; }
    }
}
