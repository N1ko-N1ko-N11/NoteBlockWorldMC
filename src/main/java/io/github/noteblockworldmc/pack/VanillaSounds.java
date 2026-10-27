package io.github.noteblockworldmc.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.noteblockworldmc.NoteblockWorldMCPlugin;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Obtains the vanilla note block sounds so the pack can ship
 * octave-shifted copies of them (see {@link OggTools} for why shifted
 * COPIES are required rather than pitch multipliers).
 *
 * The audio is pulled from Mojang's own asset CDN — the same source the
 * game itself downloads from, and the same approach noteblock.world uses
 * when packing custom instruments. Files are cached in
 * plugins/NoteblockWorldMC/vanilla-sounds/ and only fetched once.
 *
 * A server owner who prefers not to fetch anything can simply drop the
 * .ogg files into that folder by hand (or disable the feature); anything
 * already present is never re-downloaded.
 */
public class VanillaSounds {

    private static final String VERSION_MANIFEST =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String ASSET_BASE =
            "https://resources.download.minecraft.net/";
    /**
     * Asset keys for note block audio are NOT under the sound event's path
     * (block.note_block.harp): historically the files live in
     * "minecraft/sounds/note/harp.ogg". Rather than hard-code a guess that
     * breaks whenever Mojang moves them, scan every ".ogg" asset whose path
     * mentions "note" and index it by file stem.
     */
    private static final String SOUND_MARKER = "note";

    /** Upper bound on any single fetch (asset index is the biggest, ~1 MB). */
    private static final long MAX_FETCH_BYTES = 8L * 1024 * 1024;

    /**
     * Asset file each note block event plays, for when sounds.json itself
     * cannot be read. Five stems differ from the instrument name. Harp and
     * bass matter most: Minecraft 1.12 moved those events to harp2.ogg and
     * bassattack.ogg but still ships the retired harp.ogg and bass.ogg, so a
     * lookup by instrument name finds a real -- but different -- sample.
     * Verified against the 26.3 client's sounds.json.
     */
    static final Map<String, String> ASSET_ALIASES = Map.of(
            "harp", "harp2",
            "bass", "bassattack",
            "basedrum", "bd",
            "chime", "icechime",
            "xylophone", "xylobone");
    /** The stems releases up to 1.1.0 selected; only used to recognise files they cached. */
    private static final Map<String, String> PREVIOUS_ALIASES = Map.of(
            "basedrum", "bd",
            "chime", "icechime",
            "xylophone", "xylobone");

    /**
     * Version of the rules that choose which asset backs each cached file.
     * A cache written under older rules is re-verified once against Mojang's
     * index; files the old rules downloaded by mistake are replaced, and any
     * other file an administrator placed there is left alone.
     */
    static final String CACHE_LAYOUT = "2";
    static final String LAYOUT_FILE = ".layout";
    private volatile boolean changedOnLastRun;

    private final NoteblockWorldMCPlugin plugin;
    private final Path cacheDir;
    private final SampleCache samples = new SampleCache(16L * 1024 * 1024);
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final io.github.noteblockworldmc.util.BoundedHttp bounded = new io.github.noteblockworldmc.util.BoundedHttp(http);

    public VanillaSounds(NoteblockWorldMCPlugin plugin) {
        this.plugin = plugin;
        this.cacheDir = plugin.getDataFolder().toPath().resolve("vanilla-sounds");
    }

    /** Releases the HTTP client's threads (call on plugin disable). */
    public void close() {
        bounded.close();
        samples.clear();
    }

    /** A reload permits another lookup after a temporary CDN/index failure. */
    public void resetLookupFailures() {
        indexFetchFailed = false;
        soundEventsFetchFailed = false;
        samples.invalidateFiles();
    }

