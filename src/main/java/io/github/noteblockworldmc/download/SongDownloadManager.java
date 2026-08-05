package io.github.noteblockworldmc.download;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.noteblockworldmc.NoteblockWorldMCPlugin;
import com.google.gson.reflect.TypeToken;
import io.github.noteblockworldmc.api.NoteBlockWorldClient.SongQuery;
import io.github.noteblockworldmc.api.NoteBlockWorldClient.Sort;
import io.github.noteblockworldmc.api.model.SongPage;
import io.github.noteblockworldmc.api.model.SongPreview;
import io.github.noteblockworldmc.nbs.NbsParser;
import io.github.noteblockworldmc.nbs.NbsSong;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads and stores packed songs from Note Block World.
 *
 * Flow: /song/{id}/open (header src: downloadButton) -> signed file URL
 * -> download zip -> extract into plugins/NoteblockWorldMC/songs/{publicId}/
 *
 * Zip layout (fixed by the server's packer): song.nbs at the root,
 * custom instrument audio under sounds/.
 *
 * Everything here runs off the main thread except the constructor;
 * callers are responsible for hopping back before touching Bukkit.
 */
public class SongDownloadManager {

    public static final String NBS_FILE_NAME = "song.nbs";
    private static final String META_FILE_NAME = "meta.json";

    /** Song public IDs are URL-safe; anything else is refused defensively. */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]+");

    /**
     * A noteblock.world song address: optional scheme, a host, then /song/ID.
     * The capture stops at the first character an ID cannot contain, so a
     * trailing slash, query string or #fragment falls away on its own.
     */
    private static final Pattern SONG_LINK = Pattern.compile(
            "(?i)^(?:https?://)?[^/\\s]*/song/([A-Za-z0-9_-]+)");

    private static final int MAX_ZIP_ENTRIES = 256;

    private final NoteblockWorldMCPlugin plugin;
    private final Path songsDir;
    private final Path importDir;
    private final long maxZipBytes;
    private final long maxExtractedBytes;
    private final int maxStoredSongs;
    private final int maxConcurrentDownloads;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /** IDs with a completed download on disk. */
    private final Set<String> downloaded = ConcurrentHashMap.newKeySet();
    /** IDs currently being downloaded (dedup guard). */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    /** IDs whose files are still being deleted; re-download must wait. */
    private final Set<String> pendingDeletion = ConcurrentHashMap.newKeySet();
    /** publicId -> song title, for name lookup and tab completion. */
    private final Map<String, String> titles = new ConcurrentHashMap<>();
    /**
     * publicId -> parsed library entry. Without this, every --downloaded page
     * view re-read every song's meta.json (500 file reads + JSON parses per
     * page flip at the storage cap). Entries are immutable once written;
     * remove() is the only invalidator.
     */
    private final Map<String, LocalEntry> entryCache = new ConcurrentHashMap<>();

    public SongDownloadManager(NoteblockWorldMCPlugin plugin) {
        this.plugin = plugin;
        this.songsDir = plugin.getDataFolder().toPath().resolve("songs");
        this.importDir = plugin.getDataFolder().toPath().resolve("import");
        this.maxZipBytes = plugin.getConfig().getLong("download.max-zip-size-mb", 32) * 1024 * 1024;
        this.maxExtractedBytes = plugin.getConfig().getLong("download.max-extracted-size-mb", 64) * 1024 * 1024;
        this.maxStoredSongs = plugin.getConfig().getInt("download.max-stored-songs", 500);
        this.maxConcurrentDownloads = plugin.getConfig().getInt("download.max-concurrent-downloads", 4);

        try {
            Files.createDirectories(songsDir);
            // Created up front so it's discoverable without running /nbw import first
            Files.createDirectories(importDir);
            try (Stream<Path> dirs = Files.list(songsDir)) {
                dirs.filter(Files::isDirectory)
                        .filter(dir -> Files.exists(dir.resolve(NBS_FILE_NAME)))
                        .forEach(dir -> downloaded.add(dir.getFileName().toString()));
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Could not scan songs directory: " + e);
        }
        plugin.getLogger().info("Found " + downloaded.size() + " downloaded song(s).");
        // Reading every song's meta.json for the title index is one file per
        // song — at the 500-song cap on a slow disk that's a visible stall in
        // onEnable. The IDs (from the directory listing above) are enough for
        // everything to function; titles land moments later and merely make
        // tab-completion and name-lookup prettier. `titles` is concurrent.
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            for (String publicId : downloaded) {
                try {
                    String title = asString(readMeta(songsDir.resolve(publicId)).get("title"));
                    if (title != null && !title.isBlank()) titles.put(publicId, title);
                } catch (Exception ignored) {
                    // damaged meta: name lookup falls back to the ID
                }
            }
        });
    }

    public boolean isDownloaded(String publicId) {
        return downloaded.contains(publicId);
    }

    public boolean isInFlight(String publicId) {
        return inFlight.contains(publicId);
    }

    /** Path to a downloaded song's .nbs file (existence not re-checked). */
    public Path getNbsFile(String publicId) {
        return songsDir.resolve(publicId).resolve(NBS_FILE_NAME);
    }

    /** A downloaded song's directory. */
    public Path getSongDirectory(String publicId) {
        // Defence in depth. Today every caller passes an ID that came out of
        // the library (resolve() only ever returns known entries), so nothing
        // hostile reaches here — but this is a public method that turns a
        // String into a filesystem path, and "../../.." would escape the data
        // folder. Validate rather than rely on callers staying careful.
        if (publicId == null || !SAFE_ID.matcher(publicId).matches()) {
            throw new IllegalArgumentException("Unsafe song id: " + publicId);
        }
        return songsDir.resolve(publicId);
    }

    /** Folder players drop .nbs files into for /nbw import. Created at startup. */
    public Path getImportDirectory() {
        return importDir;
    }

    /** Snapshot of downloaded song IDs. */
    public java.util.Set<String> getDownloadedIds() {
        return java.util.Set.copyOf(downloaded);
    }

    /** Titles of downloaded songs, for tab completion. */
    public java.util.List<String> getTitles() {
        return java.util.List.copyOf(titles.values());
    }

    /** The stored title for a song, or its ID if unknown. */
    public String getTitle(String publicId) {
        return titles.getOrDefault(publicId, publicId);
    }

    /**
     * Resolves a user-typed song reference to public IDs.
     *
     * Tried in order, stopping at the first that yields anything:
     * exact ID, exact title (case-insensitive), then substring of a title.
     * Returns every match so callers can report ambiguity rather than
     * silently acting on the wrong song.
     */
    public java.util.List<String> resolve(String query) {
        if (query == null || query.isBlank()) return java.util.List.of();
        String trimmed = query.trim();

        if (downloaded.contains(trimmed)) return java.util.List.of(trimmed);

        String needle = trimmed.toLowerCase(java.util.Locale.ROOT);
        java.util.List<String> exact = new java.util.ArrayList<>();
        java.util.List<String> partial = new java.util.ArrayList<>();
        for (Map.Entry<String, String> entry : titles.entrySet()) {
            String title = entry.getValue().toLowerCase(java.util.Locale.ROOT);
            if (title.equals(needle)) {
                exact.add(entry.getKey());
            } else if (title.contains(needle)) {
                partial.add(entry.getKey());
            }
        }
        return !exact.isEmpty() ? exact : partial;
    }

    /**
     * Downloads a song's packed zip and extracts it.
     *
     * @param publicId the song's public ID
     * @param preview  optional preview for writing metadata (nullable, e.g.
     *                 when downloading by bare ID from the command)
     * @return future completing with the extracted .nbs path;
     *         completes exceptionally on any failure
     */
    /** Distinguishes local imports (11 chars) from noteblock.world IDs (10). */
    private static final int LOCAL_ID_LENGTH = 11;
    private static final char[] ID_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    /** A random 11-char ID; the extra character guarantees no catalog collision. */
    private String randomLocalId() {
        var random = java.util.concurrent.ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder(LOCAL_ID_LENGTH);
        for (int i = 0; i < LOCAL_ID_LENGTH; i++) {
            sb.append(ID_ALPHABET[random.nextInt(ID_ALPHABET.length)]);
        }
        return sb.toString();
    }

    /**
     * Imports a local .nbs file into the library.
     *
     * The file is parsed (which validates it and derives title/author/length),
     * copied to songs/&lt;newId&gt;/song.nbs, and registered exactly like a
     * download so every downstream feature — library view, play-by-name,
     * analyze, the LRU cache — treats it identically.
     *
     * Custom instruments that reference the composer's OWN audio are not
     * imported (that audio isn't in the file); those notes are skipped by the
     * parser and reported, same as anywhere else. Custom instruments drawn
     * from the standard sound library still resolve normally at pack build.
     *
     * @return the new public ID
     * @throws IOException          if the file can't be read or stored
     * @throws IllegalStateException if the storage cap is reached
     */
    public String importLocal(Path nbsFile) throws IOException {
        // Size-check BEFORE reading: same late-check class fixed in both
        // HTTP download paths — don't load an oversized file just to reject it.
        long size = Files.size(nbsFile);
        if (size > maxZipBytes) {
            throw new IOException("File is too large (" + (size / 1024)
                    + " KB; limit " + (maxZipBytes / 1024) + " KB)");
        }
        return importBytes(Files.readAllBytes(nbsFile), nbsFile.getFileName().toString());
    }

    /**
     * Imports a .nbs from raw bytes (a local file or a fetched URL), with a
     * fallback display name used only when the file's own title is blank.
     */
    public String importBytes(byte[] bytes, String fallbackName) throws IOException {
        return importBytes(bytes, fallbackName, null);
    }

    /**
     * @param overrideName if non-null, becomes the song's registered title
     *                     regardless of what the file's header says; otherwise
     *                     the file's title is used, falling back to fallbackName
     */
    public String importBytes(byte[] bytes, String fallbackName, String overrideName)
            throws IOException {
        if (bytes.length > maxZipBytes) {
            throw new IOException("File is too large (" + (bytes.length / 1024)
                    + " KB; limit " + (maxZipBytes / 1024) + " KB)");
        }

        // Parse first: this validates the format and gives us the metadata.
        NbsSong song;
        try (var in = new java.io.ByteArrayInputStream(bytes)) {
            song = io.github.noteblockworldmc.nbs.NbsParser.parse(in);
        } catch (Exception e) {
            throw new IOException("Not a valid .nbs file: " + e.getMessage(), e);
        }

        // Reserve an ID by ADDING it to the concurrent set before doing any
        // work. That makes the cap check and the claim a single atomic step:
        // two concurrent imports can't both slip past the limit, and no two
        // can pick the same ID. Rolled back on any failure below.
        String publicId = reserveLocalId();
        try {
            Path dir = songsDir.resolve(publicId);
            Files.createDirectories(dir);
            try {
                Files.write(dir.resolve(NBS_FILE_NAME), bytes);
                writeLocalMeta(dir, publicId, song, fallbackName, overrideName);
            } catch (IOException e) {
                deleteRecursivelyQuietly(dir);
                throw e;
            }
            titles.put(publicId, importTitle(song, fallbackName, overrideName, publicId));
            // The ID is visible in `downloaded` from reservation onward — a
            // concurrent --downloaded view could have read (and cached) the
            // entry BEFORE meta.json existed. Drop any such half-read now
            // that the files are final; the next view loads the real thing.
            entryCache.remove(publicId);
            // Local imports carry no packed sounds, so no pack rebuild needed.
            return publicId;
        } catch (RuntimeException | IOException e) {
            downloaded.remove(publicId);   // release the reservation
            throw e;
        }
    }

    /**
     * Atomically claims a fresh local ID, enforcing the storage cap.
     *
     * The claim (downloaded.add) is what makes the cap check race-free:
     * size() is read AFTER the tentative add, so two threads adding at once
     * can't both see room. Whichever pushes over the limit backs its own
     * entry out and fails.
     */
    private String reserveLocalId() {
        for (int attempt = 0; attempt < 1000; attempt++) {
            String id = randomLocalId();
            if (Files.exists(songsDir.resolve(id))) continue;   // extremely unlikely
            if (!downloaded.add(id)) continue;                  // someone else has it
            if (maxStoredSongs > 0 && downloaded.size() > maxStoredSongs) {
                downloaded.remove(id);
                throw new IllegalStateException("The server's song storage limit ("
                        + maxStoredSongs + ") has been reached");
            }
            return id;
        }
        throw new IllegalStateException("Could not allocate a unique song ID");
    }

    /** Fetches an https URL (SSRF-guarded) and imports it. Blocking. */
    public String importUrl(String url, String overrideName) throws IOException {
        byte[] bytes;
        try (SecureUrlFetcher fetcher = new SecureUrlFetcher(maxZipBytes)) {
            bytes = fetcher.fetch(url);
        }
        // A blank override means "use the file's own title, then the URL".
        String override = overrideName != null && !overrideName.isBlank()
                ? overrideName.trim() : null;
        return importBytes(bytes, fallbackNameFromUrl(url), override);
    }

    private static String fallbackNameFromUrl(String url) {
        int slash = url.lastIndexOf('/');
        String tail = slash >= 0 && slash < url.length() - 1 ? url.substring(slash + 1) : url;
        int query = tail.indexOf('?');
        if (query >= 0) tail = tail.substring(0, query);
        try {
            // "My%20Song.nbs" should register as "My Song", not "My%20Song"
            tail = java.net.URLDecoder.decode(tail, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            // malformed escapes: keep the raw tail
        }
        return tail.isBlank() ? "imported" : tail;
    }

    private void writeLocalMeta(Path dir, String publicId, NbsSong song, String fallbackName,
                                String overrideName) throws IOException {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("publicId", publicId);
        meta.put("downloadedAt", Instant.now().toString());
        meta.put("source", "local");
        meta.put("title", importTitle(song, fallbackName, overrideName, publicId));
        meta.put("author", song.getAuthor());
        meta.put("durationSeconds", song.getDurationSeconds());
        meta.put("noteCount", song.getNoteCount());
        meta.put("playCount", 0);
        meta.put("uploader", "Local import");
        Files.writeString(dir.resolve(META_FILE_NAME), gson.toJson(meta), StandardCharsets.UTF_8);
    }

    /**
     * Turns a noteblock.world link into the song ID it points at, leaving
     * anything that is not a link untouched.
     *
     * People copy the address bar rather than hunting for the ID inside it, so
     * "https://noteblock.world/song/s4S4kBoWob" is accepted anywhere a bare
     * "s4S4kBoWob" is.
     *
     * Only the ID is taken from the text. The download still goes to the
     * configured api-base-url, never to the pasted address, so a link naming
     * some other host cannot redirect a fetch anywhere -- it simply yields an
     * ID that the API will or will not recognise.
     *
     * Input that looks like a link but carries no usable ID is returned
     * unchanged, so the caller's normal "song not found" path reports it
     * rather than a second, more confusing error.
     */
    public static String extractSongId(String input) {
        if (input == null) return null;
        String text = input.trim();
        if (text.isEmpty() || text.indexOf('/') < 0) return text;

        var matcher = SONG_LINK.matcher(text);
        return matcher.find() ? matcher.group(1) : text;
    }

    /**
     * Title for an imported song.
     *
     * An explicit override wins; then the title stored inside the .nbs; then
     * the source file name minus its extension. The raw ID is a last resort
     * only — it is meaningless to a human and shows up in tab completion.
     *
     * Shared by the in-memory title index and meta.json on purpose: these
     * were two separate expressions, and they DISAGREED. The index fell back
     * to the ID while meta.json used the file name, so a freshly imported
     * song listed as its ID until the next restart rebuilt the index from
     * meta.json, at which point it silently renamed itself.
     */
    private static String importTitle(NbsSong song, String fallbackName,
                                      String overrideName, String publicId) {
        if (overrideName != null && !overrideName.isBlank()) return overrideName;
        if (!song.getName().isBlank()) return song.getName();
        if (fallbackName != null) {
            String fromFile = stripExtension(fallbackName).trim();
            if (!fromFile.isBlank()) return fromFile;
        }
        return publicId;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    public CompletableFuture<Path> download(String publicId, SongPreview preview) {
        if (!SAFE_ID.matcher(publicId).matches()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Invalid song ID: " + publicId));
        }
        if (downloaded.contains(publicId)) {
            return CompletableFuture.completedFuture(getNbsFile(publicId));
        }
        if (maxStoredSongs > 0 && downloaded.size() + inFlight.size() >= maxStoredSongs) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "The server's song storage limit (" + maxStoredSongs + ") has been reached"));
        }
        if (pendingDeletion.contains(publicId)) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "That song is still being removed; try again in a moment"));
        }
        if (inFlight.size() >= maxConcurrentDownloads) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Too many downloads in progress; try again in a moment"));
        }
        if (!inFlight.add(publicId)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("This song is already being downloaded"));
        }
        // Authoritative cap check, AFTER the inFlight claim: the earlier one
        // is a courtesy fast-path, but two concurrent downloads at cap-1
        // could both pass it (the same check-then-act race the import path
        // fixes with reserveLocalId).
        if (maxStoredSongs > 0 && downloaded.size() + inFlight.size() > maxStoredSongs) {
            inFlight.remove(publicId);
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "The server's song storage limit (" + maxStoredSongs + ") has been reached"));
        }

        CompletableFuture<Path> future = plugin.getApiClient().fetchOpenUrl(publicId)
                .thenCompose(url -> plugin.getApiClient().downloadFile(url, maxZipBytes))
                .thenApply(zipBytes -> {
                    try {
                        return extract(publicId, zipBytes, preview);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });

        // Return the DERIVED future, not the original: CompletableFuture runs
        // dependents in reverse registration order, so a caller chaining onto
        // the original could otherwise start playback before this bookkeeping
        // (and the pack rebuild it triggers) had run.
        CompletableFuture<Path> result = future.whenComplete((path, throwable) -> {
            inFlight.remove(publicId);
            if (throwable == null) {
                downloaded.add(publicId);
                // Index the title for name lookup / tab completion. Bare-ID
                // downloads have no preview, so fall back to the library
                // loader, which reads (and self-heals) meta.json.
                if (preview != null && !preview.getTitle().isBlank()) {
                    titles.put(publicId, preview.getTitle());
                } else {
                    LocalEntry entry = loadLocalEntry(publicId);
                    if (entry != null) titles.put(publicId, entry.preview().getTitle());
                }
                // If this song shipped custom instrument sounds, the
                // resource pack must grow to include them.
                if (hasCustomSounds(publicId)) {
                    plugin.getResourcePackService().onLibraryChanged();
                }
            } else {
                // Don't leave partial extractions on disk
                deleteRecursivelyQuietly(songsDir.resolve(publicId));
            }
        });
        return result;
    }

    // ------------------------------------------------------------------

    /**
     * Removes a downloaded song from the library. Refuses while the song
     * is mid-download. The in-memory set updates immediately (so UIs
     * refresh correctly); file deletion happens off-thread.
     */
    public boolean remove(String publicId) {
        if (inFlight.contains(publicId)) return false;
        if (!downloaded.remove(publicId)) return false;
        titles.remove(publicId);
        entryCache.remove(publicId);
        // Must be checked before the files are swept
        boolean hadCustomSounds = hasCustomSounds(publicId);
        // Guard the window between "removed from the set" and "files gone":
        // a re-download during the async sweep would otherwise extract into
        // a directory that is being deleted underneath it.
        pendingDeletion.add(publicId);
        CompletableFuture.runAsync(() -> {
            try {
                deleteRecursivelyQuietly(songsDir.resolve(publicId));
            } finally {
                pendingDeletion.remove(publicId);
                if (hadCustomSounds) {
                    // Quiet pack rebuild (no re-push): lean pack for new
                    // joiners, zero disruption for online players.
                    plugin.getResourcePackService().onLibraryShrunk();
                }
            }
        });
        return true;
    }

    // ------------------------------------------------------------------
    // Local library ("--downloaded" mode)
    // ------------------------------------------------------------------

    /**
     * Queries the local downloaded library instead of the API.
     * Supports text search over title/author, all sort modes (popular uses
     * the play count recorded at download time), and the same pagination
     * contract as the API. Category/uploader filters are site concepts and
     * do not apply locally. Runs off the main thread.
     */
    public CompletableFuture<SongPage> queryLocal(SongQuery query) {
        return CompletableFuture.supplyAsync(() -> {
            List<LocalEntry> entries = loadLocalEntries();

            // Text filter over title + author
            String q = query.getQ();
            if (q != null && !q.isBlank()) {
                String needle = q.toLowerCase(java.util.Locale.ROOT);
                entries.removeIf(entry ->
                        !entry.preview.getTitle().toLowerCase(java.util.Locale.ROOT).contains(needle)
                        && !entry.preview.getDisplayAuthor().toLowerCase(java.util.Locale.ROOT).contains(needle));
            }

            int total = entries.size();
            Sort sort = query.getSort() != null ? query.getSort() : Sort.RECENT;

            if (sort == Sort.RANDOM) {
                java.util.Collections.shuffle(entries);
            } else {
                java.util.Comparator<LocalEntry> comparator = switch (sort) {
                    case TITLE -> java.util.Comparator.comparing(
                            entry -> entry.preview.getTitle().toLowerCase(java.util.Locale.ROOT));
                    case DURATION -> java.util.Comparator.comparingDouble(
                            entry -> entry.preview.getDuration());
                    case NOTE_COUNT -> java.util.Comparator.comparingInt(
                            entry -> entry.preview.getNoteCount());
                    case PLAY_COUNT -> java.util.Comparator.comparingInt(
                            entry -> entry.preview.getPlayCount());
                    default -> java.util.Comparator.comparing(entry -> entry.downloadedAt);
                };
                if (!query.isEffectivelyAscending()) {
                    comparator = comparator.reversed();
                }
                entries.sort(comparator);
            }

            int limit = Math.max(1, query.getLimit());
            int page = Math.max(1, query.getPage());
            int from = Math.min(entries.size(), (page - 1) * limit);
            int to = Math.min(entries.size(), from + limit);

            List<SongPreview> slice = new java.util.ArrayList<>();
            for (LocalEntry entry : entries.subList(from, to)) {
                slice.add(entry.preview);
            }
            if (sort == Sort.RANDOM) {
                // Match the API's random contract: one unordered batch
                return SongPage.of(slice, 1, limit, slice.size());
            }
            return SongPage.of(slice, page, limit, total);
        });
    }

    private record LocalEntry(SongPreview preview, java.time.Instant downloadedAt) {}

    private List<LocalEntry> loadLocalEntries() {
        List<LocalEntry> entries = new java.util.ArrayList<>();
        for (String publicId : downloaded) {
            LocalEntry entry = loadLocalEntry(publicId);
            if (entry != null) entries.add(entry);
        }
        return entries;
    }

    private LocalEntry loadLocalEntry(String publicId) {
        LocalEntry cached = entryCache.get(publicId);
        if (cached != null) return cached;
        LocalEntry loaded = readLocalEntry(publicId);
        if (loaded != null) entryCache.put(publicId, loaded);
        return loaded;
    }

    private LocalEntry readLocalEntry(String publicId) {
        Path dir = songsDir.resolve(publicId);
        Map<String, Object> meta = readMeta(dir);

        String title = asString(meta.get("title"));
        String author = asString(meta.get("author"));

        // Self-heal: bare-ID downloads have no title in meta.json.
        // Parse the NBS header once and write the repaired meta back.
        if (title == null || title.isBlank()) {
            try {
                NbsSong song = NbsParser.parse(dir.resolve(NBS_FILE_NAME));
                title = song.getName().isBlank() ? publicId : song.getName();
                author = (author == null || author.isBlank()) ? song.getAuthor() : author;
                meta.put("title", title);
                meta.put("author", author);
                meta.put("durationSeconds", song.getDurationSeconds());
                meta.putIfAbsent("publicId", publicId);
                try {
                    Files.writeString(dir.resolve(META_FILE_NAME), gson.toJson(meta),
                            StandardCharsets.UTF_8);
                } catch (IOException ignored) {
                    // Healing is best-effort; we can still show this entry
                }
            } catch (IOException e) {
                title = publicId;
            }
        }

        java.time.Instant downloadedAt;
        try {
            downloadedAt = java.time.Instant.parse(asString(meta.get("downloadedAt")));
        } catch (Exception e) {
            downloadedAt = java.time.Instant.EPOCH;
        }

        return new LocalEntry(SongPreview.local(
                publicId,
                title,
                author == null ? "" : author,
                asDouble(meta.get("durationSeconds")),
                (int) asDouble(meta.get("noteCount")),
                (int) asDouble(meta.get("playCount")),
                asString(meta.get("uploader"))), downloadedAt);
    }

    private Map<String, Object> readMeta(Path dir) {
        Path metaFile = dir.resolve(META_FILE_NAME);
        if (Files.exists(metaFile)) {
            try {
                Map<String, Object> meta = gson.fromJson(
                        Files.readString(metaFile, StandardCharsets.UTF_8),
                        new TypeToken<Map<String, Object>>() {}.getType());
                if (meta != null) return new LinkedHashMap<>(meta);
            } catch (Exception e) {
                plugin.getLogger().warning("Unreadable " + metaFile + ": " + e);
            }
        }
        return new LinkedHashMap<>();
    }

    private static String asString(Object value) {
        return value instanceof String s ? s : null;
    }

    private static double asDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0;
    }

    private Path extract(String publicId, byte[] zipBytes, SongPreview preview) throws IOException {
        Path dir = songsDir.resolve(publicId);
        Files.createDirectories(dir);

        long totalExtracted = 0;
        int entryCount = 0;
        byte[] buffer = new byte[8192];

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entryCount > MAX_ZIP_ENTRIES) {
                    throw new IOException("Zip has too many entries");
                }

                // Zip-slip protection: entry must resolve inside the song dir
                Path target = dir.resolve(entry.getName()).normalize();
                if (!target.startsWith(dir)) {
                    throw new IOException("Blocked unsafe zip path: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());

                try (OutputStream out = Files.newOutputStream(target)) {
                    int read;
                    while ((read = zip.read(buffer)) > 0) {
                        totalExtracted += read;
                        if (totalExtracted > maxExtractedBytes) {
                            throw new IOException("Zip contents exceed the extraction size limit");
                        }
                        out.write(buffer, 0, read);
                    }
                }
            }
        }

        Path nbsFile = dir.resolve(NBS_FILE_NAME);
        if (!Files.exists(nbsFile)) {
            throw new IOException("Downloaded zip did not contain " + NBS_FILE_NAME);
        }

        writeMeta(dir, publicId, preview, nbsFile);
        return nbsFile;
    }

    /**
     * noteblock.world names packed sounds by Mojang asset hash with NO
     * file extension, so an "*.ogg" filter finds nothing. Sniff the OggS
     * magic bytes instead.
     */
    private boolean hasCustomSounds(String publicId) {
        Path soundsDir = songsDir.resolve(publicId).resolve("sounds");
        if (!Files.isDirectory(soundsDir)) return false;
        try (Stream<Path> walk = Files.walk(soundsDir)) {
            return walk.filter(Files::isRegularFile).anyMatch(file -> {
                try (var in = Files.newInputStream(file)) {
                    byte[] magic = in.readNBytes(4);
                    return magic.length == 4 && magic[0] == 'O' && magic[1] == 'g'
                            && magic[2] == 'g' && magic[3] == 'S';
                } catch (IOException e) {
                    return false;
                }
            });
        } catch (IOException e) {
            return false;
        }
    }

    private void deleteRecursivelyQuietly(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException e) {
            plugin.getLogger().warning("Could not clean up partial download " + dir + ": " + e);
        }
    }

    /**
     * Records what we know about a downloaded song.
     *
     * A preview is only available when the download started from a search
     * result or the GUI. Downloading by raw ID -- "/nbw download aBcD123456"
     * -- has none, and this used to write nothing but the ID, leaving the
     * library entry with no title, 0:00 duration and 0 notes. The .nbs itself
     * knows most of that, so it is read as a fallback rather than fetching
     * more from the API.
     */
    private void writeMeta(Path dir, String publicId, SongPreview preview, Path nbsFile) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("publicId", publicId);
        meta.put("downloadedAt", Instant.now().toString());
        meta.put("source", "noteblock.world");
        if (preview != null) {
            meta.put("title", preview.getTitle());
            meta.put("author", preview.getDisplayAuthor());
            meta.put("durationSeconds", preview.getDuration());
            meta.put("noteCount", preview.getNoteCount());
            meta.put("playCount", preview.getPlayCount());
            meta.put("uploader", preview.getUploaderName());
        } else {
            try (InputStream in = Files.newInputStream(nbsFile)) {
                NbsSong song = NbsParser.parse(in);
                String title = song.getName().isBlank() ? publicId : song.getName();
                meta.put("title", title);
                meta.put("author", song.getAuthor());
                meta.put("durationSeconds", song.getDurationSeconds());
                meta.put("noteCount", song.getNoteCount());
                // Play count is a noteblock.world statistic; nothing in the
                // file knows it, and inventing a number would be worse than 0.
                meta.put("playCount", 0);
                meta.put("uploader", "");
            } catch (IOException | RuntimeException e) {
                plugin.getLogger().warning("Could not read metadata from " + publicId
                        + "'s song file: " + e);
                meta.put("title", publicId);
            }
        }
        try {
            Files.writeString(dir.resolve(META_FILE_NAME), gson.toJson(meta), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Metadata is nice-to-have; the song itself downloaded fine
            plugin.getLogger().warning("Could not write " + META_FILE_NAME
                    + " for " + publicId + ": " + e);
        }
    }
}
