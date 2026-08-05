package dev.example.noteblockworldmc.playback;

import dev.example.noteblockworldmc.NoteblockWorldMCPlugin;
import dev.example.noteblockworldmc.nbs.NbsParser;
import dev.example.noteblockworldmc.nbs.NbsSong;
import dev.example.noteblockworldmc.nbs.PlayableNote;
import dev.example.noteblockworldmc.pack.ResourcePackService;
import dev.example.noteblockworldmc.util.Throwables;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Plays parsed NBS songs using note block sounds.
 *
 * Two session kinds:
 *  - PERSONAL: one per player, heard only by that listener.
 *  - BROADCAST: at most one server-wide, heard by everyone who can hear it.
 *
 * A live broadcast takes precedence: a player's personal session goes silent
 * while one is running, keeps advancing in the background, and is audible
 * again the moment the broadcast ends. Late joiners hear the remainder of a
 * broadcast automatically.
 *
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
     * [instrument][octave + 3] -> "nbw:<name>_m3".."nbw:<name>_p3"
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
    private volatile boolean showActionBar;
    private volatile int barLength;
    private volatile boolean respectLoop;
    private volatile boolean highPrecision;
    private volatile int precisionMs;
    /** Soft limiter: scales dense chords down to keep the client mixer clean. */
    private volatile boolean softLimiter;
    private volatile int limiterThreshold;
    /**
     * Shared high-resolution clock. Minecraft's scheduler only runs 20x a
     * second, so tick-driven playback quantises every note to a 50 ms grid —
     * a 40 t/s song arrives as clusters of two notes instead of an even run.
     * This thread steps every session every few milliseconds instead.
     *
     * It touches NOTHING from the Bukkit world: each session publishes a
     * listener snapshot (positions, volumes, pack state) from the main thread
     * once per tick, and the clock only reads that and emits sound packets.
     */
    private java.util.concurrent.ScheduledExecutorService clock;
    /**
     * A note below this contributes nothing audible, but still seizes one of
     * the client's ~250 sound channels for its whole sample -- evicting notes
     * you CAN hear. Not worth the channel.
     */
    private static final float AUDIBLE_FLOOR = 0.02f;

    /**
     * Voice allocation: keeps the number of simultaneously RINGING sounds
     * this session asks of the client under a ceiling.
     *
     * The client has ~250 sound channels for the whole game, and a note holds
     * one for its full sample duration, not just its onset. When the pool
     * overflows, the client evicts sounds arbitrarily — possibly the melody,
     * possibly footsteps. Tracking occupancy with MEASURED ring times (read
     * from each sample's Ogg granule position) lets us trim the quietest
     * notes of a chord only when genuinely near the ceiling, so sparse songs
     * are never touched and dense ones degrade deliberately.
     */
    /**
     * Milliseconds spent ramping down when a song is stopped; 0 = hard cut.
     *
     * Minecraft cannot alter the volume of a sound that is already playing,
     * so the ramp itself only quietens notes STRUCK during the window. What
     * makes the fade audible is that the session then ends WITHOUT cutting
     * the remainder: notes already ringing decay on their own. Ramp plus
     * natural decay together give a smooth tail. Cutting at the end of the
     * ramp instead — as this first did — merely delayed a hard cut.
     */
    private volatile int fadeOutMs;
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
     *
     * A world-placed note is pinned to where it was played, and the game keeps
     * recomputing its volume and direction as you move -- so every ringing note
     * fades and swings around you while you walk or fly. Attaching the sound to
     * the player removes that: the note travels with you.
     *
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
    /** Pack-resolution waits pending before personal playback starts. */
    /**
     * Songs waiting for the current one to finish fading out.
     *
     * Driven by the fade COMPLETING rather than a timer, so the new song can
     * never begin while the old session is still winding down — a timer would
     * race the finish hop to the main thread and the winner would cut the
     * outgoing tail.
     */
    private final Map<UUID, Runnable> queuedStarts = new ConcurrentHashMap<>();
    private final Map<UUID, org.bukkit.scheduler.BukkitTask> pendingStarts = new ConcurrentHashMap<>();
    /** Pack-resolution wait pending before a broadcast starts. Main thread only. */
    private org.bukkit.scheduler.BukkitTask pendingBroadcast;
    /** Give-up threshold for pack waits: start anyway after 60s. */
    private static final int PACK_WAIT_TIMEOUT_TICKS = 20 * 60;

    /** Measured ring time of each vanilla instrument's sample; -1 unknown. */
    private volatile double[] vanillaRingCache;
    /** Measured ring times of a song's custom sounds, by publicId. */
    private final Map<String, double[]> customRingCache = new ConcurrentHashMap<>();

    /** Small LRU cache of parsed songs (parsing re-reads the file otherwise). */
    private final Map<String, NbsSong> songCache =
            java.util.Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, NbsSong> eldest) {
                    return size() > 16;
                }
            });

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
        this.barLength = Math.max(4, Math.min(40,
                config.getInt("playback.actionbar-length", 20)));
        this.respectLoop = config.getBoolean("playback.respect-loop", true);
        this.fadeOutMs = Math.max(0, Math.min(5000,
                config.getInt("playback.fade-out-ms", 500)));
        this.voiceAllocation = config.getBoolean("playback.voice-allocation", true);
        // Upper bound is the CONFIGURED pool, not a hardcoded vanilla 247:
        // client mods can enlarge the audio engine's source pool, and an
        // admin who raised client-channels to match must be able to raise the
        // ceiling with it.
        int channels = PolyphonyAnalyzer.clientChannels(plugin);
        this.voiceCeiling = Math.max(16, Math.min(channels,
                config.getInt("playback.voice-ceiling", 200)));
        this.humanize = config.getBoolean("playback.humanize", true);
        this.humanizeTimingMs = Math.max(0, Math.min(50,
                config.getDouble("playback.humanize-timing-ms", 6)));
        this.humanizeVelocity = Math.max(0, Math.min(0.5,
                config.getDouble("playback.humanize-velocity", 0.04)));
        this.humanizePercussion = Math.max(0, Math.min(1,
                config.getDouble("playback.humanize-percussion-factor", 0.25)));
        this.humanizeDrift = Math.max(0, Math.min(1,
                config.getDouble("playback.humanize-drift", 0.7)));

        this.followPlayer = config.getBoolean("playback.follow-player", true);
        // Panning needs a world position to offset; attached sounds have none
        this.stereoPanning = !this.followPlayer
                && config.getBoolean("playback.stereo-panning", true);
        this.panDistance = Math.max(0.1, Math.min(8.0,
                config.getDouble("playback.pan-distance", 2.0)));
        this.softLimiter = config.getBoolean("playback.soft-limiter", true);
        this.limiterThreshold = Math.max(2, config.getInt("playback.limiter-threshold", 8));

        boolean wasHighPrecision = this.highPrecision;
        int wasPrecisionMs = this.precisionMs;
        this.highPrecision = config.getBoolean("playback.high-precision", true);
        this.precisionMs = Math.max(2, Math.min(25,
                config.getInt("playback.precision-ms", 2)));

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

    /**
     * The listener's "right" direction, from yaw alone.
     *
     * The obvious direction x UP cross product collapses to a zero vector
     * when a player looks straight up or down — normalising that yields NaN
     * coordinates, which would then be handed to playSound. Deriving it from
     * yaw is always unit length, and keeps the stereo field horizontal
     * (panning shouldn't tilt when someone glances at the sky).
     */
    private static org.bukkit.util.Vector rightVector(float yaw) {
        double radians = Math.toRadians(yaw);
        return new org.bukkit.util.Vector(-Math.cos(radians), 0, -Math.sin(radians));
    }

    /**
     * Volume correction for panned notes.
     *
     * Minecraft attenuates sound with distance (audible radius ~16 blocks),
     * so a note emitted 2 blocks to the side arrives noticeably quieter than
     * a centred one — which would leave every stereo song with a lopsided
     * mix. Scale the volume back up by the attenuation the offset costs.
     */
    private float panVolumeCompensation(org.bukkit.util.Vector right, float pan) {
        if (right == null || pan == 0f) return 1f;
        double distance = Math.abs(pan) * panDistance;
        double remaining = 1.0 - distance / 16.0;
        if (remaining <= 0.05) return 2f;          // shouldn't happen; stay sane
        return (float) Math.min(2.0, 1.0 / remaining);
    }

    /** Starts the shared clock on first use. */
    private synchronized void ensureClock() {
        if (!highPrecision || clock != null) return;
        clock = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "NoteblockWorldMC-clock");
            thread.setDaemon(true);
            return thread;
        });
        clock.scheduleAtFixedRate(this::clockStep, 0, precisionMs,
                java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void clockStep() {
        try {
            for (Playback session : sessions.values()) {
                session.step();
            }
            Playback broadcast = broadcastSession;
            if (broadcast != null) broadcast.step();
        } catch (Throwable t) {
            // A clock thread that dies takes all music with it
            plugin.getLogger().warning("Playback clock error: " + t);
        }
    }

    /** Stops the clock; call on plugin disable. */
    public synchronized void shutdownClock() {
        if (clock != null) {
            clock.shutdownNow();
            clock = null;
        }
    }

    /**
     * Stops the clock when nothing is playing. Without this it keeps waking
     * every precision-ms (200x/second at the default) to iterate an empty
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
     *
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
     *
     * An attached sound is positioned at the entity's feet, while the game's
     * listener sits at the camera — about 1.62 blocks higher. Minecraft's
     * audible radius is max(volume, 1) x 16 blocks and attenuation is linear,
     * so that fixed gap costs a constant fraction of the gain:
     *
     *     gain = volume x (1 - distance / 16)        (for volume <= 1)
     *
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
        loadSong(publicId, player, song -> start(player, song, publicId));
    }

    /** Parses (off-thread, cached) and broadcasts a downloaded song to everyone. */
    public void playDownloadedForAll(Player starter, String publicId) {
        loadSong(publicId, starter, song -> startBroadcast(starter, song, publicId));
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
        UUID id = player.getUniqueId();
        Playback current = sessions.get(id);

        // Switching while something is playing: ramp the outgoing song down
        // and queue this one to begin when that ramp completes. If a fade is
        // already running (the player is switching again mid-switch) just
        // replace what comes next rather than starting a second ramp.
        if (current != null && (current.isFading() || current.beginFade())) {
            cancelPending(id);
            queuedStarts.put(id, () -> startWhenPackSettles(player, song, publicId));
            return;
        }

        stop(id);
        startWhenPackSettles(player, song, publicId);
    }

    /** True when a song is playing and already ramping down. */
    public boolean isFadingOut(UUID playerId) {
        Playback playback = sessions.get(playerId);
        return playback != null && playback.isFading();
    }

    private void startWhenPackSettles(Player player, NbsSong song, String publicId) {
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
                            if (!player.isOnline()) {
                                cancelPending(player.getUniqueId());
                                return;
                            }
                            waitedTicks += 10;
                            if (pack.isSettled(player.getUniqueId())
                                    || waitedTicks >= PACK_WAIT_TIMEOUT_TICKS) {
                                cancelPending(player.getUniqueId());
                                startNow(player, song, publicId);
                            }
                        }
                    }, 10L, 10L);
            pendingStarts.put(player.getUniqueId(), task);
            return;
        }
        startNow(player, song, publicId);
    }

    private void cancelPending(UUID playerId) {
        org.bukkit.scheduler.BukkitTask task = pendingStarts.remove(playerId);
        if (task != null) task.cancel();
    }

    private void startNow(Player player, NbsSong song, String publicId) {
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

        player.sendMessage(nowPlayingMessage(song, null));
        sendCustomNoteWarning(player, song);
    }

    private Component nowPlayingMessage(NbsSong song, String starterName) {
        var messages = plugin.getMessages();
        String title = song.getName().isBlank()
                ? PlainTextComponentSerializer.plainText().serialize(messages.get("playback.untitled"))
                : song.getName();
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
            player.sendMessage(plugin.getMessages().get("playback.custom-notes-skipped",
                    Placeholder.unparsed("count", String.valueOf(song.getSkippedCustomNotes()))));
        }
        if (song.getSkippedDenseNotes() > 0) {
            player.sendMessage(plugin.getMessages().get("playback.dense-notes-skipped",
                    Placeholder.unparsed("count", String.valueOf(song.getSkippedDenseNotes()))));
        }
    }

    /** Starts a broadcast of an unidentified song (no custom sounds). */
    public void startBroadcast(Player starter, NbsSong song) {
        startBroadcast(starter, song, null);
    }

    /** Starts a server-wide broadcast, replacing any current broadcast. */
    public void startBroadcast(Player starter, NbsSong song, String publicId) {
        stopBroadcast();

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
                            if (allOnlineResolved(pack)
                                    || waitedTicks >= PACK_WAIT_TIMEOUT_TICKS) {
                                if (pendingBroadcast != null) {
                                    pendingBroadcast.cancel();
                                    pendingBroadcast = null;
                                }
                                startBroadcastNow(starter, song, publicId);
                            }
                        }
                    }, 10L, 10L);
            return;
        }
        startBroadcastNow(starter, song, publicId);
    }

    private boolean allOnlineResolved(ResourcePackService pack) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!pack.isSettled(online.getUniqueId())) return false;
        }
        return true;
    }

    private void startBroadcastNow(Player starter, NbsSong song, String publicId) {
        ensureClock();
        broadcastSession = new Playback(null, song, publicId);
        broadcastSession.start();

        // Player#getName works even if the starter logged off mid-wait
        plugin.getServer().sendMessage(nowPlayingMessage(song, starter.getName()));
        if (starter.isOnline()) {
            sendCustomNoteWarning(starter, song);
        }
    }

    /** Stops a player's personal session (and any pending pack wait). */
    /**
     * Stops a player's song at once, with no ramp.
     *
     * This is the form shutdown and quit must use: a fading session stays
     * registered and keeps stepping, so fading here would leave onDisable
     * killing the clock before anything was cancelled -- notes would hang on
     * every client and the sessions would leak. Use stopFading() for stops a
     * player actually asked for.
     */
    public boolean stop(UUID playerId) {
        cancelPending(playerId);
        // Drop anything queued behind a fade: stopping must mean stop, not
        // "stop and then start the song I was switching to".
        queuedStarts.remove(playerId);
        Playback playback = sessions.remove(playerId);
        if (playback == null) return false;
        playback.cancelQuietly();
        maybeStopClock();
        return true;
    }

    /**
     * Stops a player's song, ramping down first when fade-out-ms allows.
     *
     * The session stays registered while it fades so it keeps stepping; it
     * removes itself when the ramp completes. A second call finds the fade
     * already running and cuts immediately, so repeating the command always
     * feels responsive.
     */
    public boolean stopFading(UUID playerId) {
        Playback playback = sessions.get(playerId);
        if (playback != null && playback.beginFade()) {
            cancelPending(playerId);
            return true;
        }
        return stop(playerId);
    }

    /** Stops the server-wide broadcast (and any pending pack wait). Main thread only. */
    /** Stops the broadcast, ramping down first when fade-out-ms allows. */
    /**
     * Fades out every personal (non-broadcast) session. Returns true if at
     * least one was playing. Used by "/nbw stop @a" together with the
     * broadcast stop, so one command clears everything currently audible.
     */
    public boolean stopAllPersonalFading() {
        boolean any = false;
        for (UUID id : sessions.keySet().toArray(UUID[]::new)) {
            if (stopFading(id)) any = true;
        }
        return any;
    }

    public boolean stopBroadcastFading() {
        Playback session = broadcastSession;
        if (session != null && session.beginFade()) {
            if (pendingBroadcast != null) {
                pendingBroadcast.cancel();
                pendingBroadcast = null;
            }
            return true;
        }
        return stopBroadcast();
    }

    public boolean stopBroadcast() {
        if (pendingBroadcast != null) {
            pendingBroadcast.cancel();
            pendingBroadcast = null;
        }
        if (broadcastSession == null) return false;
        broadcastSession.cancelQuietly();
        broadcastSession = null;
        maybeStopClock();
        return true;
    }

    /** Drops a parsed song from the cache (call when a song is removed). */
    public void invalidateCache(String publicId) {
        songCache.remove(publicId);
        customRingCache.remove(publicId);
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

    /**
     * Reads how long each sample this song uses actually rings, from the Ogg
     * files themselves (granule position = exact sample count). Blocking file
     * I/O — only called from the async load path.
     */
    private void measureRingTimes(String publicId, NbsSong song) {
        try {
            if (vanillaRingCache == null || !hasMeasured(vanillaRingCache)) {
                double[] vanilla = new double[INSTRUMENT_SOUNDS.length];
                for (int i = 0; i < vanilla.length; i++) {
                    vanilla[i] = plugin.getResourcePackService().sampleSeconds(i);
                }
                vanillaRingCache = vanilla;
            }
            if (!song.getCustomSoundFiles().isEmpty()
                    && !customRingCache.containsKey(publicId)) {
                Map<Integer, Double> measured = PolyphonyAnalyzer
                        .customSampleLengths(plugin, song, publicId);
                double[] custom = new double[song.getCustomSoundFiles().size()];
                java.util.Arrays.fill(custom, -1);
                for (Map.Entry<Integer, Double> entry : measured.entrySet()) {
                    if (entry.getKey() < custom.length) {
                        custom[entry.getKey()] = entry.getValue();
                    }
                }
                customRingCache.put(publicId, custom);
            }
        } catch (Exception e) {
            // Allocation falls back to a 1-second estimate; never block playback
            plugin.getLogger().warning("Could not measure sample lengths for "
                    + publicId + ": " + e);
        }
    }

    /** Stops everything; call from onDisable. */
    public void stopAll() {
        for (UUID id : pendingStarts.keySet().toArray(UUID[]::new)) {
            cancelPending(id);
        }
        for (UUID id : sessions.keySet().toArray(UUID[]::new)) {
            stop(id);
        }
        stopBroadcast();
        shutdownClock();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stop(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Parses a song for inspection (/nbw analyze) without playing it,
     * reusing the same LRU cache playback uses so repeat calls are free.
     */
    public void inspect(String publicId, Player feedback, Consumer<NbsSong> onLoaded) {
        loadSong(publicId, feedback, onLoaded);
    }

    /**
     * The song this player is currently hearing, or null. A live broadcast
     * overrides personal playback (mirroring the emission rule), so it is
     * reported first; the player's own session is what they hear only when no
     * broadcast is running.
     */
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
     * live broadcast are still included and flagged, so an admin sees the full
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
        String name = pb.song.getName();
        return name.isBlank() ? pb.publicId : name;
    }

    public String currentSongIdFor(UUID playerId) {
        Playback broadcast = broadcastSession;
        if (broadcast != null) return broadcast.publicId;
        Playback own = sessions.get(playerId);
        return own != null ? own.publicId : null;
    }

    private void loadSong(String publicId, Player feedback, Consumer<NbsSong> onLoaded) {
        NbsSong cached = songCache.get(publicId);
        if (cached != null) {
            onLoaded.accept(cached);
            return;
        }

        Path file = plugin.getDownloadManager().getNbsFile(publicId);
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        NbsSong parsed = NbsParser.parse(file);
                        // Measure sample ring times here, while already off
                        // the main thread: voice allocation needs them, and
                        // reading them lazily on the clock thread would
                        // stutter the first notes of the song.
                        measureRingTimes(publicId, parsed);
                        return parsed;
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .whenComplete((song, throwable) ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (throwable != null) {
                                if (feedback.isOnline()) {
                                    // A song can be removed while still cached and
                                    // playing; analysing or replaying it then reads
                                    // a file that is gone. The failure arrives wrapped
                                    // twice (CompletionException -> UncheckedIOException
                                    // -> NoSuchFileException), so the ROOT cause is what
                                    // must be inspected -- checking one level down finds
                                    // only the UncheckedIOException.
                                    if (Throwables.isMissingFile(throwable)) {
                                        feedback.sendMessage(
                                                plugin.getMessages().get("playback.song-deleted"));
                                    } else {
                                        feedback.sendMessage(plugin.getMessages().get("playback.read-error",
                                                Placeholder.unparsed("reason", Throwables.describe(throwable))));
                                    }
                                }
                                return;
                            }
                            songCache.put(publicId, song);
                            if (feedback.isOnline()) {
                                onLoaded.accept(song);
                            }
                        }));
    }


    // ------------------------------------------------------------------

    /**
     * A note scheduled slightly off its exact tick.
     *
     * The limiter gain is captured HERE, at schedule time, from the size of
     * the whole chord. Humanisation scatters a chord across several clock
     * steps, so by emission time each step sees only a note or two — the
     * limiter would never see the chord it is meant to be taming.
     */
    private record Scheduled(long dueNanos, PlayableNote note, float chordGain) {}

    /**
     * One listener's state, captured on the MAIN thread once per game tick.
     * The clock thread reads only this — never the live world — so nothing
     * touches Bukkit off-thread.
     */
    private record Listener(Player player, org.bukkit.Location ear,
                            org.bukkit.util.Vector right, float volume, boolean extended,
                            double eyeHeight) {}

    private class Playback {

        /** Session owner for personal playback; null for a broadcast. */
        private final Player owner;
        private final NbsSong song;
        private final String publicId;
        /** [customIndex][octave+3] -> full sound key, null when unavailable. */
        private final String[][] customSoundKeys;
        /** The same, minus the "nbw:" prefix, for allocation-free hasEvent. */
        private final String[][] customSoundEvents;

        /** Refreshed on the main thread each tick; read by the clock thread. */
        private volatile List<Listener> listeners = List.of();
        private volatile boolean started = false;
        private volatile boolean cancelled = false;
        private volatile boolean finishing = false;
        /** Read by the action bar (main thread), written by the stepping thread. */
        private volatile double elapsedSeconds = 0;

        /** Timing state, only ever touched by the stepping thread. */
        private double currentTempo;
        private double position = 0;
        private int lastPlayedTick = -1;
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

        private org.bukkit.Location panLocation(org.bukkit.Location ear,
                                                org.bukkit.util.Vector right, float pan) {
            if (right == null || pan == 0f) return ear;
            double offset = pan * panDistance;
            panScratch.setWorld(ear.getWorld());
            panScratch.setX(ear.getX() + right.getX() * offset);
            panScratch.setY(ear.getY() + right.getY() * offset);
            panScratch.setZ(ear.getZ() + right.getZ() * offset);
            return panScratch;
        }

        /**
         * Expiry stamps (nanos) of voices believed still ringing on the
         * client. A min-heap: the head is the next to fall silent. Stepping
         * thread only. NOT cleared on loop restart — the client keeps ringing
         * those samples across the loop seam, so the channels stay occupied.
         */
        private final java.util.PriorityQueue<Long> ringing = new java.util.PriorityQueue<>();
        /** Ring times captured at construction; may be null (fallback 1s). */
        private final double[] vanillaRing;
        private final double[] customRing;

        /**
         * nanoTime the fade began, or 0 when not fading. Volatile: set on the
         * main thread by stop(), read by the stepping thread.
         */
        private volatile long fadeStartNanos;

        /** Notes awaiting their humanised moment. Stepping thread only. */
        private final java.util.PriorityQueue<Scheduled> pending = new java.util.PriorityQueue<>(
                java.util.Comparator.comparingLong(Scheduled::dueNanos));
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
            this.vanillaRing = vanillaRingCache;
            this.customRing = publicId != null ? customRingCache.get(publicId) : null;
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
                sessions.remove(owner.getUniqueId(), this);
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
            if (!highPrecision) step();
        }

        /** Main thread: capture everything the clock thread will need. */
        private void refreshListeners() {
            ResourcePackService pack = plugin.getResourcePackService();
            boolean packActive = pack.isActive();
            List<Listener> snapshot = new ArrayList<>();

            for (Player player : audience(pack, packActive)) {
                float volume = plugin.getVolumeManager().getFactor(player.getUniqueId());
                if (volume <= 0f) continue;              // muted: emit nothing
                org.bukkit.Location ear = player.getEyeLocation();
                org.bukkit.util.Vector right = stereoPanning ? rightVector(ear.getYaw()) : null;
                boolean extended = packActive && pack.isPackLoaded(player.getUniqueId());
                snapshot.add(new Listener(player, ear, right, volume, extended,
                        player.getEyeHeight()));
            }
            this.listeners = List.copyOf(snapshot);
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
            return !packActive || pack.isSettled(player.getUniqueId());
        }

        // --------------------------------------------------------------
        // Stepping: the clock thread (or the main tick in low-precision mode)
        // --------------------------------------------------------------

        /** Re-anchors the clock so a pause (e.g. a settings reload) isn't
         *  mistaken for elapsed song time. */
        void resync() {
            lastStepNanos = System.nanoTime();
        }

        /** Advances by real elapsed time and emits any notes now due. */
        void step() {
            if (!started || cancelled) return;

            long now = System.nanoTime();

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

            elapsedSeconds += delta;
            position += currentTempo * delta;
            int upTo = (int) Math.floor(position);

            List<Listener> snapshot = listeners;
            ResourcePackService pack = plugin.getResourcePackService();

            if (upTo > lastPlayedTick) {
                for (int tick = lastPlayedTick + 1; tick <= upTo; tick++) {
                    List<PlayableNote> notes = song.getNotesAt(tick);
                    if (humanize) {
                        scheduleHumanised(notes, tick, now);
                    } else {
                        emit(notes, snapshot, pack, now);
                    }
                    lastPlayedTick = tick;

                    Double tempoChange = song.getTempoChangeAt(tick);
                    if (tempoChange != null) {
                        // Re-anchor at the change so remaining fractional
                        // progress at the old rate can't overshoot notes that
                        // should now take longer (or arrive sooner).
                        //
                        // The overshoot is CONVERTED rather than discarded.
                        // position had already moved a little past this tick,
                        // and that fraction is real elapsed time -- dropping it
                        // stalled the song by about half a clock interval at
                        // every change. Individually inaudible, but a song with
                        // dense tempo automation has thousands of changes, and
                        // it accumulated into seconds of drift against the
                        // duration shown in the action bar.
                        double overshoot = position - tick;
                        double previousTempo = currentTempo;
                        currentTempo = NbsSong.clampTempo(tempoChange);
                        // Same wall-clock time, expressed in the new tempo's ticks.
                        position = tick + overshoot * (currentTempo / previousTempo);
                        break;
                    }
                }
            }

            // Drain whenever anything is queued -- not only when humanize is
            // currently on. Toggling it off via /nbw reload mid-song can leave
            // notes already scheduled; without this they'd never play AND the
            // finish gate below would wait forever for a queue nothing drains.
            if (!pending.isEmpty()) drainPending(now, snapshot, pack);

            // Don't end the song while humanised notes are still due
            if (lastPlayedTick >= song.getLengthTicks() && pending.isEmpty()) {
                if (shouldLoop()) {
                    restartLoop();
                } else {
                    requestFinish();
                }
            }
        }

        /**
         * Scatters a tick's notes in time.
         *
         * The tick's TRUE moment is reconstructed from how far past it the
         * clock has drifted, so jitter is measured from where the note should
         * have landed rather than from the step boundary — otherwise the clock
         * grid would just be traded for a coarser one.
         *
         * Offsets are drawn from [0, 2*spread] rather than [-spread, +spread]:
         * a note can't be emitted in the past, and the constant few-ms latency
         * this adds is imperceptible.
         */
        private void scheduleHumanised(List<PlayableNote> notes, int tick, long now) {
            // Nothing to schedule if no one can hear it: without this the
            // queue would fill and drain into an empty audience every tick.
            if (notes.isEmpty() || listeners.isEmpty()) return;

            // Voice allocation happens at schedule time so the whole chord is
            // still visible (emission is scattered note by note). Registering
            // occupancy from "now" rather than each jittered due-time is fine:
            // ring times dwarf the few-ms jitter.
            notes = admitVoices(notes, now);
            if (notes.isEmpty()) return;

            double overshootTicks = Math.max(0, position - tick);
            long tickNanos = now - (long) (overshootTicks
                    / Math.max(0.001, currentTempo) * 1_000_000_000.0);

            float chordGain = limiterGain(notes.size());
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
                timingOffsetMs = Math.max(-bound, Math.min(bound, timingOffsetMs));
            } else {
                timingOffsetMs = 0;
            }

            for (PlayableNote note : notes) {
                // Percussion carries the beat: pull it back toward the grid so
                // drums stay tight while melodic voices breathe.
                double noteOffsetMs = timingOffsetMs
                        * (isPercussion(note.instrument()) ? humanizePercussion : 1.0);
                // A note can't play in the past; shift the whole range positive.
                double delayMs = noteOffsetMs + spread;
                long offset = (long) (Math.max(0, delayMs) * 1_000_000.0);
                pending.add(new Scheduled(tickNanos + offset, note, chordGain));
            }
        }

        private void drainPending(long now, List<Listener> snapshot, ResourcePackService pack) {
            while (!pending.isEmpty() && pending.peek().dueNanos() <= now) {
                Scheduled due = pending.poll();
                emitNote(due.note(), due.chordGain(), snapshot, pack);
            }
        }

        /** Emits one tick's notes. Sound packets only — no world access. */
        private void emit(List<PlayableNote> notes, List<Listener> snapshot,
                          ResourcePackService pack, long now) {
            if (notes.isEmpty() || snapshot.isEmpty()) return;

            // Use the step's timestamp, not a fresh one: voice expiry and note
            // scheduling must share a single time base within a step.
            notes = admitVoices(notes, now);
            if (notes.isEmpty()) return;

            // One gain for the whole chord (the ADMITTED chord — that is what
            // actually reaches the client's mixer), so its balance is kept
            float chordGain = limiterGain(notes.size());
            for (PlayableNote note : notes) {
                emitNote(note, chordGain, snapshot, pack);
            }
        }

        /** Emits a single note to every listener. */
        private void emitNote(PlayableNote note, float chordGain,
                              List<Listener> snapshot, ResourcePackService pack) {
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

            // Which sound to play depends only on the NOTE and the pack —
            // not on the listener — so resolve it once here instead of
            // repeating the map lookups for every listener of a broadcast.
            final int octaveSlot = note.octave() + 3;
            final String packKey;
            if (note.isCustom()) {
                int index = note.customIndex();
                String key = customSoundKeys[index][octaveSlot];
                // Missing variant (rate out of range) -> the base sound,
                // i.e. the folded note.
                if (key == null || !pack.hasEvent(customSoundEvents[index][octaveSlot])) {
                    key = customSoundEvents[index][3] != null
                            && pack.hasEvent(customSoundEvents[index][3])
                            ? customSoundKeys[index][3] : null;
                }
                // Custom sounds exist only in the pack; with no usable event
                // there is nothing any listener could hear.
                if (key == null) return;
                packKey = key;
            } else if (note.octave() != 0
                    && EXTENDED_SOUND_KEYS[note.instrument()][octaveSlot] != null
                    // The pack skips variants whose sample rate would fall
                    // outside the decodable range; fold those.
                    && pack.hasEvent(EXTENDED_SOUND_EVENTS[note.instrument()][octaveSlot])) {
                packKey = EXTENDED_SOUND_KEYS[note.instrument()][octaveSlot];
            } else {
                packKey = null;              // vanilla sound, or folded note
            }
            final boolean custom = note.isCustom();

            for (Listener listener : snapshot) {
                // A listener without the pack hears nothing for a custom
                // instrument (the pre-song warning covered this case).
                if (custom && !listener.extended()) continue;

                org.bukkit.Location at = panLocation(listener.ear(), listener.right(), note.pan());
                float volume = Math.min(1f,
                        note.volume() * listener.volume() * chordGain * human * fade)
                        * panVolumeCompensation(listener.right(), note.pan());
                if (volume < AUDIBLE_FLOOR) continue;

                String key = listener.extended() ? packKey : null;
                if (followPlayer) {
                    float attached = attachedVolume(volume, listener.eyeHeight());
                    if (key != null) {
                        listener.player().playSound(listener.player(), key,
                                SoundCategory.RECORDS, attached, note.pitch());
                    } else {
                        listener.player().playSound(listener.player(),
                                INSTRUMENT_SOUNDS[note.instrument()],
                                SoundCategory.RECORDS, attached, note.pitch());
                    }
                } else if (key != null) {
                    listener.player().playSound(at, key, SoundCategory.RECORDS,
                            volume, note.pitch());
                } else {
                    listener.player().playSound(at, INSTRUMENT_SOUNDS[note.instrument()],
                            SoundCategory.RECORDS, volume, note.pitch());
                }
            }
        }

        /**
         * How long this note will hold a client channel. Rate-shifted octave
         * variants play 2^octave faster, and the runtime pitch (0.5-2.0)
         * scales playback speed again — both shorten or lengthen the hold.
         */
        private double ringSeconds(PlayableNote note) {
            // isCustom() already guarantees customIndex >= 0; only the upper
            // bound needs checking (customRing is sized from the song's own
            // sound list, which a stale cache entry could disagree with).
            double base = note.isCustom()
                    ? (customRing != null && note.customIndex() < customRing.length
                            ? customRing[note.customIndex()] : -1)
                    : (vanillaRing != null ? vanillaRing[note.instrument()] : -1);
            if (base <= 0) base = 1.0;   // unmeasured: a typical note block ring
            return base / Math.pow(2, note.octave()) / Math.max(0.25, note.pitch());
        }

        /**
         * Admits as much of a chord as the voice ceiling allows.
         *
         * Occupancy is real, not per-tick: expired voices are released by
         * their measured ring time, so a sparse song never comes near the
         * ceiling and is never touched. When a chord genuinely would overflow
         * it, the quietest notes are dropped — a deliberate choice, instead
         * of the client evicting arbitrary sounds (possibly the melody, or
         * the game's own audio) when its global pool fills.
         */
        private List<PlayableNote> admitVoices(List<PlayableNote> notes, long now) {
            if (!voiceAllocation || notes.isEmpty()) return notes;

            while (!ringing.isEmpty() && ringing.peek() <= now) {
                ringing.poll();
            }
            int available = voiceCeiling - ringing.size();
            List<PlayableNote> admitted;
            if (notes.size() <= available) {
                admitted = notes;
            } else if (available <= 0) {
                return List.of();
            } else {
                List<PlayableNote> byVolume = new ArrayList<>(notes);
                byVolume.sort((a, b) -> Float.compare(b.volume(), a.volume()));
                admitted = byVolume.subList(0, available);
            }
            for (PlayableNote note : admitted) {
                ringing.add(now + (long) (ringSeconds(note) * 1_000_000_000.0));
            }
            return admitted;
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
            if (song.getLengthTicks() <= 0) return false;
            int max = song.getMaxLoopCount();
            return max == 0 || loopsDone + 1 < max;   // 0 = loop forever
        }

        /** Jumps back to the song's loop start, restoring the tempo there. */
        private void restartLoop() {
            loopsDone++;
            pending.clear();
            timingOffsetMs = 0;
            int start = song.getLoopStartTick();
            position = start;
            lastPlayedTick = start - 1;
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
            Bukkit.getScheduler().runTask(plugin, this::finish);
        }

        private void finish() {
            // Reached only by a natural ending or a completed fade — in both
            // cases the remaining notes should decay, not be chopped.
            if (isBroadcast()) {
                if (broadcastSession == this) broadcastSession = null;
                cancelLettingRing();
                plugin.getServer().sendMessage(plugin.getMessages().get("playback.finished"));
            } else {
                // Identity-checked, mirroring the broadcast branch above: a
                // stale session finishing must not remove the song the player
                // has since started.
                sessions.remove(owner.getUniqueId(), this);
                cancelLettingRing();
                Runnable queued = queuedStarts.remove(owner.getUniqueId());
                if (queued != null) {
                    // A switch, not an ending: "Finished" would be noise right
                    // before "Now playing". The outgoing tail is left ringing
                    // and decays under the new song's opening.
                    if (owner.isOnline()) queued.run();
                } else if (owner.isOnline()) {
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
                            Placeholder.unparsed("chars", "\u25ac".repeat(filled)))
                    .append(messages.get("playback.bar-empty",
                            Placeholder.unparsed("chars", "\u25ac".repeat(barLength - filled))));

            String title = song.getName().isBlank()
                    ? PlainTextComponentSerializer.plainText()
                            .serialize(messages.get("playback.untitled"))
                    : song.getName();

            Component actionBar = messages.get("playback.actionbar",
                    Placeholder.unparsed("title", title),
                    Placeholder.unparsed("elapsed", formatTime(elapsed)),
                    Placeholder.unparsed("total", formatTime(total)),
                    Placeholder.component("bar", bar));

            for (Listener listener : listeners) {
                listener.player().sendActionBar(actionBar);
            }
        }

        /** Main thread. */
        /** Ends the session and CUTS anything still ringing. */
        void cancelQuietly() {
            cancel(true);
        }

        /**
         * Ends the session but leaves ringing notes to decay on their own.
         *
         * This is what makes a fade audible. Minecraft cannot alter a sound
         * that is already playing, so the ramp only quietens notes struck
         * DURING it -- everything struck in the second or so before still
         * rings at full volume. Calling stopSound at the end therefore
         * guillotined exactly the loudest part, which is why the fade was
         * barely perceptible: it was a delayed hard cut.
         *
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
            // We play in RECORDS only, so stopping that category stops exactly
            // our notes -- when stopping is what's wanted.
            for (Listener listener : listeners) {
                if (cutRingingSound) {
                    listener.player().stopSound(SoundCategory.RECORDS);
                }
                if (showActionBar) {
                    listener.player().sendActionBar(Component.empty());
                }
            }
        }
    }
}
