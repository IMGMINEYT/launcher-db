package com.imgmine.agentic;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Base64;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** On-device OCR (ML Kit, latin) + JPEG/base64 helpers for screenshots. Never throws. */
public final class Ocr {
    private static TextRecognizer client;
    private static volatile boolean unavailable = false;
    private static int failures = 0;
    private static volatile long cooldownUntil = 0;

    private static synchronized TextRecognizer client() {
        if (client == null) client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        return client;
    }

    /** Blocking OCR; returns quoted lines with centers, or null when unavailable/failed. */
    public static String recognize(Bitmap bmp) {
        if (unavailable || bmp == null || System.currentTimeMillis() < cooldownUntil) return null;
        final CountDownLatch l = new CountDownLatch(1);
        final String[] out = {null};
        try {
            client().process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener(t -> { failures = 0; out[0] = format(t); l.countDown(); })
                .addOnFailureListener(e -> { if (++failures >= 3) cooldownUntil = System.currentTimeMillis() + 600000; l.countDown(); });
            l.await(6, TimeUnit.SECONDS);
        } catch (Throwable t) {
            unavailable = true; // mlkit missing/model broken - stay tree-only
            return null;
        }
        return out[0];
    }

    private static String format(Text t) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Text.TextBlock b : t.getTextBlocks()) {
            for (Text.Line line : b.getLines()) {
                String s = line.getText();
                if (s == null || s.trim().isEmpty()) continue;
                s = s.replace('\n', ' ').trim();
                sb.append('"').append(s.length() > 80 ? s.substring(0, 80) : s).append('"');
                Rect r = line.getBoundingBox();
                if (r != null) sb.append(" @").append(r.centerX()).append(',').append(r.centerY());
                sb.append('\n');
                if (++n >= 40) return sb.toString();
            }
        }
        return sb.toString();
    }

    /** Downscale to max 720px and encode JPEG (q55) as base64. */
    public static String jpeg64(Bitmap src) {
        try {
            int w = src.getWidth(), h = src.getHeight(), max = 720;
            if (Math.max(w, h) > max) {
                float sc = max / (float) Math.max(w, h);
                src = Bitmap.createScaledBitmap(src, Math.round(w * sc), Math.round(h * sc), true);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            src.compress(Bitmap.CompressFormat.JPEG, 55, bos);
            return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable t) { return null; }
    }
}
