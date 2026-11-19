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

/** Bridge-test increment: local loopback WebSocket server (Minecraft connects via /connect),
 *  a bounded chat queue that sends tellraw batches, and a small floating control panel.
 *  YouTube feed is NOT in this increment; TEST sends a local sample line.
 *  Binds 127.0.0.1 only. Chat text is JSON-encoded data, never a command. */
public class StreamChatService extends Service {
    static volatile String state = "OFF";       // OFF, WAITING, CONNECTED
    static volatile int port;
    static volatile boolean chatOn = true;
    static volatile int sent, droppedTotal;

    private static final int MAX_INFLIGHT = 20;   // client errors near ~100 outstanding commands; stay far below
    private static final long INFLIGHT_TTL = 10000;
    private static final int QUEUE_CAP = 50;
    private static final int BATCH_LINES = 3, BATCH_CHARS = 4000, TICK_MS = 800;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Ws.ChatQueue queue = new Ws.ChatQueue(QUEUE_CAP);
    private final Map<String, Long> inflight = new HashMap<>();
    private ServerSocket server;
    private volatile Socket client;
    private volatile OutputStream clientOut;
    private Thread acceptThread, sendThread;
    private WindowManager wm;
    private View panel;
    private TextView statusView, portView;
    private TextView toggleBtn;

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
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        startForeground(3, notification());
        live = this;
        openServer();
        addPanel();
        startSender();
    }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        if (i != null && "stop".equals(i.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (i != null && "panel".equals(i.getAction()) && panel == null) addPanel();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        live = null;
        state = "OFF";
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        closeClient();
        removePanel();
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

    /** Inbound protocol JSON: only used to release inflight slots. Never parsed into actions. */
    private void noteInbound(byte[] data) {
        try {
            String j = new String(data, "UTF-8");
            if (!j.contains("commandResponse")) return;
            String marker = "\"requestId\":\"";
            int i = j.indexOf(marker);
            if (i < 0) return;
            int e = j.indexOf('"', i + marker.length());
            if (e < 0) return;
            synchronized (inflight) { inflight.remove(j.substring(i + marker.length(), e)); }
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

    // ---------- floating mini control panel ----------

    private void addPanel() {
        if (panel != null) return;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.panel(Ui.BG2));
        card.setPadding(Ui.dp(10), Ui.dp(6), Ui.dp(10), Ui.dp(10));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("STREAM CHAT", 13, Ui.GREEN);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView hide = text("X", 13, Ui.MUTE);
        hide.setGravity(Gravity.CENTER);
        hide.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { removePanel(); } });
        head.addView(hide, new LinearLayout.LayoutParams(Ui.dp(30), Ui.dp(26)));
        card.addView(head);

        statusView = text("OFF", 12, Ui.MUTE);
        statusView.setTypeface(null);
        card.addView(statusView, wrap(4));
        portView = text("", 11, Ui.MUTE);
        portView.setTypeface(null);
        card.addView(portView, wrap(2));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        toggleBtn = Ui.button(this, "CHAT ON", Ui.CARD, Ui.INK);
        toggleBtn.setTextSize(11);
        toggleBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            chatOn = !chatOn;
            toggleBtn.setText(Ui.fix(chatOn ? "CHAT ON" : "CHAT OFF"));
        }});
        row.addView(toggleBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView test = Ui.button(this, "TEST", Ui.GREEN, 0xFF0C1A0E);
        test.setTextSize(11);
        test.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { postTest(); } });
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.leftMargin = Ui.dp(6);
        row.addView(test, tl);
        card.addView(row, wrap(8));

        LinearLayout row2 = new LinearLayout(this);
        row2.setGravity(Gravity.CENTER);
        final TextView modeBtn = Ui.button(this, "MODE: " + mode().toUpperCase(java.util.Locale.US), Ui.CARD, Ui.INK);
        modeBtn.setTextSize(11);
        modeBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            String[] order = {"both", "bubble", "panel", "off"};
            String cur = mode();
            int i = 0;
            for (int k = 0; k < order.length; k++) if (order[k].equals(cur)) i = k;
            String next = order[(i + 1) % order.length];
            getSharedPreferences("ui", MODE_PRIVATE).edit().putString("stream_mode", next).apply();
            postControl(Ws.scriptMode(next));
            modeBtn.setText(Ui.fix("MODE: " + next.toUpperCase(java.util.Locale.US)));
        }});
        row2.addView(modeBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView clear = Ui.button(this, "CLEAR", Ui.CARD, Ui.INK);
        clear.setTextSize(11);
        clear.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { postControl(Ws.SCRIPT_CLEAR); } });
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cl.leftMargin = Ui.dp(6);
        row2.addView(clear, cl);
        card.addView(row2, wrap(6));

        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                Ui.dp(210), WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = Ui.dp(12); lp.y = Ui.dp(120);
        head.setOnTouchListener(new View.OnTouchListener() {
            float dx, dy;
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) { dx = e.getRawX() - lp.x; dy = e.getRawY() - lp.y; return true; }
                if (e.getAction() == MotionEvent.ACTION_MOVE) {
                    lp.x = (int) (e.getRawX() - dx); lp.y = (int) (e.getRawY() - dy);
                    try { wm.updateViewLayout(panel, lp); } catch (Exception ignored) {}
                    return true;
                }
                return false;
            }
        });
        wm.addView(card, lp);
        panel = card;
        h.post(statusTick);
    }

    private void removePanel() {
        if (panel != null) {
            try { wm.removeView(panel); } catch (Exception ignored) {}
            panel = null;
        }
    }

    private final Runnable statusTick = new Runnable() { public void run() {
        if (panel == null) return;
        String st = state;
        statusView.setText(Ui.fix(st + (chatOn ? "" : "  (chat paused)") + "  sent " + sent + (droppedTotal > 0 ? "  dropped " + droppedTotal : "")));
        statusView.setTextColor(st.equals("CONNECTED") ? Ui.GREEN : st.equals("WAITING") ? Ui.GOLD : Ui.RED);
        portView.setText(Ui.fix(st.equals("OFF") ? "server stopped" : "in Minecraft: /connect 127.0.0.1:" + port));
        h.postDelayed(this, 500);
    }};

    private String mode() {
        return getSharedPreferences("ui", MODE_PRIVATE).getString("stream_mode", "both");
    }

    private TextView text(String t, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(Ui.fix(t));
        v.setTypeface(Ui.PX);
        v.setTextSize(sp);
        v.setTextColor(color);
        return v;
    }

    private LinearLayout.LayoutParams wrap(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = Ui.dp(top);
        return p;
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
