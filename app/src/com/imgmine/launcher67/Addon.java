package com.imgmine.launcher67;

final class Addon {
    String id = "", name = "", desc = "", banner, download = "", type = "file", filename = "", source;
    long size, added;
    boolean icon, cf, nine, laby, slim;
    String page = "", author = "", labyHash = "";
    String seed, seedCoords;          // WiseHosting seed string + coords; null until detail loaded
    String lname = "", ldesc = "";
    java.util.List<String> vers;   // MCPEDL declared supported versions; null = not checked or none found
    Mc.Info info;                  // parsed post page (files + versions); null until loaded

    String srcLabel() { return cf ? "MCPEDL" : nine ? "9MINECRAFT" : laby ? "LABY" : ""; }

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

    String typeLabel() { if (type.equals("texture")) return "TEXTURES"; if (type.equals("hdr")) return ""; if (type.equals("skin")) return laby ? "SKIN" : "SKIN PACK"; if (type.equals("seed")) return "SEED"; return type.equals("world") ? "WORLD" : type.equals("addon") ? "ADDON" : type.equals("pack") ? "PACK" : type.equals("template") ? "TEMPLATE" : "FILE"; }
}