    /** Every instrument is cached and was chosen by the current selection rules. */
    public boolean isComplete() {
        if (!hasAll()) return false;
        Path marker = cacheDir.resolve(LAYOUT_FILE);
        try {
            return Files.isRegularFile(marker, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    && Files.size(marker) < 64
                    && Files.readString(marker, StandardCharsets.UTF_8).trim().equals(CACHE_LAYOUT);
        } catch (IOException e) { return false; }
    }

    /** True once after a download run that added or replaced a cached sample. */
    public boolean consumeChanged() {
        boolean changed = changedOnLastRun;
        changedOnLastRun = false;
        return changed;
    }

    /**
     * Picks the asset file each instrument's note block event really plays.
     *
     * The event's first sound in sounds.json is authoritative; the alias table
     * covers a missing or unreadable sounds.json, and a stem search under any
     * "note" directory covers a future move of the files. Pure, so the choice
     * can be checked without network access.
     *
     * @param eventSounds sound event id to its first sound name, e.g.
     *                    "block.note_block.harp" to "note/harp2"; may be empty
     * @param assetKeys   every key in the asset index
     * @return instrument name to asset key; unresolvable instruments are absent
     */
    static Map<String, String> instrumentAssets(Map<String, String> eventSounds, java.util.Collection<String> assetKeys) {
        java.util.Set<String> keys = assetKeys instanceof java.util.Set<String> set ? set : new java.util.HashSet<>(assetKeys);
        Map<String, String> chosen = new java.util.LinkedHashMap<>();
        for (String instrument : ResourcePackService.INSTRUMENT_NAMES) {
            String sound = eventSounds.get("block.note_block." + instrument);
            if (sound != null) {
                int colon = sound.indexOf(':');
                String key = "minecraft/sounds/" + (colon >= 0 ? sound.substring(colon + 1) : sound) + ".ogg";
                if (keys.contains(key)) { chosen.put(instrument, key); continue; }
            }
            String alias = "minecraft/sounds/note/" + ASSET_ALIASES.getOrDefault(instrument, instrument) + ".ogg";
            if (keys.contains(alias)) { chosen.put(instrument, alias); continue; }
            String moved = stemMatch(assetKeys, ASSET_ALIASES.getOrDefault(instrument, instrument));
            if (moved != null) chosen.put(instrument, moved);
        }
        return chosen;
    }

    /**
     * Whether a cached sample must be re-downloaded: only when it is
     * byte-identical to the asset an earlier release chose by mistake. The
     * correct file stays, and so does anything else an administrator supplied.
     */
    static boolean replaceCached(String cachedSha1, String expectedSha1, String previousSha1) {
        if (cachedSha1.equalsIgnoreCase(expectedSha1)) return false;
        return previousSha1 != null && !previousSha1.equalsIgnoreCase(expectedSha1)
                && cachedSha1.equalsIgnoreCase(previousSha1);
    }

    /** The asset a release up to 1.1.0 would have cached for this instrument. */
    static String previousAsset(String instrument, java.util.Collection<String> assetKeys) {
        return stemMatch(assetKeys, PREVIOUS_ALIASES.getOrDefault(instrument, instrument));
    }

    /** First ".ogg" under a sounds directory mentioning "note" whose file stem matches. */
    private static String stemMatch(java.util.Collection<String> assetKeys, String stem) {
        for (String key : assetKeys) {
            if (!key.endsWith(".ogg") || !key.contains("/sounds/")
                    || !key.toLowerCase(java.util.Locale.ROOT).contains(SOUND_MARKER)) continue;
            if (key.substring(key.lastIndexOf('/') + 1, key.length() - 4).equals(stem)) return key;
        }
        return null;
    }

    /** First sound named by each note block event in the client's sounds.json, or empty. */
    private Map<String, String> noteBlockEventSounds() {
        JsonObject registry = soundEventRegistry();
        Map<String, String> out = new HashMap<>();
        if (registry == null) return out;
        for (String instrument : ResourcePackService.INSTRUMENT_NAMES) {
            String event = "block.note_block." + instrument;
            try {
                JsonObject entry = registry.getAsJsonObject(event);
                JsonArray sounds = entry == null ? null : entry.getAsJsonArray("sounds");
                if (sounds == null || sounds.isEmpty()) continue;
                JsonElement first = sounds.get(0);
                // A nested event reference is not a file; leave it to the fallbacks.
                if (first.isJsonObject()) {
                    JsonObject object = first.getAsJsonObject();
                    if (object.has("type") && "event".equals(object.get("type").getAsString())) continue;
                    if (object.has("name")) out.put(event, object.get("name").getAsString());
                } else {
                    out.put(event, first.getAsString());
                }
            } catch (RuntimeException ignored) {
                // Malformed entry: the alias table still applies.
            }
        }
        return out;
    }

    /** True when every instrument's sound is already cached on disk. */
    public boolean hasAll() {
        for (String instrument : ResourcePackService.INSTRUMENT_NAMES) {
            if (get(instrument) == null) return false;
        }
        return true;
    }

    /**
     * Cached sound file for an instrument, or null if unavailable.
     *
     * Checks the file really IS an Ogg, not merely that it exists: a
     * truncated or empty cache file (a crash or full disk mid-write) would
     * otherwise satisfy hasAll(), so the sound would never be re-fetched,
     * while the pack builder's own isOgg() check silently dropped every
     * octave variant for that instrument — permanently, with no error.
     * Treating it as absent makes the next startup heal it.
     */
    public Path get(String instrument) {
        return sample(instrument) == null ? null : cacheDir.resolve(instrument + ".ogg");
    }

    /** Duration and bytes come from the same validated file revision. */
    public double sampleSeconds(String instrument) {
        OggTools.Sample sample = sample(instrument);
        return sample == null ? -1 : sample.seconds();
    }

    OggTools.Sample shiftedSample(OggTools.Sample source, int octave) { return samples.shift(source, octave); }

    OggTools.Sample sample(String instrument) {
        if (instrument == null || !instrument.matches("[A-Za-z0-9_]{1,32}")
                || Files.isSymbolicLink(cacheDir)) return null;
        Path file = cacheDir.resolve(instrument + ".ogg");
        if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            return samples.read(file);
        } catch (IOException e) { return null; }
    }

