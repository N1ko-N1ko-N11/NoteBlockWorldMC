package io.github.noteblockworldmc.util;

import java.io.*;
import java.nio.file.*;

public final class Limits {
    public static final long SAMPLE_BYTES = 8L * 1024 * 1024;
    public static final double SAMPLE_SECONDS = 30;
    private Limits() {}
    public static byte[] read(Path path, long limit) throws IOException {
        if (Files.size(path) > limit) throw new IOException("File exceeds size limit");
        try (InputStream in = Files.newInputStream(path)) { return read(in, limit); }
    }
    public static byte[] read(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
        while ((n = in.read(buffer)) != -1) {
            if (n > limit - out.size()) throw new IOException("File exceeds size limit");
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Work cancelled");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
    public static String display(String text) { return display(text, 256); }
    public static String display(String text, int max) {
        if (text == null) return "";
        String clipped = text.substring(0, Math.min(max, text.length())).replaceAll("[\\p{Cntrl}\\p{Cf}§]", " ");
        return text.length() > max ? clipped + "…" : clipped;
    }
}
