package io.github.noteblockworldmc.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Backs {@code /nbw config}: which settings may be edited in game, how their
 * values are validated, and how one line of config.yml is rewritten without
 * disturbing the rest of the file.
 *
 * <p>Scope is deliberately narrow: playback settings only. Those are the ones
 * worth changing from in game, because every one of them is audible on the very
 * next note, so an admin can tune by ear while a song plays.
 *
 * <p>Other settings are edited in config.yml and applied with /nbw reload.
 * Pack changes may rebuild audio, re-push resources, or rebind its HTTP server;
 * this editor stays focused on playback controls that can be tuned by ear.
 *
 * <p>Writes edit a single line in place. Bukkit's {@code saveConfig()} would
 * serialise the whole tree from memory, and this file's comments carry most of
 * its documentation, so a round trip through the YAML writer risks losing them.
 */
public final class ConfigEditor {

    /** How a value is parsed and displayed. */
    public enum Kind { BOOLEAN, INTEGER, DECIMAL }

    /**
     * One editable setting.
     * <p>
     * {@code min}/{@code max} mirror the clamps the plugin applies when it
     * reads the value. They exist so the command can REJECT an out-of-range
     * value: silently clamping would leave config.yml disagreeing with the
     * plugin's behaviour, and the admin with no idea why.
     */
    public record Option(String path, Kind kind, double min, double max) {

        /** Trailing part of the path, which is what admins actually type. */
        public String shortName() {
            int dot = path.lastIndexOf('.');
            return dot < 0 ? path : path.substring(dot + 1);
        }

        public String section() {
            int dot = path.lastIndexOf('.');
            return dot < 0 ? "" : path.substring(0, dot);
        }

        public String range() {
            return switch (kind) {
                case BOOLEAN -> "true or false";
                case INTEGER -> formatNumber(min) + " - " + formatNumber(max);
                case DECIMAL -> formatNumber(min) + " - " + formatNumber(max);
            };
        }
    }

    private static final Map<String, Option> BY_PATH = new LinkedHashMap<>();

    private static void add(String path, Kind kind, double min, double max) {
        BY_PATH.put(path, new Option(path, kind, min, max));
    }

    static {
        // ---- playback: every one of these is re-read by loadHotSettings() ----
        add("playback.show-actionbar", Kind.BOOLEAN, 0, 1);
        add("playback.actionbar-length", Kind.INTEGER, 4, 40);
        add("playback.respect-loop", Kind.BOOLEAN, 0, 1);
        add("playback.high-precision", Kind.BOOLEAN, 0, 1);
        add("playback.precision-ms", Kind.INTEGER, 2, 25);
        add("playback.soft-limiter", Kind.BOOLEAN, 0, 1);
        // The plugin enforces only a lower bound here; the upper limit is this
        // command's own sanity check, since a chord cannot exceed the parser's
        // 128-notes-per-tick cap anyway.
        add("playback.limiter-threshold", Kind.INTEGER, 2, 128);
        add("playback.audible-floor", Kind.DECIMAL, 0, 1);
        add("playback.fade-out-ms", Kind.INTEGER, 0, 5000);
        add("playback.voice-allocation", Kind.BOOLEAN, 0, 1);
        add("playback.client-channels", Kind.INTEGER, 16, 8192);
        // Clamped again to client-channels when read, so a value above the
        // configured pool is accepted here but takes effect as the pool size.
        add("playback.voice-ceiling", Kind.INTEGER, 16, 8192);
        add("playback.humanize", Kind.BOOLEAN, 0, 1);
        add("playback.humanize-timing-ms", Kind.DECIMAL, 0, 50);
        add("playback.humanize-velocity", Kind.DECIMAL, 0, 0.5);
        add("playback.humanize-drift", Kind.DECIMAL, 0, 1);
        add("playback.humanize-percussion-factor", Kind.DECIMAL, 0, 1);
        add("playback.follow-player", Kind.BOOLEAN, 0, 1);
        // Only has an effect while follow-player is false: an attached sound
        // has no world position to offset for stereo.
        add("playback.stereo-panning", Kind.BOOLEAN, 0, 1);
        add("playback.pan-distance", Kind.DECIMAL, 0.1, 8.0);
    }

