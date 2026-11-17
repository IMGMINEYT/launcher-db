package com.imgmine.launcher67;
import android.app.*;
import android.content.Intent;
import android.os.*;

/** Keeps active transfers visible to Android while the launcher is behind Minecraft.
 * Force-stop/process death cannot be defeated; persisted partials become resumable paused entries. */
public final class DownloadService extends Service {
    private final Handler h=new Handler(Looper.getMainLooper());
    private final Runnable tick=new Runnable(){public void run(){if(!Downloader.busy()){stopForeground(true);stopSelf();}else h.postDelayed(this,2000);}};
    public void onCreate(){super.onCreate();
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT>=26)nm.createNotificationChannel(new NotificationChannel("downloads","Downloads",NotificationManager.IMPORTANCE_LOW));
        Intent i=new Intent(this,DlActivity.class);
        PendingIntent pi=PendingIntent.getActivity(this,8,i,PendingIntent.FLAG_UPDATE_CURRENT|(Build.VERSION.SDK_INT>=23?PendingIntent.FLAG_IMMUTABLE:0));
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,"downloads"):new Notification.Builder(this);
        startForeground(68,b.setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("67 Launcher downloads").setContentText("Tap to view downloads").setContentIntent(pi).setOngoing(true).build());
    }
    public int onStartCommand(Intent i,int f,int id){h.removeCallbacks(tick);h.postDelayed(tick,2000);return START_NOT_STICKY;}
    public void onDestroy(){h.removeCallbacks(tick);super.onDestroy();}
    public IBinder onBind(Intent i){return null;}
}
