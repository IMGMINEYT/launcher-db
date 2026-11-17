package com.imgmine.launcher67;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Download manager: queued/running/paused/failed/done downloads + the offline library of finished files. */
public final class DlActivity extends Activity {
    private ListView list;
    private TextView head;
    private Ad ad;
    private final List<Downloader.Task> rows = new ArrayList<>();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        head = text("", 14, Ui.INK);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.dp(14), Ui.dp(10), Ui.dp(14), Ui.dp(10));
        root.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        list = new ListView(this);
        list.setDivider(null);
        ad = new Ad();
        list.setAdapter(ad);
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        Downloader.setWatch(new Downloader.Watch() { public void changed() { refresh(); } });
    }

    @Override protected void onResume() { super.onResume(); refresh(); }
    @Override protected void onDestroy() { Downloader.setWatch(null); super.onDestroy(); }

    private TextView text(String s, float sp, int c) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(c);
        return t;
    }

    private void refresh() {
        rows.clear();
        rows.addAll(Downloader.tasks(this));
        long used = Downloader.storageUsed(this);
        head.setText(rows.isEmpty() ? "No downloads yet"
                : rows.size() + " download" + (rows.size() == 1 ? "" : "s") + "  -  " + mb(used) + " stored  -  " + mb(getFilesDir().getUsableSpace()) + " free");
        ad.notifyDataSetChanged();
    }

    static String mb(long b) {
        if (b >= 1048576) return String.format(Locale.US, "%.1f MB", b / 1048576.0);
        return Math.max(0, b / 1024) + " KB";
    }

    static String speed(Downloader.Task t) {
        if (t.state != Downloader.RUNNING || t.bps <= 1) return "";
        StringBuilder s = new StringBuilder("  ").append(mb((long) t.bps)).append("/s");
        long eta = t.etaSec();
        if (eta >= 0) s.append("  -  ").append(eta < 60 ? eta + "s left" : (eta / 60) + "m left");
        return s.toString();
    }

    static String stateText(Downloader.Task t) {
        switch (t.state) {
            case Downloader.QUEUED: return "Waiting...";
            case Downloader.RUNNING: {
                int p = t.pct();
                return (p >= 0 ? p + "%" : mb(t.got)) + speed(t);
            }
            case Downloader.PAUSED: return "Paused" + (t.pct() >= 0 ? " at " + t.pct() + "%" : "");
            case Downloader.DONE: return "Done" + (t.total > 0 ? "  -  " + mb(t.total) : "");
            case Downloader.FAILED: return "Failed - tap RETRY";
            case Downloader.CANCELED: return "Canceled";
            default: return "";
        }
    }

    private void confirmDelete(final Downloader.Task t){
        new android.app.AlertDialog.Builder(this).setTitle("Delete download?").setMessage(t.label+" will be removed from this app only. Your Minecraft worlds are untouched.")
        .setNegativeButton("Cancel",null).setPositiveButton("Delete",(d,w)->{Downloader.remove(this,t.key,true);refresh();}).show();
    }
    private final class Ad extends BaseAdapter {
        public int getCount() { return rows.size(); }
        public Object getItem(int i) { return rows.get(i); }
        public long getItemId(int i) { return i; }
        private final class Row {
            LinearLayout box,bar;TextView name,st;android.widget.ProgressBar pr;String key="";int state=-1;
        }
        public View getView(int i, View cv, ViewGroup pg) {
            Row r;
            if(cv==null){
                r=new Row();r.box=new LinearLayout(DlActivity.this);r.box.setOrientation(LinearLayout.VERTICAL);
                r.box.setPadding(Ui.dp(14),Ui.dp(8),Ui.dp(14),Ui.dp(8));
                r.name=text("",15,Ui.INK);r.name.setMaxLines(2);r.box.addView(r.name);
                r.st=text("",13,Ui.MUTE);r.st.setPadding(0,Ui.dp(2),0,0);r.box.addView(r.st);
                r.pr=new android.widget.ProgressBar(DlActivity.this,null,android.R.attr.progressBarStyleHorizontal);r.pr.setMax(100);r.box.addView(r.pr);
                r.bar=new LinearLayout(DlActivity.this);r.bar.setPadding(0,Ui.dp(6),0,0);r.box.addView(r.bar);r.box.setTag(r);cv=r.box;
            }else r=(Row)cv.getTag();
            final Downloader.Task t=rows.get(i);
            r.name.setText(t.label==null||t.label.isEmpty()?t.name:t.label);
            r.st.setText(stateText(t)+(t.error.isEmpty()?"":"  -  "+t.error));r.st.setTextColor(t.state==Downloader.FAILED?0xFFFF9F68:Ui.MUTE);
            r.pr.setVisibility(t.state==Downloader.RUNNING||t.state==Downloader.QUEUED?View.VISIBLE:View.GONE);
            r.pr.setIndeterminate(t.total<=0);r.pr.setProgress(Math.max(0,t.pct()));
            if(!r.key.equals(t.key)||r.state!=t.state){r.key=t.key;r.state=t.state;r.bar.removeAllViews();addBtn(r.bar,t);}
            return cv;
        }

        private void addBtn(LinearLayout bar, final Downloader.Task t) {
            switch (t.state) {
                case Downloader.RUNNING:
                case Downloader.QUEUED:
                    btn(bar, "PAUSE", new View.OnClickListener() { public void onClick(View v) { Downloader.pause(DlActivity.this, t.key); refresh(); } });
                    btn(bar, "CANCEL", new View.OnClickListener() { public void onClick(View v) { Downloader.cancel(DlActivity.this, t.key); refresh(); } });
                    break;
                case Downloader.PAUSED:
                    btn(bar, "RESUME", new View.OnClickListener() { public void onClick(View v) { Downloader.resume(DlActivity.this, t.key); refresh(); } });
                    btn(bar, "CANCEL", new View.OnClickListener() { public void onClick(View v) { Downloader.cancel(DlActivity.this, t.key); refresh(); } });
                    break;
                case Downloader.FAILED:
                    btn(bar, "RETRY", new View.OnClickListener() { public void onClick(View v) { Downloader.retry(DlActivity.this, t.key); refresh(); } });
                    btn(bar, "DELETE", new View.OnClickListener() { public void onClick(View v) { confirmDelete(t); } });
                    break;
                case Downloader.CANCELED:
                    btn(bar, "RETRY", new View.OnClickListener() { public void onClick(View v) { Downloader.retry(DlActivity.this, t.key); refresh(); } });
                    btn(bar, "DELETE", new View.OnClickListener() { public void onClick(View v) { confirmDelete(t); } });
                    break;
                case Downloader.DONE:
                    btn(bar, "SHARE", new View.OnClickListener(){public void onClick(View v){
                        Uri u=Uri.parse("content://"+ShareProvider.AUTH+"/dl/"+Uri.encode(t.key));
                        Intent i=new Intent(Intent.ACTION_SEND).setType("application/octet-stream");i.putExtra(Intent.EXTRA_STREAM,u);i.setClipData(android.content.ClipData.newRawUri("Pack",u));i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(i,"Share downloaded pack"));
                    }});
                    btn(bar, "INSTALL", new View.OnClickListener() { public void onClick(View v) { Downloader.install(DlActivity.this, t.key); } });
                    btn(bar, "DELETE", new View.OnClickListener() { public void onClick(View v) {
                        confirmDelete(t);
                    } });
                    break;
            }
        }

        private void btn(LinearLayout bar, String s, View.OnClickListener l) {
            TextView b = text(s, 12, Ui.GREEN);
            b.setPadding(Ui.dp(8), Ui.dp(6), Ui.dp(8), Ui.dp(6));
            b.setOnClickListener(l);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(8);
            bar.addView(b, lp);
        }
    }
}
