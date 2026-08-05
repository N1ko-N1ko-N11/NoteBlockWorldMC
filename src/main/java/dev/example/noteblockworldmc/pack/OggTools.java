package dev.example.noteblockworldmc.pack;

/**
 * Minimal Ogg Vorbis surgery: shifts a sound's pitch by rewriting the
 * sample rate in its Vorbis identification header.
 *
 * WHY: Minecraft clamps the FINAL sound pitch to [0.5, 2.0] client-side
 * (SoundEngine), and a resource pack's "pitch" field multiplies into that
 * same value before the clamp — so pitch multipliers in sounds.json can
 * never break out of the vanilla two-octave window. The only way to place
 * a note outside it is to ship audio that plays at a different rate.
 *
 * Re-encoding needs a Vorbis codec; instead we simply tell the decoder the
 * audio was sampled at rate x 2^k. It then plays it 2^k times faster
 * (or slower) — an exact octave shift, no decoding, no quality loss beyond
 * the resampling the client already does.
 *
 * Only the first Ogg page is touched (it carries the identification
 * header), so only that page's CRC needs recomputing.
 */
public final class OggTools {

    /**
     * Rate bounds for shifted copies.
     *
     * +3 octaves multiplies the source rate by 8, so a 44.1/48 kHz sample
     * lands at 352.8/384 kHz. That is above the Vorbis spec's nominal
     * 200 kHz ceiling, but the decoder stores the rate as a plain integer
     * and OpenAL simply resamples to the device rate — so it plays. We allow
     * it deliberately: without the +3 variant the top four piano keys
     * (84-87) would be unreachable and would fold an octave down.
     */
    public static final int MIN_RATE = 1_000;
    public static final int MAX_RATE = 400_000;

    /** Ogg CRC: poly 0x04c11db7, init 0, no reflection, no final xor. */
    private static final int[] CRC_TABLE = new int[256];
    static {
        for (int i = 0; i < 256; i++) {
            int crc = i << 24;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x80000000) != 0 ? (crc << 1) ^ 0x04c11db7 : crc << 1;
            }
            CRC_TABLE[i] = crc;
        }
    }

    private OggTools() {
    }

    /**
     * Whether the buffer starts with the Ogg capture pattern.
     *
     * Length check is {@code >= 4}, not {@code > 4}: the magic IS four bytes,
     * so a caller that reads exactly the magic (to test a file cheaply
     * without loading it) must not be told a valid stream is invalid.
     */
    public static boolean isOgg(byte[] data) {
        return data.length >= 4 && data[0] == 'O' && data[1] == 'g'
                && data[2] == 'g' && data[3] == 'S';
    }

    /**
     * Playing time of an Ogg stream, in seconds, or -1 if unreadable.
     *
     * The last page's granule position IS the total sample count, so the
     * duration is exact — no guessing at how long a harp "probably" rings.
     * That matters for polyphony: a sound holds a client channel for its
     * whole length, so occupancy is a function of this number.
     */
    public static double readDurationSeconds(byte[] ogg) {
        int rate = readSampleRate(ogg);
        if (rate <= 0) return -1;

        // Walk back to the final page's capture pattern
        for (int i = ogg.length - 14; i >= 0; i--) {
            if (ogg[i] == 'O' && ogg[i + 1] == 'g' && ogg[i + 2] == 'g' && ogg[i + 3] == 'S'
                    // "OggS" can occur by chance inside compressed audio; a
                    // real page header always has stream-structure version 0,
                    // which rejects almost every false positive.
                    && ogg[i + 4] == 0) {
                long granule = 0;
                for (int b = 7; b >= 0; b--) {
                    granule = (granule << 8) | (ogg[i + 6 + b] & 0xFFL);
                }
                if (granule <= 0) continue;          // page carries no audio yet
                return (double) granule / rate;
            }
        }
        return -1;
    }

    /** Sample rate from the Vorbis identification header, or -1. */
    public static int readSampleRate(byte[] ogg) {
        int offset = identificationHeaderOffset(ogg);
        if (offset < 0) return -1;
        return readIntLE(ogg, offset + 12);
    }

    /**
     * Returns a copy of the sound shifted by {@code octaves}, or null if
     * the shift would put the sample rate outside the decodable range
     * (callers then fall back to folding the note into the base window).
     */
    public static byte[] shiftOctaves(byte[] ogg, int octaves) {
        if (octaves == 0) return ogg;

        // Locate the header ONCE and reuse it. Recomputing it after the clone
        // meant a second scan, and its -1 failure value went unchecked — a
        // future change that made the two disagree would write the rate at
        // offset 11, corrupting the page header instead of failing.
        int offset = identificationHeaderOffset(ogg);
        if (offset < 0) return null;
        int rate = readIntLE(ogg, offset + 12);
        if (rate <= 0) return null;

        double shifted = rate * Math.pow(2, octaves);
        if (shifted < MIN_RATE || shifted > MAX_RATE) return null;

        byte[] copy = ogg.clone();
        writeIntLE(copy, offset + 12, (int) Math.round(shifted));
        if (!fixPageCrc(copy, 0)) return null;   // malformed page
        return copy;
    }

    // ------------------------------------------------------------------

    /** Offset of the Vorbis identification packet inside the first page. */
    private static int identificationHeaderOffset(byte[] ogg) {
        if (!isOgg(ogg) || ogg.length < 28) return -1;
        int segmentCount = ogg[26] & 0xFF;
        int dataStart = 27 + segmentCount;
        // packet: 0x01 "vorbis" version(4) channels(1) sampleRate(4) ...
        if (ogg.length < dataStart + 16) return -1;
        if (ogg[dataStart] != 1
                || ogg[dataStart + 1] != 'v' || ogg[dataStart + 2] != 'o'
                || ogg[dataStart + 3] != 'r' || ogg[dataStart + 4] != 'b'
                || ogg[dataStart + 5] != 'i' || ogg[dataStart + 6] != 's') {
            return -1;
        }
        return dataStart;
    }

    /**
     * Recomputes the CRC of the page starting at pageStart.
     * Returns false if the page header is malformed — these files come from
     * arbitrary uploaders, so a bogus segment table must not walk off the
     * end of the array and take the whole pack rebuild down with it.
     */
    private static boolean fixPageCrc(byte[] ogg, int pageStart) {
        if (pageStart + 27 > ogg.length) return false;
        int segmentCount = ogg[pageStart + 26] & 0xFF;
        int headerLength = 27 + segmentCount;
        if (pageStart + headerLength > ogg.length) return false;

        int payload = 0;
        for (int i = 0; i < segmentCount; i++) {
            payload += ogg[pageStart + 27 + i] & 0xFF;
        }
        int pageLength = headerLength + payload;
        if (pageStart + pageLength > ogg.length) return false;

        // The CRC field itself is treated as zero while computing
        for (int i = 22; i < 26; i++) {
            ogg[pageStart + i] = 0;
        }
        int crc = 0;
        for (int i = 0; i < pageLength; i++) {
            crc = (crc << 8) ^ CRC_TABLE[((crc >>> 24) ^ (ogg[pageStart + i] & 0xFF)) & 0xFF];
        }
        writeIntLE(ogg, pageStart + 22, crc);
        return true;
    }

    private static int readIntLE(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    private static void writeIntLE(byte[] data, int offset, int value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        data[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        data[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }
}
