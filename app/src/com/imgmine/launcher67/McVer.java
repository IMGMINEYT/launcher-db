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
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)+)").matcher(raw);
        if (!m.find()) return null;
        String v = m.group(1);
        String[] parts = v.split("\\.");
        if (parts.length >= 2 && "1".equals(parts[0])) {
            try {
                if (Integer.parseInt(parts[1]) >= 26) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 1; i < parts.length; i++) { if (i > 1) sb.append('.'); sb.append(parts[i]); }
                    return sb.toString();
                }
            } catch (Exception ignored) {}
        }
        return v;
    }

    /** The version to match addons against: the release game if installed, else Preview. */
    static String userVersion(Context c) {
        String r = norm(raw(c));
        if (r != null) return r;
        return norm(previewRaw(c));
    }

    /** Selection policy: exact/broad versions, plus the owner's explicit 26.x forward rule.
     * This is a file-selection rule, not a guarantee that a pack runs without game changes. */
    static boolean matches(String declared, String userVer) {
        if(declared==null || userVer==null)return false;
        String d=norm(declared),u=norm(userVer);
        if(d==null||u==null)return false;
        if(d.equals(u)||u.startsWith(d+"."))return true;
        // Owner-requested 26.x forward-selection rule, not a tested compatibility guarantee.
        if(d.startsWith("26.")&&u.startsWith("26.")) {
            String[] a=d.split("\\."),b=u.split("\\.");
            for(int i=0;i<Math.max(a.length,b.length);i++) {
                try {
                    int x=i<a.length?Integer.parseInt(a[i]):0,y=i<b.length?Integer.parseInt(b[i]):0;
                    if(x!=y)return x<y;
                } catch(NumberFormatException badVersion) { return false; }
            }
            return true;
        }
        return false;
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
