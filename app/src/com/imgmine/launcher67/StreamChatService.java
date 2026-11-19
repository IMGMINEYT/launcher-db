package com.imgmine.launcher67;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.util.*;

/** Bridge increment: local loopback WebSocket server (Minecraft connects via /connect),
 *  a bounded chat queue that sends commandRequest frames, plus the YouTube live-chat poller.
 *  Controls live in the 67 ball panel (Panel bridge screen), not in a separate overlay.
 *  Binds 127.0.0.1 only. Chat text is JSON-encoded data, never a command. */
public class StreamChatService extends Service {
    static volatile boolean running;
    static volatile String state = "OFF";       // OFF, WAITING, CONNECTED
    static volatile int port;
    static volatile boolean chatOn = true;
    static volatile int sent, droppedTotal;
    static volatile String ytState = "YT OFF";
    static volatile String lastCmdError = "";   // last non-zero commandResponse statusMessage, for the bridge screen

    private static final int MAX_INFLIGHT = 20;   // client errors near ~100 outstanding commands; stay far below
    private static final long INFLIGHT_TTL = 10000;
    private static final int QUEUE_CAP = 50;
    private static final int BATCH_LINES = 3, BATCH_CHARS = 4000, TICK_MS = 800;

    private final Ws.ChatQueue queue = new Ws.ChatQueue(QUEUE_CAP);
    private final Map<String, Long> inflight = new HashMap<>();
    private ServerSocket server;
    private volatile Socket client;
    private volatile OutputStream clientOut;
    private Thread acceptThread, sendThread, ytThread;
    private String ytVideo, ytChatId, ytToken;
    static volatile boolean ytQuotaStop;   // cleared when the user saves new YouTube settings

