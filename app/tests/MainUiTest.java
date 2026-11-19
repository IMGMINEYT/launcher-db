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
 @Test public void bundledAddon150VersionAndDependency()throws Exception{
  File f=new File(System.getProperty("launcher.root",".")+"/app/assets/streamchat.mcaddon");
  Map<String,JSONObject> docs=new HashMap<>();
  try(ZipFile z=new ZipFile(f)){Enumeration<? extends ZipEntry> e=z.entries();while(e.hasMoreElements()){
   ZipEntry x=e.nextElement();
   if(x.getName().endsWith(".mcpack"))try(java.util.zip.ZipInputStream nested=new java.util.zip.ZipInputStream(z.getInputStream(x))){
    ZipEntry n;while((n=nested.getNextEntry())!=null)if(n.getName().equals("manifest.json")){
     java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] buf=new byte[1024];int count;while((count=nested.read(buf))>0)out.write(buf,0,count);
     docs.put(x.getName().replace(".mcpack","/manifest.json"),new JSONObject(new String(out.toByteArray(),"UTF-8")));
    }
   }
  }}
  JSONObject bp=docs.get("StreamChatBP/manifest.json"),rp=docs.get("StreamChatRP/manifest.json");assertNotNull(bp);assertNotNull(rp);
  assertEquals("[1,5,0]",bp.getJSONObject("header").getJSONArray("version").toString());
  assertEquals("[1,5,0]",rp.getJSONObject("header").getJSONArray("version").toString());
  for(JSONObject manifest:new JSONObject[]{bp,rp}){
   JSONArray modules=manifest.getJSONArray("modules");for(int i=0;i<modules.length();i++)assertEquals("[1,5,0]",modules.getJSONObject(i).getJSONArray("version").toString());
   assertFalse(manifest.getJSONObject("header").getString("description").contains("(MIT)"));
  }
  boolean found=false;JSONArray deps=bp.getJSONArray("dependencies");for(int i=0;i<deps.length();i++){JSONObject x=deps.getJSONObject(i);if(x.optString("uuid").equals(rp.getJSONObject("header").getString("uuid"))){assertEquals("[1,5,0]",x.getJSONArray("version").toString());found=true;}}assertTrue(found);
 }
}
