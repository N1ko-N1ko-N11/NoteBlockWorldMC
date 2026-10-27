package io.github.noteblockworldmc.nbs;

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
     * do. Stable voice-order indices add up to four bytes per note when
     * needed; playback and analysis traverse these arrays without records.
     * getNotesAt remains available for callers that need a list.
     */
    private final int[] noteTicks;
    private final int[] noteStart;
    /** Stable descending-volume indices; null when every chord is already ordered. */
    private final int[] voiceOrder;
    private final long estimatedRetainedBytes;
    private final byte[] noteInstrument;   // -1..19 fits a byte
    private final byte[] noteOctave;       // clamped -3..+3
    private final short[] noteCustomIndex; // -1..254
    private final float[] notePitch;
    private final float[] noteVolume;
    private final float[] notePan;
    /** Notes skipped because their custom instrument definition is unavailable. */
    private final int skippedCustomNotes;
    /** Notes dropped from ticks denser than the per-tick safety cap. */
    private final int skippedDenseNotes;
    /** Playable custom-instrument notes (need the server resource pack). */
    private final int customNoteCount;
    /** Retained notes per custom instrument, after merging and density limits. */
    private final int[] customNoteCounts;
    /** Duplicate notes folded into an existing note on the same tick. */
    private final int mergedNotes;
    /** Custom instruments' sound files, index-aligned ("" = none/consumed). */
    private final List<String> customSoundFiles;
    /** Mid-song tempo changes: tick -> new tempo (ticks/second). */
    private final TreeMap<Integer, Double> tempoChanges;
    private final int[] tempoTicks;
    private final double[] tempoSeconds, tempoRates;
    private final double durationSeconds;
    /** Loop settings from the NBS header (v4+). */
    private final boolean loopEnabled;
    /** 0 = loop forever. */
    private final int maxLoopCount;
    private final int loopStartTick;

    NbsSong(String name, String author, double tempo, int lengthTicks,
            Map<Integer, List<PlayableNote>> notesByTick, int skippedCustomNotes,
            int skippedDenseNotes, int mergedNotes,
            List<String> customSoundFiles,
            TreeMap<Integer, Double> tempoChanges,
            boolean loopEnabled, int maxLoopCount, int loopStartTick) {
        this.name = io.github.noteblockworldmc.util.Limits.display(name);
        this.author = io.github.noteblockworldmc.util.Limits.display(author);
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
        this.customNoteCounts = new int[customSoundFiles.size()];

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
                if (note.isCustom()) customNoteCounts[note.customIndex()]++;
                noteIndex++;
            }
        }
        noteStart[tickCount] = noteIndex;
        int[] ordered = null;
        for (int chord = 0; chord < tickCount; chord++) {
            int from = noteStart[chord], to = noteStart[chord + 1];
            for (int i = from + 1; i < to; i++) {
                if (ordered == null) {
                    if (noteVolume[i] <= noteVolume[i - 1]) continue;
                    ordered = new int[total];
                    for (int n = 0; n < total; n++) ordered[n] = n;
                }
                int current = ordered[i], j = i;
                // Chords have at most 128 notes; equal volumes retain their original order.
                while (j > from && noteVolume[ordered[j - 1]] < noteVolume[current]) {
                    ordered[j] = ordered[j - 1]; j--;
                }
                ordered[j] = current;
            }
        }
        voiceOrder = ordered;
        this.skippedCustomNotes = skippedCustomNotes;
        this.skippedDenseNotes = skippedDenseNotes;
        this.customNoteCount = java.util.Arrays.stream(customNoteCounts).sum();
        this.mergedNotes = mergedNotes;
        // Defensive: getCustomSoundFiles() hands this list to the pack
        // builder and the analyzer. The parser already passes a copy, but
        // enforcing it here means no future caller can alias a mutable list
        // into a cached, shared song.
        this.customSoundFiles = List.copyOf(customSoundFiles);
        this.tempoChanges = new TreeMap<>(tempoChanges);
        TreeMap<Integer, Double> segments = new TreeMap<>(); segments.put(0, clampTempo(tempo));
        tempoChanges.forEach((tick, rate) -> { if (tick >= 0 && tick <= lengthTicks) segments.put(tick, clampTempo(rate)); });
        tempoTicks = new int[segments.size()]; tempoSeconds = new double[segments.size()]; tempoRates = new double[segments.size()];
        int segment = 0;
        for (var entry : segments.entrySet()) {
            tempoTicks[segment] = entry.getKey(); tempoRates[segment] = entry.getValue();
            if (segment > 0) tempoSeconds[segment] = tempoSeconds[segment-1]
                    + (tempoTicks[segment] - tempoTicks[segment-1]) / tempoRates[segment-1];
            segment++;
        }
        durationSeconds = getSecondsUpTo(lengthTicks + 1);
        this.loopEnabled = loopEnabled;
        this.maxLoopCount = maxLoopCount;
        this.loopStartTick = loopStartTick;
        long retained = 512L + 16L * total + 4L * (noteTicks.length + noteStart.length + customNoteCounts.length)
                + (voiceOrder == null ? 0 : 4L * voiceOrder.length)
                + 20L * tempoTicks.length + 96L * this.tempoChanges.size()
                + 2L * (this.name.length() + this.author.length());
        for (String file : this.customSoundFiles) retained += 64L + 2L * file.length();
        estimatedRetainedBytes = retained;
    }

    public boolean isLoopEnabled() {
        return loopEnabled;
    }

    /**
     * How many times the song repeats after its first play-through.
     *
     * Straight from the NBS header, where OpenNBS defines it as "the amount
     * of times to loop". 1 therefore means the song is heard twice, not once.
     * 0 means loop forever.
     */
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
        int i = java.util.Arrays.binarySearch(tempoTicks, Math.max(0, tick));
        if (i < 0) i = -i - 2;
        return tempoSeconds[i] + (Math.max(0, tick) - tempoTicks[i]) / tempoRates[i];
    }

    /** Inverse of the pre-integrated timeline, including fractional song ticks. */
    public double positionAtSeconds(double seconds) {
        int i = java.util.Arrays.binarySearch(tempoSeconds, Math.max(0, seconds));
        if (i < 0) i = -i - 2;
        return tempoTicks[i] + (Math.max(0, seconds) - tempoSeconds[i]) * tempoRates[i];
    }
    /** Index of the next occupied tick, avoiding scans through millions of silent ticks. */
    public int nextNoteTick(int after) {
        int i = java.util.Arrays.binarySearch(noteTicks, after);
        i = i >= 0 ? i + 1 : -i - 1;
        return i < noteTicks.length ? noteTicks[i] : Integer.MAX_VALUE;
    }
    /** Allocation-free chord and note access for playback and analysis. Arrays remain private. */
    public int chordCount() { return noteTicks.length; }
    public int chordTick(int chord) { return noteTicks[chord]; }
    public int chordStart(int chord) { return noteStart[chord]; }
    public int chordEnd(int chord) { return noteStart[chord + 1]; }
    public int firstChordAfter(int tick) {
        int i = java.util.Arrays.binarySearch(noteTicks, tick);
        return i >= 0 ? i + 1 : -i - 1;
    }
    public int orderedNoteIndex(int position) { return voiceOrder == null ? position : voiceOrder[position]; }
    public int noteInstrument(int index) { return noteInstrument[index]; }
    public int noteOctave(int index) { return noteOctave[index]; }
    public int noteCustomIndex(int index) { return noteCustomIndex[index]; }
    public float notePitch(int index) { return notePitch[index]; }
    public float noteVolume(int index) { return noteVolume[index]; }
    public float notePan(int index) { return notePan[index]; }
    /** Conservative cache weight, including primitive storage, strings and tempo-map nodes. */
    public long estimatedRetainedBytes() { return estimatedRetainedBytes; }
    public double minTempo() { return java.util.Arrays.stream(tempoRates).min().orElse(tempo); }
    public double maxTempo() { return java.util.Arrays.stream(tempoRates).max().orElse(tempo); }

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
        if (tempo <= 0 || !Double.isFinite(tempo)) return 10.0;
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
        return durationSeconds;
    }

    /**
     * Compatibility list of the notes starting at a tick, in original order.
     * Performance-sensitive callers use the chord ranges and indexed getters.
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

    public int getCustomNoteCount(int instrumentIndex) {
        return customNoteCounts[instrumentIndex];
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
