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
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Arrays;
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
 * Search flags: --sort <recent|popular|random|title|duration|count>
 *               --order <asc|desc>  --category <name>  --downloaded
 */
public class SongsCommand implements CommandExecutor, TabCompleter {

    private final NoteblockWorldMCPlugin plugin;

    public SongsCommand(NoteblockWorldMCPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
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
                    return true;
                }
                new io.github.noteblockworldmc.gui.SongBrowserGui(
                        plugin, player, SongQuery.builder().build()).open();
            } else {
                sendUsage(sender, label);
            }
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        String[] rest = Arrays.copyOfRange(args, 1, args.length);

        switch (sub) {
            case "search" -> {
                if (!sender.hasPermission("noteblockworldmc.browse")) {
                    sender.sendMessage(msg("permission.browse-catalog"));
                    return true;
                }
                SongQuery query = QueryParser.parse(plugin, sender, rest);
                if (query == null) return true;
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
                    return true;
                }
                if (!(sender instanceof org.bukkit.entity.Player player)) {
                    sender.sendMessage(msg("general.players-only-gui"));
                    return true;
                }
                new io.github.noteblockworldmc.gui.SongBrowserGui(plugin, player, query).open();
            }
            case "download" -> {
                if (rest.length != 1) {
                    sender.sendMessage(msg("usage.download", Placeholder.unparsed("label", label)));
                    return true;
                }
                if (!sender.hasPermission("noteblockworldmc.download")) {
                    sender.sendMessage(msg("permission.download"));
                    return true;
                }
                // A pasted noteblock.world link works as well as a bare ID.
                String id = SongDownloadManager.extractSongId(rest[0]);
                var downloads = plugin.getDownloadManager();
                if (downloads.isDownloaded(id)) {
                    sender.sendMessage(msg("download.already-downloaded"));
                    return true;
                }
                sender.sendMessage(msg("download.downloading", Placeholder.unparsed("song", id)));
                downloads.download(id, null).whenComplete((path, throwable) ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (throwable != null) {
                                sender.sendMessage(msg("download.failed",
                                        Placeholder.unparsed("reason", Throwables.describe(throwable))));
                                return;
                            }
                            sender.sendMessage(msg("download.downloaded-to", Placeholder.unparsed("path", String.valueOf(path))));
                        }));
            }
            case "play" -> {
                if (!(sender instanceof org.bukkit.entity.Player player)) {
                    sender.sendMessage(msg("general.players-only-play"));
                    return true;
                }
                if (!player.hasPermission("noteblockworldmc.play")) {
                    player.sendMessage(msg("permission.play"));
                    return true;
                }
                if (rest.length == 0) {
                    player.sendMessage(msg("usage.play", Placeholder.unparsed("label", label)));
                    return true;
                }

                // Optional leading target: "@a" for everyone, or a player name.
                // Broadcasting needs the broadcast permission; targeting another
                // player is an admin action and needs it too. Playing for
                // yourself (no target, or your own name) needs only play.
                PlayTarget target = parsePlayTarget(player, rest);
                if (target.invalid()) {
                    player.sendMessage(msg("playback.target-not-found",
                            Placeholder.unparsed("name", target.rawName())));
                    return true;
                }
                if (target.needsBroadcastPermission()
                        && !player.hasPermission("noteblockworldmc.broadcast")) {
                    player.sendMessage(msg("permission.broadcast"));
                    return true;
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
                    return true;
                }

                var downloads = plugin.getDownloadManager();
                String joined = String.join(" ", songArgs);

                // A name (or partial name) from the local library wins; a bare
                // catalog ID still works and is downloaded on demand below.
                List<String> local = downloads.resolve(joined);
                if (local.size() == 1) {
                    playResolved(player, target, local.get(0));
                    return true;
                }
                if (local.size() > 1) {
                    resolveSong(player, songArgs);   // reports the ambiguity
                    return true;
                }
                // Nothing local matched. Only a SINGLE token can plausibly be
                // a catalog ID worth downloading; a multi-word input was a
                // name lookup that failed, and trying to download "My" (the
                // first word) would report a misleading "download failed".
                if (songArgs.length > 1) {
                    player.sendMessage(msg("song.not-found",
                            Placeholder.unparsed("query", joined)));
                    return true;
                }
                String id = songArgs[0];
                if (!player.hasPermission("noteblockworldmc.download")) {
                    player.sendMessage(msg("permission.download-needed"));
                    return true;
                }
                player.sendMessage(msg("download.downloading", Placeholder.unparsed("song", id)));
                downloads.download(id, null).whenComplete((path, throwable) ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (!player.isOnline()) return;
                            if (throwable != null) {
                                player.sendMessage(msg("download.failed",
                                        Placeholder.unparsed("reason", Throwables.describe(throwable))));
                                return;
                            }
                            playResolved(player, target, id);
                        }));
            }
            case "stop" -> {
                if (!(sender instanceof org.bukkit.entity.Player player)) {
                    sender.sendMessage(msg("general.players-only-stop"));
                    return true;
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
                    return true;
                }

                if (!player.hasPermission("noteblockworldmc.broadcast")) {
                    // Not permission.broadcast: that message is about PLAYING
                    // to everyone, which reads wrong when refusing a stop.
                    player.sendMessage(msg("permission.stop-others"));
                    return true;
                }
                String targetName = rest[0];
                if (targetName.equalsIgnoreCase("@a")) {
                    boolean any = plugin.getPlaybackManager().stopBroadcastFading();
                    any |= plugin.getPlaybackManager().stopAllPersonalFading();
                    sender.sendMessage(any ? msg("playback.stopped-everyone")
                                           : msg("playback.nothing-playing-anywhere"));
                    return true;
                }
                org.bukkit.entity.Player targetPlayer = Bukkit.getPlayerExact(targetName);
                if (targetPlayer == null) {
                    player.sendMessage(msg("playback.target-not-found",
                            Placeholder.unparsed("name", targetName)));
                    return true;
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
                    return true;
                }
                fetchCategories(sender, label);
            }
            case "volume" -> {
                if (!(sender instanceof org.bukkit.entity.Player player)) {
                    sender.sendMessage(msg("general.players-only-volume"));
                    return true;
                }
                if (!player.hasPermission("noteblockworldmc.volume")) {
                    player.sendMessage(msg("permission.volume"));
                    return true;
                }
                if (rest.length == 0) {
                    player.sendMessage(msg("volume.current", Placeholder.unparsed("volume",
                            String.valueOf(plugin.getVolumeManager()
                                    .getVolume(player.getUniqueId())))));
                    return true;
                }
                int value;
                try {
                    value = Integer.parseInt(rest[0]);
                } catch (NumberFormatException e) {
                    player.sendMessage(msg("volume.invalid"));
                    return true;
                }
                if (value < 0 || value > 100) {
                    player.sendMessage(msg("volume.invalid"));
                    return true;
                }
                plugin.getVolumeManager().setVolume(player.getUniqueId(), value);
                player.sendMessage(msg("volume.set",
                        Placeholder.unparsed("volume", String.valueOf(value))));
            }
            case "remove" -> {
                if (!sender.hasPermission("noteblockworldmc.remove")) {
                    sender.sendMessage(msg("permission.remove"));
                    return true;
                }
                if (rest.length == 0) {
                    sender.sendMessage(msg("usage.remove", Placeholder.unparsed("label", label)));
                    return true;
                }
                String id = resolveSong(sender, rest);
                if (id == null) return true;   // not found / ambiguous, already reported
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
                    return true;
                }
                java.nio.file.Path importDir = plugin.getDownloadManager().getImportDirectory();
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                    java.util.List<java.nio.file.Path> files;
                    try {
                        // Created at startup, but be defensive if it was deleted
                        java.nio.file.Files.createDirectories(importDir);
                        try (var stream = java.nio.file.Files.list(importDir)) {
                            files = stream
                                    .filter(java.nio.file.Files::isRegularFile)
                                    .filter(f -> f.getFileName().toString()
                                            .toLowerCase(Locale.ROOT).endsWith(".nbs"))
                                    .sorted()
                                    .toList();
                        }
                    } catch (java.io.IOException e) {
                        plugin.getServer().getScheduler().runTask(plugin, () ->
                                sender.sendMessage(msg("import.error",
                                        Placeholder.unparsed("error", String.valueOf(e.getMessage())))));
                        return;
                    }

                    if (files.isEmpty()) {
                        plugin.getServer().getScheduler().runTask(plugin, () ->
                                sender.sendMessage(msg("import.empty",
                                        Placeholder.unparsed("path", importDir.toString()))));
                        return;
                    }

                    int[] tally = {0, 0};   // imported, failed
                    for (java.nio.file.Path file : files) {
                        try {
                            String id = plugin.getDownloadManager().importLocal(file);
                            java.nio.file.Files.deleteIfExists(file);   // consumed
                            tally[0]++;
                            plugin.getServer().getScheduler().runTask(plugin, () ->
                                    sender.sendMessage(msg("import.one",
                                            Placeholder.unparsed("title",
                                                    plugin.getDownloadManager().getTitle(id)))));
                        } catch (Exception e) {
                            tally[1]++;
                            plugin.getServer().getScheduler().runTask(plugin, () ->
                                    sender.sendMessage(msg("import.failed",
                                            Placeholder.unparsed("file", file.getFileName().toString()),
                                            Placeholder.unparsed("error", String.valueOf(e.getMessage())))));
                        }
                    }
                    plugin.getServer().getScheduler().runTask(plugin, () ->
                            sender.sendMessage(msg("import.done",
                                    Placeholder.unparsed("count", String.valueOf(tally[0])),
                                    Placeholder.unparsed("failed", String.valueOf(tally[1])))));
                });
            }
            case "importurl" -> {
                if (!sender.hasPermission("noteblockworldmc.importurl")) {
                    sender.sendMessage(msg("permission.importurl"));
                    return true;
                }
                if (rest.length < 1) {
                    sender.sendMessage(msg("usage.importurl",
                            Placeholder.unparsed("label", label)));
                    return true;
                }
                String url = rest[0];
                // Everything after the URL is an optional name to register under
                String overrideName = rest.length > 1
                        ? String.join(" ", java.util.Arrays.copyOfRange(rest, 1, rest.length))
                        : null;
                sender.sendMessage(msg("importurl.fetching",
                        Placeholder.unparsed("url", url)));
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        String id = plugin.getDownloadManager().importUrl(url, overrideName);
                        plugin.getServer().getScheduler().runTask(plugin, () ->
                                sender.sendMessage(msg("importurl.done",
                                        Placeholder.unparsed("title",
                                                plugin.getDownloadManager().getTitle(id)))));
                    } catch (Exception e) {
                        plugin.getServer().getScheduler().runTask(plugin, () ->
                                sender.sendMessage(msg("importurl.failed",
                                        Placeholder.unparsed("error",
                                                String.valueOf(e.getMessage())))));
                    }
                });
            }
            case "analyze" -> {
                if (!(sender instanceof org.bukkit.entity.Player player)) {
                    sender.sendMessage(msg("general.players-only-analyze"));
                    return true;
                }
                if (!sender.hasPermission("noteblockworldmc.play")) {
                    sender.sendMessage(msg("permission.play"));
                    return true;
                }
                String songId;
                if (rest.length == 0) {
                    // No song given: analyse whatever the player is hearing now
                    songId = plugin.getPlaybackManager()
                            .currentSongIdFor(player.getUniqueId());
                    if (songId == null) {
                        sender.sendMessage(msg("analyze.nothing-playing"));
                        return true;
                    }
                } else {
                    songId = resolveSong(sender, rest);
                    if (songId == null) return true;
                }

                plugin.getPlaybackManager().inspect(songId, player, song ->
                        // The sweep sorts every note twice; keep it off the main thread
                        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                            var report = io.github.noteblockworldmc.playback.PolyphonyAnalyzer
                                    .analyze(plugin, song, songId);
                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                if (player.isOnline()) sendAnalysis(player, report);
                            });
                        }));
            }
            case "packinfo" -> {
                if (!sender.hasPermission("noteblockworldmc.reload")) {
                    sender.sendMessage(msg("permission.reload"));
                    return true;
                }
                var pack = plugin.getResourcePackService();
                sender.sendMessage(net.kyori.adventure.text.Component.text(
                        "Pack enabled=" + pack.isEnabled()
                        + " active=" + pack.isActive()
                        + " url=" + (pack.getPublicUrl().isBlank() ? "(unset)" : pack.getPublicUrl())
                        + " size=" + pack.getPackSize() + "B"
                        + " events=" + pack.getEventCount()
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
                    return true;
                }
                var active = plugin.getPlaybackManager().activeSongs();
                if (active.isEmpty()) {
                    sender.sendMessage(msg("playing.none"));
                    return true;
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
                    return true;
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
                    return true;
                }
                plugin.reloadConfig();
                plugin.getMessages().reload();
                plugin.getPlaybackManager().loadHotSettings();
                plugin.getResourcePackService().loadHotSettings();
                // The category cache has no expiry, so reload is the one place
                // an admin can force it to be re-read.
                plugin.getApiClient().refreshCategories();
                sender.sendMessage(msg("general.reloaded"));
                sender.sendMessage(msg("general.reload-note"));
            }
            case "help" -> sendUsage(sender, label);
            default -> sendUsage(sender, label);
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Fetching
    // ------------------------------------------------------------------

    private void fetchCategories(CommandSender sender, String label) {
        sender.sendMessage(msg("general.fetching-categories"));

        plugin.getApiClient().fetchCategories()
                .whenComplete((categories, throwable) ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (throwable != null) {
                                handleError(sender, throwable);
                                return;
                            }
                            sender.sendMessage(msg("categories.header"));
                            for (Map.Entry<String, Integer> entry : categories.entrySet()) {
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
     * Lists only the commands this sender can actually run.
     *
     * Every line is gated on the permission guarding its own command, rather
     * than split into fixed "player" and "admin" blocks. On a server where
     * everything but volume is op-only — the intended setup — an ordinary
     * player sees just the volume line instead of a menu of commands that
     * would only answer with a permission error.
     */
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
        if (target.everyone()) {
            plugin.getPlaybackManager().playDownloadedForAll(caller, publicId);
        } else if (target.single() != null) {
            plugin.getPlaybackManager().playDownloaded(target.single(), publicId);
            caller.sendMessage(msg("playback.playing-for-target",
                    Placeholder.unparsed("name", target.single().getName())));
        } else {
            plugin.getPlaybackManager().playDownloaded(caller, publicId);
        }
    }

    /**
     * Lists the settings that can be changed in game.
     *
     * Only values the plugin re-reads at runtime appear here. Anything consumed
     * once at startup (the pack port, the API base URL, the HTTP timeouts) is
     * absent by design: offering it would let an admin change a number and see
     * nothing happen.
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

        // Re-read from disk and re-apply, so the change is live immediately
        // rather than waiting for a separate /nbw reload. Both managers are
        // refreshed even though only playback settings are editable today: it
        // costs a handful of field assignments and stays correct if the
        // editable set ever widens.
        plugin.reloadConfig();
        plugin.getPlaybackManager().loadHotSettings();
        plugin.getResourcePackService().loadHotSettings();

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

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filterPrefix(Arrays.asList("search", "download", "play", "stop", "playing", "volume", "remove", "categories", "analyze", "import", "importurl", "packinfo", "config", "reload", "help"), args[0]);
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
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
            targets.add("@a");
            for (org.bukkit.entity.Player online : Bukkit.getOnlinePlayers()) {
                targets.add(online.getName());
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
