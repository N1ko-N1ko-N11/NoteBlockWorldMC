package io.github.noteblockworldmc;

import io.github.noteblockworldmc.api.NoteBlockWorldClient;
import io.github.noteblockworldmc.command.SongsCommand;
import io.github.noteblockworldmc.download.SongDownloadManager;
import io.github.noteblockworldmc.gui.GuiListener;
import io.github.noteblockworldmc.pack.ResourcePackService;
import io.github.noteblockworldmc.playback.PlaybackManager;
import io.github.noteblockworldmc.playback.VolumeManager;
import io.github.noteblockworldmc.util.Messages;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;

public final class NoteblockWorldMCPlugin extends JavaPlugin {

    private final io.github.noteblockworldmc.util.WorkPool workPool = new io.github.noteblockworldmc.util.WorkPool();
    public io.github.noteblockworldmc.util.WorkPool getWorkPool() { return workPool; }
    private final io.github.noteblockworldmc.util.RequestGate browserGate = new io.github.noteblockworldmc.util.RequestGate();
    public io.github.noteblockworldmc.util.RequestGate getBrowserGate() { return browserGate; }
    private volatile boolean stopping;
    public boolean isStopping() { return stopping; }

    /** Async continuations are discarded after disable, including the scheduling race. */
    public void runMain(Runnable action) {
        if (stopping || !isEnabled()) return;
        try { getServer().getScheduler().runTask(this, () -> { if (!stopping) action.run(); }); }
        catch (RuntimeException e) { if (!stopping && isEnabled()) throw e; }
    }

    private Messages messages;
    private volatile NoteBlockWorldClient apiClient;
    private ApiSettings apiSettings;

    private record ApiSettings(String baseUrl, Duration connectTimeout, Duration requestTimeout) {}
    private SongDownloadManager downloadManager;
    private PlaybackManager playbackManager;
    private VolumeManager volumeManager;
    private ResourcePackService resourcePackService;
    private java.util.concurrent.CompletableFuture<Void> reloadTask = java.util.concurrent.CompletableFuture.completedFuture(null);

    @Override
    public void onEnable() {
        saveDefaultConfig();
        runtimeConfig = readSettings();
        io.github.noteblockworldmc.util.SettingsValidator.validate(runtimeConfig);
        this.messages = new Messages(this);

        reloadApiClient();
        this.downloadManager = new SongDownloadManager(this);
        this.volumeManager = new VolumeManager(this);
        this.playbackManager = new PlaybackManager(this);

        // Paper 26.3 marks the plugin.yml/CommandExecutor path obsolete; the
        // command is registered through the Brigadier lifecycle instead.
        registerCommand(SongsCommand.LABEL, SongsCommand.DESCRIPTION, java.util.List.of(), new SongsCommand(this));

        getServer().getPluginManager().registerEvents(new GuiListener(), this);
        getServer().getPluginManager().registerEvents(playbackManager, this);

        this.resourcePackService = new ResourcePackService(this);
        getServer().getPluginManager().registerEvents(resourcePackService, this);
        resourcePackService.start();

        getLogger().info("NoteblockWorldMC enabled. API base: " + apiSettings.baseUrl());
    }

