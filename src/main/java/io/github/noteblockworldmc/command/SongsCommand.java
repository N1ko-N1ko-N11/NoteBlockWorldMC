package io.github.noteblockworldmc.command;

import io.github.noteblockworldmc.NoteblockWorldMCPlugin;
import io.github.noteblockworldmc.download.SongDownloadManager;
import io.github.noteblockworldmc.util.ConfigEditor;
import io.github.noteblockworldmc.util.Throwables;
import io.github.noteblockworldmc.api.NoteBlockWorldClient.SongQuery;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The /nbw command tree: browsing (bare /nbw, search, categories), library
 * management (download, remove, import, importurl), playback (play, stop,
 * playing, volume), and diagnostics (analyze, packinfo, config, reload,
 * help). play and stop take an optional leading target ("@a" or a player
 * name).
 *
 * Registered through Paper's Brigadier {@link BasicCommand} API: the Bukkit
 * CommandExecutor/TabCompleter/plugin.yml command path is obsolete as of
 * Paper 26.3.
 *
 * Search flags: --sort <recent|popular|random|title|duration|count>
 *               --order <asc|desc>  --category <name>  --downloaded
 */
public class SongsCommand implements BasicCommand {

    /** Root literal registered with Paper's Brigadier command API. */
    public static final String LABEL = "nbw";
    public static final String DESCRIPTION = "Browse, download, and play songs from noteblock.world";

    private void runBackground(org.bukkit.command.CommandSender sender, Runnable work) {
        plugin.getWorkPool().run(sender, work).whenComplete((value, error) -> {
            if (error != null && !plugin.isStopping()) plugin.runMain(() ->
                    sender.sendMessage(net.kyori.adventure.text.Component.text("Music request failed: "
                            + io.github.noteblockworldmc.util.Throwables.describe(error))));
        });
    }

    private final NoteblockWorldMCPlugin plugin;

    public SongsCommand(NoteblockWorldMCPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        handle(source.getSender(), LABEL, args);
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        // Brigadier passes no arguments at all for "/nbw " before anything is
        // typed; the completer below expects the token being typed to exist.
        return complete(source.getSender(), args.length == 0 ? new String[]{""} : args);
    }

