package com.imgmine.launcher67;
import org.junit.*;import static org.junit.Assert.*;
import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.annotation.*;
import android.content.*;import android.app.*;import android.view.*;import android.widget.*;import java.lang.reflect.*;import java.util.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=34) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LauncherNextTest {
    Activity a;boolean enabled;Context c;
    @Before public void setup(){a=Robolectric.buildActivity(Activity.class).setup().get();c=a;Ui.init(c);enabled=Vip.ENABLED;Vip.ENABLED=false;c.getSharedPreferences("ui",0).edit().clear().commit();}
    @After public void cleanup(){Vip.ENABLED=enabled;}
    Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    void set(Object target,String name,Object value)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    Object call(Object t,String name,Class[] args,Object...values)throws Exception{Method m=t.getClass().getDeclaredMethod(name,args);m.setAccessible(true);return m.invoke(t,values);}
    TextView find(View v,String prefix){if(v instanceof TextView&&((TextView)v).getText().toString().startsWith(prefix))return(TextView)v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){TextView x=find(g.getChildAt(i),prefix);if(x!=null)return x;}}return null;}
    @Test public void versionPolicyOnlyForwardWithin26AndExactFileWins(){
        assertTrue(McVer.matches("26.0","26.2.3"));assertTrue(McVer.matches("1.26.0","26.3"));assertFalse(McVer.matches("26.3","26.2"));assertFalse(McVer.matches("26.999999999999999999999","26.2"));
        assertFalse(McVer.matches("1.21","26.0"));assertFalse(McVer.matches("26.0","27.0"));assertTrue(McVer.matches("1.21","1.21.90"));
        Mc.Info info=new Mc.Info();info.files.add(new String[]{"https://fixture.test/a.mcaddon","pack_26.0.mcaddon"});info.files.add(new String[]{"https://fixture.test/b.mcaddon","pack_26.2.mcaddon"});
        assertEquals("https://fixture.test/b.mcaddon",Mc.pickFile(info,"26.2.1")[0]);assertEquals("yes",Mc.pickFile(info,"26.4")[2]);assertEquals("no",Mc.pickFile(info,"1.21")[2]);
    }
    @Test public void redDotClickNeverLaunchesPageOrDownloader()throws Exception{
        Panel p=new Panel(c,()->{});call(p,"build",new Class[]{});set(p,"userVer","26.0");
        Addon addon=new Addon();addon.id="mismatch";addon.cf=true;addon.page="https://fixture.test/post";addon.info=new Mc.Info();addon.info.files.add(new String[]{"https://fixture.test/pack.mcaddon","pack_26.3.mcaddon"});
        assertEquals(true,call(p,"redDot",new Class[]{Addon.class},addon));
        call(p,"download",new Class[]{Addon.class},addon);
        assertNull(org.robolectric.Shadows.shadowOf(a).getNextStartedActivity());assertNull(Downloader.task(c,"mismatch.mcaddon"));
    }
    @Test public void databaseCacheAndRowsAreHiddenButExternalFeedWorks()throws Exception{
        java.io.File cache=new java.io.File(c.getFilesDir(),"addons.json");try(java.io.FileOutputStream out=new java.io.FileOutputStream(cache)){out.write("{\"addons\":[{\"id\":\"private\",\"name\":\"Hidden Database\",\"download\":\"https://fixture.test/private.mcaddon\"}]}".getBytes("UTF-8"));}
        assertTrue(Vip.feedLocked(c));assertNull(Api.cached(c));
        Panel p=new Panel(c,()->{});call(p,"build",new Class[]{});Addon db=new Addon();db.id="private";db.name="Hidden Database";Addon external=new Addon();external.id="public";external.cf=true;external.name="Public catalog";external.type="addon";
        ((List<Addon>)field(p,"all")).add(db);((List<Addon>)field(p,"feed")).add(external);call(p,"refilter",new Class[]{boolean.class},false);
        List<Addon> visible=(List<Addon>)field(p,"view");assertFalse(visible.contains(db));assertTrue(visible.contains(external));
        call(p,"showScreen",new Class[]{int.class},0);assertEquals(View.VISIBLE,((View)field(p,"list")).getVisibility());
        call(p,"showScreen",new Class[]{int.class},1);assertEquals(View.VISIBLE,((View)field(p,"dlView")).getVisibility());
    }
    @Test public void databaseFetchDoesNotUsePublicMirrors()throws Exception{
        final int[] network={0},result={0};Net.Connections old=Net.connections;
        Net.connections=u->{network[0]++;throw new java.io.IOException("must not fetch");};
        try{
            Api.fetch(c,new Api.CB(){public void ok(List<Addon> list){assertTrue(list.isEmpty());result[0]=1;}public void fail(){result[0]=2;}});
            long end=System.currentTimeMillis()+3000;while(result[0]==0&&System.currentTimeMillis()<end){Thread.sleep(20);org.robolectric.shadows.ShadowLooper.idleMainLooper();}
            assertEquals(1,result[0]);assertEquals(0,network[0]);
        }finally{Net.connections=old;}
    }
    @Test public void localFirstButExplicitApiAndLegacyApiKeysPreserved(){
        SharedPreferences sp=c.getSharedPreferences("ui",0);assertEquals("local",YtUi.source(sp));
        sp.edit().putString("stream_yt_key_enc","saved").commit();assertEquals("api",YtUi.source(sp));
        sp.edit().putString("stream_yt_source","local").commit();assertEquals("local",YtUi.source(sp));sp.edit().putString("stream_yt_source","api").commit();assertEquals("api",YtUi.source(sp));
    }
    @Test public void bundledAddonIsSavedByteExactInExistingDownloadLibrary() throws Exception {
        c.getAssets().getClass().getMethod("addAssetPath",String.class).invoke(c.getAssets(),System.getProperty("launcher.assetsApk"));
        byte[] expected;try(java.io.InputStream in=c.getAssets().open("streamchat.mcaddon")){expected=Api.readAll(in,16<<20);}
        Downloader.saveLocal(c,"existing.mcpack","Existing history",new byte[]{1,2,3});
        YtUi.installAddon(c);
        java.io.File out=new java.io.File(c.getFilesDir(),"dl/StreamChat.mcaddon");
        assertArrayEquals(expected,java.nio.file.Files.readAllBytes(out.toPath()));assertNotNull(Downloader.task(c,"existing.mcpack"));
        assertNotNull(Downloader.task(c,"StreamChat.mcaddon"));
        Intent intent=org.robolectric.Shadows.shadowOf(a).getNextStartedActivity();assertNotNull(intent);
        assertEquals("content",intent.getData().getScheme());assertEquals(Intent.ACTION_VIEW,intent.getAction());
    }
    @Test public void bridgeContainsSourceAndBundledAddonAndScrollsInsideSameOverlay()throws Exception{
        Panel p=new Panel(c,()->{});call(p,"build",new Class[]{});call(p,"showScreen",new Class[]{int.class},2);
        View root=(View)field(p,"card");((ViewGroup)root.getParent()).removeView(root);a.setContentView(root);
        assertNotNull(find(root,"YOUTUBE: PHONE-LOCAL"));assertNotNull(find(root,"SAVE YOUTUBE"));assertNotNull(find(root,"DOWNLOAD / INSTALL CHAT ADDON"));
        assertEquals(0xFFFFFFFF,find(root,"YOUTUBE: PHONE-LOCAL").getCurrentTextColor());assertNotNull(find(root,"TEST"));assertNotNull(find(root,"COPY"));
        String dir=System.getProperty("launcher.renderDir","/tmp/next-renders");
        VisualTest.render(root,400,360,dir+"/bridge-top.png");
        ScrollView scroll=(ScrollView)field(p,"bridgeView");scroll.fullScroll(View.FOCUS_DOWN);scroll.scrollTo(0,10000);
        VisualTest.render(root,400,360,dir+"/bridge-youtube.png");
    }
}
