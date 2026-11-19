package com.imgmine.launcher67;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** MCPEDL addons, maps, texture packs + skins through the same public JSON search their website uses. */
final class Mc {
    private static final String API = "https://api.mcpedl.com/api/search/advanced?";
    private static final String UA = "67Launcher/2.0 (+https://67-launcher.netlify.app)";
    private static final long TTL = 10 * 60 * 1000L;
    private static final Map<String, Object[]> CACHE = new HashMap<>();
    private static final Map<String, Info> INFO = new HashMap<>();
    private static final Set<String> INFO_PENDING = new HashSet<>();
    private static long lastCall;

    static byte[] get(String url, int max) throws Exception {
        return Net.get(url,max,300000L,UA);
    }

    /** One page of results plus whether another page exists. */
    static final class Page {
        final List<Addon> items;
        final boolean more;
        Page(List<Addon> i, boolean m) { items = i; more = m; }
    }

    /** Blocking. Latest posts of one type, no search text. null on error. */
    static Page browse(int typeId, int page) {
        return fetch("", typeId, page, typeName(typeId), "updated_at");
    }

    /** Blocking. Search within one type. null on error. */
    static Page query(String q, int typeId, int page) {
        return fetch(q, typeId, page, typeName(typeId));
    }

    static String typeName(int typeId) {
        return typeId == 1 ? "addon" : typeId == 2 ? "world" : typeId == 6 ? "skin" : typeId == 4 ? "seed" : "texture";
    }

    static Page sorted(int typeId,int page,String sort){return fetch("",typeId,page,typeName(typeId),sort);}
    private static Page fetch(String q,int typeId,int page,String type){return fetch(q,typeId,page,type,"relevance");}
    private static Page fetch(String q, int typeId, int page, String type, String sort) {
        final long ttl="created_at".equals(sort)?60000L:"popularity_all".equals(sort)?300000L:TTL;
        String ck = "p:" + typeId + ":" + page + ":" + q.toLowerCase(Locale.ROOT) + ":" + sort;
        synchronized (CACHE) {
            Object[] hit = CACHE.get(ck);
            if (hit != null && System.currentTimeMillis() - (Long) hit[0] < ttl) return (Page) hit[1];
        }
        try {
            String u = API + "q=" + URLEncoder.encode(q, "UTF-8") + "&type_id=" + typeId + "&page=" + page + "&per_page=20&sort=" + sort;
            JSONObject r = new JSONObject(new String(Net.get(u,2<<20,ttl,UA), "UTF-8"));
            JSONObject meta = r.optJSONObject("meta");
            int pageCount = meta == null ? 1 : meta.optInt("page_count", 1);
            Page p = new Page(parse(r.optJSONArray("results"), type), page < pageCount);
            synchronized (CACHE) { CACHE.put(ck, new Object[]{System.currentTimeMillis(), p}); }
            return p;
        } catch (Exception e) { return null; }
    }

    /** Blocking, legacy combined search used before tabs existed. [] when nothing found; null on error. */
    static List<Addon> search(String query, boolean skins) {
        String q = query.trim();
        if (q.length() < 2 && !skins) return new ArrayList<>();
        List<Addon> out = new ArrayList<>();
        for (int t : skins ? new int[]{6} : new int[]{1, 2, 3}) {
            Page p = query(q, t, 1);
            if (p == null) return null;
            out.addAll(p.items);
        }
        return out;
    }