    /**
     * Runs one /nbw invocation for {@code sender}. {@code label} is only
     * used when echoing usage text and clickable commands back.
     */
    public void handle(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            // Bare /nbw: open the browser GUI (players); console gets usage
            if (sender instanceof org.bukkit.entity.Player player) {
                if (!player.hasPermission("noteblockworldmc.browse")) {
                    // Bare /nbw is the obvious thing to type, so answering with
                    // only a refusal leaves a volume-only player with no idea
                    // what they CAN run. sendUsage is permission-aware and
                    // already falls back to permission.none for someone who
                    // holds nothing at all.
                    sendUsage(player, label);
                    return;
                }
                new io.github.noteblockworldmc.gui.SongBrowserGui(
                        plugin, player, SongQuery.builder().build()).open();
            } else {
                sendUsage(sender, label);
            }
            return;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        String[] rest = Arrays.copyOfRange(args, 1, args.length);

        switch (sub) {
            case "search" -> {
                if (!sender.hasPermission("noteblockworldmc.browse")) {
                    sender.sendMessage(msg("permission.browse-catalog"));
                    return;
                }
                SongQuery query = QueryParser.parse(plugin, sender, rest);
                if (query == null) return;
                boolean hasQuery = query.getQ() != null && !query.getQ().isBlank();
                boolean hasFilters = query.getSort() != null || query.getAscending() != null
                        || query.getCategory() != null || query.isDownloadedOnly();
                // A filter-only search (e.g. "--category pop" with no text) is
                // valid. With nothing to search on at all, hand the player the
                // dialog rather than a usage line telling them what to type;
                // the console cannot show a dialog, so it still gets the text.
                if (!hasQuery && !hasFilters) {
                    if (sender instanceof org.bukkit.entity.Player dialogPlayer) {
                        io.github.noteblockworldmc.gui.SongBrowserGui
                                .openSearchDialog(plugin, dialogPlayer);
                    } else {
                        sender.sendMessage(msg("usage.search",
                                Placeholder.unparsed("label", label)));
                    }
                    return;
                }
                if (!(sender instanceof org.bukkit.entity.Player player)) {
                    sender.sendMessage(msg("general.players-only-gui"));
                    return;
                }
                new io.github.noteblockworldmc.gui.SongBrowserGui(plugin, player, query).open();
            }
            case "download" -> {
                if (rest.length != 1) {
                    sender.sendMessage(msg("usage.download", Placeholder.unparsed("label", label)));
                    return;
                }
                if (!sender.hasPermission("noteblockworldmc.download")) {
                    sender.sendMessage(msg("permission.download"));
                    return;
                }
                // A pasted noteblock.world link works as well as a bare ID.
                String id = SongDownloadManager.extractSongId(rest[0]);
                var downloads = plugin.getDownloadManager();
                if (downloads.isDownloaded(id)) {
                    sender.sendMessage(msg("download.already-downloaded"));
                    return;
                }
                sender.sendMessage(msg("download.downloading", Placeholder.unparsed("song", id)));
                downloads.download(id, null).whenComplete((path, throwable) ->
                        plugin.runMain(() -> {
                            if (throwable != null) {
                                sender.sendMessage(msg("download.failed",
                                        Placeholder.unparsed("reason", Throwables.describe(throwable))));
                                return;
                            }
                            // Players see the file name; the server's directory layout is for the console.
                            String shown = sender instanceof org.bukkit.entity.Player && path != null
                                    ? String.valueOf(path.getFileName()) : String.valueOf(path);
                            sender.sendMessage(msg("download.downloaded-to", Placeholder.unparsed("path", shown)));
                        }));
            }
            case "play" -> {
                if (!(sender instanceof org.bukkit.entity.Player player)) {
                    sender.sendMessage(msg("general.players-only-play"));
                    return;
                }
                if (!player.hasPermission("noteblockworldmc.play")) {
                    player.sendMessage(msg("permission.play"));
                    return;
                }
                if (rest.length == 0) {
                    player.sendMessage(msg("usage.play", Placeholder.unparsed("label", label)));
                    return;
                }

                // Optional leading target: "@a" for everyone, or a player name.
                // Broadcasting needs the broadcast permission; targeting another
                // player is an admin action and needs it too. Playing for
                // yourself (no target, or your own name) needs only play.
                PlayTarget target = parsePlayTarget(player, rest);
                if (target.invalid()) {
                    player.sendMessage(msg("playback.target-not-found",
                            Placeholder.unparsed("name", target.rawName())));
                    return;
                }
                if (target.needsBroadcastPermission()
                        && !player.hasPermission("noteblockworldmc.broadcast")) {
                    player.sendMessage(msg("permission.broadcast"));
                    return;
                }
                String[] songArgs = target.songArgs();
                // Resolve a pasted link to its ID before anything else looks at
                // it, so the local-library lookup and the download fallback
                // both see the same clean value.
                if (songArgs.length == 1) {
                    songArgs = new String[]{SongDownloadManager.extractSongId(songArgs[0])};
                }
                if (songArgs.length == 0) {
                    player.sendMessage(msg("usage.play", Placeholder.unparsed("label", label)));
                    return;
                }

                var downloads = plugin.getDownloadManager();
                String joined = String.join(" ", songArgs);

                // A name (or partial name) from the local library wins; a bare
                // catalog ID still works and is downloaded on demand below.
                List<String> local = downloads.resolve(joined);
                if (local.size() == 1) {
                    playResolved(player, target, local.get(0));
                    return;
                }
                if (local.size() > 1) {
                    resolveSong(player, songArgs);   // reports the ambiguity
                    return;
                }
                // Nothing local matched. Only a SINGLE token can plausibly be
                // a catalog ID worth downloading; a multi-word input was a
                // name lookup that failed, and trying to download "My" (the
                // first word) would report a misleading "download failed".
                if (songArgs.length > 1) {
                    player.sendMessage(msg("song.not-found",
                            Placeholder.unparsed("query", joined)));
                    return;
                }
                String id = songArgs[0];
                if (!player.hasPermission("noteblockworldmc.download")) {
                    player.sendMessage(msg("permission.download-needed"));
                    return;
                }
                player.sendMessage(msg("download.downloading", Placeholder.unparsed("song", id)));
                var playRequest = plugin.getPlaybackManager().beginPlay(player,
                        target.single() == null ? player : target.single(), target.everyone());
                downloads.download(id, null).whenComplete((path, throwable) ->
                        plugin.runMain(() -> {
                            if (throwable != null) {
                                plugin.getPlaybackManager().abandon(playRequest);
                                if (player.isOnline()) player.sendMessage(msg("download.failed",
                                        Placeholder.unparsed("reason", Throwables.describe(throwable))));
                                return;
                            }
                            if (!player.isOnline()) return;
                            if (plugin.getPlaybackManager().isCurrent(playRequest))
                                playResolved(player, target, id, playRequest);
                        }));
            }
            case "stop" -> {
                if (!(sender instanceof org.bukkit.entity.Player player)) {
                    sender.sendMessage(msg("general.players-only-stop"));
                    return;
                }

                // Bare /nbw stop stops the caller's own song. A target ("@a" or
                // a player name) stops someone else's, or everyone's, and is an
                // admin action gated on broadcast.
                if (rest.length == 0) {
                    if (plugin.getPlaybackManager().stopFading(player.getUniqueId())) {
                        player.sendMessage(msg("playback.stopped"));
                    } else {
                        player.sendMessage(msg("playback.nothing-playing"));
                    }
                    return;
                }

                if (!player.hasPermission("noteblockworldmc.broadcast")) {
                    // Not permission.broadcast: that message is about PLAYING
                    // to everyone, which reads wrong when refusing a stop.
                    player.sendMessage(msg("permission.stop-others"));
                    return;
                }
                String targetName = rest[0];
                if (targetName.equalsIgnoreCase("@a")) {
                    boolean any = plugin.getPlaybackManager().stopEveryoneFading();
                    sender.sendMessage(any ? msg("playback.stopped-everyone")
                                           : msg("playback.nothing-playing-anywhere"));
                    return;
                }
                org.bukkit.entity.Player targetPlayer = Bukkit.getPlayerExact(targetName);
                if (targetPlayer == null) {
                    player.sendMessage(msg("playback.target-not-found",
                            Placeholder.unparsed("name", targetName)));
                    return;
                }
                if (plugin.getPlaybackManager().stopFading(targetPlayer.getUniqueId())) {
                    player.sendMessage(msg("playback.stopped-target",
                            Placeholder.unparsed("name", targetPlayer.getName())));
                } else {
                    player.sendMessage(msg("playback.target-nothing-playing",
                            Placeholder.unparsed("name", targetPlayer.getName())));
                }
            }
            case "categories" -> {
                if (!sender.hasPermission("noteblockworldmc.browse")) {
                    sender.sendMessage(msg("permission.browse-catalog"));
                    return;
                }
                fetchCategories(sender, label);
            }
            case "volume" -> {
                if (!(sender instanceof org.bukkit.entity.Player player)) {
                    sender.sendMessage(msg("general.players-only-volume"));
                    return;
                }
                if (!player.hasPermission("noteblockworldmc.volume")) {
                    player.sendMessage(msg("permission.volume"));
                    return;
                }
                if (rest.length == 0) {
                    player.sendMessage(msg("volume.current", Placeholder.unparsed("volume",
                            String.valueOf(plugin.getVolumeManager()
                                    .getVolume(player.getUniqueId())))));
                    return;
                }
                int value;
                try {
                    value = Integer.parseInt(rest[0]);
                } catch (NumberFormatException e) {
                    player.sendMessage(msg("volume.invalid"));
                    return;
                }
                if (value < 0 || value > 100) {
                    player.sendMessage(msg("volume.invalid"));
                    return;
                }
                plugin.getVolumeManager().setVolume(player.getUniqueId(), value);
                player.sendMessage(msg("volume.set",
                        Placeholder.unparsed("volume", String.valueOf(value))));
            }
            case "remove" -> {
                if (!sender.hasPermission("noteblockworldmc.remove")) {
                    sender.sendMessage(msg("permission.remove"));
                    return;
                }
                if (rest.length == 0) {
                    sender.sendMessage(msg("usage.remove", Placeholder.unparsed("label", label)));
                    return;
                }
                String exact = String.join(" ", rest).trim();
                String id = plugin.getDownloadManager().isOrphan(exact) ? exact : resolveSong(sender, rest);
                if (id == null) return;   // not found / ambiguous, already reported
                String title = plugin.getDownloadManager().getTitle(id);
                if (plugin.getDownloadManager().remove(id)) {
                    plugin.getPlaybackManager().invalidateCache(id);
                    sender.sendMessage(msg("remove.removed", Placeholder.unparsed("song", title)));
                } else {
                    sender.sendMessage(msg("remove.failed"));
                }
            }
            case "import" -> {
                if (!sender.hasPermission("noteblockworldmc.reload")) {
                    sender.sendMessage(msg("permission.reload"));
                    return;
                }
                java.nio.file.Path importDir = plugin.getDownloadManager().getImportDirectory();
                String requestedFile = rest.length == 0 ? null : String.join(" ", rest);
                runBackground(sender, () -> {
                    java.util.List<java.nio.file.Path> files;
                    try {
                        files = plugin.getDownloadManager().findImportFiles(requestedFile);
                    } catch (java.io.IOException e) {
                        plugin.runMain(() ->
                                sender.sendMessage(msg("import.error",
                                        Placeholder.unparsed("error", String.valueOf(e.getMessage())))));
                        return;
                    }

                    if (files.isEmpty()) {
                        plugin.runMain(() ->
                                sender.sendMessage(msg("import.empty",
                                        Placeholder.unparsed("path", importDir.toString()))));
                        return;
                    }

                    int[] tally = {0, 0};   // imported, failed
                    for (java.nio.file.Path file : files) {
                        try {
                            String id = plugin.getDownloadManager().importLocal(file);
                            tally[0]++;
                            plugin.runMain(() ->
                                    sender.sendMessage(msg("import.one",
                                            Placeholder.unparsed("title",
                                                    plugin.getDownloadManager().getTitle(id)))));
                        } catch (Exception e) {
                            tally[1]++;
                            plugin.runMain(() ->
                                    sender.sendMessage(msg("import.failed",
                                            Placeholder.unparsed("file", file.getFileName().toString()),
                                            Placeholder.unparsed("error", String.valueOf(e.getMessage())))));
                        }
                    }
                    plugin.runMain(() ->
                            sender.sendMessage(msg("import.done",
                                    Placeholder.unparsed("count", String.valueOf(tally[0])),
                                    Placeholder.unparsed("failed", String.valueOf(tally[1])))));
                });
            }
            case "importurl" -> {
                if (!sender.hasPermission("noteblockworldmc.importurl")) {
                    sender.sendMessage(msg("permission.importurl"));
                    return;
                }
                if (rest.length < 1) {
                    sender.sendMessage(msg("usage.importurl",
                            Placeholder.unparsed("label", label)));
                    return;
                }
                String url = rest[0];
                // Everything after the URL is an optional name to register under
                String overrideName = rest.length > 1
                        ? String.join(" ", java.util.Arrays.copyOfRange(rest, 1, rest.length))
                        : null;
                sender.sendMessage(msg("importurl.fetching",
                        Placeholder.unparsed("url", url)));
                runBackground(sender, () -> {
                    try {
                        String id = plugin.getDownloadManager().importUrl(url, overrideName);
                        plugin.runMain(() ->
                                sender.sendMessage(msg("importurl.done",
                                        Placeholder.unparsed("title",
                                                plugin.getDownloadManager().getTitle(id)))));
                    } catch (Exception e) {
                        plugin.runMain(() ->
                                sender.sendMessage(msg("importurl.failed",
                                        Placeholder.unparsed("error",
                                                String.valueOf(e.getMessage())))));
                    }
                });
            }
            case "analyze" -> {
                if (!(sender instanceof org.bukkit.entity.Player player)) {
                    sender.sendMessage(msg("general.players-only-analyze"));
                    return;
                }
                if (!sender.hasPermission("noteblockworldmc.play")) {
                    sender.sendMessage(msg("permission.play"));
                    return;
                }
                String songId;
                if (rest.length == 0) {
                    // No song given: analyse whatever the player is hearing now
                    songId = plugin.getPlaybackManager()
                            .currentSongIdFor(player.getUniqueId());
                    if (songId == null) {
                        sender.sendMessage(msg("analyze.nothing-playing"));
                        return;
                    }
                } else {
                    songId = resolveSong(sender, rest);
                    if (songId == null) return;
                }

                plugin.getPlaybackManager().inspect(songId, player, song ->
                        // The sweep sorts every note twice; keep it off the main thread
                        runBackground(sender, () -> {
                            var report = io.github.noteblockworldmc.playback.PolyphonyAnalyzer
                                    .analyze(plugin, song, songId, player.getUniqueId());
                            plugin.runMain(() -> {
                                if (player.isOnline()) sendAnalysis(player, report);
                            });
                        }));
            }
            case "packinfo" -> {
                if (!sender.hasPermission("noteblockworldmc.reload")) {
                    sender.sendMessage(msg("permission.reload"));
                    return;
                }
                var pack = plugin.getResourcePackService();
                sender.sendMessage(net.kyori.adventure.text.Component.text(
                        "Pack enabled=" + pack.isEnabled()
                        + " active=" + pack.isActive()
                        + " bound=" + pack.getBoundAddress()
                        + " url=" + (pack.getPublicUrl().isBlank() ? "(unset)" : pack.getPublicUrl())
                        + " download=" + pack.getDownloadUrl()
                        + " size=" + pack.getPackSize() + "B"
                        + " events=" + pack.getEventCount()
                        + " format=" + pack.getPackFormat()
                        + " sha1=" + pack.getPackSha1()));
                if (sender instanceof org.bukkit.entity.Player player) {
                    sender.sendMessage(net.kyori.adventure.text.Component.text(
                            "Your client: loaded=" + pack.isPackLoaded(player.getUniqueId())
                            + " resolved=" + pack.isResolved(player.getUniqueId())));
                }
            }
            case "playing" -> {
                if (!sender.hasPermission("noteblockworldmc.broadcast")) {
                    sender.sendMessage(msg("permission.broadcast"));
                    return;
                }
                var active = plugin.getPlaybackManager().activeSongs();
                if (active.isEmpty()) {
                    sender.sendMessage(msg("playing.none"));
                    return;
                }
                sender.sendMessage(msg("playing.header",
                        Placeholder.unparsed("count", String.valueOf(active.size()))));
                for (var song : active) {
                    String key = song.broadcast() ? "playing.line-broadcast" : "playing.line";
                    sender.sendMessage(msg(key,
                            Placeholder.unparsed("who", song.who()),
                            Placeholder.unparsed("title", song.title()),
                            Placeholder.unparsed("elapsed", formatClock(song.elapsedSeconds())),
                            Placeholder.unparsed("total", formatClock(song.totalSeconds())),
                            Placeholder.unparsed("state", song.fading() ? "fading" : "playing")));
                }
            }
            case "config" -> {
                if (!sender.hasPermission("noteblockworldmc.config")) {
                    sender.sendMessage(msg("permission.config"));
                    return;
                }
                if (rest.length == 0) {
                    listConfig(sender, label);
                } else if (rest.length == 1) {
                    describeConfig(sender, rest[0]);
                } else if (rest.length == 2) {
                    setConfig(sender, rest[0], rest[1]);
                } else {
                    // Joining the extras would turn a fat-fingered "2 5" into
                    // 25 and set something the admin never typed. Every value
                    // here is a single token, so refuse instead of guessing.
                    sender.sendMessage(msg("config.too-many-values",
                            Placeholder.unparsed("setting", rest[0]),
                            Placeholder.unparsed("label", label)));
                }
            }
            case "reload" -> {
                if (!sender.hasPermission("noteblockworldmc.reload")) {
                    sender.sendMessage(msg("permission.reload"));
                    return;
                }
                try {
                    plugin.reloadSettings(true).whenComplete((unused, error) -> plugin.runMain(() -> {
                        if (error != null) {
                            sender.sendMessage(msg("general.reload-failed",
                                    Placeholder.unparsed("reason", Throwables.describe(error))));
                            plugin.getLogger().warning("Reload failed: " + error);
                        } else {
                            sender.sendMessage(msg("general.reloaded"));
                            sender.sendMessage(msg("general.reload-note"));
                        }
                    }));
                } catch (RuntimeException e) {
                    sender.sendMessage(msg("general.reload-failed",
                            Placeholder.unparsed("reason", Throwables.describe(e))));
                    plugin.getLogger().warning("Reload failed: " + e);
                }
            }
            case "help" -> sendUsage(sender, label);
            default -> sendUsage(sender, label);
        }
    }

