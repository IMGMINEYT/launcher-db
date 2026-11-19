package com.imgmine.launcher67;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Download engine: concurrent queue, pause/resume (HTTP Range with honest restart fallback),
 *  cancel/retry, measured speed/ETA, persisted history. Reports real bytes only - never fabricated. */
final class Downloader {
    static final String MC = "com.mojang.minecraftpe";

    static final int QUEUED = 0, RUNNING = 1, PAUSED = 2, DONE = 3, FAILED = 4, CANCELED = 5;

    static final class Task {
        String key="", url="", name="", label="", validator="", error="";
        volatile long got, total=-1, updatedAt;
        volatile double bps;
        volatile int state=QUEUED;
        volatile boolean stop;
        volatile HttpURLConnection connection;
        Listener listener;
        int pct() { return total>0 ? (int)Math.min(100,got*100/total) : -1; }
        long etaSec() { return total>0 && bps>1 ? Math.max(0,(long)((total-got)/bps)) : -1; }
    }
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL=Executors.newFixedThreadPool(3);
    private static final Map<String,Task> TASKS=new LinkedHashMap<>();
    private static final Set<String> ACTIVE=new HashSet<>();
    private static boolean loaded;
    private static volatile Watch watch;
    interface Listener { void progress(int pct); void done(boolean ok); }
    interface Watch { void changed(); }
    static void setWatch(Watch w) { watch=w; }
    static boolean safeName(String n) { return n!=null && n.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,180}") && !n.contains("..") && !n.endsWith(".json"); }
    static boolean valid(String u) {
        if(u==null)return false;
        // VIP gate file endpoints carry opaque ids, not extensions: trust by origin, not suffix.
        if(Vip.ENABLED&&Api.sameOrigin(Vip.GATE,u))return u.startsWith("https://")||Api.loopbackGate(u);
        if(!u.startsWith("https://"))return false;
        String path=u.split("[?]",2)[0].toLowerCase(Locale.ROOT);
        return path.endsWith(".mcaddon") || path.endsWith(".mcpack") || path.endsWith(".mcworld") || path.endsWith(".mctemplate") || path.contains("/releases/download/");
    }
    static String fileName(String url) { String n=Uri.parse(url).getLastPathSegment(); return n==null?"addon.mcaddon":n.replaceAll("[^A-Za-z0-9._-]","_"); }
    static boolean have(Context c,Addon a) { File f=new File(new File(c.getFilesDir(),"dl"),a.safeFile()); return f.isFile() && f.length()>0 && (a.size<=0 || f.length()==a.size); }
    static synchronized List<Task> tasks(Context c) { load(c);
        boolean changed=false;for(Task t:TASKS.values())if(t.state==DONE&&!new File(new File(c.getFilesDir(),"dl"),t.name).isFile()){t.state=FAILED;t.error="Downloaded file is missing";changed=true;}if(changed)save(c);
        List<Task> out=new ArrayList<>(TASKS.values()); Collections.reverse(out); return out; }
    static synchronized Task task(Context c,String k) { load(c);return TASKS.get(k); }
    static synchronized boolean busy() { for(Task t:TASKS.values()) if(t.state==QUEUED||t.state==RUNNING)return true;return false; }
    static void start(Context c,String u,String n,Listener l) {start(c,u,n,n,l);}
    static synchronized void start(Context c,String u,String n,String label,Listener l) {
        Context app=c.getApplicationContext(); load(app);
        if(!safeName(n)||!valid(u)) {if(l!=null)MAIN.post(()->l.done(false));return;}
        Task t=TASKS.get(n);
        if(t!=null && (t.state==RUNNING||t.state==QUEUED))return;
        if(t==null) {t=new Task();t.key=n;t.name=n;TASKS.put(n,t);}
        t.url=u;t.label=label;t.listener=l;t.error="";
        t.state=QUEUED;t.stop=false;t.updatedAt=System.currentTimeMillis();save(app);enqueue(app,t);
    }
    static synchronized void pause(Context c,String k) { Task t=TASKS.get(k);if(t==null||t.state==DONE||t.state==CANCELED)return;t.state=PAUSED;t.stop=true;abort(t);save(c);ping(); }
    static synchronized void cancel(Context c,String k) { Task t=TASKS.get(k);if(t==null||t.state==DONE)return;t.state=CANCELED;t.stop=true;abort(t);if(!ACTIVE.contains(k))part(c,t).delete();save(c);ping(); }
    private static void abort(Task t) {HttpURLConnection con=t.connection;if(con!=null)con.disconnect();}
    static synchronized void resume(Context c,String k) { Task t=TASKS.get(k);if(t==null||t.state==DONE)return;t.state=QUEUED;t.stop=true;save(c);if(!ACTIVE.contains(k)){t.stop=false;enqueue(c.getApplicationContext(),t);}ping(); }
    static void retry(Context c,String k) {resume(c,k);}
    static synchronized void remove(Context c,String k,boolean deleteFile) {
        Task t=TASKS.get(k);if(t==null||!safeName(k))return;
        if(ACTIVE.contains(k))return; // only inactive manager rows expose delete
        TASKS.remove(k);if(deleteFile){new File(new File(c.getFilesDir(),"dl"),k).delete();part(c,t).delete();}save(c);ping();
    }
    private static File part(Context c,Task t){return new File(new File(c.getFilesDir(),"dl"),t.name+".part");}
    private static synchronized void enqueue(final Context c,final Task t){
        if(!ACTIVE.add(t.key))return;
        try { Intent i=new Intent(c,DownloadService.class);if(android.os.Build.VERSION.SDK_INT>=26)c.startForegroundService(i);else c.startService(i); }catch(Exception e){t.error="Background protection unavailable";}
        POOL.execute(()->{
            boolean ok=false;
            try {
                for(int attempt=0;attempt<3;attempt++) {
                    synchronized(Downloader.class){if(t.state!=QUEUED||t.stop)break;t.state=RUNNING;t.bps=0;save(c);}
                    ok=transfer(c,t);
                    synchronized(Downloader.class){
                        if(t.stop)break;
                        if(ok){t.state=DONE;t.error="";break;}
                        if(attempt==2){t.state=FAILED;break;}
                        t.error="Retry "+(attempt+1)+"/2";t.state=QUEUED;save(c);ping();
                    }
                    try{Thread.sleep(2000L*(attempt+1));}catch(InterruptedException e){break;}
                }
            }finally{
                synchronized(Downloader.class){
                    ACTIVE.remove(t.key);t.bps=0;
                    if(t.state==CANCELED){part(c,t).delete();t.got=0;}
                    if(t.state==QUEUED&&t.stop){t.stop=false;enqueue(c,t);}
                    save(c);ping();
                }
            }
            final boolean result=ok;
            if(!t.stop && (t.state==DONE||t.state==FAILED))MAIN.post(()->{Listener l=t.listener;t.listener=null;if(l!=null)l.done(result);if(result)install(c,t.name);});
        });
    }
    private static boolean transfer(Context c,Task t){
        File tmp=part(c,t);tmp.getParentFile().mkdirs();HttpURLConnection con=null;
        try{
            long at=tmp.isFile()?tmp.length():0;
            // No validator means we cannot prove partial bytes still match the remote object.
            if(at>0&&t.validator.isEmpty())at=0;
            con=openFresh(t,at);int code=con.getResponseCode();
            if(at>0 && (code!=206 || !rangeMatches(con.getHeaderField("Content-Range"),at))){con.disconnect();at=0;con=openFresh(t,0);code=con.getResponseCode();}
            if(t.stop)return false;
            String validator=con.getHeaderField("ETag");if(validator==null||validator.startsWith("W/"))validator=con.getHeaderField("Last-Modified");
            if(validator!=null)t.validator=validator;
            if(code==206&&!rangeMatches(con.getHeaderField("Content-Range"),at))throw new IOException("Invalid range response");
            long length=Net.contentLength(con);t.total=code==206?rangeTotal(con.getHeaderField("Content-Range")):(length>=0?length:-1);t.got=at;
            try(InputStream in=con.getInputStream();FileOutputStream fo=new FileOutputStream(tmp,at>0)){
                byte[] buf=new byte[65536];int n;long tick=System.nanoTime(),lastBytes=at;
                while(!t.stop&&(n=in.read(buf))!=-1){
                    if(t.stop)break;fo.write(buf,0,n);t.got+=n;
                    long now=System.nanoTime();if(now-tick>=500000000L){t.bps=(t.got-lastBytes)*1e9/(now-tick);tick=now;lastBytes=t.got;post(t);}
                }
            }
            if(t.stop)return false;
            if(t.total>=0&&tmp.length()!=t.total)throw new IOException("Incomplete transfer");
            // Refuse HTML/error pages and encrypted Marketplace indicators before exposing an install button.
            try(java.util.zip.ZipFile z=new java.util.zip.ZipFile(tmp)){if(z.size()==0)throw new IOException("Empty pack");}
            catch(IOException bad){tmp.delete();throw new IOException("Invalid pack archive");}
            if(looksPaid(tmp)){tmp.delete();throw new IOException("Encrypted or Marketplace content is not supported");}
            File out=new File(tmp.getParentFile(),t.name);
            if(!tmp.renameTo(out))throw new IOException("Cannot save downloaded pack");
            t.got=out.length();t.total=out.length();post(t);return true;
        }catch(Exception e){if(!t.stop)t.error=e.getMessage()==null?"Download failed":e.getMessage();return false;}
        finally{if(con!=null)con.disconnect();t.connection=null;}
    }
    static boolean rangeMatches(String s,long at){
        if(s==null)return false;try{java.util.regex.Matcher m=java.util.regex.Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matcher(s);
            return m.matches()&&Long.parseLong(m.group(1))==at&&Long.parseLong(m.group(2))>=at&&Long.parseLong(m.group(3))>Long.parseLong(m.group(2));
        }catch(Exception e){return false;}
    }
    static long rangeTotal(String s){try{return Long.parseLong(s.substring(s.lastIndexOf('/')+1));}catch(Exception e){return -1;}}
    interface Connections{HttpURLConnection connect(URL u)throws IOException;}
    static Connections connections=u->(HttpURLConnection)u.openConnection();
    /** Gate signed URLs expire after 10 minutes (contract v3): on a 404 from a gate URL,
     *  refresh it once through the catalog and retry with the fresh URL. Non-gate URLs and
     *  non-404 failures propagate unchanged. */
    private static HttpURLConnection openFresh(Task t,long at)throws IOException{
        try{return open(t,at);}
        catch(IOException e){
            String m=e.getMessage();
            if(m==null||!m.contains("404")||!Vip.ENABLED||!Api.sameOrigin(Vip.GATE,t.url))throw e;
            String fresh=Api.refreshGateUrl(app,t.url);
            if(fresh==null)throw e;
            t.url=fresh;
            return open(t,at);
        }
    }
    private static HttpURLConnection open(Task t,long at)throws IOException{
        String u=t.url;
        for(int i=0;i<6;i++){
            URL url=new URL(u);
            boolean gate=Vip.ENABLED&&Api.sameOrigin(Vip.GATE,u);
            if(!"https".equals(url.getProtocol())&&!(gate&&Api.loopbackGate(u)))throw new IOException("HTTPS required");
            HttpURLConnection con=connections.connect(url);t.connection=con;
            con.setInstanceFollowRedirects(false);con.setConnectTimeout(10000);con.setReadTimeout(15000);
            con.setRequestProperty("User-Agent","67Launcher/2.6");con.setRequestProperty("Accept-Encoding","identity");
            if(gate){String tok=app==null?"":Vip.token(app);if(tok.length()==0)throw new IOException("VIP token missing");con.setRequestProperty("X-VIP-Token",tok);}
            if(at>0){con.setRequestProperty("Range","bytes="+at+"-");con.setRequestProperty("If-Range",t.validator);}
            int code=con.getResponseCode();if(code>=300&&code<400){String loc=con.getHeaderField("Location");con.disconnect();if(loc==null)throw new IOException("Missing redirect");String next=new URL(url,loc).toString();if(gate&&!Api.sameOrigin(Vip.GATE,next))throw new IOException("Untrusted redirect");u=next;continue;}
            if(code!=200&&code!=206&&!(code==416&&at>0)){con.disconnect();throw new IOException("HTTP "+code);}
            return con;
        }throw new IOException("Too many redirects");
    }
    private static void post(Task t){MAIN.post(()->{if(t.listener!=null)t.listener.progress(t.pct());});ping();}
    private static void ping(){Watch w=watch;if(w!=null)MAIN.post(w::changed);}
    private static File histFile(Context c){return new File(c.getFilesDir(),"dl/history.json");}
    private static Context app;
    private static synchronized void load(Context c){
        app=c.getApplicationContext();
        if(loaded)return;loaded=true;File dir=new File(c.getFilesDir(),"dl");dir.mkdirs();
        try(InputStream in=new java.io.FileInputStream(histFile(c))){
            JSONArray a=new JSONArray(new String(Api.readAll(in,4<<20),"UTF-8"));
            for(int i=0;i<a.length();i++){JSONObject j=a.getJSONObject(i);String k=j.optString("key");if(!safeName(k))continue;
                Task t=new Task();t.key=k;t.name=k;t.label=j.optString("label",k);t.url=j.optString("url");t.validator=j.optString("validator");t.total=j.optLong("total",-1);t.state=j.optInt("state",FAILED);t.got=j.optLong("got");
                if(t.state==RUNNING||t.state==QUEUED)t.state=PAUSED;
                File saved=new File(dir,k);if(t.state==DONE&&!saved.isFile()){t.state=FAILED;t.error="Downloaded file is missing";}
                File partial=part(c,t);if(t.state!=DONE)t.got=partial.isFile()?partial.length():0;
                TASKS.put(k,t);
            }
        }catch(Exception ignored){}
        // Upgrade migration: older builds have downloaded files but no history.
        File[] fs=dir.listFiles();if(fs!=null)for(File f:fs){String n=f.getName();if(safeName(n)&&!n.endsWith(".part")&&!TASKS.containsKey(n)&&isPack(n)){
            Task t=new Task();t.key=n;t.name=n;t.label=n;t.state=DONE;t.got=f.length();t.total=f.length();TASKS.put(n,t);
        }}
    }
    static boolean isPack(String n){return n.endsWith(".mcpack")||n.endsWith(".mcaddon")||n.endsWith(".mcworld")||n.endsWith(".mctemplate");}
    private static synchronized void save(Context c){
        try{JSONArray a=new JSONArray();for(Task t:TASKS.values()){JSONObject j=new JSONObject();j.put("key",t.key);j.put("url",t.url);j.put("label",t.label);j.put("total",t.total);j.put("got",t.got);j.put("validator",t.validator);j.put("state",t.state);a.put(j);}
            File f=histFile(c);f.getParentFile().mkdirs();File tmp=new File(f.getPath()+".tmp");try(FileOutputStream fo=new FileOutputStream(tmp)){fo.write(a.toString().getBytes("UTF-8"));fo.getFD().sync();}tmp.renameTo(f);
        }catch(Exception ignored){}
    }
    static synchronized void saveLocal(Context c,String name,String label,byte[] data)throws IOException{
        load(c);if(!safeName(name)||!isPack(name)||ACTIVE.contains(name))throw new IOException("Invalid local pack");
        File out=new File(new File(c.getFilesDir(),"dl"),name),tmp=new File(out.getPath()+".part");
        tmp.getParentFile().mkdirs();
        try(FileOutputStream fo=new FileOutputStream(tmp)){fo.write(data);fo.getFD().sync();}
        if(!tmp.renameTo(out))throw new IOException("Cannot save pack");
        Task t=new Task();t.key=name;t.name=name;t.label=label;t.state=DONE;t.got=data.length;t.total=data.length;TASKS.put(name,t);save(c);ping();
    }
    static long storageUsed(Context c){long b=0;File[] fs=new File(c.getFilesDir(),"dl").listFiles();if(fs!=null)for(File f:fs)if(isPack(f.getName())||f.getName().endsWith(".part"))b+=f.length();return b;}