    private ConfigEditor() {
    }

    public static List<Option> options() {
        return List.copyOf(BY_PATH.values());
    }

    /**
     * Looks up a setting by full path ("playback.precision-ms") or by its short
     * name ("precision-ms"), case-insensitively.
     * <p>
     * Short names are unambiguous across the editable set; a name that ever
     * collided would resolve to nothing rather than guess a section.
     */
    public static Option find(String name) {
        if (name == null || name.isBlank()) return null;
        String needle = name.trim().toLowerCase(Locale.ROOT);
        Option exact = BY_PATH.get(needle);
        if (exact != null) return exact;

        Option match = null;
        for (Option option : BY_PATH.values()) {
            if (option.shortName().equals(needle)) {
                if (match != null) return null;   // ambiguous: refuse to guess
                match = option;
            }
        }
        return match;
    }

    /** Editable setting names, for tab completion. */
    public static List<String> names() {
        List<String> out = new ArrayList<>(BY_PATH.size());
        for (Option option : BY_PATH.values()) out.add(option.shortName());
        return out;
    }

    // ------------------------------------------------------------------
    // Parsing and validation
    // ------------------------------------------------------------------

    /** Outcome of validating raw input: either a value to write, or a reason. */
    public record Parsed(Object value, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    public static Parsed parse(Option option, String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) return new Parsed(null, "no value given");

        switch (option.kind()) {
            case BOOLEAN -> {
                if (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("yes")
                        || text.equalsIgnoreCase("on")) {
                    return new Parsed(Boolean.TRUE, null);
                }
                if (text.equalsIgnoreCase("false") || text.equalsIgnoreCase("no")
                        || text.equalsIgnoreCase("off")) {
                    return new Parsed(Boolean.FALSE, null);
                }
                return new Parsed(null, "expected true or false");
            }
            case INTEGER -> {
                long parsed;
                try {
                    parsed = Long.parseLong(text);
                } catch (NumberFormatException e) {
                    // A decimal here is a likely typo rather than nonsense, so
                    // name the actual problem instead of "not a number".
                    return new Parsed(null, text.contains(".")
                            ? "expected a whole number" : "expected a number");
                }
                if (parsed < option.min() || parsed > option.max()) {
                    return new Parsed(null, "must be between "
                            + formatNumber(option.min()) + " and " + formatNumber(option.max()));
                }
                return new Parsed(parsed, null);
            }
            case DECIMAL -> {
                double parsed;
                try {
                    parsed = Double.parseDouble(text);
                } catch (NumberFormatException e) {
                    return new Parsed(null, "expected a number");
                }
                if (Double.isNaN(parsed) || Double.isInfinite(parsed)) {
                    return new Parsed(null, "expected a finite number");
                }
                if (parsed < option.min() || parsed > option.max()) {
                    return new Parsed(null, "must be between "
                            + formatNumber(option.min()) + " and " + formatNumber(option.max()));
                }
                return new Parsed(parsed, null);
            }
        }
        return new Parsed(null, "unsupported setting type");
    }

    /** Renders a value the way it should appear in YAML and in chat. */
    public static String render(Object value) {
        if (value instanceof Boolean bool) return bool.toString();
        if (value instanceof Double dbl) return formatNumber(dbl);
        return String.valueOf(value);
    }

