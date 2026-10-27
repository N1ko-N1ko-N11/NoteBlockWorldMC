package io.github.noteblockworldmc.download;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.noteblockworldmc.pack.VanillaSounds;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,80}");

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
    private volatile long maxZipBytes;
    private volatile long maxExtractedBytes;
    private volatile int maxStoredSongs;
    private volatile int maxConcurrentDownloads;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /** IDs with a completed download on disk. */
    private volatile Set<String> downloaded = ConcurrentHashMap.newKeySet();
    /** IDs currently being downloaded (dedup guard). */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    /** Catalog downloads in progress, so a second request for the same song waits for the first. */
    private final Map<String, CompletableFuture<Path>> activeDownloads = new ConcurrentHashMap<>();
    /** IDs whose files are still being deleted; re-download must wait. */
    private final Set<String> pendingDeletion = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicBoolean trashCleanupPending = new java.util.concurrent.atomic.AtomicBoolean();
    private org.bukkit.scheduler.BukkitTask trashCleanupTask;
    private final Object recoveryLock = new Object();
    /** Paths owned by this running instance; recovery must never claim them. */
    private final Set<Path> activeImportPaths = new HashSet<>();
    /** publicId -> song title, for name lookup and tab completion. */
    private volatile Map<String, String> titles = new ConcurrentHashMap<>();
    /**
     * publicId -> parsed library entry. Without this, every --downloaded page
     * view re-read every song's meta.json (500 file reads + JSON parses per
     * page flip at the storage cap). Entries are immutable once written;
     * Removal and import completion invalidate stale entries.
     */
    private final Map<String, LocalEntry> entryCache = new ConcurrentHashMap<>();
    /** Invalidates the asynchronous title index and local-entry loads at reload boundaries. */
    private long libraryGeneration;
    private volatile boolean startupTitlesPending = true;

    /**
     * Result of a local-library rescan. The sets are immutable snapshots so a
     * caller can safely decide whether a resource-pack rebuild is needed
     * without holding the download-manager lock.
     */
    public record LibraryReconciliation(Set<String> removed, Set<String> added,
                                        boolean successful) {
        public boolean changed() { return !removed.isEmpty() || !added.isEmpty(); }
    }

    public SongDownloadManager(NoteblockWorldMCPlugin plugin) {
        this.plugin = plugin;
        this.songsDir = plugin.getDataFolder().toPath().resolve("songs");
        this.importDir = plugin.getDataFolder().toPath().resolve("import");
        loadHotSettings();

        if (Files.isSymbolicLink(songsDir) || Files.isSymbolicLink(importDir)) {
            throw new IllegalStateException("Songs and import folders must not be symbolic links");
        }

        try {
            Files.createDirectories(songsDir);
            // Created up front so it's discoverable without running /nbw import first
            Files.createDirectories(importDir);
            if (!Files.isDirectory(songsDir, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    || !Files.isDirectory(importDir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Songs and import folders must be real directories");
            }
            try (Stream<Path> dirs = Files.list(songsDir)) {
                dirs.filter(dir -> Files.isDirectory(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                        .filter(dir -> SAFE_ID.matcher(dir.getFileName().toString()).matches())
                        .filter(dir -> Files.isRegularFile(dir.resolve(NBS_FILE_NAME), java.nio.file.LinkOption.NOFOLLOW_LINKS))
                        .forEach(dir -> downloaded.add(dir.getFileName().toString()));
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Could not scan songs directory: " + e);
        }
        plugin.getLogger().info("Found " + downloaded.size() + " downloaded song(s).");
        // Recover abandoned removals from previous runs and retry failures at
        // a bounded cadence. Disk traversal always runs on the shared workers.
        trashCleanupTask = org.bukkit.Bukkit.getScheduler().runTaskTimer(plugin,
                this::requestTrashCleanup, 1200L, 1200L);
        requestTrashCleanup();
        // The maintenance worker reads title metadata off-thread. It retries a
        // stale snapshot immediately, or on its next sweep under sustained
        // mutations/worker saturation. ID-based lookup is already available.
    }

    private void indexStartupTitles() {
        for (int attempt = 0; attempt < 3 && startupTitlesPending; attempt++) {
            final long generation;
            final Set<String> ids;
            synchronized (this) {
                generation = libraryGeneration;
                ids = Set.copyOf(downloaded);
            }
            Map<String, String> found = new java.util.HashMap<>();
            for (String publicId : ids) {
                if (plugin.isStopping() || Thread.currentThread().isInterrupted()) return;
                try {
                    String title = asString(readMeta(songsDir.resolve(publicId)).get("title"));
                    if (title != null && !title.isBlank()) {
                        found.put(publicId, io.github.noteblockworldmc.util.Limits.display(title));
                    }
                } catch (Exception ignored) {
                    // damaged meta: name lookup falls back to the ID
                }
            }
            synchronized (this) {
                if (!startupTitlesPending) return;
                if (generation != libraryGeneration) continue;
                found.forEach(titles::putIfAbsent);
                startupTitlesPending = false;
            }
        }
    }

    public boolean isDownloaded(String publicId) {
        return publicId != null && downloaded.contains(publicId) && hasStoredSong(publicId);
    }

    /** Applies new limits while retaining the library and in-flight bookkeeping. */
    public void loadHotSettings() {
        var config = plugin.getConfig();
        long zip = Math.multiplyExact(Math.max(1, config.getLong("download.max-zip-size-mb", 32)), 1024L * 1024);
        long extracted = Math.multiplyExact(Math.max(1, config.getLong("download.max-extracted-size-mb", 64)), 1024L * 1024);
        maxZipBytes = zip;
        maxExtractedBytes = extracted;
        maxStoredSongs = Math.max(0, config.getInt("download.max-stored-songs", 500));
        maxConcurrentDownloads = Math.max(1, config.getInt("download.max-concurrent-downloads", 4));
    }

    /**
     * Reconciles the in-memory local-library index with the files on disk.
     *
     * The manager is deliberately retained across /nbw reload, so the startup
     * directory scan is not enough: an administrator can remove or replace a
     * song while the server is running. Disk reads run on a worker without the
     * manager lock. Publication checks the library generation and retries if
     * an import/removal changed the index while the scan was in progress. The
     * title and local-page caches are rebuilt, and every parsed-song cache entry
     * is invalidated because an existing song file may have been replaced, not
     * merely deleted.
     *
     * A failed scan leaves the previous index untouched. That is safer than
     * treating a transient I/O error as proof that every song disappeared.
     */
    public LibraryReconciliation reconcileLibrary() {
        requestTrashCleanup();
        // Bounded retries keep a busy library from monopolizing a worker forever.
        for (int attempt = 0; attempt < 3; attempt++) {
            if (plugin.isStopping() || Thread.currentThread().isInterrupted()) break;
            LibraryReconciliation result = reconcileSnapshot();
            if (result != null) return result;
        }
        plugin.getLogger().warning("Songs changed during reload; retry /nbw reload once imports/removals settle.");
        return new LibraryReconciliation(Set.of(), Set.of(), false);
    }

    /** Null means the scan was superseded and must be retried. No disk I/O under the manager lock. */
    private LibraryReconciliation reconcileSnapshot() {
        Set<String> removed;
        Set<String> added;
        Set<String> invalidate;
        int currentCount;

        try {
            final long generation;
            final Set<String> previous;
            synchronized (this) {
                generation = libraryGeneration;
                previous = new HashSet<>(downloaded);
            }
            Set<String> present = scanSongIds();
            Map<String, String> refreshedTitles = new java.util.HashMap<>();
            for (String publicId : present) {
                if (plugin.isStopping() || Thread.currentThread().isInterrupted())
                    throw new java.io.InterruptedIOException("Library scan cancelled");
                String title = asString(readMeta(songsDir.resolve(publicId)).get("title"));
                if (title != null && !title.isBlank())
                    refreshedTitles.put(publicId, io.github.noteblockworldmc.util.Limits.display(title));
            }
            removed = new HashSet<>(previous); removed.removeAll(present);
            added = new HashSet<>(present); added.removeAll(previous);
            invalidate = new HashSet<>(previous); invalidate.addAll(present);
            Set<String> refreshedIds = ConcurrentHashMap.newKeySet(); refreshedIds.addAll(present);
            Map<String, String> publishedTitles = new ConcurrentHashMap<>(refreshedTitles);
            synchronized (this) {
                if (generation != libraryGeneration) return null;
                if (plugin.isStopping()) return new LibraryReconciliation(Set.of(), Set.of(), false);
                libraryGeneration++;
                // Readers see a whole ID/title index, never an empty clear/add interval.
                downloaded = refreshedIds;
                // A reload is an explicit cache boundary. Re-read metadata so
                // changed titles and timestamps cannot remain hidden behind a
                // LocalEntry from before the reload.
                titles = publishedTitles;
                startupTitlesPending = false;
                entryCache.clear();
                currentCount = present.size();
            }
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().warning("Could not reconcile songs directory; keeping the previous library index: " + e);
            return new LibraryReconciliation(Set.of(), Set.of(), false);
        }

        // Do this outside the manager lock: invalidation can cancel a parser
        // completion and must not hold up a concurrent import unnecessarily.
        var playback = plugin.getPlaybackManager();
        if (playback != null) {
            for (String publicId : invalidate) playback.invalidateCache(publicId);
        }
        if (!removed.isEmpty()) {
            plugin.getLogger().info("Local song library removed " + removed.size()
                    + " missing song(s) during reload.");
        }
        if (!added.isEmpty()) {
            plugin.getLogger().info("Local song library discovered " + added.size()
                    + " song(s) on disk during reload.");
        }
        plugin.getLogger().info("Rechecked " + currentCount + " local song(s); cleared local song caches.");
        return new LibraryReconciliation(Set.copyOf(removed), Set.copyOf(added), true);
    }

    /** Returns IDs backed by a real directory containing a regular song.nbs. */
    private Set<String> scanSongIds() throws IOException {
        if (Files.isSymbolicLink(songsDir)) {
            throw new IOException("Songs folder must be a real directory");
        }
        // The whole data folder may have been pruned while the server was
        // stopped. Recreate a missing root and treat it as an empty library;
        // preserve the previous index only for a genuine I/O or type error.
        if (!Files.exists(songsDir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(songsDir);
        }
        if (!Files.isDirectory(songsDir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Songs folder must be a real directory");
        }
        try (Stream<Path> dirs = Files.list(songsDir)) {
            return dirs.filter(dir -> Files.isDirectory(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .filter(dir -> SAFE_ID.matcher(dir.getFileName().toString()).matches())
                    .filter(dir -> Files.isRegularFile(dir.resolve(NBS_FILE_NAME),
                            java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .filter(dir -> !pendingDeletion.contains(dir.getFileName().toString()))
                    .map(dir -> dir.getFileName().toString())
                    .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        }
    }

    /** Selects a named top-level import, or all imports when no name is given. Off-thread. */
    public List<Path> findImportFiles(String name) throws IOException {
        return ImportFiles.select(importDir, name);
    }

    public boolean isInFlight(String publicId) {
        return publicId != null && inFlight.contains(publicId);
    }

    /** Path to a downloaded song's .nbs file (existence not re-checked). */
    public Path getNbsFile(String publicId) {
        return getSongDirectory(publicId).resolve(NBS_FILE_NAME);
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
        Path directory = songsDir.resolve(publicId);
        if (Files.isSymbolicLink(directory)) throw new IllegalArgumentException("Song directory must not be a symlink");
        return directory;
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
        return publicId == null ? "" : titles.getOrDefault(publicId, publicId);
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

        if (downloaded.contains(trimmed) && hasStoredSong(trimmed)) return java.util.List.of(trimmed);

        String needle = trimmed.toLowerCase(java.util.Locale.ROOT);
        java.util.List<String> exact = new java.util.ArrayList<>();
        java.util.List<String> partial = new java.util.ArrayList<>();
        for (Map.Entry<String, String> entry : titles.entrySet()) {
            if (!downloaded.contains(entry.getKey()) || !hasStoredSong(entry.getKey())) continue;
            String title = entry.getValue().toLowerCase(java.util.Locale.ROOT);
            if (title.equals(needle)) {
                exact.add(entry.getKey());
            } else if (title.contains(needle)) {
                partial.add(entry.getKey());
            }
        }
        return !exact.isEmpty() ? exact : partial;
    }

    /** Keeps generated local IDs distinct from catalog IDs. */
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
     * NBS contains custom instrument references, not audio. Their notes remain
     * in the parsed song; optional Minecraft-asset recovery can supply samples,
     * otherwise playback reports their unavailability.
     *
     * @return the new public ID
     * @throws IOException          if the file can't be read or stored
     * @throws IllegalStateException if the storage cap is reached
     */
    public String importLocal(Path nbsFile) throws IOException {
        if (nbsFile == null) throw new IOException("No local .nbs file was supplied");
        if (Files.isSymbolicLink(importDir)) {
            throw new IOException("Import folder must not be a symbolic link");
        }
        Path source = nbsFile.toAbsolutePath().normalize();
        if (!source.getParent().equals(importDir.toAbsolutePath().normalize())
                || !Files.isRegularFile(source, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Choose a regular file directly inside the import folder");
        String id = reserveLocalId();
        Path claim = importDir.resolve(".nbw-claim-" + java.util.UUID.randomUUID());
        boolean claimed = false, committed = false;
        try {
            synchronized (recoveryLock) {
                activeImportPaths.add(claim);
                Files.move(source, claim); claimed = true; // only one invocation can claim this input
            }
            byte[] bytes = io.github.noteblockworldmc.util.Limits.read(claim, maxZipBytes);
            importReserved(id, bytes, source.getFileName().toString(), null); committed = true;
            try { Files.delete(claim); }
            catch (IOException e) { plugin.getLogger().warning("Imported " + id + ", but could not clean up " + claim + ": " + e); }
            return id;
        } finally {
            release(id);
            if (claimed && !committed) {
                try { Files.move(claim, source); }
                catch (IOException e) { plugin.getLogger().warning("Failed import remains at " + claim + ": " + e); }
            }
            synchronized (recoveryLock) { activeImportPaths.remove(claim); }
        }
    }

    public String importBytes(byte[] bytes, String fallbackName) throws IOException {
        return importBytes(bytes, fallbackName, null);
    }
    public String importBytes(byte[] bytes, String fallbackName, String overrideName) throws IOException {
        if (bytes == null) throw new IOException("No song data was supplied");
        String id = reserveLocalId();
        try { return importReserved(id, bytes, fallbackName, overrideName); }
        finally { release(id); }
    }
    private String importReserved(String id, byte[] bytes, String fallback, String override) throws IOException {
        if (bytes.length > maxZipBytes) throw new IOException("File exceeds size limit");
        NbsSong song;
        try { song = NbsParser.parse(new ByteArrayInputStream(bytes)); }
        catch (Exception e) { throw new IOException("Not a valid .nbs file: " + e.getMessage(), e); }
        Path stage = newStage();
        try {
            Files.write(stage.resolve(NBS_FILE_NAME), bytes);
            writeLocalMeta(stage, id, song, fallback, override);
            recoverVanillaCustomSounds(stage, id, song);
            commit(stage, id, importTitle(song, fallback, override, id));
        } finally { cleanupStage(stage); }
        try {
            if (hasCustomSounds(id)) plugin.getResourcePackService().onLibraryChanged();
        } catch (RuntimeException e) {
            // The song is already committed. A rebuild notification is
            // best-effort and must never make a successful import look like a
            // failure (which would make importLocal try to restore a file
            // that is now safely present in the library).
            plugin.getLogger().warning("Imported " + id + ", but could not refresh the resource pack: " + e);
        }
        return id;
    }

    /** One lock covers checking all reservations, claiming, and publishing a completed song. */
    private synchronized void reserve(String id) {
        if (plugin.isStopping()) throw new IllegalStateException("Plugin is stopping");
        if (downloaded.contains(id) && !hasStoredSong(id)) {
            // A file may have been removed behind the running server. Drop
            // that stale index entry before admission; /nbw reload performs a
            // full reconciliation, but a fresh download should not be blocked
            // by an already-missing song in the meantime.
            downloaded.remove(id);
            titles.remove(id);
            entryCache.remove(id);
            libraryGeneration++;
            var playback = plugin.getPlaybackManager();
            if (playback != null) playback.invalidateCache(id);
        }
        if (pendingDeletion.contains(id) || inFlight.contains(id) || downloaded.contains(id))
            throw new IllegalStateException("This song is already stored or being changed");
        if (maxStoredSongs > 0 && downloaded.size() + inFlight.size() >= maxStoredSongs)
            throw new IllegalStateException("The server's song storage limit (" + maxStoredSongs + ") has been reached");
        if (inFlight.size() >= maxConcurrentDownloads)
            throw new IllegalStateException("Too many imports/downloads in progress; try again shortly");
        Path destination = getSongDirectory(id);
        if (Files.exists(destination, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            if (!isOrphanDirectory(destination))
                throw new IllegalStateException("This song is already stored or being changed");
            // Preserve leftover audio/metadata for recovery while freeing the
            // ID for a new staged download. Never overwrite a valid song.
            Path recovery = songsDir.resolve(".orphaned");
            try {
                if (Files.isSymbolicLink(songsDir) || Files.isSymbolicLink(recovery))
                    throw new IOException("Recovery folder must not be a symlink");
                Files.createDirectories(recovery);
                Path backup = recovery.resolve(id + "-" + java.util.UUID.randomUUID());
                Files.move(destination, backup);
                plugin.getLogger().info("Preserved incomplete song folder in " + backup);
            } catch (IOException e) { throw new UncheckedIOException("Could not preserve incomplete song folder", e); }
        }
        inFlight.add(id);
    }
    private boolean isOrphanDirectory(Path directory) {
        return !Files.isSymbolicLink(songsDir)
                && Files.isDirectory(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                && !Files.exists(directory.resolve(NBS_FILE_NAME), java.nio.file.LinkOption.NOFOLLOW_LINKS);
    }
    /** Exact-ID recovery only; incomplete folders never appear as playable songs. */
    public boolean isOrphan(String id) {
        if (id == null || !SAFE_ID.matcher(id).matches()) return false;
        try { return isOrphanDirectory(getSongDirectory(id)); }
        catch (RuntimeException e) { return false; }
    }
    private synchronized String reserveLocalId() {
        for (int attempt = 0; attempt < 1000; attempt++) {
            String id = randomLocalId();
            if (Files.exists(songsDir.resolve(id)) || inFlight.contains(id) || pendingDeletion.contains(id)) continue;
            reserve(id); return id;
        }
        throw new IllegalStateException("Could not allocate a unique song ID");
    }
    private synchronized void release(String id) { inFlight.remove(id); }
    private Path newStage() throws IOException {
        if (Files.isSymbolicLink(songsDir)
                || !Files.isDirectory(songsDir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Songs folder must be a real directory");
        }
        Path root = songsDir.resolve(".staging");
        if (Files.isSymbolicLink(root)) throw new IOException("Songs staging folder must not be a symbolic link");
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Songs staging folder must be a real directory");
        }
        synchronized (recoveryLock) {
            Path stage = Files.createTempDirectory(root, "song-");
            activeImportPaths.add(stage);
            return stage;
        }
    }
    private synchronized void commit(Path stage, String id, String title) throws IOException {
        if (plugin.isStopping() || !inFlight.contains(id)) throw new IOException("Import/download cancelled");
        Path destination = getSongDirectory(id);
        try { Files.move(stage, destination, java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(stage, destination); }
        titles.put(id, io.github.noteblockworldmc.util.Limits.display(title));
        downloaded.add(id); inFlight.remove(id); entryCache.remove(id); libraryGeneration++;
    }

    /** Reservation precedes the network request, so URL imports share the same admission limits. */
    public String importUrl(String url, String overrideName) throws IOException {
        if (url == null || url.isBlank()) throw new IOException("A download URL is required");
        String id = reserveLocalId();
        try (SecureUrlFetcher fetcher = new SecureUrlFetcher(maxZipBytes)) {
            return importReserved(id, fetcher.fetch(url), fallbackNameFromUrl(url),
                    overrideName != null && !overrideName.isBlank() ? overrideName.trim() : null);
        } finally { release(id); }
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
     * Tries to supply an imported song's custom instruments from Minecraft's
     * own sounds.
     *
     * A .nbs names its custom instruments but cannot contain them, so an
     * import arrives with the names and no audio and those notes fall silent.
     * A download from noteblock.world does not have this problem because it
     * ships a zip with a sounds/ folder -- and because that site only accepts
     * instruments that already exist in Minecraft.
     *
     * That last point is what makes this recoverable at all: if a declared
     * path names a real Minecraft sound, the audio can be fetched from
     * Mojang's CDN, exactly as the octave variants already are. Anything that
     * is NOT a Minecraft sound (someone's own recording) cannot be recovered
     * by any means, and is reported instead of silently ignored.
     *
     * Off by default: it reaches out to the network during an import, and an
     * operator should choose that rather than discover it.
     */
    private void recoverVanillaCustomSounds(Path dir, String publicId, NbsSong song) {
        if (!plugin.getConfig().getBoolean(
                "resource-pack.recover-vanilla-custom-sounds", false)) {
            return;
        }
        List<String> declared = song.getCustomSoundFiles();
        if (declared.isEmpty()) return;

        var vanilla = plugin.getResourcePackService().getVanillaSounds();
        if (vanilla == null) return;

        Path soundsDir = dir.resolve("sounds");
        final Path soundsRoot;
        try {
            if (Files.isSymbolicLink(soundsDir)) {
                plugin.getLogger().warning("Refusing symbolic-link sounds directory for " + publicId);
                return;
            }
            Files.createDirectories(soundsDir);
            if (Files.isSymbolicLink(soundsDir)) {
                plugin.getLogger().warning("Refusing symbolic-link sounds directory for " + publicId);
                return;
            }
            soundsRoot = soundsDir.toRealPath();
        } catch (IOException e) {
            plugin.getLogger().warning("Could not create custom sounds directory for " + publicId + ": " + e);
            return;
        }
        List<String> recovered = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        // A song may name the same file for several instruments; fetching it
        // once per mention would be pure waste.
        Set<String> seen = new HashSet<>();

        long recoveredBytes = 0;
        for (String rawPath : declared) {
            if (rawPath == null || rawPath.isBlank()) continue;
            // The same canonical form the lookup uses, so a path that
            // resolves is also a path we can write.
            String declaredPath = VanillaSounds.normalizeDeclaredPath(rawPath);
            if (declaredPath.isBlank() || declaredPath.length() > 512 || !seen.add(declaredPath)) continue;

            try {
                byte[] audio = vanilla.resolveCustomSound(declaredPath);
                if (audio == null || audio.length > maxExtractedBytes - recoveredBytes
                        || !io.github.noteblockworldmc.pack.OggTools.isVorbis(audio)) {
                    unavailable.add(declaredPath);
                    continue;
                }
                // Written under the path the song asks for, so the pack
                // builder finds it exactly as it would a shipped sound.
                Path target = soundsRoot.resolve(declaredPath).normalize();
                // Contained to sounds/, NOT merely to the song folder: the
                // looser check let "../song.nbs" through, which would have
                // overwritten the song itself with audio. Declared paths come
                // from an arbitrary .nbs, so they are untrusted input.
                if (!target.startsWith(soundsRoot) || hasSymlinkComponent(soundsRoot, target)) {
                    plugin.getLogger().warning("Refusing unsafe custom sound path in "
                            + publicId + ": " + safeForLog(declaredPath));
                    unavailable.add(declaredPath);
                    continue;
                }
                Files.createDirectories(target.getParent());
                if (hasSymlinkComponent(soundsRoot, target) || Files.isSymbolicLink(target)) {
                    throw new IOException("Refusing symbolic-link custom sound path");
                }
                Files.write(target, audio, java.nio.file.StandardOpenOption.CREATE_NEW);
                recoveredBytes += audio.length;
                recovered.add(declaredPath);
            } catch (IOException | RuntimeException e) {
                plugin.getLogger().warning("Could not recover '" + safeForLog(declaredPath)
                        + "' for " + publicId + ": " + e);
                unavailable.add(declaredPath);
            }
        }

        if (!recovered.isEmpty()) {
            plugin.getLogger().info("Recovered " + recovered.size()
                    + " custom instrument sound(s) from Minecraft for " + publicId + ".");
        }
        if (!unavailable.isEmpty()) {
            // Named, not counted: the admin can then decide whether to supply
            // the files by hand.
            plugin.getLogger().warning(publicId + " uses custom instrument(s) that are not "
                    + "Minecraft sounds and could not be recovered: "
                    + unavailable.stream().map(SongDownloadManager::safeForLog)
                            .collect(java.util.stream.Collectors.joining(", "))
                    + ". Those notes will be silent unless you place the .ogg files in "
                    + dir.resolve("sounds") + ".");
        }
    }

    /** Rejects symlinked path components in a freshly-built staging tree. */
    private static boolean hasSymlinkComponent(Path root, Path candidate) {
        if (!candidate.startsWith(root) || Files.isSymbolicLink(root)) return true;
        Path current = root;
        for (Path part : root.relativize(candidate)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) return true;
        }
        return false;
    }

    /**
     * Strips control characters from untrusted text before it reaches the log.
     *
     * Custom instrument paths are arbitrary strings out of a .nbs file, and a
     * newline in one would let a crafted song forge console lines.
     */
    private static String safeForLog(String text) {
        String cleaned = text.replaceAll("[\\p{Cntrl}]", "?");
        return cleaned.length() > 120 ? cleaned.substring(0, 117) + "..." : cleaned;
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
        if (overrideName != null && !overrideName.isBlank()) return io.github.noteblockworldmc.util.Limits.display(overrideName);
        if (!song.getName().isBlank()) return song.getName();
        if (fallbackName != null) {
            String fromFile = stripExtension(fallbackName).trim();
            if (!fromFile.isBlank()) return io.github.noteblockworldmc.util.Limits.display(fromFile);
        }
        return publicId;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
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
    public synchronized CompletableFuture<Path> download(String publicId, SongPreview preview) {
        if (publicId == null || !SAFE_ID.matcher(publicId).matches())
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid song ID"));
        if (isDownloaded(publicId)) return CompletableFuture.completedFuture(getNbsFile(publicId));
        // A second request used to be refused as "already stored or being
        // changed", and because it replaced the caller's play request first,
        // the original download then finished without playing anything.
        // Share the running download instead (a view, so cancelling one
        // caller's future cannot cancel it for the other).
        CompletableFuture<Path> running = activeDownloads.get(publicId);
        if (running != null) return running.thenApply(path -> path);
        try { reserve(publicId); }
        catch (RuntimeException e) { return CompletableFuture.failedFuture(e); }
        var client = plugin.getApiClient();
        // The URL returned by the API is untrusted. Do not hand it to the
        // general HttpClient: that client follows redirects and resolves DNS
        // independently of validation, reopening an SSRF/DNS-rebinding
        // window. SecureUrlFetcher validates and pins every hop on the same
        // socket that downloads the bytes.
        CompletableFuture<Path> result = client.fetchOpenUrl(publicId).thenCompose(url -> plugin.getWorkPool().submit(() -> {
                    try (SecureUrlFetcher fetcher = new SecureUrlFetcher(maxZipBytes)) {
                        return fetcher.fetch(url);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }))
                .thenCompose(bytes -> plugin.getWorkPool().submit(() -> {
                    try { return extract(publicId, bytes, preview); }
                    catch (IOException e) { throw new UncheckedIOException(e); }
                })).whenComplete((path, error) -> {
                    release(publicId);
                    if (error == null && !plugin.isStopping()) {
                        try {
                            if (hasCustomSounds(publicId)) plugin.getResourcePackService().onLibraryChanged();
                        } catch (RuntimeException e) {
                            // The library commit already succeeded. A failed
                            // rebuild notification must not turn that success
                            // into an exceptional download future.
                            plugin.getLogger().warning("Downloaded " + publicId
                                    + ", but could not refresh the resource pack: " + e);
                        }
                    }
                });
        // Registered before observing completion, so a download that already
        // finished removes its own entry immediately.
        activeDownloads.put(publicId, result);
        result.whenComplete((path, error) -> activeDownloads.remove(publicId, result));
        return result;
    }

    // ------------------------------------------------------------------

    /**
     * Removes a downloaded song from the library. Refuses while the song
     * is mid-download. The in-memory set updates immediately (so UIs
     * refresh correctly); file deletion happens off-thread.
     */
    public synchronized boolean remove(String publicId) {
        if (publicId == null || !SAFE_ID.matcher(publicId).matches()) return false;
        if (inFlight.contains(publicId) || pendingDeletion.contains(publicId)) return false;
        Path original = getSongDirectory(publicId), trash;
        if (!downloaded.contains(publicId) && !isOrphanDirectory(original)) return false;
        pendingDeletion.add(publicId);
        boolean hadAudio = hasSoundFiles(original);
        try {
            Path root = songsDir.resolve(".trash");
            if (Files.isSymbolicLink(root)) throw new IOException("Songs trash folder must not be a symbolic link");
            Files.createDirectories(root);
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Songs trash folder must be a real directory");
            }
            trash = root.resolve(java.util.UUID.randomUUID().toString());
            Files.move(original, trash); // unpublish atomically, before any new import/download can reserve the ID
        } catch (IOException e) {
            pendingDeletion.remove(publicId);
            plugin.getLogger().warning("Could not remove " + publicId + ": " + e); return false;
        }
        downloaded.remove(publicId); titles.remove(publicId); entryCache.remove(publicId); libraryGeneration++;
        plugin.getPlaybackManager().invalidateCache(publicId);
        // The UUID trash destination is independent of the reusable song ID.
        // Its contents stay recoverable by the sweeper if admission fails.
        pendingDeletion.remove(publicId);
        requestTrashCleanup();
        // A song without custom audio contributes nothing to the pack. With
        // repush-on-remove, rebuilding anyway held every listener's next song
        // behind a "waiting for resource pack" gate for no change at all.
        if (hadAudio) plugin.getResourcePackService().onLibraryShrunk();
        return true;
    }

    /** Cheap, conservative check run before a removal: any regular file under sounds/. */
    private static boolean hasSoundFiles(Path songDirectory) {
        Path sounds = songDirectory.resolve("sounds");
        if (!Files.isDirectory(sounds, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return false;
        try (Stream<Path> walk = Files.walk(sounds, 16)) {
            return walk.anyMatch(file -> Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS));
        } catch (IOException | java.io.UncheckedIOException e) {
            return true; // unknown: rebuilding is the safe choice
        }
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
        return plugin.getWorkPool().submit(() -> {
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
            int from = (int)Math.min(entries.size(), (long)(page - 1) * limit);
            int to = (int) Math.min(entries.size(), (long) from + limit);

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
            // A file can be removed between reloads. Do not let an old set
            // member or LocalEntry make a missing song appear in --downloaded.
            if (!hasStoredSong(publicId)) continue;
            LocalEntry entry = loadLocalEntry(publicId);
            if (entry != null) entries.add(entry);
        }
        return entries;
    }

    private LocalEntry loadLocalEntry(String publicId) {
        final long generation;
        LocalEntry cached;
        synchronized (this) {
            generation = libraryGeneration;
            cached = entryCache.get(publicId);
        }
        if (cached != null) {
            synchronized (this) {
                return generation == libraryGeneration && downloaded.contains(publicId) ? cached : null;
            }
        }
        LocalEntry loaded = readLocalEntry(publicId);
        synchronized (this) {
            if (generation != libraryGeneration || !downloaded.contains(publicId)) return null;
            if (loaded != null) entryCache.put(publicId, loaded);
            return loaded;
        }
    }

    private LocalEntry readLocalEntry(String publicId) {
        Path dir;
        try {
            dir = getSongDirectory(publicId);
        } catch (RuntimeException e) {
            return null;
        }
        if (!Files.isDirectory(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(dir.resolve(NBS_FILE_NAME), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
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

    /** Fast defensive check used at command/query boundaries between rescans. */
    private boolean hasStoredSong(String publicId) {
        if (publicId == null || !SAFE_ID.matcher(publicId).matches()) return false;
        try {
            Path dir = getSongDirectory(publicId);
            return Files.isDirectory(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    && Files.isRegularFile(dir.resolve(NBS_FILE_NAME),
                            java.nio.file.LinkOption.NOFOLLOW_LINKS);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private Map<String, Object> readMeta(Path dir) {
        Path metaFile = dir.resolve(META_FILE_NAME);
        if (Files.exists(metaFile)) {
            try {
                Map<String, Object> meta = gson.fromJson(
                        new String(io.github.noteblockworldmc.util.Limits.read(metaFile, 128 * 1024), StandardCharsets.UTF_8),
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
        Path dir = newStage();
        try {
        // A reload changes subsequent extractions, not the budget of this one.
        long extractionLimit = maxExtractedBytes;
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
                    if (zip.read() != -1) throw new IOException("ZIP directory entry contains data");
                    if (hasSymlinkComponent(dir, target)) throw new IOException("Blocked symbolic-link ZIP path");
                    Files.createDirectories(target);
                    continue;
                }
                if (target.equals(dir) || Files.exists(target) || entry.getName().length() > 512
                        || dir.relativize(target).getNameCount() > 16) throw new IOException("Duplicate or invalid ZIP path");
                Files.createDirectories(target.getParent());
                if (hasSymlinkComponent(dir, target) || Files.isSymbolicLink(target)) {
                    throw new IOException("Blocked symbolic-link ZIP path");
                }

                try (OutputStream out = Files.newOutputStream(target,
                        java.nio.file.StandardOpenOption.CREATE_NEW)) {
                    int read;
                    while ((read = zip.read(buffer)) > 0) {
                        totalExtracted += read;
                        if (totalExtracted > extractionLimit) {
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

        NbsSong parsed = NbsParser.parse(nbsFile); // validate even when API metadata is present
        writeMeta(dir, publicId, preview, nbsFile);
        commit(dir, publicId, preview != null && !preview.getTitle().isBlank() ? preview.getTitle()
                : parsed.getName().isBlank() ? publicId : parsed.getName());
        return getNbsFile(publicId);
        } finally { cleanupStage(dir); }
    }

    /**
     * noteblock.world names packed sounds by Mojang asset hash with NO
     * file extension, so an "*.ogg" filter finds nothing. Inspect the bounded
     * Ogg/Vorbis structure instead of trusting the filename or four-byte magic.
     */
    private boolean hasCustomSounds(String publicId) {
        Path soundsDir = songsDir.resolve(publicId).resolve("sounds");
        if (!Files.isDirectory(soundsDir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return false;
        try (Stream<Path> walk = Files.walk(soundsDir)) {
            return walk.filter(file -> Files.isRegularFile(file,
                            java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .anyMatch(file -> {
                try {
                    if (Files.size(file) > io.github.noteblockworldmc.util.Limits.SAMPLE_BYTES) return false;
                    return io.github.noteblockworldmc.pack.OggTools.isVorbis(
                            io.github.noteblockworldmc.util.Limits.read(file,
                                    io.github.noteblockworldmc.util.Limits.SAMPLE_BYTES));
                } catch (IOException e) {
                    return false;
                }
            });
        } catch (IOException e) {
            return false;
        }
    }

    /** Cancel our recurring admission task before the plugin closes its workers. */
    public void stop() {
        if (trashCleanupTask != null) { trashCleanupTask.cancel(); trashCleanupTask = null; }
    }

    private void requestTrashCleanup() {
        if (plugin.isStopping() || !trashCleanupPending.compareAndSet(false, true)) return;
        plugin.getWorkPool().run(this::cleanTrash).whenComplete((unused, error) -> {
            trashCleanupPending.set(false);
            if (error != null && !plugin.isStopping())
                plugin.getLogger().warning("Trash cleanup deferred; will retry automatically: " + error);
        });
    }

    /** Only our UUID-named removal directories; never staging or recovery backups. */
    private void cleanTrash() {
        indexStartupTitles();
        recoverInterruptedImports();
        Path root = songsDir.resolve(".trash");
        if (Files.isSymbolicLink(songsDir) || !Files.isDirectory(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> entries = Files.list(root)) {
            var iterator = entries.iterator();
            int attempted = 0;
            while (iterator.hasNext() && attempted < 64 && !plugin.isStopping()
                    && !Thread.currentThread().isInterrupted()) {
                Path entry = iterator.next();
                if (!entry.getFileName().toString().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                        || !Files.isDirectory(entry, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
                attempted++;
                deleteRecursivelyQuietly(entry);
            }
        } catch (IOException | java.io.UncheckedIOException e) {
            plugin.getLogger().warning("Could not scan trash; will retry automatically: " + e);
        }
    }

    private void cleanupStage(Path stage) {
        try { deleteRecursivelyQuietly(stage); }
        finally { synchronized (recoveryLock) { activeImportPaths.remove(stage); } }
    }

    /** Restore claimed NBS inputs; discard only abandoned, uncommitted staging trees. */
    private void recoverInterruptedImports() {
        if (plugin.isStopping() || Files.isSymbolicLink(songsDir) || Files.isSymbolicLink(importDir)) return;
        recoverDirectory(importDir, true);
        Path staging = songsDir.resolve(".staging");
        if (Files.isDirectory(staging, java.nio.file.LinkOption.NOFOLLOW_LINKS)) recoverDirectory(staging, false);
    }

    private void recoverDirectory(Path root, boolean claims) {
        try (Stream<Path> entries = Files.list(root)) {
            var iterator = entries.iterator();
            int recovered = 0;
            while (iterator.hasNext() && recovered < 64 && !plugin.isStopping()
                    && !Thread.currentThread().isInterrupted()) {
                Path entry = iterator.next();
                String name = entry.getFileName().toString();
                if (claims ? !name.matches("\\.nbw-claim-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                        : !name.matches("song-[A-Za-z0-9_-]+")) continue;
                synchronized (recoveryLock) {
                    if (activeImportPaths.contains(entry)) continue;
                    try {
                        if (claims) {
                            if (!Files.isRegularFile(entry, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
                            // Older claims contain no original filename. Keep every byte
                            // and give it a discoverable name without replacing any input.
                            Path target = importDir.resolve("recovered-" + UUID.randomUUID() + ".nbs");
                            Files.move(entry, target);
                            plugin.getLogger().info("Recovered interrupted import as " + target.getFileName());
                        } else {
                            if (!Files.isDirectory(entry, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
                            Path trash = songsDir.resolve(".trash");
                            if (Files.isSymbolicLink(trash)) throw new IOException("Trash folder must not be a symlink");
                            Files.createDirectories(trash);
                            // Relocate briefly under the ownership lock; recursive
                            // deletion happens later in the ordinary trash sweep.
                            Files.move(entry, trash.resolve(UUID.randomUUID().toString()));
                        }
                        recovered++;
                    } catch (IOException e) { plugin.getLogger().warning("Import recovery deferred for " + entry + ": " + e); }
                }
            }
        } catch (IOException | java.io.UncheckedIOException e) {
            plugin.getLogger().warning("Could not scan interrupted imports; will retry: " + e);
        }
    }

    private void deleteRecursivelyQuietly(Path dir) {
        if (!Files.exists(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) { plugin.getLogger().warning("Could not clean up " + path + ": " + e); }
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
    private void writeMeta(Path dir, String publicId, SongPreview preview, Path nbsFile) throws IOException {
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
        Files.writeString(dir.resolve(META_FILE_NAME), gson.toJson(meta), StandardCharsets.UTF_8);
    }
}
