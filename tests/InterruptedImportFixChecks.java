import io.github.noteblockworldmc.NoteblockWorldMCPlugin;
import java.nio.file.*;
import java.util.*;

/** Recovery tests for interrupted imports and live path ownership. */
public final class InterruptedImportFixChecks extends RegressionChecks {
    public static void main(String[] args) throws Exception {
        Path data = Path.of(args[0]); Files.createDirectories(data);
        System.setProperty("nbw.test.data", data.toString());
        Path imports = data.resolve("import"); Files.createDirectories(imports);
        Path source = imports.resolve("Interrupted.nbs");
        byte[] song = nbs("Interrupted", List.of(), new int[][]{{0,0,0,45,100}});
        Files.write(source, song);
        Path claim = imports.resolve(".nbw-claim-" + UUID.randomUUID());
        Files.move(source, claim); // importLocal's claim step, before successful commit/finally.
        Path staged = data.resolve("songs/.staging/song-abandoned"); Files.createDirectories(staged);
        Files.write(staged.resolve("song.nbs"), song);
        var plugin = new NoteblockWorldMCPlugin(); plugin.onEnable(); drain(plugin);
        try {
            var downloads = plugin.getDownloadManager();
            var recovered = downloads.findImportFiles(null);
            check(!Files.exists(claim) && recovered.size() == 1, "Startup restores a discoverable NBS input");
            check(Arrays.equals(Files.readAllBytes(recovered.get(0)), song), "Recovery preserves every input byte");
            check(!Files.exists(staged), "Startup cleans abandoned staging files");
            String imported = downloads.importLocal(recovered.get(0));
            check(downloads.isDownloaded(imported), "Recovered input imports successfully");
            check(((Set<?>)field(downloads, "activeImportPaths")).isEmpty(), "Successful import releases claim and staging ownership");

            Path activeStage = (Path)call(downloads, "newStage");
            Files.write(activeStage.resolve("song.nbs"), song);
            Path activeClaim = imports.resolve(".nbw-claim-" + UUID.randomUUID());
            Files.write(activeClaim, song);
            Object lock = field(downloads, "recoveryLock");
            synchronized (lock) { ((Set<Path>)field(downloads, "activeImportPaths")).add(activeClaim); }
            Path laterClaim = imports.resolve(".nbw-claim-" + UUID.randomUUID());
            Files.write(laterClaim, song);
            Files.write(source, new byte[]{9,8,7});
            Path abandoned = data.resolve("songs/.staging/song-later"); Files.createDirectories(abandoned);
            Files.write(abandoned.resolve("song.nbs"), song);
            Path backup = data.resolve("songs/.orphaned/keep"); Files.createDirectories(backup);
            Files.write(backup.resolve("audio.ogg"), new byte[]{1,2,3});
            Path linkedStage = data.resolve("songs/.staging/song-link");
            Files.createSymbolicLink(linkedStage, backup);
            Path linkedClaim = imports.resolve(".nbw-claim-" + UUID.randomUUID());
            Files.createSymbolicLink(linkedClaim, source);
            var reload = plugin.applySettings(plugin.getConfig(), true); drain(plugin); reload.join();
            check(Files.exists(activeClaim) && Files.exists(activeStage.resolve("song.nbs")),
                    "Reload leaves live claims and staging files untouched");
            check(!Files.exists(laterClaim) && !Files.exists(abandoned), "Reload recovers newly abandoned work");
            check(Arrays.equals(Files.readAllBytes(source), new byte[]{9,8,7}), "Recovery never overwrites existing import inputs");
            check(Files.isSymbolicLink(linkedClaim) && Files.isSymbolicLink(linkedStage)
                    && Files.exists(backup.resolve("audio.ogg")), "Recovery does not follow symlinks or remove backups");
            synchronized (lock) { ((Set<Path>)field(downloads, "activeImportPaths")).remove(activeClaim); }
            call(downloads, "cleanupStage", activeStage);
            org.bukkit.Bukkit.getScheduler().tick(); drain(plugin);
            check(!Files.exists(activeClaim) && !Files.exists(activeStage), "Periodic sweep recovers released abandoned work");
        } finally { plugin.onDisable(); }
        System.out.println("PASS: " + checks + " interrupted-import recovery regression checks.");
    }
}
