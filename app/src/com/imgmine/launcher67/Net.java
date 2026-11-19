package com.imgmine.launcher67;
import android.content.Context;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Shared bounded read-through cache and per-origin request budgets. HttpURLConnection's
 * process-wide keep-alive pool reuses sockets after fully consuming/closing response streams. */
final class Net {
    private static File dir;
    private static final ConcurrentHashMap<String,ExecutorService> WORKERS=new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String,Object> LOCKS=new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String,Long> LAST=new ConcurrentHashMap<>();
    /** Per-origin 429 cooldown: origin -> epoch ms until which requests are refused without connecting. Honors Retry-After. */
    private static final ConcurrentHashMap<String,Long> COOLDOWN=new ConcurrentHashMap<>();
    private static Object lock(String key){Object o=LOCKS.get(key);if(o!=null)return o;Object n=new Object();o=LOCKS.putIfAbsent(key,n);return o==null?n:o;}
    static void init(Context c){dir=new File(c.getCacheDir(),"http");dir.mkdirs();System.setProperty("http.keepAlive","true");}
    static ExecutorService worker(String source){ExecutorService ex=WORKERS.get(source);if(ex!=null)return ex;ExecutorService n=Executors.newFixedThreadPool(2);ex=WORKERS.putIfAbsent(source,n);if(ex!=null){n.shutdown();return ex;}return n;}
    private static String key(String s)throws Exception{byte[] h=MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"));StringBuilder b=new StringBuilder();for(byte x:h)b.append(String.format("%02x",x));return b.toString();}
    interface Connections{HttpURLConnection connect(URL u)throws IOException;}
    static Connections connections=u->(HttpURLConnection)u.openConnection();
    static byte[] get(String url,int max,long ttl,String ua)throws Exception{
        URL target=new URL(url);if(!target.getProtocol().equals("https"))throw new IOException("HTTPS required");
        String origin=target.getHost();Object lock=lock(origin);
        // Cache synchronization per URL, not per origin: unrelated origins/requests never wait for a slow response.
        synchronized(lock(url)){
            File body=dir==null?null:new File(dir,key(url)+".bin"),meta=dir==null?null:new File(dir,key(url)+".json");
            JSONObject m=new JSONObject();if(meta!=null&&meta.isFile())try(InputStream in=new FileInputStream(meta)){m=new JSONObject(new String(Api.readAll(in,65536),"UTF-8"));}catch(Exception ignored){}
            if(body!=null&&body.isFile()&&System.currentTimeMillis()-m.optLong("at")<ttl){try(InputStream in=new FileInputStream(body)){return Api.readAll(in,max);}}
            Long until=COOLDOWN.get(origin);
            if(until!=null){
                if(System.currentTimeMillis()<until.longValue()){
                    if(body!=null&&body.isFile()){try(InputStream in=new FileInputStream(body)){return Api.readAll(in,max);}}
                    throw new IOException("HTTP 429 (cooldown)");
                }
                COOLDOWN.remove(origin,until);
            }
            synchronized(lock){long delay=1000-(System.currentTimeMillis()-(LAST.containsKey(origin)?LAST.get(origin):0L));if(delay>0)Thread.sleep(delay);LAST.put(origin,System.currentTimeMillis());}
            String u=url;
            for(int r=0;r<6;r++){
                URL x=new URL(u);if(!x.getProtocol().equals("https"))throw new IOException("HTTPS required");
                HttpURLConnection c=connections.connect(x);c.setInstanceFollowRedirects(false);c.setConnectTimeout(5000);c.setReadTimeout(7000);c.setRequestProperty("User-Agent",ua);
                if(body!=null&&body.isFile()){String et=m.optString("etag"),lm=m.optString("modified");if(!et.isEmpty())c.setRequestProperty("If-None-Match",et);if(!lm.isEmpty())c.setRequestProperty("If-Modified-Since",lm);}
                int code=c.getResponseCode();
                if(code==304&&body!=null&&body.isFile()){m.put("at",System.currentTimeMillis());write(meta,m.toString().getBytes("UTF-8"));c.disconnect();try(InputStream in=new FileInputStream(body)){return Api.readAll(in,max);}}
                if(code>=300&&code<400){String loc=c.getHeaderField("Location");c.disconnect();if(loc==null)throw new IOException("No redirect");u=new URL(x,loc).toString();continue;}
                if(code==429){
                    long retryAfter=retryAfterMs(c);c.disconnect();
                    if(retryAfter>0)COOLDOWN.put(origin,System.currentTimeMillis()+retryAfter);
                    if(body!=null&&body.isFile()){try(InputStream in=new FileInputStream(body)){return Api.readAll(in,max);}}
                    throw new IOException("HTTP 429");
                }
                if(code!=200){c.disconnect();throw new IOException("HTTP "+code);}
                byte[] data;try(InputStream in=c.getInputStream()){data=Api.readAll(in,max);}
                try{if(body!=null){write(body,data);m.put("at",System.currentTimeMillis());m.put("etag",c.getHeaderField("ETag"));m.put("modified",c.getHeaderField("Last-Modified"));write(meta,m.toString().getBytes("UTF-8"));trim();}}catch(IOException cacheFailure){/* network data is still usable */}
                return data;
            }throw new IOException("Too many redirects");
        }
    }
    private static long retryAfterMs(HttpURLConnection c){
        String v=c.getHeaderField("Retry-After");
        if(v==null)return 0;
        try{long s=Long.parseLong(v.trim());return s<0?0:s*1000L;}catch(NumberFormatException ignored){}
        long at=c.getHeaderFieldDate("Retry-After",-1);
        return at<0?0:Math.max(0,at-System.currentTimeMillis());
    }
    static long contentLength(java.net.URLConnection c){try{return Long.parseLong(c.getHeaderField("Content-Length"));}catch(Exception e){return -1;}}
    private static long lastTrim;
    private static synchronized void trim(){
        if(dir==null||System.currentTimeMillis()-lastTrim<60000)return;lastTrim=System.currentTimeMillis();
        File[] fs=dir.listFiles((d,n)->n.endsWith(".bin"));if(fs==null)return;
        long bytes=0;for(File f:fs)bytes+=f.length();if(bytes<=32L*1024*1024)return;
        java.util.Arrays.sort(fs,(a,b)->Long.compare(a.lastModified(),b.lastModified()));
        for(File f:fs){if(bytes<=24L*1024*1024)break;long size=f.length();if(f.delete()){bytes-=size;new File(dir,f.getName().replace(".bin",".json")).delete();}}
    }
    private static void write(File f,byte[] data)throws IOException{File t=new File(f.getPath()+".tmp");try(FileOutputStream out=new FileOutputStream(t)){out.write(data);}if(!t.renameTo(f))throw new IOException("Cache write failed");}
}
