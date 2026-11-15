package com.imgmine.launcher67;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.widget.TextView;

final class Ui {
    static float D = 3f;
    static Typeface PX = Typeface.MONOSPACE;
    static final int BG = 0xFF14171C, BG2 = 0xFF1B1F26, CARD = 0xFF20252D, LINE = 0xFF2E3540,
            INK = 0xFFE9E6DC, MUTE = 0xFF9AA3AD, GREEN = 0xFF5FD068, GREEN_D = 0xFF2F8A39,
            GOLD = 0xFFE8B33A, RED = 0xFFD9534F;

    static void init(Context c) {
        D = c.getResources().getDisplayMetrics().density;
        try { PX = Typeface.createFromAsset(c.getAssets(), "pixelify.ttf"); } catch (Throwable t) { PX = Typeface.MONOSPACE; }
    }

    static int dp(float v) { return Math.round(v * D); }

    /** Flat pixel block with a light top-left edge and dark bottom-right edge. */
    static final class Bevel extends Drawable {
        final int fill, light, dark, border;
        final Paint p = new Paint();
        Bevel(int fill, int light, int dark, int border) {
            this.fill = fill; this.light = light; this.dark = dark; this.border = border;
            p.setAntiAlias(false);
        }
        @Override public void draw(Canvas c) {
            Rect r = getBounds();
            int b = dp(2), t = dp(2);
            p.setColor(border); c.drawRect(r, p);
            p.setColor(fill); c.drawRect(r.left + b, r.top + b, r.right - b, r.bottom - b, p);
            p.setColor(light);
            c.drawRect(r.left + b, r.top + b, r.right - b, r.top + b + t, p);
            c.drawRect(r.left + b, r.top + b, r.left + b + t, r.bottom - b, p);
            p.setColor(dark);
            c.drawRect(r.left + b, r.bottom - b - t, r.right - b, r.bottom - b, p);
            c.drawRect(r.right - b - t, r.top + b, r.right - b, r.bottom - b, p);
        }
        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(ColorFilter f) {}
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }

    static Drawable panel(int fill) { return new Bevel(fill, shade(fill, 1.15f), shade(fill, 0.8f), 0xFF000000); }

    static int shade(int c, float f) {
        int r = Math.min(255, Math.round(Color.red(c) * f)), g = Math.min(255, Math.round(Color.green(c) * f)), b = Math.min(255, Math.round(Color.blue(c) * f));
        return Color.rgb(r, g, b);
    }

    static Drawable buttonBg(int fill) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, new Bevel(shade(fill, 0.85f), shade(fill, 0.7f), shade(fill, 1.1f), 0xFF000000));
        s.addState(new int[0], new Bevel(fill, shade(fill, 1.2f), shade(fill, 0.7f), 0xFF000000));
        return s;
    }

    static TextView button(Context c, String text, int fill, int ink) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTypeface(PX);
        t.setTextSize(14);
        t.setTextColor(ink);
        t.setGravity(Gravity.CENTER);
        t.setBackground(buttonBg(fill));
        t.setPadding(dp(14), dp(8), dp(14), dp(10));
        t.setClickable(true);
        return t;
    }
}
