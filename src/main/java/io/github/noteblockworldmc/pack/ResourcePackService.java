package io.github.noteblockworldmc.pack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpServer;
import io.github.noteblockworldmc.NoteblockWorldMCPlugin;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Generates, hosts, and enforces the server resource pack.
 *
 * Contents are of two kinds. Extended-octave sound events: for each of the
 * 20 note block instruments, six events (_m3.._m1, _p1.._p3) reference the
 * VANILLA sound event (type "event" — no Mojang audio is copied) with a
 * baked pitch of 2^-3 .. 2^3. Combined with the runtime 0.5-2.0 pitch
 * range, base + ±3 octaves spans NBS keys 0-87: the full 88-key piano.
 * And custom instrument audio shipped with downloaded songs, subject to the
 * max-custom-sounds-mb budget; anything that is not Ogg Vorbis is skipped
 * with a warning, since Minecraft packs accept no other format.
 *
 * The pack is built in memory, written to the data folder for inspection,
 * hashed (SHA-1), and served by a tiny built-in HTTP server. rebuild()
 * regenerates and re-pushes whenever the library changes.
 */
public class ResourcePackService implements Listener {

    /** Suffix -> octave shift applied to the source audio. */
    private static final Map<String, Integer> OCTAVE_VARIANTS = new LinkedHashMap<>();
    static {
        OCTAVE_VARIANTS.put("_m3", -3);
        OCTAVE_VARIANTS.put("_m2", -2);
        OCTAVE_VARIANTS.put("_m1", -1);
        OCTAVE_VARIANTS.put("_p1", 1);
        OCTAVE_VARIANTS.put("_p2", 2);
        OCTAVE_VARIANTS.put("_p3", 3);
    }

    /** Short names, index-aligned with the playback instrument table. */
    public static final String[] INSTRUMENT_NAMES = {
            "harp", "bass", "basedrum", "snare", "hat", "guitar", "flute",
            "bell", "chime", "xylophone", "iron_xylophone", "cow_bell",
            "didgeridoo", "bit", "banjo", "pling",
            "trumpet", "trumpet_exposed", "trumpet_weathered", "trumpet_oxidized"
    };

    /** Stable pack id: same pack slot across rebuilds, new hash per build. */
    private static final UUID PACK_ID =
            UUID.nameUUIDFromBytes("noteblockworldmc-pack".getBytes(StandardCharsets.UTF_8));

    private final NoteblockWorldMCPlugin plugin;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Whether the pack is served at all. Not final: /nbw reload can start or
     * stop the server, so this is re-read with the other hot settings.
     */
    private volatile boolean enabled;
    /**
     * Address clients fetch the pack from. Re-read on reload; changing it does
     * not touch the bound socket, only what we tell clients to request.
     */
    private volatile String publicUrl;
    private final int port;
    private final String bindAddress;
    private final int rateLimitPerMinute;
    private final int maxConcurrentDownloads;
    private volatile boolean kickOnRefuse;
    private volatile long maxCustomSoundBytes;
    /**
     * Strict removal: re-push the pack when songs are removed, so online
     * players never keep stale sounds.
     *
     * Off by default. A re-push makes every online player re-download the
     * pack and reload resources — a large bandwidth spike and an audible
     * interruption — to clear sounds that nothing can trigger any more,
     * since the song itself is gone. Left off, they sit unused until the
     * next natural push.
     */
    private volatile boolean repushOnRemove;
    private volatile long rebuildDebounceTicks;
    private volatile long settleDelayMillis;
    private final java.util.concurrent.atomic.AtomicBoolean rebuildPending =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private HttpServer httpServer;
    private java.util.concurrent.ExecutorService httpExecutor;
    /** Downloads in flight, to cap concurrency. */
    private final java.util.concurrent.atomic.AtomicInteger activeDownloads =
            new java.util.concurrent.atomic.AtomicInteger();
    /** Per-IP request counters for rate limiting: ip -> [windowStartMs, count]. */
    private final Map<String, long[]> requestCounts = new ConcurrentHashMap<>();
    /**
     * The built pack and its hash, published together.
     *
     * These must move as ONE value: hashing several megabytes takes long
     * enough that assigning them separately leaves a window where the HTTP
     * server serves the new bytes while send() still advertises the old
     * SHA-1. A client joining in that window fails its hash check -- and
     * with kick-on-refuse, gets kicked for the server's own race.
     */
    private record PackData(byte[] bytes, String sha1) {}
    private volatile PackData pack;
    /** Sound events actually present in the current pack (no namespace). */
    private volatile java.util.Set<String> generatedEvents = java.util.Set.of();
    private final VanillaSounds vanillaSounds;

    /** Players whose client confirmed the pack loaded. */
    private final Set<UUID> packLoaded = ConcurrentHashMap.newKeySet();
    /** Players whose pack status reached ANY terminal state (loaded/declined/failed). */
    private final Set<UUID> packResolved = ConcurrentHashMap.newKeySet();
    /** Players still awaiting their FIRST (join-time) pack: only these are kicked. */
    private final Set<UUID> awaitingInitialPack = ConcurrentHashMap.newKeySet();
    /** When each player's pack status resolved, for the settle delay. */
    private final Map<UUID, Long> resolvedAtMillis = new ConcurrentHashMap<>();
    /**
     * When each player was last sent a pack and has not answered yet.
     *
     * Playback is gated on a player being "settled", and nothing ever released
     * that gate on its own: a client that never reports a status stayed
     * unsettled for the rest of the session, which silenced every song for
     * them until they reconnected. This gives that wait a bound.
     */
    private final Map<UUID, Long> packSentAtMillis = new ConcurrentHashMap<>();

