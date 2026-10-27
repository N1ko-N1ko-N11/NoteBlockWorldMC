import io.github.noteblockworldmc.NoteblockWorldMCPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.nio.file.*;
import java.util.*;

/** Real parser and playback-state checks with the existing server doubles. */
public final class QueuedTuningFixChecks extends RegressionChecks {
    public static void main(String[] args) throws Exception {
        String[] files = {"trumpet.ogg", "exposed_trumpet.ogg", "weathered_trumpet.ogg", "oxidized_trumpet.ogg"};
        for (int version : new int[]{5, 6}) for (int instrument = 0; instrument < files.length; instrument++) {
            for (int tuning : new int[]{0, 33, 45, 57, 87, 255}) {
                int builtins = version == 5 ? 16 : 20;
                byte[] promoted = nbs("Tuning", List.of(files[instrument]), new int[][]{{0,0,builtins,45,100}});
                byte[] ordinary = nbs("Tuning", List.of("ordinary.ogg"), new int[][]{{0,0,builtins,45,100}});
                for (byte[] bytes : new byte[][]{promoted, ordinary}) {
                    bytes[2] = (byte)version; bytes[3] = (byte)builtins;
                    bytes[bytes.length - 2] = (byte)tuning;
                }
                var actual = parse(promoted); var reference = parse(ordinary);
                check(actual.noteInstrument(0) == instrument + 16 && actual.noteCustomIndex(0) == -1
                        && Math.abs(actual.notePitch(0) - reference.notePitch(0)) < 1e-6
                        && actual.noteOctave(0) == reference.noteOctave(0),
                        "Promoted trumpet preserves tuning, folding and malformed-key fallback: v" + version + "/" + instrument + "/" + tuning);
            }
        }
        Path data = Path.of(args[0]); Files.createDirectories(data);
        System.setProperty("nbw.test.data", data.toString());
        Player player = new Player("Listener"); Bukkit.getServer().players.add(player);
        var plugin = new NoteblockWorldMCPlugin(); plugin.onEnable(); drain(plugin);
        try {
            var downloads = plugin.getDownloadManager(); var playback = plugin.getPlaybackManager();
            plugin.getConfig().set("playback.fade-out-ms", 5000); playback.loadHotSettings();
            byte[] first = nbs("First", List.of(), new int[][]{{0,0,0,45,100}});
            for (boolean broadcast : new boolean[]{false, true}) for (boolean localDelete : new boolean[]{false, true}) {
                String next = downloads.importBytes(first, "next.nbs");
                if (broadcast) playback.startBroadcast(player, parse(first)); else playback.start(player, parse(first));
                Object old = broadcast ? field(playback, "broadcastSession") : FixChecks.personal(playback, player);
                if (broadcast) playback.playDownloadedForAll(player, next); else playback.playDownloaded(player, next);
                drain(plugin);
                check(broadcast ? field(playback, "queuedBroadcastStart") != null
                        : ((Map<?, ?>)field(playback, "queuedStarts")).containsKey(player.getUniqueId()),
                        "Downloaded song queued behind fade");
                if (localDelete) Files.delete(downloads.getNbsFile(next)); else check(downloads.remove(next), "Remove queued song");
                setField(old, "fadeStartNanos", System.nanoTime() - 6_000_000_000L);
                call(old, "step"); drain(plugin);
                check(playback.activeSongs().isEmpty(), "Deleted song cannot start after personal/broadcast fade");
                check(field(playback, "broadcastRequest") == null && ((Map<?, ?>)field(playback, "playRequests")).isEmpty(),
                        "Rejected queued start releases request ownership");
                playback.stopAll();
            }
            int port = freePort(); var config = plugin.getConfig(); var pack = plugin.getResourcePackService();
            config.set("resource-pack.enabled", true); config.set("resource-pack.port", port);
            config.set("resource-pack.bind-address", "127.0.0.1");
            config.set("resource-pack.public-url", "http://127.0.0.1:" + port + "/pack.zip");
            pack.loadHotSettings(); drain(plugin);
            for (boolean broadcast : new boolean[]{false, true}) {
                String next = downloads.importBytes(first, "waiting.nbs");
                if (broadcast) playback.playDownloadedForAll(player, next); else playback.playDownloaded(player, next);
                drain(plugin);
                check(broadcast ? field(playback, "pendingBroadcast") != null
                        : !((Map<?, ?>)field(playback, "pendingStarts")).isEmpty(), "Selection waits for client pack");
                Files.delete(downloads.getNbsFile(next));
                Bukkit.getScheduler().tick(); drain(plugin);
                check(playback.activeSongs().isEmpty() && field(playback, "pendingBroadcast") == null
                        && ((Map<?, ?>)field(playback, "pendingStarts")).isEmpty(),
                        "Pack-waiting selection is cancelled when its song disappears");
            }
        } finally { plugin.onDisable(); }
        System.out.println("PASS: " + checks + " queued-deletion and trumpet-tuning regression checks.");
    }
}
