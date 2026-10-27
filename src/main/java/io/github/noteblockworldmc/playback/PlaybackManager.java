package io.github.noteblockworldmc.playback;

import io.github.noteblockworldmc.NoteblockWorldMCPlugin;
import io.github.noteblockworldmc.nbs.NbsParser;
import io.github.noteblockworldmc.nbs.NbsSong;
import io.github.noteblockworldmc.pack.ResourcePackService;
import io.github.noteblockworldmc.util.Throwables;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Plays parsed NBS songs using note block sounds.
 * <p>
 * Two session kinds:
 *  - PERSONAL: one per player, heard only by that listener.
 *  - BROADCAST: at most one server-wide, heard by everyone who can hear it.
 * <p>
 * A live broadcast takes precedence: a player's personal session goes silent
 * while one is running, keeps advancing in the background, and is audible
 * again the moment the broadcast ends. Late joiners hear the remainder of a
 * broadcast automatically.
 * <p>
 * Timing comes from a dedicated clock thread stepping every
 * playback.precision-ms (default 2ms), not from the game tick, because a song
 * tick at NBS tempos rarely divides evenly into Minecraft's 50ms and fast
 * songs would otherwise batch several notes into one tick. Setting
 * playback.high-precision to false falls back to a per-game-tick
 * BukkitRunnable, which is only adequate up to about 20 t/s.
 */
public class PlaybackManager implements Listener {

    /** NBS vanilla instrument index -> note block sound. */
    private static final Sound[] INSTRUMENT_SOUNDS = {
            Sound.BLOCK_NOTE_BLOCK_HARP,            // 0  piano
            Sound.BLOCK_NOTE_BLOCK_BASS,            // 1  double bass
            Sound.BLOCK_NOTE_BLOCK_BASEDRUM,        // 2  bass drum
            Sound.BLOCK_NOTE_BLOCK_SNARE,           // 3  snare
            Sound.BLOCK_NOTE_BLOCK_HAT,             // 4  click
            Sound.BLOCK_NOTE_BLOCK_GUITAR,          // 5  guitar
            Sound.BLOCK_NOTE_BLOCK_FLUTE,           // 6  flute
            Sound.BLOCK_NOTE_BLOCK_BELL,            // 7  bell
            Sound.BLOCK_NOTE_BLOCK_CHIME,           // 8  chime
            Sound.BLOCK_NOTE_BLOCK_XYLOPHONE,       // 9  xylophone
            Sound.BLOCK_NOTE_BLOCK_IRON_XYLOPHONE,  // 10 iron xylophone
            Sound.BLOCK_NOTE_BLOCK_COW_BELL,        // 11 cow bell
            Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO,      // 12 didgeridoo
            Sound.BLOCK_NOTE_BLOCK_BIT,             // 13 bit
            Sound.BLOCK_NOTE_BLOCK_BANJO,           // 14 banjo
            Sound.BLOCK_NOTE_BLOCK_PLING,           // 15 pling
            // NBS v6 built-ins (Minecraft 26.1 copper trumpets)
            Sound.BLOCK_NOTE_BLOCK_TRUMPET,           // 16 trumpet
            Sound.BLOCK_NOTE_BLOCK_TRUMPET_EXPOSED,   // 17 exposed trumpet
            Sound.BLOCK_NOTE_BLOCK_TRUMPET_WEATHERED, // 18 weathered trumpet
            Sound.BLOCK_NOTE_BLOCK_TRUMPET_OXIDIZED   // 19 oxidized trumpet
    };

    /**
     * Extended-octave sound keys from the server resource pack:
     * [instrument][octave + 3] -> {@code "nbw:<name>_m3".."nbw:<name>_p3"}
     * (octave 0 uses the vanilla Sound above and stays null here).
     */
    private static final String[][] EXTENDED_SOUND_KEYS =
            new String[ResourcePackService.INSTRUMENT_NAMES.length][7];
    /**
     * The same names WITHOUT the "nbw:" namespace, which is what
     * ResourcePackService#hasEvent expects. Precomputed because the lookup
     * happens per note on the clock thread: deriving it with substring(4)
     * there allocated a throwaway String for every note and every listener.
     */
    private static final String[][] EXTENDED_SOUND_EVENTS =
            new String[ResourcePackService.INSTRUMENT_NAMES.length][7];
    static {
        for (int i = 0; i < ResourcePackService.INSTRUMENT_NAMES.length; i++) {
            for (int octave = -3; octave <= 3; octave++) {
                if (octave == 0) continue;
                String event = ResourcePackService.INSTRUMENT_NAMES[i]
                        + (octave < 0 ? "_m" + (-octave) : "_p" + octave);
                EXTENDED_SOUND_EVENTS[i][octave + 3] = event;
                EXTENDED_SOUND_KEYS[i][octave + 3] = "nbw:" + event;
            }
        }
    }

    private final NoteblockWorldMCPlugin plugin;
    public record PlayRequest(UUID token, UUID caller, UUID listener, boolean broadcast) {}
    private final Map<UUID, PlayRequest> playRequests = new ConcurrentHashMap<>();
    private volatile PlayRequest broadcastRequest;
    private static final class PendingSelection {
        final PlayRequest request;
        final String songId;
        volatile boolean invalidated;
        PendingSelection(PlayRequest request, String songId) { this.request = request; this.songId = songId; }
    }
    /** Only not-yet-started library selections; one per personal slot and one broadcast. */
    private final Map<Object, PendingSelection> pendingSelections = new ConcurrentHashMap<>();
    private static Object selectionKey(PlayRequest request) { return request.broadcast() ? "broadcast" : request.listener(); }
    private record InspectionOwner(UUID id) {}
    private record LoadCallback(String id, UUID token, Player feedback, Consumer<NbsSong> accept) {}
    private record SongLoad(UUID token, CompletableFuture<NbsSong> future) {}
    private final Map<Object, LoadCallback> loadCallbacks = new ConcurrentHashMap<>();
    private final Map<String, SongLoad> loadingSongs = new ConcurrentHashMap<>();
    private final java.util.concurrent.locks.ReentrantLock driverLock = new java.util.concurrent.locks.ReentrantLock();
    private volatile long clockGeneration;
    private record Voice(long end, Object owner, boolean broadcast, Object sound) {}
    private static final class Voices {
        final java.util.PriorityQueue<Voice> ends = new java.util.PriorityQueue<>(
                java.util.Comparator.comparingLong(Voice::end));
        void expire(long now) { while (!ends.isEmpty() && ends.peek().end() <= now) ends.poll(); }
    }
    private final Map<UUID, Voices> listenerVoices = new ConcurrentHashMap<>();

    /** Starts intent BEFORE download/parse; every continuation retains this identity. Main thread. */
    public PlayRequest beginPlay(Player caller, Player listener, boolean broadcast) {
        PlayRequest request = new PlayRequest(UUID.randomUUID(), caller.getUniqueId(),
                broadcast ? null : listener.getUniqueId(), broadcast);
        pendingSelections.remove(selectionKey(request));
        if (broadcast) { loadCallbacks.remove("broadcast"); broadcastRequest = request; cancelPendingBroadcast(); queuedBroadcastStart = null; }
        else { loadCallbacks.remove(request.listener()); playRequests.put(request.listener(), request); cancelPending(request.listener()); queuedStarts.remove(request.listener()); }
        return request;
    }
    public boolean isCurrent(PlayRequest request) {
        if (plugin.isStopping() || request == null || !(request.broadcast()
                ? request.equals(broadcastRequest) : request.equals(playRequests.get(request.listener())))) return false;
        Player caller = plugin.getServer().getPlayer(request.caller());
        Player listener = request.broadcast() ? caller : plugin.getServer().getPlayer(request.listener());
        return caller != null && caller.isOnline() && listener != null && listener.isOnline()
                && caller.hasPermission("noteblockworldmc.play")
                && (!(request.broadcast() || !request.caller().equals(request.listener()))
                    || caller.hasPermission("noteblockworldmc.broadcast"));
    }
    public void playDownloaded(PlayRequest request, Player feedback, String id) {
        if (!isCurrent(request)) return;
        synchronized (songCache) {
            pendingSelections.put(selectionKey(request), new PendingSelection(request, id));
        }
        loadSong(id, feedback, request.broadcast() ? "broadcast" : request.listener(), song -> {
            if (!isCurrent(request) || !plugin.getDownloadManager().isDownloaded(id)) return;
            if (request.broadcast()) startBroadcastRequested(feedback, song, id, request);
            else startRequested(plugin.getServer().getPlayer(request.listener()), song, id, request);
        });
    }
    /**
     * Releases a selection whose download failed before playback could start.
     * Without this the request stayed registered, so a later stop reported
     * stopping a song that never existed. A newer selection is unaffected.
     */
    public void abandon(PlayRequest request) {
        if (request != null) completeRequest(request);
    }
    private void completeRequest(PlayRequest request) {
        pendingSelections.computeIfPresent(selectionKey(request), (_, selection) ->
                selection.request.equals(request) ? null : selection);
        if (request.broadcast()) { if (request.equals(broadcastRequest)) broadcastRequest = null; }
        else playRequests.remove(request.listener(), request);
    }

