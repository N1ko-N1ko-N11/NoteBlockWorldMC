package io.github.noteblockworldmc.pack;

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
    private static final long MAX_FETCH_BYTES = 64L * 1024 * 1024;

    /**
     * Three instruments' asset files don't match their sound-event names —
     * these are Minecraft's historic filenames (bass drum, ice chime, and
     * the bone-block xylophone). Everything else matches by name.
     */
    private static final Map<String, String> ASSET_ALIASES = Map.of(
            "basedrum", "bd",
            "chime", "icechime",
            "xylophone", "xylobone");

    private final NoteblockWorldMCPlugin plugin;
    private final Path cacheDir;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public VanillaSounds(NoteblockWorldMCPlugin plugin) {
        this.plugin = plugin;
        this.cacheDir = plugin.getDataFolder().toPath().resolve("vanilla-sounds");
    }

    /** Releases the HTTP client's threads (call on plugin disable). */
    public void close() {
        http.close();
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
        Path file = cacheDir.resolve(instrument + ".ogg");
        if (!Files.isRegularFile(file)) return null;
        byte[] magic = new byte[4];
        try (var in = Files.newInputStream(file)) {
            if (in.read(magic) != magic.length || !OggTools.isOgg(magic)) return null;
        } catch (IOException e) {
            return null;
        }
        return file;
    }

    /**
     * Downloads any missing note block sounds. Blocking — call off-thread.
     *
     * @return number of instruments available afterwards
     */
    public int ensureDownloaded() throws IOException, InterruptedException {
        Files.createDirectories(cacheDir);

        int missing = 0;
        for (String instrument : ResourcePackService.INSTRUMENT_NAMES) {
            if (get(instrument) == null) missing++;
        }
        if (missing == 0) return ResourcePackService.INSTRUMENT_NAMES.length;

        String version = Bukkit.getMinecraftVersion();
        plugin.getLogger().info("Fetching vanilla note block sounds for Minecraft "
                + version + " (one-time, cached in " + cacheDir + ")...");

        JsonObject assetIndex = fetchAssetIndex(version);
        if (assetIndex == null) return countAvailable();

        JsonObject objects = assetIndex.getAsJsonObject("objects");
        Map<String, String> hashes = new HashMap<>();
        for (String key : objects.keySet()) {
            if (!key.endsWith(".ogg") || !key.contains("/sounds/")) continue;
            if (!key.toLowerCase(java.util.Locale.ROOT).contains(SOUND_MARKER)) continue;
            String stem = key.substring(key.lastIndexOf('/') + 1, key.length() - 4);
            // First match wins; asset keys are unique per file anyway
            hashes.putIfAbsent(stem, objects.getAsJsonObject(key).get("hash").getAsString());
        }
        if (hashes.isEmpty()) {
            plugin.getLogger().warning("Found no note block sounds in Mojang's asset index. "
                    + "Place the .ogg files in " + cacheDir + " manually instead.");
        } else {
            plugin.getLogger().info("Note block sounds available in Mojang's assets: "
                    + String.join(", ", new java.util.TreeSet<>(hashes.keySet())));
        }

        int fetched = 0;
        for (String instrument : ResourcePackService.INSTRUMENT_NAMES) {
            if (get(instrument) != null) continue;
            String hash = hashes.get(ASSET_ALIASES.getOrDefault(instrument, instrument));
            if (hash == null) {
                // Not every instrument name matches its file stem in every
                // version; the INFO line above lists what actually exists.
                plugin.getLogger().warning("No vanilla sound found for '" + instrument
                        + "'; notes on it will stay within the vanilla octave range.");
                continue;
            }
            byte[] bytes = fetch(ASSET_BASE + hash.substring(0, 2) + "/" + hash);
            if (bytes == null || !OggTools.isOgg(bytes)) continue;
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
            writeAtomically(cacheDir.resolve(instrument + ".ogg"), bytes);
            fetched++;
            // The source rate decides how far up we can shift (see OggTools)
            plugin.getLogger().info("  " + instrument + ": "
                    + OggTools.readSampleRate(bytes) + " Hz");
        }
        plugin.getLogger().info("Downloaded " + fetched + " vanilla note block sound(s).");
        return countAvailable();
    }

    private static void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(temp, bytes);
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
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
        // Streamed with a cap rather than buffered whole: the same
        // late-size-check pattern already fixed in the API client and the URL
        // fetcher. The largest thing here is Mojang's asset index (~1 MB), so
        // 64 MB is generous while still bounding a pathological response.
        HttpResponse<java.io.InputStream> response =
                http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            plugin.getLogger().warning("HTTP " + response.statusCode() + " fetching " + url);
            return null;
        }
        try (var in = response.body()) {
            return readCapped(in, MAX_FETCH_BYTES);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed reading " + url + ": " + e.getMessage());
            return null;
        }
    }

    private static byte[] readCapped(java.io.InputStream in, long maxBytes) throws IOException {
        var out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IOException("response exceeds " + maxBytes + " bytes");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
