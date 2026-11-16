package com.imgmine.agentic;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypted multi-key store. Keys live in a ring (iv:cipher base64, AES-GCM, Android Keystore). Never logged. */
public final class SecureStore {
    private static final String PREFS = "agentic_cfg";
    private static final String KEY_ALIAS = "agentic_api_key_v1";

    public static class KeyReadException extends Exception { KeyReadException(String m){ super(m); } }

    private static SharedPreferences p(Context c){ return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        SecretKey k = (SecretKey) ks.getKey(KEY_ALIAS, null);
        if (k != null) return k;
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return g.generateKey();
    }

    private static void deleteKey() {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
            ks.deleteEntry(KEY_ALIAS);
        } catch (Exception ignored) {}
    }

    private static String enc(String plain) throws Exception {
        Cipher cp = Cipher.getInstance("AES/GCM/NoPadding");
        try { cp.init(Cipher.ENCRYPT_MODE, key()); }
        catch (Exception e) { deleteKey(); cp.init(Cipher.ENCRYPT_MODE, key()); }
        return Base64.encodeToString(cp.getIV(), Base64.NO_WRAP) + ":"
             + Base64.encodeToString(cp.doFinal(plain.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }

    private static String dec(String stored) throws KeyReadException {
        try {
            int sep = stored.indexOf(':');
            Cipher cp = Cipher.getInstance("AES/GCM/NoPadding");
            cp.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(stored.substring(0, sep), Base64.NO_WRAP)));
            return new String(cp.doFinal(Base64.decode(stored.substring(sep + 1), Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new KeyReadException(e.getClass().getSimpleName()); }
    }

    private static JSONArray ring(Context c) throws Exception {
        String s = p(c).getString("keys", null);
        if (s != null) return new JSONArray(s);
        JSONArray r = new JSONArray();
        // migrate the old single-key config
        String iv = p(c).getString("key_iv", null), encv = p(c).getString("key_enc", null);
        if (iv != null && encv != null) {
            r.put(new JSONObject().put("label", "Key 1").put("v", iv + ":" + encv));
            p(c).edit().putString("keys", r.toString()).remove("key_iv").remove("key_enc").apply();
        }
        return r;
    }
    private static void saveRing(Context c, JSONArray r) { p(c).edit().putString("keys", r.toString()).apply(); }

    // ---- provider ecosystem: multiple providers, each with its own encrypted key ring ----
    public static final class Prov {
        public final Context c;
        public final int index;
        public String name, type, base, model;
        JSONArray keys;
        int idx;
        Prov(Context c, int i, JSONObject o) {
            this.c = c; this.index = i;
            name = o.optString("name", "Provider");
            type = o.optString("type", "openai");
            base = o.optString("base", null);
            model = o.optString("model", null);
            keys = o.optJSONArray("keys");
            if (keys == null) keys = new JSONArray();
            idx = o.optInt("idx", 0);
        }
        public int keyCount() { return keys.length(); }
        public String currentKey() throws KeyReadException {
            try {
                if (keys.length() == 0) return null;
                return dec(keys.getJSONObject(idx % keys.length()).getString("v"));
            } catch (KeyReadException e) { throw e; }
              catch (Exception e) { throw new KeyReadException(e.getClass().getSimpleName()); }
        }
        public String[] keyLabels() {
            String[] out = new String[keys.length()];
            for (int i = 0; i < keys.length(); i++) out[i] = keys.optJSONObject(i).optString("label", "Key " + (i + 1));
            return out;
        }
        public void rotate() { if (keys.length() > 1) { idx = (idx + 1) % keys.length(); save(); } }
        JSONObject json() {
            try {
                return new JSONObject().put("name", name).put("type", type).put("base", base)
                    .put("model", model).put("keys", keys).put("idx", idx);
            } catch (Exception e) { return new JSONObject(); }
        }
        void save() {
            try {
                JSONArray a = providers(c);
                if (index < a.length()) { a.put(index, json()); saveProviders(c, a); }
            } catch (Exception ignored) {}
        }
    }

    private static JSONArray providers(Context c) {
        try {
            String s = p(c).getString("providers", null);
            if (s != null) return new JSONArray(s);
        } catch (Exception ignored) {}
        // migrate legacy single-provider config
        JSONArray a = new JSONArray();
        try {
            JSONArray r = ring(c);
            String b = p(c).getString("base_url", null), m = p(c).getString("model", null);
            if (b != null || r.length() > 0) {
                a.put(new JSONObject().put("name", "Default").put("type", "openai").put("base", b)
                    .put("model", m).put("keys", r).put("idx", p(c).getInt("key_idx", 0)));
                saveProviders(c, a);
                p(c).edit().remove("keys").remove("base_url").remove("model").remove("key_idx").apply();
            }
        } catch (Exception ignored) {}
        return a;
    }
    private static void saveProviders(Context c, JSONArray a) { p(c).edit().putString("providers", a.toString()).apply(); }

    public static int providerCount(Context c) { return providers(c).length(); }
    public static Prov providerAt(Context c, int i) {
        JSONArray a = providers(c);
        if (i < 0 || i >= a.length()) return null;
        try { return new Prov(c, i, a.getJSONObject(i)); } catch (Exception e) { return null; }
    }
    public static Prov active(Context c) { return providerAt(c, p(c).getInt("active_provider", 0)); }
    public static Prov fallback(Context c) { return providerAt(c, p(c).getInt("fallback_provider", -1)); }
    public static int activeIndex(Context c) { return p(c).getInt("active_provider", 0); }
    public static int fallbackIndex(Context c) { return p(c).getInt("fallback_provider", -1); }
    public static void setActive(Context c, int i) { p(c).edit().putInt("active_provider", i).apply(); }
    public static void setFallback(Context c, int i) { p(c).edit().putInt("fallback_provider", i).apply(); }

    public static int addProvider(Context c, String name, String type, String base, String apiKey, String model) throws Exception {
        JSONArray a = providers(c);
        JSONArray r = new JSONArray();
        r.put(new JSONObject().put("label", "Key 1").put("v", enc(apiKey)));
        a.put(new JSONObject().put("name", name).put("type", type).put("base", base)
            .put("model", model).put("keys", r).put("idx", 0));
        saveProviders(c, a);
        return a.length() - 1;
    }

    public static void removeProvider(Context c, int i) {
        JSONArray a = providers(c), n = new JSONArray();
        for (int j = 0; j < a.length(); j++) if (j != i) n.put(a.optJSONObject(j));
        saveProviders(c, n);
        int act = activeIndex(c), fb = fallbackIndex(c);
        if (act == i) p(c).edit().putInt("active_provider", 0).apply();
        else if (act > i) p(c).edit().putInt("active_provider", act - 1).apply();
        if (fb == i) p(c).edit().putInt("fallback_provider", -1).apply();
        else if (fb > i) p(c).edit().putInt("fallback_provider", fb - 1).apply();
    }

    public static void setModel(Context c, Prov pv, String model) { pv.model = model; pv.save(); }

    /** First-time connect (OpenAI-compatible flow). */
    public static void saveConfig(Context c, String baseUrl, String apiKey, String model) throws Exception {
        int i = addProvider(c, "Default", "openai", baseUrl, apiKey, model);
        setActive(c, i);
    }

    // legacy delegates -> active provider (keeps the agent loop and key UI unchanged)
    public static void addKey(Context c, String apiKey) throws Exception {
        Prov pv = active(c);
        if (pv == null) return;
        pv.keys.put(new JSONObject().put("label", "Key " + (pv.keys.length() + 1)).put("v", enc(apiKey)));
        pv.save();
    }
    public static void removeKey(Context c, int index) throws Exception {
        Prov pv = active(c);
        if (pv == null) return;
        JSONArray n = new JSONArray();
        for (int j = 0; j < pv.keys.length(); j++) if (j != index) n.put(pv.keys.getJSONObject(j));
        for (int j = 0; j < n.length(); j++) n.getJSONObject(j).put("label", "Key " + (j + 1));
        pv.keys = n;
        if (pv.idx >= Math.max(1, n.length())) pv.idx = 0;
        pv.save();
    }
    public static int keyCount(Context c) { Prov pv = active(c); return pv == null ? 0 : pv.keyCount(); }
    public static String[] keyLabels(Context c) { Prov pv = active(c); return pv == null ? new String[0] : pv.keyLabels(); }
    public static String currentKey(Context c) throws KeyReadException { Prov pv = active(c); return pv == null ? null : pv.currentKey(); }
    public static void rotateKey(Context c) { Prov pv = active(c); if (pv != null) pv.rotate(); }
    public static String baseUrl(Context c) { Prov pv = active(c); return pv == null ? null : pv.base; }
    public static String model(Context c) { Prov pv = active(c); return pv == null ? null : pv.model; }
    public static boolean configured(Context c) { Prov pv = active(c); return pv != null && pv.base != null && pv.model != null && pv.keyCount() > 0; }
    /** Reset providers only - memory and app approvals survive. */
    public static void clear(Context c) { p(c).edit().remove("providers").remove("active_provider").remove("fallback_provider").apply(); }

    // ---- long-term memory (encrypted, compact) ----
    private static final int MEM_MAX = 15;

    public static List<String> loadMemory(Context c) {
        List<String> out = new ArrayList<>();
        try {
            String st = p(c).getString("mem", null);
            if (st == null) return out;
            JSONArray a = new JSONArray(dec(st));
            for (int i = 0; i < a.length(); i++) { String f = a.optString(i); if (f.length() > 0) out.add(f); }
        } catch (Exception ignored) {}
        return out;
    }

    public static void addMemory(Context c, String fact) {
        if (fact == null) return;
        fact = fact.trim();
        if (fact.length() < 3) return;
        if (fact.length() > 100) fact = fact.substring(0, 100);
        List<String> m = loadMemory(c);
        for (String x : m) if (x.equalsIgnoreCase(fact)) return;
        m.add(fact);
        while (m.size() > MEM_MAX) m.remove(0);
        try {
            JSONArray a = new JSONArray();
            for (String x : m) a.put(x);
            p(c).edit().putString("mem", enc(a.toString())).apply();
        } catch (Exception ignored) {}
    }

    public static void clearMemory(Context c) { p(c).edit().remove("mem").apply(); }

    public static boolean appApproved(Context c, String pkg){ return p(c).getBoolean("ok_" + pkg, false); }
    public static void approveApp(Context c, String pkg){ p(c).edit().putBoolean("ok_" + pkg, true).apply(); }
}
