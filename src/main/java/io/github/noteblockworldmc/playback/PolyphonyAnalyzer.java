package io.github.noteblockworldmc.playback;

import io.github.noteblockworldmc.NoteblockWorldMCPlugin;
import io.github.noteblockworldmc.nbs.NbsSong;
import io.github.noteblockworldmc.nbs.PlayableNote;
import io.github.noteblockworldmc.pack.OggTools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
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
                         double[] density) {

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

    /** Blocking: run this off the main thread. */
    public static Report analyze(NoteblockWorldMCPlugin plugin, NbsSong song, String publicId) {
        double[] vanillaLengths =
                new double[io.github.noteblockworldmc.pack.ResourcePackService
                        .INSTRUMENT_NAMES.length];
        Arrays.fill(vanillaLengths, -1);
        Map<Integer, Double> customLengths = customSampleLengths(plugin, song, publicId);

        // Two flat arrays sized from the known note count, rather than a
        // List<double[]>: a million notes meant a million 2-element arrays
        // (~32 MB of headers alone) that were then copied into flat arrays
        // anyway. The exact size is known up front, so allocate once.
        int total = song.getNoteCount();
        double[] starts = new double[total];
        double[] ends = new double[total];
        int n = 0;
        // Whether any ring time had to be guessed rather than measured.
        boolean estimated = false;

        // Onset is tracked incrementally: calling getSecondsUpTo(tick) each
        // iteration would re-walk the tempo map from zero every time --
        // O(ticks^2), which the 10M-tick hard cap would turn into a hang.
        double onset = 0;
        double minTempo = NbsSong.clampTempo(song.getTempo());
        double maxTempo = minTempo;
        double tickSeconds = 1.0 / NbsSong.clampTempo(song.getTempo());
        for (int tick = 0; tick <= song.getLengthTicks(); tick++) {
            Double tempoChange = song.getTempoChangeAt(tick);
            if (tempoChange != null) {
                double changed = NbsSong.clampTempo(tempoChange);
                tickSeconds = 1.0 / changed;
                minTempo = Math.min(minTempo, changed);
                maxTempo = Math.max(maxTempo, changed);
            }
            List<PlayableNote> notes = song.getNotesAt(tick);
            if (notes.isEmpty()) {
                onset += tickSeconds;
                continue;
            }
            for (PlayableNote note : notes) {
                double base;
                if (note.isCustom()) {
                    base = customLengths.getOrDefault(note.customIndex(), -1.0);
                } else {
                    int instrument = note.instrument();
                    if (vanillaLengths[instrument] < 0) {
                        double measured =
                                plugin.getResourcePackService().sampleSeconds(instrument);
                        // 0 marks "used but unreadable"; -1 stays "never used"
                        vanillaLengths[instrument] = measured > 0 ? measured : 0;
                    }
                    base = vanillaLengths[instrument];
                }
                if (base <= 0) {
                    // Measured directly at the point of use: inspecting the
                    // length tables afterwards missed custom sounds whose
                    // file was absent entirely (never entered in the map), so
                    // a guessed report could claim to be exact.
                    estimated = true;
                    base = FALLBACK_SECONDS;
                }

                // An octave up plays the sample twice as fast: half as long,
                // so it releases its channel twice as soon.
                double ring = base / Math.pow(2, note.octave());
                if (n < total) {
                    starts[n] = onset;
                    ends[n] = onset + Math.max(0.01, ring);
                    n++;
                }
            }
            onset += tickSeconds;
        }

        return sweep(song, starts, ends, n, estimated, clientChannels(plugin),
                minTempo, maxTempo);
    }

    /** Peak overlap via a sorted start/end sweep. */
    private static Report sweep(NbsSong song, double[] starts, double[] ends,
                                int count, boolean estimated, int clientChannels,
                                double minTempo, double maxTempo) {
        Arrays.sort(starts, 0, count);
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
        double songSeconds = Math.max(0.001, song.getDurationSeconds());

        // Run until every END is consumed, not merely every start. Stopping
        // at the last onset discarded the whole tail of the song -- all the
        // time notes spend ringing after the final note is struck -- which
        // for a short piece is most of its playing time and made the mean
        // wildly too low. Peak is unaffected (it only rises on a start).
        while (e < count) {
            double time;
            if (s < count && starts[s] <= ends[e]) {
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

        double duration = Math.max(0.001, song.getDurationSeconds());
        // Convert accumulated voice-seconds into an average voice count per slice
        double sliceSeconds = songSeconds / DENSITY_BUCKETS;
        for (int i = 0; i < density.length; i++) {
            density[i] /= sliceSeconds;
        }

        return new Report(song.getName(), song.getDurationSeconds(), count,
                song.getMergedNotes(), peak, peakAt, weighted / duration, estimated,
                clientChannels, NbsSong.clampTempo(song.getTempo()),
                minTempo, maxTempo, density);
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
    static Map<Integer, Double> customSampleLengths(NoteblockWorldMCPlugin plugin,
                                                            NbsSong song, String publicId) {
        Map<Integer, Double> lengths = new HashMap<>();
        if (publicId == null) return lengths;

        Path soundsDir = plugin.getDownloadManager().getSongDirectory(publicId).resolve("sounds");
        if (!Files.isDirectory(soundsDir)) return lengths;

        List<String> files = song.getCustomSoundFiles();
        for (int i = 0; i < files.size(); i++) {
            String file = files.get(i);
            if (file == null || file.isBlank()) continue;
            Path path = soundsDir.resolve(Path.of(file).getFileName().toString());
            if (!Files.isRegularFile(path)) continue;
            try {
                lengths.put(i, OggTools.readDurationSeconds(Files.readAllBytes(path)));
            } catch (IOException ignored) {
                // fall back to the estimate
            }
        }
        return lengths;
    }
}