    private static StreamChatService live;
    /** Test hook + panel feedback: queue one chat line (already plain text; escaped on send). */
    static void post(String author, String text) {
        StreamChatService s = live;
        if (s == null) return;
        s.queue.add(Ws.scriptChat(author, text));
    }
    static void postTest() {
        StreamChatService s = live;
        if (s != null) s.queue.add(Ws.SCRIPT_TEST);
    }
    static void postControl(String cmd) {
        StreamChatService s = live;
        if (s != null) s.queue.add(cmd);
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        Ui.init(this);
        startForeground(3, notification());
        live = this;
        running = true;
        openServer();
        // push persisted display settings; they sit in the queue until Minecraft connects
        queue.add(Ws.scriptMode(mode()));
        queue.add(Ws.scriptLifetime(lifetime()));
        startSender();
        startYt();
    }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        if (i != null && "stop".equals(i.getAction())) { stopSelf(); return START_NOT_STICKY; }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        live = null;
        running = false;
        state = "OFF";
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        closeClient();
        super.onDestroy();
    }

    // ---------- bridge ----------

    private void openServer() {
        try {
            server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
            port = server.getLocalPort();
            state = "WAITING";
        } catch (Exception e) {
            state = "OFF";
            return;
        }
        acceptThread = new Thread(new Runnable() { public void run() { acceptLoop(); } }, "ws-accept");
        acceptThread.start();
    }

    private void acceptLoop() {
        while (server != null && !server.isClosed()) {
            try {
                final Socket s = server.accept();
                new Thread(new Runnable() { public void run() { session(s); } }, "ws-client").start();
            } catch (Exception e) { return; }
        }
    }

    /** One client slot: a newer connection replaces the old one (they hold no authority; worst case is slot occupation). */
    private void session(Socket s) {
        try {
            s.setTcpNoDelay(true);
            InputStream in = s.getInputStream();
            String head = Ws.readHttpHead(in, 8192);
            String key = Ws.header(head, "Sec-WebSocket-Key");
            String first = head.split("\r\n")[0];
            if (key == null || !first.startsWith("GET")) { s.close(); return; }
            OutputStream out = s.getOutputStream();
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
                    + Ws.acceptKey(key) + "\r\n\r\n").getBytes("UTF-8"));
            out.flush();
            synchronized (this) {
                closeClient();
                client = s; clientOut = out;
            }
            state = "CONNECTED";
            while (true) {
                Ws.Frame f = Ws.read(in);
                if (f == null) break;
                if (f.op == 8) break;                       // close
                if (f.op == 9) Ws.write(out, 10, f.data);   // ping -> pong
                if (f.op == 1) noteInbound(f.data);          // text: commandResponse / event; never executed here
            }
        } catch (Exception ignored) {
        } finally {
            synchronized (this) {
                if (client == s) { client = null; clientOut = null; }
            }
            try { s.close(); } catch (Exception ignored) {}
            if (state.equals("CONNECTED") && client == null) state = "WAITING";
        }
    }

    /** Inbound protocol JSON: releases inflight slots and surfaces real command failures on the panel.
     *  Parsed as data only; never turned into actions. */
    private void noteInbound(byte[] data) {
        try {
            String j = new String(data, "UTF-8");
            if (!j.contains("commandResponse")) return;
            org.json.JSONObject o = new org.json.JSONObject(j);
            org.json.JSONObject head = o.optJSONObject("header");
            if (head != null) {
                String rid = head.optString("requestId", "");
                synchronized (inflight) { inflight.remove(rid); }
            }
            org.json.JSONObject body = o.optJSONObject("body");
            if (body != null && body.optInt("statusCode", 0) != 0) {
                String msg = body.optString("statusMessage", "command failed");
                lastCmdError = msg.length() > 100 ? msg.substring(0, 100) : msg;
            }
        } catch (Exception ignored) {}
    }

    private void closeClient() {
        Socket s = client; client = null; clientOut = null;
        if (s != null) try { s.close(); } catch (Exception ignored) {}
    }

    private void startSender() {
        sendThread = new Thread(new Runnable() { public void run() {
            while (live == StreamChatService.this) {
                try { tickSend(); } catch (Exception ignored) {}
                try { Thread.sleep(TICK_MS); } catch (InterruptedException e) { return; }
            }
        } }, "ws-send");
        sendThread.start();
    }

    private void tickSend() throws IOException {
        OutputStream out = clientOut;
        if (out == null || !chatOn) return;
        List<String> lines;
        synchronized (inflight) {
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<String, Long>> it = inflight.entrySet().iterator();
            while (it.hasNext()) if (now - it.next().getValue() > INFLIGHT_TTL) it.remove();
            if (inflight.size() >= MAX_INFLIGHT) return;
            lines = queue.batch(BATCH_LINES, BATCH_CHARS);
            if (lines.isEmpty()) return;
            for (String cmd : lines) {
                if (inflight.size() >= MAX_INFLIGHT) { queue.add(cmd); break; }
                String id = Ws.newId();
                inflight.put(id, now);
                Ws.write(out, 1, Ws.commandRequest(cmd, id));
                sent++;
            }
            droppedTotal = queue.dropped;
        }
    }

    // ---------- YouTube live chat feed (official Data API; key from launcher settings) ----------

    private void startYt() {
        ytThread = new Thread(new Runnable() { public void run() {
            while (live == StreamChatService.this) {
                try { ytCycle(); } catch (Exception ignored) {}
                try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
            }
        } }, "yt-poll");
        ytThread.start();
    }

    private void ytCycle() {
        SharedPreferences sp = getSharedPreferences("ui", MODE_PRIVATE);
        String url = sp.getString("stream_yt_url", "");
        String key;
        try {
            key = KeyBox.unprotect(this, sp.getString("stream_yt_key_enc", ""));
            // one-time migration from the early plaintext field, then remove it
            String legacy = sp.getString("stream_yt_key", "");
            if (legacy.length() > 0) {
                sp.edit().putString("stream_yt_key_enc", KeyBox.protect(this, legacy)).remove("stream_yt_key").apply();
                key = legacy;
            }
        } catch (Exception e) { ytState = "YT KEY STORAGE ERROR"; return; }
        if (url.length() == 0) { ytState = "YT OFF"; ytChatId = null; return; }
        if (ytQuotaStop) { ytState = "YT QUOTA STOP"; return; }
        if (key.length() == 0) { ytState = "YT NO KEY"; ytChatId = null; return; }
        String vid = Yt.videoId(url);
        if (vid == null) { ytState = "YT BAD URL"; ytChatId = null; return; }
        if (!vid.equals(ytVideo)) { ytVideo = vid; ytChatId = null; ytToken = null; }
        try {
            if (ytChatId == null) {
                ytState = "YT CONNECTING";
                ytChatId = Yt.resolveChatId(vid, key);
                ytToken = null;
                if (ytChatId == null) { ytState = "YT NOT LIVE / KEY?"; sleep(30000); return; }
            }
            ytState = "YT LIVE";
            while (live == this && ytChatId != null) {
                Yt.PollResult r = Yt.poll(ytChatId, key, ytToken);
                if (r.quota) { ytState = "YT QUOTA STOP"; ytQuotaStop = true; ytChatId = null; return; }
                if (r.error != null) {
                    ytState = "YT ERROR";
                    ytChatId = null;
                    sleep(r.retryAfterMs > 0 ? r.retryAfterMs : 30000);
                    return;
                }
                ytToken = r.nextToken;
                for (Yt.Msg m : r.msgs) queue.add(Ws.scriptChat(m.author, m.text));
                droppedTotal = queue.dropped;
                sleep(r.pollMs);
            }
        } catch (Exception e) {
            ytState = "YT ERROR";
            ytChatId = null;
            sleep(30000);
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private String mode() {
        return getSharedPreferences("ui", MODE_PRIVATE).getString("stream_mode", "both");
    }

    private int lifetime() {
        return Math.max(1, Math.min(10, getSharedPreferences("ui", MODE_PRIVATE).getInt("stream_lifetime", 5)));
    }

    private Notification notification() {
        String ch = "streamchat";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(ch, "Stream chat bridge", NotificationManager.IMPORTANCE_LOW);
            c.setShowBadge(false);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, ch) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("Stream chat bridge")
                .setContentText("Local server for Minecraft /connect")
                .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                        Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        return b.build();
    }
}
