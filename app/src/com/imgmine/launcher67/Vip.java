package com.imgmine.launcher67;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONObject;

/** VIP gate client. Without a deployed gate the app hides database discovery,
 * including old cache, while external catalogs and local downloads stay usable.
 * ENABLED means the real verified gate is configured, not a UI-only unlock.
 * App-side hiding does not protect already-public assets or existing copies. */
final class Vip {
    static boolean ENABLED = false; // non-final so tests can exercise the routing
    static String GATE = "https://UNSET.apps.bot-hosting.cloud"; // set when the gate is deployed
    private static final String ALIAS = "vip_gate_ks";
    private static final String PREF = "vip", KEY = "tok";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final java.util.concurrent.ExecutorService IO = java.util.concurrent.Executors.newSingleThreadExecutor();

    /** Token persistence. Default: Keystore-encrypted SharedPreferences. Tests inject memory. */
    interface Store { void save(String tok) throws Exception; String load() throws Exception; void clear(); }
    static Store store;

    private static Store store(final Context app) {
        if (store != null) return store;
        final Context a = app.getApplicationContext();
        return new Store() {
            public void save(String tok) throws Exception {
                a.getSharedPreferences(PREF, 0).edit().putString(KEY, KeyBox.protect(a, ALIAS, tok)).apply();
            }
            public String load() throws Exception {
                return KeyBox.unprotect(a, ALIAS, a.getSharedPreferences(PREF, 0).getString(KEY, ""));
            }
            public void clear() {
                a.getSharedPreferences(PREF, 0).edit().remove(KEY).apply();
            }
        };
    }

    interface CB { void ok(); void badCode(); void error(); }

    /** Raw check call, package-private for tests. Returns the response body on HTTP 200,
     *  null otherwise; httpCode[0] carries the status (-1 = transport failure). */
    static String postCheck(String gate, String code, int[] httpCode) {
        HttpURLConnection c = null;
        try {
            c = Net.connections.connect(new URL(gate + "/vip/check"));
            c.setConnectTimeout(5000);
            c.setReadTimeout(7000);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            OutputStream o = c.getOutputStream();
            o.write(new JSONObject().put("code", code).toString().getBytes("UTF-8"));
            o.close();
            httpCode[0] = c.getResponseCode();
            if (httpCode[0] != 200) return null;
            return new String(Api.readAll(c.getInputStream(), 65536), "UTF-8");
        } catch (Exception e) {
            if (httpCode[0] == 0) httpCode[0] = -1;
            return null;
        } finally { if (c != null) c.disconnect(); }
    }

    /** Verify a code with the gate. The code is never stored or logged. */
    static void check(final Context app, final String code, final CB cb) {
        IO.execute(new Runnable() { public void run() {
            final int[] rc = {0};
            String body = postCheck(GATE, code, rc);
            String tok = null;
            if (body != null) {
                try {
                    JSONObject j = new JSONObject(body);
                    String t = j.optString("token");
                    long exp = j.optLong("exp");
                    // Token must embed the same expiry it claims beside the signature.
                    if (t.length() > 0 && exp > 0 && tokenExp(t) == exp) tok = t;
                } catch (Exception ignored) {}
            }
            if (tok != null) {
                try {
                    store(app).save(tok);
                } catch (Exception e) {
                    // Keystore unavailable: no plaintext fallback - report as an error.
                    MAIN.post(new Runnable() { public void run() { cb.error(); } });
                    return;
                }
                MAIN.post(new Runnable() { public void run() { cb.ok(); } });
            } else if (rc[0] == 401) {
                MAIN.post(new Runnable() { public void run() { cb.badCode(); } });
            } else {
                MAIN.post(new Runnable() { public void run() { cb.error(); } });
            }
        }});
    }

    /** Expiry (unix seconds) embedded in the token's first segment: base64url of
     *  "<exp>.<gen>" (gate implementation; gen lets code rotation kill old tokens),
     *  with plain "<exp>" also accepted. 0 when unparseable. */
    static long tokenExp(String tok) {
        try {
            int dot = tok.indexOf('.');
            if (dot <= 0) return 0;
            String payload = new String(KeyBox.b64dec(tok.substring(0, dot).replace('-', '+').replace('_', '/')), "UTF-8");
            int p = payload.indexOf('.');
            return Long.parseLong(p >= 0 ? payload.substring(0, p) : payload);
        } catch (Exception e) { return 0; }
    }

    /** Stored token, or "" when absent, expired, or undecryptable. Never throws. */
    static String token(Context c) {
        try {
            String tok = store(c).load();
            if (tok == null || tok.length() == 0) return "";
            if (tokenExp(tok) <= System.currentTimeMillis() / 1000) return "";
            return tok;
        } catch (Exception e) { return ""; }
    }

    /** Database discovery fails closed until a deployed gate and valid token exist.
     * External catalogs, existing local downloads and history are not locked. */
    static boolean feedLocked(Context c) { return !ENABLED || token(c).length() == 0; }

    static void forget(Context c) {
        try { store(c).clear(); } catch (Exception ignored) {}
    }
}
