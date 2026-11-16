package com.imgmine.agentic;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local diagnostics: action log ring (last 100) + daily token estimate. No secrets, no screen content. */
public final class LogStore {
    private static final String PREFS = "agentic_logs";
    private static final int MAX = 100;

    private static SharedPreferences p(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    public static synchronized void log(Context c, long taskId, int step, String action, String result, long ms) {
        try {
            JSONArray a = new JSONArray(p(c).getString("log", "[]"));
            JSONArray n = new JSONArray();
            JSONObject o = new JSONObject();
            o.put("at", System.currentTimeMillis());
            o.put("task", taskId);
            o.put("step", step);
            o.put("action", action.length() > 80 ? action.substring(0, 80) : action);
            o.put("result", result.length() > 80 ? result.substring(0, 80) : result);
            o.put("ms", ms);
            n.put(o);
            for (int i = 0; i < a.length() && n.length() < MAX; i++) n.put(a.getJSONObject(i));
            p(c).edit().putString("log", n.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static JSONArray list(Context c) {
        try { return new JSONArray(p(c).getString("log", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    public static synchronized void clear(Context c) { p(c).edit().remove("log").apply(); }

    /** Estimated tokens spent (chars/4 heuristic). Labeled an estimate everywhere - not provider billing. */
    public static synchronized void addTokens(Context c, int est) {
        if (est <= 0) return;
        String day = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
        String key = "tok_" + day;
        p(c).edit().putInt(key, p(c).getInt(key, 0) + est).apply();
    }

    public static int tokensToday(Context c) {
        String day = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
        return p(c).getInt("tok_" + day, 0);
    }
}