    // ------------------------------------------------------------------
    // Fetching
    // ------------------------------------------------------------------

    private void fetchCategories(CommandSender sender, String label) {
        sender.sendMessage(msg("general.fetching-categories"));

        plugin.getApiClient().fetchCategories()
                .whenComplete((categories, throwable) ->
                        plugin.runMain(() -> {
                            if (throwable != null || categories == null) {
                                handleError(sender, throwable != null ? throwable
                                        : new IllegalStateException("noteblock.world returned no categories"));
                                return;
                            }
                            sender.sendMessage(msg("categories.header"));
                            for (Map.Entry<String, Integer> entry : categories.entrySet()) {
                                if (entry.getKey() == null) continue;
                                // Clicking a category lists it directly
                                sender.sendMessage(msg("categories.entry",
                                        Placeholder.unparsed("name", entry.getKey()),
                                        Placeholder.unparsed("count", String.valueOf(entry.getValue())))
                                        .clickEvent(ClickEvent.runCommand(
                                                "/" + label + " search --category " + entry.getKey()))
                                        .hoverEvent(HoverEvent.showText(msg("categories.entry-hover",
                                                Placeholder.unparsed("name", entry.getKey())))));
                            }
                        }));
    }

    private void handleError(CommandSender sender, Throwable throwable) {
        sender.sendMessage(msg("general.api-error",
                Placeholder.unparsed("reason", Throwables.describe(throwable))));
        plugin.getLogger().warning("Note Block World API request failed: "
                + Throwables.rootCause(throwable));
    }

