package com.imgmine.launcher67;

import static org.junit.Assert.*;
import android.content.Context;
import android.widget.ListView;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.*;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import android.app.AlertDialog;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CmdTest {
    private static final String ASSETS = System.getProperty("launcher.root", ".") + "/app/assets/";

    private static Object get(Object o, String n) throws Exception {
        Field f = o.getClass().getDeclaredField(n); f.setAccessible(true); return f.get(o);
    }
    @SuppressWarnings("unchecked")
    private static Object getP(Panel p, String n) throws Exception {
        Field f = Panel.class.getDeclaredField(n); f.setAccessible(true); return f.get(p);
    }

    @Test
    public void commandDataIsHonestAndComplete() throws Exception {
        byte[] b = new byte[(int) new File(ASSETS + "commands.json").length()];
        FileInputStream in = new FileInputStream(ASSETS + "commands.json");
        int off = 0, r;
        while ((r = in.read(b, off, b.length - off)) > 0) off += r;
        in.close();
        JSONArray a = new JSONObject(new String(b, "UTF-8")).getJSONArray("c");
        assertTrue("900+ templates", a.length() > 900);
        int chat = 0, block = 0, warn = 0;
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject j = a.getJSONObject(i);
            assertTrue("starts with /: " + j.getString("c"), j.getString("c").startsWith("/"));
            String cmd = j.getString("c");
            boolean rawJson = cmd.startsWith("/tellraw") || cmd.startsWith("/titleraw") || cmd.contains(" tellraw ") || cmd.contains(" titleraw ");
            assertTrue("no Java NBT braces: " + cmd, rawJson || !cmd.contains("{"));
            assertTrue("icon present", new File(ASSETS + "icons/" + j.getString("icon") + ".png").exists());
            assertTrue("unique id", ids.add(j.getString("id")));
            if (j.getString("cat").equals("block")) block++; else chat++;
            if (j.optBoolean("warn")) warn++;
        }
        int daily=0;for(int i=0;i<a.length();i++)if(a.getJSONObject(i).optBoolean("daily"))daily++;
        assertTrue("daily shortcuts are curated",daily>=20&&daily<=60);
        assertTrue("both subcategories present", chat > 0 && block > 0);
        assertTrue("danger warnings present", warn >= 20);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void commandsTabShowsEditCopyRowsAndDialogCopiesEditedText() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        Ui.init(c);
        Panel p = new Panel(c, new Runnable() { public void run() {} });
        Method b = Panel.class.getDeclaredMethod("build"); b.setAccessible(true); b.invoke(p);

        TextView[] chips = (TextView[]) getP(p, "chips");
        assertEquals(7, chips.length);
        assertEquals("COMMANDS", chips[6].getText().toString());
        chips[6].performClick();
        ShadowLooper.runUiThreadTasks();
        assertEquals(6, getP(p, "typeFilter"));
        android.widget.HorizontalScrollView cmdRow = (android.widget.HorizontalScrollView) getP(p, "cmdRow");
        assertEquals(android.view.View.VISIBLE, cmdRow.getVisibility());
        ((android.widget.LinearLayout)cmdRow.getChildAt(0)).getChildAt(1).performClick(); // Chat, Daily is now first

        // inject two synthetic command rows (asset loading tested separately above)
        Cmds.Entry e1 = new Cmds.Entry(); e1.id = "c1"; e1.cat = "chat"; e1.n = "Heal"; e1.c = "/effect @s instant_health 1 2"; e1.icon = "ic_effect";
        Cmds.Entry e2 = new Cmds.Entry(); e2.id = "c2"; e2.cat = "block"; e2.n = "Kill all"; e2.c = "/kill @e"; e2.warn = true; e2.icon = "ic_cmd";
        List<Addon> feed = (List<Addon>) getP(p, "cmdFeed");
        feed.add(Cmds.toAddon(e1)); feed.add(Cmds.toAddon(e2));
        Method rf = Panel.class.getDeclaredMethod("refilter", boolean.class); rf.setAccessible(true); rf.invoke(p, true);
        List<Addon> view = (List<Addon>) getP(p, "view");
        int found = 0;
        for (Addon x : view) if ("command".equals(x.type)) found++;
        assertEquals("chat subcategory shows only chat entries", 1, found);

        // row binding: EDIT + COPY label + always-visible command preview
        ListView list = (ListView) getP(p, "list");
        int idx = -1;
        for (int i = 0; i < view.size(); i++) if ("command".equals(view.get(i).type)) { idx = i; break; }
        android.view.View row = list.getAdapter().getView(idx, null, list);
        Object holder = row.getTag(com.imgmine.launcher67.R.id.holder);
        assertNotNull(holder);
        TextView dl = (TextView) get(holder, "dl");
        assertEquals("EDIT + COPY", dl.getText().toString());
        TextView desc = (TextView) get(holder, "desc");
        assertEquals(android.view.View.VISIBLE, desc.getVisibility());
        assertEquals("/effect @s instant_health 1 2", desc.getText().toString());

        // dialog: shows editable command, warn text for dangerous entries, Copy puts EDITED text on clipboard
        Method ed = Panel.class.getDeclaredMethod("editCopyCmd", Addon.class); ed.setAccessible(true);
        ed.invoke(p, Cmds.toAddon(e2));
        AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("edit dialog shown", d);
        android.widget.EditText et = findEdit(d.getWindow().getDecorView());
        assertNotNull("dialog has editable command", et);
        assertEquals("/kill @e", et.getText().toString());
        et.setText("/kill @e[type=zombie]");
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.runUiThreadTasks();
        android.content.ClipboardManager cm = (android.content.ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
        assertEquals("edited text copied", "/kill @e[type=zombie]", cm.getPrimaryClip().getItemAt(0).getText().toString());
    }

    private static android.widget.EditText findEdit(android.view.View v) {
        if (v instanceof android.widget.EditText) return (android.widget.EditText) v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.widget.EditText r = findEdit(g.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }
}
