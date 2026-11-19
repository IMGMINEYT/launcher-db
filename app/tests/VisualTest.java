package com.imgmine.launcher67;
import static org.junit.Assert.*;
import org.junit.*;import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.annotation.*;
import android.app.Activity;import android.graphics.*;import android.view.*;import android.widget.*;
import java.io.*;import java.lang.reflect.*;import java.util.*;import org.json.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=34) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class VisualTest {
 @Test public void renderPanel()throws Exception{
 Activity c=Robolectric.buildActivity(Activity.class).setup().get();String assetPath=System.getProperty("launcher.assetsApk");
 c.getAssets().getClass().getMethod("addAssetPath",String.class).invoke(c.getAssets(),assetPath);
 Ui.init(c);Images.init(c);Panel p=new Panel(c,()->{});
 Method m=Panel.class.getDeclaredMethod("build");m.setAccessible(true);m.invoke(p);
 Field f=Panel.class.getDeclaredField("card");f.setAccessible(true);View card=(View)f.get(p);
 f=Panel.class.getDeclaredField("typeFilter");f.setAccessible(true);f.set(p,6);
 f=Panel.class.getDeclaredField("cmdLoaded");f.setAccessible(true);f.set(p,true);
 f=Panel.class.getDeclaredField("cmdFeed");f.setAccessible(true);List<Addon> entries=(List<Addon>)f.get(p);
 String base=System.getProperty("launcher.root", ".")+"/app/assets/";
 JSONArray arr=new JSONObject(new String(java.nio.file.Files.readAllBytes(new File(base+"commands.json").toPath()),"UTF-8")).getJSONArray("c");
 for(int k=0;k<arr.length();k++){JSONObject j=arr.getJSONObject(k);Cmds.Entry e=new Cmds.Entry();e.id=j.getString("id");e.n=j.getString("n");e.cat=j.getString("cat");e.c=j.getString("c");e.icon=j.getString("icon");e.note=j.optString("note");e.daily=j.optBoolean("daily");entries.add(Cmds.toAddon(e));}
 m=Panel.class.getDeclaredMethod("chipStyle");m.setAccessible(true);m.invoke(p);m=Panel.class.getDeclaredMethod("refilter",boolean.class);m.setAccessible(true);m.invoke(p,true);
 f=Panel.class.getDeclaredField("view");f.setAccessible(true);assertEquals("Daily Use default shows only its 28 templates plus header",29,((List)f.get(p)).size());
 ((ViewGroup)card.getParent()).removeView(card);c.setContentView(card);
 render(card,460,740,System.getProperty("launcher.renderDir", "build/test-renders")+"/v26-panel-native.png");render(card,336,520,System.getProperty("launcher.renderDir", "build/test-renders")+"/v26-compact-native.png");
 }
 static void render(View v,int w,int h,String path)throws Exception{v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h);Bitmap b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);v.draw(new Canvas(b));new File(path).getParentFile().mkdirs();try(FileOutputStream o=new FileOutputStream(path)){assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,o));}}
 @Test public void renderDownloadManagerAndImageViewer()throws Exception{
  Activity c=Robolectric.buildActivity(Activity.class).setup().get();c.getAssets().getClass().getMethod("addAssetPath",String.class).invoke(c.getAssets(),System.getProperty("launcher.assetsApk"));Ui.init(c);Images.init(c);
  Field tf=Downloader.class.getDeclaredField("TASKS");tf.setAccessible(true);Map<String,Downloader.Task> map=(Map)tf.get(null);map.clear();
  String[] labels={"Resource pack - loading", "Village structures addon", "Skyblock survival world", "Nature texture pack", "Long addon title to check narrow screen wrapping"};int[] states={1,0,2,3,4};
  for(int i=0;i<5;i++){Downloader.Task t=new Downloader.Task();t.key="sample"+i+".mcpack";t.name=t.key;t.label=labels[i];t.state=states[i];t.got=12000000;t.total=i==1?-1:24000000;t.bps=1300000;map.put(t.key,t);if(states[i]==3){File file=new File(c.getFilesDir(),"dl/"+t.key);file.getParentFile().mkdirs();try(java.io.RandomAccessFile out=new java.io.RandomAccessFile(file,"rw")){out.setLength(t.total);}}}
  DlActivity dl=Robolectric.buildActivity(DlActivity.class).setup().get();render(dl.findViewById(android.R.id.content),360,640,System.getProperty("launcher.renderDir", "build/test-renders")+"/v26-downloads-native.png");
  Panel p=new Panel(c,()->{});Method build=Panel.class.getDeclaredMethod("build");build.setAccessible(true);build.invoke(p);Method nh=Panel.class.getDeclaredMethod("newHolder");nh.setAccessible(true);Object holder=nh.invoke(p);
  Field af=holder.getClass().getDeclaredField("a");af.setAccessible(true);Addon a=new Addon();a.banner="https://fixture.test/view.png";a.name="Image preview";af.set(holder,a);
  Field im=holder.getClass().getDeclaredField("img");im.setAccessible(true);((ImageView)im.get(holder)).setImageBitmap(BitmapFactory.decodeFile(System.getProperty("launcher.root", ".")+"/app/assets/icons/ic_sword.png"));
  Field st=holder.getClass().getDeclaredField("ist");st.setAccessible(true);((View)st.get(holder)).setVisibility(View.GONE);
  Method tap=Panel.class.getDeclaredMethod("onImageTap",holder.getClass());tap.setAccessible(true);tap.invoke(p,holder);
  android.app.Dialog dialog=org.robolectric.shadows.ShadowDialog.getLatestDialog();assertNotNull(dialog);render(dialog.findViewById(android.R.id.content),360,640,System.getProperty("launcher.renderDir", "build/test-renders")+"/v26-image-native.png");
 }


 @Test public void renderBridgeScreen()throws Exception{
  Activity c=Robolectric.buildActivity(Activity.class).setup().get();
  c.getAssets().getClass().getMethod("addAssetPath",String.class).invoke(c.getAssets(),System.getProperty("launcher.assetsApk"));
  Ui.init(c);Images.init(c);
  StreamChatService.state="CONNECTED";StreamChatService.port=51234;StreamChatService.sent=3;StreamChatService.droppedTotal=0;StreamChatService.chatOn=true;StreamChatService.ytState="YT OFF";
  Panel p=new Panel(c,()->{});
  Method m=Panel.class.getDeclaredMethod("build");m.setAccessible(true);m.invoke(p);
  Field f=Panel.class.getDeclaredField("card");f.setAccessible(true);View card=(View)f.get(p);
  m=Panel.class.getDeclaredMethod("showScreen",int.class);m.setAccessible(true);m.invoke(p,2);
  Field bf=Panel.class.getDeclaredField("bridgeView");bf.setAccessible(true);assertEquals(View.VISIBLE,((View)bf.get(p)).getVisibility());
  ((ViewGroup)card.getParent()).removeView(card);c.setContentView(card);
  render(card,460,740,System.getProperty("launcher.renderDir", "build/test-renders")+"/v28-bridgepanel-native.png");
  StreamChatService.state="OFF";StreamChatService.port=0;StreamChatService.sent=0;}

 @Test public void renderDownloadsScreen()throws Exception{
  Activity c=Robolectric.buildActivity(Activity.class).setup().get();
  c.getAssets().getClass().getMethod("addAssetPath",String.class).invoke(c.getAssets(),System.getProperty("launcher.assetsApk"));
  Ui.init(c);Images.init(c);
  Field tf=Downloader.class.getDeclaredField("TASKS");tf.setAccessible(true);Map<String,Downloader.Task> map=(Map)tf.get(null);map.clear();
  String[] labels={"Resource pack - loading", "Village structures addon", "Skyblock survival world", "Nature texture pack", "Long addon title to check narrow screen wrapping"};int[] states={1,0,2,3,4};
  for(int i=0;i<5;i++){Downloader.Task t=new Downloader.Task();t.key="sample"+i+".mcpack";t.name=t.key;t.label=labels[i];t.state=states[i];t.got=12000000;t.total=i==1?-1:24000000;t.bps=1300000;map.put(t.key,t);if(states[i]==3){File file=new File(c.getFilesDir(),"dl/"+t.key);file.getParentFile().mkdirs();try(java.io.RandomAccessFile out=new java.io.RandomAccessFile(file,"rw")){out.setLength(t.total);}}}
  Panel p=new Panel(c,()->{});
  Method m=Panel.class.getDeclaredMethod("build");m.setAccessible(true);m.invoke(p);
  Field f=Panel.class.getDeclaredField("card");f.setAccessible(true);View card=(View)f.get(p);
  m=Panel.class.getDeclaredMethod("showScreen",int.class);m.setAccessible(true);m.invoke(p,1);
  Field df=Panel.class.getDeclaredField("dlView");df.setAccessible(true);assertEquals(View.VISIBLE,((View)df.get(p)).getVisibility());
  Field lf=Panel.class.getDeclaredField("list");lf.setAccessible(true);assertEquals(View.GONE,((View)lf.get(p)).getVisibility());
  ((ViewGroup)card.getParent()).removeView(card);c.setContentView(card);
  render(card,460,740,System.getProperty("launcher.renderDir", "build/test-renders")+"/v28-downloadspanel-native.png");
  m.invoke(p,0);assertEquals(View.VISIBLE,((View)lf.get(p)).getVisibility());}

 @Test public void renderMainScreen()throws Exception{
  Activity c=Robolectric.buildActivity(MainActivity.class).setup().get();
  String assetPath=System.getProperty("launcher.assetsApk");
  c.getAssets().getClass().getMethod("addAssetPath",String.class).invoke(c.getAssets(),assetPath);
  Ui.init(c);
  c.recreate();
  render(c.findViewById(android.R.id.content),360,1240,System.getProperty("launcher.renderDir", "build/test-renders")+"/v28-main-native.png");}
}
