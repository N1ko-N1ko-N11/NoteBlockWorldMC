package dev.example.noteblockworldmc.nbs;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A parsed NBS song, ready for playback.
 */
public final class NbsSong {

    private final String name;
    private final String author;
    /** Song ticks per second (NBS header tempo / 100). */
    private final double tempo;
    /** Index of the last tick containing a note. */
    private final int lengthTicks;
    /**
     * Note storage, structure-of-arrays: the sorted ticks that contain notes,
     * a parallel start-index array (with an end sentinel), and flat per-note
     * component arrays. A worst-case song holds a million notes, and a
     * million PlayableNote records (object header, references, list slots —
     * ~48+ bytes each) costs several times the ~16 bytes/note these arrays
     * do; with up to 16 songs in the playback LRU cache that difference is
     * tens of megabytes. Records are materialized transiently by getNotesAt.
     */
    private final int[] noteTicks;
    private final int[] noteStart;
    private final byte[] noteInstrument;   // -1..19 fits a byte
    private final byte[] noteOctave;       // clamped -3..+3
    private final short[] noteCustomIndex; // -1..254
    private final float[] notePitch;
    private final float[] noteVolume;
    private final float[] notePan;
    /** Notes skipped because they use custom instruments. */
    private final int skippedCustomNotes;
    /** Notes dropped from ticks denser than the per-tick safety cap. */
    private final int skippedDenseNotes;
    /** Playable custom-instrument notes (need the server resource pack). */
    private final int customNoteCount;
    /** Duplicate notes folded into an existing note on the same tick. */
    private final int mergedNotes;
    /** Custom instruments' sound files, index-aligned ("" = none/consumed). */
    private final List<String> customSoundFiles;
    /** Mid-song tempo changes: tick -> new tempo (ticks/second). */
    private final TreeMap<Integer, Double> tempoChanges;
    /** Loop settings from the NBS header (v4+). */
    private final boolean loopEnabled;
    /** 0 = loop forever. */
    private final int maxLoopCount;
    private final int loopStartTick;

    NbsSong(String name, String author, double tempo, int lengthTicks,
            Map<Integer, List<PlayableNote>> notesByTick, int skippedCustomNotes,
            int skippedDenseNotes, int customNoteCount, int mergedNotes,
            List<String> customSoundFiles,
            TreeMap<Integer, Double> tempoChanges,
            boolean loopEnabled, int maxLoopCount, int loopStartTick) {
        this.name = name;
        this.author = author;
        this.tempo = tempo;
        this.lengthTicks = lengthTicks;
        // Flatten the parser's per-tick lists (kept there because the
        // duplicate-merge logic needs mutable lists) into primitive arrays.
        int tickCount = notesByTick.size();
        int total = 0;
        for (List<PlayableNote> tickNotes : notesByTick.values()) {
            total += tickNotes.size();
        }
        this.noteTicks = new int[tickCount];
        this.noteStart = new int[tickCount + 1];
        this.noteInstrument = new byte[total];
        this.noteOctave = new byte[total];
        this.noteCustomIndex = new short[total];
        this.notePitch = new float[total];
        this.noteVolume = new float[total];
        this.notePan = new float[total];

        // Sort primitives rather than a boxed Integer[]: an Integer[] sort
        // is TimSort with an unboxing comparison per step plus a temp array,
        // where int[] uses dual-pivot quicksort in place. Meaningful on a
        // song with hundreds of thousands of populated ticks.
        int t = 0;
        for (Integer tick : notesByTick.keySet()) {
            noteTicks[t++] = tick;
        }
        java.util.Arrays.sort(noteTicks);

        int noteIndex = 0;
        for (int i = 0; i < noteTicks.length; i++) {
            noteStart[i] = noteIndex;
            for (PlayableNote note : notesByTick.get(noteTicks[i])) {
                // These narrowing casts are safe ONLY because the parser
                // guarantees the ranges (instrument -1..19, customIndex
                // -1..254, octave -3..3). If that ever stops holding, a
                // silent wrap would corrupt instruments and index playback's
                // sound arrays out of bounds on the clock thread — a nasty,
                // far-removed failure. Fail loudly here instead.
                if (note.instrument() < Byte.MIN_VALUE || note.instrument() > Byte.MAX_VALUE
                        || note.octave() < Byte.MIN_VALUE || note.octave() > Byte.MAX_VALUE
                        || note.customIndex() < Short.MIN_VALUE
                        || note.customIndex() > Short.MAX_VALUE) {
                    throw new IllegalStateException(
                            "Note outside the parser's guaranteed range: " + note);
                }
                noteInstrument[noteIndex] = (byte) note.instrument();
                noteOctave[noteIndex] = (byte) note.octave();
                noteCustomIndex[noteIndex] = (short) note.customIndex();
                notePitch[noteIndex] = note.pitch();
                noteVolume[noteIndex] = note.volume();
                notePan[noteIndex] = note.pan();
                noteIndex++;
            }
        }
        noteStart[tickCount] = noteIndex;
        this.skippedCustomNotes = skippedCustomNotes;
        this.skippedDenseNotes = skippedDenseNotes;
        this.customNoteCount = customNoteCount;
        this.mergedNotes = mergedNotes;
        // Defensive: getCustomSoundFiles() hands this list to the pack
        // builder and the analyzer. The parser already passes a copy, but
        // enforcing it here means no future caller can alias a mutable list
        // into a cached, shared song.
        this.customSoundFiles = List.copyOf(customSoundFiles);
        this.tempoChanges = tempoChanges;
        this.loopEnabled = loopEnabled;
        this.maxLoopCount = maxLoopCount;
        this.loopStartTick = loopStartTick;
    }

