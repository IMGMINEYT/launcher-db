package com.imgmine.launcher67;

import android.content.Context;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FeedTest {

    private static Object get(Panel p, String n) throws Exception {
        Field f = Panel.class.getDeclaredField(n);
        f.setAccessible(true);
        return f.get(p);
    }

    private static Panel newPanel() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        Ui.init(c);
        Panel p = new Panel(c, new Runnable() { public void run() {} });
        Method b = Panel.class.getDeclaredMethod("build");
        b.setAccessible(true);
        b.invoke(p);
        return p;
    }

    /** Pump the main looper while the worker thread fetches real network pages. */
    private static void pump(Panel p, long maxMs) throws Exception {
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < maxMs) {
            ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS);
            ShadowLooper.runUiThreadTasks();
            if (!(Boolean) get(p, "feedLoading")) { ShadowLooper.runUiThreadTasks(); return; }
        }
        ShadowLooper.runUiThreadTasks();
    }

    private static void loadMore(Panel p) throws Exception {
        Method m = Panel.class.getDeclaredMethod("loadMore");
        m.setAccessible(true);
        m.invoke(p);
    }

    @SuppressWarnings("unchecked")
    private static List<Addon> feed(Panel p) throws Exception { return (List<Addon>) get(p, "feed"); }
    @SuppressWarnings("unchecked")
    private static List<Addon> view(Panel p) throws Exception { return (List<Addon>) get(p, "view"); }

    private static void chip(Panel p, int i) throws Exception {
        TextView[] chips = (TextView[]) get(p, "chips");
        final TextView c = chips[i];
        c.performClick();
        ShadowLooper.runUiThreadTasks();
    }

    @Test
    public void allFeedIsEndlessAddons() throws Exception {
        Panel p = newPanel();
        int[] sizes = new int[4];
        for (int round = 0; round < 4; round++) {
            loadMore(p);
            pump(p, 30000);
            sizes[round] = feed(p).size();
        }
        System.out.println("ALL feed sizes per round: " + sizes[0] + "," + sizes[1] + "," + sizes[2] + "," + sizes[3]);
        assertTrue("round 1 empty", sizes[0] > 0);
        int growths = 0;
        for (int i = 1; i < 4; i++) if (sizes[i] > sizes[i - 1]) growths++;
        assertTrue("feed did not keep growing across rounds: " + java.util.Arrays.toString(sizes), growths >= 2);
        assertTrue("feed too small after 4 rounds: " + sizes[3], sizes[3] >= 40);
        for (Addon a : feed(p)) {
            if (a.cf) assertEquals("MCPEDL item in ALL is not an addon: " + a.name + " (" + a.type + ")", "addon", a.type);
        }
    }

    @Test
    public void worldsChipLoadsWorlds() throws Exception {
        Panel p = newPanel();
        chip(p, 2); // WORLDS
        loadMore(p);
        pump(p, 30000);
        int worlds = 0;
        for (Addon a : feed(p)) if ("world".equals(a.type)) worlds++;
        System.out.println("WORLDS tab feed items: " + feed(p).size() + ", worlds=" + worlds);
        assertTrue("no worlds loaded on WORLDS chip", worlds > 0);
    }

    /** laby.net rate-limits this runner (429 with multi-hour Retry-After, observed 2026-11-19).
     *  The suite makes NO live laby.net requests: this fixture serves the search API and answers 404 for
     *  render/meta URLs, while every other host delegates to the real connection factory. Installed
     *  JVM-wide and never restored, so lagging image-prefetch threads cannot reach laby.net after a test
     *  ends. Source availability is therefore NOT verified by these tests. */
    private static void labyFixture() {
        if (Net.connections instanceof LabyFixtureFactory) return;
        Net.connections = new LabyFixtureFactory(Net.connections);
    }

    static class LabyFixtureFactory implements Net.Connections {
        final Net.Connections real;
        LabyFixtureFactory(Net.Connections real) { this.real = real; }
        public java.net.HttpURLConnection connect(java.net.URL u) throws java.io.IOException {
            if (u.getHost().endsWith("laby.net")) return new LabyConn(u);
            return real.connect(u);
        }
    }

    static class LabyConn extends java.net.HttpURLConnection {
        static final String FIXTURE = "{\"results\":["
                + "{\"image_hash\":\"a1b2c3d4e5f6\",\"slim\":false,\"tags\":\"cape blue knight\",\"top_tags\":[\"cape\"]},"
                + "{\"image_hash\":\"f6e5d4c3b2a1\",\"slim\":true,\"tags\":\"red hood\",\"top_tags\":[]},"
                + "{\"image_hash\":\"001122334455\",\"slim\":false,\"tags\":\"green creeper style\",\"top_tags\":[\"creeper\"]}" 
                + "]}";
        final int status; final byte[] body;
        LabyConn(java.net.URL u) {
            super(u);
            if (u.getPath() != null && u.getPath().startsWith("/api/v3/search/textures/skin")) {
                status = 200; body = FIXTURE.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            } else { status = 404; body = new byte[0]; }
        }
        public void connect() {}
        public void disconnect() {}
        public boolean usingProxy() { return false; }
        @Override public int getResponseCode() { return status; }
        @Override public java.io.InputStream getInputStream() throws java.io.IOException {
            if (status >= 400) throw new java.io.IOException("HTTP " + status);
            return new java.io.ByteArrayInputStream(body);
        }
    }

    @Test
    public void skinsChipLabyFirst() throws Exception {
        labyFixture();
        Panel p = newPanel();
        chip(p, 4); // SKINS
        loadMore(p);
        pump(p, 45000);
        List<Addon> v = view(p);
        int labyIdx = -1, mcIdx = -1;
        for (int i = 0; i < v.size(); i++) {
            Addon a = v.get(i);
            if ("hdr".equals(a.type)) {
                if (a.name.startsWith("LABY") && labyIdx < 0) labyIdx = i;
                if (a.name.startsWith("MCPEDL") && mcIdx < 0) mcIdx = i;
            }
        }
        List<?> labyFeed = (List<?>) get(p, "labyFeed");
        System.out.println("SKINS view rows=" + v.size() + " labyHdr=" + labyIdx + " mcHdr=" + mcIdx + " labyFeed=" + labyFeed.size());
        assertTrue("LABY popular section missing in SKINS tab", labyIdx >= 0);
        assertTrue("no Laby skins loaded", labyFeed.size() > 0);
        if (mcIdx >= 0) assertTrue("LABY section must come before MCPEDL packs", labyIdx < mcIdx);
    }

    @Test
    public void skinsSearchHitsLaby() throws Exception {
        labyFixture();
        Panel p = newPanel();
        chip(p, 4); // SKINS
        EditText search = (EditText) get(p, "search");
        search.setText("cape");
        ShadowLooper.runUiThreadTasks();
        // panel view is detached in tests, so view.postDelayed never fires; invoke the search runner directly
        Method sn = Panel.class.getDeclaredMethod("srchNow");
        sn.setAccessible(true);
        sn.invoke(p);
        List<?> labyAll = (List<?>) get(p, "labyAll");
        long t0 = System.currentTimeMillis();
        while (labyAll.isEmpty() && System.currentTimeMillis() - t0 < 45000) {
            ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS);
            ShadowLooper.runUiThreadTasks();
            labyAll = (List<?>) get(p, "labyAll");
        }
        ShadowLooper.runUiThreadTasks();
        List<?> cfAll = (List<?>) get(p, "cfAll");
        int gen = (Integer) get(p, "srchGen");
        System.out.println("diag: labyAll=" + labyAll.size() + " cfAll=" + cfAll.size() + " srchGen=" + gen + " q=[" + search.getText().toString() + "]");
        List<Addon> v = view(p);
        boolean labyHdr = false;
        for (Addon a : v) if ("hdr".equals(a.type) && a.name.startsWith("LABY")) labyHdr = true;
        System.out.println("SKINS search: labyAll=" + labyAll.size() + " viewRows=" + v.size() + " labyHdr=" + labyHdr);
        assertTrue("Laby search returned nothing for 'cape'", labyAll.size() > 0);
        assertTrue("LABY SKINS section missing in search results", labyHdr);
    }
}
