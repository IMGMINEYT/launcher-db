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
    public static class RateLimited extends ApiError { RateLimited(String m){ super(m); } }
    public static class ContextTooLong extends ApiError { ContextTooLong(String m){ super(m); } }
    public static class VisionRejected extends ApiError { VisionRejected(String m){ super(m); } }

    static ApiError classify(int code, String raw) {
        String l = raw == null ? "" : raw.toLowerCase();
        if (code == 400 && (l.contains("image") || l.contains("vision") || l.contains("multimodal") || l.contains("modality")))
            return new VisionRejected("Model images support nahi karta");
        if (code == 429 || l.contains("rate limit") || l.contains("rate_limit") || l.contains("quota") || l.contains("insufficient"))
            return new RateLimited(friendly(code));
        if ((l.contains("context") && (l.contains("length") || l.contains("window"))) || l.contains("maximum context")
                || l.contains("too many tokens") || l.contains("max_tokens") || l.contains("reduce the length"))
            return new ContextTooLong("Model ki memory full ho gayi - history compress karke continue");
        return new ApiError(friendly(code));
    }

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
        if (code >= 400) throw classify(code, sb.toString());
        return sb.toString();
    }

    static String friendly(int code) {
        switch (code) {
            case 400: return "Request reject hua (400) - model ya base URL check karo";
            case 401: case 403: return "API key invalid ya expired (" + code + ") - key dobara check karo";
            case 404: return "Base URL sahi nahi lag raha (404) - URL check karo";
            case 429: return "AI abhi busy hai / rate limit (429) - 1-2 min baad try karo";
            case 500: case 502: case 503: case 504:
                return "AI provider ka server down hai (" + code + ") - thodi der baad try karo";
            default: return "AI provider ne error diya (HTTP " + code + ")";
        }
    }

    static String friendlyNet(Exception e) {
        if (e instanceof java.net.SocketTimeoutException) return "AI ne reply me bahut time lagaya (timeout) - dobara try karo";
        if (e instanceof java.net.UnknownHostException) return "Base URL ka host nahi mila - URL ya internet check karo";
        if (e instanceof javax.net.ssl.SSLException) return "Secure connection nahi ban payi - https base URL check karo";
        if (e instanceof java.net.ConnectException) return "Server se connect nahi ho paya - URL ya internet check karo";
        return "Network error - internet check karke dobara try karo";
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
              catch (Exception e) { last = new ApiError(friendlyNet(e)); }
        }
        throw last != null ? last : new ApiError("Could not fetch models");
    }

    public interface Progress { void onPartial(String soFar); }

    /** End index of a JSON string value starting at valueStart (just after the opening quote), or -1 if incomplete. */
    public static int jsonStringEnd(String s, int valueStart) {
        boolean esc = false;
        for (int i = valueStart; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) { esc = false; continue; }
            if (c == '\\') { esc = true; continue; }
            if (c == '"') return i;
        }
        return -1;
    }

    /** Streaming chat completion with non-streaming fallback; returns full assistant content. */
    public static String chat(String root, String key, String model, JSONArray messages, Progress p) throws Exception {
        checkUrl(root);
        try {
            return chatOnce(root, key, model, messages, true, p);
        } catch (ApiError ae) {
            throw ae; // deterministic HTTP/provider error - no retry
        } catch (Exception net) {
            try {
                return chatOnce(root, key, model, messages, false, null); // fallback: plain
            } catch (ApiError ae) { throw ae; }
            catch (Exception e2) { throw new ApiError(friendlyNet(e2)); }
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
            throw classify(code, sb.toString());
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
                        if (p != null) p.onPartial(sb.toString());
                    }
                } catch (Exception ignored) {}
            }
        }
        if (sb.length() == 0) throw new IllegalStateException("empty stream");
        return sb.toString();
    }
}