    static List<Addon> parse(JSONArray a, String type) {
        List<Addon> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject j = a.optJSONObject(i);
            if (j == null) continue;
            Addon d = new Addon();
            d.cf = true;
            d.downloadCount=j.optLong("downloadCount");
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

    /** Files and declared supported versions found on a post page. */
    static final class Info {
        final List<String[]> files = new ArrayList<>();   // {url, filename, isoDate}
        final List<String> versions = new ArrayList<>();  // e.g. "26.0", "1.21"
    }

    static Info cachedInfo(Addon a) { synchronized (INFO) { return INFO.get(a.id); } }

    static boolean infoPending(Addon a) { synchronized (INFO) { return INFO_PENDING.contains(a.id); } }

    /** Fetches the post page once, caches the result, then runs done on the given handler thread. */
    static void loadInfo(final Addon a, final android.os.Handler h, final Runnable done) {
        synchronized (INFO) {
            if (INFO.containsKey(a.id) || !INFO_PENDING.add(a.id)) return;
        }
        Net.worker("mcpedl").execute(new Runnable() {
            public void run() {
                Info i = pageInfo(a);
                synchronized (INFO) {
                    INFO_PENDING.remove(a.id);
                    if (i != null) { INFO.put(a.id, i); a.info = i; a.vers = i.versions; }
                }
                if (i != null && h != null && done != null) h.post(done);
            }
        });
    }

    /** Blocking. Reads the post page: all direct file links newest-first, plus declared supported versions. */
    static Info pageInfo(Addon a) {
        try {
            String h = new String(get(a.page, 3 << 20), "UTF-8").replace("\\u002F", "/");
            Info info = new Info();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "https://edge\\.mcpedl\\.com/files/[A-Za-z0-9_/.%+()-]+\\.(?:mcaddon|mcpack|mcworld|mctemplate)(.{0,80})").matcher(h);
            Set<String> seen = new HashSet<>();
            while (m.find()) {
                String seg = m.group();
                String u = m.group().substring(0, m.group().length() - m.group(1).length());
                String date = "";
                java.util.regex.Matcher dm = java.util.regex.Pattern.compile("(20\\d\\d-\\d\\d-\\d\\dT[\\d:.]+Z)").matcher(m.group(1));
                if (dm.find()) date = dm.group(1);
                if (!seen.add(u)) continue;
                String fn = u.substring(u.lastIndexOf('/') + 1);
                try { fn = java.net.URLDecoder.decode(fn, "UTF-8"); } catch (Exception ignored) {}
                info.files.add(new String[]{u, fn, date});
            }
            int vs = h.indexOf("versions-list");
            if (vs < 0) vs = h.indexOf("Supported Minecraft Versions");
            if (vs >= 0) {
                int end = h.indexOf("</section>", vs);
                if (end < 0) end = Math.min(h.length(), vs + 4000);
                java.util.regex.Matcher vm = java.util.regex.Pattern.compile("/version/([0-9a-z-]+)/").matcher(h.substring(vs, end));
                while (vm.find()) {
                    String v = vm.group(1).replace('-', '.');
                    if (v.length() > 1 && !info.versions.contains(v)) info.versions.add(v);
                }
            }
            return info;
        } catch (Exception e) { return null; }
    }

    /** Version tokens like 26.0 / 1.21 that appear in a file name. */
    static List<String> fileTokens(String name) {
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(1\\.2\\d|2\\d\\.\\d)").matcher(name);
        while (m.find()) if (!out.contains(m.group(1))) out.add(m.group(1));
        return out;
    }

    /**
     * Picks the file to download. Returns {url, filename, match} where match is
     * "yes" (file name or post versions fit the user's game), "no" (declared data disagrees),
     * or "unknown" (nothing declared). Newest file wins when no version token is involved.
     */
    static String[] pickFile(Info info, String userVer) {
        if (info == null || info.files.isEmpty()) return null;
        String[] latest = info.files.get(0);
        if (userVer != null && userVer.length() > 0) {
            // Prefer an exact/broad matching file before the owner's 26.x forward fallback.
            for(String[] f:info.files)for(String t:fileTokens(f[1])) {
                String d=McVer.norm(t),u=McVer.norm(userVer);
                if(d!=null && u!=null && (d.equals(u)||u.startsWith(d+".")))
                    return new String[]{f[0],f[1],"yes"};
            }
            List<String[]> tok = new ArrayList<>();
            for (String[] f : info.files) {
                for (String t : fileTokens(f[1])) {
                    String n = t.startsWith("1.") && Integer.parseInt(t.substring(2, t.indexOf('.', 2) < 0 ? t.length() : t.indexOf('.', 2))) >= 26
                            ? t.substring(2) : t;
                    if (McVer.matches(n, userVer) || McVer.matches(t, userVer)) tok.add(f);
                }
            }
            if (!tok.isEmpty()) return new String[]{tok.get(0)[0], tok.get(0)[1], "yes"};
            boolean anyTokens = false;
            for (String[] f : info.files) if (!fileTokens(f[1]).isEmpty()) { anyTokens = true; break; }
            if (anyTokens) return new String[]{latest[0], latest[1], "no"};
            if (!info.versions.isEmpty())
                return McVer.matchesAny(info.versions, userVer)
                        ? new String[]{latest[0], latest[1], "yes"}
                        : new String[]{latest[0], latest[1], "no"};
        }
        return new String[]{latest[0], latest[1], "unknown"};
    }
}
