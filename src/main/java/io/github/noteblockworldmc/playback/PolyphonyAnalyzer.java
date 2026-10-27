package io.github.noteblockworldmc.playback;

import io.github.noteblockworldmc.NoteblockWorldMCPlugin;
import io.github.noteblockworldmc.nbs.NbsSong;

import java.util.Arrays;
import java.util.Map;

/**
 * Works out how many client sound channels a song actually demands.
 *
 * The client has a fixed pool of roughly 250 channels for the WHOLE game
 * (footsteps, mobs, ambience — everything), and a sound holds its channel for
 * its entire sample, not just its onset. So the thing that starves a song is
 * not notes-per-tick but overlap:
 *
 *     concurrent voices = notes still ringing at the same instant
 *
 * Rather than guess how long a harp rings, every sample's true length is read
 * from its Ogg granule position. Rate-shifted octave variants are shorter or
 * longer by exactly their shift, which this accounts for — a note three
 * octaves up plays eight times faster and therefore frees its channel eight
 * times sooner.
 */
public final class PolyphonyAnalyzer {

    /**
     * Sound channels a VANILLA client has, shared with every other game
     * sound. Mods that enlarge the audio engine's source pool raise this, so
     * it is only the default for playback.client-channels.
     */
    public static final int DEFAULT_CLIENT_CHANNELS = 247;
    /** Leave this share of the pool for footsteps, mobs and ambience. */
    private static final double SAFE_FRACTION = 0.8;
    /** Used when a sample's real length can't be read. */
    private static final double FALLBACK_SECONDS = 1.0;

    /** How many slices the density strip is drawn with. */
    public static final int DENSITY_BUCKETS = 24;

    public record Report(String title, double durationSeconds, int noteCount,
                         int mergedNotes, int peakVoices, double peakAtSeconds,
                         double meanVoices, boolean estimated, int clientChannels,
                         double tempo, double minTempo, double maxTempo,
                         double[] density, double observationSeconds, int excludedNotes, String scope) {

        /** Note Block Studio counts 4 ticks to a beat, so BPM = t/s * 15. */
        public double beatsPerMinute() {
            return tempo * 15.0;
        }

        /** True when the song contains mid-song tempo changes. */
        public boolean tempoVaries() {
            return maxTempo - minTempo > 1e-6;
        }

        /** Channels left for the song after reserving room for game audio. */
        public int safeBudget() {
            return (int) (clientChannels * SAFE_FRACTION);
        }

        public boolean exceedsPool() {
            return peakVoices > clientChannels;
        }

        public boolean exceedsSafeBudget() {
            return peakVoices > safeBudget();
        }
    }

    /** The configured client pool size, defaulting to the vanilla figure. */
    public static int clientChannels(NoteblockWorldMCPlugin plugin) {
        return Math.max(16, Math.min(8192, plugin.getConfig()
                .getInt("playback.client-channels", DEFAULT_CLIENT_CHANNELS)));
    }

    private PolyphonyAnalyzer() {
    }

