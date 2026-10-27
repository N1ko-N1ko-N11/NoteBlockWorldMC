import io.github.noteblockworldmc.NoteblockWorldMCPlugin;
import io.github.noteblockworldmc.command.SongsCommand;
import io.github.noteblockworldmc.gui.SongBrowserGui;
import io.github.noteblockworldmc.pack.ResourcePackService;
import io.github.noteblockworldmc.pack.OggTools;
import io.github.noteblockworldmc.playback.PlaybackManager;
import io.github.noteblockworldmc.util.SettingsValidator;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.logging.*;

/** Paper 26.3 port (command registration, pack format) and the follow-up audit fixes. */
public final class Port263Checks extends RegressionChecks {
    static final List<String> logged = new CopyOnWriteArrayList<>();
    /** Held strongly: LogManager keeps loggers weakly, and a collected one loses its handler. */
    static final Logger LOG = Logger.getLogger("regression");

    static void waitFor(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("Timed out: " + message);
            Bukkit.getScheduler().drain(); Thread.sleep(5);
        }
    }
    static Object staticCall(Class<?> type, String name, Object... args) throws Exception {
        for (var m : type.getDeclaredMethods()) if (m.getName().equals(name) && m.getParameterCount() == args.length) {
            m.setAccessible(true); return m.invoke(null, args);
        }
        throw new NoSuchMethodException(name);
    }

    public static void main(String[] args) throws Exception {
        Path data = Path.of(args[0]); Files.createDirectories(data);
        System.setProperty("nbw.test.data", data.toString());
        LOG.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { logged.add(record.getMessage()); }
            @Override public void flush() {}
            @Override public void close() {}
        });
        Player alice = new Player("Alice"), bob = new Player("Bob");
        Bukkit.getServer().players.add(alice); Bukkit.getServer().players.add(bob);
        var plugin = new NoteblockWorldMCPlugin(); plugin.onEnable(); drain(plugin);
        try {
            commandRegistration(plugin, alice);
            packFormat(plugin);
            vanillaAssetSelection();
            voiceAccountingAfterQuit(plugin, alice, bob);
            finishedMessage(plugin, alice);
            abandonedDownloads(plugin, alice);
            nullCategories(plugin, alice);
            chunkedBodies();
            busyRebuild(plugin, alice);
            sharedDownloads(plugin);
            removalWithoutAudio(plugin);
            categoryRetry(plugin, alice);
            descriptionFormatting();
            samePortRebind(plugin);
            reloadKeepsPlaying(plugin, alice, bob);
            singleReloadPerUpdate(plugin, alice, bob);
            stereoPlacement(plugin, alice);
            decodedLengthBomb();
            ipv6RateLimit(plugin);
            permissionAwareCompletion(plugin, bob);
            boundedDetails(plugin, alice);
            trustedSources(plugin, alice);
            strangerShare();
            dialogCallbackLifetime();
            configRollback(plugin, alice);
        } finally { plugin.onDisable(); }
        System.out.println("PASS: " + checks + " Paper 26.3 port and audit-fix checks.");
    }

    /** The obsolete plugin.yml executor is replaced by a Brigadier BasicCommand. */
    static void commandRegistration(NoteblockWorldMCPlugin plugin, Player alice) throws Exception {
        var command = plugin.commands.get("nbw");
        check(command instanceof SongsCommand, "onEnable registers /nbw through the Brigadier BasicCommand API");
        check(command.canUse(alice), "The root literal stays visible; subcommands check their own permissions");
        CommandSourceStack source = CommandSourceStack.of(alice);
        var root = new ArrayList<>(command.suggest(source, new String[0]));
        check(root.contains("play") && root.contains("search") && root.contains("reload"),
                "Brigadier's empty argument array before the first token still completes subcommands");
        check(new HashSet<>(command.suggest(source, new String[]{"pl"})).equals(Set.of("play", "playing")),
                "A partial subcommand completes by prefix");
        check(command.suggest(source, new String[]{"volume", ""}).contains("50"), "Nested completion keeps working");
        command.execute(source, new String[0]);
        check(alice.getOpenInventory().getTopInventory().getHolder() instanceof SongBrowserGui,
                "Bare /nbw executed through Brigadier opens the song browser");
        alice.closeInventory();
        int before = alice.messages.size();
        command.execute(source, new String[]{"help"});
        check(alice.messages.size() > before && alice.messages.get(before).value.contains("label=nbw"),
                "Usage text names the registered label");
    }

    static void packFormat(NoteblockWorldMCPlugin plugin) throws Exception {
        Class<?> service = ResourcePackService.class;
        check((int) staticCall(service, "resolvePackFormat", null, "26.3") == 97, "auto declares 97 on Minecraft 26.3");
        check((int) staticCall(service, "resolvePackFormat", "auto", "26.2") == 88, "auto declares 88 on 26.2");
        check((int) staticCall(service, "resolvePackFormat", "AUTO", "26.1.2") == 84, "auto declares 84 on 26.1.2");
        check((int) staticCall(service, "resolvePackFormat", "auto", "27.1") == 97, "Unknown newer versions use the newest known format");
        check((int) staticCall(service, "resolvePackFormat", 90, "26.3") == 90, "An explicit number is used as given");
        var config = new org.bukkit.configuration.file.FileConfiguration();
        config.set("resource-pack.pack-format", "auto"); SettingsValidator.validate(config); checks++;
        for (Object bad : new Object[]{"automatic", 97.5, 0}) {
            config.set("resource-pack.pack-format", bad);
            try { SettingsValidator.validate(config); throw new AssertionError("Accepted pack-format " + bad); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        var pack = plugin.getResourcePackService();
        // The harness server reports Minecraft 26.2.
        check(pack.getPackFormat() == 88, "The default (absent/auto) setting follows the server version");
        Path zip = plugin.getDataFolder().toPath().resolve("resource-pack.zip");
        waitFor(() -> Files.exists(zip), "initial pack build");
        try (var file = new java.util.zip.ZipFile(zip.toFile())) {
            String meta = new String(file.getInputStream(file.getEntry("pack.mcmeta")).readAllBytes(), StandardCharsets.UTF_8);
            check(meta.contains("\"min_format\":88") && meta.contains("\"max_format\":88") && !meta.contains("pack_format"),
                    "pack.mcmeta declares the resolved format through min/max_format only");
        }
        logged.clear();
        plugin.getConfig().set("resource-pack.pack-format", 84); pack.loadHotSettings();
        check(pack.getPackFormat() == 84 && logged.stream().anyMatch(m -> m.contains("pack-format is 84") && m.contains("pack format 88")),
                "A pinned format that disagrees with the server version is reported " + pack.getPackFormat() + " " + logged);
        int warnings = logged.size(); pack.loadHotSettings();
        check(logged.size() == warnings, "The same mismatch is reported once, not on every reload");
        plugin.getConfig().set("resource-pack.pack-format", "auto"); pack.loadHotSettings(); drain(plugin);
        check(pack.getPackFormat() == 88, "Returning to auto follows the server again");
    }

    /** Minecraft plays harp2/bassattack; the retired harp/bass files are still in the index. */
    static void vanillaAssetSelection() throws Exception {
        Class<?> vanilla = io.github.noteblockworldmc.pack.VanillaSounds.class;
        // Exactly as the 26.3 client's sounds.json and asset index list them.
        Map<String, String> events = new LinkedHashMap<>();
        String[][] table = {{"banjo", "banjo"}, {"basedrum", "bd"}, {"bass", "bassattack"}, {"bell", "bell"}, {"bit", "bit"},
                {"chime", "icechime"}, {"cow_bell", "cow_bell"}, {"didgeridoo", "didgeridoo"}, {"flute", "flute"},
                {"guitar", "guitar"}, {"harp", "harp2"}, {"hat", "hat"}, {"iron_xylophone", "iron_xylophone"},
                {"pling", "pling"}, {"snare", "snare"}, {"trumpet", "trumpet"}, {"trumpet_exposed", "trumpet_exposed"},
                {"trumpet_oxidized", "trumpet_oxidized"}, {"trumpet_weathered", "trumpet_weathered"}, {"xylophone", "xylobone"}};
        List<String> keys = new ArrayList<>(List.of("minecraft/sounds/mob/cat/hiss1.ogg"));
        for (String[] row : table) events.put("block.note_block." + row[0], "note/" + row[1]);
        for (String stem : List.of("banjo", "bass", "bassattack", "bd", "bell", "bit", "cow_bell", "didgeridoo", "flute", "guitar",
                "harp", "harp2", "hat", "icechime", "iron_xylophone", "pling", "snare", "trumpet", "trumpet_exposed",
                "trumpet_oxidized", "trumpet_weathered", "xylobone")) keys.add("minecraft/sounds/note/" + stem + ".ogg");
        @SuppressWarnings("unchecked")
        var chosen = (Map<String, String>) staticCall(vanilla, "instrumentAssets", events, keys);
        check(chosen.size() == ResourcePackService.INSTRUMENT_NAMES.length, "Every instrument resolves against the 26.3 index");
        for (String[] row : table)
            check(chosen.get(row[0]).equals("minecraft/sounds/note/" + row[1] + ".ogg"), row[0] + " uses the file its event plays");
        @SuppressWarnings("unchecked")
        var offline = (Map<String, String>) staticCall(vanilla, "instrumentAssets", Map.of(), keys);
        check(offline.equals(chosen), "Without sounds.json the alias table makes the same choices");
        check("minecraft/sounds/note/harp.ogg".equals(staticCall(vanilla, "previousAsset", "harp", keys))
                && "minecraft/sounds/note/bass.ogg".equals(staticCall(vanilla, "previousAsset", "bass", keys)),
                "Files cached by earlier releases are recognised");
        check((boolean) staticCall(vanilla, "replaceCached", "aa", "bb", "aa"), "A cached copy of the retired sample is replaced");
        check(!(boolean) staticCall(vanilla, "replaceCached", "bb", "bb", "aa"), "The correct sample is kept");
        check(!(boolean) staticCall(vanilla, "replaceCached", "cc", "bb", "aa"), "An administrator's own sample is kept");
        check(!(boolean) staticCall(vanilla, "replaceCached", "cc", "bb", null), "Unchanged mappings never replace files");
    }

    /** A stale clock snapshot cannot re-register a player who has already quit. */
    static void voiceAccountingAfterQuit(NoteblockWorldMCPlugin plugin, Player alice, Player bob) throws Exception {
        var playback = plugin.getPlaybackManager();
        var song = parse(nbs("Quit", List.of(), new int[][]{{0, 0, 0, 45, 100}}));
        playback.startBroadcast(alice, song); drain(plugin);
        Object broadcast = field(playback, "broadcastSession");
        Object snapshot = field(broadcast, "listeners");
        check(((List<?>) snapshot).size() == 2, "Both players hear the broadcast");
        playback.onQuit(new PlayerQuitEvent(bob));
        Bukkit.getServer().players.remove(bob);
        call(broadcast, "emitNote", 0, 1f, snapshot);
        var voices = (Map<?, ?>) field(playback, "listenerVoices");
        check(!voices.containsKey(bob.getUniqueId()), "Notes from the stale snapshot do not recreate the quit player's voices");
        check(voices.containsKey(alice.getUniqueId()), "Remaining listeners keep their voice accounting");
        playback.stopBroadcast();
        Bukkit.getServer().players.add(bob);
    }

    /** A fade that completes after "/nbw stop" is not announced as a natural ending. */
    static void finishedMessage(NoteblockWorldMCPlugin plugin, Player alice) throws Exception {
        var playback = plugin.getPlaybackManager();
        alice.messages.clear();
        plugin.getConfig().set("playback.fade-out-ms", 500); playback.loadHotSettings();
        var song = parse(nbs("Fade", List.of(), new int[][]{{0, 0, 0, 45, 100}, {10, 0, 0, 45, 100}}));
        playback.start(alice, song);
        Object session = FixChecks.personal(playback, alice);
        check(playback.stopFading(alice.getUniqueId()), "A fading stop begins");
        setField(session, "fadeStartNanos", System.nanoTime() - 2_000_000_000L);
        call(session, "step"); drain(plugin);
        check(FixChecks.personal(playback, alice) == null, "The faded session ended");
        check(warnings(alice, "playback.finished") == 0, "An explicit stop is not followed by \"Finished playing\"");
        playback.start(alice, song);
        session = FixChecks.personal(playback, alice);
        call(session, "requestFinish"); drain(plugin);
        check(warnings(alice, "playback.finished") == 1, "A natural ending still reports that the song finished");
        playback.startBroadcast(alice, song);
        check(playback.stopBroadcastFading(), "A broadcast fade begins");
        int before = Bukkit.getServer().messages.size();
        finishFade(playback);
        check(Bukkit.getServer().messages.subList(before, Bukkit.getServer().messages.size()).stream()
                .noneMatch(c -> c.value.startsWith("playback.finished")), "A stopped broadcast is not announced as finished");
    }

    /** A download that fails before playback must not leave a phantom pending song behind. */
    static void abandonedDownloads(NoteblockWorldMCPlugin plugin, Player alice) throws Exception {
        var playback = plugin.getPlaybackManager();
        var command = new SongsCommand(plugin);
        command.handle(alice, "nbw", new String[]{"play", "@a", "NotDownloaded1"});
        waitFor(() -> alice.messages.stream().anyMatch(c -> c.value.startsWith("download.failed")), "failed download reply");
        check(field(playback, "broadcastRequest") == null, "A failed broadcast download releases its request");
        int before = alice.messages.size();
        command.handle(alice, "nbw", new String[]{"stop", "@a"});
        check(alice.messages.get(before).value.startsWith("playback.nothing-playing-anywhere"),
                "Stopping afterwards does not claim to have stopped a song");
        alice.messages.clear();
        command.handle(alice, "nbw", new String[]{"play", "NotDownloaded2"});
        waitFor(() -> alice.messages.stream().anyMatch(c -> c.value.startsWith("download.failed")), "failed personal download");
        check(!playback.stopFading(alice.getUniqueId()), "A failed personal download leaves nothing to stop");
        var request = playback.beginPlay(alice, alice, false);
        var newer = playback.beginPlay(alice, alice, false);
        playback.abandon(request);
        check(playback.isCurrent(newer), "Abandoning an older selection keeps the newer one");
        playback.stop(alice.getUniqueId());
    }

    static void nullCategories(NoteblockWorldMCPlugin plugin, Player alice) throws Exception {
        plugin.getApiClient().categories = CompletableFuture.completedFuture(null);
        alice.messages.clear();
        new SongsCommand(plugin).handle(alice, "nbw", new String[]{"categories"});
        try { drain(plugin); }
        catch (RuntimeException e) { throw new AssertionError("An empty category response threw on the main thread: " + e); }
        check(alice.messages.stream().anyMatch(c -> c.value.startsWith("general.api-error")),
                "An empty category response is reported instead of throwing on the main thread");
        plugin.getApiClient().categories = CompletableFuture.completedFuture(Map.of());
    }

    /** Many small chunks are valid HTTP; only the byte limit bounds a chunked body. */
    static void chunkedBodies() throws Exception {
        var body = new StringBuilder();
        // 30000 two-byte chunks: about 150 KB of chunk framing for 60 KB of data.
        for (int i = 0; i < 30000; i++) body.append("2\r\nab\r\n");
        body.append("0\r\nX-Trailer: yes\r\n\r\n");
        byte[] wire = body.toString().getBytes(StandardCharsets.US_ASCII);
        Class<?> fetcher = io.github.noteblockworldmc.download.SecureUrlFetcher.class;
        byte[] decoded;
        try { decoded = (byte[]) staticCall(fetcher, "readChunked", new ByteArrayInputStream(wire), 1_000_000L); }
        catch (java.lang.reflect.InvocationTargetException e) { throw new AssertionError("A valid many-chunk body was rejected: " + e.getCause()); }
        check(decoded.length == 60_000, "A response of 30000 small chunks decodes completely");
        try { staticCall(fetcher, "readChunked", new ByteArrayInputStream(wire), 50_000L); throw new AssertionError("Oversized"); }
        catch (java.lang.reflect.InvocationTargetException e) { check(e.getCause() instanceof java.io.IOException, "The size limit still applies"); }
        byte[] longLine = ("1" + "0".repeat(2000) + "\r\n").getBytes(StandardCharsets.US_ASCII);
        try { staticCall(fetcher, "readChunked", new ByteArrayInputStream(longLine), 1_000_000L); throw new AssertionError("Long line"); }
        catch (java.lang.reflect.InvocationTargetException e) { check(e.getCause() instanceof java.io.IOException, "Each size line stays bounded"); }
        byte[] badEnding = "3\r\nabcX\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        try { staticCall(fetcher, "readChunked", new ByteArrayInputStream(badEnding), 1_000L); throw new AssertionError("Bad ending"); }
        catch (java.lang.reflect.InvocationTargetException e) { check(e.getCause() instanceof java.io.IOException, "Chunk framing is still validated"); }
    }

    /** A rebuild refused by saturated workers is retried rather than silently dropped. */
    static void busyRebuild(NoteblockWorldMCPlugin plugin, Player alice) throws Exception {
        var pack = plugin.getResourcePackService();
        var scheduler = Bukkit.getScheduler();
        drain(plugin);
        CountDownLatch entered = new CountDownLatch(4), release = new CountDownLatch(1);
        List<CompletableFuture<Void>> blockers = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) blockers.add(plugin.getWorkPool().run(() -> {
                entered.countDown();
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }));
            check(entered.await(2, TimeUnit.SECONDS), "Workers occupied");
            for (int i = 0; i < 32; i++) blockers.add(plugin.getWorkPool().run(() -> {}));
            logged.clear();
            pack.onLibraryChanged();
            // Run only what is queued now: the debounced build and its completion hop.
            for (int round = 0; round < 2; round++) {
                for (var task : List.copyOf(scheduler.queued)) { scheduler.queued.remove(task); if (!task.cancelled) task.action.run(); }
            }
            check(logged.stream().anyMatch(m -> m.contains("Pack rebuild deferred")), "A refused build is reported as deferred");
            check(((java.util.concurrent.atomic.AtomicBoolean) field(pack, "rebuildPending")).get() && !scheduler.queued.isEmpty(),
                    "A retry is scheduled instead of forgetting the library change");
        } finally { release.countDown(); }
        CompletableFuture.allOf(blockers.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
        waitFor(() -> !((java.util.concurrent.atomic.AtomicBoolean) callQuiet(pack)).get(), "retried rebuild");
        check(logged.stream().noneMatch(m -> m.contains("Pack build failed")), "The retried build succeeds once workers free up");
    }
    /** A second request for a song that is still downloading waits for that download. */
    static void sharedDownloads(NoteblockWorldMCPlugin plugin) throws Exception {
        var downloads = plugin.getDownloadManager();
        var client = plugin.getApiClient();
        CompletableFuture<String> open = new CompletableFuture<>();
        client.openUrl = open;
        try {
            var first = downloads.download("JoinMe1", null);
            var second = downloads.download("JoinMe1", null);
            check(!second.isDone(), "A concurrent request joins the running download instead of failing");
            second.cancel(true);
            check(!first.isCancelled() && downloads.isInFlight("JoinMe1"), "Cancelling one caller's view leaves the download running");
            open.completeExceptionally(new java.io.IOException("offline"));
            check(first.isCompletedExceptionally(), "Both callers observe the outcome");
            check(!downloads.isInFlight("JoinMe1") && ((Map<?, ?>) field(downloads, "activeDownloads")).isEmpty(),
                    "A finished download is no longer shared");
            client.openUrl = CompletableFuture.failedFuture(new java.io.IOException("offline"));
            check(downloads.download("JoinMe2", null).isCompletedExceptionally()
                    && ((Map<?, ?>) field(downloads, "activeDownloads")).isEmpty(), "An immediately failed download leaves no stale entry");
        } finally { client.openUrl = null; }
    }

    /** Removing a song without custom audio cannot change the pack, so it does not rebuild it. */
    static void removalWithoutAudio(NoteblockWorldMCPlugin plugin) throws Exception {
        var downloads = plugin.getDownloadManager(); var pack = plugin.getResourcePackService();
        var pending = (java.util.concurrent.atomic.AtomicBoolean) field(pack, "rebuildPending");
        waitFor(() -> !pending.get(), "idle pack builder");
        String plain = downloads.importBytes(nbs("Plain", List.of(), new int[][]{{0, 0, 0, 45, 100}}), "plain.nbs");
        waitFor(() -> !pending.get(), "idle pack builder after import");
        check(downloads.remove(plain) && !pending.get(), "Removing a song without custom audio does not rebuild the pack");
        String voiced = downloads.importBytes(nbs("Voiced", List.of(), new int[][]{{0, 0, 0, 45, 100}}), "voiced.nbs");
        waitFor(() -> !pending.get(), "idle pack builder after second import");
        Path sounds = downloads.getSongDirectory(voiced).resolve("sounds"); Files.createDirectories(sounds);
        Files.write(sounds.resolve("kick.ogg"), new byte[]{1});
        check(downloads.remove(voiced) && pending.get(), "Removing a song with custom audio still rebuilds the pack");
        waitFor(() -> !pending.get(), "rebuild after removal");
    }

    /** A failed or throttled category lookup is retried from the category button. */
    static void categoryRetry(NoteblockWorldMCPlugin plugin, Player alice) throws Exception {
        plugin.getApiClient().categories = CompletableFuture.completedFuture(Map.of("pop", 3));
        Thread.sleep(300); // past the per-player request window
        var gui = new SongBrowserGui(plugin, alice, io.github.noteblockworldmc.api.NoteBlockWorldClient.SongQuery.builder().build());
        check(field(gui, "categories") == null, "No lookup has happened yet");
        gui.handleClick(51, org.bukkit.event.inventory.ClickType.LEFT); drain(plugin);
        check(List.of("pop").equals(field(gui, "categories")), "Clicking the category button retries the lookup");
        plugin.getApiClient().categories = CompletableFuture.completedFuture(Map.of());
    }

    static void descriptionFormatting() throws Exception {
        var preview = io.github.noteblockworldmc.api.model.SongPreview.local("FmtId", "T", "A", 1, 1, 1, "");
        setField(preview, "description", "plain \u00A7kscrambled\nnext");
        check(preview.getDescription().equals("plain  kscrambled\nnext"), "Descriptions cannot carry legacy formatting codes");
        var spoof = io.github.noteblockworldmc.api.model.SongPreview.local("FmtId2", "T", "A", 1, 1, 1, "\u202Eevil\u200B");
        check(!spoof.getUploaderName().contains("\u202E") && !spoof.getUploaderName().contains("\u200B"),
                "Uploader names cannot carry bidi overrides or other invisible format characters");
        check(io.github.noteblockworldmc.util.Limits.display("song\u202Eeman").indexOf('\u202E') < 0,
                "Library titles from .nbs files drop format characters too");
    }

    /** Switching 0.0.0.0 to 127.0.0.1 on the same port works on reload; an impossible address keeps the old one. */
    static void samePortRebind(NoteblockWorldMCPlugin plugin) throws Exception {
        var config = plugin.getConfig(); var pack = plugin.getResourcePackService();
        int port = freePort();
        config.set("resource-pack.enabled", true); config.set("resource-pack.port", port);
        config.set("resource-pack.bind-address", "0.0.0.0");
        config.set("resource-pack.public-url", "http://127.0.0.1:" + port + "/pack.zip");
        plugin.applySettings(config, false); drain(plugin);
        check(pack.isActive() && pack.getBoundAddress().endsWith(":" + port), "Pack server listens on the wildcard address");
        config.set("resource-pack.bind-address", "127.0.0.1");
        plugin.applySettings(config, false); drain(plugin);
        check(pack.isActive() && pack.getBoundAddress().equals("127.0.0.1:" + port),
                "Moving to 127.0.0.1 on the same port succeeds on reload");
        try (var probe = new java.net.Socket("127.0.0.1", port)) { check(probe.isConnected(), "The new listener accepts connections"); }
        config.set("resource-pack.bind-address", "192.0.2.1"); // TEST-NET-1: never a local address
        try { plugin.applySettings(config, false); throw new AssertionError("Bound a non-local address"); }
        catch (IllegalStateException expected) { checks++; }
        check(pack.isActive() && pack.getBoundAddress().equals("127.0.0.1:" + port),
                "A refused move restores the previous listener");
        try (var probe = new java.net.Socket("127.0.0.1", port)) { check(probe.isConnected(), "The restored listener accepts connections"); }
        config.set("resource-pack.bind-address", "127.0.0.1"); config.set("resource-pack.enabled", false);
        plugin.applySettings(config, false); drain(plugin);
        check(!pack.isActive(), "Disabling the pack releases the listener");
    }

    /** A pack rebuild that may push must not silence songs that are already playing. */
    static void reloadKeepsPlaying(NoteblockWorldMCPlugin plugin, Player alice, Player bob) throws Exception {
        var config = plugin.getConfig(); var pack = plugin.getResourcePackService(); var playback = plugin.getPlaybackManager();
        var pending = (java.util.concurrent.atomic.AtomicBoolean) field(pack, "rebuildPending");
        int port = freePort();
        config.set("resource-pack.enabled", true); config.set("resource-pack.port", port);
        config.set("resource-pack.bind-address", "127.0.0.1");
        config.set("resource-pack.public-url", "http://127.0.0.1:" + port + "/pack.zip");
        plugin.applySettings(config, false);
        waitFor(() -> !pending.get() && alice.packs.size() > 0 && bob.packs.size() > 0, "initial push");
        status(pack, alice, org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED);
        status(pack, bob, org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED);
        @SuppressWarnings("unchecked") var resolvedAt = (Map<UUID, Long>) field(pack, "resolvedAtMillis");
        long longAgo = System.currentTimeMillis() - 120_000;
        resolvedAt.put(alice.getUniqueId(), longAgo); resolvedAt.put(bob.getUniqueId(), longAgo);
        config.set("resource-pack.settle-delay-ms", 30_000); pack.loadHotSettings();
        var song = parse(nbs("Steady", List.of(), new int[][]{{0, 0, 0, 45, 100}, {40, 0, 0, 45, 100}}));
        playback.start(alice, song);
        Object session = FixChecks.personal(playback, alice);
        check(((List<?>) field(session, "listeners")).size() == 1, "The song starts for a settled listener");
        int packsBefore = alice.packs.size();
        pack.reloadContent(); // what /nbw reload does once the library has been rechecked
        check(!pack.isSettled(alice.getUniqueId()), "A new song still waits for the pending rebuild");
        call(session, "refreshListeners");
        check(((List<?>) field(session, "listeners")).size() == 1, "The playing song stays audible while the pack rebuilds");
        waitFor(() -> !pending.get(), "rebuild after reload");
        check(alice.packs.size() == packsBefore, "An unchanged pack is not re-sent");
        check(pack.isSettled(alice.getUniqueId()) && resolvedAt.get(alice.getUniqueId()) == longAgo,
                "An unchanged pack does not restart the settle delay");
        call(session, "refreshListeners");
        check(((List<?>) field(session, "listeners")).size() == 1, "The song is still audible after the rebuild");
        playback.stop(alice.getUniqueId());
        config.set("resource-pack.settle-delay-ms", 0); config.set("resource-pack.enabled", false);
        plugin.applySettings(config, false); drain(plugin);
    }

    /**
     * A custom-instrument song download must cost the client one resource
     * reload: the replacement is pushed first and the old pack withdrawn in
     * the same batch, and nothing is removed once the new pack has loaded.
     */
    static void singleReloadPerUpdate(NoteblockWorldMCPlugin plugin, Player alice, Player bob) throws Exception {
        var config = plugin.getConfig(); var pack = plugin.getResourcePackService(); var downloads = plugin.getDownloadManager();
        var pending = (java.util.concurrent.atomic.AtomicBoolean) field(pack, "rebuildPending");
        int port = freePort();
        config.set("resource-pack.enabled", true); config.set("resource-pack.port", port);
        config.set("resource-pack.bind-address", "127.0.0.1");
        config.set("resource-pack.public-url", "http://127.0.0.1:" + port + "/pack.zip");
        plugin.applySettings(config, false);
        waitFor(() -> !pending.get(), "initial push");
        pack.send(alice);
        var load = org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED;
        status(pack, alice, load);
        UUID original = alice.packs.get(alice.packs.size() - 1).info.id();
        int opsBefore = alice.packOps.size(), packsBefore = alice.packs.size();
        // What a finished download of a song with custom instruments does.
        String id = downloads.importBytes(nbs("Custom", List.of("kick.ogg"), new int[][]{{0, 0, 20, 45, 100}}), "custom.nbs");
        Path sounds = downloads.getSongDirectory(id).resolve("sounds"); Files.createDirectories(sounds);
        Files.write(sounds.resolve("kick.ogg"), ogg());
        pack.onLibraryChanged();
        waitFor(() -> !pending.get() && alice.packs.size() > packsBefore, "push of the rebuilt pack");
        UUID replacement = alice.packs.get(alice.packs.size() - 1).info.id();
        check(alice.packs.size() == packsBefore + 1, "One rebuilt pack is pushed");
        check(alice.packOps.subList(opsBefore, alice.packOps.size()).equals(List.of("push:" + replacement, "pop:" + original)),
                "The replacement is pushed first and the old pack withdrawn in the same batch");
        check(!pack.isPackLoaded(alice.getUniqueId()), "The withdrawn pack no longer counts as installed");
        int opsAtLoad = alice.packOps.size();
        status(pack, alice, load);
        check(alice.packOps.size() == opsAtLoad, "Loading the new pack triggers no further removal (no second reload)");
        check(pack.isPackLoaded(alice.getUniqueId()) && pack.hasEvent(alice.getUniqueId(),
                ResourcePackService.customSoundEvent(id, "kick.ogg")), "The new pack, with the song's sounds, is installed");
        downloads.remove(id); waitFor(() -> !pending.get(), "rebuild after cleanup");
        config.set("resource-pack.enabled", false); plugin.applySettings(config, false); drain(plugin);
    }

    /** World-placed stereo: pan by angle, rotate with the view, lead a moving listener. */
    static void stereoPlacement(NoteblockWorldMCPlugin plugin, Player alice) throws Exception {
        var config = plugin.getConfig(); var playback = plugin.getPlaybackManager();
        config.set("playback.follow-player", false); config.set("playback.stereo-panning", true);
        config.set("playback.pan-distance", 2.0); config.set("playback.voice-allocation", false);
        playback.loadHotSettings();
        // ticks 0..3: +30% right, full left, centre, full right; velocity 50%.
        var song = parse(nbs("Stereo", List.of(), new int[][]{
                {0, 0, 0, 45, 130, 50}, {1, 0, 0, 45, 0, 50}, {2, 0, 0, 45, 100, 50}, {3, 0, 0, 45, 200, 50}}));
        try {
            alice.x = 0; alice.y = 1.62; alice.z = 0; alice.yaw = 0; alice.pitch = 0; alice.ping = 0;
            playback.start(alice, song);
            Object session = FixChecks.personal(playback, alice);
            call(session, "refreshListeners");
            alice.soundAt.clear(); alice.gains.clear();
            for (int note = 0; note < 3; note++) call(session, "emitNote", note, 1f, field(session, "listeners"));
            var partial = alice.soundAt.get(0); var left = alice.soundAt.get(1); var centre = alice.soundAt.get(2);
            double dx = partial.getX(), dy = partial.getY() - 1.62, dz = partial.getZ();
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            // Facing +Z (yaw 0) the listener's right is -X; sin(azimuth) is the lateral share.
            check(Math.abs(distance - 2) < 1e-6 && Math.abs(-dx / distance - 0.3) < 1e-6 && dz > 0,
                    "A 30% pan is placed at the angle whose sine is 0.3, in front of the listener");
            check(Math.abs(left.getX() - 2) < 1e-6 && Math.abs(left.getZ()) < 1e-6, "A full left pan sits directly to the left");
            check(centre.getX() == 0 && centre.getY() == 1.62 && centre.getZ() == 0, "A centred note stays at the ear");
            float target = 0.5f;
            check(Math.abs(alice.gains.get(0) - target / (1 - 2 / 16.0)) < 1e-4 && Math.abs(alice.gains.get(2) - target) < 1e-6,
                    "Panned notes are compensated for their distance; centred notes are not");

            alice.yaw = 90; call(session, "refreshListeners"); alice.soundAt.clear();
            call(session, "emitNote", 3, 1f, field(session, "listeners"));
            var turned = alice.soundAt.get(0);
            check(Math.abs(turned.getZ() + 2) < 1e-6 && Math.abs(turned.getX()) < 1e-6,
                    "Panning follows the view: facing -X, the right is -Z");
            alice.yaw = 0;

            // Moving at about 10 blocks/second with 100 ms ping.
            alice.ping = 100; call(session, "refreshListeners");
            Thread.sleep(50); alice.x += 0.5; call(session, "refreshListeners");
            alice.soundAt.clear(); call(session, "emitNote", 2, 1f, field(session, "listeners"));
            double led = alice.soundAt.get(0).getX() - alice.x;
            check(led > 0.5 && led <= 4.0 + 1e-9 && alice.soundAt.get(0).getY() == 1.62,
                    "A moving listener's note is placed ahead along their motion (led " + led + " blocks)");
            alice.x += 50; call(session, "refreshListeners");
            alice.soundAt.clear(); call(session, "emitNote", 2, 1f, field(session, "listeners"));
            check(alice.soundAt.get(0).getX() == alice.x, "A teleport is not extrapolated as movement");

            config.set("playback.follow-player", true); playback.loadHotSettings();
            call(session, "refreshListeners"); alice.soundAt.clear();
            call(session, "emitNote", 0, 1f, field(session, "listeners"));
            check(alice.soundAt.isEmpty(), "follow-player keeps notes attached to the listener");
        } finally {
            playback.stop(alice.getUniqueId());
            alice.x = 0; alice.y = 1.62; alice.z = 0; alice.yaw = 0; alice.ping = 0;
            config.set("playback.follow-player", true); config.set("playback.voice-allocation", true); playback.loadHotSettings();
        }
    }

    static Object callQuiet(Object pack) { try { return field(pack, "rebuildPending"); } catch (Exception e) { throw new RuntimeException(e); } }
    /** A sample whose packets decode far past its declared length is rejected. */
    static void decodedLengthBomb() throws Exception {
        for (String name : List.of("tone.ogg", "vorbis-8000-1.ogg", "vorbis-48000-2.ogg", "vorbis-96000-2.ogg")) {
            byte[] real = Files.readAllBytes(Path.of(System.getProperty("nbw.test.fixture")).resolveSibling(name));
            List<byte[]> packets = oggPackets(real);
            long granule = finalGranule(real);
            byte[] same = oggStream(packets, 1, granule), bomb = oggStream(packets, 40, granule);
            check(OggTools.readDurationSeconds(same) > 0,
                    "A rewritten copy of " + name + " with the same packets is still accepted");
            check(OggTools.readDurationSeconds(bomb) < 0,
                    "Repeating " + name + "'s audio packets 40x under the same declared length is rejected");
        }
    }
    static long finalGranule(byte[] ogg) {
        long granule = 0;
        for (int at = 0; at + 27 <= ogg.length; ) {
            long g = 0; for (int i = 7; i >= 0; i--) g = g << 8 | (ogg[at + 6 + i] & 0xff);
            if (g > 0) granule = g;
            int segments = ogg[at + 26] & 0xff, body = 0;
            for (int i = 0; i < segments; i++) body += ogg[at + 27 + i] & 0xff;
            at += 27 + segments + body;
        }
        return granule;
    }
    static List<byte[]> oggPackets(byte[] ogg) {
        List<byte[]> packets = new ArrayList<>(); var current = new java.io.ByteArrayOutputStream();
        for (int at = 0; at + 27 <= ogg.length; ) {
            int segments = ogg[at + 26] & 0xff, body = at + 27 + segments;
            for (int i = 0; i < segments; i++) {
                int lace = ogg[at + 27 + i] & 0xff;
                current.write(ogg, body, lace); body += lace;
                if (lace < 255) { packets.add(current.toByteArray()); current.reset(); }
            }
            at = body;
        }
        return packets;
    }
    /** One packet per page; headers once, audio packets repeated; only the last page carries the granule. */
    static byte[] oggStream(List<byte[]> packets, int copies, long granule) throws Exception {
        List<byte[]> all = new ArrayList<>(packets.subList(0, 3));
        for (int c = 0; c < copies; c++) all.addAll(packets.subList(3, packets.size()));
        var out = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < all.size(); i++) {
            byte[] packet = all.get(i); int laces = packet.length / 255 + 1;
            byte[] page = new byte[27 + laces + packet.length];
            System.arraycopy("OggS".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, page, 0, 4);
            page[5] = (byte) ((i == 0 ? 2 : 0) | (i == all.size() - 1 ? 4 : 0));
            long g = i == all.size() - 1 ? granule : 0;
            for (int b = 0; b < 8; b++) page[6 + b] = (byte) (g >>> (8 * b));
            page[14] = 7;                                   // serial
            for (int b = 0; b < 4; b++) page[18 + b] = (byte) (i >>> (8 * b));
            page[26] = (byte) laces;
            for (int l = 0; l < laces; l++) page[27 + l] = (byte) (l < laces - 1 ? 255 : packet.length % 255);
            System.arraycopy(packet, 0, page, 27 + laces, packet.length);
            staticCall(OggTools.class, "fixPageCrc", page, 0);
            out.write(page);
        }
        return out.toByteArray();
    }

    /** Rotating addresses inside one IPv6 /64 shares one rate-limit bucket. */
    static void ipv6RateLimit(NoteblockWorldMCPlugin plugin) throws Exception {
        var pack = plugin.getResourcePackService();
        setField(pack, "rateLimitPerMinute", 3);
        var limited = ResourcePackService.class.getDeclaredMethod("isRateLimited", String.class);
        limited.setAccessible(true);
        boolean fourth = false;
        for (int i = 1; i <= 4; i++) fourth = (boolean) limited.invoke(pack, "2001:db8:aa:bb:0:0:0:" + Integer.toHexString(i));
        check(fourth, "A fourth address in the same IPv6 /64 is limited at 3 requests per minute");
        check(!(boolean) limited.invoke(pack, "2001:db8:aa:bc:0:0:0:1"), "A neighbouring /64 has its own bucket");
        boolean ipv4 = false;
        for (int i = 1; i <= 4; i++) ipv4 |= (boolean) limited.invoke(pack, "203.0.113." + i);
        check(!ipv4, "IPv4 addresses keep separate buckets");
        check(staticCall(ResourcePackService.class, "rateLimitKey", "2001:db8::1%eth0")
                        .equals(staticCall(ResourcePackService.class, "rateLimitKey", "2001:0db8:0:0:ffff::2")),
                "Zone suffixes and zero-compression map to the same /64 key");
        ((Map<?, ?>) field(pack, "requestCounts")).clear();
        setField(pack, "rateLimitPerMinute", 30);
    }

    /** Completions reveal no titles, players or setting values to someone who lacks the subcommand. */
    static void permissionAwareCompletion(NoteblockWorldMCPlugin plugin, Player bob) throws Exception {
        var command = plugin.commands.get("nbw");
        var source = CommandSourceStack.of(bob);
        check(!command.suggest(source, new String[]{"config", "precision-ms", ""}).isEmpty()
                        && command.suggest(source, new String[]{"play", ""}).contains("@a"),
                "With permissions, settings and targets still complete");
        bob.denied.addAll(List.of("noteblockworldmc.play", "noteblockworldmc.config", "noteblockworldmc.reload",
                "noteblockworldmc.broadcast", "noteblockworldmc.remove", "noteblockworldmc.importurl", "noteblockworldmc.download"));
        try {
            var root = command.suggest(source, new String[]{""});
            check(!root.contains("config") && !root.contains("play") && !root.contains("reload")
                    && root.contains("help") && root.contains("stop"), "The subcommand list only offers what the sender can run");
            check(command.suggest(source, new String[]{"config", "precision-ms", ""}).isEmpty(),
                    "Current setting values are not suggested without the config permission");
            check(command.suggest(source, new String[]{"play", ""}).isEmpty()
                            && command.suggest(source, new String[]{"remove", ""}).isEmpty()
                            && command.suggest(source, new String[]{"analyze", ""}).isEmpty(),
                    "Library titles are not suggested without the matching permission");
            bob.denied.remove("noteblockworldmc.play");
            check(!command.suggest(source, new String[]{"play", ""}).contains("@a"),
                    "Play targets need the broadcast permission");
        } finally { bob.denied.clear(); }
    }

    /** A description of thousands of lines costs one bounded chat message per right-click. */
    static void boundedDetails(NoteblockWorldMCPlugin plugin, Player alice) throws Exception {
        var song = io.github.noteblockworldmc.api.model.SongPreview.local("LongDesc1", "T", "A", 1, 1, 1, "");
        setField(song, "description", "\n".repeat(2000) + "line\n".repeat(1000));
        var gui = new SongBrowserGui(plugin, alice, io.github.noteblockworldmc.api.NoteBlockWorldClient.SongQuery.builder().build());
        setField(gui, "currentPage", io.github.noteblockworldmc.api.model.SongPage.of(List.of(song), 1, 36, 1));
        var render = SongBrowserGui.class.getDeclaredMethod("render"); render.setAccessible(true); render.invoke(gui);
        int before = alice.messages.size();
        gui.handleClick(0, org.bukkit.event.inventory.ClickType.RIGHT); drain(plugin);
        var sent = alice.messages.subList(before, alice.messages.size());
        check(sent.size() <= 3, "Right-click details send a bounded number of messages (" + sent.size() + ")");
        String last = sent.isEmpty() ? "" : sent.get(sent.size() - 1).value;
        long lines = last.lines().count();
        check(lines <= 31 && last.contains("…"), "The description is capped at 30 lines plus an ellipsis (" + lines + ")");
    }

    /** Online players' addresses and a local proxy are trusted; the proxy is not rate limited as one client. */
    static void trustedSources(NoteblockWorldMCPlugin plugin, Player alice) throws Exception {
        var pack = plugin.getResourcePackService();
        var trusted = ResourcePackService.class.getDeclaredMethod("isTrustedSource", String.class); trusted.setAccessible(true);
        var limited = ResourcePackService.class.getDeclaredMethod("isRateLimited", String.class); limited.setAccessible(true);
        alice.address = new java.net.InetSocketAddress(java.net.InetAddress.getByName("2001:db8:5:6::10"), 50000);
        pack.onJoin(new org.bukkit.event.player.PlayerJoinEvent(alice));
        check((boolean) trusted.invoke(pack, "2001:db8:5:6::99"), "A joined player's /64 is a trusted download source");
        check(!(boolean) trusted.invoke(pack, "198.51.100.9"), "Other addresses are not trusted");
        check((boolean) trusted.invoke(pack, "127.0.0.1") && (boolean) trusted.invoke(pack, "::1"), "A local reverse proxy is trusted");
        check((boolean) trusted.invoke(pack, "172.18.0.1") && (boolean) trusted.invoke(pack, "192.168.1.20")
                        && (boolean) trusted.invoke(pack, "fd12:3456::1") && (boolean) trusted.invoke(pack, "fe80::1%eth0"),
                "Private and link-local sources (container forwarders, LANs) are trusted");
        check(!(boolean) trusted.invoke(pack, "100.64.0.1") && !(boolean) trusted.invoke(pack, "2001:db8::1"),
                "Carrier-grade NAT and global addresses are not");
        pack.onQuit(new PlayerQuitEvent(alice));
        check(!(boolean) trusted.invoke(pack, "2001:db8:5:6::10"), "Trust ends when the player quits");
        setField(pack, "rateLimitPerMinute", 3);
        boolean any = false;
        for (int i = 0; i < 10; i++) any |= (boolean) limited.invoke(pack, "127.0.0.1") | (boolean) limited.invoke(pack, "172.18.0.1");
        check(!any, "Downloads relayed by a local reverse proxy are not rate limited as one client");
        setField(pack, "rateLimitPerMinute", 30);
        ((Map<?, ?>) field(pack, "requestCounts")).clear();
        alice.address = null;
    }

    /** Strangers get a bounded share of the transfer pool; trusted sources get all of it. */
    static void strangerShare() throws Exception {
        Path file = Files.createTempFile("nbw-share", ".zip");
        Files.write(file, new byte[4 * 1024 * 1024]);
        Class<?> type = Class.forName("io.github.noteblockworldmc.pack.PackHttpServer");
        var make = type.getDeclaredConstructor(java.net.InetSocketAddress.class, java.util.function.Function.class,
                java.util.function.Predicate.class, java.util.function.Predicate.class, java.util.function.IntSupplier.class, long.class);
        make.setAccessible(true);
        var address = type.getDeclaredMethod("address"); address.setAccessible(true);
        var close = type.getDeclaredMethod("close"); close.setAccessible(true);
        java.util.function.Function<String, java.nio.channels.FileChannel> lookup = target -> {
            try { return java.nio.channels.FileChannel.open(file); } catch (java.io.IOException e) { return null; }
        };
        try {
            for (String transport : List.of("nio", "jdk")) {
                System.setProperty("noteblockworldmc.pack-http-transport", transport);
                for (boolean trusted : List.of(false, true)) {
                    java.util.function.Predicate<String> rate = ip -> false, trust = ip -> trusted;
                    java.util.function.IntSupplier limit = () -> 8;
                    Object server = make.newInstance(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0),
                            lookup, rate, trust, limit, 20_000L);
                    int port = ((java.net.InetSocketAddress) address.invoke(server)).getPort();
                    List<java.net.Socket> slow = new ArrayList<>();
                    try {
                        for (int i = 0; i < 2; i++) {
                            var socket = new java.net.Socket(); socket.setReceiveBufferSize(1024);
                            socket.connect(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), port));
                            socket.getOutputStream().write("GET /pack.zip HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                            slow.add(socket);
                        }
                        Thread.sleep(300);
                        String third;
                        try (var socket = new java.net.Socket(java.net.InetAddress.getLoopbackAddress(), port)) {
                            socket.setSoTimeout(5000);
                            socket.getOutputStream().write("HEAD /pack.zip HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                            byte[] head = socket.getInputStream().readNBytes(12);
                            third = new String(head, StandardCharsets.US_ASCII);
                        }
                        check(third.endsWith(trusted ? "200" : "503"), transport + ": a third concurrent download from one "
                                + (trusted ? "trusted source is served" : "untrusted source is refused") + " (" + third + ")");
                        for (var socket : slow) socket.close();
                        String after = "";
                        for (int attempt = 0; attempt < 20 && !after.endsWith("200"); attempt++) {
                            Thread.sleep(100);
                            try (var socket = new java.net.Socket(java.net.InetAddress.getLoopbackAddress(), port)) {
                                socket.setSoTimeout(5000);
                                socket.getOutputStream().write("HEAD /pack.zip HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                                after = new String(socket.getInputStream().readNBytes(12), StandardCharsets.US_ASCII);
                            }
                        }
                        check(after.endsWith("200"), transport + ": closed downloads give their slots back (" + after + ")");
                    } finally {
                        for (var socket : slow) socket.close();
                        close.invoke(server);
                    }
                }
            }
        } finally { System.clearProperty("noteblockworldmc.pack-http-transport"); Files.deleteIfExists(file); }
        check((int) staticCall(Class.forName("io.github.noteblockworldmc.pack.PackHttpServer"), "untrustedLimit", 16) == 8
                && (int) staticCall(Class.forName("io.github.noteblockworldmc.pack.PackHttpServer"), "perSourceLimit", 16) == 4
                && (int) staticCall(Class.forName("io.github.noteblockworldmc.pack.PackHttpServer"), "perSourceLimit", 1) == 1,
                "Strangers share half the pool, four slots per source at the default 16, and never more than exists");
    }

    /** Dialog callbacks expire in minutes, not Adventure's 12-hour default. */
    static void dialogCallbackLifetime() throws Exception {
        var options = (net.kyori.adventure.text.event.ClickCallback.Options) staticField(SongBrowserGui.class, "DIALOG_CALLBACK");
        var lifetime = java.time.Duration.from(options.lifetime);
        check(options.uses == 1 && lifetime.compareTo(java.time.Duration.ofMinutes(10)) <= 0,
                "Search dialog callbacks are single-use and expire within 10 minutes (" + lifetime + ")");
    }
    static Object staticField(Class<?> type, String name) throws Exception {
        var f = type.getDeclaredField(name); f.setAccessible(true); return f.get(null);
    }

    /** A /nbw config edit that cannot be applied leaves config.yml as it was. */
    static void configRollback(NoteblockWorldMCPlugin plugin, Player alice) throws Exception {
        Path file = plugin.getDataFolder().toPath().resolve("config.yml");
        String original = Files.readString(Path.of(System.getProperty("nbw.test.fixture")).getParent().getParent().getParent()
                .resolve("src/main/resources/config.yml"));
        // Something else in the file was hand-edited to an invalid value.
        Files.writeString(file, original.replaceFirst("(?m)^  port: \\d+", "  port: 0"));
        // The test YAML double reads nothing from disk; mirror the hand edit in what it loads.
        org.bukkit.configuration.file.YamlConfiguration.onLoad = config -> config.set("resource-pack.port", 0);
        try {
            int before = alice.messages.size();
            plugin.commands.get("nbw").execute(CommandSourceStack.of(alice), new String[]{"config", "precision-ms", "9"});
            String reply = alice.messages.size() > before ? alice.messages.get(alice.messages.size() - 1).value : "";
            check(reply.startsWith("config.apply-failed"), "The edit reports that it could not be applied (" + reply + ")");
            check(Files.readString(file).contains("\n  precision-ms: 2\n"), "config.yml keeps the previous value after a failed apply");
        } finally {
            org.bukkit.configuration.file.YamlConfiguration.onLoad = config -> {};
            Files.deleteIfExists(file);
        }
    }

}
