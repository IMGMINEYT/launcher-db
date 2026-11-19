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
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Cross-language end-to-end: the REAL Java client against the bot worker's REAL
 *  Python rev3 gate running on loopback (no protocol reimplementation here).
 *  The gate must be started by the test runner script; its URL comes from
 *  -Dlauncher.gate (default http://127.0.0.1:18931, 10-min signed URLs), a second
 *  short-TTL gate from -Dlauncher.gate2 (default http://127.0.0.1:18932, 2s URLs),
 *  code from -Dlauncher.vipcode. Rev3: /vip/file requires X-VIP-Token; signed URLs
 *  live 10 minutes and are refreshed through /vip/catalog. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class CrossGateTest {
    Context c;
    boolean oldEnabled;
    String oldGate;
    String gate, gate2, code;
    final AtomicReference<String> mem = new AtomicReference<>("");

    @Before public void before() {
        c = RuntimeEnvironment.getApplication();
        oldEnabled = Vip.ENABLED;
        oldGate = Vip.GATE;
        gate = System.getProperty("launcher.gate", "http://127.0.0.1:18931");
        gate2 = System.getProperty("launcher.gate2", "http://127.0.0.1:18932");
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
        final String tok = mem.get();
        assertTrue(tok.length() > 0);
        assertTrue(Vip.tokenExp(tok) > System.currentTimeMillis() / 1000);

        // Catalog: the REAL gate's relative signed URL resolves to an absolute loopback URL.
        AtomicReference<java.util.List<Addon>> got = new AtomicReference<>();
        assertEquals(1, runFetch(got));
        assertEquals(1, got.get().size());
        Addon a = got.get().get(0);
        assertEquals("pack-one", a.id);
        assertTrue(a.download, a.download.startsWith(gate + "/vip/file/pack-one?s="));
        // Rev3: catalog URLs carry a 10-minute expiry.
        long exp = Long.parseLong(a.download.replaceAll(".*[?&]e=([0-9]+).*", "$1"));
        long ttl = exp - System.currentTimeMillis() / 1000;
        assertTrue("rev3 signed URL TTL should be ~10min, was " + ttl + "s", ttl > 500 && ttl <= 600);

        // Rev3: a signed URL WITHOUT the token is refused (bearer-URL shareability is dead).
        HttpURLConnection noTok = (HttpURLConnection) new URL(a.download).openConnection();
        assertEquals(401, noTok.getResponseCode());

        // Full download through the real gate WITH the token header, as the app's downloader sends it.
        HttpURLConnection full = (HttpURLConnection) new URL(a.download).openConnection();
        full.setRequestProperty("X-VIP-Token", tok);
        assertEquals(200, full.getResponseCode());
        byte[] all = read(full);
        assertEquals(a.size, all.length);

        // Range (the app's resume shape) with the token header: byte-accurate vs the full body.
        HttpURLConnection part = (HttpURLConnection) new URL(a.download).openConnection();
        part.setRequestProperty("X-VIP-Token", tok);
        part.setRequestProperty("Range", "bytes=100-199");
        assertEquals(206, part.getResponseCode());
        byte[] p100 = read(part);
        assertEquals(100, p100.length);
        for (int i = 0; i < 100; i++) assertEquals(all[100 + i], p100[i]);

        // Forged signature (with a valid token): 404, no leak. No auth on catalog: 401.
        HttpURLConnection forged = (HttpURLConnection) new URL(a.download.replaceAll("s=[^&]+", "s=forged")).openConnection();
        forged.setRequestProperty("X-VIP-Token", tok);
        assertEquals(404, forged.getResponseCode());
        HttpURLConnection noTokCat = (HttpURLConnection) new URL(gate + "/vip/catalog").openConnection();
        assertEquals(401, noTokCat.getResponseCode());
    }

    /** Rev3 refresh-for-resume, through the app's REAL Downloader: a partial download whose
     *  signed URL has expired must refresh via /vip/catalog and complete with a Range resume. */
    @Test public void realPythonGateResumeWithFreshUrl() throws Exception {
        Vip.GATE = gate2;   // short-TTL gate (2s signed URLs), same state and code
        try {
            assertEquals(1, runCheck(code));
            final String tok = mem.get();

            AtomicReference<java.util.List<Addon>> got = new AtomicReference<>();
            assertEquals(1, runFetch(got));
            Addon a = got.get().get(0);
            final String staleUrl = a.download;
            assertTrue(staleUrl.startsWith(gate2 + "/vip/file/pack-one?s="));

            // Seed an honest partial: the first 1024 bytes, fetched with the token, Range-verified.
            HttpURLConnection p = (HttpURLConnection) new URL(staleUrl).openConnection();
            p.setRequestProperty("X-VIP-Token", tok);
            p.setRequestProperty("Range", "bytes=0-1023");
            assertEquals(206, p.getResponseCode());
            byte[] head = read(p);
            assertEquals(1024, head.length);

            Downloader.Task t = new Downloader.Task();
            t.key = "pack-one.mcaddon";
            t.name = "pack-one.mcaddon";
            t.url = staleUrl;
            t.validator = "e2e";   // non-empty: partial bytes are treated as resumable
            Downloader.tasks(c);   // triggers load(): sets the app context
            Method partM = Downloader.class.getDeclaredMethod("part", Context.class, Downloader.Task.class);
            partM.setAccessible(true);
            File part = (File) partM.invoke(null, c, t);
            part.getParentFile().mkdirs();
            try (FileOutputStream o = new FileOutputStream(part)) { o.write(head); }

            Thread.sleep(3500);   // let the 2s signed URL actually expire
            HttpURLConnection staleCheck = (HttpURLConnection) new URL(staleUrl).openConnection();
            staleCheck.setRequestProperty("X-VIP-Token", tok);
            assertEquals("stale URL must be dead before resume", 404, staleCheck.getResponseCode());

            Method transfer = Downloader.class.getDeclaredMethod("transfer", Context.class, Downloader.Task.class);
            transfer.setAccessible(true);
            boolean ok = (Boolean) transfer.invoke(null, c, t);
            assertTrue("resume with refreshed URL failed: " + t.error, ok);
            assertTrue("URL must have been refreshed via catalog", !staleUrl.equals(t.url));
            assertTrue(t.url.startsWith(gate2 + "/vip/file/pack-one?s="));
            File done = new File(part.getParentFile(), t.name);
            assertTrue(done.isFile());
            assertEquals(a.size, done.length());
            byte[] all = new byte[(int) done.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(done)) {
                int off = 0, n;
                while (off < all.length && (n = in.read(all, off, all.length - off)) > 0) off += n;
            }
            for (int i = 0; i < 1024; i++) assertEquals("resume must append to the partial bytes", head[i], all[i]);
            done.delete();
        } finally {
            Vip.GATE = gate;
        }
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
