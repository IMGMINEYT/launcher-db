package com.imgmine.agentic;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
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

    /** First-time connect: replaces everything with one key. */
    public static void saveConfig(Context c, String baseUrl, String apiKey, String model) throws Exception {
        JSONArray r = new JSONArray();
        r.put(new JSONObject().put("label", "Key 1").put("v", enc(apiKey)));
        saveRing(c, r);
        p(c).edit().putString("base_url", baseUrl).putString("model", model).putInt("key_idx", 0).apply();
    }

    public static void addKey(Context c, String apiKey) throws Exception {
        JSONArray r = ring(c);
        r.put(new JSONObject().put("label", "Key " + (r.length() + 1)).put("v", enc(apiKey)));
        saveRing(c, r);
    }

    public static void removeKey(Context c, int index) throws Exception {
        JSONArray r = ring(c), n = new JSONArray();
        for (int j = 0; j < r.length(); j++) if (j != index) n.put(r.getJSONObject(j));
        for (int j = 0; j < n.length(); j++) n.getJSONObject(j).put("label", "Key " + (j + 1));
        saveRing(c, n);
        if (p(c).getInt("key_idx", 0) >= Math.max(1, n.length())) p(c).edit().putInt("key_idx", 0).apply();
    }

    public static int keyCount(Context c) { try { return ring(c).length(); } catch (Exception e) { return 0; } }
    public static String[] keyLabels(Context c) {
        try {
            JSONArray r = ring(c);
            String[] out = new String[r.length()];
            for (int i = 0; i < r.length(); i++) out[i] = r.getJSONObject(i).optString("label", "Key " + (i + 1));
            return out;
        } catch (Exception e) { return new String[0]; }
    }

    public static String currentKey(Context c) throws KeyReadException {
        try {
            JSONArray r = ring(c);
            if (r.length() == 0) return null;
            int idx = p(c).getInt("key_idx", 0) % r.length();
            return dec(r.getJSONObject(idx).getString("v"));
        } catch (KeyReadException e) { throw e; }
          catch (Exception e) { throw new KeyReadException(e.getClass().getSimpleName()); }
    }

    public static void rotateKey(Context c) {
        int n = keyCount(c);
        if (n > 1) p(c).edit().putInt("key_idx", (p(c).getInt("key_idx", 0) + 1) % n).apply();
    }

    public static String baseUrl(Context c){ return p(c).getString("base_url", null); }
    public static String model(Context c){ return p(c).getString("model", null); }
    public static boolean configured(Context c){ return baseUrl(c) != null && model(c) != null && keyCount(c) > 0; }
    public static void clear(Context c){ p(c).edit().clear().apply(); }

    public static boolean appApproved(Context c, String pkg){ return p(c).getBoolean("ok_" + pkg, false); }
    public static void approveApp(Context c, String pkg){ p(c).edit().putBoolean("ok_" + pkg, true).apply(); }
}
