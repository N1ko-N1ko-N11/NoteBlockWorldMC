package io.github.noteblockworldmc.playback;

import io.github.noteblockworldmc.nbs.NbsSong;
import java.util.LinkedHashMap;

/** LRU bounded by both estimated retained bytes and song count. Active sessions own their songs separately. */
final class SongCache {
    private final LinkedHashMap<String, NbsSong> songs = new LinkedHashMap<>(16, .75f, true);
    private final long byteLimit;
    private final int entryLimit;
    private long bytes;
    SongCache(long byteLimit, int entryLimit) { this.byteLimit = byteLimit; this.entryLimit = entryLimit; }
    synchronized NbsSong get(String id) { return songs.get(id); }
    synchronized void put(String id, NbsSong song) {
        remove(id);
        if (song.estimatedRetainedBytes() > byteLimit) return;
        songs.put(id, song); bytes += song.estimatedRetainedBytes();
        var iterator = songs.entrySet().iterator();
        while (bytes > byteLimit || songs.size() > entryLimit) {
            bytes -= iterator.next().getValue().estimatedRetainedBytes(); iterator.remove();
        }
    }
    synchronized void remove(String id) {
        NbsSong old = songs.remove(id);
        if (old != null) bytes -= old.estimatedRetainedBytes();
    }
    synchronized void clear() { songs.clear(); bytes = 0; }
}
