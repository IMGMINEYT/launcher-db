package com.imgmine.launcher67;

import static org.junit.Assert.*;
import android.content.Context;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** End-to-end test of the app against a faithful Java mock of the bot worker's
 *  rev2 gate (vip_gate rev2: exp.gen base64url HMAC tokens, generation-bound
 *  signed file URLs, scrypt code check - server-side only, fail-closed off,
 *  Range support, 404-no-leak). Any wire drift between the two implementations
 *  fails here. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class GateE2ETest {
    static final long TTL = 30L * 24 * 3600;

    /** Java mirror of the rev2 Python gate's wire behavior. */
    static class MockGate {
        byte[] secret = new byte[32];
        String code = "owner-code-1";
        int gen = 1;
        boolean on = true;
        final Map<String, byte[]> files = new HashMap<>();
        long now = 1_900_000_000L; // fixed clock, before all token exps

        MockGate() { new java.util.Random(7).nextBytes(secret); files.put("pack-one", new byte[1000]); }

        static String b64(byte[] d) {
            String s = KeyBox.b64(d);
            return s.replace('+', '-').replace('/', '_').replace("=", "");
        }
        byte[] hmac(String msg) throws Exception {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(secret, "HmacSHA256"));
            return m.doFinal(msg.getBytes("UTF-8"));
        }
        String mintToken() throws Exception {
            String payload = (now + TTL) + "." + gen;
            return b64(payload.getBytes("UTF-8")) + "." + b64(hmac(payload));
        }
        boolean verifyToken(String tok) throws Exception {
            try {
                String[] parts = tok.split("\\.", 2);
                String payload = new String(KeyBox.b64dec(parts[0].replace('-', '+').replace('_', '/')), "UTF-8");
                String[] eg = payload.split("\\.", 2);
                long exp = Long.parseLong(eg[0]);
                int g = Integer.parseInt(eg[1]);
                if (!b64(hmac(payload)).equals(parts[1])) return false;
                return g == gen && now <= exp;
            } catch (Exception e) { return false; }
        }
        String signFile(String fid, long exp) throws Exception { return b64(hmac(fid + "." + exp + "." + gen)); }
        boolean verifyFileSig(String fid, String sig, long exp) throws Exception {
            if (now > exp) return false;
            return signFile(fid, exp).equals(sig);
        }
        void rotate(String newCode) { code = newCode; gen++; }
        void revokeAll() { new java.util.Random(13).nextBytes(secret); code = null; gen++; }
    }

    /** HttpURLConnection served by the mock gate. */
    static class GateConn extends HttpURLConnection {
        final MockGate g; final String path;
        final Map<String,String> headers = new HashMap<>();
        ByteArrayOutputStream sent;
        int status = 200; byte[] body = new byte[0];
        GateConn(URL u, MockGate g) { super(u); this.g = g; path = u.getPath() + (u.getQuery() != null ? "?" + u.getQuery() : ""); }
        public void connect() {}
        public void disconnect() {}
        public boolean usingProxy() { return false; }
        @Override public void setRequestProperty(String k, String v) { headers.put(k, v); super.setRequestProperty(k, v); }
        @Override public OutputStream getOutputStream() { sent = new ByteArrayOutputStream(); return sent; }
        @Override public InputStream getInputStream() throws java.io.IOException {
            if (status >= 400) throw new java.io.IOException("HTTP " + status);
            return new ByteArrayInputStream(body);
        }
        void json(int st, String j) { status = st; body = j.getBytes(); }
        void notFound() { status = 404; body = new byte[0]; }

        @Override public int getResponseCode() throws java.io.IOException {
            try {
                if (path.equals("/vip/check")) return check();
                if (path.equals("/vip/catalog")) return catalog();
                if (path.startsWith("/vip/file/")) return file();
                if (path.equals("/vip/health")) { json(200, "{\"ok\":true,\"on\":" + g.on + "}"); return status; }
            } catch (Exception e) { json(500, "{}"); }
            notFound();
            return status;
        }

        int check() throws Exception {
            if (!g.on) { json(503, "{\"error\":\"gate_off\"}"); return status; }
            String req = sent == null ? "" : new String(sent.toByteArray(), "UTF-8");
            String code = new org.json.JSONObject(req).optString("code");
            if (code.length() > 0 && code.equals(g.code)) {
                long exp = g.now + TTL;
                json(200, "{\"token\":\"" + g.mintToken() + "\",\"exp\":" + exp + "}");
            } else {
                json(401, "{\"error\":\"bad_code\"}");
            }
            return status;
        }

        int catalog() throws Exception {
            if (!g.on) { json(503, "{\"error\":\"gate_off\"}"); return status; }
            if (!g.verifyToken(headers.getOrDefault("X-VIP-Token", ""))) { json(401, "{\"error\":\"unauthorized\"}"); return status; }
            long exp = g.now + 24 * 3600;
            StringBuilder addons = new StringBuilder();
            for (String fid : g.files.keySet()) {
                if (addons.length() > 0) addons.append(',');
                addons.append("{\"id\":\"").append(fid).append("\",\"name\":\"").append(fid)
                        .append("\",\"size\":").append(g.files.get(fid).length)
                        .append(",\"download\":\"/vip/file/").append(fid)
                        .append("?s=").append(g.signFile(fid, exp)).append("&e=").append(exp).append("\"}");
            }
            json(200, "{\"addons\":[" + addons + "]}");
            return status;
        }

        int file() throws Exception {
            if (!g.on) { notFound(); return status; }
            String p = path.substring("/vip/file/".length());
            String fid = p.substring(0, p.indexOf('?'));
            Map<String,String> q = new HashMap<>();
            for (String kv : p.substring(p.indexOf('?') + 1).split("&")) {
                String[] pair = kv.split("=", 2);
                if (pair.length == 2) q.put(pair[0], pair[1]);
            }
            long exp;
            try { exp = Long.parseLong(q.getOrDefault("e", "0")); } catch (NumberFormatException e) { exp = 0; }
            byte[] content = g.files.get(fid);
            if (content == null || !g.verifyFileSig(fid, q.getOrDefault("s", ""), exp)) { notFound(); return status; }
            String range = headers.get("Range");
            if (range == null || range.length() == 0) { status = 200; body = content; return status; }
            // single-range parser mirroring the gate's parse_range
            if (!range.startsWith("bytes=") || range.contains(",")) { status = 416; return status; }
            String[] se = range.substring(6).split("-", 2);
            int start, end;
            try {
                if (se[0].length() == 0) {
                    int n = Integer.parseInt(se[1]);
                    if (n <= 0) { status = 416; return status; }
                    start = Math.max(0, content.length - n); end = content.length - 1;
                } else {
                    start = Integer.parseInt(se[0]);
                    end = se.length > 1 && se[1].length() > 0 ? Integer.parseInt(se[1]) : content.length - 1;
                }
            } catch (NumberFormatException e) { status = 416; return status; }
            if (start < 0 || end < start || start >= content.length) { status = 416; return status; }
            end = Math.min(end, content.length - 1);
            status = 206;
            body = java.util.Arrays.copyOfRange(content, start, end + 1);
            return status;
        }
    }

    Context c;
    Net.Connections oldConn;
    boolean oldEnabled;
    String oldGate;
    MockGate gate;
    final AtomicReference<String> mem = new AtomicReference<>("");

    @Before public void before() {
        c = RuntimeEnvironment.getApplication();
        oldConn = Net.connections;
        oldEnabled = Vip.ENABLED;
        oldGate = Vip.GATE;
        gate = new MockGate();
        mem.set("");
        Vip.store = new Vip.Store() {
            public void save(String tok) { mem.set(tok); }
            public String load() { return mem.get(); }
            public void clear() { mem.set(""); }
        };
        Vip.GATE = "https://gate.test";
        Vip.ENABLED = true;
        Net.connections = u -> new GateConn(u, gate);
    }

    @After public void after() {
        Net.connections = oldConn;
        Vip.ENABLED = oldEnabled;
        Vip.GATE = oldGate;
        Vip.store = null;
    }

    private int runCheck(String code) throws Exception {
        final AtomicInteger out = new AtomicInteger(0);
        Vip.check(c, code, new Vip.CB() {
            public void ok() { out.set(1); }
            public void badCode() { out.set(2); }
            public void error() { out.set(3); }
        });
        long end = System.currentTimeMillis() + 5000;
        while (out.get() == 0 && System.currentTimeMillis() < end) {
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS);
            ShadowLooper.runUiThreadTasks();
        }
        return out.get();
    }

    private int runFetch(final AtomicReference<java.util.List<Addon>> got) throws Exception {
        final AtomicInteger out = new AtomicInteger(0);
        Api.lastFailAuth = false;
        Api.lastFailGateOff = false;
        Api.fetch(c, new Api.CB() {
            public void ok(java.util.List<Addon> l) { got.set(l); out.set(1); }
            public void fail() { out.set(2); }
        });
        long end = System.currentTimeMillis() + 5000;
        while (out.get() == 0 && System.currentTimeMillis() < end) {
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS);
            ShadowLooper.runUiThreadTasks();
        }
        return out.get();
    }

    /** Direct GET through the injected connections, like the download manager would. */
    private GateConn get(String url, String range) throws Exception {
        GateConn conn = new GateConn(new URL(url), gate);
        if (range != null) conn.setRequestProperty("Range", range);
        conn.getResponseCode();
        return conn;
    }

    @Test public void fullFlowAuthCatalogDownloadRotation() throws Exception {
        AtomicReference<java.util.List<Addon>> got = new AtomicReference<>();

        // 1. No token: feed locked, catalog fetch fails auth without serving anything.
        assertTrue(Vip.feedLocked(c));
        assertEquals(2, runFetch(got));
        assertTrue(Api.lastFailAuth);

        // 2. Wrong code: 401 -> badCode, nothing stored.
        assertEquals(2, runCheck("not-the-code"));
        assertTrue(Vip.feedLocked(c));

        // 3. Right code: token minted by the mock validates against the app's parser and stores.
        assertEquals(1, runCheck("owner-code-1"));
        String tok = mem.get();
        assertTrue(tok.length() > 0);
        assertEquals(gate.now + TTL, Vip.tokenExp(tok));   // app reads the gate's exp.gen payload correctly
        assertTrue(gate.verifyToken(tok));                 // and the gate accepts what the app keeps
        assertFalse(Vip.feedLocked(c));

        // 4. Catalog: app resolves the gate's relative signed URL to absolute.
        assertEquals(1, runFetch(got));
        assertEquals(1, got.get().size());
        String dl = got.get().get(0).download;
        assertTrue(dl.startsWith("https://gate.test/vip/file/pack-one?s="));
        assertFalse(Api.lastFailAuth);

        // 5. Download: full body and HTTP Range both work against the signed URL.
        GateConn full = get(dl, null);
        assertEquals(200, full.status);
        assertEquals(1000, full.body.length);
        GateConn part = get(dl, "bytes=100-199");
        assertEquals(206, part.status);
        assertEquals(100, part.body.length);
        GateConn suffix = get(dl, "bytes=-10");
        assertEquals(206, suffix.status);
        assertEquals(10, suffix.body.length);
        assertEquals(416, get(dl, "bytes=2000-3000").status);
        assertEquals(404, get(dl.replaceAll("s=[^&]+", "s=forged"), null).status); // forged sig: no leak

        // 6. Rotation: old token and old signed URL both die; app fails closed to the lock screen.
        gate.rotate("owner-code-2");
        assertEquals(2, runFetch(got));
        assertTrue(Api.lastFailAuth);
        assertEquals(404, get(dl, null).status);

        // 7. New code restores the whole flow.
        assertEquals(1, runCheck("owner-code-2"));
        assertEquals(1, runFetch(got));
        assertEquals(200, get(got.get().get(0).download, null).status);
    }

    @Test public void gateOffFailsClosedEverywhere() throws Exception {
        // Establish a working session first.
        assertEquals(1, runCheck("owner-code-1"));
        AtomicReference<java.util.List<Addon>> got = new AtomicReference<>();
        assertEquals(1, runFetch(got));
        String dl = got.get().get(0).download;

        // Gate off: checks 503, catalog 503 (auth flag NOT set - distinct gate-off surface), files 404.
        gate.on = false;
        assertEquals(3, runCheck("owner-code-1"));
        assertEquals(2, runFetch(got));
        assertFalse(Api.lastFailAuth);
        assertTrue(Api.lastFailGateOff);
        assertEquals(404, get(dl, null).status);
    }

    @Test public void revokeAllKillsTokensAndUrls() throws Exception {
        assertEquals(1, runCheck("owner-code-1"));
        AtomicReference<java.util.List<Addon>> got = new AtomicReference<>();
        assertEquals(1, runFetch(got));
        String dl = got.get().get(0).download;
        gate.revokeAll();
        assertEquals(2, runFetch(got));
        assertTrue(Api.lastFailAuth);
        assertEquals(404, get(dl, null).status);
        assertEquals(2, runCheck("owner-code-1"));   // verifier cleared: old code no longer works
    }
}