    /**
     * Asset index for the running version, parsed once and kept.
     *
     * It is roughly a megabyte of JSON and identical for every lookup, so a
     * song with twenty custom instruments must not fetch it twenty times.
     */
    private volatile JsonObject cachedIndex;
    private volatile boolean indexFetchFailed;

    /** Cache for soundEventRegistry(); see resolveSoundEvent(). */
    private volatile JsonObject cachedSoundEvents;
    private volatile boolean soundEventsFetchFailed;

    /**
     * Finds a song's custom instrument sound in Minecraft's own assets.
     *
     * A .nbs file names its custom instruments but cannot contain them, so an
     * imported song arrives with the names and no audio. Note Block World
     * sidesteps this by only accepting sounds that already exist in Minecraft
     * ("we'd like to keep songs in a Minecraft feel"), and Note Block Studio
     * solves it locally by copying sounds out of a Java installation. This is
     * the server-side equivalent: if the declared path names a real Minecraft
     * sound, fetch that sound from Mojang's CDN.
     *
     * Nothing here guesses. Candidate asset keys are looked up in Mojang's
     * index, which is authoritative -- a hit means the sound genuinely exists
     * in the game, a miss means it does not and the caller should report it
     * rather than substitute something.
     *
     * @return the audio, or null if the path names no vanilla sound
     */
    public byte[] resolveCustomSound(String declaredPath) {
        if (declaredPath == null || declaredPath.isBlank()) return null;
        String displayPath = safeForLog(declaredPath);

        JsonObject index = assetIndex();
        if (index == null) {
            // Worth separating from "not a Minecraft sound": the name may be
            // perfectly valid and simply unlookupable right now.
            plugin.getLogger().warning("Cannot check '" + displayPath
                    + "' because Mojang's asset index is unavailable.");
            return null;
        }
        JsonObject objects = index.getAsJsonObject("objects");
        if (objects == null) {
            plugin.getLogger().warning("Mojang's asset index has no 'objects' section;"
                    + " cannot check '" + displayPath + "'.");
            return null;
        }

        for (String candidate : candidateAssetKeys(declaredPath)) {
            JsonObject entry = objects.getAsJsonObject(candidate);
            if (entry == null) continue;
            String hash = entry.get("hash").getAsString();

            byte[] bytes;
            try {
                bytes = fetch(ASSET_BASE + hash.substring(0, 2) + "/" + hash);
            } catch (IOException e) {
                plugin.getLogger().warning("Could not fetch vanilla sound '"
                        + safeForLog(candidate) + "': " + e);
                continue;
            } catch (InterruptedException e) {
                // Restore the flag and give up: the caller treats null as
                // "not recoverable", which is the right outcome for a
                // shutdown interrupting an import.
                Thread.currentThread().interrupt();
                return null;
            }
            if (bytes == null || !OggTools.isVorbis(bytes)) continue;
            // Mojang's asset key IS the file's SHA-1, so this is free
            // integrity checking rather than an extra round trip.
            String actual = sha1Hex(bytes);
            if (!actual.equalsIgnoreCase(hash)) {
                plugin.getLogger().warning("Checksum mismatch for vanilla sound '"
                        + safeForLog(candidate) + "'; skipping.");
                continue;
            }
            return bytes;
        }

        // The declared name may not be a file path at all: NBS custom
        // instruments are sometimes saved under Minecraft's SOUND EVENT id
        // (the same dotted string /playsound uses, e.g.
        // "entity.experience_orb.pickup") rather than a physical asset path.
        // The two are not interchangeable -- that particular event resolves
        // to "random/orb", a name with no derivable relationship to the
        // event id -- so this has to go through the same sounds.json the
        // client itself reads, not a guessed transformation of the string.
        String eventPath = resolveSoundEvent(stripKnownExtension(declaredPath), 0);
        if (eventPath == null) {
            // Still nothing. The name may be a LEGACY event id: Minecraft
            // renamed many events in 1.13 ("entity.firework.blast_far" became
            // "entity.firework_rocket.blast_far"), and the underlying FILE
            // often kept its old location ("fireworks/blast_far.ogg"), so
            // neither the current registry nor a dotted-path guess finds it.
            //
            // Rather than carry a rename table that would need maintaining
            // every version, search the index Mojang actually shipped for a
            // sound whose path ends with this name. Only an unambiguous match
            // is accepted -- guessing between several candidates would give a
            // song the wrong instrument, which is worse than reporting it.
            eventPath = searchIndexBySuffix(objects, stripKnownExtension(declaredPath));
        }
        if (eventPath != null) {
            for (String candidate : candidateAssetKeys(eventPath)) {
                JsonObject entry = objects.getAsJsonObject(candidate);
                if (entry == null) continue;
                String hash = entry.get("hash").getAsString();

                byte[] bytes;
                try {
                    bytes = fetch(ASSET_BASE + hash.substring(0, 2) + "/" + hash);
                } catch (IOException e) {
                    plugin.getLogger().warning("Could not fetch vanilla sound '"
                            + safeForLog(candidate) + "': " + e);
                    continue;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                if (bytes == null || !OggTools.isVorbis(bytes)) continue;
                String actual = sha1Hex(bytes);
                if (!actual.equalsIgnoreCase(hash)) {
                    plugin.getLogger().warning("Checksum mismatch for vanilla sound '"
                            + safeForLog(candidate) + "'; skipping.");
                    continue;
                }
                return bytes;
            }
        }

        // Everything was tried and nothing matched. Say what was attempted:
        // without this the message is just "not a Minecraft sound", which is
        // indistinguishable from a bug in the matching itself.
        int soundCount = 0;
        for (String key : objects.keySet()) {
            if (key.startsWith("minecraft/sounds/")) soundCount++;
        }
        plugin.getLogger().warning("No Minecraft sound matches '" + displayPath
                + "'. Tried as a file path " + safeForLog(String.valueOf(candidateAssetKeys(declaredPath)))
                + ", as sound event '" + safeForLog(stripKnownExtension(declaredPath))
                + "', and by searching " + soundCount
                + " indexed sounds for a matching path.");

        // Say what IS there under a similar name. Minecraft moves and renames
        // sound files between versions, so the useful question is not "was it
        // found" but "what is it called now" -- and only the index that
        // shipped with this server can answer that.
        List<String> near = nearestSounds(objects, stripKnownExtension(declaredPath), 6);
        if (!near.isEmpty()) {
            plugin.getLogger().warning("  Similar sounds in this version: " + near
                    + ". If one of these is the intended sound, set the custom"
                    + " instrument to that path in Note Block Studio, or drop the"
                    + " .ogg into the song's sounds/ folder yourself.");
        }
        return null;
    }

    /** Keeps arbitrary instrument names from forging additional console lines. */
    private static String safeForLog(String text) {
        if (text == null) return "";
        String cleaned = text.replaceAll("[\\p{Cntrl}]", "?");
        return cleaned.length() > 160 ? cleaned.substring(0, 157) + "..." : cleaned;
    }

    /**
     * Sounds in the shipped index whose path resembles the requested name.
     *
     * Purely diagnostic: it never feeds recovery, because a loose resemblance
     * is exactly the kind of match that would hand a song the wrong
     * instrument. It exists so a failure names the real alternatives instead
     * of leaving an admin to guess what Minecraft calls the sound now.
     *
     * Scored on how many of the requested segments appear in the candidate
     * path, with the last segment (the file name) weighted highest, since
     * that is what survives Minecraft's directory reshuffles.
     */
    private static List<String> nearestSounds(JsonObject objects, String name, int limit) {
        if (name == null || name.isBlank()) return List.of();
        String[] segments = name.toLowerCase(java.util.Locale.ROOT)
                .replace('.', '/').split("/");
        if (segments.length == 0) return List.of();
        String last = segments[segments.length - 1];

        record Scored(String path, int score) {}
        List<Scored> hits = new ArrayList<>();
        for (String key : objects.keySet()) {
            if (!key.startsWith("minecraft/sounds/") || !key.endsWith(".ogg")) continue;
            String path = key.substring("minecraft/sounds/".length(),
                    key.length() - ".ogg".length());
            String lower = path.toLowerCase(java.util.Locale.ROOT);

            int score = 0;
            for (String seg : segments) {
                if (seg.length() >= 3 && lower.contains(seg)) score++;
            }
            // The file name matching is worth more than any directory match.
            if (last.length() >= 3 && lower.endsWith("/" + last)) score += 3;
            if (score > 0) hits.add(new Scored(path, score));
        }
        hits.sort((a, b) -> b.score() != a.score()
                ? Integer.compare(b.score(), a.score())
                : a.path().compareTo(b.path()));

        List<String> out = new ArrayList<>(limit);
        for (Scored hit : hits) {
            if (out.size() >= limit) break;
            out.add(hit.path());
        }
        return out;
    }

    /**
     * Last-resort lookup: find a shipped sound whose path ends with the name
     * the song asked for.
     *
     * The name is tried from most to least specific -- "entity/firework/
     * blast_far.ogg", then "firework/blast_far.ogg", then "blast_far.ogg" --
     * and the first length that produces exactly ONE match wins. Requiring
     * uniqueness is the point: several matches means the name is ambiguous
     * and any pick would be a guess, so it reports nothing instead.
     *
     * @return a path relative to assets/minecraft/sounds/, or null
     */
    private static String searchIndexBySuffix(JsonObject objects, String name) {
        if (name == null || name.isBlank()) return null;
        // Dots and slashes are both segment separators by this point.
        String[] segments = name.replace('.', '/').split("/");
        if (segments.length == 0) return null;

        for (int from = 0; from < segments.length; from++) {
            StringBuilder tail = new StringBuilder();
            for (int i = from; i < segments.length; i++) {
                if (tail.length() > 0) tail.append('/');
                tail.append(segments[i]);
            }
            // Anchored on a separator so "blast_far.ogg" cannot match
            // "large_blast_far.ogg".
            String suffix = "/" + tail + ".ogg";

            String found = null;
            for (String key : objects.keySet()) {
                if (!key.startsWith("minecraft/sounds/") || !key.endsWith(suffix)) continue;
                if (found != null) {
                    found = null;       // ambiguous at this length
                    break;
                }
                found = key;
            }
            if (found != null) {
                return found.substring("minecraft/sounds/".length(),
                        found.length() - ".ogg".length());
            }
        }
        return null;
    }

    /**
     * Resolves a Minecraft sound EVENT id to one of the real files it plays,
     * via the client's own sounds.json.
     *
     * An event can list several interchangeable files -- the game picks one
     * at random each time the event plays -- and can point to ANOTHER event
     * instead of a file at all. The first listed file is always chosen here
     * rather than picking randomly, so the pack a song produces is the same
     * on every rebuild; event indirection is followed with a depth limit so a
     * malformed or self-referential registry cannot hang an import.
     *
     * @return a path relative to assets/minecraft/sounds/, or null if the id
     *         is not a known event or the registry could not be read
     */
    private String resolveSoundEvent(String eventId, int depth) {
        if (eventId == null || eventId.isBlank() || depth > 4) return null;
        JsonObject registry = soundEventRegistry();
        if (registry == null) return null;
        JsonObject event = registry.getAsJsonObject(eventId);
        if (event == null) {
            // The id may predate a rename. Minecraft's 1.13 Flattening made
            // many event names MORE specific without reordering them --
            // "entity.firework.blast_far" became
            // "entity.firework_rocket.blast_far" -- and a song saved before
            // that carries the old name forever.
            String renamed = findRenamedEvent(registry, eventId);
            if (renamed == null) return null;
            plugin.getLogger().info("Sound event '" + eventId
                    + "' appears to have been renamed to '" + renamed + "'; using that.");
            event = registry.getAsJsonObject(renamed);
            if (event == null) return null;
        }
        JsonArray sounds = event.getAsJsonArray("sounds");
        if (sounds == null || sounds.isEmpty()) return null;

        JsonElement first = sounds.get(0);
        String name;
        boolean isNestedEvent = false;
        if (first.isJsonObject()) {
            JsonObject obj = first.getAsJsonObject();
            if (!obj.has("name")) return null;
            name = obj.get("name").getAsString();
            isNestedEvent = obj.has("type") && "event".equals(obj.get("type").getAsString());
        } else {
            name = first.getAsString();
        }

        // A "namespace:path" form crosses into a different asset namespace;
        // vanilla's own registry never does, and following one would need
        // fetching that namespace's assets, which this does not do.
        int colon = name.indexOf(':');
        if (colon >= 0) name = name.substring(colon + 1);

        return isNestedEvent ? resolveSoundEvent(name, depth + 1) : name;
    }

    /**
     * Finds the modern name of a renamed sound event.
     *
     * Deliberately narrow. A candidate qualifies only when it has the same
     * number of dot-separated parts, its first and last parts match exactly,
     * and every requested part is a PREFIX of the corresponding part. That is
     * precisely the shape of the Flattening's renames -- a part grew a
     * qualifier ("firework" to "firework_rocket") while the surrounding
     * structure stayed put -- and it will not match two unrelated events that
     * merely share a word.
     *
     * A tie is rejected rather than broken: handing a song a plausible-but-
     * wrong instrument is worse than reporting that nothing was found.
     */
    private static String findRenamedEvent(JsonObject registry, String eventId) {
        String[] want = eventId.toLowerCase(java.util.Locale.ROOT).split("\\.");
        if (want.length < 2) return null;

        String match = null;
        for (String key : registry.keySet()) {
            String[] have = key.toLowerCase(java.util.Locale.ROOT).split("\\.");
            if (have.length != want.length) continue;
            if (!have[0].equals(want[0])) continue;
            if (!have[have.length - 1].equals(want[want.length - 1])) continue;

            boolean ok = true;
            boolean grew = false;
            for (int i = 1; i < want.length - 1; i++) {
                if (have[i].equals(want[i])) continue;
                if (have[i].startsWith(want[i])) {
                    grew = true;               // a part gained a qualifier
                    continue;
                }
                ok = false;
                break;
            }
            // Require at least one part to have actually grown, so this can
            // never "rename" an id to itself or to an equally valid sibling.
            if (!ok || !grew) continue;

            if (match != null) return null;    // ambiguous
            match = key;
        }
        return match;
    }

    /**
     * Mojang's client-side sound registry (assets/minecraft/sounds.json),
     * fetched at most once per server run alongside the asset index it is
     * looked up through.
     */
    private JsonObject soundEventRegistry() {
        JsonObject local = cachedSoundEvents;
        if (local != null) return local;
        if (soundEventsFetchFailed) return null;
        synchronized (this) {
            if (cachedSoundEvents != null) return cachedSoundEvents;
            if (soundEventsFetchFailed) return null;
            try {
                JsonObject index = assetIndex();
                JsonObject objects = index == null ? null : index.getAsJsonObject("objects");
                JsonObject entry = objects == null ? null
                        : objects.getAsJsonObject("minecraft/sounds.json");
                if (entry != null) {
                    String hash = entry.get("hash").getAsString();
                    byte[] bytes = fetch(ASSET_BASE + hash.substring(0, 2) + "/" + hash);
                    if (bytes != null) {
                        cachedSoundEvents = JsonParser
                                .parseString(new String(bytes, StandardCharsets.UTF_8))
                                .getAsJsonObject();
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Could not read Mojang's sound event registry: " + e);
            }
            if (cachedSoundEvents == null) soundEventsFetchFailed = true;
            return cachedSoundEvents;
        }
    }

    /** Strips a trailing .ogg/.wav/.mp3, leaving any other dots untouched. */
    private static String stripKnownExtension(String declaredPath) {
        String path = normalizeDeclaredPath(declaredPath);
        for (String ext : new String[]{".ogg", ".wav", ".mp3"}) {
            if (path.toLowerCase(java.util.Locale.ROOT).endsWith(ext)) {
                return path.substring(0, path.length() - ext.length());
            }
        }
        return path;
    }

    /**
     * A declared custom sound path reduced to one canonical spelling, with
     * separators and dot segments normalized.
     *
     * Exposed so the importer writes the recovered file under exactly the
     * path this class looked up. They used to normalise separately: a song
     * declaring "/dig/sand1.ogg" resolved fine here (leading slash stripped)
     * but the writer passed the raw string to Path.resolve, which treats a
     * leading slash as ABSOLUTE and discards the base directory -- so the
     * sound was downloaded and then thrown away.
     */
    public static String normalizeDeclaredPath(String declaredPath) {
        if (declaredPath == null) return "";
        String path = declaredPath.replace('\\', '/').trim();
        while (path.startsWith("./")) path = path.substring(2);
        while (path.startsWith("/")) path = path.substring(1);
        java.util.ArrayDeque<String> segments = new java.util.ArrayDeque<>();
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".")) continue;
            if (segment.equals("..")) {
                if (segments.isEmpty()) return "";
                segments.removeLast();
            } else {
                segments.addLast(segment);
            }
        }
        return String.join("/", segments);
    }

