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

    static void init(Context c) { dir = new File(c.getCacheDir(), "img"); dir.mkdirs(); }

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

    static void load(final ImageView v, final String url, final boolean icon) {
        v.setTag(url);
        Bitmap m = MEM.get(url);
        if (m != null) { set(v, m, icon); return; }
        POOL.execute(new Runnable() {
            public void run() {
                if (!url.equals(v.getTag())) return;   // row was recycled or scrolled away before we started
                Bitmap have = MEM.get(url);
                final Bitmap b = have != null ? have : fetch(url);
                if (b == null) return;
                MEM.put(url, b);
                MAIN.post(new Runnable() { public void run() { if (url.equals(v.getTag())) set(v, b, icon); } });
            }
        });
    }

    /** Warms the disk cache for the first rows so they appear instantly. */
    static void prefetch(final java.util.List<Addon> list, int n) {
        for (int k = Math.min(n, list.size()) - 1; k >= 0; k--) {
            final String u = list.get(k).banner;
            if (u == null || MEM.get(u) != null) continue;
            POOL.execute(new Runnable() { public void run() { if (MEM.get(u) == null) { Bitmap b = fetch(u); if (b != null) MEM.put(u, b); } } });
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

    private static Bitmap fetch(String url) {
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
                for (String u : urls) {
                    try {
                        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                        c.setConnectTimeout(4000);
                        c.setReadTimeout(6000);
                        c.setRequestProperty("User-Agent", "67Launcher/1.8");
                        if (c.getResponseCode() != 200) continue;
                        data = Api.readAll(c.getInputStream(), 6 << 20);
                        break;
                    } catch (Exception ignored) {}
                }
                if (data == null) return null;
                FileOutputStream fo = new FileOutputStream(f);
                fo.write(data);
                fo.close();
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int s = 1;
            while (o.outWidth / (s * 2) >= 480) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            return BitmapFactory.decodeFile(f.getPath(), o2);
        } catch (Exception e) { return null; }
    }
}
