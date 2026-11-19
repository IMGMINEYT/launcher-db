package com.imgmine.launcher67;
import org.junit.*;
import static org.junit.Assert.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.json.*;
import java.net.*;
import java.io.*;
import java.util.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class YtLocalTest {
    private static YtLocal.Reply reply(int status,String body) { YtLocal.Reply r=new YtLocal.Reply();r.status=status;r.body=body;return r; }
    private static String page() {return "<script>var ytInitialPlayerResponse={\"playabilityStatus\":{\"status\":\"OK\"},\"videoDetails\":{\"isLiveContent\":true}};</script>"
        +"<script>var ytInitialData={\"contents\":{\"liveChatRenderer\":{\"continuations\":[{\"reloadContinuationData\":{\"continuation\":\"T0\"}}]}}};</script>"
        +"<script>ytcfg.set({\"INNERTUBE_CLIENT_VERSION\":\"2.fixture\"});</script>"; }
    private static JSONObject message(String id,String name,String text) throws Exception {
        return new JSONObject().put("addChatItemAction",new JSONObject().put("item",new JSONObject().put("liveChatTextMessageRenderer",
            new JSONObject().put("id",id).put("authorName",new JSONObject().put("simpleText",name))
                .put("message",new JSONObject().put("runs",new JSONArray().put(new JSONObject().put("text",text)))))));
    }
    private static String batch(String token,long delay,JSONObject...actions) throws Exception {
        JSONObject chat=new JSONObject().put("actions",new JSONArray(Arrays.asList(actions)));
        if(token!=null)chat.put("continuations",new JSONArray().put(new JSONObject().put("timedContinuationData",new JSONObject().put("continuation",token).put("timeoutMs",delay))));
        return new JSONObject().put("continuationContents",new JSONObject().put("liveChatContinuation",chat)).toString();
    }
    @Test public void sourcesAreCanonicalAndHostBound() throws Exception {
        assertEquals("https://www.youtube.com/watch?v=abcdefghijk",YtLocal.source("https://youtu.be/abcdefghijk?t=2").url.toString());
        assertEquals("https://www.youtube.com/@test/live",YtLocal.source("@test").url.toString());
        assertEquals("https://www.youtube.com/@test/live",YtLocal.source("https://youtube.com/@test").url.toString());
        for(String bad:new String[]{"https://evil.test/watch?v=abcdefghijk","http://www.youtube.com/watch?v=abcdefghijk","https://youtube.com.evil.test/watch?v=abcdefghijk","https://u:p@youtube.com/watch?v=abcdefghijk","https://youtube.com:8080/watch?v=abcdefghijk","https://youtu.be/abc","file:///etc/passwd"}) {
            try {YtLocal.source(bad);fail(bad);}catch(YtLocal.Failure expected){}
        }
    }
    @Test public void balancedExtractionHandlesStringBracesAndEscapes() throws Exception {
        JSONObject o=YtLocal.embedded("var ytInitialData={\"x\":\"} { \\\" hello\",\"a\":{\"b\":3}}; trailing", "ytInitialData");
        assertNotNull(o); assertEquals(3,o.getJSONObject("a").getInt("b"));
        assertNull(YtLocal.embedded("var ytInitialData={not json}","ytInitialData"));
    }
    @Test public void stopsOnLoginConsentAndOfflineWithoutRetry() throws Exception {
        String[] pages={"var ytInitialPlayerResponse={\"playabilityStatus\":{\"status\":\"LOGIN_REQUIRED\"}};", "https://consent.youtube.com/m", "var ytInitialPlayerResponse={\"videoDetails\":{\"isLiveContent\":false}};"};
        YtLocal.State[] states={YtLocal.State.LOGIN_REQUIRED,YtLocal.State.CONSENT_REQUIRED,YtLocal.State.NOT_LIVE};
        for(int i=0;i<pages.length;i++) {
            final String p=pages[i];final int[] calls={0};YtLocal c=new YtLocal((u,b)->{calls[0]++;return reply(200,p);});
            try {c.open("abcdefghijk");fail();}catch(YtLocal.Failure e){assertEquals(states[i],e.state);}
            try {c.next();fail();}catch(YtLocal.Failure e){assertEquals(states[i],e.state);}
            assertEquals(1,calls[0]);
        }
    }
    @Test public void sessionSkipsBacklogDeduplicatesPreservesEmojiAndHonorsDelay() throws Exception {
        ArrayList<String> requests=new ArrayList<>();final int[] n={0};
        String first=batch("T1",4000,message("old","Old","history"));
        String second=batch("T2",90000,message("old","Old","history"),message("new","A😀","hi 👩🏽‍💻 ❤️"));
        YtLocal c=new YtLocal((u,b)->{requests.add(b==null?"GET":b);return reply(200,n[0]++==0?page():n[0]==2?first:second);});
        c.open("abcdefghijk");assertEquals(0,c.next().messages.size());YtLocal.Batch b=c.next();
        assertEquals(90000,b.waitMs);assertEquals(1,b.messages.size());assertEquals("A😀",b.messages.get(0).author);assertEquals("hi 👩🏽‍💻 ❤️",b.messages.get(0).text);
        JSONObject request=new JSONObject(requests.get(2));assertEquals("T1",request.getString("continuation"));
        assertEquals("2.fixture",request.getJSONObject("context").getJSONObject("client").getString("clientVersion"));
    }
    @Test public void rateLimitIsTerminalAndRetryAfterIsReported() throws Exception {
        final int[] calls={0};YtLocal c=new YtLocal((u,b)->{calls[0]++;if(b==null)return reply(200,page());YtLocal.Reply r=reply(429,"");r.retryAfter="120";return r;});
        c.open("abcdefghijk");for(int i=0;i<2;i++)try{c.next();fail();}catch(YtLocal.Failure e){assertEquals(YtLocal.State.RATE_LIMITED,e.state);if(i==0)assertEquals(120000,e.retryAfterMs);}
        assertEquals(2,calls[0]);
    }
    @Test public void consentAndLoginRedirectsAreNotFollowed() throws Exception {
        for(String host:new String[]{"https://consent.google.com/m","https://accounts.google.com/login","https://evil.test/"}) {
            final int[] n={0};YtLocal c=new YtLocal((u,b)->{n[0]++;YtLocal.Reply r=reply(302,"");r.location=host;return r;});
            try{c.open("abcdefghijk");fail();}catch(YtLocal.Failure expected){}assertEquals(1,n[0]);
        }
    }
    @Test public void finalBatchEndsAndDoesNotRefetch() throws Exception {
        final int[] n={0};String last=batch(null,0,message("a","A","x"));YtLocal c=new YtLocal((u,b)->reply(200,n[0]++==0?page():last));
        c.open("abcdefghijk");assertTrue(c.next().ended);try{c.next();fail();}catch(YtLocal.Failure e){assertEquals(YtLocal.State.END,e.state);}assertEquals(2,n[0]);
    }
    @Test public void emojiRunsUseReadableFallbackWithoutImageDownloads() throws Exception {
        JSONObject runs=new JSONObject().put("runs",new JSONArray().put(new JSONObject().put("text","hello "))
            .put(new JSONObject().put("emoji",new JSONObject().put("shortcuts",new JSONArray().put(":custom:"))))
            .put(new JSONObject().put("emoji",new JSONObject().put("emojiId","😀"))));
        assertEquals("hello :custom:😀",YtLocal.text(runs));
    }
    @Test public void boundedSeenAndBatch() throws Exception {
        LinkedHashSet<String> seen=new LinkedHashSet<>();for(int i=0;i<5000;i++)seen.add("old"+i);
        JSONObject[] rows=new JSONObject[240];for(int i=0;i<rows.length;i++)rows[i]=message("new"+i,"Name","Hello");
        YtLocal.Batch b=YtLocal.parse(new JSONObject(batch("T",1,rows)),seen,true);assertEquals(200,b.messages.size());assertEquals(5000,seen.size());assertEquals(5000,b.waitMs);
    }
    @Test public void transportHasBoundedHttpsNoCookieOrAutoRedirect() throws Exception {
        final boolean[] closed={false};final HttpURLConnection[] captured={null};
        Net.connections=u->{HttpURLConnection c=new HttpURLConnection(u){
            public void connect(){}public void disconnect(){closed[0]=true;}public boolean usingProxy(){return false;}
            public int getResponseCode(){return 200;}public InputStream getInputStream(){return new ByteArrayInputStream("ok".getBytes());}
            public OutputStream getOutputStream(){return new ByteArrayOutputStream();}
        };captured[0]=c;return c;};
        try {
            assertEquals("ok",new YtLocal.PhoneTransport().request(new URL("https://www.youtube.com/watch?v=abcdefghijk"),null).body);
            assertTrue(closed[0]);assertFalse(captured[0].getInstanceFollowRedirects());assertNull(captured[0].getRequestProperty("Cookie"));assertEquals(8000,captured[0].getConnectTimeout());
        } finally {Net.connections=u->(HttpURLConnection)u.openConnection();}
    }
}