    /**
     * Asset keys a declared path might correspond to.
     *
     * Minecraft's assets are keyed "minecraft/sounds/<path>.ogg". NBS records
     * whatever is under its own Sounds folder, and its "import sounds from
     * Minecraft" feature lays those out mirroring the game, so a declared
     * "minecraft/dig/sand1.ogg" and a declared "dig/sand1.ogg" should both
     * find "minecraft/sounds/dig/sand1.ogg".
     *
     * NBS also accepts .wav and .mp3, which Minecraft never ships, so the
     * extension is normalised to .ogg before looking up.
     */
    private static List<String> candidateAssetKeys(String declaredPath) {
        String path = normalizeDeclaredPath(declaredPath);

        int dot = path.lastIndexOf('.');
        if (dot > 0) path = path.substring(0, dot);
        if (path.isBlank()) return List.of();
        String ogg = path + ".ogg";

        List<String> keys = new ArrayList<>(6);
        if (ogg.startsWith("minecraft/")) {
            // "minecraft/dig/sand1.ogg" -> "minecraft/sounds/dig/sand1.ogg"
            keys.add("minecraft/sounds/" + ogg.substring("minecraft/".length()));
        }
        keys.add("minecraft/sounds/" + ogg);
        // Already a full asset key.
        keys.add(ogg);

        // A dotted name may be a path written with dots rather than slashes.
        // Note Block Studio stores custom instruments under a mix of
        // conventions, so "block.note_block.harp" and "block/note_block/harp"
        // can both refer to the same file.
        if (path.indexOf('/') < 0 && path.indexOf('.') >= 0) {
            String slashed = path.replace('.', '/') + ".ogg";
            keys.add("minecraft/sounds/" + slashed);
            keys.add(slashed);
        }
        return keys;
    }