    private volatile boolean showActionBar;
    private volatile int barLength;
    private volatile boolean respectLoop;
    private volatile boolean highPrecision;
    private volatile int precisionMs;
    /** Soft limiter: scales dense chords down to keep the client mixer clean. */
    private volatile boolean softLimiter;
    private volatile int limiterThreshold;
    /** Minimum effective note volume sent to a client. */
    private volatile float audibleFloor;
    /**
     * Shared high-resolution clock. Minecraft's scheduler only runs 20x a
     * second, so tick-driven playback quantises every note to a 50 ms grid —
     * a 40 t/s song arrives as clusters of two notes instead of an even run.
     * This thread steps every session every few milliseconds instead.
     * <p>
     * It touches NOTHING from the Bukkit world: each session publishes a
     * listener snapshot (positions, volumes, pack state) from the main thread
     * once per tick, and the clock only reads that and emits sound packets.
     */
    private java.util.concurrent.ScheduledExecutorService clock;
    /**
     * Default minimum effective note volume. Zero keeps every nonzero NBS note
     * eligible for playback; raise it on a busy server to save sound channels.
     */
    public static final float DEFAULT_AUDIBLE_FLOOR = 0f;

    /** Reads and bounds the quiet-note filter shared by playback and analysis. */
    public static float configuredAudibleFloor(NoteblockWorldMCPlugin plugin) {
        double configured = plugin.getConfig().getDouble("playback.audible-floor", DEFAULT_AUDIBLE_FLOOR);
        if (!Double.isFinite(configured)) return DEFAULT_AUDIBLE_FLOOR;
        return (float) Math.clamp(configured, 0d, 1d);
    }

    /**
     * Milliseconds spent ramping down when a song is stopped; 0 = hard cut.
     * <p>
     * Minecraft cannot alter the volume of a sound that is already playing,
     * so the ramp itself only quietens notes STRUCK during the window. What
     * makes the fade audible is that the session then ends WITHOUT cutting
     * the remainder: notes already ringing decay on their own. Ramp plus
     * natural decay together give a smooth tail. Cutting at the end of the
     * ramp instead — as this first did — merely delayed a hard cut.
    */
    private volatile int fadeOutMs;
    /**
     * Keeps the number of simultaneously ringing sounds this session asks of
     * the client under a ceiling. Measured sample lengths let dense songs
     * degrade deliberately while sparse songs remain untouched.
     */
    private volatile boolean voiceAllocation;
    private volatile int voiceCeiling;

    private volatile boolean humanize;
    /**
     * Ceiling on timing jitter as a share of one song tick. Past roughly this
     * much, jitter stops sounding like loose human timing and starts sounding
     * like the rhythm is wrong.
     */
    private static final double MAX_JITTER_FRACTION = 0.15;
    private volatile double humanizeTimingMs;
    private volatile double humanizeVelocity;
    private volatile double humanizePercussion;
    /** 0 = independent jitter per chord; 1 = smooth wandering timing. */
    private volatile double humanizeDrift;
    /** basedrum, snare, hat — these carry the beat and must stay tight. */
    private static boolean isPercussion(int instrument) {
        return instrument >= 2 && instrument <= 4;
    }

    /**
     * Attach notes to the listener instead of placing them in the world.
     * <p>
     * A world-placed note is pinned to where it was played, and the game keeps
     * recomputing its volume and direction as you move -- so every ringing note
     * fades and swings around you while you walk or fly. Attaching the sound to
     * the player removes that: the note travels with you.
     * <p>
     * Trade-off: Minecraft cannot pan an attached sound (stereo exists only
     * because a sound has a POSITION to be offset from), so panning is off in
     * this mode.
     */
    private volatile boolean followPlayer;
    private volatile boolean stereoPanning;
    private volatile double panDistance;
    private final Map<UUID, Playback> sessions = new ConcurrentHashMap<>();
    /** The single server-wide broadcast session, or null. Main thread only. */
    private volatile Playback broadcastSession;
    /**
     * Songs waiting for the current one to finish fading out.
     * <p>
     * Driven by the fade COMPLETING rather than a timer, so the new song can
     * never begin while the old session is still winding down — a timer would
     * race the finish hop to the main thread and the winner would cut the
     * outgoing tail.
     */
    private final Map<UUID, Runnable> queuedStarts = new ConcurrentHashMap<>();
    private final Map<UUID, org.bukkit.scheduler.BukkitTask> pendingStarts = new ConcurrentHashMap<>();
    /** Pack-resolution wait pending before a broadcast starts. Main thread only. */
    private org.bukkit.scheduler.BukkitTask pendingBroadcast;
    /** Latest broadcast selection, started by the outgoing session's completed fade. */
    private Runnable queuedBroadcastStart;
    /** Give-up threshold for pack waits: start anyway after 60s. */
    private static final int PACK_WAIT_TIMEOUT_TICKS = 20 * 60;

    /** Measured ring time of each vanilla instrument's sample; -1 unknown. */
    private volatile double[] vanillaRingCache;
    /** Active songs stay owned by sessions; retained idle songs have a separate 64 MiB budget. */
    private final SongCache songCache = new SongCache(64L * 1024 * 1024, 16);

    public PlaybackManager(NoteblockWorldMCPlugin plugin) {
        this.plugin = plugin;
        loadHotSettings();
    }

    /**
     * (Re)reads playback settings. Called on startup and by /nbw reload.
     * Everything here takes effect immediately, mid-song included — changing
     * the clock rate restarts the clock and re-anchors running sessions so
     * they don't jump.
     */
    public void loadHotSettings() {
        var config = plugin.getConfig();
        this.showActionBar = config.getBoolean("playback.show-actionbar", true);
        this.barLength = Math.clamp(config.getInt("playback.actionbar-length", 20), 4, 40);
        this.respectLoop = config.getBoolean("playback.respect-loop", true);
        this.fadeOutMs = Math.clamp(config.getInt("playback.fade-out-ms", 500), 0, 5000);
        this.voiceAllocation = config.getBoolean("playback.voice-allocation", true);
        // Upper bound is the CONFIGURED pool, not a hardcoded vanilla 247:
        // client mods can enlarge the audio engine's source pool, and an
        // admin who raised client-channels to match must be able to raise the
        // ceiling with it.
        int channels = PolyphonyAnalyzer.clientChannels(plugin);
        // clientChannels() is never below 16, so the bounds cannot cross.
        this.voiceCeiling = Math.clamp(config.getInt("playback.voice-ceiling", 200), 16, channels);
        this.humanize = config.getBoolean("playback.humanize", true);
        this.humanizeTimingMs = Math.clamp(config.getDouble("playback.humanize-timing-ms", 6), 0d, 50d);
        this.humanizeVelocity = Math.clamp(config.getDouble("playback.humanize-velocity", 0.04), 0d, 0.5);
        this.humanizePercussion = Math.clamp(config.getDouble("playback.humanize-percussion-factor", 0.25), 0d, 1d);
        this.humanizeDrift = Math.clamp(config.getDouble("playback.humanize-drift", 0.7), 0d, 1d);

        this.followPlayer = config.getBoolean("playback.follow-player", true);
        // Panning needs a world position to offset; attached sounds have none
        this.stereoPanning = !this.followPlayer
                && config.getBoolean("playback.stereo-panning", true);
        this.panDistance = Math.clamp(config.getDouble("playback.pan-distance", 2.0), 0.1, 8.0);
        this.softLimiter = config.getBoolean("playback.soft-limiter", true);
        this.limiterThreshold = Math.max(2, config.getInt("playback.limiter-threshold", 8));
        this.audibleFloor = configuredAudibleFloor(plugin);

        boolean wasHighPrecision = this.highPrecision;
        int wasPrecisionMs = this.precisionMs;
        this.highPrecision = config.getBoolean("playback.high-precision", true);
        this.precisionMs = Math.clamp(config.getInt("playback.precision-ms", 2), 2, 25);

        // Only churn the clock thread if the timing settings actually changed
        if (this.highPrecision != wasHighPrecision || this.precisionMs != wasPrecisionMs) {
            restartClock();
        }
    }

    /** Rebuilds the clock after a timing change, without disturbing playback. */
    private void restartClock() {
        shutdownClock();
        // Running sessions must not see the gap as elapsed song time
        for (Playback session : sessions.values()) session.resync();
        Playback broadcast = broadcastSession;
        if (broadcast != null) broadcast.resync();
        if (!sessions.isEmpty() || broadcast != null) {
            ensureClock();
        }
    }

    /** Movement prediction is capped: beyond this a note would land far from a player who stopped. */
    private static final double MAX_LEAD_SECONDS = 0.6, MAX_LEAD_BLOCKS = 4, TELEPORT_BLOCKS = 10;
    private static final double[] NO_MOTION = {0, 0, 0};

