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
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Cross-language end-to-end: the REAL Java client against the bot worker's REAL
 *  Python rev2 gate running on loopback (no protocol reimplementation here).
 *  The gate must be started by the test runner script; its URL comes from
 *  -Dlauncher.gate (default http://127.0.0.1:18931), code from -Dlauncher.vipcode. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class CrossGateTest {
    Context c;
    boolean oldEnabled;
    String oldGate;
    String gate, code;
    final AtomicReference<String> mem = new AtomicReference<>("");

    @Before public void before() {
        c = RuntimeEnvironment.getApplication();
        oldEnabled = Vip.ENABLED;
        oldGate = Vip.GATE;
        gate = System.getProperty("launcher.gate", "http://127.0.0.1:18931");
        code = System.getProperty("launcher.vipcode", "e2e-test-code");
        mem.set("");
        Vip.store = new Vip.Store() {
            public void save(String tok) { mem.set(tok); }
            public String load() { return mem.get(); }
            public void clear() { mem.set(""); }
        };
        Vip.ENABLED = true;
        Vip.GATE = gate;   // loopback test gate: the only plain-http exception in the app
    }

    @After public void after() {
        Vip.ENABLED = oldEnabled;
        Vip.GATE = oldGate;
        Vip.store = null;
    }

    private int runCheck(String cd) throws Exception {
        final AtomicInteger out = new AtomicInteger(0);
        Vip.check(c, cd, new Vip.CB() {
            public void ok() { out.set(1); }
            public void badCode() { out.set(2); }
            public void error() { out.set(3); }
        });
        long end = System.currentTimeMillis() + 8000;
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
        long end = System.currentTimeMillis() + 8000;
        while (out.get() == 0 && System.currentTimeMillis() < end) {
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS);
            ShadowLooper.runUiThreadTasks();
        }
        return out.get();
    }

    @Test public void realPythonGateFullFlow() throws Exception {
        // Health: gate answers and is on.
        HttpURLConnection h = (HttpURLConnection) new URL(gate + "/vip/health").openConnection();
        assertEquals(200, h.getResponseCode());
        String health = new String(read(h), "UTF-8");
        assertTrue(health.contains("\"on\": true"));

        // Wrong code -> 401 -> badCode; nothing stored.
        assertEquals(2, runCheck("definitely-wrong"));
        assertEquals("", mem.get());

        // Right code -> the REAL gate's token passes the app's parser and stores.
        assertEquals(1, runCheck(code));
        String tok = mem.get();
        assertTrue(tok.length() > 0);
        assertTrue(Vip.tokenExp(tok) > System.currentTimeMillis() / 1000);

        // Catalog: the REAL gate's relative signed URL resolves to an absolute loopback URL.
        AtomicReference<java.util.List<Addon>> got = new AtomicReference<>();
        assertEquals(1, runFetch(got));
        assertEquals(1, got.get().size());
        Addon a = got.get().get(0);
        assertEquals("pack-one", a.id);
        assertTrue(a.download, a.download.startsWith(gate + "/vip/file/pack-one?s="));

        // Full download through the real gate, then a Range request (the app's resume shape).
        HttpURLConnection full = (HttpURLConnection) new URL(a.download).openConnection();
        assertEquals(200, full.getResponseCode());
        byte[] all = read(full);
        assertEquals(2048, all.length);
        HttpURLConnection part = (HttpURLConnection) new URL(a.download).openConnection();
        part.setRequestProperty("Range", "bytes=100-199");
        assertEquals(206, part.getResponseCode());
        byte[] p100 = read(part);
        assertEquals(100, p100.length);
        for (int i = 0; i < 100; i++) assertEquals(all[100 + i], p100[i]);

        // Forged signature and missing auth on catalog: 404 / 401, no leaks.
        HttpURLConnection forged = (HttpURLConnection) new URL(a.download.replaceAll("s=[^&]+", "s=forged")).openConnection();
        assertEquals(404, forged.getResponseCode());
        HttpURLConnection noTok = (HttpURLConnection) new URL(gate + "/vip/catalog").openConnection();
        assertEquals(401, noTok.getResponseCode());
    }

    private static byte[] read(HttpURLConnection c) throws Exception {
        InputStream in = c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toByteArray();
    }
}
