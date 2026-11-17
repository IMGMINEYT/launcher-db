package com.imgmine.launcher67;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** WiseHosting Bedrock seeds: category (tag) lists + per-seed details. Bedrock-only by server filter. */
final class Seeds {
    private static final String UA = "Mozilla/5.0 (Linux; Android 14) 67Launcher";
    private static final long TTL = 10 * 60 * 1000L;
    private static final Map<String, Object[]> CACHE = new HashMap<>();

    /** Tag display name + slug, in the site's own filter order. */
    static final String[][] TAGS = {
        {"Ancient City", "ancient-city"}, {"Badlands", "badlands"}, {"Caldera", "caldera"},
        {"Cave", "cave"}, {"Cherry Blossom", "cherry-blossom"}, {"Cliffs", "cliffs"},
        {"Crater", "crater"}, {"Dappled Forest", "dappled-forest"}, {"Desert", "desert"},
        {"Forest", "forest"}, {"Frozen Ocean", "frozen-ocean"}, {"Frozen Peaks", "frozen-peaks"},
        {"Hill", "hill"}, {"Ice Spikes", "ice-spikes"}, {"Island", "island"},
        {"Jagged Peaks", "jagged-peaks"}, {"Jungle", "jungle"}, {"Lake", "lake"},
        {"Lush Caves", "lush-caves"}, {"Meadows", "meadows"}, {"Mountain", "mountain"},
        {"Mushroom Fields", "mushroom-fields"}, {"Ocean", "ocean"}, {"Pale Garden", "pale-garden"},
        {"Pillager Outpost", "pillager-outpost"}, {"Rare", "rare"}, {"Ravine", "ravine"},
        {"River", "river"}, {"Savanna", "savanna"}, {"Snowy Slopes", "snowy-slopes"},
        {"Stony Peaks", "stony-peaks"}, {"Stony Shore", "stony-shore"}, {"Swamp", "swamp"},
        {"Taiga", "taiga"}, {"Valley", "valley"}, {"Village", "village"},
        {"Weird", "weird"}, {"Woodland Mansion", "woodland-mansion"}
    };

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(7000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", UA);
        if (c.getResponseCode() != 200) throw new java.io.IOException("http " + c.getResponseCode());
        return new String(Api.readAll(c.getInputStream(), 2 << 20), "UTF-8");
    }

    private static final Pattern CARD = Pattern.compile(
        "<a href=\"https://wisehosting\\.com/minecraft-seeds/([a-z0-9-]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL);
    private static final Pattern IMG = Pattern.compile("(https://cdn\\.wisehosting\\.com/seeds/[^\"\\s]+\\.avif)");
    private static final Pattern TAGSTRIP = Pattern.compile("<[^>]+>");

    /** Blocking. First page of Bedrock seeds for a tag (null slug = all Bedrock). null on error. */
    @SuppressWarnings("unchecked")
    static List<Addon> browse(String tagSlug) {
        String ck = "b:" + (tagSlug == null ? "" : tagSlug);
        synchronized (CACHE) {
            Object[] hit = CACHE.get(ck);
            if (hit != null && System.currentTimeMillis() - (Long) hit[0] < TTL) return (List<Addon>) hit[1];
        }
        try {
            String u = "https://wisehosting.com/minecraft-seeds/search?edition=bedrock";
            if (tagSlug != null) u += "&tags%5B0%5D=" + tagSlug;
            String html = get(u);
            List<Addon> out = new ArrayList<>();
            Matcher m = CARD.matcher(html);
            Set<String> seen = new HashSet<>();
            while (m.find()) {
                String slug = m.group(1);
                if (!seen.add(slug)) continue;
                String body = m.group(2);
                Addon d = new Addon();
                d.id = "sd" + slug;
                d.type = "seed";
                d.page = "https://wisehosting.com/minecraft-seeds/" + slug;
                Matcher im = IMG.matcher(body);
                if (im.find()) d.banner = "https://images.weserv.nl/?url="
                    + im.group(1).replaceFirst("^https?://", "").replace("%", "%25") + "&w=640&output=jpg";
                String txt = TAGSTRIP.matcher(body).replaceAll("|").replace('\u00a0', ' ');
                String[] toks = txt.split("\\|+");
                String name = "", ver = "";
                boolean bedrock = false;
                for (String t : toks) {
                    t = t.trim();
                    if (t.length() == 0) continue;
                    if (t.equals("Bedrock")) { bedrock = true; continue; }
                    if (t.equals("Verified") || t.equals("Java")) continue;
                    if (name.length() == 0) { name = t; continue; }
                    if (bedrock && ver.length() == 0 && t.matches("\\d+(\\.\\d+)*")) ver = t;
                }
                if (!bedrock || name.length() == 0) continue;   // Bedrock-only, no inference
                d.name = name;
                d.desc = "";
                d.author = "";
                if (ver.length() > 0) d.vers = new ArrayList<>(Collections.singletonList(ver));
                d.lname = Fuzzy.norm(name);
                d.ldesc = "";
                out.add(d);
            }
            synchronized (CACHE) { CACHE.put(ck, new Object[]{System.currentTimeMillis(), out}); }
            return out;
        } catch (Exception e) { return null; }
    }

    private static final Pattern CODE = Pattern.compile("<code[^>]*>(.*?)</code>", Pattern.DOTALL);

    /** Blocking. Fetches seed string + coords + description for a card. false on error. */
    static boolean fillDetail(Addon d) {
        if (d.seed != null) return true;
        String ck = "d:" + d.id;
        synchronized (CACHE) {
            Object[] hit = CACHE.get(ck);
            if (hit != null && System.currentTimeMillis() - (Long) hit[0] < TTL) {
                String[] v = (String[]) hit[1];
                d.seed = v[0]; d.seedCoords = v[1]; d.desc = v[2]; d.author = v[3];
                return true;
            }
        }
        try {
            String html = get(d.page);
            Matcher cm = CODE.matcher(html);
            String seed = null, coords = "";
            if (cm.find()) seed = TAGSTRIP.matcher(cm.group(1)).replaceAll("").trim();
            if (cm.find()) coords = TAGSTRIP.matcher(cm.group(1)).replaceAll("").trim();
            if (seed == null || seed.length() == 0) return false;
            String author = "";
            Matcher am = Pattern.compile("Posted by\\s*</[^>]+>\\s*<[^>]+>([^<]+)<").matcher(html);
            if (am.find()) author = am.group(1).trim();
            String desc = "";
            int di = html.indexOf("Description");
            if (di >= 0) {
                int end = html.indexOf("Found the perfect seed", di);
                String seg = html.substring(di, end > di ? Math.min(end, di + 24000) : Math.min(html.length(), di + 24000));
                seg = TAGSTRIP.matcher(seg).replaceAll(" ").replace('\u00a0', ' ')
                    .replace("  ", " ").trim();
                if (seg.startsWith("Description")) seg = seg.substring(11).trim();
                if (seg.length() > 900) seg = seg.substring(0, 900).trim() + "...";
                desc = seg;
            }
            d.seed = seed; d.seedCoords = coords; d.desc = desc; d.author = author;
            synchronized (CACHE) { CACHE.put(ck, new Object[]{System.currentTimeMillis(), new String[]{seed, coords, desc, author}}); }
            return true;
        } catch (Exception e) { return false; }
    }
}
