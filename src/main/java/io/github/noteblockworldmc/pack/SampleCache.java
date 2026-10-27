package io.github.noteblockworldmc.pack;

import io.github.noteblockworldmc.util.Limits;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.Objects;

/** Bounded, immutable audio snapshots. File I/O and validation never hold the cache lock. */
final class SampleCache {
    private record Revision(Path path, Object fileKey, long size, FileTime modified, FileTime created) {}
    private record Variant(String digest, int octave) {}
    private final LinkedHashMap<Object, OggTools.Sample> entries = new LinkedHashMap<>(32, .75f, true);
    private final long byteLimit;
    private long bytes, epoch;
    private static final int ENTRY_LIMIT = 2048;

    SampleCache(long byteLimit) { this.byteLimit = byteLimit; }

    OggTools.Sample read(Path path) throws IOException {
        Revision before = revision(path);
        long observed;
        synchronized (this) {
            OggTools.Sample cached = entries.get(before);
            if (cached != null) return cached;
            observed = epoch;
        }
        byte[] data;
        try (var in = Files.newInputStream(before.path(), LinkOption.NOFOLLOW_LINKS)) {
            data = Limits.read(in, Limits.SAMPLE_BYTES);
        }
        if (!before.equals(revision(path))) throw new IOException("Audio changed while reading; retry the build");
        OggTools.Sample sample = OggTools.validate(data);
        if (sample == null) throw new IOException("Invalid or oversized Ogg Vorbis sample");
        synchronized (this) {
            if (observed == epoch) {
                // A replaced file should not leave every historical revision in the cache.
                entries.entrySet().removeIf(e -> {
                    if (e.getKey() instanceof Revision r && r.path().equals(before.path())) {
                        bytes -= e.getValue().size(); return true;
                    }
                    return false;
                });
                put(before, sample);
            }
        }
        return sample;
    }

    OggTools.Sample shift(OggTools.Sample source, int octave) {
        if (octave == 0) return source;
        Variant key = new Variant(source.digest(), octave);
        long observed;
        synchronized (this) {
            OggTools.Sample cached = entries.get(key);
            if (cached != null) return cached;
            observed = epoch;
        }
        OggTools.Sample shifted = OggTools.shiftValidated(source, octave);
        if (shifted != null) synchronized (this) { if (observed == epoch) put(key, shifted); }
        return shifted;
    }

    /** Reload revalidates disk files; variants of an identical validated digest remain reusable. */
    synchronized void invalidateFiles() {
        epoch++;
        entries.entrySet().removeIf(e -> {
            if (e.getKey() instanceof Revision) { bytes -= e.getValue().size(); return true; }
            return false;
        });
    }

    synchronized void clear() { epoch++; entries.clear(); bytes = 0; }

    private void put(Object key, OggTools.Sample sample) {
        if (sample.size() > byteLimit) return;
        OggTools.Sample old = entries.put(key, sample);
        bytes += sample.size() - (old == null ? 0 : old.size());
        var iterator = entries.entrySet().iterator();
        while (bytes > byteLimit || entries.size() > ENTRY_LIMIT) {
            bytes -= iterator.next().getValue().size(); iterator.remove();
        }
    }

    private static Revision revision(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        BasicFileAttributes a = Files.readAttributes(normalized, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!a.isRegularFile() || a.size() > Limits.SAMPLE_BYTES) throw new IOException("Invalid audio file or size");
        return new Revision(normalized, Objects.toString(a.fileKey(), ""), a.size(), a.lastModifiedTime(), a.creationTime());
    }
}
