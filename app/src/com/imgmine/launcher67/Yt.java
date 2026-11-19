package com.imgmine.launcher67;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** YouTube live chat feed via the official Data API (liveChatMessages.list).
 *  Needs a free Google Cloud API key entered by the user in the launcher; never collected in chat.
 *  Parsing is pure and fixture-tested; network only in resolveChatId/poll. */
final class Yt {

    /** Extract a video id from watch / youtu.be / live / shorts URLs, or accept a bare 11-char id. */
    static String videoId(String url) {
        if (url == null) return null;
        url = url.trim();
        if (url.matches("[A-Za-z0-9_-]{11}")) return url;
        try {
            if (url.contains("youtu.be/")) return cut(url.split("youtu\\.be/")[1]);
            int i = url.indexOf("v=");
            if (i >= 0) return cut(url.substring(i + 2));
            for (String p : new String[]{"/live/", "/shorts/", "/embed/"}) {
                int k = url.indexOf(p);
                if (k >= 0) return cut(url.substring(k + p.length()));
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String cut(String s) {
        StringBuilder o = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' || c == '?' || c == '/' || c == '#') break;
            o.append(c);
        }
        String id = o.toString();
        return id.length() == 11 ? id : null;
    }

    /** activeLiveChatId from videos.list, or null when the video is not live. Fixture-testable. */
    static String parseChatId(String json) {
        try {
            JSONArray items = new JSONObject(json).optJSONArray("items");
            if (items == null || items.length() == 0) return null;
            JSONObject d = items.getJSONObject(0).optJSONObject("liveStreamingDetails");
            if (d == null) return null;
            String id = d.optString("activeLiveChatId");
            return id.length() == 0 ? null : id;
        } catch (Exception e) { return null; }
    }

    static final class Msg { String author, text; }

    static final class PollResult {
        final List<Msg> msgs = new ArrayList<>();
        String nextToken;
        long pollMs = 10000;
        String error;
    }

    /** liveChatMessages.list response parse. Skips non-text events and retracted/offline messages. Fixture-testable. */
    static PollResult parsePoll(String json) {
        PollResult r = new PollResult();
        try {
            JSONObject o = new JSONObject(json);
            JSONObject err = o.optJSONObject("error");
            if (err != null) { r.error = err.optString("message", "api error"); return r; }
            r.nextToken = o.optString("nextPageToken", null);
            long ms = o.optLong("pollingIntervalMillis", 10000);
            r.pollMs = Math.max(5000, Math.min(60000, ms));
            JSONArray items = o.optJSONArray("items");
            if (items == null) return r;
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) continue;
                JSONObject sn = it.optJSONObject("snippet");
                JSONObject au = it.optJSONObject("authorDetails");
                if (sn == null || au == null) continue;
                if (!"textMessageEvent".equals(sn.optString("type"))) continue;
                if (!sn.isNull("offlineAt")) continue;      // retracted after the fact
                String text = sn.optString("displayMessage").trim();
                String name = au.optString("displayName").trim();
                if (text.length() == 0 || name.length() == 0) continue;
                Msg m = new Msg();
                m.author = name; m.text = text;
                r.msgs.add(m);
            }
        } catch (Exception e) { r.error = "bad response"; }
        return r;
    }

    private static String get(String u) throws IOException {
        if (!u.startsWith("https://")) throw new IOException("HTTPS required");
        HttpURLConnection c = Net.connections.connect(new URL(u));
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "67Launcher/2.7");
        int code = c.getResponseCode();
        InputStream in = code == 200 ? c.getInputStream() : c.getErrorStream();
        String body = in == null ? "" : new String(Api.readAll(in, 1 << 20), "UTF-8");
        c.disconnect();
        return body;
    }

    static String resolveChatId(String videoId, String key) throws IOException {
        String u = "https://www.googleapis.com/youtube/v3/videos?part=liveStreamingDetails&id=" + videoId + "&key=" + key;
        return parseChatId(get(u));
    }

    static PollResult poll(String chatId, String key, String pageToken) throws IOException {
        String u = "https://www.googleapis.com/youtube/v3/liveChatMessages?part=snippet,authorDetails&liveChatId=" + chatId + "&key=" + key;
        if (pageToken != null) u += "&pageToken=" + pageToken;
        return parsePoll(get(u));
    }
}