    /** One pass of arrangement demand before voice trimming, fades and humanization. */
    public static Report analyze(NoteblockWorldMCPlugin plugin, NbsSong song, String publicId) {
        return analyze(plugin, song, publicId, null);
    }
    public static Report analyze(NoteblockWorldMCPlugin plugin, NbsSong song, String publicId, java.util.UUID listener) {
        var pack = plugin.getResourcePackService();
        Map<String, Double> manifest = pack.eventDurations(listener);
        double[] vanilla = new double[io.github.noteblockworldmc.pack.ResourcePackService.INSTRUMENT_NAMES.length];
        Arrays.fill(vanilla, -1);
        int vanillaCount = vanilla.length;
        Timing[][] timings = new Timing[vanillaCount + song.getCustomSoundFiles().size()][7];
        String[] customBases = new String[song.getCustomSoundFiles().size()];
        if (publicId != null) for (int i = 0; i < customBases.length; i++) {
            if (song.getCustomNoteCount(i) > 0) customBases[i] = io.github.noteblockworldmc.pack.ResourcePackService
                    .customSoundEvent(publicId, song.getCustomSoundFiles().get(i));
        }
        float audibleFloor = PlaybackManager.configuredAudibleFloor(plugin);
        int total = song.getNoteCount(), n = 0, excluded = 0;
        double[] starts = new double[total], ends = new double[total];
        boolean estimated = false;
        for (int chord = 0; chord < song.chordCount(); chord++) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            double onset = song.getSecondsUpTo(song.chordTick(chord));
            for (int note = song.chordStart(chord); note < song.chordEnd(chord); note++) {
                float noteVolume = song.noteVolume(note);
                if (!(noteVolume > 0f) || noteVolume < audibleFloor) { excluded++; continue; }
                int custom = song.noteCustomIndex(note), instrument = song.noteInstrument(note), octave = song.noteOctave(note);
                int row = custom >= 0 ? vanillaCount + custom : instrument;
                Timing timing = timings[row][octave + 3];
                if (timing == null) {
                    timing = resolveTiming(pack, manifest, vanilla, instrument, octave, custom >= 0,
                            custom >= 0 ? customBases[custom] : null);
                    timings[row][octave + 3] = timing;
                }
                if (!timing.available()) { excluded++; continue; }
                estimated |= timing.estimated();
                float runtimePitch = runtimePitch(song.notePitch(note), octave, timing.shifted());
                starts[n] = onset; ends[n] = onset + timing.seconds() / Math.max(.5, runtimePitch); n++;
            }
        }
        return sweep(song, starts, ends, n, estimated, clientChannels(plugin), song.minTempo(), song.maxTempo(),
                excluded, listener == null ? "current generated pack" : "your installed pack");
    }

    private record Timing(double seconds, boolean shifted, boolean available, boolean estimated) {}

    /** At most one resolution per instrument/octave, regardless of how many notes use it. */
    private static Timing resolveTiming(io.github.noteblockworldmc.pack.ResourcePackService pack,
                                       Map<String, Double> manifest, double[] vanilla,
                                       int instrument, int octave, boolean custom, String base) {
        String suffix = octave == 0 ? "" : octave < 0 ? "_m" + -octave : "_p" + octave;
        double seconds;
        boolean shifted;
        if (custom) {
            if (base == null) return new Timing(-1, false, false, false);
            seconds = manifest.getOrDefault(base + suffix, -1.0);
            shifted = octave != 0 && seconds > 0;
            if (!(seconds > 0)) seconds = manifest.getOrDefault(base, -1.0);
            return new Timing(seconds, shifted, seconds > 0, false);
        }
        String event = io.github.noteblockworldmc.pack.ResourcePackService.INSTRUMENT_NAMES[instrument] + suffix;
        seconds = octave == 0 ? -1 : manifest.getOrDefault(event, -1.0);
        shifted = octave != 0 && seconds > 0;
        if (!(seconds > 0)) {
            if (vanilla[instrument] < 0) vanilla[instrument] = Math.max(0, pack.sampleSeconds(instrument));
            seconds = vanilla[instrument];
        }
        boolean estimated = !(seconds > 0);
        return new Timing(estimated ? FALLBACK_SECONDS : seconds, shifted, true, estimated);
    }

    private static float runtimePitch(float pitch, int octave, boolean shifted) {
        if (shifted || octave == 0) return pitch;
        double folded = Math.scalb((double) pitch, octave);
        return (float) Math.max(0.5, Math.min(2.0, folded));
    }

    /** Peak overlap via a sorted start/end sweep. */
    private static Report sweep(NbsSong song, double[] starts, double[] ends,
                                int count, boolean estimated, int clientChannels,
                                double minTempo, double maxTempo, int excluded, String scope) {
        // Starts are already chronological because the compact chord table is ordered.
        Arrays.sort(ends, 0, count);

        int active = 0;
        int peak = 0;
        double peakAt = 0;
        int s = 0;
        int e = 0;
        // Weighted mean: how many voices are ringing on average, over time
        double weighted = 0;
        double lastTime = 0;
        // Time-weighted voice count per slice, so the report can show WHERE
        // the song is busy rather than only how busy its worst instant is.
        double[] density = new double[DENSITY_BUCKETS];
        double songSeconds = Math.max(0.001, Math.max(song.getDurationSeconds(), count == 0 ? 0 : ends[count-1]));

        // Run until every END is consumed, not merely every start. Stopping
        // at the last onset discarded the whole tail of the song -- all the
        // time notes spend ringing after the final note is struck -- which
        // for a short piece is most of its playing time and made the mean
        // wildly too low. Peak is unaffected (it only rises on a start).
        while (e < count) {
            double time;
            if (s < count && starts[s] < ends[e]) {
                time = starts[s];
                weighted += active * (time - lastTime);
                addSpan(density, lastTime, time, active, songSeconds);
                lastTime = time;
                active++;
                s++;
                if (active > peak) {
                    peak = active;
                    peakAt = time;
                }
            } else {
                time = ends[e];
                weighted += active * (time - lastTime);
                addSpan(density, lastTime, time, active, songSeconds);
                lastTime = time;
                active--;
                e++;
            }
        }

        double duration = songSeconds;
        // Convert accumulated voice-seconds into an average voice count per slice
        double sliceSeconds = songSeconds / DENSITY_BUCKETS;
        for (int i = 0; i < density.length; i++) {
            density[i] /= sliceSeconds;
        }

        return new Report(song.getName(), song.getDurationSeconds(), count,
                song.getMergedNotes(), peak, peakAt, weighted / duration, estimated,
                clientChannels, NbsSong.clampTempo(song.getTempo()),
                minTempo, maxTempo, density, songSeconds, excluded, scope);
    }

    /**
     * Adds {@code active} voices held from {@code from} to {@code to} into the
     * slices that span covers, splitting proportionally across boundaries so a
     * long-held note contributes to every slice it actually sounds in.
     */
    private static void addSpan(double[] buckets, double from, double to,
                                int active, double songSeconds) {
        if (active <= 0 || to <= from) return;
        int n = buckets.length;
        double width = songSeconds / n;
        int first = Math.max(0, Math.min(n - 1, (int) (from / width)));
        int last = Math.max(0, Math.min(n - 1, (int) (to / width)));
        for (int b = first; b <= last; b++) {
            double overlap = Math.min(to, (b + 1) * width) - Math.max(from, b * width);
            if (overlap > 0) buckets[b] += active * overlap;
        }
    }

    /** Reads each custom instrument's sample length from the song's own files. */
}
