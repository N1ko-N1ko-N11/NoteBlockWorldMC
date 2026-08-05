package dev.example.noteblockworldmc.nbs;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Parser for the .nbs (Note Block Studio) binary format.
 * Supports the classic format (version 0) and OpenNBS versions 1-5.
 * Format reference: https://opennbs.org/nbs
 *
 * All multi-byte values in NBS are little-endian; DataInputStream is
 * big-endian, hence the readShortLE/readIntLE helpers.
 *
 * Pitch/volume are precomputed here so playback is a dumb loop:
 *  - effective key = key + finePitch/100 (v4+ fine pitch is in cents)
 *  - transposed by octaves into the note block range (keys 33-57,
 *    F#3-F#5), then pitch = 2^((key-45)/12)
 *  - volume = note velocity% x layer volume%
 * Notes using custom instruments are counted and skipped: the server
 * cannot play arbitrary .ogg files without a resource pack.
 */
public final class NbsParser {

    /** Note block playable range in NBS key numbering (F#3..F#5). */
    private static final int MIN_KEY = 33;
    private static final int MAX_KEY = 57;
    /** Sanity cap for length-prefixed strings. */
    private static final int MAX_STRING_LENGTH = 1 << 20;
    /** Hard caps against maliciously crafted files (song uploads are untrusted). */
    private static final int MAX_NOTES = 1_000_000;
    private static final int MAX_TICK = 10_000_000;
    /**
     * Playable notes kept per tick. The effective audio rate is
     * tempo x notes-per-tick; without this cap a crafted file could fire
     * hundreds of thousands of sounds (packets, per listener) in a single
     * game tick. Clients can only mix ~247 concurrent sounds anyway, so
     * 128 is far beyond any musical use.
     */
    private static final int MAX_NOTES_PER_TICK = 128;
    /** Highest NBS format version this parser understands. */
    private static final int MAX_SUPPORTED_VERSION = 6;
    /** Built-in instrument count: 16 through v5; v6 added the four trumpets (16-19). */
    private static final int BUILTINS_PRE_V6 = 16;
    private static final int BUILTINS_V6 = 20;

    private NbsParser() {
    }

    public static NbsSong parse(Path file) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            return parse(in);
        }
    }

    public static NbsSong parse(InputStream rawIn) throws IOException {
        DataInputStream in = new DataInputStream(rawIn);

        // ---- Header ----
        int version;
        int vanillaInstrumentCount;
        int headerSongLength = readShortLE(in);

        if (headerSongLength == 0) {
            // New (OpenNBS) format: the first short is 0 as a signature
            version = in.readUnsignedByte();
            if (version > MAX_SUPPORTED_VERSION) {
                throw new IOException("Unsupported NBS format version " + version
                        + " (this plugin supports up to v" + MAX_SUPPORTED_VERSION + ")");
            }
            vanillaInstrumentCount = in.readUnsignedByte();
            if (version >= 3) {
                headerSongLength = readShortLE(in);
            } else {
                headerSongLength = -1; // not stored before v3; derive from notes
            }
        } else {
            version = 0;
            vanillaInstrumentCount = 10; // classic format had 10 built-in instruments
        }

        int layerCount = readShortLE(in);
        String name = readString(in);
        String author = readString(in);
        String originalAuthor = readString(in);
        readString(in); // description (unused here)
        // readShortLE sign-extends, so a crafted file can declare a zero or
        // negative tempo. Rather than let each consumer clamp differently
        // (or forget to), normalise at the source: fall back to Note Block
        // Studio's own default of 10 t/s.
        double tempo = readShortLE(in) / 100.0;
        if (!(tempo > 0)) {          // also catches NaN
            tempo = 10.0;
        }
        in.readByte();               // auto-saving
        in.readByte();               // auto-saving duration
        in.readByte();               // time signature
        readIntLE(in);               // minutes spent
        readIntLE(in);               // left clicks
        readIntLE(in);               // right clicks
        readIntLE(in);               // note blocks added
        readIntLE(in);               // note blocks removed
        readString(in);              // imported MIDI/schematic file name
        boolean loopEnabled = false;
        int maxLoopCount = 0;
        int loopStartTick = 0;
        if (version >= 4) {
            loopEnabled = in.readUnsignedByte() != 0;
            maxLoopCount = in.readUnsignedByte();   // 0 = forever
            loopStartTick = readShortLE(in);
        }

        // ---- Note blocks ----
        // Raw notes keep their layer so layer volume can be baked in after
        // the layers section is read.
        record RawNote(int tick, int layer, int instrument, int key,
                       int velocity, int finePitch, int panning) {}

        List<RawNote> rawNotes = new ArrayList<>();
        int maxTick = 0;
        int tick = -1;

        while (true) {
            int tickJump = readShortLE(in);
            if (tickJump == 0) break;
            if (tickJump < 0) {
                throw new IOException("Corrupt NBS file: negative tick jump");
            }
            tick += tickJump;
            if (tick > MAX_TICK) {
                throw new IOException("NBS song exceeds the maximum supported length");
            }
            maxTick = tick;

            int layer = -1;
            while (true) {
                int layerJump = readShortLE(in);
                if (layerJump == 0) break;
                if (layerJump < 0) {
                    throw new IOException("Corrupt NBS file: negative layer jump");
                }
                layer += layerJump;

                int instrument = in.readUnsignedByte();
                int key = in.readUnsignedByte();
                int velocity = 100;
                int finePitch = 0;
                int panning = 100;                  // 0-200, 100 = centre
                if (version >= 4) {
                    velocity = in.readUnsignedByte();
                    panning = in.readUnsignedByte();
                    finePitch = readShortLE(in);    // signed, in cents
                }
                if (rawNotes.size() >= MAX_NOTES) {
                    throw new IOException("NBS file has too many notes");
                }
                rawNotes.add(new RawNote(tick, layer, instrument, key, velocity, finePitch,
                        panning));
            }
        }

        // ---- Layers (optional: files may be truncated after the notes) ----
        int[] layerVolumes = new int[Math.max(layerCount, 0)];
        int[] layerStereo = new int[Math.max(layerCount, 0)];
        java.util.Arrays.fill(layerVolumes, 100);
        java.util.Arrays.fill(layerStereo, 100);    // 0-200, 100 = centre
        try {
            for (int i = 0; i < layerCount; i++) {
                readString(in);                     // layer name
                if (version >= 4) in.readByte();    // lock
                int volume = in.readUnsignedByte();
                if (i < layerVolumes.length) layerVolumes[i] = volume;
                if (version >= 2) {
                    int stereo = in.readUnsignedByte();
                    if (i < layerStereo.length) layerStereo[i] = stereo;
                }
            }
        } catch (IOException ignored) {
            // Truncated OR misaligned layer section. This is optional data
            // (volume/stereo already default to 100), so degrade rather than
            // reject a song whose notes parsed fine. EOFException alone was
            // too narrow: a misaligned read surfaces as a bogus string
            // length, which is a plain IOException.
        }

        // First index that counts as a custom instrument. The header's own
        // vanilla-instrument-count field participates, capped by what the
        // format version can actually declare (guards malicious headers).
        int firstCustomIndex = Math.min(Math.max(vanillaInstrumentCount, 0),
                version >= 6 ? BUILTINS_V6 : BUILTINS_PRE_V6);

        // ---- Custom instruments (optional section after layers) ----
        // Pre-v6 songs often ship the trumpets as custom instruments pointing
        // at the same .ogg files that are now vanilla sounds (16-19). Promote
        // those so old trumpet songs play instead of being skipped.
        Map<Integer, Integer> promotedCustoms = new HashMap<>();
        // "Tempo Changer" instruments (Note Block Studio convention): their
        // notes are not sounds but mid-song tempo events.
        Set<Integer> tempoChangerInstruments = new HashSet<>();
        // "Sound Stopper" is the other Note Block Studio marker instrument.
        // Like Tempo Changer it is a NAME convention rather than anything in
        // the NBS spec, and it carries no sound file.
        //
        // It is recognised but deliberately NOT acted upon. A stopper cuts
        // sounds across a range of layers, and Minecraft cannot express that:
        // stopSound addresses sounds by event name or category, never by
        // instance, so there is no way to silence the harp on one layer while
        // the harp on another keeps ringing. Approximating it would cut notes
        // the composer wanted kept, so the song simply rings on.
        //
        // Recognising it still matters: without this the notes look like
        // custom instruments whose audio is missing, and every listener is
        // told "N notes use custom instruments and can't be played" about
        // notes that were never meant to make a sound.
        Set<Integer> soundStopperInstruments = new HashSet<>();
        // Per custom index: sound file ("" = consumed/none) and base key
        List<String> customSoundFiles = new ArrayList<>();
        List<Integer> customKeys = new ArrayList<>();
        try {
            int customCount = in.readUnsignedByte();
            for (int i = 0; i < customCount; i++) {
                String instrumentName = readString(in);
                String soundFile = readString(in);
                int soundKey = in.readUnsignedByte();
                in.readByte();                           // press key
                String trimmedName = instrumentName.trim();
                if (trimmedName.equals("Tempo Changer")) {
                    tempoChangerInstruments.add(firstCustomIndex + i);
                    customSoundFiles.add("");
                    customKeys.add(45);
                    continue;
                }
                if (trimmedName.equals("Sound Stopper")) {
                    soundStopperInstruments.add(firstCustomIndex + i);
                    customSoundFiles.add("");
                    customKeys.add(45);
                    continue;
                }
                int vanilla = matchVanillaTrumpet(soundFile);
                if (vanilla >= 0) {
                    promotedCustoms.put(firstCustomIndex + i, vanilla);
                    customSoundFiles.add("");
                    customKeys.add(45);
                    continue;
                }
                customSoundFiles.add(soundFile == null ? "" : soundFile.trim());
                // 0-87; anything else is malformed -> fall back to F#4
                customKeys.add(soundKey <= 87 ? soundKey : 45);
            }
        } catch (IOException ignored) {
            // No, truncated, or unreadable custom instrument section. Notes
            // referencing custom instruments then count as unplayable and are
            // skipped with a warning, which is the correct degradation.
        }

        // ---- Bake notes ----
        Map<Integer, List<PlayableNote>> notesByTick = new HashMap<>();
        TreeMap<Integer, Double> tempoChanges = new TreeMap<>();
        int skippedCustom = 0;
        int skippedDense = 0;
        int customNoteCount = 0;
        int mergedNotes = 0;

        for (RawNote raw : rawNotes) {
            int instrument = raw.instrument();
            int customIndex = -1;
            if (tempoChangerInstruments.contains(instrument)) {
                // Note pitch encodes the new tempo: abs(pitch)/15 = t/s.
                // Notes are read in ascending layer order, so plain overwrite
                // makes the bottom-most layer win on conflicting ticks —
                // matching Note Block Studio's rule.
                double newTempo = Math.abs(raw.finePitch()) / 15.0;
                // pitch 0 would mean "0 ticks per second"; clamping that
                // downstream yields 5 seconds per tick and the song appears
                // to freeze. Ignore the event instead.
                if (newTempo > 0) {
                    tempoChanges.put(raw.tick(), newTempo);
                }
                continue;
            }
            if (soundStopperInstruments.contains(instrument)) {
                // Silent by design: a marker, not a missing sound. Minecraft
                // cannot act on it anyway -- stopSound addresses sounds by
                // event name or category, never by instance, so a stopper's
                // layer range is inexpressible. Dropped without comment.
                continue;
            }
            if (instrument >= firstCustomIndex) {
                Integer promoted = promotedCustoms.get(instrument);
                if (promoted != null) {
                    instrument = promoted;
                } else {
                    int index = instrument - firstCustomIndex;
                    // Unknown index or no sound file -> genuinely unplayable
                    if (index >= customSoundFiles.size()
                            || customSoundFiles.get(index).isBlank()) {
                        skippedCustom++;
                        continue;
                    }
                    customIndex = index;
                    instrument = -1;
                }
            }
            if (customIndex < 0 && instrument >= BUILTINS_V6) {
                skippedCustom++; // defensive; playback maps 0-19
                continue;
            }
            int layerVolume = raw.layer() >= 0 && raw.layer() < layerVolumes.length
                    ? layerVolumes[raw.layer()] : 100;
            float volume = clamp((raw.velocity() / 100f) * (layerVolume / 100f), 0f, 1f);
            if (volume <= 0f) continue;

            double effectiveKey = raw.key() + raw.finePitch() / 100.0;
            // Fold into the base window (vanilla 33-57, or +-12 around a
            // custom instrument's own key), remembering how far we moved:
            // the octave offset selects the extended-pack sound variant.
            // A custom instrument's playable window is centred on the MIRROR
            // of its declared key around F#4 (45), not on the key itself:
            //
            //     centre = 45 - (instrumentKey - 45) = 90 - instrumentKey
            //
            // This is what Note Block Studio and noteblock.world actually do
            // (see NBW's stats.ts). Centring on the key itself pitches every
            // instrument tuned away from F#4 in the WRONG DIRECTION, so
            // high-tuned samples come out low and vice versa.
            int baseKey = customIndex >= 0
                    ? 90 - customKeys.get(customIndex)
                    : 45;
            double minKey = customIndex >= 0 ? baseKey - 12 : MIN_KEY;
            double maxKey = customIndex >= 0 ? baseKey + 12 : MAX_KEY;
            int octave = 0;
            while (effectiveKey < minKey) { effectiveKey += 12; octave--; }
            while (effectiveKey > maxKey) { effectiveKey -= 12; octave++; }
            // The pack ships +-3 octaves (full 88-key piano); fine-pitch
            // abuse beyond that stays folded at the clamped variant.
            octave = Math.max(-3, Math.min(3, octave));
            float pitch = (float) Math.pow(2, (effectiveKey - baseKey) / 12.0);
            if (customIndex >= 0) customNoteCount++;

            // Panning: note and layer are both 0-200 with 100 = centre.
            // Matches Note Block World's resolvePanning(): a centred layer
            // leaves the note alone, otherwise the two are averaged.
            int notePan = raw.panning() - 100;
            int stereo = (raw.layer() >= 0 && raw.layer() < layerStereo.length
                    ? layerStereo[raw.layer()] : 100) - 100;
            int resolvedPan = stereo == 0 ? notePan : (notePan + stereo) / 2;
            float pan = Math.max(-1f, Math.min(1f, resolvedPan / 100f));

            List<PlayableNote> tickNotes =
                    notesByTick.computeIfAbsent(raw.tick(), t -> new ArrayList<>());

            // Coalesce an identical note already on this tick. NBS arrangements
            // routinely stack the SAME note across layers purely to make it
            // louder. Identical samples at identical pitch are coherent, so
            // their amplitudes add: one note at the summed volume sounds the
            // same and costs ONE of the client's ~250 sound channels instead
            // of several. Lossless, and often halves a layered song's voices.
            int duplicate = -1;
            for (int i = 0; i < tickNotes.size(); i++) {
                PlayableNote other = tickNotes.get(i);
                if (other.instrument() == instrument
                        && other.customIndex() == customIndex
                        && other.octave() == octave
                        && Math.abs(other.pitch() - pitch) < 1e-4f
                        && Math.abs(other.pan() - pan) < 1e-3f) {
                    duplicate = i;
                    break;
                }
            }
            if (duplicate >= 0) {
                PlayableNote other = tickNotes.get(duplicate);
                float summed = Math.min(1f, other.volume() + volume);
                tickNotes.set(duplicate, new PlayableNote(instrument, pitch, summed,
                        octave, customIndex, pan));
                mergedNotes++;
                continue;
            }

            if (tickNotes.size() >= MAX_NOTES_PER_TICK) {
                skippedDense++;
                continue;
            }
            tickNotes.add(new PlayableNote(instrument, pitch, volume, octave, customIndex, pan));
        }

        int lengthTicks = Math.max(headerSongLength, maxTick);
        String displayName = !name.isBlank() ? name : "";
        String displayAuthor = !originalAuthor.isBlank() ? originalAuthor : author;

        // A loop point at or past the end would restart every tick forever
        if (loopStartTick < 0 || loopStartTick >= lengthTicks) loopStartTick = 0;

        return new NbsSong(displayName, displayAuthor, tempo, lengthTicks,
                notesByTick, skippedCustom, skippedDense, customNoteCount, mergedNotes,
                java.util.List.copyOf(customSoundFiles), tempoChanges,
                loopEnabled, maxLoopCount, loopStartTick);
    }

    // ------------------------------------------------------------------

    /**
     * Maps a custom instrument's sound file to a vanilla trumpet index
     * (16-19), or -1 if it isn't one of the known trumpet sounds.
     */
    private static int matchVanillaTrumpet(String soundFile) {
        String name = soundFile.toLowerCase(java.util.Locale.ROOT);
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        return switch (name) {
            case "trumpet.ogg" -> 16;
            case "exposed_trumpet.ogg" -> 17;
            case "weathered_trumpet.ogg" -> 18;
            case "oxidized_trumpet.ogg" -> 19;
            default -> -1;
        };
    }

    private static int readShortLE(DataInputStream in) throws IOException {
        int b1 = in.readUnsignedByte();
        int b2 = in.readUnsignedByte();
        return (short) (b1 | (b2 << 8)); // sign-extend
    }

    private static int readIntLE(DataInputStream in) throws IOException {
        int b1 = in.readUnsignedByte();
        int b2 = in.readUnsignedByte();
        int b3 = in.readUnsignedByte();
        int b4 = in.readUnsignedByte();
        return b1 | (b2 << 8) | (b3 << 16) | (b4 << 24);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = readIntLE(in);
        if (length < 0 || length > MAX_STRING_LENGTH) {
            throw new IOException("Invalid string length in NBS file: " + length);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