    /** The parsed asset index, fetched at most once per server run. */
    private JsonObject assetIndex() {
        JsonObject local = cachedIndex;
        if (local != null) return local;
        if (indexFetchFailed) return null;         // do not retry all session
        synchronized (this) {
            if (cachedIndex != null) return cachedIndex;
            if (indexFetchFailed) return null;
            try {
                cachedIndex = fetchAssetIndex(Bukkit.getMinecraftVersion());
            } catch (Exception e) {
                plugin.getLogger().warning("Could not read Mojang's asset index: " + e);
            }
            if (cachedIndex == null) indexFetchFailed = true;
            return cachedIndex;
        }
    }

    /**
     * Downloads any missing note block sounds. Blocking — call off-thread.
     *
     * @return number of instruments available afterwards
     */
    public int ensureDownloaded() throws IOException, InterruptedException {
        if (Files.isSymbolicLink(cacheDir)) {
            throw new IOException("Vanilla sound cache must not be a symbolic link");
        }
        Files.createDirectories(cacheDir);
        if (!Files.isDirectory(cacheDir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Vanilla sound cache must be a real directory");
        }
        if (isComplete()) return ResourcePackService.INSTRUMENT_NAMES.length;

        String version = Bukkit.getMinecraftVersion();
        plugin.getLogger().info("Checking vanilla note block sounds for Minecraft "
                + version + " (cached in " + cacheDir + ")...");

        JsonObject assetIndex = assetIndex();
        JsonObject objects = assetIndex == null ? null : assetIndex.getAsJsonObject("objects");
        if (objects == null) return countAvailable();
        // Ordered like the index itself, so stem fallbacks pick what earlier
        // releases picked when recognising files they downloaded.
        List<String> keys = new ArrayList<>(objects.keySet());
        Map<String, String> chosen = instrumentAssets(noteBlockEventSounds(), keys);

        int fetched = 0, replaced = 0;
        for (String instrument : ResourcePackService.INSTRUMENT_NAMES) {
            String key = chosen.get(instrument);
            if (key == null) {
                plugin.getLogger().warning("No vanilla sound found for '" + instrument
                        + "'; notes on it will stay within the vanilla octave range.");
                continue;
            }
            String hash = objects.getAsJsonObject(key).get("hash").getAsString();
            Path target = cacheDir.resolve(instrument + ".ogg");
            if (get(instrument) != null) {
                String have = sha1Hex(io.github.noteblockworldmc.util.Limits.read(target, MAX_FETCH_BYTES));
                String previous = previousAsset(instrument, keys);
                JsonObject previousEntry = previous == null || previous.equals(key) ? null : objects.getAsJsonObject(previous);
                if (!replaceCached(have, hash, previousEntry == null ? null : previousEntry.get("hash").getAsString())) continue;
                plugin.getLogger().info("Replacing cached " + instrument + ".ogg (" + previous.substring(previous.lastIndexOf('/') + 1)
                        + ") with " + key.substring(key.lastIndexOf('/') + 1) + ", the sample Minecraft actually plays.");
                replaced++;
            }
            byte[] bytes = fetch(ASSET_BASE + hash.substring(0, 2) + "/" + hash);
            if (bytes == null || !OggTools.isVorbis(bytes)) continue;
            // Mojang's asset keys ARE the SHA-1 of the file, so verifying is
            // free integrity checking: a corrupted or truncated transfer gets
            // rejected here instead of being cached forever as a dud sound.
            String actual = sha1Hex(bytes);
            if (!actual.equalsIgnoreCase(hash)) {
                plugin.getLogger().warning("Checksum mismatch for '" + instrument
                        + "' (expected " + hash + ", got " + actual + "); skipping.");
                continue;
            }
            // Cached under OUR instrument name, so the pack builder can find
            // it regardless of what Mojang called the file. Written via a
            // temp file + atomic move so an interrupted write can never leave
            // a half-file that later looks like a valid cache entry.
            writeAtomically(target, bytes);
            fetched++;
            // The source rate decides how far up we can shift (see OggTools)
            plugin.getLogger().info("  " + instrument + ": "
                    + OggTools.readSampleRate(bytes) + " Hz");
        }
        if (fetched > 0) {
            changedOnLastRun = true;
            samples.invalidateFiles();
            plugin.getLogger().info("Downloaded " + fetched + " vanilla note block sound(s)"
                    + (replaced > 0 ? ", replacing " + replaced + " outdated cached sample(s)." : "."));
        }
        // Recorded only after a full pass against the index; a failed fetch
        // above throws first, so the next start verifies again.
        if (hasAll()) writeAtomically(cacheDir.resolve(LAYOUT_FILE), (CACHE_LAYOUT + "\n").getBytes(StandardCharsets.UTF_8));
        return countAvailable();
    }

    private static void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path parent = target.getParent();
        if (parent == null || Files.isSymbolicLink(parent)
                || !Files.isDirectory(parent, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Vanilla sound cache must be a real directory");
        }
        Path temp = Files.createTempFile(parent, target.getFileName().toString() + ".", ".tmp");
        try {
            Files.write(temp, bytes, java.nio.file.StandardOpenOption.WRITE);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
        }
    }

    private static String sha1Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    private int countAvailable() {
        int available = 0;
        for (String instrument : ResourcePackService.INSTRUMENT_NAMES) {
            if (get(instrument) != null) available++;
        }
        return available;
    }

    private JsonObject fetchAssetIndex(String version) throws IOException, InterruptedException {
        byte[] manifestBytes = fetch(VERSION_MANIFEST);
        if (manifestBytes == null) return null;
        JsonObject manifest = JsonParser.parseString(new String(manifestBytes, StandardCharsets.UTF_8)).getAsJsonObject();

        String versionUrl = null;
        for (var element : manifest.getAsJsonArray("versions")) {
            JsonObject entry = element.getAsJsonObject();
            if (version.equals(entry.get("id").getAsString())) {
                versionUrl = entry.get("url").getAsString();
                break;
            }
        }
        if (versionUrl == null) {
            plugin.getLogger().warning("Minecraft version " + version
                    + " not found in Mojang's manifest; skipping vanilla sound download.");
            return null;
        }

        byte[] versionBytes = fetch(versionUrl);
        if (versionBytes == null) return null;
        JsonObject versionJson = JsonParser
                .parseString(new String(versionBytes, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject assetIndexNode = versionJson.getAsJsonObject("assetIndex");
        if (assetIndexNode == null || !assetIndexNode.has("url")) {
            plugin.getLogger().warning("Mojang's version metadata has no assetIndex URL; "
                    + "skipping vanilla sound download.");
            return null;
        }
        String indexUrl = assetIndexNode.get("url").getAsString();

        byte[] indexBytes = fetch(indexUrl);
        return indexBytes == null ? null
                : JsonParser.parseString(new String(indexBytes, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private byte[] fetch(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "NoteblockWorldMC-Plugin")
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = bounded.send(request, MAX_FETCH_BYTES, Duration.ofSeconds(30)).get();
            if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
            return response.body();
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IOException("Asset fetch failed", e.getCause());
        }
    }
}
