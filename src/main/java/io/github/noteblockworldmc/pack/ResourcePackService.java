package io.github.noteblockworldmc.pack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import io.github.noteblockworldmc.NoteblockWorldMCPlugin;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
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
 * Generates and hosts a resource pack containing rate-shifted vanilla samples
 * and custom instrument audio. Original samples plus up to three shifted
 * octaves either side cover the NBS pitch range with Minecraft's runtime pitch.
 *
 * Immutable pack files, SHA-1, and the generated event set are published together. Rebuilds
 * run off-thread; per-push IDs keep stale resource-pack acknowledgements from
 * opening playback's pack gate. An inspection ZIP is also written to disk.
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
    private int port;
    private String bindAddress;
    private volatile int configuredPackFormat;
    private boolean settingsLoaded;
    private final java.util.concurrent.atomic.AtomicBoolean refreshSourceCaches = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean fetchingVanilla =
            new java.util.concurrent.atomic.AtomicBoolean();
    /**
     * Both are consulted per HTTP request and nothing is constructed from
     * them, so they are re-read with the other hot settings rather than
     * forcing a restart to change a number.
     */
    private volatile int rateLimitPerMinute;
    private volatile int maxConcurrentDownloads;
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

    private volatile PackHttpServer httpServer;
    private final Map<String, long[]> requestCounts = new java.util.HashMap<>();
    private record PackData(Path file, long size, String sha1, Set<String> events, Map<String, Double> seconds) {}
    private final Object buildLock = new Object();
    private final Object rateLimitLock = new Object();
    private final SampleCache customSamples = new SampleCache(32L * 1024 * 1024);
    private org.bukkit.scheduler.BukkitTask cleanupTask;
    private final java.util.concurrent.atomic.AtomicBoolean cleanupPending = new java.util.concurrent.atomic.AtomicBoolean();
    private boolean storagePrepared;
    private record Installed(UUID request, String sha1, Set<String> events, Map<String, Double> seconds) {}
    private final Map<String, PackData> generations = new ConcurrentHashMap<>();
    private final Map<String, Long> advertisedAt = new ConcurrentHashMap<>();
    private final Map<UUID, Installed> installed = new ConcurrentHashMap<>();
    private final Map<UUID, Installed> requested = new ConcurrentHashMap<>();
    private static final long RETENTION_MS = 600_000;
    private static final long GENERATION_BYTES = 256L * 1024 * 1024;
    private static final long MAX_PACK_BYTES = 96L * 1024 * 1024;
    private final Map<String, Double> buildingSeconds = new LinkedHashMap<>();
    private final Object generationLock = new Object();
    private volatile PackData pack;
    /** Pins an existing generation while a builder prepares to publish it again. Guarded by generationLock. */
    private PackData publishing;
    private final VanillaSounds vanillaSounds;

    /** Each push has its own ID so old or unrelated status events cannot unlock playback. */
    private final Map<UUID, UUID> requestedPackIds = new ConcurrentHashMap<>();
    private final Map<UUID, String> requestedUrls = new ConcurrentHashMap<>();

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
        if (value.toLowerCase(Locale.ROOT).startsWith("auto:")) {
            try { port = Integer.parseInt(value.substring(5)); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Expected auto:PORT", e); }
            if (port < 1 || port > 65535) throw new IllegalArgumentException("auto:PORT must be between 1 and 65535");
        }
        String detected = detectOutboundIp();
        if (detected == null) {
            plugin.getLogger().warning("resource-pack.public-url is \"auto\" but this server's"
                    + " outbound IP could not be detected. Set public-url to a real address.");
            return "";
        }
        String host = detected.contains(":") ? "[" + detected + "]" : detected;
        String url = "http://" + host + ":" + port + "/pack.zip";
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

    /** Prepares a replacement listener without changing any live setting; bind failure leaves the old service intact. */
    public Runnable prepareSettings(org.bukkit.configuration.file.FileConfiguration config) {
        io.github.noteblockworldmc.util.SettingsValidator.validate(config);
        int nextPort = config.getInt("resource-pack.port", 8194);
        String nextBind = config.getString("resource-pack.bind-address", "0.0.0.0").trim();
        if (nextBind.isEmpty()) throw new IllegalArgumentException("resource-pack.bind-address is empty");
        String nextUrl = resolvePublicUrl(config.getString("resource-pack.public-url", ""), nextPort);
        validatePublicUrl(nextUrl);
        boolean nextEnabled = config.getBoolean("resource-pack.enabled", false);
        boolean wantsServer = nextEnabled && !nextUrl.isBlank();
        boolean socketChanged = nextPort != port || !java.util.Objects.equals(nextBind, bindAddress);
        boolean wasActive = isActive();
        String oldUrl = publicUrl;
        int format = resolvePackFormat(config.get("resource-pack.pack-format"), serverVersion());
        long budget = config.getLong("resource-pack.max-custom-sounds-mb", 64) * 1024L * 1024;
        boolean contentChanged = budget != maxCustomSoundBytes || format != configuredPackFormat;
        PackHttpServer prepared;
        try {
            prepared = settingsLoaded && wantsServer && (!wasActive || socketChanged)
                    ? bindReplacing(new InetSocketAddress(nextBind, nextPort)) : httpServer;
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Could not bind resource-pack server; previous settings retained", e);
        }
        return () -> {
            enabled = nextEnabled; port = nextPort; bindAddress = nextBind; publicUrl = nextUrl;
            configuredPackFormat = format; maxCustomSoundBytes = budget;
            warnAboutPackFormat(config.get("resource-pack.pack-format"), format);
            kickOnRefuse = config.getBoolean("resource-pack.kick-on-refuse", true);
            repushOnRemove = config.getBoolean("resource-pack.repush-on-remove", false);
            rebuildDebounceTicks = 20L * config.getInt("resource-pack.rebuild-debounce-seconds", 3);
            settleDelayMillis = config.getLong("resource-pack.settle-delay-ms", 2000);
            rateLimitPerMinute = config.getInt("resource-pack.rate-limit-per-minute", 30);
            maxConcurrentDownloads = config.getInt("resource-pack.max-concurrent-downloads", 16);
            if (!settingsLoaded) { settingsLoaded = true; return; }
            PackHttpServer previous = httpServer;
            httpServer = wantsServer ? prepared : null;
            if (previous != null && previous != httpServer) previous.close();
            if (contentChanged || !wasActive && isActive()) onLibraryChanged();
            else if (isActive() && (socketChanged || !java.util.Objects.equals(oldUrl, publicUrl))) {
                for (Player player : plugin.getServer().getOnlinePlayers()) send(player);
            }
        };
    }
    public void loadHotSettings() { prepareSettings(plugin.getConfig()).run(); }

    private static void validatePublicUrl(String value) {
        if (value.isBlank()) return;
        URI uri = URI.create(value);
        if (uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getUserInfo() != null
                || uri.getPort() == 0 || uri.getPort() > 65535
                || !"/pack.zip".equals(uri.getPath()) || uri.getHost() == null || !("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("resource-pack.public-url must be auto, empty, or an absolute HTTP(S) URL"
                    + " whose path is exactly /pack.zip, with no query or credentials");
        }
    }

    /** Explicit reload also refreshes manually supplied audio and retries failed asset lookups. */
    public void reloadContent() {
        refreshSourceCaches.set(true);
        vanillaSounds.resetLookupFailures();
        onLibraryChanged();
        if (plugin.getConfig().getBoolean("resource-pack.download-vanilla-sounds", true)) {
            fetchVanillaSoundsAsync();
        }
    }

    /** Builds an inspection copy even with hosting disabled. */
    public void start() {
        // Players already online across a plugin reload never fire a join.
        for (Player online : org.bukkit.Bukkit.getOnlinePlayers()) trackSource(online);
        if (enabled && !publicUrl.isBlank()) {
            try { bindServer(); }
            catch (IOException | RuntimeException e) {
                plugin.getLogger().severe("Could not start the resource-pack server: " + e);
            }
        } else if (enabled) {
            plugin.getLogger().warning("Resource-pack hosting needs a reachable public-url.");
        }
        if (cleanupTask == null) cleanupTask = org.bukkit.Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (plugin.isStopping() || !cleanupPending.compareAndSet(false, true)) return;
            plugin.getWorkPool().run(() -> {
                try { expireGenerations(System.currentTimeMillis()); }
                catch (IOException e) { throw new java.util.concurrent.CompletionException(e); }
            }).whenComplete((unused, error) -> {
                cleanupPending.set(false);
                if (error != null && !plugin.isStopping()) plugin.getLogger().warning("Pack cleanup failed: " + error);
            });
        }, 1200L, 1200L);
        buildPackAsync(isActive());
        if (plugin.getConfig().getBoolean("resource-pack.download-vanilla-sounds", true)) {
            fetchVanillaSoundsAsync();
        }
    }

    private PackHttpServer newServer(InetSocketAddress address) throws IOException {
        return new PackHttpServer(address, this::lookupPack, this::isRateLimited, this::isTrustedSource,
                () -> maxConcurrentDownloads, 60_000);
    }

    /**
     * Binds the listener a reload asks for while the current one keeps serving.
     *
     * Moving between the wildcard and one interface on the SAME port (the
     * 0.0.0.0 to 127.0.0.1 reverse-proxy switch config.yml recommends) can
     * never overlap: the OS refuses the second bind while the first listens,
     * so that reload always failed. For that case only, the old listener is
     * released first; if the new address still cannot be bound, the old one
     * is restored and the reload is refused as before.
     */
    private PackHttpServer bindReplacing(InetSocketAddress target) throws IOException {
        try {
            return newServer(target);
        } catch (java.net.BindException conflict) {
            PackHttpServer current = httpServer;
            if (current == null || target.getPort() != port) throw conflict;
            current.close();
            try {
                return newServer(target);
            } catch (IOException | RuntimeException retry) {
                conflict.addSuppressed(retry);
                try {
                    httpServer = newServer(new InetSocketAddress(bindAddress, port));
                } catch (IOException | RuntimeException restore) {
                    httpServer = null;
                    conflict.addSuppressed(restore);
                    plugin.getLogger().severe("The previous resource-pack listener on " + bindAddress + ":" + port
                            + " could not be restored; fix the address and run /nbw reload: " + restore);
                }
                throw conflict;
            }
        }
    }

    /** A new listener is prepared before the old endpoint is released. */
    private void bindServer() throws IOException {
        PackHttpServer replacement = newServer(new InetSocketAddress(bindAddress, port));
        PackHttpServer previous = httpServer;
        httpServer = replacement;
        if (previous != null) previous.close();
        plugin.getLogger().info("Resource pack served on " + bindAddress + ":" + port
                + " using " + replacement.name());
    }

    private FileChannel lookupPack(String target) {
        // Only a content-addressed generation is served; no filesystem path comes from HTTP.
        String requestedSha;
        if ("/pack.zip".equals(target)) {
            PackData current = pack;
            requestedSha = current == null ? null : current.sha1();
        } else if (target.matches("/pack\\.zip\\?v=[0-9a-f]{40}")) {
            requestedSha = target.substring(target.indexOf("?v=") + 3);
        } else {
            return null;
        }
        if (requestedSha == null) return null;
        synchronized (generationLock) {
            PackData found = generations.get(requestedSha);
            if (found == null) return null;
            try { return FileChannel.open(found.file(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS); }
            catch (IOException e) { return null; }
        }
    }

    /**
     * Rate-limit keys of online players' addresses, counted per player.
     * Updated on the main thread, read by the HTTP threads.
     */
    private final Map<UUID, String> playerSources = new ConcurrentHashMap<>();
    private final Map<String, Integer> trustedSources = new ConcurrentHashMap<>();

    private void trackSource(Player player) {
        java.net.InetSocketAddress address = player.getAddress();
        if (address == null || address.getAddress() == null) return;
        String key = rateLimitKey(address.getAddress().getHostAddress());
        String previous = playerSources.put(player.getUniqueId(), key);
        if (key.equals(previous)) return;
        if (previous != null) untrackKey(previous);
        trustedSources.merge(key, 1, Integer::sum);
    }

    private void untrackSource(UUID playerId) {
        String key = playerSources.remove(playerId);
        if (key != null) untrackKey(key);
    }

    private void untrackKey(String key) {
        trustedSources.computeIfPresent(key, (k, n) -> n <= 1 ? null : n - 1);
    }

    /**
     * Online players, and anything on this machine or the local network
     * (a reverse proxy, a container port-forwarder, a LAN), may use the
     * whole download pool; strangers share part of it.
     */
    private boolean isTrustedSource(String ip) {
        if (isLocalNetwork(ip)) return true;
        return trustedSources.containsKey(rateLimitKey(ip));
    }

    /**
     * Loopback, private (10/8, 172.16/12, 192.168/16, fc00::/7) and
     * link-local sources. A TCP peer cannot fake one of these from the
     * internet; seeing one usually means a proxy or NAT that hides the real
     * client, so per-address limits would lump every player together.
     */
    static boolean isLocalNetwork(String ip) {
        if (ip == null) return false;
        try {
            java.net.InetAddress address = java.net.InetAddress.ofLiteral(ip.replaceFirst("%.*$", ""));
            byte[] bytes = address.getAddress();
            return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                    || (bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Fixed-window limiter, per source IPv4 address or IPv6 /64. Local and
     * private sources are exempt: behind a reverse proxy or container
     * port-forwarder every player's download arrives from one such address,
     * so counting it as one client refused the 31st download in a minute --
     * and with kick-on-refuse, kicked that player.
     */
    private boolean isRateLimited(String ip) {
        if (rateLimitPerMinute <= 0 || isLocalNetwork(ip)) return false;
        ip = rateLimitKey(ip);
        synchronized (rateLimitLock) {
            long now = System.currentTimeMillis();
            if (!requestCounts.containsKey(ip) && requestCounts.size() >= 10_000) {
                requestCounts.entrySet().removeIf(e -> now - e.getValue()[0] > 60_000);
                if (requestCounts.size() >= 10_000) return true;
            }
            long[] window = requestCounts.computeIfAbsent(ip, key -> new long[]{now, 0});
            if (now - window[0] > 60_000) { window[0] = now; window[1] = 0; }
            return ++window[1] > rateLimitPerMinute;
        }
    }

    /**
     * One IPv6 subscriber is normally handed a whole /64, so keying by the
     * full address let a single host rotate through fresh addresses: it was
     * never limited, and after 10,000 addresses in a minute the full table
     * turned every legitimate newcomer away. Counting the /64 prefix keeps
     * one subscriber to one bucket.
     */
    static String rateLimitKey(String ip) {
        return PackHttpServer.sourceKey(ip);
    }

    /**
     * Full shutdown, for plugin disable.
     *
     * Removes only this instance's installed/pending client packs, including
     * during a full plugin reload. Hot configuration changes use stopServer().
     */
    public void stop() {
        if (cleanupTask != null) { cleanupTask.cancel(); cleanupTask = null; }
        stopServer();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Set<UUID> owned = new java.util.HashSet<>();
            Installed loaded = installed.get(player.getUniqueId());
            Installed pending = requested.get(player.getUniqueId());
            if (loaded != null) owned.add(loaded.request());
            if (pending != null) owned.add(pending.request());
            for (UUID id : owned) {
                try { player.removeResourcePacks(id); }
                catch (RuntimeException e) { plugin.getLogger().warning("Could not remove music pack for " + player.getName() + ": " + e); }
            }
        }
        installed.clear(); requested.clear(); requestedPackIds.clear(); requestedUrls.clear();
        packLoaded.clear(); packResolved.clear(); resolvedAtMillis.clear();
        awaitingInitialPack.clear(); packSentAtMillis.clear();
        vanillaSounds.close();
        customSamples.clear();
    }

    /**
     * Releases the port and its thread pool, leaving the rest of the service
     * usable so the pack can be switched back on without a restart.
     *
     * Players already holding the pack keep it until they reconnect; there is
     * no removal request here, and taking it away mid-session
     * would silence any song using custom sounds.
     */
    private void stopServer() {
        PackHttpServer previous = httpServer; httpServer = null;
        if (previous != null) previous.close();
    }

    /**
     * Fetches the vanilla sounds off-thread, then rebuilds and re-pushes so
     * players who joined in the meantime get the octave-enabled pack.
     */
    private void fetchVanillaSoundsAsync() {
        if (!fetchingVanilla.compareAndSet(false, true)) return;
        plugin.getWorkPool().run(() -> {
            try {
                if (vanillaSounds.isComplete()) return;
                int available = vanillaSounds.ensureDownloaded();
                if (available == 0) {
                    plugin.getLogger().warning("No vanilla note-block samples available; extended notes will fold.");
                    return;
                }
                // A verification pass that kept every cached file changes nothing in the pack.
                if (!vanillaSounds.consumeChanged()) return;
                refreshSourceCaches.set(true);
                if (plugin.isEnabled()) {
                    plugin.runMain(this::onLibraryChanged);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Could not fetch vanilla note-block sounds: " + e);
            }
        }).whenComplete((unused, error) -> {
            // Admission can fail before the worker runs, so worker-only
            // cleanup would permanently suppress every subsequent retry.
            fetchingVanilla.set(false);
            if (error != null && !plugin.isStopping())
                plugin.getLogger().warning("Vanilla sound fetch was not completed; retry /nbw reload: " + error);
        });
    }

    public boolean isActive() {
        return enabled && httpServer != null;
    }

    /** Read/validate once per source revision; callers share its measured duration. */
    public double sampleSeconds(int instrument) {
        if (instrument < 0 || instrument >= INSTRUMENT_NAMES.length) return -1;
        return vanillaSounds.sampleSeconds(INSTRUMENT_NAMES[instrument]);
    }

    /** True if the pack actually contains this sound event. */
    public boolean hasEvent(String event) {
        PackData current = pack;
        return current != null && event != null && current.events().contains(event);
    }

    /** Availability and duration belong to the generation this particular listener acknowledged. */
    public boolean hasEvent(UUID playerId, String event) {
        Installed current = installed.get(playerId);
        return current != null && current.events().contains(event);
    }
    public double eventSeconds(UUID playerId, String event) {
        Installed current = installed.get(playerId);
        return current == null ? -1 : current.seconds().getOrDefault(event, -1.0);
    }
    public Map<String, Double> eventDurations(UUID listener) {
        if (listener == null) { PackData current = pack; return current == null ? Map.of() : current.seconds(); }
        Installed current = installed.get(listener); return current == null ? Map.of() : current.seconds();
    }

    public double eventSeconds(String event) {
        PackData current = pack;
        return current == null ? -1 : current.seconds().getOrDefault(event, -1.0);
    }

    public String getPackSha1() {
        PackData current = pack;
        return current == null ? null : current.sha1();
    }

    public int getPackSize() {
        PackData current = pack;
        return current == null ? 0 : (int) current.size();
    }

    public int getEventCount() {
        PackData current = pack;
        return current == null ? 0 : current.events().size();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getPublicUrl() {
        return publicUrl;
    }

    /** The address actually bound by the listener, for diagnosing host firewall/NAT issues. */
    public String getBoundAddress() {
        PackHttpServer current = httpServer;
        if (current == null) return "(inactive)";
        try {
            InetSocketAddress address = current.address();
            return address.getHostString() + ":" + address.getPort();
        } catch (IOException e) {
            return "(unavailable)";
        }
    }

    /** Transport selected for the active listener, useful when diagnosing a host-specific issue. */
    public String getTransportName() {
        PackHttpServer current = httpServer;
        return current == null ? "(inactive)" : current.name();
    }

    /** URL a browser can use to download the current pack; Minecraft adds its hash automatically. */
    public String getDownloadUrl() {
        PackData current = pack;
        if (current == null || publicUrl.isBlank()) return "(unavailable)";
        return publicUrl + "?v=" + current.sha1();
    }

    /** True once this player's client confirmed the pack is loaded. */
    public boolean isPackLoaded(UUID playerId) {
        return installed.containsKey(playerId);
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
        return settled(playerId, true);
    }

    /**
     * Whether a song that is ALREADY playing should reach this player.
     *
     * Unlike {@link #isSettled}, a rebuild that has been requested but not yet
     * pushed does not count: the client still has the pack it had a moment
     * ago, so muting it only cut every running song for the debounce, build
     * and settle time -- even on a /nbw reload that changed nothing. A push
     * that is actually outstanding still mutes, since the client is
     * reloading its resources.
     */
    public boolean canHear(UUID playerId) {
        return settled(playerId, false);
    }

    private boolean settled(UUID playerId, boolean holdForRebuild) {
        if (!isActive()) return true;
        long now = System.currentTimeMillis();
        long invalidated = invalidatedAtMillis;
        boolean rebuildComing = holdForRebuild && invalidated != 0 && now - invalidated < RESOLUTION_TIMEOUT_MS;

        Long resolvedAt = resolvedAtMillis.get(playerId);
        if (resolvedAt != null) {
            // A start waits for a requested rebuild the player has not yet
            // resolved; resolution itself is no longer wiped when one is requested.
            if (rebuildComing && resolvedAt < invalidated) return false;
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

        if (rebuildComing) {
            // The library changed and the debounced rebuild has not pushed
            // yet. Hold, so the song starts with the pack it expects.
            return false;
        }

        // Nothing outstanding and no rebuild coming: there is nothing to wait
        // for. Letting them hear the song without the pack beats silence.
        return true;
    }

    /** Serializes builds without holding the HTTP admission or generation lookup locks. */
    public void rebuild() throws IOException {
        synchronized (buildLock) {
            if (refreshSourceCaches.getAndSet(false)) {
                customSamples.invalidateFiles();
                plugin.getPlaybackManager().invalidateSampleCaches();
            }
            PackData built = buildPackZip();
            boolean published = false;
            Path inspection = null;
            try {
                if (plugin.isStopping()) throw new IOException("Plugin is stopping");
                expireGenerations(System.currentTimeMillis());
                PackData existing;
                synchronized (generationLock) {
                    existing = generations.get(built.sha1());
                    long retained = generations.values().stream().mapToLong(PackData::size).sum();
                    if (existing == null && (retained + built.size() > GENERATION_BYTES || generations.size() >= 64))
                        throw new IOException("Pack generation retention budget is full; retry in ten minutes");
                    publishing = existing;
                }
                if (existing != null) {
                    Files.deleteIfExists(built.file()); built = existing;
                } else {
                    Path destination = built.file().resolveSibling(built.sha1() + ".zip");
                    moveReplacing(built.file(), destination);
                    built = new PackData(destination, built.size(), built.sha1(), built.events(), built.seconds());
                }
                // Independent inspection copy: editing it can never alter a served generation.
                Path packFile = plugin.getDataFolder().toPath().resolve("resource-pack.zip");
                inspection = Files.createTempFile(packFile.getParent(), "resource-pack-", ".tmp");
                Files.copy(built.file(), inspection, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                moveReplacing(inspection, packFile);
                if (plugin.isStopping()) throw new IOException("Plugin is stopping");
                synchronized (generationLock) {
                    generations.putIfAbsent(built.sha1(), built);
                    advertisedAt.putIfAbsent(built.sha1(), System.currentTimeMillis());
                    pack = generations.get(built.sha1());
                    published = true;
                }
                plugin.getPlaybackManager().refreshSampleTimings();
            } finally {
                synchronized (generationLock) { publishing = null; }
                if (inspection != null) Files.deleteIfExists(inspection);
                if (!published && !generations.containsKey(built.sha1())) Files.deleteIfExists(built.file());
            }
        }
    }

    /** Current URLs remain available; open transfers keep their file handle until completion. */
    private void expireGenerations(long now) throws IOException {
        synchronized (generationLock) {
            for (var entry : generations.entrySet()) {
                if (entry.getValue() == pack || entry.getValue() == publishing
                        || now - advertisedAt.getOrDefault(entry.getKey(), 0L) < RETENTION_MS) continue;
                // On platforms that forbid deleting open files, leave the entry and retry next time.
                try { Files.deleteIfExists(entry.getValue().file()); }
                catch (IOException e) { continue; }
                generations.remove(entry.getKey(), entry.getValue());
                advertisedAt.remove(entry.getKey());
            }
        }
    }

    private static void moveReplacing(Path from, Path to) throws IOException {
        try { Files.move(from, to, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path packDirectory() throws IOException {
        Path directory = plugin.getDataFolder().toPath().resolve("pack-generations");
        if (Files.isSymbolicLink(directory)) throw new IOException("Pack generation directory must not be a symlink");
        Files.createDirectories(directory);
        if (!storagePrepared) {
            try (Stream<Path> files = Files.list(directory)) {
                for (Path file : files.filter(f -> f.getFileName().toString().matches("[0-9a-f]{40}\\.zip|building-.*\\.tmp")).toList()) {
                    Files.deleteIfExists(file);
                }
            }
            storagePrepared = true;
        }
        return directory;
    }

    /**
     * Builds and publishes the pack on a worker, then sends it on the main thread.
     *
     * Mirrors what doRebuildAndPush already does for runtime library changes;
     * startup was the one path still doing this work inline.
     *
     * @param pushWhenDone send the finished pack to everyone already online,
     *                     which matters for /reload and plugin swaps where no
     *                     join event will fire
     */
    private void buildPackAsync(boolean pushWhenDone) { requestRebuild(pushWhenDone); }

    /** Vanilla sound cache used when an import asks to recover a Minecraft sample. */
    public VanillaSounds getVanillaSounds() {
        return vanillaSounds;
    }

    /** Sends a pack; only enforced initial join attempts are marked required. */
    public void send(Player player) {
        PackData current;
        synchronized (generationLock) {
            current = pack;
            if (current != null) advertisedAt.put(current.sha1(), System.currentTimeMillis());
        }
        if (current == null || !isActive() || plugin.isStopping()) return;
        UUID playerId = player.getUniqueId();
        String url = publicUrl + "?v=" + current.sha1();
        Installed existing = installed.get(playerId);
        if (existing != null && existing.sha1().equals(current.sha1())) {
            // Withdraw obsolete work before removing it on the client: even a
            // late terminal status must not replace this installed generation.
            requestedPackIds.remove(playerId);
            requestedUrls.remove(playerId);
            Installed obsolete = requested.remove(playerId);
            if (obsolete != null && !obsolete.request().equals(existing.request()))
                player.removeResourcePacks(obsolete.request());
            // Nothing new reaches the client, so an existing resolution keeps
            // its time: restarting the settle delay here muted listeners again.
            if (resolvedAtMillis.containsKey(playerId)) {
                awaitingInitialPack.remove(playerId); packSentAtMillis.remove(playerId);
            } else markResolved(playerId);
            return;
        }
        Installed pending = requested.get(playerId);
        Long sentAt = packSentAtMillis.get(playerId);
        if (pending != null && pending.sha1().equals(current.sha1())
                && url.equals(requestedUrls.get(playerId)) && sentAt != null
                && System.currentTimeMillis() - sentAt < RESOLUTION_TIMEOUT_MS
                && !packResolved.contains(playerId)) return;
        UUID requestId = UUID.randomUUID();
        Installed superseded = requested.put(playerId, new Installed(requestId, current.sha1(), current.events(), current.seconds()));
        requestedPackIds.put(playerId, requestId);
        requestedUrls.put(playerId, url);
        packLoaded.remove(playerId);
        packResolved.remove(playerId);
        resolvedAtMillis.remove(playerId);
        packSentAtMillis.put(playerId, System.currentTimeMillis());
        ResourcePackInfo info = ResourcePackInfo.resourcePackInfo(
                requestId, URI.create(url), current.sha1());
        player.sendResourcePacks(ResourcePackRequest.resourcePackRequest()
                .packs(info)
                .required(kickOnRefuse && awaitingInitialPack.contains(playerId))
                .prompt(plugin.getMessages().get("pack.prompt"))
                .replace(false)
                .build());
        // Withdraw what the new pack replaces only AFTER pushing it. The
        // client defers its resource reload until pending downloads finish,
        // so these removals join the single reload that applies the new pack.
        // Removing the old pack once the new one had loaded (as before) made
        // the client reload a second time: two loading screens per update.
        // replace(true) would also do it in one step, but it would remove
        // packs that belong to the server or to other plugins.
        if (superseded != null && (existing == null || !superseded.request().equals(existing.request())))
            player.removeResourcePacks(superseded.request());
        if (existing != null) {
            // The client no longer holds it, so it must not count as installed.
            installed.remove(playerId, existing);
            player.removeResourcePacks(existing.request());
        }
    }

    // ------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        trackSource(event.getPlayer());
        if (isActive()) {
            awaitingInitialPack.add(event.getPlayer().getUniqueId());
            send(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        untrackSource(event.getPlayer().getUniqueId());
        installed.remove(event.getPlayer().getUniqueId());
        requested.remove(event.getPlayer().getUniqueId());
        requestedPackIds.remove(event.getPlayer().getUniqueId());
        requestedUrls.remove(event.getPlayer().getUniqueId());
        packLoaded.remove(event.getPlayer().getUniqueId());
        packResolved.remove(event.getPlayer().getUniqueId());
        resolvedAtMillis.remove(event.getPlayer().getUniqueId());
        awaitingInitialPack.remove(event.getPlayer().getUniqueId());
        packSentAtMillis.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPackStatus(PlayerResourcePackStatusEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        if (!event.getID().equals(requestedPackIds.get(playerId))) return;
        // Enforcement applies to the pack sent on JOIN. A later re-push (a
        // song added custom sounds) must never disconnect someone: they did
        // nothing wrong, and otherwise anyone able to download songs could
        // boot players with flaky connections at will.
        boolean initial = awaitingInitialPack.contains(playerId);

        switch (event.getStatus()) {
            case SUCCESSFULLY_LOADED -> {
                Installed next = requested.get(playerId);
                if (next == null) return;
                // The previous generation was withdrawn when this one was
                // pushed; removing anything here would trigger another reload.
                installed.put(playerId, next);
                packLoaded.add(playerId);
                markResolved(playerId);
            }
            case DECLINED -> {
                packLoaded.remove(playerId);
                markResolved(playerId);
                if (kickOnRefuse && initial) {
                    event.getPlayer().kick(plugin.getMessages().get("pack.kick-refused"));
                }
            }
            case FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED -> {
                // Resolve regardless, so the playback start-gate cannot hang.
                packLoaded.remove(playerId);
                markResolved(playerId);
                if (kickOnRefuse && initial) {
                    event.getPlayer().kick(plugin.getMessages().get("pack.kick-failed"));
                } else if (!initial) {
                    plugin.getLogger().warning("Resource pack update failed for " + event.getPlayer().getName()
                            + "; they have no music pack until the next update or until they rejoin.");
                }
            }
            default -> { /* ACCEPTED / DOWNLOADED: intermediate states */ }
        }
    }

    // ------------------------------------------------------------------
    // Custom sound naming (shared contract with playback)
    // ------------------------------------------------------------------

    /**
     * Sound event key (without namespace) for a song's custom sound file.
     * Both the song ID and canonical path are SHA-256 digests, so the key is
     * bounded to the resource-pack namespace and cannot collide with a
     * generated octave suffix. Playback and generation share this method.
     */
    public static String customSoundEvent(String publicId, String soundFile) {
        return "song." + digestKey(publicId) + "." + digestKey(soundKeyPart(soundFile)) + ".base";
    }

    /**
     * The identifying part of a custom sound path: the whole relative path,
     * minus the .ogg extension, with separators and dot segments normalised.
     *
     * The directories are deliberately KEPT. NBS v5 lets a song reference
     * sounds in subfolders, so "drums/kick.ogg" and "effects/kick.ogg" are two
     * different sounds; reducing both to "kick" gave them the same event, and
     * the duplicate guard in the pack builder then dropped the second one
     * silently -- the song played with one of its instruments missing and
     * nothing said why.
     *
     * The complete canonical path is hashed below, so separators and literal
     * underscores remain distinct without a lossy sanitising transform.
     */
    public static String soundKeyPart(String soundFile) {
        String name = soundFile.replace('\\', '/');
        // Leading "./" or "/" carry no meaning here and would otherwise make
        // two spellings of one path look like different sounds.
        while (name.startsWith("./")) name = name.substring(2);
        while (name.startsWith("/")) name = name.substring(1);
        java.util.ArrayDeque<String> segments = new java.util.ArrayDeque<>();
        for (String segment : name.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".")) continue;
            if (segment.equals("..")) {
                if (segments.isEmpty()) return ""; // escaped the sounds root
                segments.removeLast();
            } else {
                segments.addLast(segment);
            }
        }
        name = String.join("/", segments);
        return name;
    }

    /** Returns the stable digest used for a namespaced key component. */
    public static String sanitizeKeyPart(String input) { return digestKey(input); }
    private static String digestKey(String input) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
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
     * Client resource-pack format (major version) of each Minecraft release
     * this build knows, used by {@code pack-format: auto}. 26.3 itself reports
     * 97.1; pack.mcmeta declares the major number, which max_format treats as
     * "any minor version", so 97 covers 97.0 through 97.x.
     */
    static final Map<String, Integer> KNOWN_PACK_FORMATS = Map.of(
            "26.1", 84, "26.1.1", 84, "26.1.2", 84,
            "26.2", 88,
            "26.3", 97);
    /** Format used by auto when the server version is newer than this table. */
    static final int LATEST_KNOWN_PACK_FORMAT = 97;
    private Object lastFormatWarning;

    private static String serverVersion() {
        try { return org.bukkit.Bukkit.getMinecraftVersion(); }
        catch (RuntimeException | LinkageError e) { return ""; }
    }

    /**
     * "auto" (or an absent value) follows the server's own Minecraft version;
     * a number is used as given, so a proxy that admits other client versions
     * can still pin the format its players need.
     */
    static int resolvePackFormat(Object configured, String serverVersion) {
        if (configured instanceof Number number) return number.intValue();
        if (configured == null || configured instanceof String text && text.trim().equalsIgnoreCase("auto")) {
            return KNOWN_PACK_FORMATS.getOrDefault(serverVersion == null ? "" : serverVersion.trim(),
                    LATEST_KNOWN_PACK_FORMAT);
        }
        throw new IllegalArgumentException("resource-pack.pack-format must be auto or a number");
    }

    /**
     * An upgraded server keeps its old config.yml, so a pinned format from the
     * previous release silently makes every client mark the pack incompatible.
     * Say so once per distinct setting rather than on every reload.
     */
    private void warnAboutPackFormat(Object configured, int format) {
        String version = serverVersion();
        Integer expected = KNOWN_PACK_FORMATS.get(version);
        Object key = configured + "@" + version;
        if (key.equals(lastFormatWarning)) return;
        if (configured instanceof Number && expected != null && expected != format) {
            lastFormatWarning = key;
            plugin.getLogger().warning("resource-pack.pack-format is " + format + ", but this server runs Minecraft "
                    + version + " (pack format " + expected + "). Clients on " + version + " will mark the music pack"
                    + " incompatible. Set pack-format: auto (or " + expected + ") unless a proxy admits other versions.");
        } else if (!(configured instanceof Number) && expected == null) {
            lastFormatWarning = key;
            plugin.getLogger().warning("pack-format: auto does not know Minecraft " + version + "; declaring format "
                    + format + ". If clients mark the pack incompatible, set resource-pack.pack-format explicitly"
                    + " (players can read theirs with F3+V).");
        }
    }

    /**
     * Resource pack format to declare, matching the client version this server
     * accepts: the configured number, or the server version's format for auto.
     */
    private int packFormat() {
        return configuredPackFormat;
    }

    /** The format currently declared in pack.mcmeta, for /nbw packinfo. */
    public int getPackFormat() {
        return configuredPackFormat;
    }

    private PackData buildPackZip() throws IOException {
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

        buildingSeconds.clear();
        Map<String, Object> sounds = new LinkedHashMap<>();
        java.util.Set<String> events = new java.util.HashSet<>();

        Path temp = Files.createTempFile(packDirectory(), "building-", ".tmp");
        boolean complete = false;
        try {
            MessageDigest digest;
            try { digest = MessageDigest.getInstance("SHA-1"); }
            catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
            try (OutputStream file = Files.newOutputStream(temp, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 ZipOutputStream zip = new ZipOutputStream(new java.security.DigestOutputStream(new PackOutput(file), digest))) {
                putEntry(zip, "pack.mcmeta", gson.toJson(mcmeta));
                appendVanillaOctaves(zip, sounds, events);
                appendCustomSounds(zip, sounds, events);
                zip.setLevel(java.util.zip.Deflater.DEFAULT_COMPRESSION);
                putEntry(zip, "assets/nbw/sounds.json", gson.toJson(sounds));
            }
            PackData result = new PackData(temp, Files.size(temp), java.util.HexFormat.of().formatHex(digest.digest()),
                    Set.copyOf(events), Map.copyOf(buildingSeconds));
            complete = true;
            return result;
        } finally { if (!complete) Files.deleteIfExists(temp); }
    }

    /** Bounds ZIP output while streaming, including the central directory. */
    private static final class PackOutput extends java.io.FilterOutputStream {
        private long written;
        PackOutput(OutputStream out) { super(out); }
        private void admit(int count) throws IOException {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Pack build cancelled");
            if (count > MAX_PACK_BYTES - written) throw new IOException("Pack exceeds total size budget");
            written += count;
        }
        @Override public void write(int value) throws IOException { admit(1); out.write(value); }
        @Override public void write(byte[] data, int from, int length) throws IOException { admit(length); out.write(data, from, length); }
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

        long remaining = 16L * 1024 * 1024;
        for (String instrument : INSTRUMENT_NAMES) {
            OggTools.Sample source = vanillaSounds.sample(instrument);
            if (source == null) continue;
            for (var variant : OCTAVE_VARIANTS.entrySet()) {
                if (source.size() > remaining) continue;
                OggTools.Sample shifted = vanillaSounds.shiftedSample(source, variant.getValue());
                if (shifted == null) continue;
                String event = instrument + variant.getKey();
                String path = "octaves/" + event;
                zip.putNextEntry(zipEntry("assets/nbw/sounds/" + path + ".ogg"));
                zip.write(shifted.bytes()); zip.closeEntry();
                addSound(sounds, events, event, path, shifted.seconds());
                remaining -= shifted.size();
            }
        }
    }

    /**
     * Injects downloaded songs' custom instrument sounds, plus an
     * octave-shifted copy of each (same reasoning as the vanilla ones).
     *
     * NOTE: noteblock.world names these files by Mojang asset hash with NO
     * extension — filtering by "*.ogg" finds nothing. Content is sniffed
     * via the OggS magic bytes instead.
     */
    private record SharedSound(String path, double seconds) {}
    private record CustomEntry(String event, Path root, String relative, String digest) {}
    private void appendCustomSounds(ZipOutputStream zip, Map<String, Object> sounds, Set<String> events) throws IOException {
        zip.setLevel(java.util.zip.Deflater.NO_COMPRESSION);
        var downloads = plugin.getDownloadManager();
        if (downloads == null || maxCustomSoundBytes == 0) return;
        long budget = Math.min(maxCustomSoundBytes, 64L * 1024 * 1024);
        List<CustomEntry> admitted = new java.util.ArrayList<>();
        Set<String> assets = new java.util.HashSet<>();
        Map<String, SharedSound> shiftedAssets = new java.util.HashMap<>();
        int invalid = 0, omitted = 0;
        for (String id : downloads.getDownloadedIds().stream().sorted().toList()) {
            Path root = downloads.getSongDirectory(id).resolve("sounds");
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) continue;
            List<Path> files;
            try (Stream<Path> walk = Files.walk(root, 16)) {
                files = walk.filter(f -> Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)).sorted().limit(4096).toList();
            } catch (IOException e) { continue; }
            for (Path file : files) {
                try {
                    if (events.size() >= 8192) { omitted++; continue; }
                    String relative = root.relativize(file).toString();
                    Path contained = CustomSoundPaths.resolve(root, relative);
                    if (contained == null) { invalid++; continue; }
                    OggTools.Sample sample = customSamples.read(contained);
                    long cost = assets.contains(sample.digest()) ? 0 : sample.size();
                    if (cost > budget) { omitted++; continue; }
                    String event = customSoundEvent(id, relative);
                    if (events.contains(event)) { invalid++; continue; }
                    writeSound(zip, sounds, events, assets, event, sample); budget -= cost;
                    admitted.add(new CustomEntry(event, root, relative, sample.digest()));
                } catch (IOException e) { invalid++; }
            }
        }
        // All fitting base events take priority; identical bytes share one physical ZIP asset.
        for (CustomEntry entry : admitted) {
            OggTools.Sample source;
            try {
                Path path = CustomSoundPaths.resolve(entry.root(), entry.relative());
                if (path == null) continue;
                source = customSamples.read(path);
                if (!source.digest().equals(entry.digest())) continue; // changed during this build
            } catch (IOException e) { continue; }
            for (var variant : OCTAVE_VARIANTS.entrySet()) {
                if (events.size() >= 8192) { omitted++; continue; }
                String variantKey = source.digest() + variant.getKey();
                SharedSound shared = shiftedAssets.get(variantKey);
                if (shared != null) {
                    addSound(sounds, events, entry.event() + variant.getKey(), shared.path(), shared.seconds()); continue;
                }
                if (source.size() > budget) { omitted++; continue; }
                OggTools.Sample shifted = customSamples.shift(source, variant.getValue());
                if (shifted == null) continue;
                long cost = assets.contains(shifted.digest()) ? 0 : shifted.size();
                if (cost > budget) { omitted++; continue; }
                writeSound(zip, sounds, events, assets, entry.event() + variant.getKey(), shifted); budget -= cost;
                shiftedAssets.put(variantKey, new SharedSound("custom/" + shifted.digest(), shifted.seconds()));
            }
        }
        if (invalid > 0 || omitted > 0) plugin.getLogger().warning("Pack audio: " + invalid
                + " invalid/unreadable files, " + omitted + " files or variants excluded by budget");
    }

    private void writeSound(ZipOutputStream zip, Map<String, Object> sounds, Set<String> events,
                            Set<String> assets, String event, OggTools.Sample sample) throws IOException {
        String path = "custom/" + sample.digest();
        if (assets.add(sample.digest())) {
            zip.putNextEntry(zipEntry("assets/nbw/sounds/" + path + ".ogg")); zip.write(sample.bytes()); zip.closeEntry();
        }
        addSound(sounds, events, event, path, sample.seconds());
    }
    private void addSound(Map<String, Object> sounds, Set<String> events, String event, String path, double seconds) {
        sounds.put(event, Map.of("sounds", List.of(Map.of("name", "nbw:" + path))));
        events.add(event); buildingSeconds.put(event, seconds);
    }

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
    public void onLibraryShrunk() { requestRebuild(repushOnRemove && isActive()); }
    public void onLibraryChanged() { requestRebuild(isActive()); }
    private final java.util.concurrent.atomic.AtomicLong libraryRevision = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicBoolean pushPending = new java.util.concurrent.atomic.AtomicBoolean();
    private void requestRebuild(boolean push) {
        if (plugin.isStopping()) return;
        libraryRevision.incrementAndGet();
        if (push) {
            // Starts are held by the invalidation timestamp alone (see
            // settled()); keeping each player's resolution lets songs that are
            // already playing continue while the pack is rebuilt.
            pushPending.set(true); invalidatedAtMillis = System.currentTimeMillis();
        }
        if (!rebuildPending.compareAndSet(false, true)) return;
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, this::doRebuildAndPush, rebuildDebounceTicks);
    }
    private void doRebuildAndPush() {
        long revision = libraryRevision.get();
        boolean push = pushPending.getAndSet(false);
        plugin.getWorkPool().run(() -> {
            try { rebuild(); }
            catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
        }).whenComplete((unused, error) -> {
            if (plugin.isStopping()) return;
            // Admission failure means the build never ran. Pushing the previous
            // generation and forgetting the change left a newly added song's
            // custom sounds out of the pack until some later library change.
            boolean busy = io.github.noteblockworldmc.util.Throwables.rootCause(error)
                    instanceof java.util.concurrent.RejectedExecutionException;
            plugin.runMain(() -> {
                rebuildPending.set(false);
                if (busy) {
                    if (push) pushPending.set(true);
                    plugin.getLogger().warning("Pack rebuild deferred because the plugin's workers are busy; retrying shortly.");
                    if (rebuildPending.compareAndSet(false, true))
                        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, this::doRebuildAndPush, Math.max(20L, rebuildDebounceTicks));
                    return;
                }
                invalidatedAtMillis = 0;
                if (error != null) plugin.getLogger().warning("Pack build failed; previous generation retained: " + error);
                if (push && isActive()) for (Player online : plugin.getServer().getOnlinePlayers()) send(online);
                if (revision != libraryRevision.get()) requestRebuild(pushPending.get());
            });
        });
    }

    private static ZipEntry zipEntry(String path) {
        ZipEntry entry = new ZipEntry(path); entry.setTime(0); return entry;
    }

    private static void putEntry(ZipOutputStream zip, String path, String content)
            throws IOException {
        zip.putNextEntry(zipEntry(path));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

}
