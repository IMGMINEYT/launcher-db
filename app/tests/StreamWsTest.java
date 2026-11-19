package com.imgmine.launcher67;
import static org.junit.Assert.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ServiceController;
import java.io.*;
import java.net.*;
import java.lang.reflect.*;
import java.util.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class StreamWsTest {

    static Object call(Object o, String n, Class<?>[] t, Object... a) throws Exception {
        Method m = o.getClass().getDeclaredMethod(n, t); m.setAccessible(true); return m.invoke(o, a);
    }
    static Object get(Object o, String n) throws Exception {
        Field f = o.getClass().getDeclaredField(n); f.setAccessible(true); return f.get(o);
    }

    @Test public void acceptKeyRfcVector() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", Ws.acceptKey("dGhlIHNhbXBsZSBub25jZQ=="));
    }

    @Test public void base64Edges() {
        assertEquals("", Ws.b64(new byte[]{}));
        assertEquals("TQ==", Ws.b64(new byte[]{0x4D}));
        assertEquals("TWE=", Ws.b64(new byte[]{0x4D, 0x61}));
        assertEquals("TWFu", Ws.b64(new byte[]{0x4D, 0x61, 0x6E}));
    }

    static void clientWrite(OutputStream out, int op, byte[] data, byte[] mask) throws IOException {
        out.write(0x80 | op);
        int len = data.length;
        if (len < 126) out.write(0x80 | len);
        else if (len <= 0xFFFF) { out.write(0x80 | 126); out.write(len >>> 8); out.write(len); }
        else { out.write(0x80 | 127); long L = len; for (int i = 7; i >= 0; i--) out.write((int) ((L >>> (8 * i)) & 0xFF)); }
        out.write(mask);
        for (int i = 0; i < len; i++) out.write(data[i] ^ mask[i & 3]);
        out.flush();
    }

    @Test public void maskedFrameParse() throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        clientWrite(bo, 1, "hello".getBytes("UTF-8"), new byte[]{1, 2, 3, 4});
        Ws.Frame f = Ws.read(new ByteArrayInputStream(bo.toByteArray()));
        assertTrue(f.fin); assertEquals(1, f.op);
        assertEquals("hello", new String(f.data, "UTF-8"));
    }

    @Test public void extendedLengthsRoundtrip() throws Exception {
        byte[] mid = new byte[700]; new Random(7).nextBytes(mid);
        ByteArrayOutputStream b1 = new ByteArrayOutputStream(); Ws.write(b1, 2, mid);
        assertArrayEquals(mid, Ws.read(new ByteArrayInputStream(b1.toByteArray())).data);
        byte[] big = new byte[70000]; new Random(9).nextBytes(big);
        ByteArrayOutputStream b2 = new ByteArrayOutputStream(); Ws.write(b2, 2, big);
        assertArrayEquals(big, Ws.read(new ByteArrayInputStream(b2.toByteArray())).data);
    }

    @Test public void fragmentAndPingFlags() throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bo.write(0x01); bo.write(0x83); bo.write(new byte[]{9, 9, 9, 9});
        byte[] p = "abc".getBytes("UTF-8");
        for (int i = 0; i < 3; i++) bo.write(p[i] ^ 9);
        Ws.Frame f = Ws.read(new ByteArrayInputStream(bo.toByteArray()));
        assertFalse(f.fin); assertEquals(1, f.op);
        ByteArrayOutputStream ping = new ByteArrayOutputStream();
        clientWrite(ping, 9, new byte[]{1, 2}, new byte[]{0, 0, 0, 0});
        assertEquals(9, Ws.read(new ByteArrayInputStream(ping.toByteArray())).op);
    }

    @Test public void oversizeFrameRejected() throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bo.write(0x82); bo.write(127);
        long len = 2L << 20;
        for (int i = 7; i >= 0; i--) bo.write((int) ((len >>> (8 * i)) & 0xFF));
        try { Ws.read(new ByteArrayInputStream(bo.toByteArray())); fail(); }
        catch (IOException e) { assertTrue(e.getMessage().contains("too big")); }
    }

    @Test public void scriptChatPayloadIsSafeJson() throws Exception {
        String cmd = Ws.scriptChat("us\"er", "nice! :) 100% \"quoted\" back\\slash \u00A7a colored?");
        assertTrue(cmd.startsWith("scriptevent tysc:chat "));
        org.json.JSONObject p = new org.json.JSONObject(cmd.substring("scriptevent tysc:chat ".length()));
        assertEquals("us\"er", p.getString("n"));
        // section sign stripped (color injection), normal punctuation and quotes preserved
        assertEquals("nice! :) 100% \"quoted\" back\\slash a colored?", p.getString("t"));
        assertEquals("c", p.getString("c"));
        assertEquals("YT", p.getString("p"));
        // caps
        org.json.JSONObject big = new org.json.JSONObject(
                Ws.scriptChat("abcdefghijklmnopqrstuvwxyz0123456789", new String(new char[300]).replace('\0', 'x'))
                        .substring("scriptevent tysc:chat ".length()));
        assertEquals(24, big.getString("n").length());
        assertEquals(120, big.getString("t").length());
    }

    @Test public void commandRequestShape() throws Exception {
        String id = Ws.newId();
        org.json.JSONObject j = new org.json.JSONObject(new String(Ws.commandRequest(Ws.scriptChat("n", "t"), id), "UTF-8"));
        org.json.JSONObject h = j.getJSONObject("header");
        assertEquals(id, h.getString("requestId"));
        assertEquals("commandRequest", h.getString("messagePurpose"));
        assertEquals(1, h.getInt("version"));
        String line = j.getJSONObject("body").getString("commandLine");
        assertTrue(line.startsWith("scriptevent tysc:chat "));
        // chat text can never break out into a second command: no raw newline survives anywhere
        String inject = Ws.scriptChat("n", "a\nb\r/say hack");
        assertFalse(inject.contains("\n"));
        assertFalse(inject.contains("\r"));
        org.json.JSONObject ip = new org.json.JSONObject(inject.substring("scriptevent tysc:chat ".length()));
        assertEquals("ab/say hack", ip.getString("t"));   // inert data inside the payload string
    }

    @Test public void controlCommandsAreFixed() {
        assertEquals("scriptevent tysc:mode both", Ws.scriptMode("both"));
        assertEquals("scriptevent tysc:test", Ws.SCRIPT_TEST);
        assertEquals("scriptevent tysc:clear", Ws.SCRIPT_CLEAR);
    }

    @Test public void scriptLifetimeClamps1to10() {
        assertEquals("scriptevent tysc:lifetime 5", Ws.scriptLifetime(5));
        assertEquals("scriptevent tysc:lifetime 1", Ws.scriptLifetime(0));
        assertEquals("scriptevent tysc:lifetime 1", Ws.scriptLifetime(-7));
        assertEquals("scriptevent tysc:lifetime 10", Ws.scriptLifetime(99));
    }

    @Test public void bundledAddonPassesPackChecks() throws Exception {
        java.io.File f = new java.io.File(System.getProperty("launcher.root"), "app/assets/streamchat.mcaddon");
        assertTrue("bundled addon exists", f.isFile());
        java.util.zip.ZipFile z = new java.util.zip.ZipFile(f);
        assertNotNull("behavior pack manifest", z.getEntry("StreamChatBP/manifest.json"));
        assertNotNull("resource pack manifest", z.getEntry("StreamChatRP/manifest.json"));
        assertNotNull("script entry", z.getEntry("StreamChatBP/scripts/main.js"));
        z.close();
        assertTrue(Downloader.isPack("StreamChat.mcaddon"));
        java.lang.reflect.Method lp = Downloader.class.getDeclaredMethod("looksPaid", java.io.File.class);
        lp.setAccessible(true);
        assertFalse("bundled addon must not trip paid-content check", (Boolean) lp.invoke(null, f));
    }

    @Test public void queueDropOldestAndBatch() {
        Ws.ChatQueue q = new Ws.ChatQueue(3);
        q.add("a"); q.add("b"); q.add("c"); q.add("d");
        assertEquals(3, q.size()); assertEquals(1, q.dropped);
        List<String> b = q.batch(2, 100);
        assertEquals(2, b.size()); assertEquals("b", b.get(0));
        Ws.ChatQueue q2 = new Ws.ChatQueue(10);
        q2.add("xxxxx"); q2.add("yyyyy"); q2.add("zzzzz");
        assertEquals(2, q2.batch(4, 11).size());
        assertEquals(1, q2.batch(4, 100).size());
        assertEquals(0, q2.size());
    }

    @Test public void httpHeadParse() throws Exception {
        String req = "GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nSec-WebSocket-Key: abc==\r\n\r\n";
        String head = Ws.readHttpHead(new ByteArrayInputStream(req.getBytes("UTF-8")), 8192);
        assertEquals("abc==", Ws.header(head, "Sec-WebSocket-Key"));
        assertNull(Ws.header(head, "Nope"));
    }

    // ---------- service-level runtime tests over a real loopback socket ----------

    private ServiceController<StreamChatService> ctl;
    private StreamChatService svc;
    private java.util.List<String> drainedConfig;

    @Before public void startService() throws Exception {
        ctl = Robolectric.buildService(StreamChatService.class);
        svc = ctl.create().get();
        long end = System.currentTimeMillis() + 5000;
        while (StreamChatService.port == 0 && System.currentTimeMillis() < end) {
            try { Thread.sleep(20); } catch (InterruptedException ignored) {}
        }
        assertTrue("server port assigned", StreamChatService.port > 0);
        assertEquals("WAITING", StreamChatService.state);
        // service startup enqueues the persisted display config (mode + lifetime); drain it so
        // delivery tests see only their own messages. servicePushesConfigOnStart asserts the drain.
        drainedConfig = ((Ws.ChatQueue) get(svc, "queue")).batch(10, 100000);
    }

    @Test public void servicePushesConfigOnStart() {
        assertEquals(2, drainedConfig.size());
        assertEquals("scriptevent tysc:mode both", drainedConfig.get(0));
        assertEquals("scriptevent tysc:lifetime 5", drainedConfig.get(1));
    }

    @After public void stopService() { ctl.destroy(); StreamChatService.port = 0; StreamChatService.state = "OFF"; StreamChatService.ytQuotaStop = false; StreamChatService.ytState = "YT OFF"; StreamChatService.lastCmdError = ""; }

    private Socket handshake() throws Exception {
        Socket c = new Socket("127.0.0.1", StreamChatService.port);
        c.setSoTimeout(5000);
        c.getOutputStream().write(("GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n").getBytes("UTF-8"));
        c.getOutputStream().flush();
        String head = Ws.readHttpHead(c.getInputStream(), 8192);
        assertTrue(head.startsWith("HTTP/1.1 101"));
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", Ws.header(head, "Sec-WebSocket-Accept"));
        long end = System.currentTimeMillis() + 3000;
        while (!"CONNECTED".equals(StreamChatService.state) && System.currentTimeMillis() < end) Thread.sleep(20);
        assertEquals("CONNECTED", StreamChatService.state);
        return c;
    }

    @Test public void serviceHandshakeAndChatDelivery() throws Exception {
        Socket c = handshake();
        StreamChatService.post("tester", "hello \"glitch\" :)");
        call(svc, "tickSend", new Class[]{});
        Ws.Frame f = Ws.read(c.getInputStream());
        assertEquals(1, f.op);
        org.json.JSONObject j = new org.json.JSONObject(new String(f.data, "UTF-8"));
        assertEquals("commandRequest", j.getJSONObject("header").getString("messagePurpose"));
        String line = j.getJSONObject("body").getString("commandLine");
        assertTrue(line.startsWith("scriptevent tysc:chat "));
        org.json.JSONObject p = new org.json.JSONObject(line.substring("scriptevent tysc:chat ".length()));
        assertEquals("tester", p.getString("n"));
        assertEquals("hello \"glitch\" :)", p.getString("t"));
        c.close();
    }

    @Test public void serviceSurfacesCommandErrors() throws Exception {
        Socket c = handshake();
        StreamChatService.lastCmdError = "";
        String resp = "{\"header\":{\"requestId\":\"x\",\"messagePurpose\":\"commandResponse\"},\"body\":{\"statusCode\":-2147352576,\"statusMessage\":\"No handler for event\"}}";
        clientWrite(c.getOutputStream(), 1, resp.getBytes("UTF-8"), new byte[]{5, 6, 7, 8});
        long end = System.currentTimeMillis() + 3000;
        while (StreamChatService.lastCmdError.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(20);
        assertEquals("No handler for event", StreamChatService.lastCmdError);
        // clean success responses do not raise the error surface
        StreamChatService.lastCmdError = "";
        String ok = "{\"header\":{\"requestId\":\"y\",\"messagePurpose\":\"commandResponse\"},\"body\":{\"statusCode\":0}}";
        clientWrite(c.getOutputStream(), 1, ok.getBytes("UTF-8"), new byte[]{5, 6, 7, 8});
        Thread.sleep(300);
        assertEquals("", StreamChatService.lastCmdError);
        c.close();
    }

    @Test public void servicePingPong() throws Exception {
        Socket c = handshake();
        clientWrite(c.getOutputStream(), 9, new byte[]{42}, new byte[]{1, 2, 3, 4});
        Ws.Frame f = Ws.read(c.getInputStream());
        assertEquals(10, f.op);
        assertArrayEquals(new byte[]{42}, f.data);
        c.close();
    }

    @Test public void serviceRespectsChatOff() throws Exception {
        Socket c = handshake();
        StreamChatService.chatOn = false;
        try {
            StreamChatService.post("tester", "should stay queued");
            call(svc, "tickSend", new Class[]{});
            assertEquals(1, ((Ws.ChatQueue) get(svc, "queue")).size());
            c.setSoTimeout(700);
            try { Ws.read(c.getInputStream()); fail("nothing should be sent while chat off"); }
            catch (SocketTimeoutException expected) {}
        } finally { StreamChatService.chatOn = true; c.close(); }
    }

    @Test public void serviceCapsInflight() throws Exception {
        Socket c = handshake();
        int wrote = 0;
        for (int i = 0; i < 30; i++) {
            for (int k = 0; k < 4; k++) StreamChatService.post("tester", "msg " + i + "-" + k);
            int before = ((Ws.ChatQueue) get(svc, "queue")).size();
            call(svc, "tickSend", new Class[]{});
            if (((Ws.ChatQueue) get(svc, "queue")).size() < before) wrote++;
        }
        @SuppressWarnings("unchecked") Map<String, Long> inflight = (Map<String, Long>) get(svc, "inflight");
        assertTrue("inflight capped", inflight.size() <= 20);
        assertTrue("queue still holds backlog", ((Ws.ChatQueue) get(svc, "queue")).size() > 0);
        assertTrue("some batches went out", wrote > 0);
        c.close();
    }

    @Test public void serviceNewClientReplacesOld() throws Exception {
        Socket first = handshake();
        Socket second = handshake();
        first.setSoTimeout(1500);
        boolean ended = false;
        try { ended = Ws.read(first.getInputStream()) == null; } catch (Exception e) { ended = true; }
        assertTrue("old client disconnected", ended);
        StreamChatService.post("tester", "to newest");
        call(svc, "tickSend", new Class[]{});
        Ws.Frame f = Ws.read(second.getInputStream());
        assertTrue(new String(f.data, "UTF-8").contains("to newest"));
        // TEST button path sends the addon self-test command
        StreamChatService.postTest();
        call(svc, "tickSend", new Class[]{});
        String tj = new String(Ws.read(second.getInputStream()).data, "UTF-8");
        assertTrue(tj.contains("scriptevent tysc:test"));
        second.close();
    }

    @Test public void serviceRejectsNonUpgrade() throws Exception {
        Socket c = new Socket("127.0.0.1", StreamChatService.port);
        c.setSoTimeout(3000);
        c.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".getBytes("UTF-8"));
        c.getOutputStream().flush();
        int b = c.getInputStream().read();
        assertEquals("closed without 101", -1, b);
        c.close();
    }
}
