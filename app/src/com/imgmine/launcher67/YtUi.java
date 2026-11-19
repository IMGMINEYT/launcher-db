package com.imgmine.launcher67;
import android.content.*;
import android.view.*;
import android.widget.*;

/** Shared YouTube controls used on main setup and inside the existing bridge scroll view.
 * Request implementation remains in YtLocal; this view changes preferences only on SAVE. */
final class YtUi {
    static String source(SharedPreferences p) {
        // Keep explicit source choices. Existing API users with a key stay on API;
        // new/no-key installs start with the phone-local mode the owner requested first.
        if(p.contains("stream_yt_source"))return p.getString("stream_yt_source","local");
        return p.getString("stream_yt_key_enc","").isEmpty() && p.getString("stream_yt_key","").isEmpty()?"local":"api";
    }
    static LinearLayout controls(final Context ctx) {
        final SharedPreferences prefs=ctx.getSharedPreferences("ui",Context.MODE_PRIVATE);
        LinearLayout box=new LinearLayout(ctx);box.setOrientation(LinearLayout.VERTICAL);
        final boolean[] localChoice = {"local".equals(YtUi.source(prefs))};
        final TextView ytSource = Ui.button(ctx, localChoice[0] ? "YOUTUBE: PHONE-LOCAL" : "YOUTUBE: API KEY", 0xFFAD2424, 0xFFFFFFFF);
        ytSource.setTextSize(11);
        box.addView(ytSource, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final TextView sourceNote = label(ctx, "Tap YOUTUBE to switch source. Phone-local works without an API key. Stops on login, consent or access limits.", 12, Ui.MUTE);
        sourceNote.setTypeface(null); box.addView(sourceNote);
        final EditText ytUrl = input(ctx, localChoice[0] ? "YouTube live URL / @handle" : "YouTube live URL (API)", prefs.getString("stream_yt_url", ""));
        LinearLayout.LayoutParams yu = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        yu.topMargin = Ui.dp(8);
        box.addView(ytUrl, yu);
        final EditText ytKey = input(ctx, "YouTube Data API key", "");
        ytKey.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams yk = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        yk.topMargin = Ui.dp(6);
        box.addView(ytKey, yk);
        ytKey.setVisibility(localChoice[0] ? View.GONE : View.VISIBLE);
        ytSource.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                localChoice[0] = !localChoice[0];
                ytSource.setText(Ui.fix(localChoice[0] ? "YOUTUBE: PHONE-LOCAL" : "YOUTUBE: API KEY"));
                ytKey.setVisibility(localChoice[0] ? View.GONE : View.VISIBLE);
                ytUrl.setHint(localChoice[0] ? "YouTube live URL / @handle" : "YouTube live URL (API)");
            }
        });
        TextView ytSave = Ui.button(ctx, "SAVE YOUTUBE", 0xFFAD2424, 0xFFFFFFFF);
        ytSave.setTextSize(12);
        ytSave.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String k = ytKey.getText().toString().trim();
                android.content.SharedPreferences.Editor ed = prefs.edit()
                        .putString("stream_yt_url", ytUrl.getText().toString().trim())
                        .putString("stream_yt_source", localChoice[0] ? "local" : "api")
                        .remove("stream_local_halt").remove("stream_local_halt_url");
                if (k.length() > 0) {
                    try {
                        ed.putString("stream_yt_key_enc", KeyBox.protect(ctx, k)).remove("stream_yt_key");
                        ytKey.setText("");
                    } catch (Exception e) {
                        Toast.makeText(ctx, "Secure storage unavailable, key not saved.", Toast.LENGTH_LONG).show();
                        return;
                    }
                }
                ed.apply();
                StreamChatService.youtubeSettingsChanged();
                Toast.makeText(ctx, "Saved. Starts/restarts selected source. Rate-limit wait is kept.", Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams ys = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ys.topMargin = Ui.dp(6);
        box.addView(ytSave, ys);

        return box;
    }
    private static TextView label(Context c,String value,int size,int color){
        TextView t=new TextView(c);t.setText(value);t.setTextSize(size);t.setTextColor(color);return t;
    }
    private static EditText input(Context c,String hint,String value){
        EditText e=new EditText(c);e.setHint(hint);e.setHintTextColor(Ui.MUTE);e.setText(value);e.setTextColor(Ui.INK);
        e.setTextSize(13);e.setSingleLine(true);e.setBackground(Ui.panel(Ui.CARD));e.setPadding(Ui.dp(10),Ui.dp(8),Ui.dp(10),Ui.dp(8));return e;
    }
    static TextView addonButton(final Context c) {
        TextView b=Ui.button(c,"DOWNLOAD / INSTALL CHAT ADDON",0xFFAD2424,0xFFFFFFFF);b.setTextSize(11);
        b.setOnClickListener(v->installAddon(c));return b;
    }
    static void installAddon(Context c) {
        try {
            byte[] bytes;try(java.io.InputStream in=c.getAssets().open("streamchat.mcaddon")){bytes=Api.readAll(in,16<<20);}
            Downloader.saveLocal(c.getApplicationContext(),"StreamChat.mcaddon","Stream Chat addon",bytes);
            Downloader.install(c,"StreamChat.mcaddon");
        } catch(Exception e){Toast.makeText(c,"Addon could not be saved. Try again.",Toast.LENGTH_LONG).show();}
    }
}