    /**
     * When the library last invalidated every client's pack, or 0.
     *
     * There is a gap between invalidating and pushing: the rebuild is
     * debounced so a burst of downloads costs one client reload rather than
     * several. During that gap a player has no resolution AND no outstanding
     * push, and treating that as "nothing to wait for" let songs start on the
     * old pack -- audibly, the song began without its custom instruments and
     * they appeared partway through once the pack landed.
     */
    private volatile long invalidatedAtMillis;

    /**
     * How long to wait for a client to answer a pack push before giving up on
     * it. Generous, because a large pack on a slow link legitimately takes a
     * while, and the only cost of waiting is that the first song of a session
     * may start without the extended octaves.
     */
    private static final long RESOLUTION_TIMEOUT_MS = 30_000;

    public ResourcePackService(NoteblockWorldMCPlugin plugin) {
        this.plugin = plugin;
        // Seeded here so the loadHotSettings() call at the end of this
        // constructor sees no CHANGE and therefore starts nothing. The plugin
        // calls start() itself once everything is wired; without these two
        // lines a config with the pack enabled would look like an off->on
        // transition and bind the socket from inside the constructor.
        this.enabled = plugin.getConfig().getBoolean("resource-pack.enabled", false);
        this.publicUrl = resolvePublicUrl(
                plugin.getConfig().getString("resource-pack.public-url", ""),
                plugin.getConfig().getInt("resource-pack.port", 8194));
        // Fixed for the lifetime of the server: the HTTP server binds these
        this.port = plugin.getConfig().getInt("resource-pack.port", 8194);
        this.bindAddress = plugin.getConfig()
                .getString("resource-pack.bind-address", "0.0.0.0");
        this.rateLimitPerMinute = Math.max(0, plugin.getConfig()
                .getInt("resource-pack.rate-limit-per-minute", 30));
        this.maxConcurrentDownloads = Math.max(1, plugin.getConfig()
                .getInt("resource-pack.max-concurrent-downloads", 16));
        this.vanillaSounds = new VanillaSounds(plugin);
        loadHotSettings();
    }

    /**
     * Turns the configured public-url into the address clients actually fetch
     * from. "auto" (or "auto:PORT") asks the OS which outbound IP this machine
     * uses and builds "http://THAT_IP:port/pack.zip" from it.
     *
     * This is a convenience for simple single-server setups, and it comes with
     * real caveats, so it is opt-in rather than the default:
     *  - It returns the machine's PRIMARY outbound IP. Behind NAT (most home
     *    servers) that is the LAN address, which outside players cannot reach;
     *    those setups still need a manual public-url or a port-forward + the
     *    external IP.
     *  - It never guesses https. Detection only ever yields plain http.
     * Anything already looking like a URL is passed through untouched.
     */
    private String resolvePublicUrl(String configured, int port) {
        String value = configured == null ? "" : configured.trim();
        if (!value.equalsIgnoreCase("auto") && !value.toLowerCase(java.util.Locale.ROOT).startsWith("auto:")) {
            return value;   // blank or an explicit URL -- unchanged
        }
        String detected = detectOutboundIp();
        if (detected == null) {
            plugin.getLogger().warning("resource-pack.public-url is \"auto\" but this server's"
                    + " outbound IP could not be detected. Set public-url to a real address.");
            return "";
        }
        String url = "http://" + detected + ":" + port + "/pack.zip";
        plugin.getLogger().info("resource-pack.public-url auto-detected as " + url
                + ". If players outside your network cannot load the pack, this is the"
                + " LAN address -- set public-url manually to your public IP or domain.");
        return url;
    }

