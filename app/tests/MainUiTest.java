package com.imgmine.launcher67;
import static org.junit.Assert.*;
import org.junit.*;import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.annotation.*;
import android.app.Activity;import android.view.*;import android.widget.*;
import java.io.*;import java.util.*;import java.util.zip.*;import org.json.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=34) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MainUiTest {
 private TextView find(View v,String prefix){if(v instanceof TextView&&((TextView)v).getText().toString().startsWith(prefix))return (TextView)v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){TextView t=find(g.getChildAt(i),prefix);if(t!=null)return t;}}return null;}
 private int images(View v){int n=v instanceof ImageView?1:0;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)n+=images(g.getChildAt(i));}return n;}
 @Test public void mainActionsAndStreamToggle()throws Exception{
  Activity a=Robolectric.buildActivity(MainActivity.class).setup().get(); View root=a.findViewById(android.R.id.content);
  assertNotNull(find(root,"START + OPEN MINECRAFT"));assertNotNull(find(root,"ENTER VIP CODE"));assertEquals(0,images(root));
  TextView toggle=find(root,"STREAM CHAT");assertNotNull(toggle); TextView save=find(root,"SAVE YOUTUBE");assertNotNull(save);assertFalse(save.isShown());
  String d=System.getProperty("launcher.renderDir","/tmp/renders");VisualTest.render(root,360,800,d+"/main-simple.png");
  toggle.performClick();assertTrue(save.isShown());VisualTest.render(root,360,1380,d+"/main-stream-expanded.png");
  toggle.performClick();assertFalse(save.isShown());
 }
 @Test public void bundledAddon141VersionAndDependency()throws Exception{
  File f=new File(System.getProperty("launcher.root",".")+"/app/assets/streamchat.mcaddon");
  Map<String,JSONObject> docs=new HashMap<>();try(ZipFile z=new ZipFile(f)){Enumeration<? extends ZipEntry> e=z.entries();while(e.hasMoreElements()){ZipEntry x=e.nextElement();if(!x.getName().endsWith("manifest.json"))continue;try(InputStream in=z.getInputStream(x)){docs.put(x.getName(),new JSONObject(new String(Api.readAll(in,65536),"UTF-8")));}}}
  JSONObject bp=docs.get("StreamChatBP/manifest.json"),rp=docs.get("StreamChatRP/manifest.json");assertNotNull(bp);assertNotNull(rp);
  assertEquals("[1,4,1]",bp.getJSONObject("header").getJSONArray("version").toString());
  assertEquals("[1,4,1]",rp.getJSONObject("header").getJSONArray("version").toString());
  boolean found=false;JSONArray deps=bp.getJSONArray("dependencies");for(int i=0;i<deps.length();i++){JSONObject x=deps.getJSONObject(i);if(x.optString("uuid").equals(rp.getJSONObject("header").getString("uuid"))){assertEquals("[1,4,1]",x.getJSONArray("version").toString());found=true;}}assertTrue(found);
 }
}
