package com.imgmine.launcher67;
import static org.junit.Assert.*;
import org.junit.*;import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.annotation.*;
import org.json.*;import java.io.*;import java.lang.reflect.*;import java.net.*;
/** Opt-in live gate interoperability. Never logs code, token, response body or signed URL.
 * Input is a private UTF-8 file, not a command-line secret. Does not rotate/generate credentials.
 * Readiness here covers an EMPTY protected catalog, not file/Range download support. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class LiveGateTest {
 @Test public void actualJavaClientUnlockAndEmptyCatalog()throws Exception {
  String path=System.getProperty("launcher.vipCodeFile");assertNotNull("private code file required",path);
  String base=System.getProperty("launcher.gate");assertNotNull("explicit HTTPS gate required",base);assertTrue(base.startsWith("https://"));
  String code=new String(java.nio.file.Files.readAllBytes(new File(path).toPath()),"UTF-8").trim();assertFalse("code must exist",code.isEmpty());
  String old=Vip.GATE;boolean enabled=Vip.ENABLED;
  try {
   Vip.GATE=base;Vip.ENABLED=true;int[] status={0};
   String raw=Vip.postCheck(base,code,status);code=null;
   assertEquals("unlock HTTP status",200,status[0]);assertNotNull("unlock response",raw);
   JSONObject response=new JSONObject(raw);String token=response.getString("token");long exp=response.getLong("exp");raw=null;
   assertEquals("client expiry parsing",exp,Vip.tokenExp(token));assertTrue("token unexpired",exp>System.currentTimeMillis()/1000);
   Method cat=Api.class.getDeclaredMethod("gateCatalog",String.class);cat.setAccessible(true);
   byte[] bytes=(byte[])cat.invoke(null,token);assertNotNull("authorized catalog response",bytes);
   java.util.List<Addon> catalog=Api.parse(new String(bytes,"UTF-8"));assertTrue("expected rights-qualified catalog empty",catalog.isEmpty());
   byte[] denied=(byte[])cat.invoke(null,"invalid-test-token");assertNull("invalid token denied",denied);assertTrue("auth failure classified",Api.lastFailAuth);
   HttpURLConnection file=(HttpURLConnection)new URL(base+"/vip/file/nonexistent?s=invalid&e=1").openConnection();
   file.setRequestProperty("X-VIP-Token",token);file.setConnectTimeout(5000);file.setReadTimeout(7000);
   try{assertEquals("authenticated invalid file signature denied",404,file.getResponseCode());}finally{file.disconnect();}
   token=null;
  } finally {Vip.GATE=old;Vip.ENABLED=enabled;}
 }
}
