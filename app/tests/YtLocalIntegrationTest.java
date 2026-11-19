package com.imgmine.launcher67;
import org.junit.*;import static org.junit.Assert.*;
import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.annotation.*;
import android.content.*;import android.app.*;import android.view.*;import android.widget.*;
import java.lang.reflect.*;import java.util.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=34) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class YtLocalIntegrationTest {
    StreamChatService service; SharedPreferences sp; int calls; boolean created;
    private YtLocal.Reply r(int code,String body){YtLocal.Reply r=new YtLocal.Reply();r.status=code;r.body=body;return r;}
    @Before public void setup() throws Exception {
        service=Robolectric.buildService(StreamChatService.class).get();
        sp=service.getSharedPreferences("ui",Context.MODE_PRIVATE);sp.edit().clear().commit();
        Field live=StreamChatService.class.getDeclaredField("live");live.setAccessible(true);live.set(null,service);
        StreamChatService.ytQuotaStop=false;
        StreamChatService.localTransport=(u,b)->{calls++;return r(403,"");};
    }
    @After public void cleanup() throws Exception {
        if(created) service.onDestroy();
        Field live=StreamChatService.class.getDeclaredField("live");live.setAccessible(true);live.set(null,null);
        StreamChatService.localTransport=new YtLocal.PhoneTransport();StreamChatService.ytQuotaStop=false;StreamChatService.ytState="YT OFF";
    }
    @Test public void existingInstallDefaultsToApiAndDoesNotFetchLocal() {
        sp.edit().putString("stream_yt_url","abcdefghijk").commit();service.ytStep();
        assertEquals("YT NO KEY",StreamChatService.ytState);assertEquals(0,calls);
    }
    @Test public void localDoesNotDecryptOrReplaceOldKeyAndHaltsPersistently() throws Exception {
        sp.edit().putString("stream_yt_source","local").putString("stream_yt_url","abcdefghijk").putString("stream_yt_key_enc","old-ciphertext").commit();
        service.ytStep();assertEquals("YT LOCAL ACCESS DENIED, STOPPED",StreamChatService.ytState);service.ytStep();assertEquals(1,calls);
        assertEquals("old-ciphertext",sp.getString("stream_yt_key_enc",""));
        StreamChatService replacement=Robolectric.buildService(StreamChatService.class).get();
        Field live=StreamChatService.class.getDeclaredField("live");live.setAccessible(true);live.set(null,replacement);
        replacement.ytStep();assertEquals(1,calls); // sticky process restart must not retry boundary
    }
    @Test public void explicitSaveResetsHaltButCannotClearServerWait() {
        sp.edit().putString("stream_yt_source","local").putString("stream_yt_url","abcdefghijk")
            .putLong("stream_local_retry_at",System.currentTimeMillis()+120000).commit();
        StreamChatService.youtubeSettingsChanged();assertTrue(service.ytStep()>100000);assertEquals(0,calls);
        assertEquals("YT LOCAL RATE LIMIT, WAIT",StreamChatService.ytState);
    }
    @Test public void terminalStateHasNoTimerRetry() {
        sp.edit().putString("stream_yt_source","local").putString("stream_yt_url","abcdefghijk").commit();
        StreamChatService.localTransport=(u,b)->{calls++;return r(200,"var ytInitialPlayerResponse={\"playabilityStatus\":{\"status\":\"LOGIN_REQUIRED\"}};");};
        for(int i=0;i<4;i++)service.ytStep();assertEquals(1,calls);assertEquals("YT LOCAL LOGIN REQUIRED, STOPPED",StreamChatService.ytState);
    }
    @Test public void emptyUrlTurnsEitherSourceOff() {
        sp.edit().putString("stream_yt_source","local").commit();service.ytStep();assertEquals("YT OFF",StreamChatService.ytState);assertEquals(0,calls);
    }
    @Test public void settingsChangedDuringFetchNeverSendStaleSource() {
        sp.edit().putString("stream_yt_source","local").putString("stream_yt_url","abcdefghijk").commit();
        StreamChatService.localTransport=(u,b)->{calls++;sp.edit().putString("stream_yt_url","").commit();StreamChatService.youtubeSettingsChanged();return r(403,"");};
        service.ytStep();assertFalse(sp.contains("stream_local_halt"));service.ytStep();assertEquals("YT OFF",StreamChatService.ytState);assertEquals(1,calls);
    }
    @Test public void fixtureSourceReachesRealLoopbackBridgeWithUnicode() throws Exception {
        // Real TCP/WS service; upstream is an injected fixture, never YouTube.
        service.onCreate();created=true;
        Field tf=StreamChatService.class.getDeclaredField("ytThread");tf.setAccessible(true);Thread yt=(Thread)tf.get(service);
        long end=System.currentTimeMillis()+2000;
        while(yt.getState()!=Thread.State.TIMED_WAITING && System.currentTimeMillis()<end)Thread.sleep(5);
        assertEquals(Thread.State.TIMED_WAITING,yt.getState());
        java.net.Socket socket=new java.net.Socket("127.0.0.1",StreamChatService.port);socket.setSoTimeout(4000);
        try {
            socket.getOutputStream().write(("GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n").getBytes("UTF-8"));
            assertTrue(Ws.readHttpHead(socket.getInputStream(),8192).startsWith("HTTP/1.1 101"));
            Ws.read(socket.getInputStream());Ws.read(socket.getInputStream());
            final int[] n={0};
            String page="var ytInitialData={\"liveChatRenderer\":{\"continuations\":[{\"reloadContinuationData\":{\"continuation\":\"T0\"}}]}}; ytcfg.set({\"INNERTUBE_CLIENT_VERSION\":\"2.fixture\"});";
            String batch="{\"continuationContents\":{\"liveChatContinuation\":{\"continuations\":[{\"timedContinuationData\":{\"continuation\":\"T1\",\"timeoutMs\":5000}}],\"actions\":[{\"addChatItemAction\":{\"item\":{\"liveChatTextMessageRenderer\":{\"id\":\"ID\",\"authorName\":{\"simpleText\":\"Alice😀\"},\"message\":{\"runs\":[{\"text\":\"hello 👩🏽‍💻\"}]}}}}}]}}}";
            StreamChatService.localTransport=(u,b)->r(200,n[0]++==0?page:batch.replace("ID",n[0]==2?"old":"new"));
            sp.edit().putString("stream_yt_source","local").putString("stream_yt_url","abcdefghijk").commit();
            service.ytStep();service.ytStep();assertEquals("YT LOCAL LIVE",StreamChatService.ytState);
            org.json.JSONObject frame=new org.json.JSONObject(new String(Ws.read(socket.getInputStream()).data,"UTF-8"));
            String command=frame.getJSONObject("body").getString("commandLine");
            assertTrue(command.startsWith("scriptevent tysc:chat "));
            org.json.JSONObject payload=new org.json.JSONObject(command.substring("scriptevent tysc:chat ".length()));
            assertEquals("Alice😀",payload.getString("n"));assertEquals("hello 👩🏽‍💻",payload.getString("t"));assertEquals(3,n[0]);
        } finally {socket.close();}
    }
    @Test public void freshLifetimeRemainsFiveSeconds() throws Exception {
        Activity a=Robolectric.buildActivity(MainActivity.class).setup().get();View root=a.findViewById(android.R.id.content);
        assertNotNull(find(root,"5s"));assertFalse(sp.contains("stream_lifetime"));
    }
    @Test public void stoppedLocalStatusFitsSmallBridgeOverlay() throws Exception {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();Ui.init(a);Images.init(a);
        StreamChatService.state="CONNECTED";StreamChatService.port=51234;StreamChatService.ytState=StreamChatService.localStatus("LOGIN_REQUIRED");
        Panel panel=new Panel(a,()->{});Method build=Panel.class.getDeclaredMethod("build");build.setAccessible(true);build.invoke(panel);
        Method screen=Panel.class.getDeclaredMethod("showScreen",int.class);screen.setAccessible(true);screen.invoke(panel,2);
        Field cf=Panel.class.getDeclaredField("card");cf.setAccessible(true);View card=(View)cf.get(panel);
        ((ViewGroup)card.getParent()).removeView(card);a.setContentView(card);
        assertNotNull(find(card,"YOUTUBE: LOCAL LOGIN REQUIRED, STOPPED"));assertNotNull(find(card,"BRIDGE: CONNECTED"));assertNotNull(find(card,"COPY"));assertNotNull(find(card,"TEST"));
        VisualTest.render(card,400,360,System.getProperty("launcher.renderDir","/tmp/yt-local-renders")+"/bridge-local-stopped.png");
        StreamChatService.state="OFF";StreamChatService.port=0;
    }
    private TextView find(View v,String prefix){if(v instanceof TextView&&((TextView)v).getText().toString().startsWith(prefix))return(TextView)v;
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){TextView t=find(g.getChildAt(i),prefix);if(t!=null)return t;}}return null;}
    @Test public void sourceUiSavesExplicitLocalAndPreservesOldPrefsAndPixels() throws Exception {
        sp.edit().putString("stream_yt_key_enc","untouched").putInt("stream_lifetime",7).putString("stream_mode","bubble").putBoolean("stream_expanded",true).commit();
        Activity a=Robolectric.buildActivity(MainActivity.class).setup().get();View root=a.findViewById(android.R.id.content);
        TextView toggle=find(root,"STREAM CHAT");TextView save=find(root,"SAVE YOUTUBE");if(!save.isShown())toggle.performClick();
        TextView source=find(root,"SOURCE: YOUTUBE API");assertNotNull(source);
        source.performClick();assertTrue(source.getText().toString().contains("PHONE-LOCAL"));
        save.performClick();assertEquals("local",sp.getString("stream_yt_source",""));assertEquals("untouched",sp.getString("stream_yt_key_enc",""));
        assertEquals(7,sp.getInt("stream_lifetime",0));assertEquals("bubble",sp.getString("stream_mode",""));
        String dir=System.getProperty("launcher.renderDir","/tmp/yt-local-renders");
        org.robolectric.shadows.ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS);
        VisualTest.render(root,360,1760,dir+"/main-local-expanded.png");
        source.performClick();save.performClick();assertEquals("api",sp.getString("stream_yt_source",""));
        org.robolectric.shadows.ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS);
        VisualTest.render(root,360,1760,dir+"/main-api-expanded.png");
    }
}
