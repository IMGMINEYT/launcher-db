package com.imgmine.launcher67;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** 429 cooldown semantics: Retry-After honored per origin, stale cache served as fallback,
 *  and no network connection attempted while a cooldown is active. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class NetTest {

    static class FakeConn extends HttpURLConnection {
        final int status; final byte[] body; final Map<String, String> respHeaders;
        FakeConn(URL u, int status, byte[] body, Map<String, String> h) {
            super(u); this.status = status; this.body = body; this.respHeaders = h;
        }
        public void connect() {}
        public void disconnect() {}
        public boolean usingProxy() { return false; }
        @Override public int getResponseCode() { return status; }
        @Override public String getHeaderField(String n) { return respHeaders.get(n); }
        @Override public InputStream getInputStream() throws IOException {
            if (status >= 400) throw new IOException("HTTP " + status);
            return new ByteArrayInputStream(body);
        }
    }

    @SuppressWarnings("unchecked")
    private static void clearCooldowns() throws Exception {
        Field f = Net.class.getDeclaredField("COOLDOWN");
        f.setAccessible(true);
        ((ConcurrentHashMap<String, Long>) f.get(null)).clear();
    }

    private static String cacheKey(String url) throws Exception {
        Method k = Net.class.getDeclaredMethod("key", String.class);
        k.setAccessible(true);
        return (String) k.invoke(null, url);
    }

    private static File cacheDir() throws Exception {
        Field d = Net.class.getDeclaredField("dir");
        d.setAccessible(true);
        return (File) d.get(null);
    }

    @Test
    public void cooldownHonorsRetryAfter() throws Exception {
        clearCooldowns();
        Net.Connections old = Net.connections;
        final AtomicInteger connects = new AtomicInteger();
        try {
            final Map<String, String> h = new HashMap<>();
            h.put("Retry-After", "120");
            Net.connections = u -> { connects.incrementAndGet(); return new FakeConn(u, 429, new byte[0], h); };
            String url = "https://fixture429.test/a";
            try { Net.get(url, 1024, 0, "t"); fail("expected HTTP 429"); }
            catch (IOException e) { assertTrue(e.getMessage(), e.getMessage().contains("429")); }
            assertEquals(1, connects.get());
            try { Net.get(url, 1024, 0, "t"); fail("expected cooldown refusal"); }
            catch (IOException e) { assertTrue(e.getMessage(), e.getMessage().contains("cooldown")); }
            assertEquals("cooldown must not connect again", 1, connects.get());
        } finally {
            Net.connections = old;
            clearCooldowns();
        }
    }

    @Test
    public void staleCacheServedDuring429AndCooldown() throws Exception {
        clearCooldowns();
        Net.init(RuntimeEnvironment.getApplication());
        Net.Connections old = Net.connections;
        final AtomicInteger connects = new AtomicInteger();
        final String url = "https://fixture429b.test/b";
        File body = null, meta = null;
        try {
            byte[] cached = "cached-bytes".getBytes("UTF-8");
            String kk = cacheKey(url);
            body = new File(cacheDir(), kk + ".bin");
            meta = new File(cacheDir(), kk + ".json");
            try (FileOutputStream o = new FileOutputStream(body)) { o.write(cached); }
            try (FileOutputStream o = new FileOutputStream(meta)) { o.write("{\"at\":0}".getBytes("UTF-8")); }

            final Map<String, String> h = new HashMap<>();
            h.put("Retry-After", "120");
            Net.connections = u -> { connects.incrementAndGet(); return new FakeConn(u, 429, new byte[0], h); };

            byte[] first = Net.get(url, 1024, 60000, "t");
            assertEquals("429 with an expired cache must serve the stale body", "cached-bytes", new String(first, "UTF-8"));
            assertEquals(1, connects.get());

            byte[] second = Net.get(url, 1024, 60000, "t");
            assertEquals("active cooldown must serve the stale body", "cached-bytes", new String(second, "UTF-8"));
            assertEquals("cooldown must not connect again", 1, connects.get());
        } finally {
            Net.connections = old;
            clearCooldowns();
            if (body != null) body.delete();
            if (meta != null) meta.delete();
        }
    }
}
