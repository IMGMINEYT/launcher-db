package com.imgmine.launcher67;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.*;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.util.*;

/** The small draggable, touch-modal addon window (native UI). */
final class Panel {
    private final Context ctx;
    private final WindowManager wm;
    private final SharedPreferences sp;
    private final Runnable onClosed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private LinearLayout card;
    private ListView list;
    private TextView state, clear;
    private EditText search;
    private TextView[] chips = new TextView[7];
    private static final String[] KEYS = {"", "addon", "world", "texture", "skin", "seed", "command"};
    private boolean shown;
    private int cw, ch, typeFilter;
    private List<Addon> all = new ArrayList<>(), view = new ArrayList<>();
    private final Set<String> open = new HashSet<>();
    private final Map<String, Integer> prog = new HashMap<>();
    private final Set<String> done = new HashSet<>();
    private BaseAdapter adapter;
    private boolean loading;
    private String userVer;

    // endless feed state (no search text): MCPEDL pages per type id
    private final Map<Integer, Integer> nextPage = new HashMap<>();
    private final Set<Integer> endedTypes = new HashSet<>();
    private final Set<String> seenIds = new HashSet<>();
    private int ninePage = 1;
    private boolean nineEnded, feedLoading, feedFailed;
    private TextView foot;
    private List<Addon> feed = new ArrayList<>();   // MCPEDL/9mc items, in arrival order
    private List<Addon> labyFeed = new ArrayList<>();
    private boolean labyDone;
    // SEEDS tab state (WiseHosting, Bedrock-only)
    private HorizontalScrollView seedRow, cmdRow, allRow;
    private int allMode;
    private final java.util.Map<Integer,java.util.List<Addon>> sortedFeed=new java.util.HashMap<>();
    private final java.util.Map<Integer,Integer> sortedPage=new java.util.HashMap<>();
    private final java.util.Set<Integer> sortedEnded=new java.util.HashSet<>();
    private final java.util.Set<String> failedSources=new java.util.HashSet<>();
    private final java.util.Set<String> sourceBusy=new java.util.HashSet<>();
    private String cmdCat = "daily";
    private final List<Addon> cmdFeed = new ArrayList<>();
    private boolean cmdLoaded;
    private String seedTag;
    private boolean seedDone;
    private List<Addon> seedFeed = new ArrayList<>();

    // in-panel screens: 0 = addon feed, 1 = downloads, 2 = stream chat bridge
    private int screen;
    private LinearLayout searchBox;
    private HorizontalScrollView chipsRow;
    private FrameLayout bodyBox;
    private View dlView, bridgeView;
    private TextView dlHead;
    private DlAd dlAd;
    private final List<Downloader.Task> dlRows = new ArrayList<>();
    private TextView brStatus, brPort, brYt, brErr, brChatBtn, brModeBtn;

    /** Button that fills with progress while downloading; red dot when the addon declares no support for the user's game version. */
    private final class PBtn extends TextView {
        int pct = -1;
        boolean dot;
        PBtn(Context c) { super(c); }
        @Override protected void onDraw(Canvas c) {
            if (pct >= 0) {
                Paint p = new Paint();
                p.setColor(Ui.GREEN_D);
                int in = Ui.dp(2);
                c.drawRect(in, in, in + (getWidth() - 2 * in) * pct / 100f, getHeight() - in, p);
            }
            super.onDraw(c);
            if (dot) {
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                int r = Ui.dp(4), x = getWidth() - r - Ui.dp(2), y = r + Ui.dp(1);
                p.setColor(0xFF000000);
                c.drawCircle(x, y, r, p);
                p.setColor(Ui.RED);
                c.drawCircle(x, y, r - Ui.dp(1.5f), p);
            }
        }
    }

