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

    private static volatile StreamChatService live;
    private static volatile long ytRevision;
    private long activeYtRevision = -1;
    private String ytConfig;
    private YtLocal localYt;
    static YtLocal.Transport localTransport = new YtLocal.PhoneTransport();
    static synchronized void youtubeSettingsChanged() {
        ytQuotaStop = false;
        ytRevision++;
        StreamChatService s = live;
        if (s != null && s.ytThread != null) s.ytThread.interrupt();
    }
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
        if (ytThread != null) ytThread.interrupt();
        if (sendThread != null) sendThread.interrupt();
        ytState = "YT OFF";
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
            // replay persisted display settings the moment the session is ready, so a world
            // that connects long after service start still gets mode + bubble lifetime
            Ws.write(out, 1, Ws.commandRequest(Ws.scriptMode(mode()), Ws.newId()));
            Ws.write(out, 1, Ws.commandRequest(Ws.scriptLifetime(lifetime()), Ws.newId()));
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

    // ---------- YouTube sources. Official mode remains the existing default. ----------
    private void startYt() {
        ytThread = new Thread(new Runnable() { public void run() {
            while (live == StreamChatService.this) {
                long delay = 5000;
                try { delay = ytStep(); } catch (Exception e) { ytState = "YT ERROR"; delay = 30000; }
                if (live != StreamChatService.this) return;
                try { Thread.sleep(Math.max(1000, delay)); }
                catch (InterruptedException e) { if (live != StreamChatService.this) return; }
            }
        } }, "yt-poll");
        ytThread.start();
    }

    // One bounded step enables settings changes/cancellation between responses.
    // Package-private so fixtures can run the actual service state path without networking.
    long ytStep() {
        SharedPreferences sp = getSharedPreferences("ui", MODE_PRIVATE);
        String url = sp.getString("stream_yt_url", "").trim();
        String source = sp.getString("stream_yt_source", "api");
        long revision = ytRevision;
        String config = source + "\n" + url + "\n" + sp.getString("stream_yt_key_enc", "");
        if (!config.equals(ytConfig) || activeYtRevision != revision) {
            ytConfig = config; activeYtRevision = revision;
            ytVideo = null; ytChatId = null; ytToken = null; localYt = null;
        }
        if (url.isEmpty()) { ytState = "YT OFF"; return 5000; }
        if ("local".equals(source)) return localStep(sp, url, config, revision);
        if (ytQuotaStop) { ytState = "YT QUOTA STOP"; return 5000; }
        String key;
        try {
            key = KeyBox.unprotect(this, sp.getString("stream_yt_key_enc", ""));
            String legacy = sp.getString("stream_yt_key", "");
            if (legacy.length() > 0) {
                sp.edit().putString("stream_yt_key_enc", KeyBox.protect(this, legacy)).remove("stream_yt_key").apply();
                key = legacy;
            }
        } catch (Exception e) { ytState = "YT KEY STORAGE ERROR"; return 5000; }
        if (key.isEmpty()) { ytState = "YT NO KEY"; return 5000; }
        String vid = Yt.videoId(url);
        if (vid == null) { ytState = "YT BAD URL"; return 5000; }
        if (!vid.equals(ytVideo)) { ytVideo = vid; ytChatId = null; ytToken = null; }
        try {
            if (ytChatId == null) {
                ytState = "YT CONNECTING";
                ytChatId = Yt.resolveChatId(vid, key); ytToken = null;
                if (ytChatId == null) { ytState = "YT NOT LIVE / KEY?"; return 30000; }
            }
            if (!current(config, revision)) return 1000;
            Yt.PollResult r = Yt.poll(ytChatId, key, ytToken);
            if (!current(config, revision)) return 1000;
            if (r.quota) { ytState = "YT QUOTA STOP"; ytQuotaStop = true; ytChatId = null; return 5000; }
            if (r.error != null) { ytState = "YT ERROR"; ytChatId = null; return r.retryAfterMs > 0 ? r.retryAfterMs : 30000; }
            ytToken = r.nextToken;
            for (Yt.Msg m : r.msgs) queue.add(Ws.scriptChat(m.author, m.text));
            droppedTotal = queue.dropped; ytState = "YT LIVE";
            return r.pollMs;
        } catch (Exception e) { ytState = "YT ERROR"; ytChatId = null; return 30000; }
    }

    private boolean current(String config, long revision) {
        SharedPreferences p = getSharedPreferences("ui", MODE_PRIVATE);
        return live == this && ytRevision == revision && config.equals(p.getString("stream_yt_source", "api") + "\n"
            + p.getString("stream_yt_url", "").trim() + "\n" + p.getString("stream_yt_key_enc", ""));
    }

    private long localStep(SharedPreferences sp, String url, String config, long revision) {
        long until = sp.getLong("stream_local_retry_at", 0);
        if (until > System.currentTimeMillis()) {
            ytState = "YT LOCAL RATE LIMIT, WAIT"; return Math.max(5000, until - System.currentTimeMillis());
        }
        String halted = sp.getString("stream_local_halt", "");
        if (!halted.isEmpty() && url.equals(sp.getString("stream_local_halt_url", ""))) {
            ytState = localStatus(halted); return 5000;
        }
        try {
            if (localYt == null) {
                ytState = "YT LOCAL CONNECTING";
                localYt = new YtLocal(localTransport); localYt.open(url);
            }
            if (!current(config, revision)) return 1000;
            YtLocal.Batch b = localYt.next();
            if (!current(config, revision)) return 1000;
            for (Yt.Msg m : b.messages) queue.add(Ws.scriptChat(m.author, m.text));
            droppedTotal = queue.dropped;
            if (b.ended) { haltLocal(sp, url, YtLocal.State.END, 0); return 5000; }
            ytState = "YT LOCAL LIVE";
            return b.waitMs;
        } catch (Exception e) {
            if (!current(config, revision)) return 1000;
            YtLocal.State reason = e instanceof YtLocal.Failure ? ((YtLocal.Failure)e).state : YtLocal.State.NETWORK_ERROR;
            long wait = e instanceof YtLocal.Failure ? ((YtLocal.Failure)e).retryAfterMs : 0;
            haltLocal(sp, url, reason, wait); return 5000;
        }
    }

    private void haltLocal(SharedPreferences sp, String url, YtLocal.State reason, long retry) {
        sp.edit().putString("stream_local_halt", reason.name()).putString("stream_local_halt_url", url)
            .putLong("stream_local_retry_at", retry > 0 ? System.currentTimeMillis() + retry : 0).apply();
        localYt = null; ytState = localStatus(reason.name());
    }
    static String localStatus(String state) {
        if ("LOGIN_REQUIRED".equals(state)) return "YT LOCAL LOGIN REQUIRED, STOPPED";
        if ("CONSENT_REQUIRED".equals(state)) return "YT LOCAL CONSENT REQUIRED, STOPPED";
        if ("RATE_LIMITED".equals(state)) return "YT LOCAL RATE LIMIT, STOPPED";
        if ("FORBIDDEN".equals(state)) return "YT LOCAL ACCESS DENIED, STOPPED";
        if ("END".equals(state)) return "YT LOCAL CHAT ENDED";
        if ("NOT_LIVE".equals(state)) return "YT LOCAL NOT LIVE";
        if ("CHAT_UNAVAILABLE".equals(state)) return "YT LOCAL CHAT UNAVAILABLE";
        if ("NETWORK_ERROR".equals(state)) return "YT LOCAL NETWORK ERROR, STOPPED";
        return "YT LOCAL RESPONSE ERROR, STOPPED";
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