    /** True when a downloaded pack carries the usual signs of an encrypted paid Marketplace pack. */
    static boolean looksPaid(File f) {
        try {
            java.util.zip.ZipFile z = new java.util.zip.ZipFile(f);
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = z.entries();
            boolean bad = false, contentsBad = false;
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry entry=en.nextElement();
                String n = entry.getName().toLowerCase(Locale.ROOT);
                String base = n.contains("/") ? n.substring(n.lastIndexOf('/') + 1) : n;
                if (base.equals("signatures.json")) { bad = true; break; }
                if (base.equals("contents.json")) {
                    try {
                        byte[] b = Api.readAll(z.getInputStream(entry), 1 << 20);
                        String t = new String(b, "UTF-8").replace("\uFEFF", "").trim();
                        if (!t.startsWith("{")) contentsBad = true;
                    } catch (Exception e) { contentsBad = true; }
                }
            }
            z.close();
            return bad || contentsBad;
        } catch (Exception e) { return false; }
    }

    /** Hands the downloaded file to Minecraft so Bedrock imports it. */
    static void install(Context c, String fileName) {
        if(!safeName(fileName)||!isPack(fileName))return;
        File pack=new File(new File(c.getFilesDir(),"dl"),fileName);
        if(!pack.isFile()||looksPaid(pack))return;
        Uri u = Uri.parse("content://" + ShareProvider.AUTH + "/dl/" + Uri.encode(fileName));
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(u, "application/octet-stream");
        i.setClipData(ClipData.newRawUri("addon", u));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.getPackageManager().getPackageInfo(MC, 0);
            i.setPackage(MC);
        } catch (Exception e) {
            try {
                c.getPackageManager().getPackageInfo(McVer.PREVIEW, 0);
                i.setPackage(McVer.PREVIEW);
            } catch (Exception ignored) {}
        }
        try {
            c.startActivity(i);
            return;
        } catch (ActivityNotFoundException e) { /* fall through */ }
        try {
            i.setPackage(null);
            Intent ch = Intent.createChooser(i, "Open addon with");
            ch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(ch);
        } catch (Exception e) {
            Toast.makeText(c, "Minecraft is not installed.", Toast.LENGTH_LONG).show();
        }
    }
}