    private volatile org.bukkit.configuration.file.FileConfiguration runtimeConfig;
    @Override @org.jspecify.annotations.NullMarked public org.bukkit.configuration.file.FileConfiguration getConfig() {
        var current = runtimeConfig; return current == null ? super.getConfig() : current;
    }
    private org.bukkit.configuration.file.FileConfiguration readSettings() {
        var config = new org.bukkit.configuration.file.YamlConfiguration();
        try {
            config.load(new java.io.File(getDataFolder(), "config.yml"));
            try (var embedded = getResource("config.yml")) {
                if (embedded != null) config.setDefaults(org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                        new java.io.InputStreamReader(embedded, java.nio.charset.StandardCharsets.UTF_8)));
            }
            return config;
        } catch (Exception e) { throw new IllegalArgumentException("Could not read config.yml; previous settings retained", e); }
    }
    public java.util.concurrent.CompletableFuture<Void> reloadSettings(boolean reloadMessages) {
        return applySettings(readSettings(), reloadMessages);
    }

    /** Validate configuration, messages and the replacement socket before committing runtime state. */
    public java.util.concurrent.CompletableFuture<Void> applySettings(org.bukkit.configuration.file.FileConfiguration next, boolean reloadMessages) {
        if (stopping) throw new IllegalStateException("Plugin is stopping");
        if (reloadMessages && !reloadTask.isDone()) throw new IllegalStateException("A reload is already in progress");
        io.github.noteblockworldmc.util.SettingsValidator.validate(next);
        var nextMessages = reloadMessages ? messages.prepareReload() : null;
        ApiSettings desired = apiSettings(next);
        NoteBlockWorldClient replacement = desired.equals(apiSettings) ? null : new NoteBlockWorldClient(desired.baseUrl(),
                getPluginMeta().getVersion(), desired.connectTimeout(), desired.requestTimeout());
        Runnable commitPack;
        try { commitPack = resourcePackService.prepareSettings(next); }
        catch (RuntimeException e) { if (replacement != null) replacement.close(); throw e; }
        runtimeConfig = next;
        NoteBlockWorldClient previous = apiClient;
        if (replacement != null) { apiClient = replacement; apiSettings = desired; }
        downloadManager.loadHotSettings(); playbackManager.loadHotSettings();
        if (nextMessages != null) messages.install(nextMessages);
        commitPack.run();
        if (replacement != null) { previous.shutdown(); replacement.refreshCategories(); }
        // Editing one option does not require a library-wide scan.
        if (!reloadMessages) return java.util.concurrent.CompletableFuture.completedFuture(null);
        var completed = new java.util.concurrent.CompletableFuture<Void>();
        reloadTask = completed;
        workPool.submit(downloadManager::reconcileLibrary).whenComplete((library, failure) -> {
            if (stopping) { completed.cancel(false); return; }
            try {
                runMain(() -> {
                    if (failure != null) { completed.completeExceptionally(failure); return; }
                    if (!library.successful()) {
                        completed.completeExceptionally(new IllegalStateException("Settings applied, but the local song library could not be rechecked; see the console"));
                        return;
                    }
                    try {
                        // Only the reconciled library may drive the explicit reload's pack push.
                        resourcePackService.reloadContent();
                        if (replacement == null) apiClient.refreshCategories();
                        completed.complete(null);
                    } catch (RuntimeException e) { completed.completeExceptionally(e); }
                });
            } catch (RuntimeException e) { completed.completeExceptionally(e); }
        });
        return completed;
    }
    private ApiSettings apiSettings(org.bukkit.configuration.file.FileConfiguration config) {
        return new ApiSettings(config.getString("api-base-url", "https://api.noteblock.world/v1").trim(),
                Duration.ofSeconds(config.getInt("http.connect-timeout", 10)),
                Duration.ofSeconds(config.getInt("http.request-timeout", 15)));
    }

    /** Publishes a replacement before gracefully retiring the old HTTP client. */
    private void reloadApiClient() {
        ApiSettings next = apiSettings(getConfig());
        if (next.equals(apiSettings)) return;
        NoteBlockWorldClient replacement = new NoteBlockWorldClient(next.baseUrl(),
                getPluginMeta().getVersion(), next.connectTimeout(), next.requestTimeout());
        NoteBlockWorldClient previous = apiClient;
        apiClient = replacement;
        apiSettings = next;
        replacement.refreshCategories();
        // Retirement cancels outstanding requests without blocking the server thread.
        if (previous != null) previous.shutdown();
    }

    @Override
    public void onDisable() {
        stopping = true;
        reloadTask.cancel(false);
        // Close our menus while their listeners and holder classes are still live.
        for (var player : getServer().getOnlinePlayers()) {
            try {
                if (player.getOpenInventory().getTopInventory().getHolder()
                        instanceof io.github.noteblockworldmc.gui.SongBrowserGui) player.closeInventory();
            } catch (RuntimeException e) { getLogger().warning("Could not close song browser for " + player.getName() + ": " + e); }
        }
        if (downloadManager != null) downloadManager.stop();
        workPool.close();
        browserGate.close();
        if (playbackManager != null) {
            playbackManager.stopAll();
        }
        if (resourcePackService != null) {
            resourcePackService.stop();
        }
        if (apiClient != null) {
            apiClient.close();
        }
        if (volumeManager != null) {
            // setVolume saves via async tasks, which Bukkit cancels when a
            // plugin disables — flush synchronously so no change is lost.
            volumeManager.flush();
        }
    }

    public Messages getMessages() {
        return messages;
    }

    public NoteBlockWorldClient getApiClient() {
        return apiClient;
    }

    public SongDownloadManager getDownloadManager() {
        return downloadManager;
    }

    public PlaybackManager getPlaybackManager() {
        return playbackManager;
    }

    public VolumeManager getVolumeManager() {
        return volumeManager;
    }

    public ResourcePackService getResourcePackService() {
        return resourcePackService;
    }
}
