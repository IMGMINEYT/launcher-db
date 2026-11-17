package com.imgmine.launcher67;

import java.io.ByteArrayOutputStream;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Wraps a raw skin PNG into a minimal .mcpack skin pack that Bedrock imports. */
final class SkinPack {
    static byte[] build(String name, byte[] png, boolean slim) throws Exception {
        String head = UUID.randomUUID().toString(), mod = UUID.randomUUID().toString();
        String safe = name.replace("\"", "'").replace("\\", " ");
        if (safe.trim().length() == 0) safe = "Skin";
        String manifest = "{\"format_version\":2,\"header\":{\"name\":\"" + safe + "\","
                + "\"description\":\"Single skin from 67 Launcher\",\"uuid\":\"" + head + "\",\"version\":[1,0,0],"
                + "\"min_engine_version\":[1,16,0]},\"modules\":[{\"type\":\"skin_pack\",\"uuid\":\"" + mod + "\",\"version\":[1,0,0]}]}";
        String skins = "{\"serialize_name\":\"67skin\",\"localization_name\":\"67skin\","
                + "\"skins\":[{\"localization_name\":\"s1\",\"geometry\":\""
                + (slim ? "geometry.humanoid.customSlim" : "geometry.humanoid.custom")
                + "\",\"texture\":\"skin.png\",\"type\":\"free\"}]}";
        String en = "{\"skin.67skin.s1\":\"" + safe + "\"}";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        ZipOutputStream z = new ZipOutputStream(bo);
        z.putNextEntry(new ZipEntry("manifest.json"));
        z.write(manifest.getBytes("UTF-8"));
        z.closeEntry();
        z.putNextEntry(new ZipEntry("skins.json"));
        z.write(skins.getBytes("UTF-8"));
        z.closeEntry();
        z.putNextEntry(new ZipEntry("texts/en_US.lang"));
        z.write(en.getBytes("UTF-8"));
        z.closeEntry();
        z.putNextEntry(new ZipEntry("skin.png"));
        z.write(png);
        z.closeEntry();
        z.close();
        return bo.toByteArray();
    }
}
