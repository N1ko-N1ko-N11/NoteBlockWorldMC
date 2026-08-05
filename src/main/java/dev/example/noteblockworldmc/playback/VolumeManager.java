package dev.example.noteblockworldmc.playback;

import dev.example.noteblockworldmc.NoteblockWorldMCPlugin;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-player music volume (0-100), applied per listener at playback time —
 * so during a broadcast, everyone hears the concert at their own level.
 *
 * 100 is the default and is deliberately NOT stored: only deviations are
 * persisted to volumes.yml, so setting a player back to 100 erases their
 * entry rather than pinning a redundant value.
 */
public class VolumeManager {

    private final NoteblockWorldMCPlugin plugin;
    private final File file;
    /** Only non-100 values live here; absence means 100. */
    private final Map<UUID, Integer> volumes = new ConcurrentHashMap<>();
    /** True while a save is already queued, so bursts collapse into one write. */
    private final AtomicBoolean savePending = new AtomicBoolean();
    /** Coalescing window; long enough to batch a rush, short enough to be safe. */
    private static final long SAVE_DELAY_TICKS = 40L;   // 2 seconds

    public VolumeManager(NoteblockWorldMCPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "volumes.yml");
        load();
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = config.getConfigurationSection("volumes");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            try {
                int value = clamp(section.getInt(key, 100));
                if (value != 100) {
                    volumes.put(UUID.fromString(key), value);
                }
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Ignoring invalid UUID in volumes.yml: " + key);
            }
        }
        plugin.getLogger().info("Loaded " + volumes.size() + " custom volume setting(s).");
    }

    /** This player's volume, 0-100 (default 100). */
    public int getVolume(UUID playerId) {
        return volumes.getOrDefault(playerId, 100);
    }

    /** Multiplier for playback, 0.0-1.0. */
    public float getFactor(UUID playerId) {
        return getVolume(playerId) / 100f;
    }

    /** Sets and persists a player's volume. 100 removes the stored entry. */
    public void setVolume(UUID playerId, int volume) {
        int clamped = clamp(volume);
        if (clamped >= 100) {
            volumes.remove(playerId);
        } else {
            volumes.put(playerId, clamped);
        }
        // Coalesced, not immediate: every call used to rewrite the WHOLE
        // file, so N players each setting a volume once cost N full writes of
        // a file that grows with N. Queue at most one save per window and let
        // it capture whatever the map holds by then. flush() on disable
        // catches anything still pending.
        if (savePending.compareAndSet(false, true)) {
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                // Cleared BEFORE writing, so a change made during the write
                // queues a fresh save rather than being silently dropped.
                savePending.set(false);
                save();
            }, SAVE_DELAY_TICKS);
        }
    }

    /**
     * Saves synchronously. setVolume persists via an async scheduler task,
     * and Bukkit cancels a disabling plugin's pending tasks — so a volume
     * changed moments before shutdown would silently vanish without this
     * being called from onDisable.
     */
    public synchronized void flush() {
        save();
    }

    private synchronized void save() {
        YamlConfiguration config = new YamlConfiguration();
        // Weakly-consistent iteration: a concurrent setVolume may or may not
        // appear in this snapshot. That is fine — it queued its own save.
        for (Map.Entry<UUID, Integer> entry : volumes.entrySet()) {
            config.set("volumes." + entry.getKey(), entry.getValue());
        }
        // Write to a temp file and move it into place: config.save() truncates
        // the real file first, so a crash mid-write would leave every player's
        // volume lost. The move is the only step that touches volumes.yml.
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            config.save(temp);
            try {
                Files.move(temp.toPath(), file.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save volumes.yml: " + e);
            try {
                Files.deleteIfExists(temp.toPath());
            } catch (IOException ignored) {
                // leftover temp is harmless; the next save overwrites it
            }
        }
    }

    private static int clamp(int volume) {
        return Math.max(0, Math.min(100, volume));
    }
}
