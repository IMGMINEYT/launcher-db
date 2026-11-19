package com.imgmine.launcher67;
import static org.junit.Assert.*;
import org.junit.*;import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.annotation.*;import org.json.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class EmojiTransportTest {
 @Test public void commonClustersNeverSplit(){
  for(String cluster:new String[]{"😀","👍🏽","👨‍👩‍👧‍👦","🇮🇳","1️⃣","❤️","é"}){
   for(int cap=0;cap<cluster.length();cap++)assertEquals("cluster split at "+cap,"",Ws.stripCtl(cluster,cap));
   assertEquals(cluster,Ws.stripCtl(cluster,cluster.length()));
   assertEquals("a",Ws.stripCtl("a"+cluster,cluster.length()));
  }
 }
 @Test public void controlsRemovedNormalScriptsPreserved(){
  assertEquals("नमस्ते hello ❤️",Ws.stripCtl("नमस्ते hello ❤️",120));
  assertEquals("ab",Ws.stripCtl("a\n\u00a7b",120));
  assertEquals("xy",Ws.stripCtl("x\ud800y",120));
 }
 @Test public void utf8JsonAndWebsocketKeepEmoji()throws Exception{
  String text="Hi 👨‍👩‍👧‍👦 👍🏽 🇮🇳 ❤️ नमस्ते";
  String cmd=Ws.scriptChat("User😀",text);
  JSONObject payload=new JSONObject(cmd.substring("scriptevent tysc:chat ".length()));
  assertEquals(text,payload.getString("t"));assertEquals("User😀",payload.getString("n"));
  JSONObject outer=new JSONObject(new String(Ws.commandRequest(cmd,"id"),"UTF-8"));
  assertEquals(cmd,outer.getJSONObject("body").getString("commandLine"));
 }
}
