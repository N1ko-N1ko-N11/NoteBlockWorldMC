package dev.example.noteblockworldmc;

import dev.example.noteblockworldmc.api.NoteBlockWorldClient;
import dev.example.noteblockworldmc.command.SongsCommand;
import dev.example.noteblockworldmc.download.SongDownloadManager;
import dev.example.noteblockworldmc.gui.GuiListener;
import dev.example.noteblockworldmc.pack.ResourcePackService;
import dev.example.noteblockworldmc.playback.PlaybackManager;
import dev.example.noteblockworldmc.playback.VolumeManager;
import dev.example.noteblockworldmc.util.Messages;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;

public final class NoteblockWorldMCPlugin extends JavaPlugin {

    private Messages messages;
    private NoteBlockWorldClient apiClient;
    private SongDownloadManager downloadManager;
    private PlaybackManager playbackManager;
    private VolumeManager volumeManager;
    private ResourcePackService resourcePackService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.messages = new Messages(this);

        String baseUrl = getConfig().getString("api-base-url", "https://api.noteblock.world/v1");
        // Clamped: a zero or negative timeout from a hand-edited config would
        // make HttpClient's builder throw and kill the whole enable with a
        // stack trace that doesn't mention the config at all.
        Duration connectTimeout = Duration.ofSeconds(
                Math.max(1, getConfig().getInt("http.connect-timeout", 10)));
        Duration requestTimeout = Duration.ofSeconds(
                Math.max(1, getConfig().getInt("http.request-timeout", 15)));

        this.apiClient = new NoteBlockWorldClient(baseUrl,
                getPluginMeta().getVersion(), connectTimeout, requestTimeout);

        // Warm the category cache once, off the enable thread. Categories

        // barely change, so this is the only time they are fetched until

        // someone runs /nbw reload -- rather than once per GUI open.

        apiClient.refreshCategories();
        this.downloadManager = new SongDownloadManager(this);
        this.volumeManager = new VolumeManager(this);
        this.playbackManager = new PlaybackManager(this);

        PluginCommand nbwCommand = getCommand("nbw");
        if (nbwCommand != null) {
            SongsCommand executor = new SongsCommand(this);
            nbwCommand.setExecutor(executor);
            nbwCommand.setTabCompleter(executor);
        } else {
            // Only possible if plugin.yml is damaged; without this log the
            // plugin would enable "successfully" with a dead command.
            getLogger().severe("Command 'nbw' is missing from plugin.yml; commands disabled.");
        }

        getServer().getPluginManager().registerEvents(new GuiListener(), this);
        getServer().getPluginManager().registerEvents(playbackManager, this);

        this.resourcePackService = new ResourcePackService(this);
        getServer().getPluginManager().registerEvents(resourcePackService, this);
        resourcePackService.start();

        getLogger().info("NoteblockWorldMC enabled. API base: " + baseUrl);
    }

    @Override
    public void onDisable() {
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
