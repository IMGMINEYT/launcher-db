package com.imgmine.launcher67;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

/** Detects the installed Minecraft (and Minecraft Preview) version and normalizes it to MCPEDL style ("26.0"). */
final class McVer {
    static final String MC = "com.mojang.minecraftpe";
    static final String PREVIEW = "com.mojang.minecraftpreviewpe";

    /** Raw versionName of the installed release game, or null. */
    static String raw(Context c) { return rawOf(c, MC); }

    static String previewRaw(Context c) { return rawOf(c, PREVIEW); }

    private static String rawOf(Context c, String pkg) {
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(pkg, 0);
            return p.versionName;
        } catch (PackageManager.NameNotFoundException e) { return null; }
              catch (Exception e) { return null; }
    }

    /**
     * Normalized "major.minor" used to compare with MCPEDL version names:
     * Bedrock renamed 1.26.x to 26.x in Feb 2026, so both spellings map to "26.0" style.
     * Older versions stay "1.21" style. null when the game is missing or unparseable.
     */
    static String norm(String raw) {
        if (raw == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\.(\\d+)").matcher(raw);
        if (!m.find()) return null;
        int major = Integer.parseInt(m.group(1)), minor = Integer.parseInt(m.group(2));
        if (major == 1 && minor >= 26) return minor + ".0";
        if (major >= 26) return major + "." + minor;
        return major + "." + minor;
    }

    /** The version to match addons against: the release game if installed, else Preview. */
    static String userVersion(Context c) {
        String r = norm(raw(c));
        if (r != null) return r;
        return norm(previewRaw(c));
    }

    /** True when a declared version string fits the user's normalized version, either side may carry patch detail. */
    static boolean matches(String declared, String userVer) {
        return declared.equals(userVer) || declared.startsWith(userVer + ".") || userVer.startsWith(declared + ".");
    }

    static boolean matchesAny(java.util.List<String> declared, String userVer) {
        if (declared == null || userVer == null) return false;
        for (String d : declared) if (matches(d, userVer)) return true;
        return false;
    }

    /** Short human status for the main screen. */
    static String status(Context c) {
        String r = raw(c), p = previewRaw(c);
        if (r == null && p == null) return "Minecraft not found on this phone.";
        StringBuilder s = new StringBuilder();
        if (r != null) s.append("Minecraft ").append(r);
        if (p != null) {
            if (s.length() > 0) s.append("  +  ");
            s.append("Preview ").append(p).append(" (beta)");
        }
        return s.toString();
    }
}