    public boolean isLoopEnabled() {
        return loopEnabled;
    }

    /** 0 means loop forever. */
    public int getMaxLoopCount() {
        return maxLoopCount;
    }

    public int getLoopStartTick() {
        return loopStartTick;
    }

    /** Tempo in effect at a given tick (accounts for tempo changers). */
    public double getTempoAt(int tick) {
        Map.Entry<Integer, Double> entry = tempoChanges.floorEntry(tick);
        return clampTempo(entry != null ? entry.getValue() : tempo);
    }

    /** Seconds from the start of the song up to a tick, integrating tempo. */
    public double getSecondsUpTo(int tick) {
        double currentTempo = clampTempo(tempo);
        double seconds = 0;
        int lastTick = 0;
        for (Map.Entry<Integer, Double> change : tempoChanges.entrySet()) {
            int changeTick = change.getKey();
            if (changeTick > tick) break;
            if (changeTick > lastTick) {
                seconds += (changeTick - lastTick) / currentTempo;
                lastTick = changeTick;
            }
            currentTempo = clampTempo(change.getValue());
        }
        seconds += (tick - lastTick) / currentTempo;
        return seconds;
    }

    /** Clamps a tempo into the playable range, replacing garbage values. */
    /**
     * Keeps a tempo inside the range songs can legitimately use.
     *
     * The ceiling tracks Note Block Studio's own authoring limit, raised to
     * 180 t/s (2700 BPM) in 3.12. It was 100 here, which silently played any
     * faster song at up to 44% below its intended speed rather than rejecting
     * it. The clamp still exists to contain corrupt values -- the file format
     * can express far higher tempos than anyone can author.
     *
     * Note that at 180 t/s a song tick is only 5.6 ms, so playback-precision-ms
     * needs to be well below that for the timing to be meaningful.
     */
    public static double clampTempo(double tempo) {
        if (tempo <= 0 || Double.isNaN(tempo)) return 10.0;
        return Math.max(0.2, Math.min(180.0, tempo));
    }

    /** The tempo change taking effect at this tick, or null. */
    public Double getTempoChangeAt(int tick) {
        return tempoChanges.get(tick);
    }

    public String getName() {
        return name;
    }

    public String getAuthor() {
        return author;
    }

    public double getTempo() {
        return tempo;
    }

    public int getLengthTicks() {
        return lengthTicks;
    }

    /**
     * Duration in seconds, integrating mid-song tempo changes segment by
     * segment (a constant-tempo estimate is wrong for tempo-changer songs).
     */
    public double getDurationSeconds() {
        double currentTempo = clampTempo(tempo);
        double seconds = 0;
        int lastTick = 0;
        for (Map.Entry<Integer, Double> change : tempoChanges.entrySet()) {
            int tick = change.getKey();
            if (tick > lengthTicks) break;
            if (tick > lastTick) {
                seconds += (tick - lastTick) / currentTempo;
                lastTick = tick;
            }
            currentTempo = clampTempo(change.getValue());
        }
        seconds += (lengthTicks + 1 - lastTick) / currentTempo;
        return seconds;
    }

    /**
     * The notes starting at a tick. Records are built transiently from the
     * flat arrays — a handful of short-lived young-gen objects per audible
     * tick, in exchange for the several-times-smaller cached footprint.
     */
    public List<PlayableNote> getNotesAt(int tick) {
        int i = java.util.Arrays.binarySearch(noteTicks, tick);
        if (i < 0) return List.of();
        int from = noteStart[i];
        int to = noteStart[i + 1];
        List<PlayableNote> notes = new java.util.ArrayList<>(to - from);
        for (int n = from; n < to; n++) {
            notes.add(new PlayableNote(noteInstrument[n], notePitch[n], noteVolume[n],
                    noteOctave[n], noteCustomIndex[n], notePan[n]));
        }
        return notes;
    }

    public int getSkippedCustomNotes() {
        return skippedCustomNotes;
    }

    public int getSkippedDenseNotes() {
        return skippedDenseNotes;
    }

    /** Playable custom-instrument notes (audible only with the pack). */
    public int getCustomNoteCount() {
        return customNoteCount;
    }

    public int getMergedNotes() {
        return mergedNotes;
    }

    /** Total playable notes after merging. */
    public int getNoteCount() {
        return noteInstrument.length;
    }

    /** Custom instruments' sound files, index-aligned ("" = unavailable). */
    public List<String> getCustomSoundFiles() {
        return customSoundFiles;
    }
}
