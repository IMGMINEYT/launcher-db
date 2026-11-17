package com.imgmine.launcher67;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 9minecraft PE section: latest feed + search, limited to Bedrock (Pocket Edition) posts. */
final class NineMc {
    private static final String BASE = "https://www.9minecraft.net";
    private static final String UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36";
    private static final long TTL = 10 * 60 * 1000L;
    private static final Map<String, Object[]> CACHE = new HashMap<>();
    private static long lastCall;

    private static String get(String url, int max) throws Exception {
        synchronized (NineMc.class) {
            long w = 700 - (System.currentTimeMillis() - lastCall);
            if (w > 0) Thread.sleep(w);
            lastCall = System.currentTimeMillis();
        }
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(7000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "text/html");
        if (c.getResponseCode() != 200) throw new java.io.IOException("http " + c.getResponseCode());
        return new String(Api.readAll(c.getInputStream(), max), "UTF-8");
    }

    /** One page of PE posts plus whether another page may exist. null on error. */
    static final class Page {
        final List<Addon> items;
        final boolean more;
        Page(List<Addon> i, boolean m) { items = i; more = m; }
    }

    /** Blocking. Latest posts from the PE mods/addons category. */
    static Page browse(int page) {
        String url = page <= 1 ? BASE + "/category/minecraft-pe/mods-addons/"
                : BASE + "/category/minecraft-pe/mods-addons/page/" + page + "/";
        return fetch(url, page, null);
    }

    /** Blocking. Whole-site search, cards without a PE category dropped. */
    static Page search(String q, int page) {
        String enc;
        try { enc = URLEncoder.encode(q.trim(), "UTF-8"); } catch (Exception e) { return null; }
        if (enc.length() < 2) return new Page(new ArrayList<Addon>(), false);
        String url = page <= 1 ? BASE + "/?s=" + enc : BASE + "/page/" + page + "/?s=" + enc;
        return fetch(url, page, Boolean.TRUE);
    }

    private static Page fetch(String url, int page, Boolean peOnly) {
        String ck = url;
        synchronized (CACHE) {
            Object[] hit = CACHE.get(ck);
            if (hit != null && System.currentTimeMillis() - (Long) hit[0] < TTL) return (Page) hit[1];
        }
        try {
            String h = get(url, 2 << 20);
            List<Addon> out = new ArrayList<>();
            Matcher am = Pattern.compile("<article id=\"post-(\\d+)\" class=\"result-card ([^\"]*)\"").matcher(h);
            List<int[]> spans = new ArrayList<>();
            List<String> ids = new ArrayList<>(), classes = new ArrayList<>();
            while (am.find()) {
                spans.add(new int[]{am.start(), am.end()});
                ids.add(am.group(1));
                classes.add(am.group(2));
            }
            for (int i = 0; i < spans.size(); i++) {
                String cls = classes.get(i);
                boolean pe = cls.contains("category-minecraft-pe");
                if (peOnly != null && peOnly && !pe) continue;
                int from = spans.get(i)[1];
                int to = i + 1 < spans.size() ? spans.get(i + 1)[0] : Math.min(h.length(), from + 6000);
                String card = h.substring(from, to);
                Addon d = new Addon();
                d.nine = true;
                d.id = "9mc" + ids.get(i);
                d.type = cls.contains("category-maps") ? "world"
                        : cls.contains("category-texture-packs") ? "texture"
                        : cls.contains("category-skins") ? "skin"
                        : cls.contains("category-seeds") ? "seed" : "addon";
                Matcher tm = Pattern.compile("<h2 class=\"card-title\">\\s*<a href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL).matcher(card);
                if (!tm.find()) continue;
                d.page = tm.group(1);
                d.name = clean(tm.group(2));
                Matcher im = Pattern.compile("<img[^>]+src=\"(https://img\\d?\\.9minecraft\\.net/[^\"]+)\"").matcher(card);
                if (im.find()) d.banner = im.group(1);
                d.icon = true;
                Matcher em = Pattern.compile("<p class=\"excerpt\">(.*?)</p>", Pattern.DOTALL).matcher(card);
                if (em.find()) d.desc = clean(em.group(1));
                if (d.name.length() == 0 || !d.page.startsWith(BASE)) continue;
                d.lname = Fuzzy.norm(d.name);
                d.ldesc = Fuzzy.norm(d.desc);
                out.add(d);
            }
            boolean more = h.contains("next page-numbers")
                    || h.contains("/page/" + (page + 1) + "/");
            Page p = new Page(out, more);
            synchronized (CACHE) { CACHE.put(ck, new Object[]{System.currentTimeMillis(), p}); }
            return p;
        } catch (Exception e) { return null; }
    }

    private static String clean(String s) {
        s = s.replaceAll("<[^>]+>", " ");
        s = s.replace("&#8211;", "-").replace("&#8212;", "-").replace("&#8217;", "'")
                .replace("&#8220;", "\"").replace("&#8221;", "\"").replace("&amp;", "&")
                .replace("&quot;", "\"").replace("&#038;", "&").replace("&#8230;", "...");
        return s.replaceAll("\\s+", " ").trim();
    }

    /** Blocking. First direct file link on a post page (.mcaddon/.mcpack/.mcworld), or null. */
    static String fileFor(Addon a) {
        try {
            String h = get(a.page, 2 << 20);
            Matcher m = Pattern.compile("href=\"(https://(?:dl2|files2)\\.9minecraft\\.net/dl2\\.php\\?file=[^\"]+?\\.(?:mcaddon|mcpack|mcworld|mctemplate))\"").matcher(h);
            while (m.find()) {
                String u = m.group(1).replace("&amp;", "&");
                if (u.contains("files2.")) return u;   // files2 redirected cleanly in checks; prefer it
            }
            m.reset();
            return m.find() ? m.group(1).replace("&amp;", "&") : null;
        } catch (Exception e) { return null; }
    }

    static String fileName(String url) {
        int i = url.indexOf("file=");
        String f = i >= 0 ? url.substring(i + 5) : "addon.mcaddon";
        int amp = f.indexOf('&');
        if (amp >= 0) f = f.substring(0, amp);
        try { f = java.net.URLDecoder.decode(f, "UTF-8"); } catch (Exception ignored) {}
        return f.replaceAll("[^A-Za-z0-9._()+ -]", "_");
    }
}