    /**
     * Resolves a song reference typed as free text: an exact public ID, an
     * exact title, or a distinctive part of one ("mega" -> Megalovania).
     * Reports "not found" / "ambiguous" itself and returns null in those
     * cases, so callers can just check for null.
     */
    private String resolveSong(CommandSender sender, String[] words) {
        String query = String.join(" ", words);
        List<String> matches = plugin.getDownloadManager().resolve(query);

        if (matches.isEmpty()) {
            sender.sendMessage(msg("song.not-found", Placeholder.unparsed("query", query)));
            return null;
        }
        if (matches.size() > 1) {
            String names = matches.stream()
                    .limit(5)
                    .map(id -> plugin.getDownloadManager().getTitle(id))
                    .collect(java.util.stream.Collectors.joining(", "));
            sender.sendMessage(msg("song.ambiguous",
                    Placeholder.unparsed("query", query),
                    Placeholder.unparsed("matches", names)));
            return null;
        }
        return matches.get(0);
    }

    private void sendAnalysis(org.bukkit.entity.Player player,
            io.github.noteblockworldmc.playback.PolyphonyAnalyzer.Report report) {
        player.sendMessage(net.kyori.adventure.text.Component.text(
                "Excluded inaudible/unavailable notes: " + report.excludedNotes()));
        player.sendMessage(msg("analyze.header",
                Placeholder.unparsed("title", report.title())));
        player.sendMessage(msg("analyze.notes",
                Placeholder.unparsed("notes", String.valueOf(report.noteCount())),
                Placeholder.unparsed("merged", String.valueOf(report.mergedNotes()))));
        if (report.tempoVaries()) {
            player.sendMessage(msg("analyze.tempo-varies",
                    Placeholder.unparsed("tps", String.format("%.2f", report.tempo())),
                    Placeholder.unparsed("bpm", String.format("%.0f", report.beatsPerMinute())),
                    Placeholder.unparsed("min", String.format("%.2f", report.minTempo())),
                    Placeholder.unparsed("max", String.format("%.2f", report.maxTempo()))));
        } else {
            player.sendMessage(msg("analyze.tempo",
                    Placeholder.unparsed("tps", String.format("%.2f", report.tempo())),
                    Placeholder.unparsed("bpm", String.format("%.0f", report.beatsPerMinute()))));
        }
        player.sendMessage(msg("analyze.peak",
                Placeholder.unparsed("peak", String.valueOf(report.peakVoices())),
                Placeholder.unparsed("at", formatClock(report.peakAtSeconds())),
                Placeholder.unparsed("mean", String.format("%.1f", report.meanVoices()))));
        player.sendMessage(msg("analyze.budget",
                Placeholder.unparsed("pool", String.valueOf(report.clientChannels())),
                Placeholder.unparsed("safe", String.valueOf(report.safeBudget()))));

        String strip = densityStrip(report.density());
        if (!strip.isEmpty()) {
            player.sendMessage(msg("analyze.density",
                    Placeholder.unparsed("strip", strip),
                    Placeholder.unparsed("span", formatClock(report.durationSeconds()))));
        }

        if (report.exceedsPool()) {
            player.sendMessage(msg("analyze.verdict-over"));
        } else if (report.exceedsSafeBudget()) {
            player.sendMessage(msg("analyze.verdict-tight"));
        } else {
            player.sendMessage(msg("analyze.verdict-ok"));
        }
        if (report.estimated()) {
            player.sendMessage(msg("analyze.estimated"));
        }
    }

