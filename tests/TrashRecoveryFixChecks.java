import io.github.noteblockworldmc.NoteblockWorldMCPlugin;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Regression coverage for deferred trash cleanup and recovery. */
public final class TrashRecoveryFixChecks extends RegressionChecks {
    public static void main(String[] args) throws Exception {
        Path data = Path.of(args[0]); Files.createDirectories(data);
        System.setProperty("nbw.test.data", data.toString());
        var plugin = new NoteblockWorldMCPlugin(); plugin.onEnable(); drain(plugin);
        Path retained;
        try {
            var downloads = plugin.getDownloadManager();
            String id = downloads.importBytes(nbs("Removed", List.of(), new int[][]{{0,0,0,45,100}}), "remove.nbs");
            CountDownLatch entered = new CountDownLatch(4), release = new CountDownLatch(1);
            List<CompletableFuture<Void>> jobs = new ArrayList<>();
            try {
                for (int i = 0; i < 4; i++) jobs.add(plugin.getWorkPool().run(() -> {
                    entered.countDown();
                    try { if (!release.await(4, TimeUnit.SECONDS)) throw new AssertionError("Blocker expired"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                }));
                check(entered.await(2, TimeUnit.SECONDS), "All workers occupied");
                for (int i = 0; i < 32; i++) jobs.add(plugin.getWorkPool().run(() -> {}));
                check(plugin.getWorkPool().run(() -> {}).isCompletedExceptionally(), "Queue is saturated");
                check(downloads.remove(id), "Removal succeeds despite cleanup rejection");
                try (var trash = Files.list(data.resolve("songs/.trash"))) { retained = trash.findFirst().orElseThrow(); }
                check(Files.exists(retained.resolve("song.nbs")) && !downloads.isDownloaded(id),
                        "Removed file remains on disk outside the library index");
            } finally { release.countDown(); }
            CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new)).get(3, TimeUnit.SECONDS);
            org.bukkit.Bukkit.getScheduler().tick(); drain(plugin);
            check(!Files.exists(retained), "Periodic retry deletes trash after queue recovers");
            retained = data.resolve("songs/.trash").resolve(UUID.randomUUID().toString());
            Files.createDirectories(retained); Files.writeString(retained.resolve("song.nbs"), "abandoned");
            var reloaded = plugin.applySettings(plugin.getConfig(), true); drain(plugin); reloaded.join();
            check(!Files.exists(retained), "Reload recovers abandoned cleanup");
            Files.createDirectories(retained); Files.writeString(retained.resolve("song.nbs"), "restart recovery");
            Path recovery = data.resolve("songs/.orphaned/keep");
            Files.createDirectories(recovery); Files.writeString(recovery.resolve("audio.ogg"), "backup");
            Path staging = data.resolve("songs/.staging/keep");
            Files.createDirectories(staging); Files.writeString(staging.resolve("song.nbs"), "in progress");
            Path unrelated = data.resolve("songs/.trash/keep");
            Files.createDirectories(unrelated); Files.writeString(unrelated.resolve("keep.txt"), "not plugin trash");
            Files.createSymbolicLink(data.resolve("songs/.trash").resolve(UUID.randomUUID().toString()), recovery);
        } finally { plugin.onDisable(); }
        var restarted = new NoteblockWorldMCPlugin(); restarted.onEnable(); drain(restarted);
        try {
            check(!Files.exists(retained) && restarted.getDownloadManager().getDownloadedIds().isEmpty(),
                    "Startup recovers abandoned trash without republishing removed songs");
            check(Files.exists(data.resolve("songs/.orphaned/keep/audio.ogg"))
                    && Files.exists(data.resolve("songs/.staging/keep/song.nbs"))
                    && Files.exists(data.resolve("songs/.trash/keep/keep.txt")),
                    "Cleanup preserves recovery backups, staging files and unrecognized entries");
            Object task = field(restarted.getDownloadManager(), "trashCleanupTask");
            restarted.getDownloadManager().stop();
            check(((org.bukkit.scheduler.BukkitTask)task).cancelled, "Disable cancels periodic cleanup task");
        } finally { restarted.onDisable(); }
        System.out.println("PASS: " + checks + " trash cleanup/recovery regression checks.");
    }
}
