package com.imgmine.launcher67;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.*;
import android.os.Build;
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
    private FrameLayout root;
    private LinearLayout card;
    private ListView list;
    private TextView state, count, clear;
    private EditText search;
    private TextView[] chips = new TextView[5];
    private static final String[] KEYS = {"", "addon", "world", "pack", "template"};
    private boolean shown;
    private int cw, ch, typeFilter;
    private List<Addon> all = new ArrayList<>(), view = new ArrayList<>();
    private final Set<String> open = new HashSet<>();
    private final Map<String, Integer> prog = new HashMap<>();
    private final Set<String> done = new HashSet<>();
    private BaseAdapter adapter;
    private boolean loading;

    /** Button that fills with progress while downloading. */
    private final class PBtn extends TextView {
        int pct = -1;
        PBtn(Context c) { super(c); }
        @Override protected void onDraw(Canvas c) {
            if (pct >= 0) {
                Paint p = new Paint();
                p.setColor(Ui.GREEN_D);
                int in = Ui.dp(2);
                c.drawRect(in, in, in + (getWidth() - 2 * in) * pct / 100f, getHeight() - in, p);
            }
            super.onDraw(c);
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

    private final class Holder { LinearLayout row; ImageView img; TextView name, meta, desc, q; PBtn dl; Addon a; }

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
        v.setText(t);
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
                    if (e.getAction() == KeyEvent.ACTION_UP) hide();
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
        head.addView(t);
        count = text("", 12, Ui.MUTE);
        count.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        count.setPadding(0, 0, Ui.dp(8), 0);
        head.addView(count, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView x = Ui.button(ctx, "X", Ui.RED, 0xFFFFFFFF);
        x.setPadding(0, Ui.dp(2), 0, Ui.dp(4));
        x.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { hide(); } });
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
        search.setHint("Search addons");
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
            public void afterTextChanged(Editable e) { clear.setVisibility(e.length() > 0 ? View.VISIBLE : View.INVISIBLE); refilter(true); }
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

        // type chips
        LinearLayout cr = new LinearLayout(ctx);
        String[] names = {"ALL", "ADDONS", "WORLDS", "PACKS", "TEMPLATES"};
        for (int i = 0; i < 5; i++) {
            final int k = i;
            TextView c = text(names[i], 12, Ui.MUTE);
            c.setGravity(Gravity.CENTER);
            c.setPadding(Ui.dp(9), Ui.dp(5), Ui.dp(9), Ui.dp(7));
            c.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { typeFilter = k; chipStyle(); refilter(true); } });
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

        // list + state
        FrameLayout body = new FrameLayout(ctx);
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
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            public void onScrollStateChanged(AbsListView v, int s) { if (s != SCROLL_STATE_IDLE) hideKeys(); }
            public void onScroll(AbsListView v, int a, int b, int c) {}
        });
        body.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        state = text("", 15, Ui.MUTE);
        state.setGravity(Gravity.CENTER);
        state.setPadding(Ui.dp(20), 0, Ui.dp(20), 0);
        state.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { if (!loading && all.isEmpty()) fetch(); } });
        body.addView(state, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void chipStyle() {
        for (int i = 0; i < 5; i++) {
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
            public void ok(List<Addon> l) { loading = false; all = l; refilter(false); }
            public void fail() {
                loading = false;
                if (all.isEmpty()) setState("Cannot load addons.\nCheck internet, tap to retry.");
            }
        });
    }

    private void setState(String s) {
        state.setText(s);
        state.setVisibility(s.length() == 0 ? View.GONE : View.VISIBLE);
    }

    private void refilter(boolean toTop) {
        List<Addon> typed = new ArrayList<>();
        for (Addon a : all) {
            if (typeFilter > 0 && !KEYS[typeFilter].equals(a.type)) continue;
            typed.add(a);
        }
        view = Fuzzy.search(typed, search == null ? "" : search.getText().toString());
        count.setText(all.isEmpty() ? "" : view.size() + (view.size() == 1 ? " addon" : " addons"));
        if (view.isEmpty() && !all.isEmpty()) {
            String q = search.getText().toString().trim();
            setState(q.length() > 0 ? "Nothing found for\n\"" + q + "\"" : "Nothing in this tab yet.");
        } else if (view.isEmpty()) {
            if (!loading) setState("No addons yet.\nCheck back soon.");
        } else setState("");
        adapter.notifyDataSetChanged();
        if (toTop) list.setSelection(0);
    }

    // ---------- list ----------
    private final class Adapter extends BaseAdapter {
        public int getCount() { return view.size(); }
        public Object getItem(int i) { return view.get(i); }
        public long getItemId(int i) { return i; }
        public View getView(int i, View cv, ViewGroup p) {
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
        h.dl.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { download(h.a); } });
        h.q.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (h.a == null) return;
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
        b.setSingleLine(true);
    }

    private void bind(Holder h, Addon a) {
        h.a = a;
        h.name.setText(a.name);
        String sz = a.sizeText();
        h.meta.setText(a.typeLabel() + (sz.length() > 0 ? "  -  " + sz : ""));
        h.img.setTag(null);
        h.img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        h.img.setImageDrawable(Images.art(a.name, ctx.getResources()));
        if (a.banner != null) Images.load(h.img, a.banner, a.icon);
        boolean o = open.contains(a.id);
        if (a.desc.trim().length() == 0) o = false;
        h.desc.setVisibility(o ? View.VISIBLE : View.GONE);
        h.desc.setText(a.desc);
        h.q.setVisibility(a.desc.trim().length() > 0 ? View.VISIBLE : View.GONE);
        if (a.desc.trim().length() == 0) o = false;
        h.q.setText(o ? "-" : "?");
        btn(h.dl, a);
    }

    private void btn(PBtn b, Addon a) {
        Integer p = prog.get(a.id);
        if (p != null) {
            b.pct = Math.max(0, p);
            b.setText(p >= 0 ? p + "%" : "...");
            b.setAlpha(1f);
        } else {
            b.pct = -1;
            b.setText(done.contains(a.id) || Downloader.have(ctx, a) ? "INSTALL" : "DOWNLOAD");
        }
        b.invalidate();
    }

    private void refreshRow(String id) {
        for (int i = 0; i < list.getChildCount(); i++) {
            Holder h = (Holder) list.getChildAt(i).getTag(R.id.holder);
            if (h != null && h.a != null && h.a.id.equals(id)) btn(h.dl, h.a);
        }
    }

    private void download(final Addon a) {
        if (a == null || prog.containsKey(a.id)) return;
        if (done.contains(a.id) || Downloader.have(ctx, a)) {
            Downloader.install(ctx, a.safeFile());
            hide();
            return;
        }
        prog.put(a.id, -1);
        refreshRow(a.id);
        Downloader.start(ctx.getApplicationContext(), a.download, a.safeFile(), new Downloader.Listener() {
            public void progress(int pct) { prog.put(a.id, pct); refreshRow(a.id); }
            public void done(boolean ok) {
                prog.remove(a.id);
                if (ok) done.add(a.id);
                refreshRow(a.id);
                if (ok && shown) hide();
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
        if (all.isEmpty()) {
            List<Addon> c = Api.cached(ctx);
            if (c != null) { all = c; refilter(false); }
        }
        if (all.isEmpty() || System.currentTimeMillis() - Api.lastFetch > 10000) fetch();
        root.setAlpha(0f);
        card.setScaleX(0.94f);
        card.setScaleY(0.94f);
        root.animate().alpha(1f).setDuration(120).start();
        card.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
        root.requestFocus();
    }

    void hide() {
        if (!shown) return;
        shown = false;
        hideKeys();
        try { wm.removeView(root); } catch (Exception ignored) {}
        if (onClosed != null) onClosed.run();
    }

    void destroy() { hide(); root = null; }
}