    /**
     * Renders the concurrency curve as a bar strip, scaled to the song's own
     * busiest slice -- so it shows WHERE the load sits, not how it compares to
     * the channel budget (the verdict lines already cover that).
     *
     * The glyph ramp lives in messages.yml: block characters are likely but
     * not guaranteed to render in Minecraft's font, and swapping them should
     * not need a code change.
     */
    private String densityStrip(double[] density) {
        if (density == null || density.length == 0) return "";
        double max = 0;
        for (double value : density) max = Math.max(max, value);
        if (max <= 0) return "";

        String ramp = plugin.getMessages()
                .getRaw("analyze.density-ramp", "\u2581\u2582\u2583\u2584\u2585\u2586\u2587\u2588");
        if (ramp.isEmpty()) return "";

        StringBuilder out = new StringBuilder(density.length);
        for (double value : density) {
            // Index 0 is reserved for true silence, so an empty slice reads
            // differently from a merely quiet one. Everything audible maps
            // across the remaining glyphs, peaking exactly at the top one.
            int index = value <= 0 ? 0
                    : 1 + (int) Math.round((ramp.length() - 2) * (value / max));
            out.append(ramp.charAt(Math.max(0, Math.min(ramp.length() - 1, index))));
        }
        return out.toString();
    }

    private static String formatClock(double seconds) {
        int total = (int) Math.max(0, Math.round(seconds));
        return String.format("%d:%02d", total / 60, total % 60);
    }

