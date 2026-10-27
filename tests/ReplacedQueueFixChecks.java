import io.github.noteblockworldmc.NoteblockWorldMCPlugin;
import io.github.noteblockworldmc.nbs.NbsSong;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.nio.file.*;
import java.util.*;

/** Regression coverage for queued selection invalidation on reload. */
public final class ReplacedQueueFixChecks extends RegressionChecks {
    public static void main(String[] args) throws Exception {
        Path data = Path.of(args[0]); Files.createDirectories(data);
        System.setProperty("nbw.test.data", data.toString());
        Player player = new Player("Listener"); Bukkit.getServer().players.add(player);
        var plugin = new NoteblockWorldMCPlugin(); plugin.onEnable(); drain(plugin);
        try {
            var playback = plugin.getPlaybackManager(); var downloads = plugin.getDownloadManager();
            plugin.getConfig().set("playback.fade-out-ms", 5000); playback.loadHotSettings();
            byte[] original = nbs("Original", List.of(), new int[][]{{0,0,0,45,100}});
            byte[] replacement = nbs("Replacement", List.of(), new int[][]{{0,0,0,57,100}});
            for (boolean broadcast : new boolean[]{false, true}) {
                String id = downloads.importBytes(original, "queued.nbs");
                if (broadcast) playback.startBroadcast(player, parse(original)); else playback.start(player, parse(original));
                Object outgoing = broadcast ? field(playback, "broadcastSession") : FixChecks.personal(playback, player);
                if (broadcast) playback.playDownloadedForAll(player, id); else playback.playDownloaded(player, id);
                drain(plugin);
                check(broadcast ? field(playback, "queuedBroadcastStart") != null
                        : !((Map<?, ?>)field(playback, "queuedStarts")).isEmpty(), "Selection queued behind fade");
                Files.write(downloads.getNbsFile(id), replacement);
                var reloaded = plugin.applySettings(plugin.getConfig(), true); drain(plugin); reloaded.join();
                check(call(field(playback, "songCache"), "get", id) == null, "Explicit reload really cleared parsed cache");
                check(parse(Files.readAllBytes(downloads.getNbsFile(id))).notePitch(0) == 2f, "Replacement file has different notes");
                setField(outgoing, "fadeStartNanos", System.nanoTime() - 6_000_000_000L);
                call(outgoing, "step"); drain(plugin);
                Object started = broadcast ? field(playback, "broadcastSession") : FixChecks.personal(playback, player);
                check(started == null, "Invalidated queued callback cannot start old data");
                check(((Map<?, ?>)field(playback, "pendingSelections")).isEmpty(), "Cancelled selection releases tracked state");
                if (broadcast) playback.playDownloadedForAll(player, id); else playback.playDownloaded(player, id);
                drain(plugin);
                Object fresh = broadcast ? field(playback, "broadcastSession") : FixChecks.personal(playback, player);
                check(fresh != null && ((NbsSong)field(fresh, "song")).notePitch(0) == 2f,
                        "Reselection parses and starts replacement notes");
                playback.stopAll();
            }
            int port = freePort(); var config = plugin.getConfig(); var pack = plugin.getResourcePackService();
            config.set("resource-pack.enabled", true); config.set("resource-pack.port", port);
            config.set("resource-pack.bind-address", "127.0.0.1");
            config.set("resource-pack.public-url", "http://127.0.0.1:" + port + "/pack.zip");
            pack.loadHotSettings(); drain(plugin);
            for (boolean broadcast : new boolean[]{false, true}) {
                String id = downloads.importBytes(original, "waiting.nbs");
                if (broadcast) playback.playDownloadedForAll(player, id); else playback.playDownloaded(player, id);
                drain(plugin);
                check(broadcast ? field(playback, "pendingBroadcast") != null
                        : !((Map<?, ?>)field(playback, "pendingStarts")).isEmpty(), "Selection waits for a pack");
                playback.invalidateCache("unrelated-song"); drain(plugin);
                check(!((Map<?, ?>)field(playback, "pendingSelections")).isEmpty(), "Unrelated invalidation preserves selection");
                Files.write(downloads.getNbsFile(id), replacement);
                var reload = plugin.applySettings(config, true); drain(plugin); reload.join();
                check(field(playback, "pendingBroadcast") == null && ((Map<?, ?>)field(playback, "pendingStarts")).isEmpty()
                        && ((Map<?, ?>)field(playback, "pendingSelections")).isEmpty(),
                        "Reload cancels pack waits and releases their selections");
                if (broadcast) playback.playDownloadedForAll(player, id); else playback.playDownloaded(player, id);
                drain(plugin);
                playback.invalidateCache(id);
                // Select again before the invalidation's scheduled cleanup executes.
                if (broadcast) playback.playDownloadedForAll(player, id); else playback.playDownloaded(player, id);
                drain(plugin);
                check(!((Map<?, ?>)field(playback, "pendingSelections")).isEmpty(), "Stale cleanup preserves newer selection");
                playback.stopAll();
            }
        } finally { plugin.onDisable(); }
        System.out.println("PASS: " + checks + " replacement/reload regression checks.");
    }
}