    /** Starts the shared clock on first use. */
    private synchronized void ensureClock() {
        if (!highPrecision || clock != null) return;
        clock = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "NoteblockWorldMC-clock");
            thread.setDaemon(true);
            return thread;
        });
        long generation = ++clockGeneration;
        clock.scheduleAtFixedRate(() -> clockStep(generation), 0, precisionMs,
                java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void clockStep(long generation) {
        if (!driverLock.tryLock()) return;
        try {
            if (generation != clockGeneration || !highPrecision || plugin.isStopping()) return;
            for (Playback session : sessions.values()) {
                session.step();
            }
            Playback broadcast = broadcastSession;
            if (broadcast != null) broadcast.step();
        } catch (Throwable t) {
            // A clock thread that dies takes all music with it
            plugin.getLogger().warning("Playback clock error: " + t);
        } finally { driverLock.unlock(); }
    }

    /** Stops the clock; call on plugin disable. */
    public synchronized void shutdownClock() {
        ++clockGeneration;
        if (clock != null) {
            clock.shutdownNow();
            clock = null;
        }
    }

    /**
     * Stops the clock when nothing is playing. Without this it keeps waking
     * every precision-ms (500x/second at the default) to iterate an empty
     * session map until the next song or server shutdown — needless wakeups
     * costing a little CPU and battery-unfriendly timer pressure for zero
     * work. ensureClock() restarts it on the next play. Main thread only;
     * every session-ending path already is, so this never runs on the clock
     * thread itself.
     */
    private void maybeStopClock() {
        if (sessions.isEmpty() && broadcastSession == null) {
            shutdownClock();
        }
    }

    /**
     * Gain for a tick carrying {@code count} simultaneous notes.
     * <p>
     * The client mixes sounds by summing them, so a dense chord at full
     * velocity sums past 1.0 and clips — that's the harsh "mush" on busy
     * passages. Below the threshold nothing changes (quiet passages keep
     * their exact dynamics); above it, gain falls as 1/sqrt(n/threshold),
     * which preserves the *perceived* loudness of the chord (power grows
     * with n, so amplitude should shrink with its square root) while
     * pulling the sum back under the ceiling.
     */
    private float limiterGain(int count) {
        if (!softLimiter || count <= limiterThreshold) return 1f;
        return (float) Math.sqrt((double) limiterThreshold / count);
    }

    /**
     * Volume to SEND so an entity-attached note ARRIVES at the intended gain.
     * <p>
     * An attached sound is positioned at the entity's feet, while the game's
     * listener sits at the camera — about 1.62 blocks higher. Minecraft's
     * audible radius is max(volume, 1) x 16 blocks and attenuation is linear,
     * so that fixed gap costs a constant fraction of the gain:
     * <p>
     *     gain = volume x (1 - distance / 16)        (for volume &lt;= 1)
     * <p>
     * i.e. every attached note arrives ~10% quiet. Divide it back out. Past
     * ~0.9 that needs a volume above 1.0, which in Minecraft does NOT play
     * louder — it only widens the radius, which is exactly the lever we want.
     */
    private static float attachedVolume(float target, double distance) {
        if (target <= 0f) return 0f;
        double falloff = 1.0 - distance / 16.0;        // ~0.899 at eye height
        if (falloff <= 0) return 1f;                   // absurd eye height; bail
        if (target <= falloff) {
            return (float) (target / falloff);         // stays <= 1.0
        }
        // Louder than falloff allows: needs volume > 1, where the near-field
        // gain clamps to 1 and only the radius grows.
        double needed = distance / (16.0 * Math.max(1e-4, 1.0 - target));
        return (float) Math.min(8.0, needed);
    }

    private static String formatTime(double seconds) {
        int total = (int) Math.max(0, Math.round(seconds));
        return String.format("%d:%02d", total / 60, total % 60);
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** Parses (off-thread, cached) and plays a downloaded song for one player. */
    public void playDownloaded(Player player, String publicId) {
        playDownloaded(beginPlay(player, player, false), player, publicId);
    }

    /** Parses (off-thread, cached) and broadcasts a downloaded song to everyone. */
    public void playDownloadedForAll(Player starter, String publicId) {
        playDownloaded(beginPlay(starter, null, true), starter, publicId);
    }

    /** Starts personal playback of an unidentified song (no custom sounds). */
    public void start(Player player, NbsSong song) {
        start(player, song, null);
    }

    /**
     * Starts personal playback on the main thread, replacing any current
     * session. The publicId links custom-instrument notes to their pack
     * sounds; pass null for songs outside the library.
     */
    public void start(Player player, NbsSong song, String publicId) {
        startRequested(player, song, publicId, beginPlay(player, player, false));
    }
    private void startRequested(Player player, NbsSong song, String publicId, PlayRequest request) {
        if (!isCurrent(request)) return;
        if (publicId != null) pendingSelections.computeIfAbsent(selectionKey(request), _ -> new PendingSelection(request, publicId));
        UUID id = player.getUniqueId();
        Playback current = sessions.get(id);

        // Switching while something is playing: ramp the outgoing song down
        // and queue this one to begin when that ramp completes. If a fade is
        // already running (the player is switching again mid-switch) just
        // replace what comes next rather than starting a second ramp.
        if (current != null && (current.isFading() || current.beginFade())) {
            cancelPending(id);
            queuedStarts.put(id, () -> startWhenPackSettles(player, song, publicId, request));
            return;
        }

        stopSession(id);
        startWhenPackSettles(player, song, publicId, request);
    }


    private void startWhenPackSettles(Player player, NbsSong song, String publicId, PlayRequest request) {
        if (!readyToStart(player, publicId, request)) return;
        ResourcePackService pack = plugin.getResourcePackService();
        if (pack.isActive() && !pack.isSettled(player.getUniqueId())) {
            // Don't start until the player's pack attempt resolves
            // (loaded OR failed/declined); give up after the timeout.
            player.sendMessage(plugin.getMessages().get("playback.waiting-pack"));
            org.bukkit.scheduler.BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin,
                    new Runnable() {
                        int waitedTicks = 0;

                        @Override
                        public void run() {
                            if (!readyToStart(player, publicId, request)) {
                                cancelPending(player.getUniqueId());
                                return;
                            }
                            waitedTicks += 10;
                            if (pack.isSettled(player.getUniqueId())
                                    || waitedTicks >= PACK_WAIT_TIMEOUT_TICKS) {
                                cancelPending(player.getUniqueId());
                                startNow(player, song, publicId, request);
                            }
                        }
                    }, 10L, 10L);
            pendingStarts.put(player.getUniqueId(), task);
            return;
        }
        startNow(player, song, publicId, request);
    }

    private boolean cancelPending(UUID playerId) {
        org.bukkit.scheduler.BukkitTask task = pendingStarts.remove(playerId);
        if (task != null) task.cancel();
        return task != null;
    }

    private void startNow(Player player, NbsSong song, String publicId, PlayRequest request) {
        if (!readyToStart(player, publicId, request)) return;
        completeRequest(request);
        ensureClock();
        Playback playback = new Playback(player, song, publicId);
        // Cancel whatever we are replacing rather than just dropping it from
        // the map. An overwritten session keeps its own ticker running, so it
        // would go on pushing action bars (and notes) for a song the player
        // has already moved on from -- the "action bar flickering between two
        // songs" symptom.
        Playback previous = sessions.put(player.getUniqueId(), playback);
        if (previous != null) previous.cancelQuietly();
        playback.start();

        player.sendMessage(nowPlayingMessage(song, publicId, null));
        sendCustomNoteWarning(player, song);
    }

    /**
     * The name to show for a song.
     * <p>
     * Prefers the title recorded in the library over the one inside the .nbs.
     * A file imported with no internal title still gets a real name at import
     * time -- from the name given to /nbw importurl, or the file name -- and
     * that is what the browser and /nbw playing display. Reading the NBS
     * header here instead meant the same song appeared under its proper name
     * everywhere except while it was playing, where it said "Untitled".
     * <p>
     * Falls back to the header, then to the placeholder, so a song that has
     * neither still shows something.
     */
    private String displayTitle(NbsSong song, String publicId) {
        if (publicId != null) {
            String stored = plugin.getDownloadManager().getTitle(publicId);
            // getTitle returns the id when it knows no better; that is not a
            // name worth showing when the file itself might have one.
            if (stored != null && !stored.isBlank() && !stored.equals(publicId)) {
                return stored;
            }
        }
        if (!song.getName().isBlank()) return song.getName();
        return PlainTextComponentSerializer.plainText()
                .serialize(plugin.getMessages().get("playback.untitled"));
    }

    private Component nowPlayingMessage(NbsSong song, String publicId, String starterName) {
        var messages = plugin.getMessages();
        String title = displayTitle(song, publicId);
        String author = song.getAuthor().isBlank()
                ? PlainTextComponentSerializer.plainText().serialize(messages.get("playback.unknown-author"))
                : song.getAuthor();
        int seconds = (int) Math.round(song.getDurationSeconds());
        String duration = String.format("%d:%02d", seconds / 60, seconds % 60);

        if (starterName != null) {
            return messages.get("playback.now-playing-broadcast",
                    Placeholder.unparsed("title", title),
                    Placeholder.unparsed("author", author),
                    Placeholder.unparsed("duration", duration),
                    Placeholder.unparsed("starter", starterName));
        }
        return messages.get("playback.now-playing",
                Placeholder.unparsed("title", title),
                Placeholder.unparsed("author", author),
                Placeholder.unparsed("duration", duration));
    }

    private void sendCustomNoteWarning(Player player, NbsSong song) {
        if (song.getSkippedCustomNotes() > 0) {
            player.sendMessage(plugin.getMessages().get("playback.custom-notes-undefined",
                    Placeholder.unparsed("count", String.valueOf(song.getSkippedCustomNotes()))));
        }
        if (song.getSkippedDenseNotes() > 0) {
            player.sendMessage(plugin.getMessages().get("playback.dense-notes-skipped",
                    Placeholder.unparsed("count", String.valueOf(song.getSkippedDenseNotes()))));
        }
    }

    /** Fades the outgoing broadcast, then starts the latest requested replacement. */
    private void startBroadcastRequested(Player starter, NbsSong song, String publicId, PlayRequest request) {
        if (!isCurrent(request)) return;
        if (publicId != null) pendingSelections.computeIfAbsent(selectionKey(request), _ -> new PendingSelection(request, publicId));
        Playback current = broadcastSession;
        if (current != null && (current.isFading() || current.beginFade())) {
            cancelPendingBroadcast();
            queuedBroadcastStart = () -> startBroadcastWhenPackSettles(starter, song, publicId, request);
            return;
        }
        stopBroadcastSession();
        startBroadcastWhenPackSettles(starter, song, publicId, request);
    }

    private void startBroadcastWhenPackSettles(Player starter, NbsSong song, String publicId, PlayRequest request) {
        if (!readyToStart(starter, publicId, request)) return;
        ResourcePackService pack = plugin.getResourcePackService();
        if (pack.isActive() && !allOnlineResolved(pack)) {
            // Wait until EVERY online player's pack attempt has resolved.
            // Players joining during the wait are naturally included by
            // re-checking the full online list each poll.
            starter.sendMessage(plugin.getMessages().get("playback.waiting-pack-all"));
            pendingBroadcast = Bukkit.getScheduler().runTaskTimer(plugin,
                    new Runnable() {
                        int waitedTicks = 0;

                        @Override
                        public void run() {
                            waitedTicks += 10;
                            if (!readyToStart(starter, publicId, request)) { cancelPendingBroadcast(); return; }
                            if (allOnlineResolved(pack)
                                    || waitedTicks >= PACK_WAIT_TIMEOUT_TICKS) {
                                if (pendingBroadcast != null) {
                                    pendingBroadcast.cancel();
                                    pendingBroadcast = null;
                                }
                                startBroadcastNow(starter, song, publicId, request);
                            }
                        }
                    }, 10L, 10L);
            return;
        }
        startBroadcastNow(starter, song, publicId, request);
    }

    private boolean allOnlineResolved(ResourcePackService pack) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!pack.isSettled(online.getUniqueId())) return false;
        }
        return true;
    }

    private void startBroadcastNow(Player starter, NbsSong song, String publicId, PlayRequest request) {
        if (!readyToStart(starter, publicId, request)) return;
        completeRequest(request);
        ensureClock();
        broadcastSession = new Playback(null, song, publicId);
        broadcastSession.start();

        // Player#getName works even if the starter logged off mid-wait
        plugin.getServer().sendMessage(nowPlayingMessage(song, publicId, starter.getName()));
        if (starter.isOnline()) {
            sendCustomNoteWarning(starter, song);
        }
    }

    /** Revalidate library-backed selections after fades and pack-loading waits. */
    private boolean readyToStart(Player feedback, String publicId, PlayRequest request) {
        if (!isCurrent(request)) return false;
        PendingSelection selection = pendingSelections.get(selectionKey(request));
        boolean changed = selection != null && selection.request.equals(request) && selection.invalidated;
        if (!changed && (publicId == null || plugin.getDownloadManager().isDownloaded(publicId))) return true;
        completeRequest(request);
        if (request.broadcast()) {
            cancelPendingBroadcast(); queuedBroadcastStart = null;
        } else {
            cancelPending(request.listener()); queuedStarts.remove(request.listener());
        }
        feedback.sendMessage(plugin.getMessages().get("playback.read-error",
                Placeholder.unparsed("reason", changed ? "Song data was invalidated; select it again"
                        : "Song is no longer downloaded; select it again")));
        return false;
    }

    /**
     * Stops a player's song at once, with no ramp.
     * <p>
     * This is the form shutdown and quit must use: a fading session stays
     * registered and keeps stepping, so fading here would leave onDisable
     * killing the clock before anything was cancelled -- notes would hang on
     * every client and the sessions would leak. Use stopFading() for stops a
     * player actually asked for.
     */
    public boolean stop(UUID playerId) {
        pendingSelections.remove(playerId);
        loadCallbacks.remove(playerId);
        boolean loading = playRequests.remove(playerId) != null;
        boolean stopped = stopSession(playerId);
        stopped = cutTail(playerId) || stopped;
        return loading || stopped;
    }
    private boolean cutTail(UUID playerId) {
        return cutVoices(playerId, voice -> !voice.broadcast());
    }

    /** Stop only recorded music events. Shared event names must be allowed to decay. */
    private boolean cutVoices(UUID playerId, java.util.function.Predicate<Voice> selected) {
        Voices voices = listenerVoices.get(playerId); if (voices == null) return false;
        synchronized (voices) {
            voices.expire(System.nanoTime()); if (voices.ends.isEmpty()) return false;
            java.util.Set<Object> sounds = new java.util.HashSet<>();
            java.util.Set<Object> protectedSounds = new java.util.HashSet<>();
            for (Voice voice : voices.ends) {
                (selected.test(voice) ? sounds : protectedSounds).add(voice.sound());
            }
            if (sounds.isEmpty()) return false;
            // Minecraft can stop an event, not one instance of that event. If
            // another session uses the same event, preserve both ringing tails.
            sounds.removeAll(protectedSounds);
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null) for (Object sound : sounds) {
                if (sound instanceof Sound vanilla) player.stopSound(vanilla, SoundCategory.RECORDS);
                else player.stopSound((String) sound, SoundCategory.RECORDS);
            }
            voices.ends.removeIf(voice -> sounds.contains(voice.sound()));
            return true;
        }
    }
    private boolean stopSession(UUID playerId) {
        boolean waiting = cancelPending(playerId);
        // Drop anything queued behind a fade: stopping must mean stop, not
        // "stop and then start the song I was switching to".
        boolean queued = queuedStarts.remove(playerId) != null;
        Playback playback = sessions.remove(playerId);
        if (playback == null) return waiting || queued;
        playback.cancelQuietly();
        maybeStopClock();
        return true;
    }

    /**
     * Stops a player's song, ramping down first when fade-out-ms allows.
     * <p>
     * The session stays registered while it fades so it keeps stepping; it
     * removes itself when the ramp completes. A second call finds the fade
     * already running and cuts immediately, so repeating the command always
     * feels responsive.
     */
    public boolean stopFading(UUID playerId) {
        pendingSelections.remove(playerId);
        loadCallbacks.remove(playerId);
        boolean loading = playRequests.remove(playerId) != null;
        queuedStarts.remove(playerId);
        Playback playback = sessions.get(playerId);
        if (playback != null && playback.beginFade()) {
            cancelPending(playerId);
            return true;
        }
        return stop(playerId) || loading;
    }

    /**
     * Fades out every personal (non-broadcast) session. Returns true if at
     * least one was playing. Used by "/nbw stop @a" together with the
     * broadcast stop, so one command clears everything currently audible.
     */
    public boolean stopAllPersonalFading() {
        boolean any = false;
        java.util.Set<UUID> ids = new java.util.HashSet<>(sessions.keySet());
        ids.addAll(pendingStarts.keySet());
        ids.addAll(playRequests.keySet());
        ids.addAll(listenerVoices.keySet());
        for (UUID id : ids) {
            if (stopFading(id)) any = true;
        }
        return any;
    }

    /** Global stop: preserve active fades, but select all finished tails together. */
    public boolean stopEveryoneFading() {
        boolean any = stopBroadcastFading();
        any |= stopAllPersonalFading();
        Playback broadcast = broadcastSession;
        for (UUID id : listenerVoices.keySet()) {
            Playback personal = sessions.get(id);
            // Selecting personal and broadcast tails separately makes shared
            // event names protect each other even though both should stop.
            // Voices of sessions still fading must retain that protection.
            any = cutVoices(id, voice ->
                    (personal == null || voice.owner() != personal.voiceOwner)
                    && (broadcast == null || voice.owner() != broadcast.voiceOwner)) || any;
        }
        return any;
    }

    public boolean stopBroadcastFading() {
        pendingSelections.remove("broadcast");
        loadCallbacks.remove("broadcast");
        boolean loading = broadcastRequest != null; broadcastRequest = null;
        boolean waiting = cancelPendingBroadcast();
        boolean queued = queuedBroadcastStart != null;
        queuedBroadcastStart = null;
        Playback session = broadcastSession;
        if (session != null && session.beginFade()) {
            return true;
        }
        return stopBroadcast() || waiting || queued || loading;
    }

    private boolean cancelPendingBroadcast() {
        if (pendingBroadcast != null) {
            pendingBroadcast.cancel();
            pendingBroadcast = null;
            return true;
        }
        return false;
    }

    /** Immediately stops the broadcast and cancels any queued or pack-waiting start. */
    public boolean stopBroadcast() {
        pendingSelections.remove("broadcast");
        loadCallbacks.remove("broadcast");
        boolean loading = broadcastRequest != null; broadcastRequest = null;
        boolean stopped = stopBroadcastSession();
        // Completed sessions no longer occupy broadcastSession, but their
        // samples may still ring. An explicit stop also owns those tails.
        for (UUID id : listenerVoices.keySet())
            stopped = cutVoices(id, Voice::broadcast) || stopped;
        return stopped || loading;
    }
    private boolean stopBroadcastSession() {
        boolean waiting = cancelPendingBroadcast();
        boolean queued = queuedBroadcastStart != null;
        queuedBroadcastStart = null;
        if (broadcastSession == null) return waiting || queued;
        broadcastSession.cancelQuietly();
        broadcastSession = null;
        maybeStopClock();
        return true;
    }

    /** Drops a parsed song from the cache (call when a song is removed). */
    public void invalidateCache(String publicId) {
        SongLoad load;
        synchronized (songCache) {
            // Mark synchronously even when reload invalidates on a worker. A
            // queued callback must reject old data before main-thread cleanup runs.
            for (PendingSelection selection : pendingSelections.values()) {
                if (!selection.songId.equals(publicId)) continue;
                selection.invalidated = true;
                plugin.runMain(() -> {
                    if (pendingSelections.get(selectionKey(selection.request)) != selection
                            || !isCurrent(selection.request)) return;
                    Player feedback = plugin.getServer().getPlayer(selection.request.caller());
                    if (feedback != null) readyToStart(feedback, publicId, selection.request);
                });
            }
            load = loadingSongs.remove(publicId);
            songCache.remove(publicId);
        }
        if (load != null) load.future().cancel(true);
    }

    /** Audio changes do not invalidate immutable note data or unrelated parse requests. */
    public void invalidateSampleCaches() { vanillaRingCache = null; }

    /** Called by the pack builder off-thread after audio changes. Published to ongoing sessions too. */
    public void refreshSampleTimings() {
        double[] durations = new double[INSTRUMENT_SOUNDS.length];
        for (int i=0; i<durations.length; i++) durations[i] = plugin.getResourcePackService().sampleSeconds(i);
        vanillaRingCache = durations;
    }

    /**
     * True once at least one sample length has actually been measured. If the
     * vanilla sounds had not finished downloading when the first song loaded,
     * every entry is -1; retrying later lets voice allocation upgrade from
     * its 1-second fallback to real ring times without a restart.
     */
    private static boolean hasMeasured(double[] ringTimes) {
        for (double seconds : ringTimes) {
            if (seconds > 0) return true;
        }
        return false;
    }

    /** Cold loads may precede the first pack build; initialize the vanilla fallback timings off-thread. */
    private void measureVanillaRingTimes() {
        if (vanillaRingCache == null || !hasMeasured(vanillaRingCache)) refreshSampleTimings();
    }

    /** Stops everything; call from onDisable. */
    public void stopAll() {
        pendingSelections.clear();
        playRequests.clear(); broadcastRequest = null; loadCallbacks.clear();
        loadingSongs.values().forEach(f -> f.future().cancel(true)); loadingSongs.clear();
        songCache.clear();
        for (UUID id : pendingStarts.keySet().toArray(UUID[]::new)) {
            cancelPending(id);
        }
        for (UUID id : sessions.keySet().toArray(UUID[]::new)) {
            stop(id);
        }
        stopBroadcast();
        for (UUID id : listenerVoices.keySet()) cutVoices(id, _ -> true);
        listenerVoices.clear();
        shutdownClock();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        pendingSelections.entrySet().removeIf(entry -> entry.getValue().request.caller().equals(id)
                || id.equals(entry.getValue().request.listener()));
        stop(id);
        playRequests.entrySet().removeIf(e -> e.getValue().caller().equals(id)
                || id.equals(e.getValue().listener()));
        PlayRequest waiting = broadcastRequest;
        if (waiting != null && waiting.caller().equals(id)) { broadcastRequest = null; cancelPendingBroadcast(); queuedBroadcastStart = null; }
        listenerVoices.remove(id);
        loadCallbacks.entrySet().removeIf(e -> e.getValue().feedback().getUniqueId().equals(id));
        Playback broadcast = broadcastSession;
        if (broadcast != null) broadcast.customWarnings.remove(id);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Parses a song for inspection (/nbw analyze) without playing it,
     * reusing the same LRU cache playback uses so repeat calls are free.
     */
    public void inspect(String publicId, Player feedback, Consumer<NbsSong> onLoaded) {
        loadSong(publicId, feedback, new InspectionOwner(feedback.getUniqueId()), onLoaded);
    }

    /**
     * A read-only view of one active session, for "/nbw playing". Carries the
     * display title and elapsed/total seconds so the command needs no access
     * to the internal Playback type.
     */
    public record ActiveSong(String who, boolean broadcast, String title,
                             double elapsedSeconds, double totalSeconds, boolean fading) {}

    /**
     * Snapshot of everything currently playing: the broadcast first (if any),
     * then each personal session. Personal sessions that are silenced under a
     * live broadcast are still included, so an admin sees the full
     * picture rather than only what is audible.
     */
    public List<ActiveSong> activeSongs() {
        List<ActiveSong> out = new ArrayList<>();
        Playback broadcast = broadcastSession;
        if (broadcast != null) {
            out.add(new ActiveSong("@a", true, titleOf(broadcast),
                    broadcast.elapsedSeconds, broadcast.song.getDurationSeconds(),
                    broadcast.isFading()));
        }
        for (Map.Entry<UUID, Playback> entry : sessions.entrySet()) {
            Playback pb = entry.getValue();
            Player owner = plugin.getServer().getPlayer(entry.getKey());
            String name = owner != null ? owner.getName() : entry.getKey().toString();
            out.add(new ActiveSong(name, false, titleOf(pb),
                    pb.elapsedSeconds, pb.song.getDurationSeconds(), pb.isFading()));
        }
        return out;
    }

    private String titleOf(Playback pb) {
        return displayTitle(pb.song, pb.publicId);
    }

    /**
     * The song this player is currently hearing, or null. A live broadcast
     * overrides personal playback; the player's own session is used only when
     * no broadcast is running.
     */
    public String currentSongIdFor(UUID playerId) {
        Playback broadcast = broadcastSession;
        if (broadcast != null) return broadcast.publicId;
        Playback own = sessions.get(playerId);
        return own != null ? own.publicId : null;
    }

    private void loadSong(String id, Player feedback, Object ownerKey, Consumer<NbsSong> onLoaded) {
        loadCallbacks.remove(ownerKey);
        NbsSong cached = songCache.get(id);
        if (cached != null) { onLoaded.accept(cached); return; }
        java.util.concurrent.atomic.AtomicBoolean created = new java.util.concurrent.atomic.AtomicBoolean();
        SongLoad loading = loadingSongs.computeIfAbsent(id, key -> {
            created.set(true);
            return new SongLoad(UUID.randomUUID(), plugin.getWorkPool().submit(() -> {
                try {
                    NbsSong song = NbsParser.parse(plugin.getDownloadManager().getNbsFile(key));
                    measureVanillaRingTimes(); return song;
                } catch (IOException e) { throw new UncheckedIOException(e); }
            }));
        });
        loadCallbacks.put(ownerKey, new LoadCallback(id, loading.token(), feedback, onLoaded));
        if (!created.get()) return; // one completion and one pending callback per listener/inspection
        loading.future().whenComplete((song, error) -> {
            if (plugin.isStopping()) return;
            plugin.runMain(() -> {
                boolean valid;
                synchronized (songCache) {
                    valid = loadingSongs.remove(id, loading) && plugin.getDownloadManager().isDownloaded(id);
                    if (valid && error == null) songCache.put(id, song);
                }
                for (var entry : List.copyOf(loadCallbacks.entrySet())) {
                    LoadCallback callback = entry.getValue();
                    if (!callback.id().equals(id) || !callback.token().equals(loading.token())) continue;
                    if (!loadCallbacks.remove(entry.getKey(), callback) || !callback.feedback().isOnline()) continue;
                    if (valid && error == null) callback.accept().accept(song);
                    else callback.feedback().sendMessage(plugin.getMessages().get("playback.read-error",
                            Placeholder.unparsed("reason", error == null ? "Song changed; select it again" : Throwables.describe(error))));
                }
            });
        });
    }

    // ------------------------------------------------------------------

    /**
     * A chord, or its melodic/percussion group, scheduled slightly off its exact tick.
     * <p>
     * The limiter gain is captured HERE, at schedule time, from the size of
     * the whole chord, preserving its balance when percussion and melody
     * use different timing offsets. Equal deadlines keep insertion order.
     */
    private record Scheduled(long dueNanos, long sequence, int from, int to,
                             float chordGain, int group, boolean ordered) {}

    /**
     * One listener's state, captured on the MAIN thread once per game tick.
     * The clock thread reads only this — never the live world — so nothing
     * touches Bukkit off-thread.
     * <p>
     * forward/right are null unless stereo panning applies; velocity is in
     * blocks per second and leadSeconds is the listener's round-trip time.
     */
    private record ListenerSnapshot(Player player, UUID id, org.bukkit.Location ear,
                            double[] forward, double[] right, double[] velocity, long sampledNanos, double leadSeconds,
                            float volume, Map<String, Double> eventSeconds,
                            double eyeHeight, Voices voices) {}
    /** Last sampled ear position per listener, for velocity. Main thread only. */
    private record EarSample(org.bukkit.World world, double x, double y, double z, long nanos) {}

    private class Playback {
        // A small token lets tails retain ownership without retaining the song/session.
        private final Object voiceOwner = new Object();

        /** Session owner for personal playback; null for a broadcast. */
        private final Player owner;
        private final NbsSong song;
        private final String publicId;
        /** [customIndex][octave+3] -> full sound key, null when unavailable. */
        private final String[][] customSoundKeys;
        /** The same, minus the "nbw:" prefix, for immutable manifest lookups. */
        private final String[][] customSoundEvents;

        /** Refreshed on the main thread each tick; read by the clock thread. */
        private volatile List<ListenerSnapshot> listeners = List.of();
        /** Main-thread warning fingerprints; unchanged problems are reported only once. */
        private final Map<UUID, String> customWarnings = new java.util.HashMap<>();
        private volatile boolean started = false;
        private volatile boolean cancelled = false;
        private volatile boolean finishing = false;
        /** Read by the action bar (main thread), written by the stepping thread. */
        private volatile double elapsedSeconds = 0;

        /**
         * Only the stepping thread writes elapsedSeconds (the main thread just
         * reads it for the action bar), so this read-then-write cannot lose
         * an update.
         */
        @SuppressWarnings("NonAtomicOperationOnVolatileField")
        private void advanceClock(double seconds) {
            elapsedSeconds += seconds;
        }

        /** Timing state, only ever touched by the stepping thread. */
        private double currentTempo;
        private double position = 0;
        private int nextChord;
        private int loopsDone = 0;
        private long lastStepNanos = 0;

        private org.bukkit.scheduler.BukkitTask ticker;
        private int actionBarCounter = 0;
        /**
         * Scratch Location reused for every panned emission. panLocation used
         * to clone the ear Location AND a direction Vector per panned note
         * per listener — steady allocation churn on the clock thread during
         * dense stereo songs, for objects discarded microseconds later.
         * playSound reads the coordinates synchronously and does not retain
         * the Location, and each session is stepped by exactly one driver
         * thread, so one mutable scratch per session is safe.
         */
        private final org.bukkit.Location panScratch =
                new org.bukkit.Location(null, 0, 0, 0);

        /** Ear positions from the previous snapshot, for velocity. Main thread only. */
        private Map<UUID, EarSample> lastEars = Map.of();

        /**
         * Where a world-placed note should sound for this listener.
         * <p>
         * Two corrections over placing it at the last snapshot:
         * <p>
         * 1. Panning is by DIRECTION. The note sits at the angle whose sine
         *    is the pan, pan-distance from the camera. Previously it was put
         *    straight to the side at pan x pan-distance, and a sound directly
         *    to the side pans fully however close it is, so every panned note
         *    came out hard left or hard right.
         * <p>
         * 2. The listener keeps moving. The snapshot is up to a tick old, and
         *    the client receives the packet about one round trip after the
         *    position the server last heard from it. The note is led along
         *    the listener's velocity by that time, so it starts where they
         *    actually are rather than behind them -- the gap that made notes
         *    lurch sideways and fade while sprinting or flying.
         * <p>
         * A note still stays where it started once it plays: Minecraft cannot
         * move a playing sound. follow-player avoids that drift entirely.
         */
        private org.bukkit.Location panLocation(ListenerSnapshot listener, float pan, long now) {
            org.bukkit.Location ear = listener.ear();
            double[] velocity = listener.velocity();
            double ahead = Math.min(MAX_LEAD_SECONDS,
                    Math.max(0, (now - listener.sampledNanos()) / 1_000_000_000.0) + listener.leadSeconds());
            double x = velocity[0] * ahead, y = velocity[1] * ahead, z = velocity[2] * ahead;
            double lead = Math.sqrt(x * x + y * y + z * z);
            if (lead > MAX_LEAD_BLOCKS) { double scale = MAX_LEAD_BLOCKS / lead; x *= scale; y *= scale; z *= scale; }
            if (listener.forward() != null && pan != 0f) {
                double[] offset = PanGeometry.offset(pan, panDistance, listener.forward(), listener.right());
                x += offset[0]; y += offset[1]; z += offset[2];
            }
            if (x == 0 && y == 0 && z == 0) return ear;
            panScratch.setWorld(ear.getWorld());
            panScratch.setX(ear.getX() + x);
            panScratch.setY(ear.getY() + y);
            panScratch.setZ(ear.getZ() + z);
            return panScratch;
        }


        /**
         * nanoTime the fade began, or 0 when not fading. Volatile: set on the
         * main thread by stop(), read by the stepping thread.
         */
        private volatile long fadeStartNanos;

        /** Notes awaiting their humanised moment. Stepping thread only. */
        private final java.util.PriorityQueue<Scheduled> pending = new java.util.PriorityQueue<>(
                java.util.Comparator.comparingLong(Scheduled::dueNanos).thenComparingLong(Scheduled::sequence));
        private long scheduledSequence;
        /**
         * The current "where the beat sits" offset, in milliseconds. It drifts
         * a little each chord (a random walk) instead of being redrawn from
         * scratch, so timing wanders like a human keeping loose time rather
         * than jittering independently note to note. Stepping thread only.
         */
        private double timingOffsetMs = 0;

        Playback(Player owner, NbsSong song, String publicId) {
            this.owner = owner;
            this.song = song;
            this.publicId = publicId;
            this.currentTempo = NbsSong.clampTempo(song.getTempo());

            List<String> files = song.getCustomSoundFiles();
            this.customSoundKeys = new String[files.size()][7];
            this.customSoundEvents = new String[files.size()][7];
            if (publicId != null) {
                for (int i = 0; i < files.size(); i++) {
                    if (files.get(i).isBlank()) continue;
                    String base = ResourcePackService
                            .customSoundEvent(publicId, files.get(i));
                    for (int octave = -3; octave <= 3; octave++) {
                        String event = octave == 0 ? base
                                : base + (octave < 0 ? "_m" + (-octave) : "_p" + octave);
                        customSoundEvents[i][octave + 3] = event;
                        customSoundKeys[i][octave + 3] = "nbw:" + event;
                    }
                }
            }
        }

        /** Main thread. */
        void start() {
            refreshListeners();
            lastStepNanos = System.nanoTime();
            ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::mainTick, 1L, 1L);
            started = true;   // the clock must not step us before this point
        }

        private boolean isBroadcast() {
            return owner == null;
        }

        // --------------------------------------------------------------
        // Main thread: snapshot + action bar (+ timing, in low-precision mode)
        // --------------------------------------------------------------

        private void mainTick() {
            if (cancelled) return;

            if (!isBroadcast() && !owner.isOnline()) {
                // Identity-checked: if the player already started a NEW song,
                // that session owns this slot now and must not be evicted by
                // an older one tidying itself up.
                if (!sessions.remove(owner.getUniqueId(), this)) { cancelLettingRing(); return; }
                // This path bypasses finish(), so anything queued behind the
                // fade would otherwise sit in the map forever.
                queuedStarts.remove(owner.getUniqueId());
                cancelQuietly();
                maybeStopClock();
                return;
            }

            refreshListeners();

            if (showActionBar && ++actionBarCounter >= 10) {
                actionBarCounter = 0;
                sendActionBar();
            }

            // Without the clock thread, the game tick drives playback (50 ms
            // resolution — notes above 20 t/s get batched).
            if (!highPrecision && driverLock.tryLock()) {
                try { if (!highPrecision) step(); } finally { driverLock.unlock(); }
            }
        }

        /** Main thread: capture everything the clock thread will need. */
        private void refreshListeners() {
            ResourcePackService pack = plugin.getResourcePackService();
            boolean packActive = pack.isActive();
            List<ListenerSnapshot> snapshot = new ArrayList<>();
            Map<UUID, EarSample> ears = new java.util.HashMap<>();
            long sampled = System.nanoTime();

            for (Player player : audience(pack, packActive)) {
                float volume = plugin.getVolumeManager().getFactor(player.getUniqueId());
                if (volume <= 0f) continue;              // muted: emit nothing
                warnUnavailableCustomAudio(player, pack);
                org.bukkit.Location ear = player.getEyeLocation();
                double[] forward = stereoPanning ? PanGeometry.forward(ear.getYaw(), ear.getPitch()) : null;
                double[] right = stereoPanning ? PanGeometry.right(ear.getYaw()) : null;
                double[] velocity = NO_MOTION;
                double leadSeconds = 0;
                if (!followPlayer) {
                    // Attached sounds follow the player client-side already;
                    // only world-placed notes need the movement estimate.
                    EarSample previous = lastEars.get(player.getUniqueId());
                    ears.put(player.getUniqueId(), new EarSample(ear.getWorld(), ear.getX(), ear.getY(), ear.getZ(), sampled));
                    if (previous != null && previous.world() == ear.getWorld()) {
                        double seconds = (sampled - previous.nanos()) / 1_000_000_000.0;
                        double dx = ear.getX() - previous.x(), dy = ear.getY() - previous.y(), dz = ear.getZ() - previous.z();
                        // A teleport or a long gap is not motion to extrapolate.
                        if (seconds > 0 && seconds < 1 && dx * dx + dy * dy + dz * dz < TELEPORT_BLOCKS * TELEPORT_BLOCKS)
                            velocity = new double[]{dx / seconds, dy / seconds, dz / seconds};
                    }
                    leadSeconds = Math.clamp(player.getPing(), 0, 1000) / 1000.0;
                }
                // Voice accounting is created here, on the main thread, for
                // online listeners only. The clock thread uses this reference;
                // creating it there re-registered players who had just quit
                // while a stale snapshot was still being played, and nothing
                // ever removed those entries again.
                Voices voices = listenerVoices.computeIfAbsent(player.getUniqueId(), _ -> new Voices());
                snapshot.add(new ListenerSnapshot(player, player.getUniqueId(), ear, forward, right, velocity, sampled, leadSeconds,
                        volume, pack.eventDurations(player.getUniqueId()), player.getEyeHeight(), voices));
            }
            this.lastEars = ears;
            this.listeners = List.copyOf(snapshot);
        }

        /** Checks actual pack availability after the listener's loading/settling wait. */
        private void warnUnavailableCustomAudio(Player player, ResourcePackService pack) {
            if (song.getCustomNoteCount() == 0) return;
            UUID id = player.getUniqueId();
            boolean loaded = pack.isPackLoaded(id);
            String state = loaded + ":" + pack.getPackSha1();
            if (state.equals(customWarnings.put(id, state))) return;
            int missing = 0;
            List<String> files = new ArrayList<>();
            for (int i = 0; i < customSoundEvents.length; i++) {
                int count = song.getCustomNoteCount(i);
                if (count == 0) continue;
                if (!loaded || !pack.hasEvent(id, customSoundEvents[i][3])) {
                    missing += count;
                    if (files.size() < 5) files.add(io.github.noteblockworldmc.util.Limits.display(song.getCustomSoundFiles().get(i), 120));
                }
            }
            if (missing == 0) return;
            player.sendMessage(plugin.getMessages().get(loaded
                            ? "playback.custom-audio-missing" : "playback.custom-audio-no-pack",
                    Placeholder.unparsed("count", String.valueOf(missing)),
                    Placeholder.unparsed("files", String.join(", ", files))));
        }

        /** Main thread. Who hears this session right now. */
        private List<Player> audience(ResourcePackService pack, boolean packActive) {
            if (!isBroadcast()) {
                // A live broadcast takes over every player's channel, so a
                // personal session goes silent for its owner while one is
                // running -- it keeps ticking in the background and is heard
                // again the moment the broadcast ends.
                if (broadcastSession != null) return List.of();
                return owner.isOnline() && canHear(pack, packActive, owner)
                        ? List.of(owner) : List.of();
            }
            // The broadcast is heard by everyone who can hear it; it no longer
            // yields to personal sessions.
            List<Player> result = new ArrayList<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (!canHear(pack, packActive, online)) continue;
                result.add(online);
            }
            return result;
        }

        /**
         * A player whose pack hasn't resolved yet (just reconnected, or a
         * rebuild invalidated everyone) would hear a mangled version: custom
         * instruments missing, out-of-range notes folded to the wrong octave.
         * Stay silent for them until their client is ready, then they join
         * the song in progress.
         */
        private boolean canHear(ResourcePackService pack, boolean packActive, Player player) {
            // Running songs only pause while a push is really being applied;
            // a merely requested rebuild must not mute them (starts still wait).
            return !packActive || pack.canHear(player.getUniqueId());
        }

        // --------------------------------------------------------------
        // Stepping: the clock thread (or the main tick in low-precision mode)
        // --------------------------------------------------------------

        /** Re-anchors the clock so a pause (e.g. a settings reload) isn't
         *  mistaken for elapsed song time. */
        private volatile boolean needsResync;
        void resync() { needsResync = true; }

        /** Advances by real elapsed time and emits any notes now due. */
        void step() {
            if (!started || cancelled) return;

            long now = System.nanoTime();
            if (needsResync) { needsResync = false; lastStepNanos = now; return; }

            // Checked on EVERY step, not only when the song advances to a new
            // tick: the tick gate meant a slow song (0.2 t/s = one tick every
            // five seconds) would keep the session alive long after its ramp
            // had reached silence.
            if (fadeStartNanos != 0 && fadeGain(now) <= 0f) {
                requestFinish();
                return;
            }

            double delta = (now - lastStepNanos) / 1_000_000_000.0;
            lastStepNanos = now;
            // A server hiccup must not dump thousands of notes at once
            if (delta <= 0) return;
            delta = Math.min(delta, 0.25);

            advanceClock(delta);
            List<ListenerSnapshot> snapshot = listeners;
            double end = song.getDurationSeconds();
            while (!cancelled) {
                position = song.positionAtSeconds(elapsedSeconds);
                int upTo = Math.min(song.getLengthTicks(), (int)Math.floor(position + 1e-9));
                while (nextChord < song.chordCount() && song.chordTick(nextChord) <= upTo) {
                    if (cancelled) return;
                    int chord = nextChord++;
                    currentTempo = song.getTempoAt(song.chordTick(chord));
                    if (humanize) scheduleHumanised(chord, now);
                    else emitChord(song.chordStart(chord), song.chordEnd(chord), snapshot);
                }
                currentTempo = song.getTempoAt(Math.max(0, upTo));
                if (!pending.isEmpty()) drainPending(now, snapshot);
                if (elapsedSeconds + 1e-10 < end) break;
                if (shouldLoop()) {
                    double carry = Math.max(0, elapsedSeconds - end);
                    restartLoop(); advanceClock(carry);
                } else {
                    if (pending.isEmpty()) requestFinish();
                    break;
                }
            }
        }

        /**
         * Scatters a tick's notes in time.
         * <p>
         * The tick's TRUE moment is reconstructed from how far past it the
         * clock has drifted, so jitter is measured from where the note should
         * have landed rather than from the step boundary — otherwise the clock
         * grid would just be traded for a coarser one.
         * <p>
         * Drift is bounded to twice the spread. A positive spread is added,
         * and negative delays are clamped to zero: melodic delays can reach
         * three times the spread.
         */
        private void scheduleHumanised(int chord, long now) {
            // Nothing to schedule if no one can hear it: without this the
            // queue would fill and drain into an empty audience every tick.
            int from = song.chordStart(chord), to = song.chordEnd(chord);
            if (from == to || listeners.isEmpty()) return;
            int tick = song.chordTick(chord);
            long tickNanos = now - (long)((elapsedSeconds - song.getSecondsUpTo(tick)) * 1_000_000_000.0);

            float chordGain = limiterGain(to - from);
            var random = java.util.concurrent.ThreadLocalRandom.current();

            // (A) One timing offset for the WHOLE chord, so notes struck
            // together stay together instead of smearing. (B) That offset is a
            // random walk from the last chord's, blended with an independent
            // draw by humanizeDrift: at 1.0 timing wanders smoothly like a
            // performer keeping loose time; at 0.0 each chord is independent.
            // Timing jitter only reads as human while it stays small next to the
            // gap between notes. That gap is the TICK length, which depends on
            // tempo -- so a fixed value that feels right at 10 t/s (6ms in a
            // 100ms tick) exceeds a whole tick at 180 t/s and shuffles notes
            // past each other. Cap it as a fraction of the current tick so fast
            // songs degrade to exact timing on their own.
            //
            // This binds only above ~25 t/s; every ordinary song is untouched.
            // It also follows mid-song tempo changes, which no static setting
            // could do. Velocity humanisation is unaffected -- it has nothing
            // to do with timing and stays useful at any tempo.
            double spread = Math.min(humanizeTimingMs,
                    1000.0 / Math.max(0.001, currentTempo) * MAX_JITTER_FRACTION);
            if (spread > 0) {
                double independent = random.nextDouble(-spread, spread);
                double wandered = timingOffsetMs + random.nextDouble(-spread, spread) * 0.5;
                timingOffsetMs = humanizeDrift * wandered + (1 - humanizeDrift) * independent;
                // Keep the wander from running away over a long song
                double bound = spread * 2;
                timingOffsetMs = Math.clamp(timingOffsetMs, -bound, bound);
            } else {
                timingOffsetMs = 0;
            }

            long melody = tickNanos + (long) (Math.max(0, timingOffsetMs + spread) * 1_000_000.0);
            long drums = tickNanos + (long) (Math.max(0, timingOffsetMs * humanizePercussion + spread) * 1_000_000.0);
            if (melody == drums) {
                pending.add(new Scheduled(melody, scheduledSequence++, from, to, chordGain, 0, voiceAllocation));
            } else {
                boolean hasMelody = false, hasDrums = false;
                for (int i = from; i < to; i++) {
                    if (isPercussion(song.noteInstrument(i))) hasDrums = true; else hasMelody = true;
                }
                if (hasMelody) pending.add(new Scheduled(melody, scheduledSequence++, from, to, chordGain, 1, voiceAllocation));
                if (hasDrums) pending.add(new Scheduled(drums, scheduledSequence++, from, to, chordGain, 2, voiceAllocation));
            }
        }

        private void drainPending(long now, List<ListenerSnapshot> snapshot) {
            while (!pending.isEmpty() && pending.peek().dueNanos() <= now) {
                Scheduled due = pending.poll();
                for (int position = due.from(); position < due.to(); position++) {
                    int note = due.ordered() ? song.orderedNoteIndex(position) : position;
                    boolean drum = isPercussion(song.noteInstrument(note));
                    if (due.group() == 1 && drum || due.group() == 2 && !drum) continue;
                    emitNote(note, due.chordGain(), snapshot);
                }
            }
        }

        /** Reads a chord directly from compact storage, with no lists, note records or sorting. */
        private void emitChord(int from, int to, List<ListenerSnapshot> snapshot) {
            if (from == to || snapshot.isEmpty()) return;
            float chordGain = limiterGain(to - from);
            boolean ordered = voiceAllocation;
            for (int position = from; position < to; position++) {
                emitNote(ordered ? song.orderedNoteIndex(position) : position, chordGain, snapshot);
            }
        }

        /** Emits a single note to every listener. */
        private void emitNote(int noteIndex, float chordGain, List<ListenerSnapshot> snapshot) {
            // Sampled once per note so every listener hears the same point on
            // the ramp and a single note cannot straddle two fade values.
            float fade = fadeGain(System.nanoTime());
            if (fade <= 0f) return;
            // One random volume nudge per note OCCURRENCE, shared by all
            // listeners, so a broadcast stays coherent between players.
            float human = humanize && humanizeVelocity > 0
                    ? (float) (1 + java.util.concurrent.ThreadLocalRandom.current()
                            .nextDouble(-humanizeVelocity, humanizeVelocity))
                    : 1f;

            int instrument = song.noteInstrument(noteIndex), octave = song.noteOctave(noteIndex);
            int custom = song.noteCustomIndex(noteIndex);
            float notePitch = song.notePitch(noteIndex), noteVolume = song.noteVolume(noteIndex), pan = song.notePan(noteIndex);
            for (ListenerSnapshot listener : snapshot) {
                String key = null;
                double seconds = -1;
                boolean shifted = false;
                Map<String, Double> manifest = listener.eventSeconds();
                if (custom >= 0) {
                    int slot = octave + 3;
                    String event = customSoundEvents[custom][slot];
                    if (event != null) seconds = manifest.getOrDefault(event, -1.0);
                    if (!(seconds > 0)) {
                        slot = 3; event = customSoundEvents[custom][slot];
                        seconds = event == null ? -1 : manifest.getOrDefault(event, -1.0);
                    }
                    if (!(seconds > 0)) continue;
                    key = customSoundKeys[custom][slot];
                    shifted = slot != 3;
                } else {
                    if (octave != 0) {
                        String event = EXTENDED_SOUND_EVENTS[instrument][octave + 3];
                        seconds = manifest.getOrDefault(event, -1.0);
                        if (seconds > 0) { shifted = true; key = EXTENDED_SOUND_KEYS[instrument][octave + 3]; }
                    }
                    if (!(seconds > 0)) seconds = vanillaSeconds(instrument);
                }
                float runtimePitch = runtimePitch(notePitch, octave, shifted);
                float volume = Math.min(1f, noteVolume * listener.volume() * chordGain * human * fade);
                // At the default zero floor every positive-volume note is
                // emitted; exact zero (or an invalid NaN) still consumes no
                // client channel.
                if (!(volume > 0f) || volume < audibleFloor) continue;
                if (!(seconds > 0)) seconds = 1;
                long now = System.nanoTime();
                Voices voices = listener.voices();
                // A hard stop serializes with one packet emission, never with an entire clock step.
                synchronized (voices) {
                    if (cancelled || !isBroadcast() && broadcastSession != null) continue;
                    voices.expire(now);
                    if (voiceAllocation && voices.ends.size() >= voiceCeiling) continue;
                    long seed = java.util.concurrent.ThreadLocalRandom.current().nextLong();
                    if (followPlayer) {
                        float attached = attachedVolume(volume, listener.eyeHeight());
                        if (key != null) listener.player().playSound(listener.player(), key, SoundCategory.RECORDS, attached, runtimePitch, seed);
                        else listener.player().playSound(listener.player(), INSTRUMENT_SOUNDS[instrument], SoundCategory.RECORDS, attached, runtimePitch, seed);
                    } else {
                        org.bukkit.Location at = panLocation(listener, pan, now);
                        // A panned note sits pan-distance away; send the volume
                        // that arrives at the intended gain over that distance.
                        float placed = attachedVolume(volume, listener.forward() != null && pan != 0f ? panDistance : 0);
                        if (key != null) listener.player().playSound(at, key, SoundCategory.RECORDS, placed, runtimePitch, seed);
                        else listener.player().playSound(at, INSTRUMENT_SOUNDS[instrument], SoundCategory.RECORDS, placed, runtimePitch, seed);
                    }
                    if (voices.ends.size() >= 8192) voices.ends.poll();
                    voices.ends.add(new Voice(now + (long)(seconds / Math.max(.5, runtimePitch) * 1_000_000_000L),
                            voiceOwner, isBroadcast(), key != null ? key : INSTRUMENT_SOUNDS[instrument]));
                }
            }
        }
        private static float runtimePitch(float pitch, int octave, boolean shifted) {
            if (shifted || octave == 0) return pitch;
            double folded = Math.scalb((double) pitch, octave);
            return (float) Math.clamp(folded, 0.5, 2.0);
        }
        private double vanillaSeconds(int instrument) {
            double[] current = vanillaRingCache;
            return current == null || instrument >= current.length ? -1 : current[instrument];
        }
        /**
         * Starts the ramp down. Returns false if fading is disabled or already
         * under way, so a second /nbw stop cuts immediately rather than
         * politely restarting the fade.
         */
        private boolean isFading() {
            return fadeStartNanos != 0;
        }

        private boolean beginFade() {
            if (fadeOutMs <= 0 || fadeStartNanos != 0 || cancelled) return false;
            fadeStartNanos = System.nanoTime();
            return true;
        }

        /**
         * Volume multiplier for notes struck right now: 1 normally, ramping to
         * 0 across the fade window.
         */
        private float fadeGain(long now) {
            long started = fadeStartNanos;
            if (started == 0) return 1f;
            double elapsed = (now - started) / 1_000_000.0;
            if (elapsed >= fadeOutMs) return 0f;
            return (float) (1.0 - elapsed / fadeOutMs);
        }

        private boolean shouldLoop() {
            if (!respectLoop || !song.isLoopEnabled()) return false;
            // An empty song would otherwise "loop" every step forever
            if (song.getNoteCount() == 0) return false;
            int max = song.getMaxLoopCount();
            // The NBS field is the number of times the song LOOPS, not the
            // number of times it plays: OpenNBS documents it as "the amount of
            // times to loop, 0 = infinite". So a count of 1 means one repeat
            // (two plays through). Comparing loopsDone + 1 lost exactly one
            // repeat at every finite count, and made a count of 1 do nothing.
            return max == 0 || loopsDone < max;      // 0 = loop forever
        }

        /** Jumps back to the song's loop start, restoring the tempo there. */
        private void restartLoop() {
            loopsDone++;
            timingOffsetMs = 0;
            int start = Math.clamp(song.getLoopStartTick(), 0, Math.max(0, song.getLengthTicks()));
            position = start;
            nextChord = song.firstChordAfter(start - 1);
            elapsedSeconds = song.getSecondsUpTo(start);
            // A tempo changer earlier in the song may have altered the tempo;
            // resume with whatever is in effect at the loop point.
            currentTempo = song.getTempoAt(start);
        }

        // --------------------------------------------------------------
        // Teardown (always ends up on the main thread)
        // --------------------------------------------------------------

        /** Safe to call from the clock thread: hops to the main thread. */
        private void requestFinish() {
            if (finishing) return;
            finishing = true;
            cancelled = true;             // stop stepping immediately
            plugin.runMain(this::finish);
        }

        private void finish() {
            // Reached only by a natural ending or a completed fade — in both
            // cases the remaining notes should decay, not be chopped.
            if (isBroadcast()) {
                // A stale completion must not consume a newer session's queued selection.
                if (broadcastSession != this) {
                    cancelLettingRing();
                    return;
                }
                broadcastSession = null;
                cancelLettingRing();
                Runnable queued = queuedBroadcastStart;
                queuedBroadcastStart = null;
                if (queued != null) {
                    queued.run();
                } else if (fadeStartNanos == 0) {
                    // A completed fade was a stop someone already confirmed.
                    plugin.getServer().sendMessage(plugin.getMessages().get("playback.finished"));
                }
            } else {
                // Identity-checked, mirroring the broadcast branch above: a
                // stale session finishing must not remove the song the player
                // has since started.
                if (!sessions.remove(owner.getUniqueId(), this)) { cancelLettingRing(); return; }
                cancelLettingRing();
                Runnable queued = queuedStarts.remove(owner.getUniqueId());
                if (queued != null) {
                    // A switch, not an ending: "Finished" would be noise right
                    // before "Now playing". The outgoing tail is left ringing
                    // and decays under the new song's opening.
                    if (owner.isOnline()) queued.run();
                } else if (owner.isOnline() && fadeStartNanos == 0) {
                    // Only a natural ending: a faded stop was already
                    // answered with "Playback stopped".
                    owner.sendMessage(plugin.getMessages().get("playback.finished"));
                }
            }
            maybeStopClock();
        }

        private void sendActionBar() {
            double total = song.getDurationSeconds();
            double elapsed = elapsedSeconds;
            int filled = total <= 0 ? 0
                    : (int) Math.round(barLength * Math.min(1.0, elapsed / total));

            var messages = plugin.getMessages();
            Component bar = messages.get("playback.bar-filled",
                            Placeholder.unparsed("chars", "▬".repeat(filled)))
                    .append(messages.get("playback.bar-empty",
                            Placeholder.unparsed("chars", "▬".repeat(barLength - filled))));

            String title = displayTitle(song, publicId);

            Component actionBar = messages.get("playback.actionbar",
                    Placeholder.unparsed("title", title),
                    Placeholder.unparsed("elapsed", formatTime(elapsed)),
                    Placeholder.unparsed("total", formatTime(total)),
                    Placeholder.component("bar", bar));

            for (ListenerSnapshot listener : listeners) {
                listener.player().sendActionBar(actionBar);
            }
        }

        /** Ends the session and cuts anything still ringing. Main thread. */
        void cancelQuietly() {
            cancel(true);
        }

        /**
         * Ends the session but leaves ringing notes to decay on their own.
         * <p>
         * This is what makes a fade audible. Minecraft cannot alter a sound
         * that is already playing, so the ramp only quietens notes struck
         * DURING it -- everything struck in the second or so before still
         * rings at full volume. Calling stopSound at the end therefore
         * guillotined exactly the loudest part, which is why the fade was
         * barely perceptible: it was a delayed hard cut.
         * <p>
         * The same applies at a song's natural end, where cutting truncated
         * the final chord instead of letting it ring.
         */
        void cancelLettingRing() {
            cancel(false);
        }

        private void cancel(boolean cutRingingSound) {
            cancelled = true;
            // Deliberately NOT clearing `pending` here: this runs on the main
            // thread while the clock thread may be inside poll()/add() on the
            // same non-thread-safe PriorityQueue. `cancelled` already stops
            // stepping, and the queue dies with the session, so clearing it
            // buys nothing and risks corrupting the queue mid-operation.
            if (ticker != null) {
                ticker.cancel();
                ticker = null;
            }
            if (cutRingingSound) {
                if (isBroadcast()) {
                    for (UUID id : listenerVoices.keySet()) cutVoices(id, voice -> voice.owner() == voiceOwner);
                } else {
                    cutVoices(owner.getUniqueId(), voice -> voice.owner() == voiceOwner);
                }
            }
            // Natural endings and completed fades leave sounds ringing.
            for (ListenerSnapshot listener : listeners) {
                if (showActionBar && (isBroadcast() ? broadcastSession == null || broadcastSession == this
                        : sessions.get(owner.getUniqueId()) == null || sessions.get(owner.getUniqueId()) == this)) {
                    listener.player().sendActionBar(Component.empty());
                }
            }
        }
    }
}