    private Component msg(String key, TagResolver... resolvers) {
        return plugin.getMessages().get(key, resolvers);
    }

    /**
     * A parsed /nbw play target: who the song should play for, and the song
     * arguments with any leading target token removed.
     *
     * The first token is treated as a target only when it is "@a" or the exact
     * name of an online player. Anything else -- including a song whose name
     * happens to be one word -- is left as part of the song query, so the bare
     * "/nbw play SongName" form keeps working unchanged.
     */
    private record PlayTarget(org.bukkit.entity.Player single, boolean everyone,
                              boolean invalid, String rawName, String[] songArgs) {
        boolean needsBroadcastPermission() {
            // Playing for yourself is not a broadcast; anything else is.
            return everyone || single != null;
        }
    }

    private PlayTarget parsePlayTarget(org.bukkit.entity.Player caller, String[] rest) {
        String first = rest[0];
        String[] tail = java.util.Arrays.copyOfRange(rest, 1, rest.length);

        if (first.equalsIgnoreCase("@a")) {
            return new PlayTarget(null, true, false, first, tail);
        }
        // A name matches a target only if that player is online AND there is a
        // song after it; "/nbw play Steve" with Steve online but no song is
        // still read as "play the song called Steve" if such a token exists,
        // but with nothing following we treat Steve as the (song) query so the
        // usual not-found path reports it sensibly.
        org.bukkit.entity.Player named = Bukkit.getPlayerExact(first);
        if (named != null && tail.length > 0) {
            // Targeting yourself is just a normal play, needing only play perm.
            if (named.getUniqueId().equals(caller.getUniqueId())) {
                return new PlayTarget(null, false, false, first, tail);
            }
            return new PlayTarget(named, false, false, first, tail);
        }
        // No target: the whole thing is the song query.
        return new PlayTarget(null, false, false, null, rest);
    }

    private void playResolved(org.bukkit.entity.Player caller, PlayTarget target, String publicId) {
        var request = plugin.getPlaybackManager().beginPlay(caller,
                target.single() == null ? caller : target.single(), target.everyone());
        playResolved(caller, target, publicId, request);
    }
    private void playResolved(org.bukkit.entity.Player caller, PlayTarget target, String publicId,
                              io.github.noteblockworldmc.playback.PlaybackManager.PlayRequest request) {
        plugin.getPlaybackManager().playDownloaded(request, caller, publicId);
        if (target.single() != null) caller.sendMessage(msg("playback.playing-for-target",
                Placeholder.unparsed("name", target.single().getName())));
    }

    /**
     * Lists the settings that can be changed in game.
     *
     * The editor exposes playback controls because they are useful to tune from
     * in-game. Other settings stay in config.yml and are applied by /nbw reload;
     * that includes the API endpoint, HTTP timeouts, download limits, and pack
     * server address.
     */
    private void listConfig(CommandSender sender, String label) {
        var options = ConfigEditor.options();
        sender.sendMessage(msg("config.header",
                Placeholder.unparsed("count", String.valueOf(options.size())),
                Placeholder.unparsed("label", label)));
        String section = null;
        for (var option : options) {
            if (!option.section().equals(section)) {
                section = option.section();
                sender.sendMessage(msg("config.section",
                        Placeholder.unparsed("section", section)));
            }
            sender.sendMessage(msg("config.line",
                    Placeholder.unparsed("setting", option.shortName()),
                    Placeholder.unparsed("value", currentValue(option))));
        }
        sender.sendMessage(msg("config.footer", Placeholder.unparsed("label", label)));
    }

