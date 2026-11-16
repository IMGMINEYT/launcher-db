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
        if (code == 429 || code == 529 || l.contains("rate limit") || l.contains("rate_limit") || l.contains("quota")
                || l.contains("insufficient") || l.contains("resource_exhausted") || l.contains("overloaded"))
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

    /** Fetch model ids for an OpenAI-compatible provider. */
    public static String[] fetchModels(String base, String key) throws Exception { return fetchModels(base, key, "openai"); }

    /** Fetch model ids. Returns [resolvedRoot, models...] - element 0 is the working root. */
    public static String[] fetchModels(String base, String key, String type) throws Exception {
        checkUrl(base);
        if ("anthropic".equals(type)) return fetchModelsAnthropic(base, key);
        if ("gemini".equals(type)) return fetchModelsGemini(base, key);
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

    // ---------- provider adapters (Part 5): normalize to one internal message format ----------

    public static String chat(String root, String key, String model, JSONArray messages, Progress p, String type) throws Exception {
        if ("anthropic".equals(type)) return chatAnthropic(root, key, model, messages);
        if ("gemini".equals(type)) return chatGemini(root, key, model, messages);
        return chat(root, key, model, messages, p);
    }

    private static String strip(String base) {
        String b = base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }

    private static HttpURLConnection openH(String url, String method) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setRequestMethod(method);
        h.setConnectTimeout(10000);
        h.setReadTimeout(120000);
        h.setRequestProperty("Accept", "application/json");
        if ("POST".equals(method)) { h.setDoOutput(true); h.setRequestProperty("Content-Type", "application/json"); }
        return h;
    }

    private static String post(HttpURLConnection h, JSONObject req) throws Exception {
        byte[] bytes = req.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = h.getOutputStream()) { os.write(bytes); }
        return body(h);
    }

    /** Anthropic Messages API (non-streaming). */
    static String chatAnthropic(String base, String key, String model, JSONArray messages) throws Exception {
        String b = strip(base);
        StringBuilder sys = new StringBuilder();
        JSONArray msgs = new JSONArray();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.getJSONObject(i);
            String role = m.optString("role");
            if ("system".equals(role)) { if (sys.length() > 0) sys.append("\n\n"); sys.append(contentText(m.opt("content"))); continue; }
            if (!"user".equals(role) && !"assistant".equals(role)) continue;
            msgs.put(new JSONObject().put("role", role).put("content", anthContent(m.opt("content"))));
        }
        JSONObject req = new JSONObject();
        req.put("model", model);
        req.put("max_tokens", 4096);
        if (sys.length() > 0) req.put("system", sys.toString());
        req.put("messages", msgs);
        HttpURLConnection h = openH(b + "/v1/messages", "POST");
        h.setRequestProperty("x-api-key", key);
        h.setRequestProperty("anthropic-version", "2023-06-01");
        JSONObject resp = new JSONObject(post(h, req));
        JSONArray content = resp.getJSONArray("content");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            JSONObject part = content.getJSONObject(i);
            if ("text".equals(part.optString("type"))) sb.append(part.optString("text"));
        }
        if (sb.length() == 0) throw new ApiError("AI ne khaali reply diya - dobara try karo");
        return sb.toString();
    }

    private static String contentText(Object content) {
        if (content instanceof JSONArray) {
            StringBuilder sb = new StringBuilder();
            JSONArray a = (JSONArray) content;
            for (int i = 0; i < a.length(); i++) {
                JSONObject p = a.optJSONObject(i);
                if (p != null && "text".equals(p.optString("type"))) sb.append(p.optString("text"));
            }
            return sb.toString();
        }
        return content == null ? "" : content.toString();
    }

    /** Anthropic content: text + image parts. */
    private static JSONArray anthContent(Object content) throws Exception {
        JSONArray out = new JSONArray();
        if (content instanceof JSONArray) {
            JSONArray a = (JSONArray) content;
            for (int i = 0; i < a.length(); i++) {
                JSONObject p = a.optJSONObject(i);
                if (p == null) continue;
                if ("text".equals(p.optString("type"))) out.put(new JSONObject().put("type", "text").put("text", p.optString("text")));
                else if ("image_url".equals(p.optString("type"))) {
                    String url = p.optJSONObject("image_url") != null ? p.optJSONObject("image_url").optString("url") : "";
                    int k = url.indexOf("base64,");
                    if (k > 0) out.put(new JSONObject().put("type", "image").put("source",
                        new JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", url.substring(k + 7))));
                }
            }
        } else out.put(new JSONObject().put("type", "text").put("text", content == null ? "" : content.toString()));
        return out;
    }

    /** Google Gemini generateContent (non-streaming). */
    static String chatGemini(String base, String key, String model, JSONArray messages) throws Exception {
        String b = strip(base);
        StringBuilder sys = new StringBuilder();
        JSONArray contents = new JSONArray();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.getJSONObject(i);
            String role = m.optString("role");
            if ("system".equals(role)) { if (sys.length() > 0) sys.append("\n\n"); sys.append(contentText(m.opt("content"))); continue; }
            String gRole = "assistant".equals(role) ? "model" : "user";
            contents.put(new JSONObject().put("role", gRole).put("parts", gemParts(m.opt("content"))));
        }
        JSONObject req = new JSONObject();
        req.put("contents", contents);
        if (sys.length() > 0) req.put("systemInstruction", new JSONObject().put("parts",
            new JSONArray().put(new JSONObject().put("text", sys.toString()))));
        String url = b + "/v1beta/models/" + model + ":generateContent?key=" + key;
        JSONObject resp = new JSONObject(post(openH(url, "POST"), req));
        JSONArray cand = resp.optJSONArray("candidates");
        if (cand == null || cand.length() == 0) throw new ApiError("AI ne khaali reply diya - dobara try karo");
        JSONArray parts = cand.getJSONObject(0).getJSONObject("content").getJSONArray("parts");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) sb.append(parts.getJSONObject(i).optString("text"));
        if (sb.length() == 0) throw new ApiError("AI ne khaali reply diya - dobara try karo");
        return sb.toString();
    }

    private static JSONArray gemParts(Object content) throws Exception {
        JSONArray out = new JSONArray();
        if (content instanceof JSONArray) {
            JSONArray a = (JSONArray) content;
            for (int i = 0; i < a.length(); i++) {
                JSONObject p = a.optJSONObject(i);
                if (p == null) continue;
                if ("text".equals(p.optString("type"))) out.put(new JSONObject().put("text", p.optString("text")));
                else if ("image_url".equals(p.optString("type"))) {
                    String url = p.optJSONObject("image_url") != null ? p.optJSONObject("image_url").optString("url") : "";
                    int k = url.indexOf("base64,");
                    if (k > 0) out.put(new JSONObject().put("inline_data",
                        new JSONObject().put("mime_type", "image/jpeg").put("data", url.substring(k + 7))));
                }
            }
        } else out.put(new JSONObject().put("text", content == null ? "" : content.toString()));
        return out;
    }

    private static String[] fetchModelsAnthropic(String base, String key) throws Exception {
        String b = strip(base);
        HttpURLConnection h = openH(b + "/v1/models", "GET");
        h.setRequestProperty("x-api-key", key);
        h.setRequestProperty("anthropic-version", "2023-06-01");
        JSONArray data = new JSONObject(body(h)).getJSONArray("data");
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) ids.add(data.getJSONObject(i).optString("id"));
        if (ids.isEmpty()) throw new ApiError("No models returned");
        String[] out = new String[ids.size() + 1];
        out[0] = b;
        for (int i = 0; i < ids.size(); i++) out[i + 1] = ids.get(i);
        return out;
    }

    private static String[] fetchModelsGemini(String base, String key) throws Exception {
        String b = strip(base);
        JSONObject resp = new JSONObject(body(openH(b + "/v1beta/models?key=" + key, "GET")));
        JSONArray models = resp.getJSONArray("models");
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < models.length(); i++) {
            JSONObject m = models.getJSONObject(i);
            JSONArray methods = m.optJSONArray("supportedGenerationMethods");
            boolean gen = false;
            if (methods != null) for (int j = 0; j < methods.length(); j++) if ("generateContent".equals(methods.optString(j))) gen = true;
            if (gen) {
                String n = m.optString("name");
                ids.add(n.startsWith("models/") ? n.substring(7) : n);
            }
        }
        if (ids.isEmpty()) throw new ApiError("No models returned");
        String[] out = new String[ids.size() + 1];
        out[0] = b;
        for (int i = 0; i < ids.size(); i++) out[i + 1] = ids.get(i);
        return out;
    }
}
