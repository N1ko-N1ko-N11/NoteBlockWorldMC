package io.github.noteblockworldmc.pack;

/** Bounded Vorbis I setup reader. No PCM buffers or codebook lookup tables are allocated.
 * Format reference: <a href="https://xiph.org/vorbis/doc/Vorbis_I_spec.html">Vorbis I specification</a>
 * This validates decoder configuration, not the synthesis of every audio sample.
 */
final class VorbisSetup {
    private VorbisSetup() {}

    static boolean[] read(byte[] packet, int channels) {
        try {
            Bits bits = new Bits(packet, 56);
            int count = bits.read(8) + 1;
            Book[] books = new Book[count];
            long entriesLeft = 1_000_000, valuesLeft = 4_000_000;
            for (int i = 0; i < count; i++) {
                require(bits.read(24) == 0x564342);
                int dimensions = bits.read(16), entries = bits.read(24);
                require(dimensions > 0 && entries > 0 && entries <= entriesLeft);
                entriesLeft -= entries;
                int[] lengths = new int[33];
                if (bits.read(1) != 0) {
                    int length = bits.read(5) + 1, remaining = entries;
                    while (remaining > 0) {
                        require(length <= 32);
                        int run = bits.read(ilog(remaining));
                        require(run <= remaining);
                        lengths[length++] = run;
                        remaining -= run;
                    }
                } else {
                    boolean sparse = bits.read(1) != 0;
                    for (int entry = 0; entry < entries; entry++) {
                        if (!sparse || bits.read(1) != 0) lengths[bits.read(5) + 1]++;
                    }
                }
                long slots = 1;
                int used = 0;
                for (int length = 1; length <= 32; length++) {
                    slots = slots * 2 - lengths[length];
                    require(slots >= 0);
                    used += lengths[length];
                }
                require(slots == 0 || used == 1 && lengths[1] == 1);
                int lookup = bits.read(4);
                require(lookup <= 2);
                if (lookup != 0) {
                    long values = (long) entries * dimensions;
                    require(values <= valuesLeft);
                    valuesLeft -= values;
                    bits.skip(64); // Minimum and delta, packed Vorbis floats.
                    int width = bits.read(4) + 1;
                    bits.skip(1);
                    long multiplicands = lookup == 1 ? lookupValues(entries, dimensions) : values;
                    bits.skip(multiplicands * width);
                }
                books[i] = new Book(dimensions, entries, lookup != 0);
            }
            int times = bits.read(6) + 1;
            for (int i = 0; i < times; i++) require(bits.read(16) == 0);
            int floors = bits.read(6) + 1;
            for (int i = 0; i < floors; i++) floor(bits, books);
            int residues = bits.read(6) + 1;
            for (int i = 0; i < residues; i++) {
                require(bits.read(16) <= 2);
                int begin = bits.read(24), end = bits.read(24);
                require(end >= begin);
                bits.skip(24); // Partition size minus one.
                int classes = bits.read(6) + 1;
                Book classification = book(bits, books, false);
                require(powerFits(classes, classification.dimensions(), classification.entries()));
                int[] cascade = new int[classes];
                for (int j = 0; j < classes; j++) {
                    int low = bits.read(3);
                    cascade[j] = low | (bits.read(1) == 0 ? 0 : bits.read(5) << 3);
                }
                for (int mask : cascade) for (int pass = 0; pass < 8; pass++) {
                    if ((mask & (1 << pass)) != 0) book(bits, books, true);
                }
            }
            int mappings = bits.read(6) + 1;
            for (int i = 0; i < mappings; i++) {
                require(bits.read(16) == 0);
                int submaps = bits.read(1) == 0 ? 1 : bits.read(4) + 1;
                if (bits.read(1) != 0) {
                    int pairs = bits.read(8) + 1;
                    for (int j = 0; j < pairs; j++) {
                        int magnitude = bits.read(ilog(channels - 1)), angle = bits.read(ilog(channels - 1));
                        require(magnitude != angle && magnitude < channels && angle < channels);
                    }
                }
                require(bits.read(2) == 0);
                if (submaps > 1) for (int channel = 0; channel < channels; channel++)
                    require(bits.read(4) < submaps);
                for (int j = 0; j < submaps; j++) {
                    bits.skip(8); // Unused time placeholder.
                    require(bits.read(8) < floors);
                    require(bits.read(8) < residues);
                }
            }
            boolean[] longBlocks = new boolean[bits.read(6) + 1];
            for (int i = 0; i < longBlocks.length; i++) {
                longBlocks[i] = bits.read(1) != 0;
                require(bits.read(16) == 0 && bits.read(16) == 0);
                require(bits.read(8) < mappings);
            }
            require(bits.read(1) == 1);
            return longBlocks;
        } catch (Invalid e) { return null; }
    }

    private static void floor(Bits bits, Book[] books) {
        int type = bits.read(16);
        require(type <= 1);
        if (type == 0) {
            require(bits.read(8) > 0 && bits.read(16) > 0 && bits.read(16) > 0);
            bits.skip(6 + 8);
            int count = bits.read(4) + 1;
            for (int i = 0; i < count; i++) book(bits, books, true);
            return;
        }
        int[] partitions = new int[bits.read(5)];
        int max = -1;
        for (int i = 0; i < partitions.length; i++) {
            partitions[i] = bits.read(4); max = Math.max(max, partitions[i]);
        }
        int[] dimensions = new int[max + 1];
        for (int i = 0; i <= max; i++) {
            dimensions[i] = bits.read(3) + 1;
            int subclasses = bits.read(2);
            if (subclasses != 0) book(bits, books, false);
            for (int j = 0; j < (1 << subclasses); j++) require(bits.read(8) - 1 < books.length);
        }
        bits.skip(2);
        int width = bits.read(4);
        java.util.Set<Integer> points = new java.util.HashSet<>();
        points.add(0); points.add(1 << width);
        for (int partition : partitions) for (int j = 0; j < dimensions[partition]; j++) {
            require(points.size() < 65 && points.add(bits.read(width)));
        }
    }

    private record Book(int dimensions, int entries, boolean mapped) {}
    private static Book book(Bits bits, Book[] books, boolean mapped) {
        int index = bits.read(8);
        require(index < books.length && (!mapped || books[index].mapped()));
        return books[index];
    }
    private static int lookupValues(int entries, int dimensions) {
        int low = 1, high = entries;
        while (low < high) {
            int mid = low + (high - low + 1) / 2;
            if (powerFits(mid, dimensions, entries)) low = mid; else high = mid - 1;
        }
        return low;
    }
    private static boolean powerFits(int base, int exponent, int limit) {
        if (base == 1) return true;
        long value = 1;
        for (int i = 0; i < exponent; i++) {
            value *= base;
            if (value > limit) return false;
        }
        return true;
    }
    static int ilog(int value) { return 32 - Integer.numberOfLeadingZeros(value); }
    private static void require(boolean valid) { if (!valid) throw new Invalid(); }
    private static final class Invalid extends RuntimeException {
        Invalid() { super(null, null, false, false); }
    }
    private static final class Bits {
        private final byte[] data;
        private long position;
        Bits(byte[] data, long position) { this.data = data; this.position = position; }
        void skip(long count) {
            require(count >= 0 && count <= (long) data.length * 8 - position);
            position += count;
        }
        int read(int count) {
            long start = position;
            skip(count);
            int value = 0;
            for (int bit = 0; bit < count; bit++) {
                long at = start + bit;
                value |= ((data[(int)(at >>> 3)] >>> (int)(at & 7)) & 1) << bit;
            }
            return value;
        }
    }
}
