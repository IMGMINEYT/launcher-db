package com.imgmine.launcher67;
import static org.junit.Assert.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowLooper;
import android.content.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.zip.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class UpgradeTest {
 Context c;
 @Before public void before()throws Exception{c=RuntimeEnvironment.getApplication();Ui.init(c);Images.init(c);Field loaded=Downloader.class.getDeclaredField("loaded");loaded.setAccessible(true);loaded.set(null,false);Field tasks=Downloader.class.getDeclaredField("TASKS");tasks.setAccessible(true);((Map)tasks.get(null)).clear();}
 static Object get(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
 static void set(Object o,String n,Object x)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,x);}
 static Object call(Object o,String n,Class<?>[] types,Object... args)throws Exception{Method m=o.getClass().getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(o,args);}
 Panel panel()throws Exception{Panel p=new Panel(c,()->{});call(p,"build",new Class[]{});return p;}
 static byte[] pack()throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();try(ZipOutputStream z=new ZipOutputStream(b)){z.putNextEntry(new ZipEntry("manifest.json"));z.write("{}".getBytes());z.closeEntry();z.putNextEntry(new ZipEntry("payload"));byte[] p=new byte[400000];new Random(43).nextBytes(p);z.write(p);z.closeEntry();}return b.toByteArray();}
 static class Response extends HttpURLConnection {
  final byte[] data;final int code;final long offset;final boolean length;final int delay;boolean closed;
  Response(URL u,byte[] data,int code,long offset,boolean length,int delay){super(u);this.data=data;this.code=code;this.offset=offset;this.length=length;this.delay=delay;}
  public void connect(){} public void disconnect(){closed=true;}public boolean usingProxy(){return false;}
  public int getResponseCode(){return code;}
  public long getContentLengthLong(){return length?data.length-offset:-1;}
  public String getHeaderField(String n){if(n.equals("Content-Length"))return length?""+(data.length-offset):null;if(n.equals("ETag"))return "\"test-pack-v1\"";if(n.equals("Content-Range")&&code==206)return "bytes "+offset+"-"+(data.length-1)+"/"+data.length;return null;}
  public InputStream getInputStream(){return new ByteArrayInputStream(data,(int)offset,data.length-(int)offset){public synchronized int read(byte[] b,int off,int len){if(closed)return -1;if(delay>0)try{Thread.sleep(delay);}catch(Exception e){}return super.read(b,off,Math.min(4096,len));}};}
 }
 void await(Downloader.Task t,int state)throws Exception{long end=System.currentTimeMillis()+12000;while(t.state!=state&&System.currentTimeMillis()<end){Thread.sleep(30);ShadowLooper.runUiThreadTasks();}assertEquals(t.error,state,t.state);}
 @Test public void exactBytesUnknownLengthAndOfflineHistoryMigration()throws Exception{
  byte[] bytes=pack();Downloader.Connections old=Downloader.connections;
  try{Downloader.connections=u->new Response(u,bytes,200,0,false,0);Downloader.start(c,"https://fixture.test/one.mcpack","upgrade_one.mcpack","Offline test",null);Downloader.Task t=Downloader.task(c,"upgrade_one.mcpack");await(t,Downloader.DONE);
   assertArrayEquals(bytes,java.nio.file.Files.readAllBytes(new File(c.getFilesDir(),"dl/upgrade_one.mcpack").toPath()));assertEquals(bytes.length,t.got);assertEquals(100,t.pct());
   assertTrue(Downloader.tasks(c).contains(t));Downloader.remove(c,t.key,true);assertFalse(new File(c.getFilesDir(),"dl/upgrade_one.mcpack").exists());
  }finally{Downloader.connections=old;}
 }
 @Test public void pauseResumeRangeCancelNoDuplicateWorker()throws Exception{
  byte[] bytes=pack();Downloader.Connections old=Downloader.connections;AtomicInteger calls=new AtomicInteger(),ranges=new AtomicInteger();
  try{Downloader.connections=u->{calls.incrementAndGet();return new Response(u,bytes,200,0,true,10){@Override public int getResponseCode(){String r=getRequestProperty("Range");if(r!=null)ranges.incrementAndGet();return super.getResponseCode();}};};
   Downloader.start(c,"https://fixture.test/two.mcpack","upgrade_two.mcpack","Pause test",null);Downloader.Task t=Downloader.task(c,"upgrade_two.mcpack");long end=System.currentTimeMillis()+5000;while(t.got<4096&&System.currentTimeMillis()<end)Thread.sleep(20);
   Downloader.pause(c,t.key);assertEquals(Downloader.PAUSED,t.state);Thread.sleep(120);long got=new File(c.getFilesDir(),"dl/upgrade_two.mcpack.part").length();assertTrue(got>0&&got<bytes.length);
   Downloader.resume(c,t.key);await(t,Downloader.DONE);assertTrue("range requested",ranges.get()>0);assertArrayEquals(bytes,java.nio.file.Files.readAllBytes(new File(c.getFilesDir(),"dl/upgrade_two.mcpack").toPath()));
   Downloader.remove(c,t.key,true);
   Downloader.start(c,"https://fixture.test/three.mcpack","upgrade_three.mcpack","Cancel test",null);Downloader.Task t3=Downloader.task(c,"upgrade_three.mcpack");Thread.sleep(100);Downloader.cancel(c,t3.key);Thread.sleep(200);assertEquals(Downloader.CANCELED,t3.state);assertFalse(new File(c.getFilesDir(),"dl/upgrade_three.mcpack.part").exists());Downloader.remove(c,t3.key,true);
  }finally{Downloader.connections=old;}
 }
 @Test public void completedImageMissingAndUnknownAreDistinct()throws Exception{
  Panel p=panel();Object h=call(p,"newHolder",new Class[]{});Addon a=new Addon();a.id="empty";a.name="No artwork";a.banner=null;
  call(p,"bind",new Class[]{h.getClass(),Addon.class},h,a);TextView state=(TextView)get(h,"ist");assertEquals("NO IMAGE",state.getText().toString());assertEquals(View.VISIBLE,state.getVisibility());
  a.banner="https://fixture.test/art.png";call(p,"bind",new Class[]{h.getClass(),Addon.class},h,a);assertEquals("Loading",state.getText().toString());assertEquals(View.VISIBLE,((View)get(h,"ibar")).getVisibility());
 }
 @Test public void progressNeverInventsEtaOrPercent(){Downloader.Task t=new Downloader.Task();t.got=12;t.total=-1;t.bps=0;assertEquals(-1,t.pct());assertEquals(-1,t.etaSec());t.total=100;t.got=30;t.bps=10;assertEquals(30,t.pct());assertEquals(7,t.etaSec());assertFalse(Downloader.safeName("../../world.mcworld"));assertFalse(Downloader.valid("http://fixture.test/x.mcpack"));assertFalse(Downloader.rangeMatches("bytes 0-99/100",50));assertTrue(Downloader.rangeMatches("bytes 50-99/100",50));}
 @Test public void allModesAndDailyChipsExist()throws Exception{Panel p=panel();HorizontalScrollView ar=(HorizontalScrollView)get(p,"allRow");assertEquals(4,((LinearLayout)ar.getChildAt(0)).getChildCount());HorizontalScrollView cr=(HorizontalScrollView)get(p,"cmdRow");assertEquals("DAILY USE",((TextView)((LinearLayout)cr.getChildAt(0)).getChildAt(0)).getText().toString());}
 Downloader.Task partial(String name,byte[] data,int count)throws Exception{
  Downloader.tasks(c);Downloader.Task t=new Downloader.Task();t.name=name;t.key=name;t.state=Downloader.PAUSED;t.validator="\"test-pack-v1\"";
  File f=new File(c.getFilesDir(),"dl/"+name+".part");try(FileOutputStream o=new FileOutputStream(f)){o.write(data,0,count);}
  Field field=Downloader.class.getDeclaredField("TASKS");field.setAccessible(true);((Map<String,Downloader.Task>)field.get(null)).put(name,t);return t;
 }
 @Test public void proper206And416RestartAreByteExact()throws Exception{
  byte[] bytes=pack();Downloader.Connections old=Downloader.connections;AtomicInteger resets=new AtomicInteger();
  try{
   Downloader.Task t=partial("range_ok.mcpack",bytes,80000);
   Downloader.connections=u->new Response(u,bytes,206,80000,true,0);
   Downloader.start(c,"https://fixture.test/range.mcpack",t.key,"Range",null);await(t,Downloader.DONE);
   assertArrayEquals(bytes,java.nio.file.Files.readAllBytes(new File(c.getFilesDir(),"dl/"+t.key).toPath()));Downloader.remove(c,t.key,true);
   Downloader.Task full=partial("range_416.mcpack",bytes,bytes.length);
   Downloader.connections=u->{int n=resets.incrementAndGet();return new Response(u,bytes,n==1?416:200,0,true,0);};
   Downloader.start(c,"https://fixture.test/full.mcpack",full.key,"416 restart",null);await(full,Downloader.DONE);assertEquals(2,resets.get());
   assertArrayEquals(bytes,java.nio.file.Files.readAllBytes(new File(c.getFilesDir(),"dl/"+full.key).toPath()));Downloader.remove(c,full.key,true);
  }finally{Downloader.connections=old;}
 }
 @Test public void automaticRetryAndHttpsRedirectGuard()throws Exception{
  byte[] bytes=pack();Downloader.Connections old=Downloader.connections;AtomicInteger calls=new AtomicInteger();
  try{Downloader.connections=u->new Response(u,bytes,calls.incrementAndGet()==1?503:200,0,true,0);
   Downloader.start(c,"https://fixture.test/retry.mcpack","auto_retry.mcpack","Retry",null);Downloader.Task t=Downloader.task(c,"auto_retry.mcpack");await(t,Downloader.DONE);assertEquals(2,calls.get());Downloader.remove(c,t.key,true);
   Downloader.connections=u->new Response(u,bytes,302,0,true,0){public String getHeaderField(String n){return n.equals("Location")?"http://fixture.test/insecure.mcpack":null;}};
   t=new Downloader.Task();t.url="https://fixture.test/redirect.mcpack";
   Method open=Downloader.class.getDeclaredMethod("open",Downloader.Task.class,long.class);open.setAccessible(true);
   try{open.invoke(null,t,0L);fail("cleartext redirect must fail");}catch(InvocationTargetException e){assertTrue(e.getCause().getMessage().contains("HTTPS"));}
  }finally{Downloader.connections=old;}
 }
 @Test public void localGeneratedSkinIsImmediatelyInOfflineLibrary()throws Exception{byte[] data=pack();Downloader.saveLocal(c,"local_skin.mcpack","Local skin",data);Downloader.Task t=Downloader.task(c,"local_skin.mcpack");assertNotNull(t);assertEquals(Downloader.DONE,t.state);assertEquals(data.length,t.got);Downloader.remove(c,t.key,true);}
 @Test public void cacheUsesTtlAndConditional304()throws Exception{
  Net.Connections old=Net.connections;AtomicInteger calls=new AtomicInteger(),conditionals=new AtomicInteger();byte[] bytes="cached-body".getBytes("UTF-8");
  try{Net.connections=u->new Response(u,bytes,calls.incrementAndGet()==1?200:304,0,true,0){public int getResponseCode(){if(getRequestProperty("If-None-Match")!=null)conditionals.incrementAndGet();return super.getResponseCode();}};
   String url="https://cachefixture.test/"+System.nanoTime();assertArrayEquals(bytes,Net.get(url,1000,100000,"test"));assertArrayEquals(bytes,Net.get(url,1000,100000,"test"));assertEquals(1,calls.get());
   assertArrayEquals(bytes,Net.get(url,1000,0,"test"));assertEquals(2,calls.get());assertEquals(1,conditionals.get());
  }finally{Net.connections=old;}
 }
 @Test public void independentWorkersPublishWithoutWaitingForSlowSite()throws Exception{
  java.util.concurrent.CountDownLatch blocked=new java.util.concurrent.CountDownLatch(1),started=new java.util.concurrent.CountDownLatch(1);
  java.util.concurrent.Future<?> slow=Net.worker("slow-fixture").submit(()->{started.countDown();try{blocked.await(5,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception e){}});
  try{assertTrue(started.await(2,java.util.concurrent.TimeUnit.SECONDS));assertEquals("ready",Net.worker("fast-fixture").submit(()->"ready").get(500,java.util.concurrent.TimeUnit.MILLISECONDS));assertFalse(slow.isDone());}finally{blocked.countDown();}
 }
 @Test public void sourceFailureWaitsForDiscoverableExplicitRetry()throws Exception{
  Panel p=panel();AtomicInteger runs=new AtomicInteger();Method work=Panel.class.getDeclaredMethod("work",String.class,String.class,Runnable.class);work.setAccessible(true);
  work.invoke(p,"fixture-failure","fixture-failure",(Runnable)()->{throw new RuntimeException("fixture");});Thread.sleep(100);ShadowLooper.runUiThreadTasks();
  assertTrue((Boolean)get(p,"feedFailed"));assertTrue(((TextView)get(p,"foot")).getText().toString().contains("retry"));
  work.invoke(p,"fixture-failure","fixture-failure",(Runnable)runs::incrementAndGet);Thread.sleep(50);assertEquals(0,runs.get());
  set(p,"typeFilter",6);((TextView)get(p,"foot")).performClick();assertFalse((Boolean)get(p,"feedFailed"));
  work.invoke(p,"fixture-failure","fixture-failure",(Runnable)runs::incrementAndGet);Thread.sleep(100);ShadowLooper.runUiThreadTasks();assertEquals(1,runs.get());
 }

 @Test public void foregroundServiceCreatesVisibleNotificationAndStopsWhenIdle()throws Exception{
  DownloadService service=Robolectric.buildService(DownloadService.class).create().get();org.robolectric.shadows.ShadowService shadow=org.robolectric.Shadows.shadowOf(service);
  assertEquals(68,shadow.getLastForegroundNotificationId());assertNotNull(shadow.getLastForegroundNotification());assertTrue(shadow.isLastForegroundNotificationAttached());
  service.onStartCommand(null,0,1);org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(3,java.util.concurrent.TimeUnit.SECONDS);assertTrue(shadow.isStoppedBySelf());service.onDestroy();
 }
 @Test public void historyRestoresUnfinishedTransferAsPausedWithPartialBytes()throws Exception{
  byte[] bytes=pack();Downloader.Connections old=Downloader.connections;
  try{Downloader.connections=u->new Response(u,bytes,200,0,true,10);Downloader.start(c,"https://fixture.test/history.mcpack","history_resume.mcpack","History",null);Downloader.Task t=Downloader.task(c,"history_resume.mcpack");
   long end=System.currentTimeMillis()+3000;while(t.got<4096&&System.currentTimeMillis()<end)Thread.sleep(20);Downloader.pause(c,t.key);Thread.sleep(200);
   long got=new File(c.getFilesDir(),"dl/"+t.key+".part").length();assertTrue(got>0&&got<bytes.length);
   Field loaded=Downloader.class.getDeclaredField("loaded");loaded.setAccessible(true);loaded.set(null,false);Field field=Downloader.class.getDeclaredField("TASKS");field.setAccessible(true);((Map)field.get(null)).clear();
   Downloader.Task restored=Downloader.task(c,t.key);assertNotNull(restored);assertEquals(Downloader.PAUSED,restored.state);assertEquals(got,restored.got);assertEquals("History",restored.label);assertFalse(restored.validator.isEmpty());Downloader.remove(c,t.key,true);
  }finally{Downloader.connections=old;}
 }
 @Test public void actualLatestAndPopularApiRequestsUseServerSort()throws Exception{
  Net.Connections old=Net.connections;List<String> urls=new ArrayList<>();
  byte[] data=("{\"meta\":{\"page_count\":2},\"results\":[{\"id\":123,\"title\":\"Fixture addon\",\"slug\":\"fixture-addon\",\"downloadCount\":99000}]}" ).getBytes("UTF-8");
  try{Net.connections=u->{urls.add(u.toString());return new Response(u,data,200,0,true,0);};Mc.Page latest=Mc.sorted(1,991,"created_at"),popular=Mc.sorted(1,992,"popularity_all");
   assertNotNull(latest);assertNotNull(popular);assertEquals(99000,popular.items.get(0).downloadCount);assertTrue(urls.get(0).contains("sort=created_at"));assertTrue(urls.get(1).contains("sort=popularity_all"));
  }finally{Net.connections=old;}
 }

 @Test public void missingCompletedFileIsNotShownAsAnOfflineInstall()throws Exception{Downloader.saveLocal(c,"missing_fixture.mcpack","Missing fixture",pack());assertTrue(new File(c.getFilesDir(),"dl/missing_fixture.mcpack").delete());List<Downloader.Task> rows=Downloader.tasks(c);Downloader.Task t=Downloader.task(c,"missing_fixture.mcpack");assertEquals(Downloader.FAILED,t.state);assertTrue(t.error.contains("missing"));assertEquals(0,Downloader.storageUsed(c));Downloader.remove(c,t.key,true);}

}
