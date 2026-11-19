package com.imgmine.launcher67;

import java.io.*;
import java.net.*;
import java.util.*;
import org.json.*;

/** Independently written, experimental phone-local web chat adapter.
 * Enabled only by an explicit experimental source choice. No login imports, invented consent cookies,
 * official API keys, Java desktop HTTP dependency, or automatic access retries.
 * Network responses and fixtures share exactly the same state/parser path.
 */
final class YtLocal {
    enum State { READY, LOGIN_REQUIRED, CONSENT_REQUIRED, RATE_LIMITED, FORBIDDEN,
        NOT_LIVE, CHAT_UNAVAILABLE, BAD_RESPONSE, NETWORK_ERROR, END }
    static final class Failure extends IOException {
        final State state; final long retryAfterMs;
        Failure(State state) { this(state, 0); }
        Failure(State state, long delay) { super(state.name()); this.state=state; retryAfterMs=delay; }
    }
    static final class Reply {
        int status; String body="", retryAfter, location;
    }
    interface Transport { Reply request(URL url, String body) throws IOException; }
    static final class PhoneTransport implements Transport {
        public Reply request(URL url, String body) throws IOException {
            checkedUrl(url);
            HttpURLConnection c=Net.connections.connect(url);
            try {
                c.setConnectTimeout(8000); c.setReadTimeout(10000);
                c.setInstanceFollowRedirects(false);
                c.setRequestProperty("User-Agent", "67Launcher-ExperimentalChat/1.0");
                c.setRequestProperty("Accept", "application/json,text/html");
                if(body!=null) {
                    byte[] data=body.getBytes("UTF-8"); c.setRequestMethod("POST"); c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                    c.setFixedLengthStreamingMode(data.length);
                    try(OutputStream out=c.getOutputStream()) { out.write(data); }
                }
                Reply r=new Reply(); r.status=c.getResponseCode();
                r.retryAfter=c.getHeaderField("Retry-After"); r.location=c.getHeaderField("Location");
                InputStream in=r.status<400?c.getInputStream():c.getErrorStream();
                if(in!=null) try(InputStream source=in) { r.body=new String(Api.readAll(source,2<<20),"UTF-8"); }
                return r;
            } finally { c.disconnect(); }
        }
    }
    static final class Source {
        final URL url; Source(URL u) { url=u; }
    }
    static Source source(String raw) throws Failure {
        if(raw==null) throw new Failure(State.BAD_RESPONSE);
        String s=raw.trim();
        try {
            if(s.matches("[A-Za-z0-9_-]{11}")) return new Source(new URL("https://www.youtube.com/watch?v="+s));
            if(s.matches("@[A-Za-z0-9_.-]{1,100}")) return new Source(new URL("https://www.youtube.com/"+s+"/live"));
            URL u=new URL(s); checkedUrl(u);
            String path=u.getPath();
            if(path.matches("/@[A-Za-z0-9_.-]{1,100}(/live)?/?")) {
                String p=path.replaceAll("/live/?$", "").replaceAll("/$", "");
                return new Source(new URL("https://www.youtube.com"+p+"/live"));
            }
            String id=null;
            if("youtu.be".equals(u.getHost())) id=path.substring(1);
            else if(path.equals("/watch")) {
                for(String pair:(u.getQuery()==null?"":u.getQuery()).split("&")) if(pair.startsWith("v=")) id=pair.substring(2);
            } else if(path.startsWith("/live/")) id=path.substring(6);
            if(id!=null && id.matches("[A-Za-z0-9_-]{11}")) return new Source(new URL("https://www.youtube.com/watch?v="+id));
        } catch(IOException | RuntimeException e) { if(e instanceof Failure) throw (Failure)e; }
        throw new Failure(State.BAD_RESPONSE);
    }
    static void checkedUrl(URL u) throws Failure {
        String h=u.getHost();
        if(!"https".equals(u.getProtocol()) || u.getUserInfo()!=null || (u.getPort()!=-1 && u.getPort()!=443)
                || !(h.equals("www.youtube.com") || h.equals("youtube.com") || h.equals("youtu.be")))
            throw new Failure(State.FORBIDDEN);
    }
    private final Transport transport;
    private final LinkedHashSet<String> seen=new LinkedHashSet<>();
    private String continuation, clientVersion;
    private boolean first=true, stopped;
    private State stopState;
    YtLocal(Transport transport) { this.transport=transport; }
    void open(String raw) throws IOException {
        // Each explicit open starts a fresh session. Caller owns manual restart.
        continuation=null; clientVersion=null; seen.clear(); first=true; stopped=false; stopState=null;
        try {
            Reply r=transport.request(source(raw).url,null);
            String body=check(r); checkPage(body);
            JSONObject data=embedded(body,"ytInitialData");
            if(data==null) throw new Failure(State.BAD_RESPONSE);
            JSONObject renderer=find(data,"liveChatRenderer",0);
            if(renderer==null || renderer.optBoolean("isReplay")) throw new Failure(State.CHAT_UNAVAILABLE);
            continuation=token(renderer);
            if(continuation==null) throw new Failure(State.CHAT_UNAVAILABLE);
            // Use the version delivered by the page, never a hard-coded browser identity.
            clientVersion=stringSetting(body,"INNERTUBE_CLIENT_VERSION");
            if(clientVersion==null) throw new Failure(State.BAD_RESPONSE);
        } catch(IOException e) { halt(e); throw e; }
    }
    static final class Batch {
        final List<Yt.Msg> messages=new ArrayList<>(); long waitMs=5000; boolean ended;
    }
    Batch next() throws IOException {
        if(stopped) throw new Failure(stopState==null?State.END:stopState);
        if(continuation==null || clientVersion==null) throw new Failure(State.BAD_RESPONSE);
        try {
            JSONObject client=new JSONObject().put("clientName","WEB").put("clientVersion",clientVersion);
            JSONObject request=new JSONObject().put("context",new JSONObject().put("client",client))
                    .put("continuation",continuation);
            String body=check(transport.request(new URL("https://www.youtube.com/youtubei/v1/live_chat/get_live_chat"),request.toString()));
            JSONObject response=new JSONObject(body); checkJson(response);
            Batch b=parse(response,seen,!first);
            String t=token(response); first=false;
            if(t==null) { b.ended=true; stopped=true; stopState=State.END; } else continuation=t;
            return b;
        } catch(JSONException e) { Failure f=new Failure(State.BAD_RESPONSE); halt(f); throw f;
        } catch(IOException e) { halt(e); throw e; }
    }
    private void halt(IOException e) { stopped=true; stopState=e instanceof Failure?((Failure)e).state:State.NETWORK_ERROR; }
    private static String check(Reply r) throws Failure {
        if(r.status==429) throw new Failure(State.RATE_LIMITED,retryMs(r.retryAfter));
        if(r.status==401) throw new Failure(State.LOGIN_REQUIRED);
        if(r.status==403) throw new Failure(State.FORBIDDEN);
        if(r.status>=300 && r.status<400) {
            // Do not follow consent/login redirects or silently switch destination.
            String loc=r.location==null?"":r.location;
            throw new Failure(loc.contains("consent.youtube.com")||loc.contains("consent.google.com")?State.CONSENT_REQUIRED:
                loc.contains("accounts.google.com")?State.LOGIN_REQUIRED:State.FORBIDDEN);
        }
        if(r.status!=200) throw new Failure(State.NETWORK_ERROR);
        if(r.body==null || r.body.length()>(2<<20)) throw new Failure(State.BAD_RESPONSE);
        return r.body;
    }
    static long retryMs(String raw) {
        if(raw==null)return 0;
        try { return Math.max(0,Math.min(31536000L,Long.parseLong(raw.trim())))*1000L; } catch(Exception ignored) {}
        try { java.text.SimpleDateFormat f=new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz",Locale.US);
            f.setLenient(false); return Math.max(0,f.parse(raw).getTime()-System.currentTimeMillis()); } catch(Exception ignored) { return 0; }
    }
    static void checkPage(String body) throws Failure {
        if(body.contains("consent.youtube.com") && !body.contains("ytInitialData")) throw new Failure(State.CONSENT_REQUIRED);
        JSONObject player=embedded(body,"ytInitialPlayerResponse");
        if(player!=null) {
            checkJson(player);
            JSONObject details=player.optJSONObject("videoDetails");
            if(details!=null && !details.optBoolean("isLiveContent",true)) throw new Failure(State.NOT_LIVE);
            JSONObject micro=player.optJSONObject("microformat");
            JSONObject pm=micro==null?null:micro.optJSONObject("playerMicroformatRenderer");
            JSONObject live=pm==null?null:pm.optJSONObject("liveBroadcastDetails");
            if(live!=null && live.has("isLiveNow") && !live.optBoolean("isLiveNow")) throw new Failure(State.NOT_LIVE);
        }
    }
    static void checkJson(JSONObject o) throws Failure {
        JSONObject p=o.optJSONObject("playabilityStatus");
        if(p!=null) {
            String status=p.optString("status");
            if(status.equals("LOGIN_REQUIRED") || status.equals("AGE_CHECK_REQUIRED")) throw new Failure(State.LOGIN_REQUIRED);
            if(!status.isEmpty() && !status.equals("OK")) throw new Failure(State.FORBIDDEN);
        }
        if(o.has("error")) {
            JSONObject e=o.optJSONObject("error"); int code=e==null?0:e.optInt("code");
            throw new Failure(code==401?State.LOGIN_REQUIRED:code==429?State.RATE_LIMITED:State.FORBIDDEN);
        }
    }
    static JSONObject embedded(String html,String variable) {
        // Balanced JSON extraction supports braces/quotes inside strings. No eval/JS execution.
        int pos=0;
        while((pos=html.indexOf(variable,pos))>=0) {
            int p=pos+variable.length(); pos=p;
            while(p<html.length() && (Character.isWhitespace(html.charAt(p)) || "\"'=:\"".indexOf(html.charAt(p))>=0))p++;
            if(p>=html.length() || html.charAt(p)!='{')continue;
            int depth=0; boolean str=false,esc=false;
            for(int i=p;i<html.length();i++) {
                char c=html.charAt(i);
                if(str) { if(esc)esc=false; else if(c=='\\')esc=true; else if(c=='\"')str=false; }
                else if(c=='\"')str=true;
                else if(c=='{')depth++;
                else if(c=='}' && --depth==0) { try { return new JSONObject(html.substring(p,i+1)); } catch(Exception e) { break; } }
            }
        }
        return null;
    }
    static String stringSetting(String html,String name) {
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("\\\""+name+"\\\"\\s*:\\s*\\\"([^\\\"]{1,100})\\\"").matcher(html);
        return m.find()?m.group(1):null;
    }
    static JSONObject find(Object value,String key,int depth) {
        if(depth>64)return null;
        if(value instanceof JSONObject) {
            JSONObject o=(JSONObject)value; JSONObject hit=o.optJSONObject(key); if(hit!=null)return hit;
            Iterator<String> keys=o.keys(); while(keys.hasNext()) { JSONObject got=find(o.opt(keys.next()),key,depth+1); if(got!=null)return got; }
        } else if(value instanceof JSONArray) {
            JSONArray a=(JSONArray)value; for(int i=0;i<a.length();i++) { JSONObject got=find(a.opt(i),key,depth+1); if(got!=null)return got; }
        }
        return null;
    }
    static String token(JSONObject value) {
        for(String key:new String[]{"timedContinuationData","invalidationContinuationData","reloadContinuationData"}) {
            JSONObject o=find(value,key,0); if(o!=null) { String t=o.optString("continuation"); if(!t.isEmpty() && t.length()<16384)return t; }
        }
        return null;
    }
    static Batch parse(JSONObject o,LinkedHashSet<String> seen,boolean deliver) throws Failure {
        checkJson(o); Batch b=new Batch();
        JSONObject contents=o.optJSONObject("continuationContents");
        JSONObject chat=contents==null?null:contents.optJSONObject("liveChatContinuation");
        if(chat==null) throw new Failure(State.BAD_RESPONSE);
        JSONObject timing=find(chat,"timedContinuationData",0);
        if(timing==null)timing=find(chat,"invalidationContinuationData",0);
        if(timing!=null)b.waitMs=Math.max(5000,timing.optLong("timeoutMs",5000));
        JSONArray actions=chat.optJSONArray("actions");
        if(actions==null)return b;
        for(int i=0;i<actions.length();i++) {
            JSONObject a=actions.optJSONObject(i); if(a==null)continue;
            JSONObject add=a.optJSONObject("addChatItemAction"); JSONObject item=add==null?null:add.optJSONObject("item");
            if(item==null)continue;
            JSONObject r=item.optJSONObject("liveChatTextMessageRenderer");
            if(r==null)r=item.optJSONObject("liveChatPaidMessageRenderer");
            if(r==null)continue;
            String id=r.optString("id"); if(id.isEmpty() || id.length()>256 || !seen.add(id))continue;
            while(seen.size()>5000)seen.remove(seen.iterator().next());
            String author=text(r.optJSONObject("authorName")),msg=text(r.optJSONObject("message"));
            if(deliver && !author.isEmpty() && !msg.isEmpty() && b.messages.size()<200) {
                Yt.Msg m=new Yt.Msg();m.author=author;m.text=msg;b.messages.add(m);
            }
        }
        return b;
    }
    static String text(JSONObject o) {
        if(o==null)return "";
        if(o.has("simpleText"))return o.optString("simpleText");
        StringBuilder s=new StringBuilder();JSONArray a=o.optJSONArray("runs");
        if(a!=null) for(int i=0;i<a.length() && s.length()<8192;i++) {
            JSONObject run=a.optJSONObject(i);if(run==null)continue;
            if(run.has("text"))s.append(run.optString("text"));
            else { JSONObject e=run.optJSONObject("emoji");JSONArray shortcuts=e==null?null:e.optJSONArray("shortcuts");
                if(shortcuts!=null && shortcuts.length()>0)s.append(shortcuts.optString(0));
                else if(e!=null) { String id=e.optString("emojiId");s.append(id.length()>0 && id.length()<=32?id:"[emoji]"); }
            }
        }
        return s.toString();
    }
}
