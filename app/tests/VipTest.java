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

@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class VipTest {
    Context c;
    Net.Connections oldConn;
    boolean oldEnabled;
    String oldGate;
    final AtomicReference<String> mem = new AtomicReference<>("");
    final AtomicInteger connects = new AtomicInteger();

    static String futureToken() {
        try { return KeyBox.b64("1999999999.0".getBytes("UTF-8")) + ".sig"; } catch (Exception e) { throw new RuntimeException(e); }
    }
    static String expiredToken() {
        try { return KeyBox.b64("1000000000.0".getBytes("UTF-8")) + ".sig"; } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** Fake connection capturing method, headers and request body, answering a canned response. */
    class Fake extends HttpURLConnection {
        final int code; final byte[] body; final Map<String,String> headers = new HashMap<>();
        ByteArrayOutputStream sent;
        Fake(URL u, int code, String body) { super(u); this.code = code; this.body = body.getBytes(); }
        public void connect() {}
        public void disconnect() {}
        public boolean usingProxy() { return false; }
        @Override public void setRequestProperty(String k, String v) { headers.put(k, v); super.setRequestProperty(k, v); }
        @Override public OutputStream getOutputStream() { sent = new ByteArrayOutputStream(); return sent; }
        @Override public int getResponseCode() { return code; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(body); }
    }

    @Before public void before() {
        c = RuntimeEnvironment.getApplication();
        oldConn = Net.connections;
        oldEnabled = Vip.ENABLED;
        oldGate = Vip.GATE;
        connects.set(0);
        mem.set("");
        Vip.store = new Vip.Store() {
            public void save(String tok) { mem.set(tok); }
            public String load() { return mem.get(); }
            public void clear() { mem.set(""); }
        };
        Vip.GATE = "https://gate.test";
    }

    @After public void after() {
        Net.connections = oldConn;
        Vip.ENABLED = oldEnabled;
        Vip.GATE = oldGate;
        Vip.store = null;
    }

    @Test public void tokenExpParses() throws Exception {
        assertEquals(1999999999L, Vip.tokenExp(futureToken()));          // "<exp>.<gen>" gate format
        assertEquals(1999999999L, Vip.tokenExp(KeyBox.b64("1999999999".getBytes("UTF-8")) + ".sig")); // plain "<exp>" also accepted
        assertEquals(0, Vip.tokenExp("junk"));
        assertEquals(0, Vip.tokenExp(".x"));
        assertEquals(0, Vip.tokenExp("abc.def"));   // base64 noise, not a number
        assertEquals(0, Vip.tokenExp(""));
        assertEquals(0, Vip.tokenExp("--_.sig"));   // urlsafe chars that decode to non-digits: no crash
    }

    @Test public void postCheckClassifies() throws Exception {
        final Fake ok = new Fake(new URL(Vip.GATE + "/vip/check"), 200, "{\"token\":\"" + futureToken() + "\",\"exp\":1999999999}");
        Net.connections = u -> { connects.incrementAndGet(); return ok; };
        int[] rc = {0};
        String body = Vip.postCheck(Vip.GATE, "letmein", rc);
        assertNotNull(body);
        assertEquals(200, rc[0]);
        assertEquals("POST", ok.getRequestMethod());
        assertTrue(new String(ok.sent.toByteArray(), "UTF-8").contains("letmein"));  // code goes to the gate, nowhere else

        Net.connections = u -> new Fake(u, 401, "{\"error\":\"bad_code\"}");
        rc[0] = 0;
        assertNull(Vip.postCheck(Vip.GATE, "nope", rc));
        assertEquals(401, rc[0]);

        Net.connections = u -> { throw new java.io.IOException("down"); };
        rc[0] = 0;
        assertNull(Vip.postCheck(Vip.GATE, "nope", rc));
        assertEquals(-1, rc[0]);
    }

    /** Run Vip.check and wait for the callback: 1=ok, 2=badCode, 3=error. */
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

    @Test public void checkOkStoresToken() throws Exception {
        Net.connections = u -> new Fake(u, 200, "{\"token\":\"" + futureToken() + "\",\"exp\":1999999999}");
        assertEquals(1, runCheck("vip123"));
        assertEquals(futureToken(), Vip.token(c));   // stored, retrievable
    }

    @Test public void checkBadCode() {
        Net.connections = u -> new Fake(u, 401, "{\"error\":\"bad_code\"}");
        try { assertEquals(2, runCheck("wrong")); } catch (Exception e) { fail(e.toString()); }
        assertEquals("", Vip.token(c));              // nothing stored
    }

    @Test public void checkServerErrorAndTransport() {
        Net.connections = u -> new Fake(u, 500, "oops");
        try { assertEquals(3, runCheck("vip123")); } catch (Exception e) { fail(e.toString()); }
        Net.connections = u -> { throw new java.io.IOException("down"); };
        try { assertEquals(3, runCheck("vip123")); } catch (Exception e) { fail(e.toString()); }
    }

    @Test public void checkRejectsExpiryMismatch() throws Exception {
        // Token claims 1999999999 inside, but the exp field disagrees: rejected, nothing stored.
        Net.connections = u -> new Fake(u, 200, "{\"token\":\"" + futureToken() + "\",\"exp\":12345}");
        try { assertEquals(3, runCheck("vip123")); } catch (Exception e) { fail(e.toString()); }
        assertEquals("", Vip.token(c));
    }

    @Test public void expiredStoredTokenIsInvisible() throws Exception {
        mem.set(expiredToken());
        assertEquals("", Vip.token(c));
        mem.set(futureToken());
        assertEquals(futureToken(), Vip.token(c));
        Vip.forget(c);
        assertEquals("", Vip.token(c));
    }

    @Test public void feedLockedWhenGateUnavailableOrNoToken() throws Exception {
        Vip.ENABLED = false;
        assertTrue(Vip.feedLocked(c));               // missing gate must fail closed
        Vip.ENABLED = true;
        assertTrue(Vip.feedLocked(c));
        mem.set(futureToken());
        assertFalse(Vip.feedLocked(c));
        mem.set(expiredToken());
        assertTrue(Vip.feedLocked(c));               // expired = locked again
    }

    /** Run Api.fetch and wait: 1=ok, 2=fail. */
    private int runFetch() throws Exception {
        final AtomicInteger out = new AtomicInteger(0);
        Api.lastFailAuth = false;
        Api.fetch(c, new Api.CB() {
            public void ok(java.util.List<Addon> l) { out.set(1); }
            public void fail() { out.set(2); }
        });
        long end = System.currentTimeMillis() + 5000;
        while (out.get() == 0 && System.currentTimeMillis() < end) {
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS);
            ShadowLooper.runUiThreadTasks();
        }
        return out.get();
    }

    @Test public void apiVipRoutingSendsToken() throws Exception {
        Vip.ENABLED = true;
        mem.set(futureToken());
        final AtomicReference<Fake> seen = new AtomicReference<>();
        Net.connections = u -> {
            connects.incrementAndGet();
            Fake f = new Fake(u, 200, "{\"addons\":[{\"id\":\"a1\",\"name\":\"Test Pack\",\"download\":\"https://gate.test/vip/file/a1?s=x&e=1\",\"size\":5,\"added\":\"2026-11-19T00:00:00Z\"}]}");
            seen.set(f);
            return f;
        };
        assertEquals(1, runFetch());
        assertEquals(1, connects.get());             // gate only: no public mirror race
        assertTrue(seen.get().getURL().toString().startsWith("https://gate.test/vip/catalog"));
        assertEquals(futureToken(), seen.get().headers.get("X-VIP-Token"));
        assertFalse(Api.lastFailAuth);
    }

    @Test public void apiVip401MarksAuthFailure() {
        Vip.ENABLED = true;
        mem.set(futureToken());
        Net.connections = u -> new Fake(u, 401, "{}");
        try {
            assertEquals(2, runFetch());
            assertTrue(Api.lastFailAuth);            // UI shows the lock screen, not a network error
        } catch (Exception e) { fail(e.toString()); }
    }

    @Test public void apiVipNoTokenSkipsNetwork() {
        Vip.ENABLED = true;
        Net.connections = u -> { connects.incrementAndGet(); return new Fake(u, 200, "{}"); };
        try {
            assertEquals(2, runFetch());
            assertTrue(Api.lastFailAuth);
            assertEquals(0, connects.get());         // no request without a token
        } catch (Exception e) { fail(e.toString()); }
    }

    @Test public void gateRelativeDownloadUrlsResolve() throws Exception {
        // Gate catalogs use root-relative signed file URLs; the app must make them absolute.
        java.util.List<Addon> l = Api.parse("{\"addons\":[{\"id\":\"a1\",\"name\":\"P\",\"download\":\"/vip/file/a1?s=x&e=1\"}]}");
        assertEquals(1, l.size());
        assertEquals(Vip.GATE + "/vip/file/a1?s=x&e=1", l.get(0).download);
        // Legacy public-parser compatibility only, never a production fetch fallback.
        Vip.ENABLED = false;
        java.util.List<Addon> p = Api.parse("{\"addons\":[{\"id\":\"a2\",\"name\":\"Q\",\"download\":\"https://github.com/x/y.mcaddon\"}]}");
        assertEquals("https://github.com/x/y.mcaddon", p.get(0).download);
    }

    @Test public void vipCatalogDropsOffOriginAndCleartext() throws Exception {
        Vip.ENABLED = true;
        // Same-origin gate URL accepted; other https hosts and cleartext dropped in VIP mode.
        assertTrue(Api.acceptDownload("https://gate.test/vip/file/a1?s=x&e=1"));
        assertFalse(Api.acceptDownload("https://evil.example/steal.mcaddon"));
        assertFalse(Api.acceptDownload("http://gate.test/vip/file/a1"));        // scheme must match too
        assertFalse(Api.acceptDownload("https://gate.test.evil.example/vip/file/a1"));
        // ...and a hostile catalog entry is dropped wholesale, never followed.
        java.util.List<Addon> l = Api.parse("{\"addons\":["
                + "{\"id\":\"ok\",\"name\":\"A\",\"download\":\"/vip/file/ok?s=x&e=1\"},"
                + "{\"id\":\"bad\",\"name\":\"B\",\"download\":\"https://evil.example/x.mcaddon\"}]}");
        assertEquals(1, l.size());
        assertEquals("ok", l.get(0).id);
        // Public mode unchanged: https required, no origin restriction.
        Vip.ENABLED = false;
        assertTrue(Api.acceptDownload("https://github.com/x/y.mcaddon"));
        assertFalse(Api.acceptDownload("http://github.com/x/y.mcaddon"));
    }

    @Test public void loopbackGateIsTheOnlyCleartextException() {
        Vip.ENABLED = true;
        Vip.GATE = "http://127.0.0.1:18931";
        assertTrue(Api.sameOrigin(Vip.GATE, "http://127.0.0.1:18931/vip/file/a?s=x&e=1"));
        assertTrue(Api.loopbackGate("http://127.0.0.1:18931/vip/file/a?s=x&e=1"));
        assertTrue(Api.acceptDownload("http://127.0.0.1:18931/vip/file/a?s=x&e=1"));
        assertFalse(Api.sameOrigin(Vip.GATE, "http://127.0.0.1:9999/vip/file/a"));  // port matters
        Vip.GATE = "https://gate.test";
        assertFalse(Api.loopbackGate("https://gate.test/vip/file/a"));
    }

    @Test public void downloaderValidTrustsGateByOrigin() {
        Vip.ENABLED = true;
        Vip.GATE = "https://gate.test";
        // Gate file URLs carry opaque ids, not extensions: origin, not suffix, decides.
        assertTrue(Downloader.valid("https://gate.test/vip/file/pack-one?s=x&e=1"));
        assertFalse(Downloader.valid("https://evil.example/vip/file/pack-one?s=x&e=1"));
        // Non-gate rules unchanged.
        assertTrue(Downloader.valid("https://github.com/a/b/releases/download/t/pack.mcaddon"));
        assertFalse(Downloader.valid("https://example.com/not-a-pack.txt"));
        assertFalse(Downloader.valid("http://github.com/a/b/releases/download/t/pack.mcaddon"));
    }
}
