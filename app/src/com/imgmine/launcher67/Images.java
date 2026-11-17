package com.imgmine.launcher67;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.*;

final class Images {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    // newest request first: rows the user is looking at now load before rows already scrolled past
    private static final ThreadPoolExecutor POOL = new ThreadPoolExecutor(6, 6, 30, TimeUnit.SECONDS, new LinkedBlockingDeque<Runnable>() {
        @Override public boolean offer(Runnable r) { return offerFirst(r); }
        @Override public boolean add(Runnable r) { addFirst(r); return true; }
    });
    static { POOL.allowCoreThreadTimeOut(true); }
    private static final LruCache<String, Bitmap> MEM = new LruCache<String, Bitmap>(
            (int) Math.min(24L << 20, Runtime.getRuntime().maxMemory() / 10)) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private static final LruCache<String, Drawable> ART = new LruCache<>(60);
    static File dir;

    static void init(Context c) { Net.init(c); dir = new File(c.getCacheDir(), "img"); dir.mkdirs(); }

    /** Generated pixel picture, used while a banner loads or when there is none. */
    static Drawable art(String name, android.content.res.Resources res) {
        Drawable d = ART.get(name);
        if (d != null) return d;
        int h = name.hashCode();
        java.util.Random r = new java.util.Random(h);
        int[][] pal = {{0xFF2F8A39, 0xFF5FD068, 0xFF3F9D4A, 0xFF1F5E28}, {0xFF6B4A2B, 0xFF8A6238, 0xFF54391F, 0xFF3F2A16},
                {0xFF3A4A66, 0xFF4F6690, 0xFF2C3A52, 0xFF202B3E}, {0xFF6B2F2F, 0xFF8F4040, 0xFF4F2222, 0xFF3A1818}};
        int[] p = pal[(h & 0x7fffffff) % pal.length];
        Bitmap b = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < 18; y++) for (int x = 0; x < 32; x++) b.setPixel(x, y, p[r.nextInt(p.length)]);
        BitmapDrawable bd = new BitmapDrawable(res, b);
        bd.setFilterBitmap(false);
        ART.put(name, bd);
        return bd;
    }

    interface State {
        void progress(int pct);   // 0-100 when the server sent a length, -1 while downloading unknown size
        void loaded();
        void failed();            // fetch/decode failed: an image may exist, offer retry
    }

    static void load(final ImageView v, final String url, final boolean icon) { load(v, url, icon, null); }

    static void load(final ImageView v, final String url, final boolean icon, final State st) {
        v.setTag(url);
        if(url==null || !url.startsWith("https://")){if(st!=null)st.failed();return;}
        Bitmap m = MEM.get(url);
        if (m != null) { set(v, m, icon); if (st != null) st.loaded(); return; }
        POOL.execute(new Runnable() {
            public void run() {
                if (!url.equals(v.getTag())) return;   // row was recycled or scrolled away before we started
                Bitmap have = MEM.get(url);
                final Bitmap b = have != null ? have : fetch(url, v, st);
                if (b == null) {
                    if (st != null && url.equals(v.getTag())) MAIN.post(new Runnable() { public void run() { if (url.equals(v.getTag())) st.failed(); } });
                    return;
                }
                MEM.put(url, b);
                MAIN.post(new Runnable() { public void run() {
                    if (url.equals(v.getTag())) { set(v, b, icon); if (st != null) st.loaded(); }
                } });
            }
        });
    }

    /** Warms the disk cache for the first rows so they appear instantly. */
    static void prefetch(final java.util.List<Addon> list, int n) {
        for (int k = Math.min(n, list.size()) - 1; k >= 0; k--) {
            final String u = list.get(k).banner;
            if (u == null || MEM.get(u) != null) continue;
            POOL.execute(new Runnable() { public void run() { if (MEM.get(u) == null) { Bitmap b = fetch(u, null, null); if (b != null) MEM.put(u, b); } } });
        }
    }

    private static void set(ImageView v, Bitmap b, boolean icon) {
        BitmapDrawable d = new BitmapDrawable(v.getResources(), b);
        if (icon || b.getWidth() <= 128) d.setFilterBitmap(false);
        v.setScaleType(icon ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
        v.setImageDrawable(d);
    }

    private static String key(String u) {
        try {
            byte[] h = MessageDigest.getInstance("MD5").digest(u.getBytes("UTF-8"));
            StringBuilder s = new StringBuilder();
            for (byte x : h) s.append(String.format("%02x", x));
            return s.toString();
        } catch (Exception e) { return "x" + u.hashCode(); }
    }

    private static final ConcurrentHashMap<String,FutureTask<Bitmap>> INFLIGHT=new ConcurrentHashMap<>();
    private static Bitmap fetch(String url, ImageView v, State st) {
        FutureTask<Bitmap> task=new FutureTask<>(()->fetchOne(url,v,st));
        FutureTask<Bitmap> existing=INFLIGHT.putIfAbsent(url,task);
        if(existing!=null)task=existing;
        else task.run();
        try{return task.get();}catch(Exception e){return null;}
        finally{INFLIGHT.remove(url,task);}
    }

    private static long lastTrim;
    private static synchronized void trimDisk(){
        if(dir==null||System.currentTimeMillis()-lastTrim<60000)return;lastTrim=System.currentTimeMillis();
        File[] fs=dir.listFiles();if(fs==null)return;long size=0;for(File f:fs)size+=f.length();if(size<=48L*1024*1024)return;
        java.util.Arrays.sort(fs,(a,b)->Long.compare(a.lastModified(),b.lastModified()));
        for(File f:fs){if(size<=32L*1024*1024)break;long n=f.length();if(f.delete())size-=n;}
    }

    private static Bitmap fetchOne(String url, final ImageView v, final State st) {
        try {
            File f = new File(dir, key(url));
            if (!f.exists() || f.length() == 0) {
                java.util.List<String> urls = new java.util.ArrayList<>();
                urls.add(url);
                if (url.startsWith(Api.RAW)) {
                    String rest = url.substring(Api.RAW.length());
                    urls.add("https://github.com/IMGMINEYT/launcher-db/raw/main/" + rest);
                    urls.add(Api.CDN + rest);
                }
                byte[] data = null;
                for (final String u : urls) {
                    HttpURLConnection c = null;
                    try {
                        c = (HttpURLConnection) new URL(u).openConnection();
                        c.setConnectTimeout(4000);
                        c.setReadTimeout(6000);
                        c.setRequestProperty("User-Agent", "67Launcher/2.6");
                        if (c.getResponseCode() != 200) continue;
                        long total = Net.contentLength(c);
                        InputStream in = c.getInputStream();
                        ByteArrayOutputStream bo = new ByteArrayOutputStream(total > 0 && total < (6 << 20) ? (int) total : 65536);
                        byte[] buf = new byte[32768];
                        long got = 0;
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            bo.write(buf, 0, n);
                            got += n;
                            if (got > (6 << 20)) throw new IOException("Image too large");
                            if (st != null) {
                                final int pct = total > 0 ? (int) (got * 100 / total) : -1;
                                MAIN.post(new Runnable() { public void run() { if (v != null && url.equals(v.getTag())) st.progress(pct); } });
                            }
                        }
                        in.close();
                        if(total>0 && got!=total)throw new IOException("Incomplete image");
                        data = bo.toByteArray();
                        break;
                    } catch (Exception ignored) {
                    } finally { if (c != null) c.disconnect(); }
                }
                if (data == null) return null;
                FileOutputStream fo = new FileOutputStream(f);
                fo.write(data);
                fo.close();trimDisk();
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int s = 1;
            if(o.outWidth<=0||o.outHeight<=0){f.delete();return null;}
            while (Math.max(o.outWidth,o.outHeight) / s > 1024) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            Bitmap decoded=BitmapFactory.decodeFile(f.getPath(), o2);
            if(decoded==null)f.delete();
            return decoded;
        } catch (Exception e) { return null; }
    }
}
