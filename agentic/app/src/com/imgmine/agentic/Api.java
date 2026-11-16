package com.imgmine.agentic;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** OpenAI-compatible client: GET {base}/models, POST {base}/chat/completions. HTTPS only. */
public final class Api {

    public static class ApiError extends Exception { ApiError(String m){ super(m); } }

    private static void checkUrl(String u) throws ApiError {
        String l = u.toLowerCase();
        if (!l.startsWith("https://")
                && !(l.startsWith("http://127.") || l.startsWith("http://localhost")))
            throw new ApiError("Base URL must be https:// (secure only)");
    }

    /** Normalize base: strip trailing slash. Returns candidate roots to try (with and without /v1). */
    private static String[] roots(String base) {
        String b = base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (b.endsWith("/v1")) return new String[]{ b };
        return new String[]{ b + "/v1", b };
    }

    private static HttpURLConnection open(String url, String key, String method) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setRequestMethod(method);
        h.setConnectTimeout(10000);
        h.setReadTimeout(120000);
        h.setRequestProperty("Authorization", "Bearer " + key);
        h.setRequestProperty("Accept", "application/json");
        if ("POST".equals(method)) {
            h.setDoOutput(true);
            h.setRequestProperty("Content-Type", "application/json");
        }
        return h;
    }

    private static String body(HttpURLConnection h) throws Exception {
        int code = h.getResponseCode();
        InputStream is = code < 400 ? h.getInputStream() : h.getErrorStream();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line; while ((line = r.readLine()) != null) sb.append(line);
        }
        if (code >= 400) throw new ApiError("HTTP " + code + ": " + (sb.length() > 300 ? sb.substring(0, 300) : sb.toString()));
        return sb.toString();
    }

    /** Fetch model ids. Returns [resolvedRoot, models...] - element 0 is the working root. */
    public static String[] fetchModels(String base, String key) throws Exception {
        checkUrl(base);
        ApiError last = null;
        for (String root : roots(base)) {
            try {
                String resp = body(open(root + "/models", key, "GET"));
                JSONArray data = new JSONObject(resp).getJSONArray("data");
                List<String> ids = new ArrayList<>();
                for (int i = 0; i < data.length(); i++) ids.add(data.getJSONObject(i).optString("id"));
                if (ids.isEmpty()) throw new ApiError("No models returned");
                String[] out = new String[ids.size() + 1];
                out[0] = root;
                for (int i = 0; i < ids.size(); i++) out[i + 1] = ids.get(i);
                return out;
            } catch (ApiError e) { last = e; }
              catch (Exception e) { last = new ApiError(e.getMessage() != null ? e.getMessage() : e.toString()); }
        }
        throw last != null ? last : new ApiError("Could not fetch models");
    }

    public interface Progress { void onChars(int n); }

    /** Streaming chat completion with non-streaming fallback; returns full assistant content. */
    public static String chat(String root, String key, String model, JSONArray messages, Progress p) throws Exception {
        checkUrl(root);
        try {
            return chatOnce(root, key, model, messages, true, p);
        } catch (Exception first) {
            return chatOnce(root, key, model, messages, false, null); // fallback: plain
        }
    }

    private static String chatOnce(String root, String key, String model, JSONArray messages, boolean stream, Progress p) throws Exception {
        JSONObject req = new JSONObject();
        req.put("model", model);
        req.put("messages", messages);
        req.put("stream", stream);
        HttpURLConnection h = open(root + "/chat/completions", key, "POST");
        byte[] bytes = req.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = h.getOutputStream()) { os.write(bytes); }
        int code = h.getResponseCode();
        if (code >= 400) {
            InputStream es = h.getErrorStream();
            StringBuilder sb = new StringBuilder();
            if (es != null) try (BufferedReader r = new BufferedReader(new InputStreamReader(es, StandardCharsets.UTF_8))) {
                String line; while ((line = r.readLine()) != null) sb.append(line);
            }
            throw new ApiError("HTTP " + code + ": " + (sb.length() > 300 ? sb.substring(0, 300) : sb.toString()));
        }
        if (!stream) {
            JSONObject resp = new JSONObject(body(h));
            return resp.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(h.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String d = line.substring(5).trim();
                if ("[DONE]".equals(d)) break;
                try {
                    JSONObject c = new JSONObject(d);
                    JSONArray ch = c.optJSONArray("choices");
                    if (ch == null || ch.length() == 0) continue;
                    JSONObject delta = ch.getJSONObject(0).optJSONObject("delta");
                    if (delta == null) continue;
                    String part = delta.optString("content", "");
                    if (part.length() > 0) {
                        sb.append(part);
                        if (p != null) p.onChars(sb.length());
                    }
                } catch (Exception ignored) {}
            }
        }
        if (sb.length() == 0) throw new ApiError("empty stream");
        return sb.toString();
    }
}
