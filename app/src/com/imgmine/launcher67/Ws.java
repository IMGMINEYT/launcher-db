package com.imgmine.launcher67;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.JSONObject;

/** RFC 6455 server transport + Bedrock websocket protocol helpers for the stream-chat bridge.
 *  Platform-neutral on purpose (no android.* imports) so the harness tests it on a plain JVM;
 *  StreamChatService owns the Android lifecycle. Protocol shape: https://minecraft.wiki/w/Commands/wsserver */
final class Ws {
    static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    static final class Frame {
        boolean fin; int op; byte[] data;
        Frame(boolean fin, int op, byte[] data) { this.fin = fin; this.op = op; this.data = data; }
    }

    /** RFC 6455 accept key for the handshake. */
    static String acceptKey(String clientKey) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] dig = md.digest((clientKey.trim() + GUID).getBytes("UTF-8"));
            return b64(dig);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** Minimal dependency-free Base64 (android.util.Base64 is device-only; java.util.Base64 needs API 26; minSdk is 21). */
    static String b64(byte[] d) {
        final char[] T = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
        StringBuilder s = new StringBuilder((d.length + 2) / 3 * 4);
        for (int i = 0; i < d.length; i += 3) {
            int b0 = d[i] & 0xFF, b1 = i + 1 < d.length ? d[i + 1] & 0xFF : 0, b2 = i + 2 < d.length ? d[i + 2] & 0xFF : 0;
            s.append(T[b0 >>> 2]);
            s.append(T[((b0 & 3) << 4) | (b1 >>> 4)]);
            s.append(i + 1 < d.length ? T[((b1 & 15) << 2) | (b2 >>> 6)] : '=');
            s.append(i + 2 < d.length ? T[b2 & 63] : '=');
        }
        return s.toString();
    }

    /** Read one HTTP head (request line + headers through the empty line), bounded. */
    static String readHttpHead(InputStream in, int max) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        int matched = 0, b;
        final int[] end = {'\r', '\n', '\r', '\n'};
        while (bo.size() < max && (b = in.read()) >= 0) {
            bo.write(b);
            matched = b == end[matched] ? matched + 1 : (b == '\r' ? 1 : 0);
            if (matched == 4) return new String(bo.toByteArray(), "UTF-8");
        }
        throw new IOException("bad http head");
    }

    static String header(String head, String name) {
        String[] lines = head.split("\r\n");
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c > 0 && lines[i].substring(0, c).trim().equalsIgnoreCase(name)) return lines[i].substring(c + 1).trim();
        }
        return null;
    }

    /** Read one frame. Client frames must be masked (RFC 6455 section 5.1). Returns null on clean close. */
    static Frame read(InputStream in) throws IOException {
        int b0 = in.read();
        if (b0 < 0) return null;
        int b1 = in.read();
        if (b1 < 0) throw new EOFException("truncated frame");
        boolean fin = (b0 & 0x80) != 0;
        int op = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;
        if (len == 126) { len = (read1(in) << 8) | read1(in); }
        else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) len = (len << 8) | read1(in);
        }
        if (len > 1 << 20) throw new IOException("frame too big: " + len);
        byte[] mask = null;
        if (masked) { mask = new byte[4]; readFull(in, mask); }
        byte[] data = new byte[(int) len];
        readFull(in, data);
        if (mask != null) for (int i = 0; i < data.length; i++) data[i] ^= mask[i & 3];
        return new Frame(fin, op, data);
    }

    private static int read1(InputStream in) throws IOException {
        int v = in.read();
        if (v < 0) throw new EOFException("truncated frame");
        return v;
    }

    private static void readFull(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new EOFException("truncated frame payload");
            off += n;
        }
    }

    /** Write an unmasked server frame. */
    static synchronized void write(OutputStream out, int op, byte[] data) throws IOException {
        out.write(0x80 | op);
        int len = data.length;
        if (len < 126) out.write(len);
        else if (len <= 0xFFFF) { out.write(126); out.write(len >>> 8); out.write(len); }
        else {
            out.write(127);
            long L = len;   // widen before shifting: int shifts are masked mod 32 in Java
            for (int i = 7; i >= 0; i--) out.write((int) ((L >>> (8 * i)) & 0xFF));
        }
        out.write(data);
        out.flush();
    }

    /** Build a Bedrock commandRequest message via a JSON library (contract rule 2: never string concat). */
    static byte[] commandRequest(String cmd, String requestId) {
        try {
            JSONObject header = new JSONObject()
                    .put("version", 1).put("requestId", requestId)
                    .put("messageType", "commandRequest").put("messagePurpose", "commandRequest");
            JSONObject body = new JSONObject()
                    .put("origin", new JSONObject().put("type", "player"))
                    .put("commandLine", cmd).put("version", 1);
            return new JSONObject().put("header", header).put("body", body).toString().getBytes("UTF-8");
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    static String newId() { return UUID.randomUUID().toString(); }

    /** One chat message as a scriptevent commandLine per the addon contract.
     *  Field caps match the addon's own truncation (name 24, text 120); payload stays far under 2048. */
    static String scriptChat(String name, String text) {
        try {
            JSONObject p = new JSONObject()
                    .put("n", stripCtl(name, 24))
                    .put("t", stripCtl(text, 120))
                    .put("c", "c")
                    .put("p", "YT");
            return "scriptevent tysc:chat " + p.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** Control commands are fixed strings with no untrusted content. */
    static String scriptMode(String mode) { return "scriptevent tysc:mode " + mode; }
    /** Contract v1.2: bubble lifetime in seconds, clamped 1-10, persisted by the addon. */
    static String scriptLifetime(int sec) { return "scriptevent tysc:lifetime " + Math.max(1, Math.min(10, sec)); }
    static final String SCRIPT_TEST = "scriptevent tysc:test";
    static final String SCRIPT_CLEAR = "scriptevent tysc:clear";
    static final String SCRIPT_PIN = "scriptevent tysc:pin";
    static final String SCRIPT_UNPIN = "scriptevent tysc:unpin";

    /** Strip control/color injection, then cap without splitting surrogate pairs or common
     * emoji clusters (ZWJ, modifiers, variation selectors, regional flags and keycaps).
     * The limit remains UTF-16 units for the existing payload budget. */
    static String stripCtl(String s, int max) {
        if(s==null||max<=0)return "";
        StringBuilder clean=new StringBuilder();
        for(int i=0;i<s.length();){
            int cp=s.codePointAt(i);i+=Character.charCount(cp);
            if(cp<0x20||cp==0x7f||cp==0xa7||(cp>=0xd800&&cp<=0xdfff))continue;
            clean.appendCodePoint(cp);
        }
        String text=clean.toString();int end=0;
        while(end<text.length()){
            int next=clusterEnd(text,end);
            if(next>max)break;
            end=next;
        }
        return text.substring(0,end);
    }
    private static boolean extend(int cp){
        int t=Character.getType(cp);
        return t==Character.NON_SPACING_MARK||t==Character.COMBINING_SPACING_MARK||t==Character.ENCLOSING_MARK
            ||(cp>=0xfe00&&cp<=0xfe0f)||(cp>=0xe0100&&cp<=0xe01ef)
            ||(cp>=0x1f3fb&&cp<=0x1f3ff)||(cp>=0xe0020&&cp<=0xe007f);
    }
    private static int clusterEnd(String s,int at){
        int first=s.codePointAt(at),i=at+Character.charCount(first);
        if(first>=0x1f1e6&&first<=0x1f1ff&&i<s.length()){
            int cp=s.codePointAt(i);if(cp>=0x1f1e6&&cp<=0x1f1ff)i+=Character.charCount(cp);
        }
        while(i<s.length()){
            int cp=s.codePointAt(i);
            if(extend(cp)){i+=Character.charCount(cp);continue;}
            if(cp==0x200d){
                int j=i+1;if(j>=s.length())return j;
                i=j+Character.charCount(s.codePointAt(j));continue;
            }
            break;
        }
        return i;
    }

    /** Bounded drop-oldest queue with batching, sized for the client's ~100-outstanding-command limit. */
    static final class ChatQueue {
        private final ArrayDeque<String> q = new ArrayDeque<>();
        private final int cap;
        int dropped;
        ChatQueue(int cap) { this.cap = cap; }
        synchronized void add(String line) {
            while (q.size() >= cap) { q.pollFirst(); dropped++; }
            q.addLast(line);
        }
        /** Take up to maxLines lines whose combined length fits maxChars. */
        synchronized List<String> batch(int maxLines, int maxChars) {
            List<String> out = new ArrayList<>();
            int chars = 0;
            while (!q.isEmpty() && out.size() < maxLines) {
                String s = q.peekFirst();
                if (chars + s.length() > maxChars && !out.isEmpty()) break;
                chars += s.length();
                out.add(q.pollFirst());
            }
            return out;
        }
        synchronized int size() { return q.size(); }
    }
}
