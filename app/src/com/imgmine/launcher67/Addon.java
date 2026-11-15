package com.imgmine.launcher67;

final class Addon {
    String id = "", name = "", desc = "", banner, download = "", type = "file", filename = "", source;
    long size, added;
    boolean icon;
    String lname = "", ldesc = "";

    String safeFile() {
        String ext = ".mcaddon";
        int dot = filename.lastIndexOf('.');
        if (dot >= 0 && filename.length() - dot <= 12) ext = filename.substring(dot).toLowerCase();
        else {
            int d2 = download.lastIndexOf('.');
            if (d2 >= 0 && download.length() - d2 <= 12) ext = download.substring(d2).toLowerCase();
        }
        String base = id.replaceAll("[^A-Za-z0-9_-]", "_");
        if (base.length() == 0) base = "addon";
        return base + ext.replaceAll("[^A-Za-z0-9.]", "");
    }

    String sizeText() {
        if (size <= 0) return "";
        if (size >= 1048576) return String.format(java.util.Locale.US, "%.1f MB", size / 1048576.0);
        return Math.max(1, size / 1024) + " KB";
    }

    String typeLabel() { return type.equals("world") ? "WORLD" : type.equals("addon") ? "ADDON" : type.equals("pack") ? "PACK" : type.equals("template") ? "TEMPLATE" : "FILE"; }
}
