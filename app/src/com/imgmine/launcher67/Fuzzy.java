package com.imgmine.launcher67;

import java.util.*;

/** Instant search: substring first, then typo-tolerant matching on words. */
final class Fuzzy {
    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]+", " ").replaceAll("\\s+", " ").trim();
    }

    static int lev(String a, String b, int max) {
        int n = a.length(), m = b.length();
        if (Math.abs(n - m) > max) return max + 1;
        int[][] d = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) d[i][0] = i;
        for (int j = 0; j <= m; j++) d[0][j] = j;
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                int c = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int v = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + c);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1))
                    v = Math.min(v, d[i - 2][j - 2] + 1);
                d[i][j] = v;
            }
        }
        return d[n][m];
    }

    /** Score of one query token against a text; 0 = no match. */
    static int tokenScore(String tok, String text, boolean isName) {
        if (text.length() == 0) return 0;
        int base = isName ? 100 : 30;
        int idx = text.indexOf(tok);
        if (idx >= 0) {
            int s = base;
            if (idx == 0 || text.charAt(idx - 1) == ' ') s += 40;
            return s;
        }
        int max = tok.length() <= 3 ? 0 : tok.length() <= 6 ? 1 : 2;
        if (max == 0) return 0;
        int best = 0;
        for (String w : text.split(" ")) {
            if (w.length() < 2) continue;
            int d = lev(tok, w, max);
            if (d <= max) best = Math.max(best, base / 2 + (max - d) * 10);
            if (w.length() > tok.length()) {
                d = lev(tok, w.substring(0, tok.length()), max);
                if (d <= max) best = Math.max(best, base / 2 + (max - d) * 10 - 5);
            }
        }
        return best;
    }

    static List<Addon> search(List<Addon> all, String query) {
        String q = norm(query);
        if (q.length() == 0) return new ArrayList<>(all);
        final String[] toks = q.split(" ");
        final Map<Addon, Integer> sc = new HashMap<>();
        List<Addon> out = new ArrayList<>();
        for (Addon a : all) {
            int total = 0;
            boolean ok = true;
            for (String t : toks) {
                int s = Math.max(tokenScore(t, a.lname, true), tokenScore(t, a.ldesc, false));
                if (s == 0) { ok = false; break; }
                total += s;
            }
            if (ok) { sc.put(a, total); out.add(a); }
        }
        Collections.sort(out, new Comparator<Addon>() {
            public int compare(Addon x, Addon y) {
                int c = sc.get(y) - sc.get(x);
                return c != 0 ? c : Long.compare(y.added, x.added);
            }
        });
        return out;
    }
}
