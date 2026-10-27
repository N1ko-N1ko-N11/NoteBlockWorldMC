import io.github.noteblockworldmc.NoteblockWorldMCPlugin;
import io.github.noteblockworldmc.nbs.NbsSong;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED;

/** Regression checks for pack replacement/retry, broadcast tails and incomplete song folders. */
public final class EdgeFixChecks extends RegressionChecks {
    public static void main(String[] args) throws Exception {
        Path data = Path.of(args[0]); Files.createDirectories(data);
        System.setProperty("nbw.test.data", data.toString());
        Player alice = new Player("Alice"); Bukkit.getServer().players.add(alice);
        var plugin = new NoteblockWorldMCPlugin(); plugin.onEnable(); drain(plugin);
        try {
            var config = plugin.getConfig(); var pack = plugin.getResourcePackService();
            var downloads = plugin.getDownloadManager(); var playback = plugin.getPlaybackManager();
            int port = freePort(); config.set("resource-pack.enabled", true);
            config.set("resource-pack.bind-address", "127.0.0.1"); config.set("resource-pack.port", port);
            config.set("resource-pack.public-url", "http://127.0.0.1:" + port + "/pack.zip");
            pack.loadHotSettings(); drain(plugin); pack.send(alice); status(pack, alice, SUCCESSFULLY_LOADED);
            String initialHash = pack.getPackSha1();
            UUID initialRequest = alice.packs.get(alice.packs.size() - 1).info.id();
            String id = downloads.importBytes(nbs("Transient custom song", List.of("kick.ogg"),
                    new int[][]{{0, 0, 20, 45, 100}}), "custom.nbs");
            Path sounds = downloads.getSongDirectory(id).resolve("sounds"); Files.createDirectories(sounds);
            Files.write(sounds.resolve("kick.ogg"), ogg()); pack.reloadContent(); drain(plugin);
            String interimHash = pack.getPackSha1();
            UUID interimRequest = alice.packs.get(alice.packs.size() - 1).info.id();
            check(!initialHash.equals(interimHash), "Added custom sound creates a different pack");
            check(downloads.remove(id), "Remove the transient song"); pack.reloadContent(); drain(plugin);
            check(initialHash.equals(pack.getPackSha1()), "Pack returns to the exact installed generation");
            check(alice.removedPacks.contains(interimRequest), "Obsolete in-flight pack is withdrawn");
            // The initial pack left the client when the interim one was pushed,
            // so returning to that generation is a fresh push.
            UUID returnRequest = alice.packs.get(alice.packs.size() - 1).info.id();
            check(alice.removedPacks.contains(initialRequest) && !returnRequest.equals(interimRequest)
                    && initialHash.equals(alice.packs.get(alice.packs.size() - 1).info.hash()),
                    "Returning to an earlier generation re-sends it");
            pack.onPackStatus(new PlayerResourcePackStatusEvent(alice, interimRequest, SUCCESSFULLY_LOADED));
            check(!pack.isPackLoaded(alice.getUniqueId()), "Late success for the withdrawn request is ignored");
            pack.onPackStatus(new PlayerResourcePackStatusEvent(alice, returnRequest, SUCCESSFULLY_LOADED));
            Object installed = ((Map<?, ?>) field(pack, "installed")).get(alice.getUniqueId());
            check(initialHash.equals(call(installed, "sha1")), "The desired generation becomes the installed pack");

            Player retry = new Player("Retry"); Bukkit.getServer().players.add(retry); pack.send(retry);
            int before = retry.packs.size();
            config.set("resource-pack.public-url", "http://localhost:" + port + "/pack.zip"); pack.loadHotSettings();
            check(retry.packs.size() == before + 1, "Corrected public URL replaces a same-hash pending request");
            UUID corrected = retry.packs.get(retry.packs.size() - 1).info.id();
            pack.send(retry);
            check(retry.packs.size() == before + 1, "Identical pending URL is deduplicated before timeout");
            ((Map<UUID, Long>) field(pack, "packSentAtMillis")).put(retry.getUniqueId(), System.currentTimeMillis() - 31_000);
            check(pack.isSettled(retry.getUniqueId()), "Playback timeout has elapsed"); pack.send(retry);
            check(retry.packs.size() == before + 2 && retry.removedPacks.contains(corrected), "Timed-out request is withdrawn and retried");
            pack.onPackStatus(new PlayerResourcePackStatusEvent(retry, corrected, SUCCESSFULLY_LOADED));
            check(!pack.isPackLoaded(retry.getUniqueId()), "Late acknowledgement for timed-out attempt is ignored");

            config.set("resource-pack.enabled", false); pack.loadHotSettings();
            NbsSong harp = parse(nbs("Ringing broadcast", List.of(), new int[][]{{0, 0, 0, 45, 100}}));
            playback.startBroadcast(alice, harp); Object broadcast = field(playback, "broadcastSession");
            call(broadcast, "emitNote", 0, 1f, field(broadcast, "listeners"));
            call(broadcast, "requestFinish"); drain(plugin); int cuts = alice.cuts;
            check(field(playback, "broadcastSession") == null, "Broadcast session finished");
            boolean stopped = playback.stopBroadcastFading(); stopped |= playback.stopAllPersonalFading();
            check(stopped && alice.cuts > cuts, "Stop everyone cuts remaining broadcast tails");
            check(((Queue<?>) field(((Map<?, ?>) field(playback, "listenerVoices")).get(alice.getUniqueId()), "ends")).isEmpty(),
                    "Stopped broadcast tail is removed from voice accounting");
            playback.stopAll();

            String orphan = downloads.importBytes(nbs("Missing song", List.of(), new int[][]{{0, 0, 0, 45, 100}}), "missing.nbs");
            Files.delete(downloads.getNbsFile(orphan));
            check(downloads.reconcileLibrary().successful() && !downloads.getDownloadedIds().contains(orphan),
                    "Reload reconciliation drops the missing song from the index");
            Path original = downloads.getSongDirectory(orphan);
            Files.writeString(original.resolve("keep.txt"), "leftover audio metadata");
            call(downloads, "reserve", orphan);
            check(downloads.isInFlight(orphan) && !Files.exists(original), "Missing song ID is admitted for replacement");
            try (var backups = Files.list(original.getParent().resolve(".orphaned"))) {
                Path backup = backups.filter(p -> p.getFileName().toString().startsWith(orphan + "-")).findFirst().orElseThrow();
                check(Files.readString(backup.resolve("keep.txt")).equals("leftover audio metadata"), "Orphan contents are preserved for recovery");
            }
            call(downloads, "release", orphan);
            String removable = downloads.importBytes(nbs("Remove orphan", List.of(), new int[][]{{0, 0, 0, 45, 100}}), "remove.nbs");
            Files.delete(downloads.getNbsFile(removable)); downloads.reconcileLibrary();
            new io.github.noteblockworldmc.command.SongsCommand(plugin).handle(alice, "nbw", new String[]{"remove", removable});
            check(!Files.exists(downloads.getSongDirectory(removable)), "Remove command accepts an unindexed orphan by exact ID");
            drain(plugin);
            String intact = downloads.importBytes(nbs("Keep intact", List.of(), new int[][]{{0, 0, 0, 45, 100}}), "intact.nbs");
            try { call(downloads, "reserve", intact); throw new AssertionError("Existing song admitted"); }
            catch (RuntimeException expected) { check(Files.exists(downloads.getNbsFile(intact)), "Valid stored song cannot be quarantined"); }
        } finally { plugin.onDisable(); }
        System.out.println("PASS: " + checks + " pack, broadcast-tail and orphan-recovery regression checks.");
    }
}
