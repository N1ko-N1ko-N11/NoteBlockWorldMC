import io.github.noteblockworldmc.NoteblockWorldMCPlugin;
import io.github.noteblockworldmc.api.NoteBlockWorldClient.SongQuery;
import io.github.noteblockworldmc.api.model.*;
import io.github.noteblockworldmc.gui.SongBrowserGui;
import io.github.noteblockworldmc.nbs.NbsSong;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED;

/** Regression coverage for the follow-up audit; no external network or game server. */
public final class FollowupFixChecks extends RegressionChecks {
    public static void main(String[] args) throws Exception {
        Path data = Path.of(args[0]); Files.createDirectories(data);
        System.setProperty("nbw.test.data", data.toString());
        Player alice = new Player("Alice"), bob = new Player("Bob");
        Bukkit.getServer().players.addAll(List.of(alice, bob));
        var plugin = new NoteblockWorldMCPlugin(); plugin.onEnable(); drain(plugin);
        try {
            var playback = plugin.getPlaybackManager(); var downloads = plugin.getDownloadManager();
            var config = plugin.getConfig(); var pack = plugin.getResourcePackService();
            var valid = SongPreview.local("good-id", "Usable", "", 1, 1, 1, "");
            var invalid = SongPreview.local("../bad", "Invalid", "", 1, 1, 1, "");
            var page = SongPage.of(Arrays.asList(null, new SongPreview(), invalid, valid), 1, 36, 4);
            check(page.getContent().equals(List.of(valid)), "Catalog pages discard null entries and missing/unsafe IDs");
            check(page.getContent() == page.getContent(), "Validated catalog content is cached rather than rebuilt per click");
            try { page.getContent().clear(); throw new AssertionError("Mutable catalog page"); }
            catch (UnsupportedOperationException expected) { check(true, "Validated catalog content is immutable"); }
            var gui = new SongBrowserGui(plugin, alice, SongQuery.builder().build());
            check(call(gui, "songItem", new SongPreview(), 0) == null, "Missing IDs cannot crash GUI item rendering");
            setField(gui, "currentPage", page); call(gui, "render");
            check(gui.getInventory().getItem(0) != null && gui.getInventory().getItem(1) == null,
                    "A bad catalog record does not hide or misalign the remaining usable song");

            NbsSong harp = parse(nbs("Harp", List.of(), new int[][]{{0, 0, 0, 45, 100}}));
            NbsSong snare = parse(nbs("Snare", List.of(), new int[][]{{0, 0, 3, 45, 100}}));
            playback.startBroadcast(alice, harp); Object broadcast = field(playback, "broadcastSession");
            call(broadcast, "emitNote", 0, 1f, field(broadcast, "listeners"));
            int cuts = alice.cuts;
            check(!playback.stopFading(alice.getUniqueId()), "Personal stop reports no personal song during a broadcast");
            check(alice.cuts == cuts && field(playback, "broadcastSession") == broadcast,
                    "Personal stop preserves current broadcast notes and session");
            playback.stopBroadcast();
            check(alice.broadCuts == 0 && alice.stoppedSounds.contains(Sound.BLOCK_NOTE_BLOCK_HARP),
                    "Hard stops target emitted sounds, never the entire RECORDS category");

            playback.start(alice, harp); Object personal = FixChecks.personal(playback, alice);
            call(personal, "emitNote", 0, 1f, field(personal, "listeners"));
            playback.startBroadcast(alice, snare); broadcast = field(playback, "broadcastSession");
            call(broadcast, "emitNote", 0, 1f, field(broadcast, "listeners"));
            call(personal, "refreshListeners"); int stoppedBefore = alice.stoppedSounds.size();
            playback.stop(alice.getUniqueId());
            check(alice.stoppedSounds.subList(stoppedBefore, alice.stoppedSounds.size()).equals(List.of(Sound.BLOCK_NOTE_BLOCK_HARP)),
                    "Stopping a personal session with an empty listener snapshot cuts only its own ringing sound");
            playback.stopAll();

            playback.start(alice, harp); personal = FixChecks.personal(playback, alice);
            call(personal, "emitNote", 0, 1f, field(personal, "listeners"));
            call(personal, "requestFinish"); drain(plugin);
            playback.startBroadcast(alice, harp); broadcast = field(playback, "broadcastSession");
            call(broadcast, "emitNote", 0, 1f, field(broadcast, "listeners")); cuts = alice.cuts;
            playback.stop(alice.getUniqueId());
            check(alice.cuts == cuts, "A personal tail sharing the broadcast's event decays without cutting the broadcast");
            playback.stopAll(); check(alice.broadCuts == 0, "Shutdown-style stopAll also preserves unrelated sound categories/events");

            String probe = downloads.importBytes(nbs("Reload Probe", List.of(), new int[][]{{0, 0, 0, 45, 100}}), "probe.nbs");
            String gone = downloads.importBytes(nbs("Gone", List.of(), new int[][]{{0, 0, 0, 45, 100}}), "gone.nbs");
            Files.delete(downloads.getNbsFile(gone));
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var armed = new AtomicBoolean(true); Thread main = Thread.currentThread();
            com.google.gson.Gson.beforeRead = text -> {
                if (text.contains("Reload Probe") && armed.compareAndSet(true, false)) {
                    if (Thread.currentThread() == main || Thread.holdsLock(downloads))
                        throw new AssertionError("Metadata read ran on the game thread or under the library lock");
                    entered.countDown();
                    try { if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("Metadata test timed out"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                }
            };
            CompletableFuture<Void> reload;
            String added;
            try {
                reload = plugin.applySettings(config, true);
                check(entered.await(2, TimeUnit.SECONDS) && !reload.isDone(), "Reload returns while metadata is being read off-thread without the library lock");
                try { plugin.applySettings(config, true); throw new AssertionError("Overlapping explicit reload accepted"); }
                catch (IllegalStateException expected) { check(true, "Overlapping explicit reloads are rejected without queuing duplicate scans"); }
                check(plugin.applySettings(config, false).isDone(), "A configuration-only change completes without a library scan");
                check(downloads.remove(probe), "Song deletion can proceed while reload metadata is paused");
                added = downloads.importBytes(nbs("During Scan", List.of(), new int[][]{{0, 0, 0, 45, 100}}), "during.nbs");
            } finally { release.countDown(); }
            drain(plugin); reload.join();
            com.google.gson.Gson.beforeRead = text -> {};
            check(!downloads.getDownloadedIds().contains(probe) && downloads.isDownloaded(added),
                    "A superseded reload scan retries without resurrecting a removed song or losing a new import");
            check(!downloads.getDownloadedIds().contains(gone), "Asynchronous explicit reload drops song files deleted directly on disk");

            int port = freePort(); config.set("resource-pack.enabled", true); config.set("resource-pack.port", port);
            config.set("resource-pack.bind-address", "127.0.0.1");
            config.set("resource-pack.public-url", "http://127.0.0.1:" + port + "/pack.zip");
            pack.loadHotSettings(); drain(plugin); pack.send(alice); status(pack, alice, SUCCESSFULLY_LOADED);
            UUID installed = alice.packs.get(alice.packs.size() - 1).info.id();
            config.set("resource-pack.pack-format", 90); pack.loadHotSettings(); drain(plugin); pack.send(alice);
            UUID pending = alice.packs.get(alice.packs.size() - 1).info.id();
            check(!installed.equals(pending), "Cleanup test has a loaded generation and a distinct pending generation");
            check(alice.removedPacks.contains(installed), "The loaded generation is withdrawn together with the push that replaces it");
            int removedBefore = alice.removedPacks.size();
            UUID unrelated = UUID.randomUUID();
            alice.sendResourcePacks(net.kyori.adventure.resource.ResourcePackRequest.resourcePackRequest().packs(
                    net.kyori.adventure.resource.ResourcePackInfo.resourcePackInfo(unrelated,
                            java.net.URI.create("https://example.invalid/other.zip"), "0".repeat(40))).replace(false).build());
            Inventory other = new Inventory(null, 54); bob.openInventory(other); alice.openInventory(gui.getInventory());
            plugin.onDisable();
            check(alice.getOpenInventory().getTopInventory() != gui.getInventory(), "Full disable closes the plugin's open menus");
            check(bob.getOpenInventory().getTopInventory() == other, "Full disable preserves unrelated inventories");
            check(new HashSet<>(alice.removedPacks.subList(removedBefore, alice.removedPacks.size())).equals(Set.of(pending)),
                    "Full disable removes exactly the plugin pack the client still holds");
            check(!alice.removedPacks.contains(unrelated) && !pack.isPackLoaded(alice.getUniqueId()),
                    "Unrelated resource packs remain installed and old plugin pack state is cleared");
        } finally {
            com.google.gson.Gson.beforeRead = text -> {};
            if (!plugin.isStopping()) plugin.onDisable();
        }
        System.out.println("PASS: " + checks + " follow-up playback, GUI, reload and cleanup checks.");
    }
}