    /** Drops a pointless trailing ".0" so ranges read as 0 - 50, not 0.0 - 50.0. */
    private static String formatNumber(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return String.valueOf((long) value);
        }
        String text = String.valueOf(value);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }

    // ------------------------------------------------------------------
    // Reading the file: comments as documentation
    // ------------------------------------------------------------------

    /** What the file says about a setting, and where its line is. */
    public record Located(int lineIndex, String indent, String rawValue, List<String> comments) {

        /**
         * The first paragraph of the comment block.
         * <p>
         * These blocks are written as a short summary, then a blank {@code #}
         * line, then the detail -- and the longest run to 21 lines, which would
         * bury a Minecraft chat window. Showing the leading paragraph gives the
         * explanation that matters and leaves the reasoning in the file for
         * anyone who wants it.
         */
        public List<String> summary() {
            List<String> out = new ArrayList<>();
            for (String line : comments) {
                if (line.isBlank()) break;
                out.add(line);
                if (out.size() == 6) break;   // safety net for an unbroken block
            }
            return out;
        }

        /** True when the file holds more explanation than the summary shows. */
        public boolean hasMoreDetail() {
            return comments.size() > summary().size();
        }
    }

    /**
     * Finds a setting's line in config.yml, together with the comment block
     * directly above it.
     * <p>
     * The comments ARE the documentation for these settings, so surfacing them
     * through {@code /nbw config <setting>} keeps one source of truth rather
     * than duplicating every explanation into messages.yml where the two would
     * drift apart.
     * <p>
     * Returns null when the file has no such key, which happens on servers
     * whose config.yml predates a setting -- worth reporting rather than
     * silently writing nothing.
     */
    public static Located locate(List<String> lines, Option option) {
        String section = option.section();
        String leaf = option.shortName();

        int start = 0;
        if (!section.isEmpty()) {
            start = -1;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.startsWith(section + ":") && !line.startsWith(" ")) {
                    start = i + 1;
                    break;
                }
            }
            if (start < 0) return null;
        }

        // Depth of the section's own keys, learned from the first one seen.
        // Without this, a key nested one level deeper -- inside a sub-map an
        // admin added by hand -- would match on its trimmed name alone and the
        // write would land on the wrong line.
        int expectedIndent = -1;

        for (int i = start; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            // A new top-level key ends the section we are searching.
            if (!section.isEmpty() && !line.isBlank() && !line.startsWith(" ")
                    && !trimmed.startsWith("#")) {
                return null;
            }
            if (trimmed.startsWith("#") || trimmed.isEmpty()) continue;

            int colon = line.indexOf(':');
            if (colon < 0) continue;
            int indentWidth = line.length() - line.stripLeading().length();
            if (expectedIndent < 0) {
                expectedIndent = indentWidth;
            }
            if (indentWidth != expectedIndent) continue;   // nested, not ours

            String key = line.substring(0, colon).trim();
            if (!key.equals(leaf)) continue;

            String indent = line.substring(0, indentWidth);
            String value = line.substring(colon + 1).trim();
            return new Located(i, indent, value, commentsAbove(lines, i));
        }
        return null;
    }

    /** Contiguous comment lines immediately above an index, in file order. */
    private static List<String> commentsAbove(List<String> lines, int index) {
        List<String> out = new ArrayList<>();
        for (int i = index - 1; i >= 0; i--) {
            String trimmed = lines.get(i).trim();
            if (!trimmed.startsWith("#")) break;
            String text = trimmed.substring(1);
            if (text.startsWith(" ")) text = text.substring(1);
            out.addFirst(text);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Writing: one line, in place
    // ------------------------------------------------------------------

    /**
     * Replaces one setting's line while preserving surrounding settings and comments.
     * <p>
     * Written to a sibling temp file and moved into place, so a crash midway
     * cannot leave a half-written config.yml -- the plugin would refuse to load
     * on the next start.
     *
     * @return the previous raw value
     * @throws IOException if the key is absent or the file cannot be replaced
     */
    public static String write(Path configFile, Option option, String newValue) throws IOException {
        List<String> lines = new ArrayList<>(Files.readAllLines(configFile, StandardCharsets.UTF_8));
        Located located = locate(lines, option);
        if (located == null) {
            throw new IOException("config.yml has no " + option.path());
        }

        String previous = located.rawValue();
        lines.set(located.lineIndex(), located.indent() + option.shortName() + ": " + newValue);

        Path temp = configFile.resolveSibling(configFile.getFileName() + ".tmp");
        Files.write(temp, lines, StandardCharsets.UTF_8);
        try {
            Files.move(temp, configFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            // Some filesystems (certain network mounts) cannot move atomically.
            Files.move(temp, configFile, StandardCopyOption.REPLACE_EXISTING);
        }
        return previous;
    }
}