    /** Shows one setting's current value, valid range, and its config.yml comment. */
    private void describeConfig(CommandSender sender, String name) {
        var option = ConfigEditor.find(name);
        if (option == null) {
            sender.sendMessage(msg("config.unknown", Placeholder.unparsed("setting", name)));
            return;
        }
        sender.sendMessage(msg("config.detail-header",
                Placeholder.unparsed("setting", option.shortName()),
                Placeholder.unparsed("path", option.path())));
        sender.sendMessage(msg("config.detail-value",
                Placeholder.unparsed("value", currentValue(option)),
                Placeholder.unparsed("range", option.range())));

        // The explanation comes from config.yml's own comments, so there is a
        // single source of truth rather than a copy in messages.yml that would
        // drift out of date.
        var located = locatedFor(option);
        if (located != null) {
            for (String line : located.summary()) {
                sender.sendMessage(msg("config.detail-comment",
                        Placeholder.unparsed("text", line)));
            }
            if (located.hasMoreDetail()) {
                sender.sendMessage(msg("config.detail-more"));
            }
        }
    }

    private void setConfig(CommandSender sender, String name, String rawValue) {
        var option = ConfigEditor.find(name);
        if (option == null) {
            sender.sendMessage(msg("config.unknown", Placeholder.unparsed("setting", name)));
            return;
        }
        var parsed = ConfigEditor.parse(option, rawValue);
        if (!parsed.ok()) {
            sender.sendMessage(msg("config.invalid",
                    Placeholder.unparsed("setting", option.shortName()),
                    Placeholder.unparsed("value", rawValue),
                    Placeholder.unparsed("reason", parsed.error()),
                    Placeholder.unparsed("range", option.range())));
            return;
        }

        String rendered = ConfigEditor.render(parsed.value());
        String previous;
        try {
            previous = ConfigEditor.write(
                    plugin.getDataFolder().toPath().resolve("config.yml"), option, rendered);
        } catch (java.io.IOException e) {
            // Most likely an older config.yml that predates this setting.
            sender.sendMessage(msg("config.write-failed",
                    Placeholder.unparsed("setting", option.path()),
                    Placeholder.unparsed("reason", Throwables.describe(e))));
            return;
        }

        try {
            plugin.reloadSettings(false);
        } catch (RuntimeException e) {
            // Put the line back: otherwise config.yml holds a value that is
            // not in effect, and the next restart or reload applies it
            // without anyone having seen it work.
            try {
                ConfigEditor.write(plugin.getDataFolder().toPath().resolve("config.yml"), option, previous);
            } catch (java.io.IOException restore) {
                e.addSuppressed(restore);
                plugin.getLogger().warning("Could not restore " + option.path() + " in config.yml: " + restore);
            }
            sender.sendMessage(msg("config.apply-failed",
                    Placeholder.unparsed("setting", option.shortName()),
                    Placeholder.unparsed("reason", Throwables.describe(e))));
            return;
        }

        sender.sendMessage(msg("config.set",
                Placeholder.unparsed("setting", option.shortName()),
                Placeholder.unparsed("old", previous),
                Placeholder.unparsed("new", rendered)));
    }

    /** The value in effect right now, formatted as it appears in config.yml. */
    private String currentValue(ConfigEditor.Option option) {
        var config = plugin.getConfig();
        return switch (option.kind()) {
            case BOOLEAN -> String.valueOf(config.getBoolean(option.path()));
            case INTEGER -> String.valueOf(config.getLong(option.path()));
            case DECIMAL -> ConfigEditor.render(config.getDouble(option.path()));
        };
    }

    /** Reads this setting's line and comment block from config.yml on disk. */
    private ConfigEditor.Located locatedFor(ConfigEditor.Option option) {
        try {
            var lines = java.nio.file.Files.readAllLines(
                    plugin.getDataFolder().toPath().resolve("config.yml"),
                    java.nio.charset.StandardCharsets.UTF_8);
            return ConfigEditor.locate(lines, option);
        } catch (java.io.IOException e) {
            // Nothing to explain is not worth an error message here; the
            // current value and range have already been shown.
            return null;
        }
    }

    private void sendUsage(CommandSender sender, String label) {
        // {message key, permission guarding the command it documents}
        String[][] entries = {
            {"gui", "noteblockworldmc.browse"},
            {"search", "noteblockworldmc.browse"},
            {"categories", "noteblockworldmc.browse"},
            {"download", "noteblockworldmc.download"},
            {"play", "noteblockworldmc.play"},
            // /nbw stop has no permission check of its own; showing it to
            // someone who cannot start playback would just be noise.
            {"stop", "noteblockworldmc.play"},
            {"playing", "noteblockworldmc.broadcast"},
            {"analyze", "noteblockworldmc.play"},
            {"volume", "noteblockworldmc.volume"},
            {"remove", "noteblockworldmc.remove"},
            {"import", "noteblockworldmc.reload"},
            {"importurl", "noteblockworldmc.importurl"},
            {"packinfo", "noteblockworldmc.reload"},
            {"config", "noteblockworldmc.config"},
            {"reload", "noteblockworldmc.reload"},
        };

        boolean any = false;
        for (String[] entry : entries) {
            if (sender.hasPermission(entry[1])) { any = true; break; }
        }
        if (!any) {
            sender.sendMessage(msg("permission.none"));
            return;
        }

        sender.sendMessage(msg("usage.help.header", Placeholder.unparsed("label", label)));
        for (String[] entry : entries) {
            if (sender.hasPermission(entry[1])) {
                sender.sendMessage(msg("usage.help." + entry[0],
                        Placeholder.unparsed("label", label)));
            }
        }
        // The flag list only means anything alongside search.
        if (sender.hasPermission("noteblockworldmc.browse")) {
            sender.sendMessage(msg("usage.help.flags", Placeholder.unparsed("label", label)));
        }
    }