    /** Pixel magnifier. */
    private final class Mag extends View {
        final String[] g = {"..XXXX....", ".X....X...", "X......X..", "X......X..", "X......X..", ".X....X...", "..XXXXXX..", "......XXX.", ".......XXX", "........XX"};
        final Paint p = new Paint();
        Mag(Context c) { super(c); p.setColor(Ui.MUTE); }
        @Override protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight()) / 10f;
            for (int y = 0; y < 10; y++) for (int x = 0; x < 10; x++)
                if (g[y].charAt(x) == 'X') c.drawRect(x * s, y * s, (x + 1) * s, (y + 1) * s, p);
        }
    }

    private final class Holder { LinearLayout row; ImageView img; TextView name, meta, desc, q, ist; android.widget.ProgressBar ibar; PBtn dl; Addon a; }

    Panel(Context c, Runnable onClosed) {
        this.ctx = c;
        this.wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        this.sp = c.getSharedPreferences("ui", Context.MODE_PRIVATE);
        this.onClosed = onClosed;
        Images.init(c);
    }

    boolean isShown() { return shown; }

    private DisplayMetrics real() {
        DisplayMetrics m = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(m);
        return m;
    }

    private TextView text(String t, int sp, int color) {
        TextView v = new TextView(ctx);
        v.setText(Ui.fix(t));
        v.setTypeface(Ui.PX);
        v.setTextSize(sp);
        v.setTextColor(color);
        return v;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void build() {
        root = new FrameLayout(ctx) {
            @Override public boolean dispatchKeyEvent(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (e.getAction() == KeyEvent.ACTION_UP) { if (screen != 0) showScreen(0); else hide(); }
                    return true;
                }
                return super.dispatchKeyEvent(e);
            }
        };
        root.setBackgroundColor(Color.TRANSPARENT);
        root.setClickable(true);
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);

        card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.panel(Ui.BG2));
        card.setPadding(Ui.dp(4), Ui.dp(4), Ui.dp(4), Ui.dp(4));
        card.setClickable(true);
        root.addView(card, new FrameLayout.LayoutParams(10, 10, Gravity.TOP | Gravity.START));

        // header (drag handle)
        LinearLayout head = new LinearLayout(ctx);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.dp(8), Ui.dp(2), Ui.dp(2), Ui.dp(2));
        head.addView(text("::", 16, Ui.MUTE));
        TextView t = text("  67 LAUNCHER", 16, Ui.GREEN);
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView x = Ui.button(ctx, "X", Ui.RED, 0xFFFFFFFF);
        x.setPadding(0, Ui.dp(2), 0, Ui.dp(4));
        x.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { hide(); } });
        TextView brg = Ui.button(ctx, "\uD83D\uDCAC", Ui.GREEN, 0xFF0F1216);
        brg.setPadding(0,Ui.dp(2),0,Ui.dp(4));
        brg.setTypeface(android.graphics.Typeface.DEFAULT);brg.setTextSize(16);
        brg.setContentDescription("Stream chat bridge controls");
        brg.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showScreen(screen == 2 ? 0 : 2); } });
        TextView dlv = Ui.button(ctx, "\u2B07", Ui.GREEN, 0xFF0F1216);
        dlv.setPadding(0,Ui.dp(2),0,Ui.dp(4));
        dlv.setTypeface(android.graphics.Typeface.DEFAULT);dlv.setTextSize(20);
        dlv.setContentDescription("Downloads");
        dlv.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showScreen(screen == 1 ? 0 : 1); } });
        head.addView(brg, new LinearLayout.LayoutParams(Ui.dp(36), Ui.dp(32)));
        head.addView(dlv, new LinearLayout.LayoutParams(Ui.dp(36), Ui.dp(32)));
        head.addView(x, new LinearLayout.LayoutParams(Ui.dp(36), Ui.dp(32)));
        card.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(40)));
        final float[] d = new float[4];
        head.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        d[0] = e.getRawX(); d[1] = e.getRawY(); d[2] = card.getTranslationX(); d[3] = card.getTranslationY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        move(d[2] + e.getRawX() - d[0], d[3] + e.getRawY() - d[1]);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        savePos();
                        return true;
                }
                return false;
            }
        });

        // search
        LinearLayout sb = new LinearLayout(ctx);
        sb.setGravity(Gravity.CENTER_VERTICAL);
        sb.setBackground(new Ui.Bevel(Ui.BG, Ui.shade(Ui.BG, 0.8f), Ui.shade(Ui.BG, 1.4f), 0xFF000000));
        sb.setPadding(Ui.dp(10), 0, Ui.dp(4), 0);
        sb.addView(new Mag(ctx), new LinearLayout.LayoutParams(Ui.dp(16), Ui.dp(16)));
        search = new EditText(ctx);
        search.setBackground(null);
        search.setHint("Search addons, worlds, skins");
        search.setHintTextColor(0xFF6B7480);
        search.setTextColor(Ui.INK);
        search.setTypeface(Ui.PX);
        search.setTextSize(15);
        search.setSingleLine(true);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        search.setPadding(Ui.dp(8), 0, Ui.dp(4), 0);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable e) { clear.setVisibility(e.length() > 0 ? View.VISIBLE : View.INVISIBLE); refilter(true); netSearch(); }
        });
        search.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int a, KeyEvent e) { hideKeys(); return true; }
        });
        sb.addView(search, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        clear = text("x", 16, Ui.MUTE);
        clear.setGravity(Gravity.CENTER);
        clear.setVisibility(View.INVISIBLE);
        clear.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { search.setText(""); } });
        sb.addView(clear, new LinearLayout.LayoutParams(Ui.dp(32), ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(42));
        slp.setMargins(Ui.dp(2), Ui.dp(2), Ui.dp(2), Ui.dp(6));
        card.addView(sb, slp);
        searchBox = sb;

        // type chips
        LinearLayout cr = new LinearLayout(ctx);
        String[] names = {"ALL", "ADDONS", "WORLDS", "TEXTURES", "SKINS", "SEEDS", "COMMANDS"};
        for (int i = 0; i < names.length; i++) {
            final int k = i;
            TextView c = text(names[i], 12, Ui.MUTE);
            c.setGravity(Gravity.CENTER);
            c.setPadding(Ui.dp(9), Ui.dp(5), Ui.dp(9), Ui.dp(7));
            c.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { typeFilter = k; chipStyle(); if (k == 6) loadCmds(); resetNet(); refilter(true); netSearch(); } });
            chips[i] = c;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(5);
            cr.addView(c, lp);
        }
        chipStyle();
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(Ui.dp(2), 0, Ui.dp(2), Ui.dp(6));
        HorizontalScrollView hs = new HorizontalScrollView(ctx);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(cr);
        card.addView(hs, clp);
        chipsRow = hs;

        // seed subcategory chips (visible only on the SEEDS tab)
        LinearLayout sr = new LinearLayout(ctx);
        TextView allC = text("ALL SEEDS", 12, Ui.MUTE);
        allC.setGravity(Gravity.CENTER);
        allC.setPadding(Ui.dp(9), Ui.dp(5), Ui.dp(9), Ui.dp(7));
        allC.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { seedTag = null; seedDone = false; seedFeed = new ArrayList<>(); seedChipStyle(); refilter(true); loadMore(); } });
        sr.addView(allC, chipLp());
        for (final String[] tg : Seeds.TAGS) {
            TextView c = text(tg[0].toUpperCase(java.util.Locale.ROOT), 12, Ui.MUTE);
            c.setGravity(Gravity.CENTER);
            c.setPadding(Ui.dp(9), Ui.dp(5), Ui.dp(9), Ui.dp(7));
            c.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { seedTag = tg[1]; seedDone = false; seedFeed = new ArrayList<>(); seedChipStyle(); refilter(true); loadMore(); } });
            c.setTag(tg[1]);
            sr.addView(c, chipLp());
        }
        seedRow = new HorizontalScrollView(ctx);
        seedRow.setHorizontalScrollBarEnabled(false);
        seedRow.addView(sr);
        seedRow.setVisibility(View.GONE);
        LinearLayout.LayoutParams slp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp2.setMargins(Ui.dp(2), 0, Ui.dp(2), Ui.dp(6));
        card.addView(seedRow, slp2);

        // command subcategory chips (visible only on the COMMANDS tab)
        LinearLayout cr2 = new LinearLayout(ctx);
        for (final String[] cc : new String[][]{{"DAILY USE", "daily"}, {"CHAT", "chat"}, {"COMMAND BLOCK", "block"}}) {
            TextView c = text(cc[0], 12, Ui.MUTE);
            c.setGravity(Gravity.CENTER);
            c.setPadding(Ui.dp(9), Ui.dp(5), Ui.dp(9), Ui.dp(7));
            c.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { cmdCat = cc[1]; cmdChipStyle(); refilter(true); } });
            c.setTag(cc[1]);
            cr2.addView(c, chipLp());
        }
        cmdRow = new HorizontalScrollView(ctx);
        cmdRow.setHorizontalScrollBarEnabled(false);
        cmdRow.addView(cr2);
        cmdRow.setVisibility(View.GONE);
        card.addView(cmdRow, slp2);

        LinearLayout ar=new LinearLayout(ctx);
        final String[] opts={"ALL FEED","LATEST","POPULAR (ALL TIME)","REFRESH"};
        for(int ai=0;ai<opts.length;ai++){
            final int mode=ai;TextView ac=text(opts[ai],12,Ui.MUTE);ac.setPadding(Ui.dp(9),Ui.dp(5),Ui.dp(9),Ui.dp(7));
            ac.setOnClickListener(v->{if(mode==3){refreshSorted();return;}allMode=mode;allChipStyle();refilter(true);loadMore();});ar.addView(ac,chipLp());
        }
        allRow=new HorizontalScrollView(ctx);allRow.setHorizontalScrollBarEnabled(false);allRow.addView(ar);card.addView(allRow,slp2);allChipStyle();

        // list + state
        FrameLayout body = new FrameLayout(ctx);
        bodyBox = body;
        body.setBackgroundColor(Ui.BG);
        card.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        list = new ListView(ctx);
        list.setDivider(null);
        list.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        list.setVerticalScrollBarEnabled(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setClipToPadding(false);
        list.setPadding(Ui.dp(6), Ui.dp(6), Ui.dp(6), Ui.dp(6));
        adapter = new Adapter();
        list.setAdapter(adapter);
        foot = text("", 13, Ui.MUTE);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, Ui.dp(14), 0, Ui.dp(14));
        foot.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { if (feedFailed) { failedSources.clear();feedFailed = false; loadMore(); } } });
        list.addFooterView(foot, null, true);
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            public void onScrollStateChanged(AbsListView v, int s) {
                if (s != SCROLL_STATE_IDLE) { hideKeys(); return; }
                prefetchVersions();
                if (list.getLastVisiblePosition() >= adapter.getCount() - 6) loadMore();
            }
            public void onScroll(AbsListView v, int first, int visible, int total) {
                if (total > 0 && first + visible >= total - 6) loadMore();
            }
        });
        body.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        state = text("", 15, Ui.MUTE);
        state.setGravity(Gravity.CENTER);
        state.setPadding(Ui.dp(20), 0, Ui.dp(20), 0);
        state.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { if(feedFailed){failedSources.clear();feedFailed=false;loadMore();}else if (!loading && all.isEmpty()) fetch(); } });
        body.addView(state, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        dlView = buildDlView();
        dlView.setVisibility(View.GONE);
        body.addView(dlView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        bridgeView = buildBridgeView();
        bridgeView.setVisibility(View.GONE);
        body.addView(bridgeView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    // ---------- in-panel screens (feed / downloads / bridge) ----------

    private void showScreen(int s) {
        screen = s;
        boolean feed = s == 0;
        searchBox.setVisibility(feed ? View.VISIBLE : View.GONE);
        chipsRow.setVisibility(feed ? View.VISIBLE : View.GONE);
        if (feed) chipStyle();
        else {
            if (seedRow != null) seedRow.setVisibility(View.GONE);
            if (cmdRow != null) cmdRow.setVisibility(View.GONE);
            if (allRow != null) allRow.setVisibility(View.GONE);
        }
        list.setVisibility(feed ? View.VISIBLE : View.GONE);
        state.setVisibility(feed && state.getText().length() > 0 ? View.VISIBLE : View.GONE);
        dlView.setVisibility(s == 1 ? View.VISIBLE : View.GONE);
        bridgeView.setVisibility(s == 2 ? View.VISIBLE : View.GONE);
        if (s == 1) {
            Downloader.setWatch(new Downloader.Watch() { public void changed() { main.post(new Runnable() { public void run() { if (screen == 1) refreshDl(); } }); } });
            refreshDl();
        } else {
            Downloader.setWatch(null);
        }
        if (s == 2) { refreshBridge(); main.post(brTick); }
        else main.removeCallbacks(brTick);
        hideKeys();
    }

    // ---------- downloads screen (ported from DlActivity; no separate portrait activity) ----------

    private View buildDlView() {
        LinearLayout v = new LinearLayout(ctx);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setBackgroundColor(Ui.BG);
        dlHead = text("", 13, Ui.INK);
        dlHead.setGravity(Gravity.CENTER_VERTICAL);
        dlHead.setPadding(Ui.dp(12), Ui.dp(8), Ui.dp(12), Ui.dp(8));
        v.addView(dlHead, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ListView dl = new ListView(ctx);
        dl.setDivider(null);
        dl.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        dl.setClipToPadding(false);
        dl.setPadding(Ui.dp(6), 0, Ui.dp(6), Ui.dp(6));
        dlAd = new DlAd();
        dl.setAdapter(dlAd);
        v.addView(dl, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return v;
    }

    private void refreshDl() {
        dlRows.clear();
        dlRows.addAll(Downloader.tasks(ctx));
        long used = Downloader.storageUsed(ctx);
        long free;
        try { free = ctx.getFilesDir().getUsableSpace(); } catch (Exception e) { free = -1; }
        dlHead.setText(dlRows.isEmpty() ? "No downloads yet"
                : dlRows.size() + " download" + (dlRows.size() == 1 ? "" : "s") + "  -  " + DlActivity.mb(used) + " stored" + (free >= 0 ? "  -  " + DlActivity.mb(free) + " free" : ""));
        dlAd.notifyDataSetChanged();
    }

    private void confirmDeleteDl(final Downloader.Task t) {
        AlertDialog d = new AlertDialog.Builder(ctx)
                .setTitle("Delete download?")
                .setMessage(t.label + " will be removed from this app only. Your Minecraft worlds are untouched.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface di, int w) { Downloader.remove(ctx, t.key, true); refreshDl(); }
                })
                .create();
        if (d.getWindow() != null) d.getWindow().setType(Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE);
        d.show();
    }

    private final class DlAd extends BaseAdapter {
        public int getCount() { return dlRows.size(); }
        public Object getItem(int i) { return dlRows.get(i); }
        public long getItemId(int i) { return i; }
        private final class Row { LinearLayout box, bar; TextView name, st; android.widget.ProgressBar pr; String key = ""; int st8 = -1; }
        public View getView(int i, View cv, ViewGroup pg) {
            Row r;
            if (cv == null) {
                r = new Row();
                r.box = new LinearLayout(ctx);
                r.box.setOrientation(LinearLayout.VERTICAL);
                r.box.setPadding(Ui.dp(10), Ui.dp(6), Ui.dp(10), Ui.dp(6));
                r.name = text("", 14, Ui.INK); r.name.setMaxLines(2); r.box.addView(r.name);
                r.st = text("", 12, Ui.MUTE); r.st.setPadding(0, Ui.dp(2), 0, 0); r.box.addView(r.st);
                r.pr = new android.widget.ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
                r.pr.setMax(100); r.box.addView(r.pr);
                r.bar = new LinearLayout(ctx); r.bar.setPadding(0, Ui.dp(4), 0, 0); r.box.addView(r.bar);
                r.box.setTag(r); cv = r.box;
            } else r = (Row) cv.getTag();
            final Downloader.Task t = dlRows.get(i);
            r.name.setText(Ui.fix(t.label == null || t.label.isEmpty() ? t.name : t.label));
            r.st.setText(Ui.fix(DlActivity.stateText(t) + (t.error.isEmpty() ? "" : "  -  " + t.error)));
            r.st.setTextColor(t.state == Downloader.FAILED ? 0xFFFF9F68 : Ui.MUTE);
            r.pr.setVisibility(t.state == Downloader.RUNNING || t.state == Downloader.QUEUED ? View.VISIBLE : View.GONE);
            r.pr.setIndeterminate(t.total <= 0);
            r.pr.setProgress(Math.max(0, t.pct()));
            if (!r.key.equals(t.key) || r.st8 != t.state) { r.key = t.key; r.st8 = t.state; r.bar.removeAllViews(); addDlBtn(r.bar, t); }
            return cv;
        }
    }

    private void addDlBtn(LinearLayout bar, final Downloader.Task t) {
        switch (t.state) {
            case Downloader.RUNNING:
            case Downloader.QUEUED:
                dlBtn(bar, "PAUSE", new View.OnClickListener() { public void onClick(View v) { Downloader.pause(ctx, t.key); refreshDl(); } });
                dlBtn(bar, "CANCEL", new View.OnClickListener() { public void onClick(View v) { Downloader.cancel(ctx, t.key); refreshDl(); } });
                break;
            case Downloader.PAUSED:
                dlBtn(bar, "RESUME", new View.OnClickListener() { public void onClick(View v) { Downloader.resume(ctx, t.key); refreshDl(); } });
                dlBtn(bar, "CANCEL", new View.OnClickListener() { public void onClick(View v) { Downloader.cancel(ctx, t.key); refreshDl(); } });
                break;
            case Downloader.FAILED:
            case Downloader.CANCELED:
                dlBtn(bar, "RETRY", new View.OnClickListener() { public void onClick(View v) { Downloader.retry(ctx, t.key); refreshDl(); } });
                dlBtn(bar, "DELETE", new View.OnClickListener() { public void onClick(View v) { confirmDeleteDl(t); } });
                break;
            case Downloader.DONE:
                dlBtn(bar, "SHARE", new View.OnClickListener() { public void onClick(View v) {
                    android.net.Uri u = android.net.Uri.parse("content://" + ShareProvider.AUTH + "/dl/" + android.net.Uri.encode(t.key));
                    android.content.Intent in = new android.content.Intent(android.content.Intent.ACTION_SEND).setType("application/octet-stream");
                    in.putExtra(android.content.Intent.EXTRA_STREAM, u);
                    in.setClipData(android.content.ClipData.newRawUri("Pack", u));
                    in.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { ctx.startActivity(android.content.Intent.createChooser(in, "Share downloaded pack").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) {}
                } });
                dlBtn(bar, "INSTALL", new View.OnClickListener() { public void onClick(View v) { Downloader.install(ctx, t.key); } });
                dlBtn(bar, "DELETE", new View.OnClickListener() { public void onClick(View v) { confirmDeleteDl(t); } });
                break;
        }
    }

    private void dlBtn(LinearLayout bar, String s, View.OnClickListener l) {
        TextView b = text(s, 12, Ui.GREEN);
        b.setPadding(Ui.dp(8), Ui.dp(6), Ui.dp(8), Ui.dp(6));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(8);
        bar.addView(b, lp);
    }

    // ---------- stream chat bridge screen ----------

    private View buildBridgeView() {
        ScrollView sc = new ScrollView(ctx);
        sc.setBackgroundColor(Ui.BG);
        LinearLayout v = new LinearLayout(ctx);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(Ui.dp(12), Ui.dp(8), Ui.dp(12), Ui.dp(12));
        sc.addView(v, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        v.addView(text("STREAM CHAT", 14, Ui.GREEN));
        brStatus = text("OFF", 12, Ui.MUTE);
        brStatus.setTypeface(null);
        brStatus.setPadding(0, Ui.dp(6), 0, 0);
        v.addView(brStatus);

        LinearLayout cmdRow = new LinearLayout(ctx);
        cmdRow.setGravity(Gravity.CENTER_VERTICAL);
        cmdRow.setPadding(0, Ui.dp(4), 0, 0);
        brPort = text("", 12, Ui.INK);
        brPort.setTypeface(android.graphics.Typeface.MONOSPACE);
        cmdRow.addView(brPort, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView copy = Ui.button(ctx, "COPY", Ui.GREEN, 0xFF0C1A0E);
        copy.setTextSize(11);
        copy.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { copyConnect(); } });
        cmdRow.addView(copy, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        v.addView(cmdRow);

        brYt = text("", 11, Ui.MUTE);
        brYt.setTypeface(null);
        brYt.setPadding(0, Ui.dp(4), 0, 0);
        v.addView(brYt);

        brErr = text("", 11, Ui.RED);
        brErr.setTypeface(null);
        brErr.setPadding(0, Ui.dp(2), 0, 0);
        v.addView(brErr);

        LinearLayout r1 = new LinearLayout(ctx);
        r1.setPadding(0, Ui.dp(8), 0, 0);
        TextView start = Ui.button(ctx, "START BRIDGE", Ui.GREEN, 0xFF0C1A0E);
        start.setTextSize(11);
        start.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { startBridge(); } });
        r1.addView(start, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView stop = Ui.button(ctx, "STOP", Ui.CARD, Ui.INK);
        stop.setTextSize(11);
        stop.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { stopBridge(); } });
        LinearLayout.LayoutParams spl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        spl.leftMargin = Ui.dp(6);
        r1.addView(stop, spl);
        v.addView(r1);

        LinearLayout r2 = new LinearLayout(ctx);
        r2.setPadding(0, Ui.dp(6), 0, 0);
        brChatBtn = Ui.button(ctx, "CHAT ON", Ui.CARD, Ui.INK);
        brChatBtn.setTextSize(11);
        brChatBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            StreamChatService.chatOn = !StreamChatService.chatOn;
            refreshBridge();
        } });
        r2.addView(brChatBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        brModeBtn = Ui.button(ctx, "MODE", Ui.CARD, Ui.INK);
        brModeBtn.setTextSize(11);
        brModeBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { cycleMode(); } });
        LinearLayout.LayoutParams mpl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mpl.leftMargin = Ui.dp(6);
        r2.addView(brModeBtn, mpl);
        v.addView(r2);

        LinearLayout r3 = new LinearLayout(ctx);
        r3.setPadding(0, Ui.dp(6), 0, 0);
        TextView test = Ui.button(ctx, "TEST", Ui.GREEN, 0xFF0C1A0E);
        test.setTextSize(11);
        test.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { StreamChatService.postTest(); } });
        r3.addView(test, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView clear = Ui.button(ctx, "CLEAR", Ui.CARD, Ui.INK);
        clear.setTextSize(11);
        clear.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { StreamChatService.postControl(Ws.SCRIPT_CLEAR); } });
        LinearLayout.LayoutParams clp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp2.leftMargin = Ui.dp(6);
        r3.addView(clear, clp2);
        v.addView(r3);

        TextView note = text("CONNECT first: start the bridge, then run the /connect command in Minecraft chat. Bubbles and chat appear in the world; bubble time lives on the app's main screen.", 11, Ui.MUTE);
        note.setTypeface(null);
        note.setPadding(0, Ui.dp(8), 0, 0);
        v.addView(note);
        return sc;
    }

    private void copyConnect() {
        if (StreamChatService.port <= 0 || "OFF".equals(StreamChatService.state)) {
            Toast.makeText(ctx, "Start the bridge first.", Toast.LENGTH_SHORT).show();
            return;
        }
        String cmd = "/connect 127.0.0.1:" + StreamChatService.port;
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("connect command", cmd));
            Toast.makeText(ctx, "Copied: " + cmd, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(ctx, cmd, Toast.LENGTH_LONG).show();
        }
    }

    private void startBridge() {
        try {
            android.content.Intent i = new android.content.Intent(ctx, StreamChatService.class);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i); else ctx.startService(i);
        } catch (Exception e) {
            Toast.makeText(ctx, "Could not start the bridge.", Toast.LENGTH_LONG).show();
        }
        main.postDelayed(new Runnable() { public void run() { refreshBridge(); } }, 400);
    }

    private void stopBridge() {
        try { ctx.stopService(new android.content.Intent(ctx, StreamChatService.class)); } catch (Exception ignored) {}
        main.postDelayed(new Runnable() { public void run() { refreshBridge(); } }, 400);
    }

    private void cycleMode() {
        String[] order = {"both", "chat", "bubble", "panel", "off"};
        String cur = sp.getString("stream_mode", "both");
        int i = 0;
        for (int k = 0; k < order.length; k++) if (order[k].equals(cur)) i = k;
        String next = order[(i + 1) % order.length];
        sp.edit().putString("stream_mode", next).apply();
        StreamChatService.postControl(Ws.scriptMode(next));
        refreshBridge();
    }

    private void refreshBridge() {
        if (brStatus == null) return;
        String st = StreamChatService.state;
        brStatus.setText(Ui.fix(st + (StreamChatService.chatOn ? "" : "  (chat paused)") + "  sent " + StreamChatService.sent + (StreamChatService.droppedTotal > 0 ? "  dropped " + StreamChatService.droppedTotal : "")));
        brStatus.setTextColor(st.equals("CONNECTED") ? Ui.GREEN : st.equals("WAITING") ? Ui.GOLD : Ui.RED);
        brPort.setText(Ui.fix(st.equals("OFF") ? "bridge stopped - tap START BRIDGE" : "/connect 127.0.0.1:" + StreamChatService.port));
        brYt.setText(Ui.fix(StreamChatService.ytState));
        brYt.setTextColor(StreamChatService.ytState.equals("YT LIVE") ? Ui.GREEN : StreamChatService.ytState.startsWith("YT ERR") || StreamChatService.ytState.startsWith("YT BAD") || StreamChatService.ytState.startsWith("YT NOT") ? Ui.RED : Ui.MUTE);
        String ce = StreamChatService.lastCmdError;
        brErr.setText(Ui.fix(ce.isEmpty() ? "" : "last command error: " + ce));
        brErr.setVisibility(ce.isEmpty() ? View.GONE : View.VISIBLE);
        brChatBtn.setText(Ui.fix(StreamChatService.chatOn ? "CHAT ON" : "CHAT OFF"));
        brModeBtn.setText(Ui.fix("MODE: " + sp.getString("stream_mode", "both").toUpperCase(java.util.Locale.US)));
    }

    private final Runnable brTick = new Runnable() { public void run() {
        if (screen != 2 || !shown) return;
        refreshBridge();
        main.postDelayed(this, 500);
    } };

    private LinearLayout.LayoutParams chipLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(5);
        return lp;
    }

    private void cmdChipStyle() {
        if (cmdRow == null) return;
        LinearLayout sr = (LinearLayout) cmdRow.getChildAt(0);
        for (int i = 0; i < sr.getChildCount(); i++) {
            TextView c = (TextView) sr.getChildAt(i);
            c.setTextColor(cmdCat.equals((String) c.getTag()) ? Ui.GREEN : Ui.MUTE);
        }
    }

    /** Loads the bundled command templates once; local asset, no network. */
    private void loadCmds() {
        if (cmdLoaded) return;
        cmdLoaded = true;
        new Thread(new Runnable() { public void run() {
            final List<Addon> rows = new ArrayList<>();
            for (Cmds.Entry e : Cmds.all(ctx)) rows.add(Cmds.toAddon(e));
            main.post(new Runnable() { public void run() {
                cmdFeed.clear(); cmdFeed.addAll(rows);
                cmdChipStyle(); refilter(false);
            } });
        } }).start();
    }

    private void seedChipStyle() {
        if (seedRow == null) return;
        LinearLayout sr = (LinearLayout) seedRow.getChildAt(0);
        for (int i = 0; i < sr.getChildCount(); i++) {
            TextView c = (TextView) sr.getChildAt(i);
            boolean on = (i == 0 && seedTag == null) || (i > 0 && ((String) c.getTag()).equals(seedTag));
            c.setTextColor(on ? Ui.GREEN : Ui.MUTE);
        }
    }

    private void chipStyle() {
        if (seedRow != null) seedRow.setVisibility(typeFilter == 5 ? View.VISIBLE : View.GONE);
        if (cmdRow != null) cmdRow.setVisibility(typeFilter == 6 ? View.VISIBLE : View.GONE);
        if(allRow!=null)allRow.setVisibility(typeFilter==0?View.VISIBLE:View.GONE);
        for (int i = 0; i < chips.length; i++) {
            boolean on = i == typeFilter;
            chips[i].setTextColor(on ? Ui.GREEN : Ui.MUTE);
            chips[i].setBackground(new Ui.Bevel(on ? 0xFF1E2B22 : Ui.CARD, on ? Ui.GREEN_D : Ui.shade(Ui.CARD, 1.2f), Ui.shade(on ? 0xFF1E2B22 : Ui.CARD, 0.7f), 0xFF000000));
        }
    }

    private void hideKeys() {
        try {
            ((InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(search.getWindowToken(), 0);
            root.requestFocus();
        } catch (Exception ignored) {}
    }

    // ---------- data ----------
    private void fetch() {
        loading = true;
        if (all.isEmpty()) setState("Loading addons...");
        Api.fetch(ctx.getApplicationContext(), new Api.CB() {
            public void ok(List<Addon> l) {
                loading = false;
                all = l;
                for (Addon a : l) seenIds.add(a.id);
                refilter(false);
                Images.prefetch(all, 24);
                loadMore();
            }
            public void fail() {
                loading = false;
                if (all.isEmpty()) setState("Cannot load addons.\nCheck internet, tap to retry.");
                loadMore();
            }
        });
    }

    private void setState(String s) {
        state.setText(s);
        state.setVisibility(s.length() == 0 ? View.GONE : View.VISIBLE);
    }

    /** MCPEDL type ids feeding the current tab. */
    private int[] feedTypes() {
        switch (typeFilter) {
            case 1: return new int[]{1};
            case 2: return new int[]{2};
            case 3: return new int[]{3};
            case 4: return new int[]{6};
            case 0: return new int[]{1};
            default: return new int[0];
        }
    }

    private void resetNet() {
        failedSources.clear();feedFailed=false;
        cfAll = new ArrayList<>();
        nineAll = new ArrayList<>();
        labyAll = new ArrayList<>();
        srchGen++;
    }

    private void allChipStyle(){if(allRow==null)return;LinearLayout row=(LinearLayout)allRow.getChildAt(0);for(int i=0;i<row.getChildCount();i++)((TextView)row.getChildAt(i)).setTextColor(i==allMode?Ui.GREEN:Ui.MUTE);}
    private void refreshSorted(){
        if(!sourceBusy.isEmpty()){Toast.makeText(ctx,"A source is already loading",Toast.LENGTH_SHORT).show();return;}
        failedSources.clear();feedFailed=false;
        if(allMode>0)Toast.makeText(ctx,allMode==1?"Refreshing latest (up to 1 minute cache)":"Refreshing popular (up to 5 minute cache)",Toast.LENGTH_SHORT).show();
        // Source cache TTL is deliberately retained to respect host budgets.
        if(allMode>0){sortedPage.remove(allMode);sortedEnded.remove(allMode);sortedFeed.remove(allMode);}
        else{nextPage.clear();endedTypes.clear();nineEnded=false;ninePage=1;feed.clear();seenIds.clear();}
        refilter(true);loadMore();
    }
    private void work(final String key,final String source,final Runnable action){
        if(failedSources.contains(key)||!sourceBusy.add(key))return;feedLoading=true;if(foot!=null)foot.setText("Loading more...");
        Net.worker(source).execute(()->{try{action.run();}catch(Exception e){main.post(()->{failedSources.add(key);feedFailed=true;});}
            finally{main.post(()->{sourceBusy.remove(key);feedLoading=!sourceBusy.isEmpty();refilter(false);
                if(foot!=null)foot.setText(feedLoading?"Loading more...":feedFailed?"Could not load more - tap to retry":"");
            });}
        });
    }
    /** Sources publish independently. A slow 9mc result cannot withhold a ready MCPEDL/Laby/seed page. */
    private void loadMore(){
        if(search==null||search.getText().toString().trim().length()>=2||typeFilter==6)return;
        final int tab=typeFilter;
        if(tab==0&&allMode>0){
            final int mode=allMode;if(sortedEnded.contains(mode))return;
            final int pg=sortedPage.containsKey(mode)?sortedPage.get(mode):1;
            work("sort:"+mode,"mcpedl",()->{
                final Mc.Page page=Mc.sorted(1,pg,mode==1?"created_at":"popularity_all");
                main.post(()->{if(page==null){failedSources.add("sort:"+mode);feedFailed=true;return;}java.util.List<Addon> dst=sortedFeed.get(mode);if(dst==null){dst=new ArrayList<>();sortedFeed.put(mode,dst);}
                    java.util.Set<String> ids=new java.util.HashSet<>();for(Addon x:dst)ids.add(x.id);for(Addon x:page.items)if(ids.add(x.id))dst.add(x);
                    sortedPage.put(mode,pg+1);if(!page.more)sortedEnded.add(mode);refilter(false);
                });
            });return;
        }
        for(final int type:feedTypes())if(!endedTypes.contains(type)){
            final int pg=nextPage.containsKey(type)?nextPage.get(type):1;
            work("mc:"+type,"mcpedl",()->{
                final Mc.Page page=Mc.browse(type,pg);main.post(()->{if(page==null){failedSources.add("mc:"+type);feedFailed=true;return;}
                    for(Addon x:page.items)if(seenIds.add(x.id))feed.add(x);
                    if(page.more)nextPage.put(type,pg+1);else endedTypes.add(type);refilter(false);
                });
            });
        }
        if((tab==0||tab==1)&&!nineEnded){final int pg=ninePage;
            work("nine","9mc",()->{final NineMc.Page page=NineMc.browse(pg);main.post(()->{if(page==null){failedSources.add("nine");feedFailed=true;return;}
                for(Addon x:page.items)if(seenIds.add(x.id))feed.add(x);ninePage=pg+1;nineEnded=!page.more;refilter(false);
            });});
        }
        if(tab==4&&!labyDone)work("laby","laby",()->{final java.util.List<Addon> rows=Laby.search("",30);main.post(()->{if(rows==null){failedSources.add("laby");feedFailed=true;return;}labyFeed=rows;labyDone=true;refilter(false);});});
        if(tab==5&&!seedDone){final String tag=seedTag;
            work("seeds:"+tag,"seeds",()->{final java.util.List<Addon> rows=Seeds.browse(tag);main.post(()->{
                if(!java.util.Objects.equals(tag,seedTag))return;if(rows==null){failedSources.add("seeds:"+tag);feedFailed=true;return;}seedFeed=rows;seedDone=true;refilter(false);
            });});
        }
    }

    // ---------- search across sources ----------
    private List<Addon> cfAll = new ArrayList<>(), nineAll = new ArrayList<>(), labyAll = new ArrayList<>();
    private int srchGen;
    private final Runnable srchRun = new Runnable() { public void run() { srchNow(); } };

    private void netSearch() {
        search.removeCallbacks(srchRun);srchGen++;
        String q = search.getText().toString().trim();
        if (q.length() < 2) {
            if (!cfAll.isEmpty() || !nineAll.isEmpty() || !labyAll.isEmpty()) { resetNet(); refilter(false); }
            return;
        }
        search.postDelayed(srchRun, 600);
    }

    private void srchNow() {
        final String q = search.getText().toString();
        final int gen = ++srchGen;
        final int tab = typeFilter;
        if(tab==6||tab==5)return;
        cfAll=new ArrayList<>();nineAll=new ArrayList<>();labyAll=new ArrayList<>();
        final int[] types=tab==4?new int[]{6}:tab==0?new int[]{1,2,3}:feedTypes();
        Net.worker("mcpedl").execute(()->{
            for(int type:types){if(gen!=srchGen)return;Mc.Page page=Mc.query(q,type,1);
                if(page!=null)main.post(()->{if(gen!=srchGen)return;cfAll.addAll(page.items);refilter(false);Images.prefetch(page.items,6);});
            }
        });
        if(tab==0||tab==1||tab==2)Net.worker("9mc").execute(()->{
            if(gen!=srchGen)return;NineMc.Page page=NineMc.search(q,1);
            main.post(()->{if(gen!=srchGen)return;nineAll=page==null?new ArrayList<>():page.items;refilter(false);});
        });
        if(tab==0||tab==4)Net.worker("laby").execute(()->{
            if(gen!=srchGen)return;List<Addon> rows=Laby.search(q,20);
            main.post(()->{if(gen!=srchGen)return;labyAll=rows==null?new ArrayList<>():rows;refilter(false);Images.prefetch(labyAll,8);});
        });
    }

    private Addon header(String t) {
        Addon h = new Addon();
        h.type = "hdr";
        h.id = "hdr" + t;
        h.name = t;
        return h;
    }

    private boolean tabHas(Addon a) {
        if ("command".equals(a.type)) return typeFilter == 6;
        return typeFilter != 6 && (typeFilter == 0 || KEYS[typeFilter].equals(a.type));
    }

    private void refilter(boolean toTop) {
        String qs = search == null ? "" : search.getText().toString();
        boolean searching = qs.trim().length() >= 2;
        view = new ArrayList<>();
        List<Addon> typed = new ArrayList<>();
        for (Addon a : all) if (tabHas(a)) typed.add(a);
        List<Addon> db = Fuzzy.search(typed, qs);
        if (searching && typeFilter == 6){List<Addon> commands=new ArrayList<>();for(Addon a:cmdFeed)if(cmdCat.equals("daily")?a.cmd.daily:cmdCat.equals(a.cmd.cat))commands.add(a);db=Fuzzy.search(commands,qs);}
        if (searching) {
            if (!db.isEmpty()) { view.add(header("67 DATABASE")); view.addAll(db); }
            if (!labyAll.isEmpty() && (typeFilter == 0 || typeFilter == 4)) { view.add(header("LABY SKINS")); view.addAll(labyAll); }
            List<Addon> cf = new ArrayList<>();
            for (Addon a : cfAll) if (tabHas(a)) cf.add(a);
            if (!cf.isEmpty()) { view.add(header(typeFilter == 4 ? "MCPEDL SKIN PACKS" : "MCPEDL")); view.addAll(cf); }
            List<Addon> nn = new ArrayList<>();
            for (Addon a : nineAll) if (tabHas(a)) nn.add(a);
            if (!nn.isEmpty()) { view.add(header("9MINECRAFT")); view.addAll(nn); }
        } else {
            view.addAll(db);
            if (typeFilter == 4 && !labyFeed.isEmpty()) { view.add(header("LABY SKINS - POPULAR")); view.addAll(labyFeed); }
            if (typeFilter == 5 && !seedFeed.isEmpty()) { view.add(header("WISEHOSTING - BEDROCK SEEDS")); view.addAll(seedFeed); }
            if (typeFilter == 6) {
                List<Addon> cd = new ArrayList<>();
                for (Addon a : cmdFeed) if ((cmdCat.equals("daily")?a.cmd.daily:cmdCat.equals(a.cmd.cat))) cd.add(a);
                if (!cd.isEmpty()) { view.add(header(cmdCat.equals("daily")?"DAILY USE":cmdCat.equals("chat") ? "CHAT COMMANDS" : "COMMAND BLOCK")); view.addAll(cd); }
            }
            List<Addon> fd = new ArrayList<>();
            for (Addon a : feed) if (tabHas(a)) fd.add(a);
            if (!fd.isEmpty()) {
                if (!db.isEmpty() || typeFilter == 4) view.add(header(typeFilter == 4 ? "MCPEDL SKIN PACKS" : typeFilter == 0 ? "MCPEDL + 9MINECRAFT - LATEST" : "LATEST"));
                view.addAll(fd);
            }
        }
        if(!searching&&typeFilter==0&&allMode>0){
            view.clear();java.util.List<Addon> ranked=sortedFeed.get(allMode);
            if(ranked!=null&&!ranked.isEmpty()){view.add(header(allMode==1?"MCPEDL - NEWEST":"MCPEDL - POPULAR ALL TIME (DOWNLOADS)"));view.addAll(ranked);}
        }
        if (view.isEmpty() && !all.isEmpty()) {
            String q = qs.trim();
            setState(q.length() > 0 ? "Nothing found for\n\"" + q + "\"" : feedFailed?"A source could not load.\nTap here to retry.":feedLoading?"Loading...":"Nothing in this tab yet.");
        } else if (view.isEmpty()) {
            if (!loading) setState(feedLoading ? "Loading..." : feedFailed?"A source could not load.\nTap here to retry.":"No addons yet.\nCheck back soon.");
        } else setState("");
        adapter.notifyDataSetChanged();
        if (toTop) list.setSelection(0);
    }

    // ---------- list ----------
    private final class Adapter extends BaseAdapter {
        public int getCount() { return view.size(); }
        public Object getItem(int i) { return view.get(i); }
        public long getItemId(int i) { return i; }
        public int getViewTypeCount() { return 2; }
        public int getItemViewType(int i) { return view.get(i).type.equals("hdr") ? 1 : 0; }
        public boolean isEnabled(int i) { return false; }
        public View getView(int i, View cv, ViewGroup p) {
            if (view.get(i).type.equals("hdr")) {
                TextView t = cv instanceof TextView ? (TextView) cv : text("", 12, Ui.GREEN);
                t.setText(view.get(i).name);
                t.setPadding(Ui.dp(4), Ui.dp(6), 0, Ui.dp(8));
                return t;
            }
            Holder h;
            if (cv == null) { h = newHolder(); cv = h.row; cv.setTag(R.id.holder, h); } else h = (Holder) cv.getTag(R.id.holder);
            bind(h, view.get(i));
            return cv;
        }
    }

    private Holder newHolder() {
        final Holder h = new Holder();
        FrameLayout wrap = new FrameLayout(ctx);
        wrap.setPadding(0, 0, 0, Ui.dp(8));
        LinearLayout row = new LinearLayout(ctx);
        row.setPadding(Ui.dp(8), Ui.dp(8), Ui.dp(8), Ui.dp(8));
        row.setBackground(Ui.panel(Ui.CARD));
        wrap.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout box = new FrameLayout(ctx);
        box.setBackgroundColor(0xFF0E1115);
        h.img = new ImageView(ctx);
        box.addView(h.img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        h.ist = new TextView(ctx);
        h.ist.setTextSize(9);
        h.ist.setTextColor(0xFFCDD4DC);
        h.ist.setGravity(Gravity.CENTER);
        h.ist.setVisibility(View.GONE);
        box.addView(h.ist, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        h.ibar = new android.widget.ProgressBar(ctx,null,android.R.attr.progressBarStyleHorizontal);
        h.ibar.setMax(100);
        h.ibar.setVisibility(View.GONE);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(5));
        bl.gravity = Gravity.BOTTOM;
        box.addView(h.ibar, bl);
        h.img.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { onImageTap(h); } });
        h.ist.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            if (h.a != null && "RETRY".contentEquals(h.ist.getText())) bind(h, h.a);
        } });
        row.addView(box, new LinearLayout.LayoutParams(Ui.dp(112), Ui.dp(63)));

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cl.leftMargin = Ui.dp(10);
        row.addView(col, cl);
        h.name = text("", 14, Ui.INK);
        h.name.setMaxLines(2);
        h.name.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(h.name);
        h.meta = text("", 11, Ui.MUTE);
        h.meta.setPadding(0, Ui.dp(1), 0, Ui.dp(6));
        col.addView(h.meta);
        h.desc = new TextView(ctx);
        h.desc.setTextSize(12);
        h.desc.setTextColor(Ui.INK);
        h.desc.setLineSpacing(0, 1.1f);
        h.desc.setPadding(0, 0, 0, Ui.dp(6));
        h.desc.setVisibility(View.GONE);
        col.addView(h.desc);
        LinearLayout btns = new LinearLayout(ctx);
        h.dl = new PBtn(ctx);
        style(h.dl, Ui.GREEN, 0xFF0C1A0E);
        h.dl.setTextSize(12);
        btns.addView(h.dl, new LinearLayout.LayoutParams(0, Ui.dp(34), 1f));
        h.q = Ui.button(ctx, "?", Ui.CARD, Ui.INK);
        h.q.setPadding(0, Ui.dp(4), 0, Ui.dp(6));
        LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(Ui.dp(34), Ui.dp(34));
        ql.leftMargin = Ui.dp(6);
        btns.addView(h.q, ql);
        col.addView(btns);
        h.row = new LinearLayout(ctx);
        h.row.addView(wrap, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        h.dl.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { if ("seed".equals(h.a.type)) copySeed(h.a); else if ("command".equals(h.a.type)) editCopyCmd(h.a); else download(h.a); } });
        h.q.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (h.a == null) return;
                if ("seed".equals(h.a.type) && h.a.desc.trim().length() == 0) {
                    h.q.setText("...");
                    final Addon a = h.a;
                    Net.worker("seeds").execute(new Runnable() { public void run() {
                        Seeds.fillDetail(a);
                        main.post(new Runnable() { public void run() {
                            open.add(a.id);
                            refilter(false);
                        } });
                    } });
                    return;
                }
                if (!open.remove(h.a.id)) open.add(h.a.id);
                bind(h, h.a);
            }
        });
        return h;
    }

    private void style(PBtn b, int fill, int ink) {
        b.setTypeface(Ui.PX);
        b.setTextColor(ink);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.buttonBg(fill));
        b.setPadding(Ui.dp(6), Ui.dp(4), Ui.dp(6), Ui.dp(6));
        b.setClickable(true);
        b.setMaxLines(1);b.setEllipsize(TextUtils.TruncateAt.END);b.setHorizontallyScrolling(false);
    }

    private boolean redDot(Addon a) {
        if (a.info == null || userVer == null || userVer.length() == 0) return false;
        String[] pick = Mc.pickFile(a.info, userVer);
        return pick != null && "no".equals(pick[2]);
    }

    private void bind(Holder h, Addon a) {
        h.a = a;
        h.name.setText(Ui.fix(a.name));
        String sz = a.sizeText();
        StringBuilder meta = new StringBuilder();
        if (a.srcLabel().length() > 0) meta.append(a.srcLabel()).append("  ");
        meta.append(a.typeLabel());
        if (sz.length() > 0) meta.append("  -  ").append(sz);
        if (a.vers != null && !a.vers.isEmpty()) {
            meta.append("\nMC ").append(a.vers.get(0));
            for (int i = 1; i < a.vers.size() && i < 3; i++) meta.append(", ").append(a.vers.get(i));
        }
        if(a.downloadCount>0)meta.append("\n").append(String.format(java.util.Locale.US,"%,d",a.downloadCount)).append(" downloads");
        if (a.cf && a.author.length() > 0) meta.append(a.vers != null && !a.vers.isEmpty() ? "  by " : "\nby ").append(a.author);
        h.meta.setText(Ui.fix(meta.toString()));
        h.img.setTag(null);
        h.img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        h.img.setImageDrawable(Images.art(a.name, ctx.getResources()));
        h.ibar.setVisibility(View.GONE);
        FrameLayout.LayoutParams bl2 = (FrameLayout.LayoutParams) h.ibar.getLayoutParams();
        bl2.width = ViewGroup.LayoutParams.MATCH_PARENT;
        h.ibar.setLayoutParams(bl2);
        if ("command".equals(a.type)) {
            h.ist.setVisibility(View.GONE);
            h.img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            try { h.img.setImageDrawable(new android.graphics.drawable.BitmapDrawable(ctx.getResources(), android.graphics.BitmapFactory.decodeStream(ctx.getAssets().open("icons/" + a.page + ".png")))); }
            catch (Exception ignored) { }
        } else if (a.banner == null) {
            h.ist.setText("NO IMAGE");
            h.ist.setVisibility(View.VISIBLE);
        } else {
            h.ist.setText("Loading");
            h.ibar.setIndeterminate(true);h.ibar.setVisibility(View.VISIBLE);
            h.ist.setVisibility(View.VISIBLE);
            final Holder hh = h;
            Images.load(h.img, a.banner, a.icon, new Images.State() {
                public void progress(int pct) {
                    if (hh.a == null || hh.a != a) return;
                    if (pct >= 0) {
                        hh.ist.setText(pct + "%");
                        hh.ibar.setVisibility(View.VISIBLE);
                        hh.ibar.setIndeterminate(false);hh.ibar.setProgress(Math.min(100,pct));
                    } else {hh.ist.setText("Loading");hh.ibar.setIndeterminate(true);hh.ibar.setVisibility(View.VISIBLE);}
                }
                public void loaded(){if(hh.a==a){hh.ist.setVisibility(View.GONE);hh.ibar.setVisibility(View.GONE);}}
                public void failed() {
                    if (hh.a == null || hh.a != a) return;
                    hh.ibar.setVisibility(View.GONE);
                    hh.ist.setText("RETRY");
                }
            });
        }
        boolean o = open.contains(a.id);
        if (a.desc.trim().length() == 0) o = false;
        boolean seedRow = "seed".equals(a.type);
        boolean cmdRow2 = "command".equals(a.type);
        h.desc.setVisibility(cmdRow2 || o || (seedRow && a.desc.trim().length() > 0) ? View.VISIBLE : View.GONE);
        h.desc.setMaxLines(o ? Integer.MAX_VALUE : 2);
        h.desc.setEllipsize(o ? null : TextUtils.TruncateAt.END);
        h.desc.setText(a.desc);
        h.q.setVisibility(cmdRow2 ? View.GONE : (a.desc.trim().length() > 0 || seedRow ? View.VISIBLE : View.GONE));
        h.q.setText(o ? "-" : "?");
        btn(h.dl, a);
    }

    /** Version info for the rows on screen, fetched only when scrolling rests. */
    private void prefetchVersions() {
        if (list == null) return;
        for (int i = 0; i < list.getChildCount(); i++) {
            Holder h = (Holder) list.getChildAt(i).getTag(R.id.holder);
            if (h == null || h.a == null) continue;
            if (userVer != null && h.a.cf && h.a.info == null) {
                final String id = h.a.id;
                Mc.loadInfo(h.a, main, new Runnable() { public void run() { refreshVer(id); } });
            } else if ("seed".equals(h.a.type) && h.a.seed == null && seedTried.add(h.a.id)) {
                final Addon a = h.a;
                final String id = a.id;
                Net.worker("seeds").execute(new Runnable() { public void run() {
                    boolean ok = Seeds.fillDetail(a);
                    if (!ok) seedTried.remove(a.id);   // allow retry on a later idle
                    main.post(new Runnable() { public void run() { refreshVer(id); } });
                } });
            }
        }
    }
    private final Set<String> seedTried = java.util.Collections.synchronizedSet(new HashSet<String>());

    private void btn(PBtn b, Addon a) {
        if ("seed".equals(a.type)) {
            b.pct = -1;
            b.dot = false;
            b.setAlpha(1f);
            b.setText("COPY SEED");
            b.invalidate();
            return;
        }
        if ("command".equals(a.type)) {
            b.pct = -1;
            b.dot = false;
            b.setAlpha(1f);
            b.setText("EDIT + COPY");
            b.invalidate();
            return;
        }
        Downloader.Task dt=Downloader.task(ctx,a.safeFile());
        if(dt!=null){
            if(dt.state==Downloader.DONE)prog.remove(a.id);
            else if(dt.state==Downloader.PAUSED||dt.state==Downloader.CANCELED||dt.state==Downloader.FAILED)prog.remove(a.id);
            else prog.put(a.id,dt.pct());
        }
        Integer p = prog.get(a.id);
        if (p != null) {
            b.pct = Math.max(0, p);
            b.setText(Ui.fix(p >= 0 ? p + "%" : "..."));
            b.setAlpha(1f);
        } else {
            b.pct = -1;
            b.setText(done.contains(a.id) || Downloader.have(ctx, a) ? "INSTALL" : "DOWNLOAD");
        }
        b.dot = redDot(a);
        b.invalidate();
    }

    private void refreshRow(String id) {
        for (int i = 0; i < list.getChildCount(); i++) {
            Holder h = (Holder) list.getChildAt(i).getTag(R.id.holder);
            if (h != null && h.a != null && h.a.id.equals(id)) btn(h.dl, h.a);
        }
    }

    private void refreshVer(String id) {
        for (int i = 0; i < list.getChildCount(); i++) {
            Holder h = (Holder) list.getChildAt(i).getTag(R.id.holder);
            if (h != null && h.a != null && h.a.id.equals(id)) bind(h, h.a);
        }
    }

    private void openPage(Addon a) {
        try {
            android.content.Intent it = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(a.page));
            it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
            hide();
        } catch (Exception ignored) {}
    }

    /** Tap on a row image: retry when failed, otherwise open the fullscreen viewer. */
    private void overlayWindow(android.app.Dialog d){
        if(!(ctx instanceof android.app.Activity)&&d.getWindow()!=null)d.getWindow().setType(android.os.Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE);
    }
    private void onImageTap(Holder h) {
        if (h.a == null) return;
        if ("RETRY".contentEquals(h.ist.getText())) { bind(h, h.a); return; }
        if (h.a.banner == null || "command".equals(h.a.type)) return;
        if (h.ist.getVisibility() == View.VISIBLE) return;   // still loading or failed
        final android.app.Dialog d = new android.app.Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout root = new FrameLayout(ctx);
        final ImageView big = new ImageView(ctx);
        big.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(big, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView cx = Ui.button(ctx, "X", Ui.RED, 0xFFFFFFFF);
        FrameLayout.LayoutParams xl = new FrameLayout.LayoutParams(Ui.dp(40), Ui.dp(36));
        xl.gravity = Gravity.TOP | Gravity.RIGHT;
        xl.topMargin = Ui.dp(10); xl.rightMargin = Ui.dp(10);
        root.addView(cx, xl);
        cx.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { d.dismiss(); } });
        d.setContentView(root);
        android.graphics.drawable.Drawable cur = h.img.getDrawable();
        if (cur instanceof android.graphics.drawable.BitmapDrawable){android.graphics.drawable.BitmapDrawable bd=(android.graphics.drawable.BitmapDrawable)cur;if(bd.getBitmap().getWidth()<=128)bd.setFilterBitmap(false);}
        if (cur != null) big.setImageDrawable(cur);
        overlayWindow(d);d.show();
        Images.load(big, h.a.banner, false, new Images.State() {
            public void progress(int pct) {}
            public void loaded(){big.setScaleType(ImageView.ScaleType.FIT_CENTER);}
            public void failed() {}
        });
    }

    /** Edit-before-copy dialog for a bundled command template. Never executes anything. */
    private void editCopyCmd(final Addon a) {
        if (a == null || a.cmd == null) return;
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(18);
        box.setPadding(pad, Ui.dp(12), pad, 0);
        final android.widget.EditText et = new android.widget.EditText(ctx);
        et.setText(a.cmd.c);
        et.setTextColor(Ui.INK);
        et.setTextSize(14);
        et.setBackgroundColor(0x00000000);
        box.addView(et, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        StringBuilder info = new StringBuilder();
        if (a.cmd.note != null && a.cmd.note.length() > 0) info.append(a.cmd.note);
        if (a.cmd.cat.equals("chat") && !a.cmd.cb) {
            if (info.length() > 0) info.append("\n");
            info.append("Chat only - not for command blocks.");
        }
        if (a.cmd.warn) {
            if (info.length() > 0) info.append("\n");
            info.append("WARNING: can destroy things or affect other players. Check the target before pasting.");
        }
        if (info.length() > 0) {
            TextView nt = text(info.toString(), 12, a.cmd.warn ? 0xFFFF9F68 : Ui.MUTE);
            nt.setPadding(0, Ui.dp(8), 0, 0);
            box.addView(nt);
        }
        AlertDialog cd = new AlertDialog.Builder(ctx)
            .setTitle(a.name)
            .setView(box)
            .setPositiveButton("Copy", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Minecraft command", et.getText().toString()));
                    Toast.makeText(ctx, "Command copied", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Cancel", null).create();
        overlayWindow(cd);cd.show();
    }

    /** Copies the exact seed string to the clipboard, fetching the detail page first if needed. */
    private void copySeed(final Addon a) {
        if (a.seed != null) { copySeedText(a); return; }
        Toast.makeText(ctx, "Loading seed...", Toast.LENGTH_SHORT).show();
        Net.worker("seeds").execute(new Runnable() { public void run() {
            final boolean ok = Seeds.fillDetail(a);
            main.post(new Runnable() { public void run() {
                if (ok) copySeedText(a);
                else Toast.makeText(ctx, "Could not load this seed. Try again.", Toast.LENGTH_SHORT).show();
            } });
        } });
    }

    private void copySeedText(Addon a) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Minecraft seed", a.seed));
        Toast.makeText(ctx, "Seed copied: " + a.seed, Toast.LENGTH_LONG).show();
    }

    private void download(final Addon a) {
        if (a == null || prog.containsKey(a.id)) return;
        if (a.laby) { labyDownload(a); return; }
        if (a.nine && a.download.length() == 0) { nineDownload(a); return; }
        if (a.cf && a.download.length() == 0) { cfDownload(a); return; }
        if (done.contains(a.id) || Downloader.have(ctx, a)) {
            Downloader.install(ctx, a.safeFile());
            hide();
            return;
        }
        startFile(a);
    }

    private void startFile(final Addon a) {
        prog.put(a.id, -1);
        refreshRow(a.id);
        Downloader.start(ctx.getApplicationContext(), a.download, a.safeFile(), a.name, new Downloader.Listener() {
            public void progress(int pct) { prog.put(a.id, pct); refreshRow(a.id); }
            public void done(boolean ok) {
                prog.remove(a.id);
                boolean good = ok;
                if (ok && (a.cf || a.nine)
                        && Downloader.looksPaid(new java.io.File(new java.io.File(ctx.getFilesDir(), "dl"), a.safeFile()))) {
                    new java.io.File(new java.io.File(ctx.getFilesDir(), "dl"), a.safeFile()).delete();
                    Toast.makeText(ctx, "That file looks like paid Marketplace content. 67 Launcher only installs free community addons.", Toast.LENGTH_LONG).show();
                    good = false;
                }
                if (good) done.add(a.id);
                else if (a.cf || a.nine) { a.download = ""; }
                refreshRow(a.id);
                if (good && shown) hide();
            }
        });
    }

    private void cfDownload(final Addon a) {
        if (done.contains(a.id) || Downloader.have(ctx, a)) { Downloader.install(ctx, a.safeFile()); hide(); return; }
        prog.put(a.id, -1);
        refreshRow(a.id);
        Net.worker("mcpedl").execute(new Runnable() {
            public void run() {
                Mc.Info info = Mc.pageInfo(a);
                final String[] f = Mc.pickFile(info, userVer);
                if (info != null) { a.vers = info.versions; a.info = info; }
                main.post(new Runnable() {
                    public void run() {
                        prog.remove(a.id);
                        refreshRow(a.id);
                        if (f == null) { openPage(a); return; }
                        if ("no".equals(f[2])) {
                            Toast.makeText(ctx, "No file listed for your Minecraft version. Opening the addon page so you can pick one.", Toast.LENGTH_LONG).show();
                            openPage(a);
                            return;
                        }
                        a.download = f[0];
                        a.filename = f[1];
                        download(a);
                    }
                });
            }
        });
    }

    private void nineDownload(final Addon a) {
        if (done.contains(a.id) || Downloader.have(ctx, a)) { Downloader.install(ctx, a.safeFile()); hide(); return; }
        prog.put(a.id, -1);
        refreshRow(a.id);
        Net.worker("9mc").execute(new Runnable() {
            public void run() {
                final String u = NineMc.fileFor(a);
                main.post(new Runnable() {
                    public void run() {
                        prog.remove(a.id);
                        if (u == null) { refreshRow(a.id); openPage(a); return; }
                        a.download = u;
                        a.filename = NineMc.fileName(u);
                        refreshRow(a.id);
                        download(a);
                    }
                });
            }
        });
    }

    private void labyDownload(final Addon a) {
        if (done.contains(a.id) || Downloader.have(ctx, a)) { Downloader.install(ctx, a.safeFile()); hide(); return; }
        prog.put(a.id, -1);
        refreshRow(a.id);
        Net.worker("laby").execute(new Runnable() {
            public void run() {
                boolean ok = false;
                try {
                    byte[] png = Laby.skinPng(a);
                    if (png != null) {
                        byte[] pack = SkinPack.build(a.name, png, a.slim);
                        Downloader.saveLocal(ctx.getApplicationContext(),a.safeFile(),a.name,pack);
                        ok = true;
                    }
                } catch (Exception ignored) {}
                final boolean fok = ok;
                main.post(new Runnable() {
                    public void run() {
                        prog.remove(a.id);
                        if (fok) {
                            done.add(a.id);
                            refreshRow(a.id);
                            Downloader.install(ctx, a.safeFile());
                            if (shown) hide();
                        } else {
                            refreshRow(a.id);
                            openPage(a);
                        }
                    }
                });
            }
        });
    }

    // ---------- window ----------
    private void size() {
        DisplayMetrics m = real();
        cw = Math.min(Ui.dp(400), Math.round(m.widthPixels * 0.84f));
        ch = Math.min(Ui.dp(360), Math.round(m.heightPixels * 0.78f));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) card.getLayoutParams();
        lp.width = cw;
        lp.height = ch;
        card.setLayoutParams(lp);
        float fx = sp.getFloat("pfx", 0.5f), fy = sp.getFloat("pfy", 0.5f);
        card.setTranslationX((m.widthPixels - cw) * fx);
        card.setTranslationY((m.heightPixels - ch) * fy);
    }

    private void move(float x, float y) {
        DisplayMetrics m = real();
        card.setTranslationX(Math.max(0, Math.min(m.widthPixels - cw, x)));
        card.setTranslationY(Math.max(0, Math.min(m.heightPixels - ch, y)));
    }

    private void savePos() {
        DisplayMetrics m = real();
        float rx = Math.max(1, m.widthPixels - cw), ry = Math.max(1, m.heightPixels - ch);
        sp.edit().putFloat("pfx", card.getTranslationX() / rx).putFloat("pfy", card.getTranslationY() / ry).apply();
    }

    void relayout() { if (shown) size(); }

    void show() {
        if (shown) return;
        if (root == null) build();
        size();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN;
        try {
            wm.addView(root, lp);
        } catch (Exception e) {
            Toast.makeText(ctx, "Allow 'display over other apps' for 67 Launcher.", Toast.LENGTH_LONG).show();
            return;
        }
        shown = true;
        if (screen != 0) showScreen(0); else { Downloader.setWatch(null); main.removeCallbacks(brTick); }
        userVer = McVer.userVersion(ctx);
        if (all.isEmpty()) {
            List<Addon> c = Api.cached(ctx);
            if (c != null) {
                all = c;
                for (Addon a : c) seenIds.add(a.id);
                refilter(false);
                Images.prefetch(all, 24);
            }
        }
        if (all.isEmpty() || System.currentTimeMillis() - Api.lastFetch > 60000) fetch();
        loadMore();
        root.requestFocus();
    }

    void hide() {
        if (!shown) return;
        shown = false;
        main.removeCallbacks(brTick);
        Downloader.setWatch(null);
        hideKeys();
        try { wm.removeView(root); } catch (Exception ignored) {}
        if (onClosed != null) onClosed.run();
    }

    void destroy() { hide(); root = null; }
}
