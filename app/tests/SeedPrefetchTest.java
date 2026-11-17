package com.imgmine.launcher67;

import android.content.Context;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SeedPrefetchTest {

    private static Object get(Object o, String n) throws Exception {
        Field f = o instanceof Panel ? Panel.class.getDeclaredField(n) : o.getClass().getDeclaredField(n);
        f.setAccessible(true);
        return f.get(o);
    }

    private static Panel newPanel() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        Ui.init(c);
        Panel p = new Panel(c, new Runnable() { public void run() {} });
        Method b = Panel.class.getDeclaredMethod("build");
        b.setAccessible(true);
        b.invoke(p);
        return p;
    }

    @Test
    @SuppressWarnings("unchecked")
    public void seedCardsGainShortDescriptionAfterDetailLoad() throws Exception {
        Panel p = newPanel();
        TextView[] chips = (TextView[]) get(p, "chips");
        chips[5].performClick(); // SEEDS
        ShadowLooper.runUiThreadTasks();
        Method lm = Panel.class.getDeclaredMethod("loadMore");
        lm.setAccessible(true);
        lm.invoke(p);
        long t0 = System.currentTimeMillis();
        while ((Boolean) get(p, "feedLoading") && System.currentTimeMillis() - t0 < 30000) {
            ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS);
            ShadowLooper.runUiThreadTasks();
        }
        List<Addon> seedFeed = (List<Addon>) get(p, "seedFeed");
        assertTrue("no seeds loaded", seedFeed.size() > 0);
        Addon a = seedFeed.get(0);
        // browse contract: cards carry no description (no scroll-blocking detail fetches)
        java.lang.reflect.Field cf = Seeds.class.getDeclaredField("CACHE");
        cf.setAccessible(true);
        ((java.util.Map<?, ?>) cf.get(null)).clear();
        java.util.List<Addon> fresh = Seeds.browse("village");
        assertTrue("village tag has seeds", fresh != null && !fresh.isEmpty());
        assertEquals("browse must not fetch descriptions", "", fresh.get(0).desc);

        // bind a real row: before detail load, description hidden
        ListView list = (ListView) get(p, "list");
        int rowIndex = -1;
        List<Addon> view = (List<Addon>) get(p, "view");
        for (int i = 0; i < view.size(); i++) if (view.get(i) == a) { rowIndex = i; break; }
        assertTrue("seed not in view", rowIndex >= 0);
        View row = list.getAdapter().getView(rowIndex, null, list);
        Object holder = row.getTag(com.imgmine.launcher67.R.id.holder);
        assertNotNull("row has no holder", holder);
        TextView desc = (TextView) get(holder, "desc");
        // idle prefetch may already have filled the description during the wait above;
        // visibility must match the card's own desc state either way
        int expect = a.desc.length() == 0 ? View.GONE : View.VISIBLE;
        assertEquals("desc visibility must match card state", expect, desc.getVisibility());

        // detail arrives (what the idle prefetch does), row rebinds by identity
        assertTrue(Seeds.fillDetail(a));
        Method rv = Panel.class.getDeclaredMethod("bind", holder.getClass(), Addon.class);
        rv.setAccessible(true);
        rv.invoke(p, holder, a);
        assertEquals("short description not visible after load", View.VISIBLE, desc.getVisibility());
        assertEquals("short description must be capped at 2 lines", 2, desc.getMaxLines());
        TextView q = (TextView) get(holder, "q");
        assertEquals("? button must be visible for seeds", View.VISIBLE, q.getVisibility());
        System.out.println("SEED CARD: [" + a.seed + "] " + a.name + " desc[" + a.desc.length() + "] " + a.desc.substring(0, Math.min(80, a.desc.length())));
    }
}
