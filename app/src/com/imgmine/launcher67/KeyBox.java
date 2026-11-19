package com.imgmine.launcher67;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.GCMParameterSpec;

/** Android Keystore-backed encrypted storage for the user's YouTube API key.
 *  The key is never logged, never sent anywhere but youtube.googleapis.com, and the app
 *  manifest already sets allowBackup=false, so the encrypted copy never leaves the device. */
final class KeyBox {
    private static final String ALIAS = "stream_yt_ks";
    private static final String XFORM = "AES/GCM/NoPadding";

    /** Encrypt for storage; throws when Keystore is unavailable (caller must not fall back to plaintext). */
    static String protect(Context c, String plain) throws Exception {
        if (plain.length() == 0) return "";
        return encrypt(keyStore(c).getKey(ALIAS, null), plain);
    }

    static String unprotect(Context c, String enc) throws Exception {
        if (enc == null || enc.length() == 0) return "";
        return decrypt(keyStore(c).getKey(ALIAS, null), enc);
    }

    /** AES/GCM with a fresh random IV per message, iv prepended to ciphertext, Base64 wrapped.
     *  Package-private and key-agnostic so the crypto path is fully unit-testable. */
    static String encrypt(java.security.Key key, String plain) throws Exception {
        Cipher cp = Cipher.getInstance(XFORM);
        cp.init(Cipher.ENCRYPT_MODE, key);
        byte[] ct = cp.doFinal(plain.getBytes("UTF-8"));
        byte[] iv = cp.getIV();
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return b64(out);
    }

    static String decrypt(java.security.Key key, String enc) throws Exception {
        byte[] all = b64dec(enc);
        Cipher cp = Cipher.getInstance(XFORM);
        cp.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
        return new String(cp.doFinal(all, 12, all.length - 12), "UTF-8");
    }

    private static KeyStore keyStore(Context c) throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build());
            g.generateKey();
        }
        return ks;
    }

    private static final char[] T = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    private static String b64(byte[] d) {
        StringBuilder s = new StringBuilder((d.length + 2) / 3 * 4);
        for (int i = 0; i < d.length; i += 3) {
            int b0 = d[i] & 0xFF, b1 = i + 1 < d.length ? d[i + 1] & 0xFF : 0, b2 = i + 2 < d.length ? d[i + 2] & 0xFF : 0;
            s.append(T[b0 >>> 2]);
            s.append(T[((b0 & 3) << 4) | (b1 >>> 4)]);
            s.append(i + 1 < d.length ? T[((b1 & 15) << 2) | (b2 >>> 6)] : '=');
            s.append(i + 2 < d.length ? T[b2 & 63] : '=');
        }
        return s.toString();
    }

    private static byte[] b64dec(String s) {
        int[] rev = new int[128];
        for (int i = 0; i < T.length; i++) rev[T[i]] = i;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int acc = 0, bits = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '=' || c > 127) break;
            acc = (acc << 6) | rev[c];
            bits += 6;
            if (bits >= 8) { bits -= 8; out.write((acc >>> bits) & 0xFF); }
        }
        return out.toByteArray();
    }
}