    // ------------------------------------------------------------------

    /** Subcommands with the permission their completions need ("" = anyone). */
    private static final String[][] SUBCOMMANDS = {
            {"search", "noteblockworldmc.browse"}, {"download", "noteblockworldmc.download"},
            {"play", "noteblockworldmc.play"}, {"stop", ""}, {"playing", "noteblockworldmc.broadcast"},
            {"volume", "noteblockworldmc.volume"}, {"remove", "noteblockworldmc.remove"},
            {"categories", "noteblockworldmc.browse"}, {"analyze", "noteblockworldmc.play"},
            {"import", "noteblockworldmc.reload"}, {"importurl", "noteblockworldmc.importurl"},
            {"packinfo", "noteblockworldmc.reload"}, {"config", "noteblockworldmc.config"},
            {"reload", "noteblockworldmc.reload"}, {"help", ""},
    };

    /** Completes the token being typed; {@code args} always holds at least that token. */
    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 0) return List.of();
        if (args.length == 1) {
            return filterPrefix(Arrays.stream(SUBCOMMANDS)
                    .filter(entry -> entry[1].isEmpty() || sender.hasPermission(entry[1]))
                    .map(entry -> entry[0]).toList(), args[0]);
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        // Suggestions are shown to everyone who can see /nbw, so they must not
        // reveal the library or current settings to players who could not
        // run that subcommand anyway.
        String needed = Arrays.stream(SUBCOMMANDS).filter(entry -> entry[0].equals(sub))
                .map(entry -> entry[1]).findFirst().orElse(null);
        if (needed == null || (!needed.isEmpty() && !sender.hasPermission(needed))) return List.of();
        if (sub.equals("import") && sender.hasPermission("noteblockworldmc.reload")) {
            try {
                return filterPrefix(plugin.getDownloadManager().findImportFiles(null).stream()
                        .map(path -> path.getFileName().toString()).toList(), args[args.length - 1]);
            } catch (java.io.IOException e) {
                return List.of();
            }
        }
        // /nbw config <setting> <value>
        if (sub.equals("config") && args.length == 2) {
            return filterPrefix(ConfigEditor.names(), args[1]);
        }
        if (sub.equals("config") && args.length == 3) {
            var option = ConfigEditor.find(args[1]);
            if (option == null) return List.of();
            List<String> values = new java.util.ArrayList<>();
            if (option.kind() == ConfigEditor.Kind.BOOLEAN) {
                values.add("true");
                values.add("false");
            } else {
                // Offer the value already in effect, so tabbing shows it
                // rather than making the admin look it up first.
                values.add(currentValue(option));
            }
            return filterPrefix(values, args[2]);
        }
        // First argument of play/stop can be a target: online players + @a.
        if ((sub.equals("play") || sub.equals("stop")) && args.length == 2) {
            java.util.List<String> targets = new java.util.ArrayList<>();
            // Targets are an admin action; offering them to everyone only
            // invited a permission error.
            if (sender.hasPermission("noteblockworldmc.broadcast")) {
                targets.add("@a");
                for (org.bukkit.entity.Player online : Bukkit.getOnlinePlayers()) {
                    targets.add(online.getName());
                }
            }
            // play also completes song titles here (no target given yet), so
            // merge both; stop takes only a target.
            if (sub.equals("play")) {
                targets.addAll(plugin.getDownloadManager().getTitles());
            }
            return filterPrefix(targets, args[1]);
        }
        if (sub.equals("play") || sub.equals("remove") || sub.equals("analyze")) {
            // Suggest downloaded song titles. Titles with spaces work: the
            // client replaces the whole token being typed with the suggestion.
            return filterPrefix(plugin.getDownloadManager().getTitles(),
                    args[args.length - 1]);
        }
        if (sub.equals("volume")) {
            return args.length == 2
                    ? filterPrefix(Arrays.asList("0", "25", "50", "75", "100"), args[1])
                    : Collections.emptyList();
        }
        if (!sub.equals("search")) {
            return Collections.emptyList();
        }

        String previous = args[args.length - 2].toLowerCase(Locale.ROOT);
        String current = args[args.length - 1];

        return switch (previous) {
            case "--sort" -> filterPrefix(QueryParser.SORT_NAMES, current);
            case "--order" -> filterPrefix(Arrays.asList("asc", "desc"), current);
            case "--category" -> Collections.emptyList();
            default -> current.startsWith("-")
                    ? filterPrefix(QueryParser.FLAGS, current)
                    : Collections.emptyList();
        };
    }

    private List<String> filterPrefix(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower))
                .toList();
    }
}
