package com.imgmine.agentic;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** API key stored AES-GCM encrypted with an Android Keystore key. Never logged. */
public final class SecureStore {
    private static final String PREFS = "agentic_cfg";
    private static final String KEY_ALIAS = "agentic_api_key_v1";

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

    public static void saveConfig(Context c, String baseUrl, String apiKey, String model) throws Exception {
        byte[] iv = new byte[12]; new SecureRandom().nextBytes(iv);
        Cipher cp = Cipher.getInstance("AES/GCM/NoPadding");
        cp.init(Cipher.ENCRYPT_MODE, key());
        byte[] enc = cp.doFinal(apiKey.getBytes(StandardCharsets.UTF_8));
        p(c).edit()
          .putString("base_url", baseUrl)
          .putString("model", model)
          .putString("key_iv", Base64.encodeToString(iv, Base64.NO_WRAP))
          .putString("key_enc", Base64.encodeToString(enc, Base64.NO_WRAP))
          .apply();
    }

    public static String apiKey(Context c) throws Exception {
        String ivs = p(c).getString("key_iv", null), enc = p(c).getString("key_enc", null);
        if (ivs == null || enc == null) return null;
        Cipher cp = Cipher.getInstance("AES/GCM/NoPadding");
        cp.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(ivs, Base64.NO_WRAP)));
        return new String(cp.doFinal(Base64.decode(enc, Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    public static String baseUrl(Context c){ return p(c).getString("base_url", null); }
    public static String model(Context c){ return p(c).getString("model", null); }
    public static boolean configured(Context c){ return baseUrl(c) != null && model(c) != null && p(c).contains("key_enc"); }
    public static void clear(Context c){ p(c).edit().clear().apply(); }

    /** Packages the user has allowed the agent to open. */
    public static boolean appApproved(Context c, String pkg){ return p(c).getBoolean("ok_" + pkg, false); }
    public static void approveApp(Context c, String pkg){ p(c).edit().putBoolean("ok_" + pkg, true).apply(); }
}