    /**
     * The IP this machine would use to reach the outside world.
     *
     * Opening a UDP socket to a public address forces the OS to select an
     * outbound interface WITHOUT sending a single packet -- no traffic leaves,
     * nothing on the far side is contacted. This beats getLocalHost(), which
     * on multi-homed or misconfigured /etc/hosts machines often returns
     * 127.0.0.1.
     */
    private String detectOutboundIp() {
        try (java.net.DatagramSocket socket = new java.net.DatagramSocket()) {
            // 192.0.2.1 is TEST-NET-1 (RFC 5737): guaranteed never routed, so
            // this is purely a routing-table lookup, not a real connection.
            socket.connect(java.net.InetAddress.getByName("192.0.2.1"), 9);
            java.net.InetAddress local = socket.getLocalAddress();
            if (local == null || local.isAnyLocalAddress() || local.isLoopbackAddress()) {
                return null;
            }
            return local.getHostAddress();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * (Re)reads the settings that can change at runtime. Called on startup
     * and by /nbw reload, so both paths always read the same keys.
     *
     * port and bind-address are still NOT here: the socket is bound to them,
     * and re-binding mid-session would drop pack downloads in flight. Those
     * need a restart. enabled and public-url are handled, because neither
     * requires touching a live socket -- one starts or stops the server, the
     * other only changes the address we hand to clients.
     */
    public void loadHotSettings() {
        var config = plugin.getConfig();
        boolean wasEnabled = this.enabled;
        String previousUrl = this.publicUrl;
        this.enabled = config.getBoolean("resource-pack.enabled", false);
        this.publicUrl = resolvePublicUrl(
                config.getString("resource-pack.public-url", ""), port);
        this.kickOnRefuse = config.getBoolean("resource-pack.kick-on-refuse", true);
        this.repushOnRemove = config.getBoolean("resource-pack.repush-on-remove", false);
        this.maxCustomSoundBytes =
                config.getLong("resource-pack.max-custom-sounds-mb", 64) * 1024 * 1024;
        this.rebuildDebounceTicks =
                20L * config.getInt("resource-pack.rebuild-debounce-seconds", 3);
        this.settleDelayMillis =
                Math.max(0, config.getLong("resource-pack.settle-delay-ms", 2000));

        applyServerStateChange(wasEnabled, previousUrl);
    }

    /**
     * Starts, stops or re-advertises the pack server after a reload.
     *
     * Called only from loadHotSettings, and only ever on the main thread, so
     * the start/stop pair cannot interleave with itself.
     */
    private void applyServerStateChange(boolean wasEnabled, String previousUrl) {
        // Startup runs loadHotSettings before start(), when nothing is bound
        // yet; there is no transition to apply and start() follows anyway.
        if (!wasEnabled && !this.enabled) return;

        if (!wasEnabled) {
            plugin.getLogger().info("resource-pack.enabled turned on; starting the pack server.");
            start();
            return;
        }
        if (!this.enabled) {
            plugin.getLogger().info("resource-pack.enabled turned off; stopping the pack server. "
                    + "Players already holding the pack keep it until they reconnect.");
            stopServer();
            return;
        }

        // Still enabled, but the address changed -- most often an admin fixing
        // a URL players could not reach. Re-send it so the people affected get
        // the pack now instead of on their next login.
        if (!java.util.Objects.equals(previousUrl, this.publicUrl)) {
            plugin.getLogger().info("resource-pack.public-url changed to " + this.publicUrl
                    + "; re-sending the pack to online players.");
            if (isActive()) {
                for (Player online : plugin.getServer().getOnlinePlayers()) {
                    // Not an initial send, so a failure warns rather than
                    // kicking someone over an address they cannot control.
                    send(online);
                }
            } else {
                // The URL was blank or invalid at boot, so nothing was ever
                // bound; a corrected address should bring the server up.
                start();
            }
        }
    }

    /** Builds the pack and starts the HTTP server. Hosting/enforcement is
     *  skipped unless enabled, but the inspection copy is always written so
     *  owners can preview the pack before turning enforcement on. */
    public void start() {
        // Idempotent: reload can reach this while a server is already bound,
        // and HttpServer.create would then throw BindException and leave the
        // catch below nulling out a perfectly good server.
        stopServer();

        // Sounds are cached after the first run, so this is normally a no-op.
        // When they ARE missing, the fetch happens off-thread: 20 downloads
        // against a slow CDN would otherwise stall the whole server boot.
        boolean fetchNeeded = plugin.getConfig()
                .getBoolean("resource-pack.download-vanilla-sounds", true)
                && !vanillaSounds.hasAll();

        if (!enabled) {
            // The inspection copy is written even when the pack is switched
            // off, but there is nobody to serve it to, so nothing to push.
            buildPackAsync(false);
            if (fetchNeeded) fetchVanillaSoundsAsync();
            return;
        }
        // Validated once here rather than on every join: URI.create throws an
        // UNCHECKED exception, which inside the join handler would surface as
        // a stack trace per player with the pack silently never arriving.
        try {
            URI.create(publicUrl == null ? "" : publicUrl);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().severe("resource-pack.public-url is not a valid URL ("
                    + publicUrl + ") — pack disabled.");
            if (fetchNeeded) fetchVanillaSoundsAsync();
            return;
        }
        if (publicUrl == null || publicUrl.isBlank()) {
            plugin.getLogger().severe("resource-pack.enabled is true but resource-pack.public-url "
                    + "is empty. Set it to a URL players can reach, e.g. "
                    + "http://your.server.address:" + port + "/pack.zip — pack disabled.");
            if (fetchNeeded) fetchVanillaSoundsAsync();
            return;
        }
        try {
            httpServer = HttpServer.create(
                    new InetSocketAddress(bindAddress, port), 32);
            httpServer.createContext("/pack.zip", this::handlePackRequest);
            // The pack carries audio (megabytes). With too few threads, one slow
            // client stalls every other download until their client gives up --
            // and kick-on-refuse would then boot legitimate players.
            this.httpExecutor = java.util.concurrent.Executors.newFixedThreadPool(8, runnable -> {
                Thread thread = new Thread(runnable, "NoteblockWorldMC-pack-http");
                thread.setDaemon(true);   // must not outlive the plugin
                return thread;
            });
            httpServer.setExecutor(httpExecutor);
            httpServer.start();
            plugin.getLogger().info("Resource pack served on port " + port
                    + " (public URL: " + publicUrl + ")");

            // Binding the socket is cheap; BUILDING the pack is not -- it reads
            // every custom sound and generates six octave variants of each, then
            // writes the zip. Doing that inline stalled startup and every
            // /nbw reload in proportion to library size. The server is up first
            // and answers 503 until the bytes exist, and the push to players
            // already online happens once the build lands.
            buildPackAsync(true);
        } catch (Exception e) {
            plugin.getLogger().severe("Could not start the resource pack server: " + e);
            httpServer = null;
        } finally {
            if (fetchNeeded) {
                fetchVanillaSoundsAsync();
            }
        }
    }

    /**
     * Serves the pack.
     *
     * The request is never used to look anything up — the response is a byte
     * array already in memory — so there is no path, query, or header that
     * could reach a filesystem, database, or interpreter. What this DOES need
     * protecting from is abuse: the pack is megabytes, and a flood (or a few
     * slow connections) could starve real players of their download and, with
     * kick-on-refuse enabled, get them disconnected.
     */
    private void handlePackRequest(com.sun.net.httpserver.HttpExchange exchange)
            throws IOException {
        try {
            PackData current = pack;
            byte[] bytes = current == null ? null : current.bytes();

            // Exact path only; the context would otherwise also match /pack.zip/...
            if (!"/pack.zip".equals(exchange.getRequestURI().getPath())) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            if (bytes == null) {
                exchange.sendResponseHeaders(503, -1);   // not built yet
                return;
            }
            if (isRateLimited(exchange.getRemoteAddress().getAddress().getHostAddress())) {
                exchange.sendResponseHeaders(429, -1);   // Too Many Requests
                return;
            }
            if (activeDownloads.incrementAndGet() > maxConcurrentDownloads) {
                // MUST release the slot we just claimed. Returning without
                // this leaks one permit per rejection, so after
                // max-concurrent-downloads rejections the counter never falls
                // back under the limit and the pack becomes permanently
                // unservable -- with kick-on-refuse on, that kicks everyone
                // who joins, until the server restarts.
                activeDownloads.decrementAndGet();
                exchange.sendResponseHeaders(503, -1);   // busy; client retries
                return;
            }
            try {
                exchange.getResponseHeaders().set("Content-Type", "application/zip");
                exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            } finally {
                activeDownloads.decrementAndGet();
            }
        } catch (IOException e) {
            // A client that hangs up mid-download is routine, not an error
        } finally {
            exchange.close();
        }
    }

    /** Fixed-window limiter, per source IP. */
    private boolean isRateLimited(String ip) {
        if (rateLimitPerMinute <= 0) return false;

        long now = System.currentTimeMillis();
        // Bound the map so a spoofed-IP flood can't grow it without limit
        if (requestCounts.size() > 10_000) {
            requestCounts.entrySet().removeIf(e -> now - e.getValue()[0] > 60_000);
        }
        long[] window = requestCounts.computeIfAbsent(ip, key -> new long[]{now, 0});
        synchronized (window) {
            if (now - window[0] > 60_000) {          // new minute
                window[0] = now;
                window[1] = 0;
            }
            return ++window[1] > rateLimitPerMinute;
        }
    }

    /**
     * Full shutdown, for plugin disable.
     *
     * Closes the vanilla-sound downloader as well, which a reload-driven
     * disable must NOT do -- its HTTP client cannot be reopened, so a later
     * re-enable would be unable to fetch anything.
     */
    public void stop() {
        stopServer();
        vanillaSounds.close();
    }

    /**
     * Releases the port and its thread pool, leaving the rest of the service
     * usable so the pack can be switched back on without a restart.
     *
     * Players already holding the pack keep it until they reconnect; there is
     * no way to ask a client to forget one, and taking it away mid-session
     * would silence any song using custom sounds.
     */
    private void stopServer() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
        // Without this the pool's threads (and the plugin's classloader)
        // survive a disable/reload, and the port can stay bound.
        if (httpExecutor != null) {
            httpExecutor.shutdownNow();
            httpExecutor = null;
        }
    }

    /**
     * Fetches the vanilla sounds off-thread, then rebuilds and re-pushes so
     * players who joined in the meantime get the octave-enabled pack.
     */
    private void fetchVanillaSoundsAsync() {
        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            int available;
            try {
                available = vanillaSounds.ensureDownloaded();
            } catch (Exception e) {
                plugin.getLogger().warning("Could not fetch vanilla note block sounds: " + e
                        + " — extended octaves unavailable for vanilla instruments.");
                return;
            }
            if (available == 0) {
                plugin.getLogger().warning("No vanilla note block sounds available — "
                        + "extended octaves unavailable (notes fold into the vanilla range). "
                        + "Custom instruments are unaffected.");
                return;
            }
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                if (isActive()) {
                    onLibraryChanged();       // rebuild + invalidate + re-push
                } else {
                    // Off-thread for the same reason as startup: this lands
                    // after a vanilla-sound download, so the pack is being
                    // built from scratch and is at its most expensive.
                    buildPackAsync(false);
                }
            });
        });
    }

    public boolean isActive() {
        return enabled && httpServer != null;
    }

    /** One pre-shifted vanilla octave variant, cached across rebuilds. */
    private record OctaveEntry(String event, String path, byte[] bytes) {}

    /**
     * The 120 vanilla octave variants never change once the vanilla sounds
     * are on disk, yet every rebuild used to re-read and re-shift all of
     * them — the bulk of the rebuild players wait through after a
     * custom-song download. Cached after first generation; regenerated only
     * if the number of available source files changes (i.e. the async
     * vanilla fetch completed in between).
     */
    private volatile List<OctaveEntry> vanillaOctaveCache;
    private volatile int vanillaOctaveSourceCount = -1;

    /** Measured ring time of a vanilla instrument's sample, or -1. */
    public double sampleSeconds(int instrument) {
        if (instrument < 0 || instrument >= INSTRUMENT_NAMES.length) return -1;
        Double cached = sampleSecondsCache.get(instrument);
        if (cached != null) return cached;

        double seconds = -1;
        java.nio.file.Path file = vanillaSounds.get(INSTRUMENT_NAMES[instrument]);
        if (file != null) {
            try {
                seconds = OggTools.readDurationSeconds(Files.readAllBytes(file));
            } catch (IOException ignored) {
                // leave as -1; the caller falls back to an estimate
            }
        }
        // Only cache successes: a read that failed because the vanilla sounds
        // had not downloaded yet would otherwise pin -1 for the rest of the
        // session, leaving voice allocation on its crude 1-second fallback
        // even after the sounds arrive.
        if (seconds > 0) {
            sampleSecondsCache.put(instrument, seconds);
        }
        return seconds;
    }

    private final Map<Integer, Double> sampleSecondsCache = new ConcurrentHashMap<>();

    /** True if the pack actually contains this sound event. */
    public boolean hasEvent(String event) {
        return generatedEvents.contains(event);
    }

    public String getPackSha1() {
        PackData current = pack;
        return current == null ? null : current.sha1();
    }

    public int getPackSize() {
        PackData current = pack;
        return current == null ? 0 : current.bytes().length;
    }

    public int getEventCount() {
        return generatedEvents.size();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getPublicUrl() {
        return publicUrl;
    }

    /** True once this player's client confirmed the pack is loaded. */
    public boolean isPackLoaded(UUID playerId) {
        return packLoaded.contains(playerId);
    }

    /**
     * True once this player's pack status reached a terminal state —
     * loaded, declined, or failed. Always true while the pack system is
     * inactive. Playback gates song starts on this, NOT on success:
     * a declined-but-not-kicked player must not stall everyone's music.
     */
    public boolean isResolved(UUID playerId) {
        return !isActive() || packResolved.contains(playerId);
    }

    /**
     * Like {@link #isResolved}, but also waits out the settle delay.
     *
     * SUCCESSFULLY_LOADED fires when the client APPLIES the pack, which is
     * not quite when it has finished tearing down the "Loading resources"
     * screen — notes played in that window can be missed. So playback waits
     * a moment after resolution. A player who resolved long ago (the normal
     * case) passes instantly; only someone who just reloaded actually waits.
     */
    public boolean isSettled(UUID playerId) {
        if (!isActive()) return true;
        long now = System.currentTimeMillis();

        Long resolvedAt = resolvedAtMillis.get(playerId);
        if (resolvedAt != null) {
            return now - resolvedAt >= settleDelayMillis;
        }

        // Unresolved, so decide whether something is still coming.
        //
        // This gate must not stick shut -- a client that never answers a push
        // once left the player in permanent silence -- but it must also not
        // open too early, or a song starts on a stale pack and its custom
        // instruments appear partway through. Both failures are real, so each
        // waiting reason carries its own bound.
        Long sentAt = packSentAtMillis.get(playerId);
        if (sentAt != null) {
            // A push is outstanding: wait for an answer, then give up.
            return now - sentAt >= RESOLUTION_TIMEOUT_MS;
        }

        long invalidated = invalidatedAtMillis;
        if (invalidated != 0 && now - invalidated < RESOLUTION_TIMEOUT_MS) {
            // The library changed and the debounced rebuild has not pushed
            // yet. Hold, so the song starts with the pack it expects.
            return false;
        }

        // Nothing outstanding and no rebuild coming: there is nothing to wait
        // for. Letting them hear the song without the pack beats silence.
        return true;
    }

    /**
     * Regenerates the pack zip and hash. Phase 3 will call this after song
     * downloads add custom instruments, then re-push to online players.
     */
    public void rebuild() throws IOException {
        byte[] bytes = buildPackZip();
        // Hash first, then publish both in a single volatile write.
        this.pack = new PackData(bytes, sha1Hex(bytes));

        // Written out for server-owner inspection/debugging only
        Path packFile = plugin.getDataFolder().toPath().resolve("resource-pack.zip");
        Files.createDirectories(packFile.getParent());
        Files.write(packFile, bytes);
    }

    /**
     * Builds the pack on a worker thread and publishes the result on the main
     * thread.
     *
     * Mirrors what doRebuildAndPush already does for runtime library changes;
     * startup was the one path still doing this work inline.
     *
     * @param pushWhenDone send the finished pack to everyone already online,
     *                     which matters for /reload and plugin swaps where no
     *                     join event will fire
     */
    private void buildPackAsync(boolean pushWhenDone) {
        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean built;
            try {
                rebuild();
                built = true;
            } catch (Exception e) {
                plugin.getLogger().severe("Could not generate the resource pack: " + e);
                built = false;
            }
            boolean success = built;

            // The plugin can be disabled while this is in flight (a fast
            // stop, or a reload that swaps it out); scheduling onto a
            // disabled plugin throws.
            if (!plugin.isEnabled()) return;
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                if (!success) return;
                plugin.getLogger().info("Resource pack generated: "
                        + plugin.getDataFolder().toPath().resolve("resource-pack.zip")
                        + " (SHA-1 " + getPackSha1() + ")");
                if (pushWhenDone && isActive()) {
                    for (Player online : plugin.getServer().getOnlinePlayers()) {
                        send(online);
                    }
                }
            });
        });
    }

    /** Sends the required pack to a player. */
    public void send(Player player) {
        PackData current = pack;
        if (current == null) return;        // nothing built yet
        // Starts (or restarts) this player's answer window.
        packSentAtMillis.put(player.getUniqueId(), System.currentTimeMillis());
        ResourcePackInfo info = ResourcePackInfo.resourcePackInfo(
                PACK_ID, URI.create(publicUrl), current.sha1());
        player.sendResourcePacks(ResourcePackRequest.resourcePackRequest()
                .packs(info)
                .required(true)
                .prompt(plugin.getMessages().get("pack.prompt"))
                .replace(true)
                .build());
    }

    // ------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (isActive()) {
            awaitingInitialPack.add(event.getPlayer().getUniqueId());
            send(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        packLoaded.remove(event.getPlayer().getUniqueId());
        packResolved.remove(event.getPlayer().getUniqueId());
        resolvedAtMillis.remove(event.getPlayer().getUniqueId());
        awaitingInitialPack.remove(event.getPlayer().getUniqueId());
        packSentAtMillis.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPackStatus(PlayerResourcePackStatusEvent event) {
        if (!isActive()) return;

        UUID playerId = event.getPlayer().getUniqueId();
        // Enforcement applies to the pack sent on JOIN. A later re-push (a
        // song added custom sounds) must never disconnect someone: they did
        // nothing wrong, and otherwise anyone able to download songs could
        // boot players with flaky connections at will.
        boolean initial = awaitingInitialPack.contains(playerId);

        switch (event.getStatus()) {
            case SUCCESSFULLY_LOADED -> {
                packLoaded.add(playerId);
                markResolved(playerId);
            }
            case DECLINED -> {
                markResolved(playerId);
                if (kickOnRefuse && initial) {
                    event.getPlayer().kick(plugin.getMessages().get("pack.kick-refused"));
                }
            }
            case FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED -> {
                // Resolve regardless, so the playback start-gate can't hang
                markResolved(playerId);
                if (kickOnRefuse && initial) {
                    event.getPlayer().kick(plugin.getMessages().get("pack.kick-failed"));
                } else if (!initial) {
                    plugin.getLogger().warning("Resource pack re-push failed for "
                            + event.getPlayer().getName() + "; they keep the previous pack.");
                }
            }
            default -> { /* ACCEPTED / DOWNLOADED: intermediate states */ }
        }
    }

    // ------------------------------------------------------------------
    // Custom sound naming (shared contract with playback)
    // ------------------------------------------------------------------

    /**
     * Sound event key (without namespace) for a song's custom sound file:
     * "song.&lt;sanitizedId&gt;.&lt;sanitizedPath&gt;". Playback and pack
     * generation both derive names through here, so they can never drift.
     */
    public static String customSoundEvent(String publicId, String soundFile) {
        return "song." + sanitizeKeyPart(publicId) + "." + sanitizeKeyPart(soundKeyPart(soundFile));
    }

    /**
     * The identifying part of a custom sound path: the whole relative path,
     * minus the .ogg extension, with separators normalised.
     *
     * The directories are deliberately KEPT. NBS v5 lets a song reference
     * sounds in subfolders, so "drums/kick.ogg" and "effects/kick.ogg" are two
     * different sounds; reducing both to "kick" gave them the same event, and
     * the duplicate guard in the pack builder then dropped the second one
     * silently -- the song played with one of its instruments missing and
     * nothing said why.
     *
     * sanitizeKeyPart later turns the separator into an underscore and, since
     * that transform is lossy, appends a hash of the original. So "drums/kick"
     * and a literal "drums_kick" stay distinct too.
     */
    public static String soundKeyPart(String soundFile) {
        String name = soundFile.replace('\\', '/');
        // Leading "./" or "/" carry no meaning here and would otherwise make
        // two spellings of one path look like different sounds.
        while (name.startsWith("./")) name = name.substring(2);
        while (name.startsWith("/")) name = name.substring(1);
        if (name.toLowerCase(Locale.ROOT).endsWith(".ogg")) {
            name = name.substring(0, name.length() - 4);
        }
        return name;
    }

    /**
     * Namespaced keys allow only [a-z0-9_.-]. Lowercase and replace the
     * rest; if that transform was lossy (uppercase, exotic chars), append
     * a hash suffix so distinct inputs cannot collide.
     */
    public static String sanitizeKeyPart(String input) {
        String lowered = input.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        if (lowered.equals(input)) {
            return lowered;
        }
        return lowered + "-" + Integer.toHexString(input.hashCode());
    }

    private void markResolved(UUID playerId) {
        packResolved.add(playerId);
        resolvedAtMillis.put(playerId, System.currentTimeMillis());
        awaitingInitialPack.remove(playerId);
        packSentAtMillis.remove(playerId);
    }

    // ------------------------------------------------------------------
    // Pack generation
    // ------------------------------------------------------------------

    /**
     * Resource pack format to declare, matching the client version this server
     * accepts.
     *
     * A server only admits clients of its own version, so one number is right
     * for everyone connected. It is configurable because the number changes
     * with every Minecraft release and an admin should not need a plugin
     * update to move: 84 is 26.1-26.1.2, 88 is 26.2. Players can read their
     * own with F3+V.
     */
    private int packFormat() {
        return Math.max(1, plugin.getConfig().getInt("resource-pack.pack-format", 88));
    }

    private byte[] buildPackZip() throws IOException {
        // Only the fields the target client actually reads.
        //
        // This previously wrote pack_format, min_format, max_format AND
        // supported_formats together, on the assumption that a client picks
        // the one it knows and ignores the rest. That is not how 1.21.9+
        // behaves: it reads min_format/max_format, and the extra legacy keys
        // (plus a max of 999, which is not a real format) got the pack marked
        // incompatible -- the red entry in the resource pack screen -- even
        // when the number itself matched the client exactly.
        int format = packFormat();
        Map<String, Object> packMeta = new LinkedHashMap<>();
        packMeta.put("description", "NoteblockWorldMC extended note block sounds");
        packMeta.put("min_format", format);
        packMeta.put("max_format", format);
        Map<String, Object> mcmeta = Map.of("pack", packMeta);

        Map<String, Object> sounds = new LinkedHashMap<>();
        java.util.Set<String> events = new java.util.HashSet<>();

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            putEntry(zip, "pack.mcmeta", gson.toJson(mcmeta));
            appendVanillaOctaves(zip, sounds, events);
            appendCustomSounds(zip, sounds, events);
            zip.setLevel(java.util.zip.Deflater.DEFAULT_COMPRESSION);  // text again
            putEntry(zip, "assets/nbw/sounds.json", gson.toJson(sounds));
        }
        this.generatedEvents = java.util.Set.copyOf(events);
        return buffer.toByteArray();
    }

    /**
     * Octave-shifted copies of the vanilla note block sounds.
     *
     * These MUST be real audio files: Minecraft clamps the final sound
     * pitch to [0.5, 2.0], and a pack "pitch" multiplier is folded into
     * that value BEFORE the clamp — so multipliers can never reach outside
     * the vanilla two-octave window. Shifting the sample rate does.
     */
    private void appendVanillaOctaves(ZipOutputStream zip, Map<String, Object> sounds,
                                      java.util.Set<String> events) throws IOException {
        // Ogg is already compressed; deflating it again wastes CPU on every
        // rebuild for ~0% size gain. Store audio entries uncompressed.
        zip.setLevel(java.util.zip.Deflater.NO_COMPRESSION);

        int available = 0;
        for (String instrument : INSTRUMENT_NAMES) {
            if (vanillaSounds.get(instrument) != null) available++;
        }

        List<OctaveEntry> cache = vanillaOctaveCache;
        if (cache == null || vanillaOctaveSourceCount != available) {
            cache = generateVanillaOctaves();
            vanillaOctaveCache = cache;
            vanillaOctaveSourceCount = available;
        }

        for (OctaveEntry entry : cache) {
            zip.putNextEntry(new ZipEntry("assets/nbw/sounds/" + entry.path() + ".ogg"));
            zip.write(entry.bytes());
            zip.closeEntry();
            sounds.put(entry.event(), Map.of("sounds",
                    java.util.List.of(Map.of("name", "nbw:" + entry.path()))));
            events.add(entry.event());
        }
    }

    /** The slow path: read the vanilla sounds and rate-shift every variant. */
    private List<OctaveEntry> generateVanillaOctaves() throws IOException {
        List<OctaveEntry> entries = new java.util.ArrayList<>();
        java.util.List<String> skipped = new java.util.ArrayList<>();
        for (String instrument : INSTRUMENT_NAMES) {
            Path source = vanillaSounds.get(instrument);
            if (source == null) continue;                 // fold instead
            byte[] original = Files.readAllBytes(source);
            if (!OggTools.isOgg(original)) continue;

            for (Map.Entry<String, Integer> variant : OCTAVE_VARIANTS.entrySet()) {
                byte[] shifted = OggTools.shiftOctaves(original, variant.getValue());
                if (shifted == null) {                    // rate outside the allowed range
                    skipped.add(instrument + variant.getKey());
                    continue;
                }
                String event = instrument + variant.getKey();
                entries.add(new OctaveEntry(event, "octaves/" + event, shifted));
            }
        }
        if (!entries.isEmpty()) {
            plugin.getLogger().info("Generated " + entries.size()
                    + " octave-shifted note block sound(s) (cached for future rebuilds).");
        }
        if (!skipped.isEmpty()) {
            plugin.getLogger().warning(skipped.size() + " octave variant(s) could not be built "
                    + "(source sample rate out of range): " + String.join(", ", skipped)
                    + ". Notes needing them fold into the octave below.");
        }
        return entries;
    }

    /**
     * Injects downloaded songs' custom instrument sounds, plus an
     * octave-shifted copy of each (same reasoning as the vanilla ones).
     *
     * NOTE: noteblock.world names these files by Mojang asset hash with NO
     * extension — filtering by "*.ogg" finds nothing. Content is sniffed
     * via the OggS magic bytes instead.
     */
    private void appendCustomSounds(ZipOutputStream zip, Map<String, Object> sounds,
                                    java.util.Set<String> events) throws IOException {
        zip.setLevel(java.util.zip.Deflater.NO_COMPRESSION);   // Ogg: see above
        var downloads = plugin.getDownloadManager();
        if (downloads == null) return;

        long budget = maxCustomSoundBytes;
        int fileCount = 0;
        // Minecraft resource packs accept Ogg Vorbis only. NBS 3.12 re-added
        // .wav custom instruments, so a song can now carry audio we simply
        // cannot ship -- count it and say so, rather than letting those notes
        // fall silent with no explanation anywhere.
        int nonAudio = 0;

        for (String publicId : downloads.getDownloadedIds()) {
            Path soundsDir = downloads.getSongDirectory(publicId).resolve("sounds");
            if (!Files.isDirectory(soundsDir)) continue;

            List<Path> files;
            try (Stream<Path> walk = Files.walk(soundsDir)) {
                files = walk.filter(Files::isRegularFile).sorted().toList();
            }

            for (Path file : files) {
                byte[] bytes;
                try {
                    bytes = Files.readAllBytes(file);
                } catch (IOException e) {
                    // May be mid-removal on another thread; skip this file
                    plugin.getLogger().warning("Skipping unreadable pack sound " + file);
                    continue;
                }
                if (!OggTools.isOgg(bytes)) {             // not Ogg Vorbis
                    nonAudio++;
                    continue;
                }
                if (bytes.length > budget) {
                    plugin.getLogger().warning("Resource pack custom-sound budget reached; "
                            + "skipping remaining sounds (raise resource-pack.max-custom-sounds-mb).");
                    return;
                }
                budget -= bytes.length;
                fileCount++;

                // Relative to the song's sounds folder, so a file in a
                // subdirectory keeps the directory in its key. Passing only
                // getFileName() here was what made two same-named files in
                // different folders collapse onto one event.
                String relative = soundsDir.relativize(file).toString();
                String event = customSoundEvent(publicId, relative);
                String path = "songs/" + sanitizeKeyPart(publicId) + "/"
                        + sanitizeKeyPart(soundKeyPart(relative));

                // Distinct paths now yield distinct events, so this should no
                // longer fire; it stays because a duplicate would make
                // ZipOutputStream throw and kill the entire rebuild.
                if (!events.add(event)) {
                    plugin.getLogger().warning("Two custom sounds in " + publicId
                            + " map to the same event (" + event + "); skipping " + relative);
                    continue;
                }

                zip.putNextEntry(new ZipEntry("assets/nbw/sounds/" + path + ".ogg"));
                zip.write(bytes);
                zip.closeEntry();
                sounds.put(event, Map.of("sounds",
                        java.util.List.of(Map.of("name", "nbw:" + path))));

                for (Map.Entry<String, Integer> variant : OCTAVE_VARIANTS.entrySet()) {
                    byte[] shifted = OggTools.shiftOctaves(bytes, variant.getValue());
                    if (shifted == null) continue;
                    // Octave variants are FULL COPIES (shiftOctaves clones the
                    // file and rewrites its sample rate), so each sound really
                    // costs up to 7x its own size. Counting only the original
                    // let the pack reach ~7x the configured budget -- held in
                    // memory AND downloaded by every client.
                    if (shifted.length > budget) {
                        plugin.getLogger().warning("Resource pack custom-sound budget reached "
                                + "while adding octave variants; skipping the rest "
                                + "(raise resource-pack.max-custom-sounds-mb).");
                        return;
                    }
                    budget -= shifted.length;
                    String variantEvent = event + variant.getKey();
                    String variantPath = path + variant.getKey();
                    zip.putNextEntry(new ZipEntry("assets/nbw/sounds/" + variantPath + ".ogg"));
                    zip.write(shifted);
                    zip.closeEntry();
                    sounds.put(variantEvent, Map.of("sounds",
                            java.util.List.of(Map.of("name", "nbw:" + variantPath))));
                    events.add(variantEvent);
                }
            }
        }
        if (fileCount > 0) {
            plugin.getLogger().info("Resource pack includes " + fileCount
                    + " custom instrument sound(s).");
        }
        if (nonAudio > 0) {
            // Names the cause explicitly: otherwise this looks like the plugin
            // losing sounds, when the file simply is not a format Minecraft
            // can play.
            plugin.getLogger().warning(nonAudio + " custom instrument file(s) were skipped"
                    + " because they are not Ogg Vorbis. Minecraft resource packs support"
                    + " Ogg only, so notes using them will be silent. Note Block Studio 3.12"
                    + " allows .wav custom instruments; re-save those sounds as .ogg.");
        }
    }

    /**
     * Call after the song library changes in a way that affects the pack
     * (a download brought custom sounds). Rebuilds off-thread, then
     * re-pushes to everyone online — clearing resolution state so the
     * playback start-gate naturally waits for clients to finish reloading.
     */
    /**
     * Call after a custom-sound song is REMOVED.
     *
     * Strict mode (repush-on-remove: true): identical to a library
     * addition — rebuild, reset resolution, re-push — so online players
     * never keep stale sounds, at the cost of one client reload each.
     *
     * Lenient mode: quiet rebuild only; new joiners get the lean pack,
     * online players keep a harmless superset until the next push.
     */
    public void onLibraryShrunk() {
        if (repushOnRemove && isActive()) {
            onLibraryChanged();
            return;
        }
        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                rebuild();
            } catch (IOException e) {
                plugin.getLogger().warning("Resource pack rebuild after removal failed: " + e);
            }
        });
    }

    public void onLibraryChanged() {
        if (!isActive()) return;

        // Invalidate IMMEDIATELY (synchronously), before the rebuild is even
        // scheduled: from this instant every client's pack is stale, so the
        // playback start-gate must hold songs until the new pack resolves.
        // Clearing this only after the async rebuild would leave a window in
        // which a song could start against the old pack.
        packLoaded.clear();
        packResolved.clear();
        resolvedAtMillis.clear();
        invalidatedAtMillis = System.currentTimeMillis();

        // Coalesce bursts (e.g. several songs downloaded back to back) into a
        // single rebuild + re-push, so players don't eat a client reload per
        // song. The start-gate already holds playback while we wait.
        if (!rebuildPending.compareAndSet(false, true)) return;
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin,
                () -> { rebuildPending.set(false); doRebuildAndPush(); },
                Math.max(0, rebuildDebounceTicks));
    }

    private void doRebuildAndPush() {
        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                rebuild();
            } catch (IOException e) {
                // Do NOT return: onLibraryChanged already cleared everyone's
                // resolution, so bailing out here left every online player
                // unable to hear anything. rebuild() publishes the new pack
                // only after building it successfully, so `pack` still holds
                // the last good one -- push that instead, and clients resolve
                // against a pack that works even though it lacks the newest
                // song's sounds.
                plugin.getLogger().warning("Resource pack rebuild failed: " + e
                        + "; re-sending the previous pack so players are not left silent.");
            }
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                for (Player online : plugin.getServer().getOnlinePlayers()) {
                    send(online);
                }
            });
        });
    }

    private static void putEntry(ZipOutputStream zip, String path, String content)
            throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
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
}
