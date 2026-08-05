package dev.example.noteblockworldmc.nbs;

/**
 * A single playable note, precomputed at parse time.
 *
 * @param instrument  vanilla instrument index (0-19), or -1 for a custom
 *                    instrument note
 * @param pitch       Minecraft sound pitch (0.5-2.0) for the folded key
 *                    (folded around 45 for vanilla, or around the custom
 *                    instrument's own base key)
 * @param volume      0.0-1.0, layer volume and note velocity baked in
 * @param octave      octaves the real key sits outside the base window
 *                    (-3..+3); selects the extended-pack sound variant
 * @param customIndex index into the song's custom instrument list, or -1
 *                    for vanilla notes
 * @param pan         stereo position, -1 (full left) .. 0 (centre) .. +1
 *                    (full right); note panning already combined with the
 *                    layer's stereo setting
 */
public record PlayableNote(int instrument, float pitch, float volume, int octave,
                           int customIndex, float pan) {

    /**
     * Whether this note plays a custom instrument sample rather than a
     * vanilla one.
     *
     * INVARIANT: instrument == -1 exactly when customIndex >= 0 — the parser
     * always sets the two together. Consumers should call this rather than
     * testing either field directly, so the two checks can never drift apart.
     *
     * Deliberately no validation in a compact constructor: this record is
     * materialized transiently by NbsSong.getNotesAt on the clock thread
     * every tick (and millions of times during an analyze sweep), so
     * per-instance checks would sit squarely in the hot path. The ranges are
     * enforced once at parse time and again when NbsSong packs them into its
     * narrow primitive arrays.
     */
    public boolean isCustom() {
        return customIndex >= 0;
    }
}
