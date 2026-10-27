package io.github.noteblockworldmc.pack;

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
        return data != null && data.length >= 4 && data[0] == 'O' && data[1] == 'g'
                && data[2] == 'g' && data[3] == 'S';
    }

    /** Requires CRC-correct pages, valid bounded Vorbis setup and audio mode headers.
     * Audio synthesis remains the client's responsibility; this is not a PCM decoder.
     */
    public static boolean isVorbis(byte[] data) { return readDurationSeconds(data) > 0; }

    /** Owned bytes, validated once. Package consumers only read this snapshot. */
    static final class Sample {
        private final byte[] bytes;
        private final double seconds;
        private final String digest;
        private Sample(byte[] bytes, double seconds) {
            this.bytes = bytes; this.seconds = seconds;
            try {
                digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        }
        byte[] bytes() { return bytes; }
        int size() { return bytes.length; }
        double seconds() { return seconds; }
        String digest() { return digest; }
    }

    static Sample validate(byte[] ownedBytes) {
        double seconds = readDurationSeconds(ownedBytes);
        return seconds > 0 ? new Sample(ownedBytes, seconds) : null;
    }

    /** Only the first page changes; all other validated framing and decoded-size bounds are preserved. */
    static Sample shiftValidated(Sample source, int octaves) {
        if (octaves == 0) return source;
        int offset = identificationHeaderOffset(source.bytes());
        int firstPageEnd = offset;
        for (int i = 0; i < (source.bytes()[26] & 255); i++) firstPageEnd += source.bytes()[27 + i] & 255;
        // The rate must be wholly on the page whose CRC we replace.
        if (offset + 16 > firstPageEnd) return null;
        int rate = readIntLE(source.bytes(), offset + 12);
        double desired = Math.scalb((double) rate, octaves);
        if (desired < MIN_RATE || desired > MAX_RATE) return null;
        int shiftedRate = (int) Math.round(desired);
        long frames = Math.round(source.seconds() * rate);
        double seconds = (double) frames / shiftedRate;
        if (seconds <= 0 || seconds > io.github.noteblockworldmc.util.Limits.SAMPLE_SECONDS) return null;
        byte[] copy = source.bytes().clone();
        writeIntLE(copy, offset + 12, shiftedRate);
        if (!fixPageCrc(copy, 0)) return null;
        return new Sample(copy, seconds);
    }

    public static double readDurationSeconds(byte[] data) {
        if (data == null) return -1;
        if (data.length > io.github.noteblockworldmc.util.Limits.SAMPLE_BYTES) return -1;
        int rate = readSampleRate(data), id = identificationHeaderOffset(data);
        if (rate < MIN_RATE || rate > MAX_RATE || id < 0 || data.length < id + 30) return -1;
        int channels = data[id + 11] & 255;
        int block = data[id + 28] & 255;
        if (readIntLE(data, id + 7) != 0 || channels < 1 || channels > 2 || data[id + 29] != 1
                || (block & 15) < 6 || (block >>> 4) > 13 || (block & 15) > (block >>> 4)) return -1;
        int offset = 0, sequence = 0, serial = 0, packets = 0;
        // Frames the audio packets will actually decode to. Each packet after
        // the first yields a quarter of the previous block plus a quarter of
        // its own (Vorbis I, section 4.3.8); this matches JOrbis exactly.
        long decodedFrames = 0;
        int previousBlock = 0;
        int shortBlock = 1 << (block & 15), longBlock = 1 << (block >>> 4);
        long granule = 0;
        boolean continued = false, ended = false;
        boolean[] modes = null;
        int audioPrefix = 0, audioPrefixBytes = 0;
        java.io.ByteArrayOutputStream header = new java.io.ByteArrayOutputStream();
        while (offset < data.length) {
            if (ended || offset + 27 > data.length || data[offset] != 'O' || data[offset+1] != 'g'
                    || data[offset+2] != 'g' || data[offset+3] != 'S' || data[offset+4] != 0) return -1;
            int flags = data[offset+5] & 255, segments = data[offset+26] & 255;
            if ((flags & ~7) != 0 || ((flags & 1) != 0) != continued || ((flags & 2) != 0) != (sequence == 0)) return -1;
            if (offset + 27 + segments > data.length || readIntLE(data, offset+18) != sequence++) return -1;
            int stream = readIntLE(data, offset+14);
            if (offset == 0) serial = stream; else if (stream != serial) return -1;
            int payload = offset + 27 + segments, end = payload;
            for (int i=0; i<segments; i++) end += data[offset+27+i] & 255;
            if (end > data.length) return -1;
            int crc = 0;
            for (int i=offset; i<end; i++) {
                int value = i >= offset+22 && i < offset+26 ? 0 : data[i] & 255;
                crc = (crc << 8) ^ CRC_TABLE[((crc >>> 24) ^ value) & 255];
            }
            if (crc != readIntLE(data, offset+22)) return -1;
            long pageGranule = 0;
            for (int i=7; i>=0; i--) pageGranule = (pageGranule << 8) | (data[offset+6+i] & 255L);
            if (pageGranule < -1 || pageGranule >= 0 && pageGranule < granule) return -1;
            if (pageGranule >= 0) granule = pageGranule;
            for (int i=0; i<segments; i++) {
                int size = data[offset+27+i] & 255;
                if (packets < 3) header.write(data, payload, size);
                else for (int prefix = 0; prefix < size && audioPrefixBytes < 2; prefix++)
                    audioPrefix |= (data[payload + prefix] & 255) << (8 * audioPrefixBytes++);
                payload += size; continued = size == 255;
                if (!continued) {
                    if (packets < 3) {
                        byte[] packet = header.toByteArray(); header.reset();
                        if (packet.length < 7 || packet[0] != 1 + packets*2
                                || packet[1] != 'v' || packet[2] != 'o' || packet[3] != 'r'
                                || packet[4] != 'b' || packet[5] != 'i' || packet[6] != 's') return -1;
                        if (packets == 0 && (packet.length != 30 || sequence != 1)) return -1;
                        if (packets == 1 && !validComment(packet)) return -1;
                        if (packets == 2) {
                            modes = VorbisSetup.read(packet, channels);
                            if (modes == null) return -1;
                        }
                    } else {
                        int width = VorbisSetup.ilog(modes.length - 1);
                        int mode = (audioPrefix >>> 1) & ((1 << width) - 1);
                        if (audioPrefixBytes == 0 || (audioPrefix & 1) != 0 || mode >= modes.length
                                || audioPrefixBytes * 8 < 1 + width + (modes[mode] ? 2 : 0)) return -1;
                        int blockSize = modes[mode] ? longBlock : shortBlock;
                        if (previousBlock > 0) decodedFrames += previousBlock / 4 + blockSize / 4;
                        previousBlock = blockSize;
                        audioPrefix = 0; audioPrefixBytes = 0;
                    }
                    packets++;
                }
            }
            ended = (flags & 4) != 0; offset = end;
        }
        double seconds = (double)granule / rate;
        // The declared length (final granule) may trim a block at either end,
        // nothing more. Without this check a sample could declare a fraction of a
        // second while its packets decode to minutes: the client decodes the
        // whole stream into memory, so such a file in the pack is a memory
        // bomb for every player who hears it, and its ring time is misjudged.
        long frameLimit = 16L * 1024 * 1024 / (channels * 2);
        return ended && !continued && packets > 3 && granule > 0
                && granule <= frameLimit && decodedFrames <= granule + 2L * longBlock
                && seconds <= io.github.noteblockworldmc.util.Limits.SAMPLE_SECONDS ? seconds : -1;
    }

    private static boolean validComment(byte[] packet) {
        if (packet.length < 16) return false;
        long at = 11L + Integer.toUnsignedLong(readIntLE(packet, 7));
        if (at + 4 > packet.length) return false;
        long count = Integer.toUnsignedLong(readIntLE(packet, (int)at)); at += 4;
        if (count > packet.length / 4) return false;
        for (long i=0; i<count; i++) {
            if (at + 4 > packet.length) return false;
            at += 4L + Integer.toUnsignedLong(readIntLE(packet, (int)at));
        }
        return at == packet.length - 1 && packet[(int)at] == 1;
    }

    /** Sample rate from the Vorbis identification header, or -1. */
    public static int readSampleRate(byte[] ogg) {
        if (ogg == null) return -1;
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
        if (ogg == null || !isVorbis(ogg)) return null;
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
        if (!fixPageCrc(copy, 0) || readDurationSeconds(copy) <= 0) return null;   // malformed page
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
