package com.imgmine.agentic;

import java.util.Locale;
import org.json.JSONObject;

/**
 * Local rule-based helper: instant execution for trivial single commands ("back karo",
 * "scroll up") WITHOUT any API call. This is pattern matching, not an AI - it never
 * guesses; anything longer or ambiguous goes to the real AI unchanged.
 */
final class LocalAssist {
    /** Returns an action JSONObject for svc.execBlocking, or null when not a trivial command. */
    static JSONObject match(String instruction) {
        if (instruction == null) return null;
        String t = instruction.toLowerCase(Locale.ROOT).trim()
                .replaceAll("[!?.\\u0964]+$", "").replaceAll("\\s+", " ");
        if (t.isEmpty() || t.length() > 40) return null;
        try {
            if (oneOf(t, "back", "back karo", "back jao", "go back", "peeche", "peeche jao", "peeche karo",
                    "piche", "piche jao", "piche karo", "wapas jao", "wapas", "ek back", "ek back karo"))
                return new JSONObject().put("action", "back");
            if (oneOf(t, "home", "home karo", "home jao", "go home", "home screen", "home screen jao", "ghar jao"))
                return new JSONObject().put("action", "home");
            if (oneOf(t, "recents", "recent", "recent apps", "recent apps kholo", "recents kholo", "recent khol", "recent kholo"))
                return new JSONObject().put("action", "recents");
            if (oneOf(t, "scroll up", "upar scroll", "upar scroll karo", "swipe up", "upar karo", "scroll upar"))
                return new JSONObject().put("action", "scroll").put("direction", "up");
            if (oneOf(t, "scroll down", "neeche scroll", "neeche scroll karo", "swipe down", "neeche karo", "scroll neeche", "niche scroll", "niche scroll karo"))
                return new JSONObject().put("action", "scroll").put("direction", "down");
            if (oneOf(t, "scroll left", "baye scroll", "left scroll karo", "swipe left"))
                return new JSONObject().put("action", "scroll").put("direction", "left");
            if (oneOf(t, "scroll right", "daye scroll", "right scroll karo", "swipe right"))
                return new JSONObject().put("action", "scroll").put("direction", "right");
            if (oneOf(t, "volume up", "volume badhao", "awaaz badhao", "awaz badhao", "sound badhao", "volume high karo"))
                return new JSONObject().put("action", "volume_up");
            if (oneOf(t, "volume down", "volume kam karo", "awaaz kam karo", "awaz kam karo", "sound kam karo", "volume low karo"))
                return new JSONObject().put("action", "volume_down");
            if (oneOf(t, "lock", "lock karo", "screen lock", "screen lock karo", "phone lock", "phone lock karo"))
                return new JSONObject().put("action", "lock_screen");
            if (oneOf(t, "notifications", "notifications kholo", "notification kholo", "notification panel kholo"))
                return new JSONObject().put("action", "notifications");
            if (oneOf(t, "quick settings", "quick settings kholo", "quick setting kholo"))
                return new JSONObject().put("action", "quick_settings");
        } catch (Exception ignored) {}
        return null;
    }

    private static boolean oneOf(String t, String... options) {
        for (String o : options) if (t.equals(o)) return true;
        return false;
    }
}
