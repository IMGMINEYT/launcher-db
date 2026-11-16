package com.imgmine.agentic;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

/** Persistent task history: states per master prompt Part 10. Plain local store, no secrets. */
public final class TaskStore {
    private static final String PREFS = "agentic_tasks";
    private static final int MAX = 50;

    public static final String RUNNING = "running", PAUSED = "paused", COMPLETED = "completed",
            FAILED = "failed", CANCELLED = "cancelled", WAITING = "waiting";

    private static SharedPreferences p(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    private static JSONArray read(Context c) {
        try {
            String s = p(c).getString("list", null);
            if (s != null) return new JSONArray(s);
        } catch (Exception ignored) {}
        return new JSONArray();
    }

    private static void write(Context c, JSONArray a) { p(c).edit().putString("list", a.toString()).apply(); }

    /** Register a new task; returns its id. */
    public static synchronized long begin(Context c, String instruction) {
        long id = System.currentTimeMillis();
        try {
            JSONArray a = read(c);
            JSONArray n = new JSONArray();
            n.put(new JSONObject().put("id", id).put("text", instruction).put("state", RUNNING)
                .put("at", id).put("note", ""));
            for (int i = 0; i < a.length() && n.length() < MAX; i++) n.put(a.getJSONObject(i));
            write(c, n);
        } catch (Exception ignored) {}
        return id;
    }

    /** Update state (+ optional note) for a task. */
    public static synchronized void update(Context c, long id, String state, String note) {
        try {
            JSONArray a = read(c);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (o.optLong("id") == id) {
                    o.put("state", state);
                    if (note != null) o.put("note", note.length() > 120 ? note.substring(0, 120) : note);
                    o.put("ended", System.currentTimeMillis());
                    break;
                }
            }
            write(c, a);
        } catch (Exception ignored) {}
    }

    /** Newest-first list. */
    public static JSONArray list(Context c) { return read(c); }

    public static synchronized void clear(Context c) { p(c).edit().clear().apply(); }

    public static String icon(String state) {
        switch (state) {
            case COMPLETED: return "\u2713";
            case FAILED: return "\u2717";
            case CANCELLED: return "\u25A0";
            case RUNNING: return "\u25B6";
            case PAUSED: return "\u275A\u275A";
            case WAITING: return "?";
            default: return "\u2022";
        }
    }
}
