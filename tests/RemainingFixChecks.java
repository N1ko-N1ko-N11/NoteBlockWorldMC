import io.github.noteblockworldmc.NoteblockWorldMCPlugin;
import io.github.noteblockworldmc.pack.ResourcePackService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Regression coverage for global shared tails and rejected audio-fetch admission. */
public final class RemainingFixChecks extends RegressionChecks {
    public static void main(String[] args) throws Exception {
        Path data = Path.of(args[0]); Files.createDirectories(data);
        System.setProperty("nbw.test.data", data.toString());
        Player player = new Player("Listener"); Bukkit.getServer().players.add(player);
        var plugin = new NoteblockWorldMCPlugin(); plugin.onEnable(); drain(plugin);
        try {
            var playback = plugin.getPlaybackManager();
            var song = parse(nbs("Shared tails", List.of(), new int[][]{{0, 0, 0, 45, 100}}));
            playback.start(player, song);
            Object personal = FixChecks.personal(playback, player);
            call(personal, "emitNote", 0, 1f, field(personal, "listeners"));
            call(personal, "requestFinish"); drain(plugin);
            playback.startBroadcast(player, song);
            Object broadcast = field(playback, "broadcastSession");
            call(broadcast, "emitNote", 0, 1f, field(broadcast, "listeners"));
            call(broadcast, "requestFinish"); drain(plugin);
            check(playback.activeSongs().isEmpty(), "Both sessions finished naturally");
            int cuts = player.cuts;
            new io.github.noteblockworldmc.command.SongsCommand(plugin).handle(player, "nbw", new String[]{"stop", "@a"});
            check(player.cuts == cuts + 1, "Global command stops the shared event once");
            Queue<?> tails = (Queue<?>) field(((Map<?, ?>) field(playback, "listenerVoices")).get(player.getUniqueId()), "ends");
            check(tails.isEmpty(), "Global stop removes both shared tails from accounting");
            playback.stopAll();
            check(player.cuts == cuts + 1 && player.broadCuts == 0, "Cleanup does not repeat the event stop or stop the entire sound category");

            playback.start(player, song);
            personal = FixChecks.personal(playback, player);
            call(personal, "emitNote", 0, 1f, field(personal, "listeners"));
            call(personal, "requestFinish"); drain(plugin);
            playback.startBroadcast(player, song);
            broadcast = field(playback, "broadcastSession");
            call(broadcast, "emitNote", 0, 1f, field(broadcast, "listeners"));
            cuts = player.cuts;
            check(playback.stopEveryoneFading() && (long) field(broadcast, "fadeStartNanos") != 0,
                    "First global stop preserves the active broadcast fade");
            check(player.cuts == cuts, "Shared old tail cannot prematurely cut the active fade");
            var gui = new io.github.noteblockworldmc.gui.SongBrowserGui(plugin, player,
                    io.github.noteblockworldmc.api.NoteBlockWorldClient.SongQuery.builder().build());
            call(gui, "handleStopClick", org.bukkit.event.inventory.ClickType.SHIFT_LEFT);
            check(playback.activeSongs().isEmpty() && player.cuts == cuts + 1 && player.broadCuts == 0,
                    "GUI global stop uses the same path and a second stop cuts shared tails immediately");
            playback.stopAll();

            var pack = plugin.getResourcePackService();
            Path samples = data.resolve("vanilla-sounds"); Files.createDirectories(samples);
            for (String name : ResourcePackService.INSTRUMENT_NAMES) Files.write(samples.resolve(name + ".ogg"), ogg());
            // A cache verified under the current asset-selection rules needs no index lookup.
            Files.writeString(samples.resolve(".layout"), "2\n");
            check(pack.getVanillaSounds().isComplete(), "Retry test needs no external network");
            CountDownLatch entered = new CountDownLatch(4), release = new CountDownLatch(1);
            List<CompletableFuture<Void>> blockers = new ArrayList<>();
            try {
                for (int i = 0; i < 4; i++) blockers.add(plugin.getWorkPool().run(() -> {
                    entered.countDown();
                    try { if (!release.await(4, TimeUnit.SECONDS)) throw new AssertionError("Bounded blocker timed out"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                }));
                check(entered.await(2, TimeUnit.SECONDS), "Four workers occupied");
                for (int i = 0; i < 32; i++) blockers.add(plugin.getWorkPool().run(() -> {}));
                check(plugin.getWorkPool().run(() -> {}).isCompletedExceptionally(), "Shared queue is full");
                call(pack, "fetchVanillaSoundsAsync");
                check(!((AtomicBoolean) field(pack, "fetchingVanilla")).get(), "Rejected submission releases fetch admission immediately");
            } finally { release.countDown(); }
            CompletableFuture.allOf(blockers.toArray(CompletableFuture[]::new)).get(3, TimeUnit.SECONDS);
            plugin.getConfig().set("resource-pack.download-vanilla-sounds", true);
            pack.reloadContent(); drain(plugin);
            check(!((AtomicBoolean) field(pack, "fetchingVanilla")).get(), "Reload fetch completes after queue recovers");
        } finally { plugin.onDisable(); }
        System.out.println("PASS: " + checks + " global-stop and fetch-retry regression checks.");
    }
}
