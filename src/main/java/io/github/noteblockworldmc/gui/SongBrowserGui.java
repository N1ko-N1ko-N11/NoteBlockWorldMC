package io.github.noteblockworldmc.gui;

import io.github.noteblockworldmc.NoteblockWorldMCPlugin;
import io.github.noteblockworldmc.util.Throwables;
import io.github.noteblockworldmc.api.NoteBlockWorldClient.Sort;
import io.github.noteblockworldmc.api.NoteBlockWorldClient.SongQuery;
import io.github.noteblockworldmc.api.model.SongPage;
import io.github.noteblockworldmc.api.model.SongPreview;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.SoundCategory;
import org.bukkit.Sound;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * Chest GUI browser for the Note Block World catalog.
 *
 * Layout (6 rows / 54 slots):
 *   0-35   songs (4 rows, 36 per page = one API request per GUI page)
 *   36-44  divider
 *   45     prev page       46 page info      47 next page (or reroll for random)
 *   49     sort selector   50 order toggle   51 category selector
 *   52     refresh         53 close
 *
 * The GUI owns a SongQuery; every control click derives a new query
 * (via toBuilder/withPage) and refetches. All Bukkit interaction happens
 * on the main thread; only the HTTP call is async.
 */
public class SongBrowserGui implements InventoryHolder {

    public static final int SONG_SLOTS = 36;

    private static final int SLOT_PREV = 45;
    private static final int SLOT_PAGE_INFO = 46;
    private static final int SLOT_NEXT = 47;
    private static final int SLOT_SEARCH = 48;
    private static final int SLOT_SORT = 49;
    private static final int SLOT_ORDER = 50;
    private static final int SLOT_CATEGORY = 51;
    private static final int SLOT_STOP = 53;

    /**
     * Bottom-row slots that do something when clicked. The rest of that row is
     * decorative filler, and clicking glass should not sound like a button.
     */
    private static final java.util.Set<Integer> CONTROL_SLOTS = java.util.Set.of(
            SLOT_PREV, SLOT_PAGE_INFO, SLOT_NEXT, SLOT_SEARCH,
            SLOT_SORT, SLOT_ORDER, SLOT_CATEGORY, SLOT_STOP);

    /** Discs picked by song id hash, purely for visual variety. */
    private static final Material[] DISCS = {
            Material.MUSIC_DISC_13, Material.MUSIC_DISC_CAT, Material.MUSIC_DISC_BLOCKS,
            Material.MUSIC_DISC_CHIRP, Material.MUSIC_DISC_FAR, Material.MUSIC_DISC_MALL,
            Material.MUSIC_DISC_MELLOHI, Material.MUSIC_DISC_STAL, Material.MUSIC_DISC_STRAD,
            Material.MUSIC_DISC_WARD, Material.MUSIC_DISC_WAIT
    };

    /** Description word-wrap width in the tooltip. */
    private static final int DESC_WRAP_WIDTH = 40;
    /** Description lines shown per tooltip page before F-cycling kicks in. */
    private static final int DESC_LINES_PER_PAGE = 10;

    private final NoteblockWorldMCPlugin plugin;
    private final Player player;
    private final Inventory inventory;

    private SongQuery query;
    private SongPage currentPage;

    /** Categories cache: null while loading, then names in a stable order. */
    private List<String> categories;
    /** -1 = all categories; otherwise index into {@link #categories}. */
    private int categoryIndex = -1;

    /** Discards responses that arrive after a newer request was issued. */
    private int requestId = 0;
    /** True while a page request is outstanding. Main thread only. */
    private boolean fetchInFlight;
    /** Set when a click arrives mid-request, so one refetch follows. */
    private boolean refetchWanted;

    /**
     * Current description page per song slot (F-key cycling).
     * Cleared whenever a new song page is rendered.
     */
    private final Map<Integer, Integer> lorePageBySlot = new HashMap<>();

    private static final long CONTROL_CLICK_THROTTLE_MS = 250;
    private long lastControlClickMillis = 0;

    /** True while the song area shows the fetch-failure barrier. */
    private boolean errorState = false;

    /** Two-step removal confirmation (Shift+Right-click twice). */
    private static final long REMOVE_CONFIRM_MS = 5000;
    private String pendingRemoveId = null;
    private long pendingRemoveAt = 0;

    public SongBrowserGui(NoteblockWorldMCPlugin plugin, Player player, SongQuery initialQuery) {
        this.plugin = plugin;
        this.player = player;
        // Adopt the caller's filters but always page with the GUI's own slot
        // capacity, whatever limit the query arrived with
        this.query = initialQuery.toBuilder().limit(SONG_SLOTS).build();
        this.inventory = Bukkit.createInventory(this, 54,
                plugin.getMessages().item("gui.title"));
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    /**
     * Opens the search dialog on its own, with no browser behind it.
     *
     * For bare {@code /nbw search}: the player gets the input box straight
     * away rather than a usage line telling them what to type. Constructing
     * the GUI is inert -- it only builds an inventory object; nothing is shown
     * and no fetch starts until open() -- and submitting the dialog opens a
     * fresh browser, so this instance is simply discarded.
     */
    public static void openSearchDialog(NoteblockWorldMCPlugin plugin, Player player) {
        new SongBrowserGui(plugin, player, SongQuery.builder().build()).openSearchDialog("");
    }

    /** Opens the GUI and starts the first fetch. */
    public void open() {
        renderLoading();
        player.openInventory(inventory);
        fetch();
        prefetchCategories();
    }

    // ------------------------------------------------------------------
    // Click handling (called from GuiListener, always on main thread)
    // ------------------------------------------------------------------

    /** True when this song slot is actually filled on the current page. */
    private boolean hasSongAt(int slot) {
        return currentPage != null && slot < currentPage.getContent().size();
    }

    /**
     * Short click for a press that did something.
     *
     * Deliberately NOT in SoundCategory.RECORDS. That is the category note
     * playback uses and the one stopSound clears, so a UI blip there would be
     * silenced by a fade or /nbw stop, would be scaled by the player's music
     * volume, and would occupy one of the client's note channels. MASTER keeps
     * it independent of all three.
     *
     * Read from config on each click rather than cached, so toggling it takes
     * effect immediately with no reload wiring.
     */
    private void playClickSound() {
        if (!plugin.getConfig().getBoolean("gui.click-sound", true)) return;
        player.playSound(player, Sound.UI_BUTTON_CLICK, SoundCategory.MASTER, 0.4f, 1.0f);
    }

    /**
     * True if this click came too soon after the last one that could hit the
     * API, in which case the caller should drop it.
     *
     * Shared by the control row and the retry barrier so there is one rate
     * limit rather than one per call site.
     */
    private boolean controlClickThrottled() {
        long now = System.currentTimeMillis();
        if (now - lastControlClickMillis < CONTROL_CLICK_THROTTLE_MS) {
            return true;
        }
        lastControlClickMillis = now;
        return false;
    }

    public void handleClick(int slot, ClickType click) {
        boolean random = isRandom();

        if (slot < SONG_SLOTS) {
            if (errorState && slot == 13) {
                // The retry barrier triggers an API request just like the
                // control row does, so it gets the same throttle. It sat in
                // this branch, which returns before the control-row check, and
                // was the one API-triggering click with no rate limit at all --
                // exactly the wrong thing to leave open, since it is only ever
                // clicked when the service is already in trouble.
                if (controlClickThrottled()) return;
                playClickSound();
                refetch();
            } else {
                // Only a slot holding a song is a button; empty slots below the
                // last result do nothing, and should sound like nothing.
                if (hasSongAt(slot)) playClickSound();
                if (click == ClickType.SWAP_OFFHAND) {
                    cycleDescriptionPage(slot);
                } else {
                    handleSongClick(slot, click);
                }
            }
            return;
        }

        // Throttle the control row: every one of these clicks can trigger an
        // API request, and click-spam would hammer the service. Placed before
        // any state mutation so e.g. the category index can never advance
        // without its matching fetch.
        if (controlClickThrottled()) return;
        // After the throttle, so a click that was dropped stays silent rather
        // than sounding like it worked.
        if (CONTROL_SLOTS.contains(slot)) playClickSound();

        switch (slot) {
            case SLOT_PREV -> {
                if (!random && currentPage != null && currentPage.getPage() > 1) {
                    query = query.withPage(currentPage.getPage() - 1);
                    refetch();
                }
            }
            case SLOT_NEXT -> {
                if (random) {
                    refetch(); // reroll
                } else if (currentPage != null && currentPage.hasNextPage()) {
                    query = query.withPage(currentPage.getPage() + 1);
                    refetch();
                }
            }
            case SLOT_SEARCH -> {
                // Hidden in random mode: the API ignores the text query there
                if (!random) openSearchDialog();
            }
            case SLOT_PAGE_INFO -> {
                // Toggle between the whole catalog and the downloaded library
                query = query.toBuilder()
                        .downloadedOnly(!query.isDownloadedOnly())
                        .page(1)
                        .build();
                refetch();
            }
            case SLOT_STOP -> handleStopClick(click);
            case SLOT_SORT -> {
                query = query.toBuilder()
                        .sort(cycleSort(effectiveSort(), click == ClickType.RIGHT ? -1 : 1))
                        .page(1)
                        .build();
                refetch();
            }
            case SLOT_ORDER -> {
                if (!random) {
                    query = query.toBuilder()
                            .ascending(!query.isEffectivelyAscending())
                            .page(1)
                            .build();
                    refetch();
                }
            }
            case SLOT_CATEGORY -> {
                if (!query.isDownloadedOnly()) {
                    cycleCategory(click == ClickType.RIGHT ? -1 : 1);
                }
            }
            default -> { /* divider / empty */ }
        }
    }

    /**
     * F key (swap-offhand) while hovering a song: advance to the next
     * description page, wrapping back to the first after the last.
     */
    private void cycleDescriptionPage(int slot) {
        if (currentPage == null || slot >= currentPage.getContent().size()) return;
        SongPreview song = currentPage.getContent().get(slot);

        int totalPages = descriptionPageCount(song);
        if (totalPages <= 1) return; // short description — nothing to cycle

        int next = (lorePageBySlot.getOrDefault(slot, 0) + 1) % totalPages;
        lorePageBySlot.put(slot, next);
        // Replacing the item refreshes the tooltip in place while hovering
        inventory.setItem(slot, songItem(song, next));
    }

    private static int descriptionPageCount(SongPreview song) {
        String desc = song.getDescription();
        if (desc == null || desc.isBlank()) return 1;
        int lines = wrap(desc, DESC_WRAP_WIDTH).size();
        return Math.max(1, (lines + DESC_LINES_PER_PAGE - 1) / DESC_LINES_PER_PAGE);
    }

    private void handleSongClick(int slot, ClickType click) {
        if (currentPage == null || slot >= currentPage.getContent().size()) return;
        SongPreview song = currentPage.getContent().get(slot);

        if (click == ClickType.SHIFT_RIGHT
                && plugin.getDownloadManager().isDownloaded(song.getPublicId())) {
            handleRemoveClick(song);
            return;
        }

        if (click.isRightClick()) {
            // Full details in chat — chat scrolls, tooltips don't, so this is
            // the guaranteed-readable view for very long descriptions.
            player.sendMessage(msg("details.header",
                    Placeholder.unparsed("title", song.getTitle()),
                    Placeholder.unparsed("author", song.getDisplayAuthor())));
            player.sendMessage(msg("details.stats",
                    Placeholder.unparsed("duration", song.getFormattedDuration()),
                    Placeholder.unparsed("notes", String.valueOf(song.getNoteCount())),
                    Placeholder.unparsed("plays", String.valueOf(song.getPlayCount())),
                    Placeholder.unparsed("id", song.getPublicId())));
            String desc = song.getDescription();
            if (desc != null && !desc.isBlank()) {
                for (String line : desc.split("\n")) {
                    player.sendMessage(msg("details.description-line",
                            Placeholder.unparsed("line", line)));
                }
            }
            return;
        }

        // Left-click: play for self. Shift+Left-click: play for everyone.
        boolean broadcast = click == ClickType.SHIFT_LEFT;
        if (broadcast && !player.hasPermission("noteblockworldmc.broadcast")) {
            player.sendMessage(msg("permission.broadcast"));
            return;
        }
        if (!broadcast && !player.hasPermission("noteblockworldmc.play")) {
            player.sendMessage(msg("permission.play"));
            return;
        }

        var downloads = plugin.getDownloadManager();
        String id = song.getPublicId();

        if (downloads.isDownloaded(id)) {
            startPlayback(id, broadcast);
            return;
        }

        if (!player.hasPermission("noteblockworldmc.download")) {
            player.sendMessage(msg("permission.download"));
            return;
        }
        if (downloads.isInFlight(id)) {
            player.sendMessage(msg("download.in-progress"));
            return;
        }

        player.sendMessage(msg("download.downloading",
                Placeholder.unparsed("song", song.getTitle())));

        downloads.download(id, song).whenComplete((path, throwable) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;
                    if (throwable != null) {
                        player.sendMessage(msg("download.failed",
                                Placeholder.unparsed("reason", Throwables.describe(throwable))));
                        return;
                    }
                    refreshSongItem(id);
                    startPlayback(id, broadcast);
                }));
    }

    private void startPlayback(String publicId, boolean broadcast) {
        if (broadcast) {
            plugin.getPlaybackManager().playDownloadedForAll(player, publicId);
        } else {
            plugin.getPlaybackManager().playDownloaded(player, publicId);
        }
    }

    /**
     * Shift+Right-click on a downloaded song: first click arms a 5-second
     * confirmation, a second Shift+Right-click on the same song deletes it.
     * Clicking a different song re-arms for that song instead.
     */
    private void handleRemoveClick(SongPreview song) {
        if (!player.hasPermission("noteblockworldmc.remove")) {
            player.sendMessage(msg("permission.remove"));
            return;
        }
        String id = song.getPublicId();
        long now = System.currentTimeMillis();

        if (id.equals(pendingRemoveId) && now - pendingRemoveAt <= REMOVE_CONFIRM_MS) {
            pendingRemoveId = null;
            if (plugin.getDownloadManager().remove(id)) {
                plugin.getPlaybackManager().invalidateCache(id);
                player.sendMessage(msg("remove.removed",
                        Placeholder.unparsed("song", song.getTitle())));
                if (query.isDownloadedOnly()) {
                    refetch(); // the song should vanish from the library view
                } else {
                    refreshSongItem(id);
                }
            } else {
                player.sendMessage(msg("remove.failed"));
            }
            return;
        }

        pendingRemoveId = id;
        pendingRemoveAt = now;
        player.sendMessage(msg("remove.confirm",
                Placeholder.unparsed("song", song.getTitle()),
                Placeholder.unparsed("seconds", String.valueOf(REMOVE_CONFIRM_MS / 1000.0)
                        .replaceAll("\\.0$", ""))));
    }

    /**
     * Stop button: a plain click stops your own song; any shift-click
     * (left or right) does exactly what "/nbw stop @a" does -- fades out the
     * broadcast AND every personal session -- and needs the broadcast
     * permission.
     */
    private void handleStopClick(ClickType click) {
        var playback = plugin.getPlaybackManager();

        if (click.isShiftClick()) {
            if (!player.hasPermission("noteblockworldmc.broadcast")) {
                player.sendMessage(msg("permission.stop-others"));
                return;
            }
            // Deliberately identical to "/nbw stop @a": stop the broadcast AND
            // every personal session, fading rather than cutting. Previously
            // this stopped only the broadcast, and hard-cut it, so the same
            // intent produced different results depending on which one you
            // used.
            boolean any = playback.stopBroadcastFading();
            any |= playback.stopAllPersonalFading();
            player.sendMessage(any ? msg("playback.stopped-everyone")
                                   : msg("playback.nothing-playing-anywhere"));
            return;
        }

        player.sendMessage(playback.stopFading(player.getUniqueId())
                ? msg("playback.stopped")
                : msg("playback.nothing-playing"));
    }

    /** Re-renders a single song item (e.g. to add the downloaded marker). */
    private void refreshSongItem(String publicId) {
        if (!isStillViewing() || currentPage == null) return;
        List<SongPreview> songs = currentPage.getContent();
        for (int i = 0; i < songs.size(); i++) {
            if (songs.get(i).getPublicId().equals(publicId)) {
                inventory.setItem(i, songItem(songs.get(i), lorePageBySlot.getOrDefault(i, 0)));
                return;
            }
        }
    }

    // ------------------------------------------------------------------
    // Fetching
    // ------------------------------------------------------------------

    private void refetch() {
        renderLoading();
        fetch();
    }

    /**
     * Requests the current page, coalescing bursts into at most one extra call.
     *
     * The requestId below only discards a stale RESPONSE -- every click still
     * sent a real request, so holding the next-page button fired one API call
     * per click and threw all but the last away. Against a small volunteer-run
     * API that is the wrong thing to do, so while a request is in flight this
     * records that another is wanted and fires exactly one more when the first
     * lands, using whatever page the user has arrived at by then.
     */
    private void fetch() {
        if (fetchInFlight) {
            refetchWanted = true;
            // Discard the in-flight response too: it is for the page the user
            // has already clicked past, and rendering it would flash stale
            // content before the real one arrives.
            requestId++;
            return;
        }
        fetchInFlight = true;
        final int id = ++requestId;

        // fetchSongs builds its query parameters synchronously before it
        // returns a future, so a RuntimeException there would escape here and
        // leave fetchInFlight stuck true -- a browser frozen on "loading"
        // until the player closes and reopens it. Cheap to rule out.
        CompletableFuture<SongPage> future;
        try {
            future = query.isDownloadedOnly()
                    ? plugin.getDownloadManager().queryLocal(query)
                    : plugin.getApiClient().fetchSongs(query);
        } catch (RuntimeException e) {
            fetchInFlight = false;
            refetchWanted = false;
            renderError();
            return;
        }

        future.whenComplete((page, throwable) ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            fetchInFlight = false;
                            boolean queued = refetchWanted;
                            refetchWanted = false;

                            if (queued && isStillViewing()) {
                                fetch();          // one call for the final page
                                return;
                            }
                            if (id != requestId || !isStillViewing()) return;
                            if (throwable != null) {
                                renderError();
                                return;
                            }
                            this.currentPage = page;
                            render();
                        }));
    }

    private void prefetchCategories() {
        plugin.getApiClient().fetchCategories()
                .whenComplete((cats, throwable) ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (throwable != null || cats == null) return;
                            this.categories = new ArrayList<>();
                            for (Map.Entry<String, Integer> e : cats.entrySet()) {
                                this.categories.add(e.getKey());
                            }
                            // If the GUI was opened with --category, point the cycler at it
                            if (query.getCategory() != null) {
                                int idx = indexOfIgnoreCase(categories, query.getCategory());
                                if (idx >= 0) categoryIndex = idx;
                            }
                            if (isStillViewing()) {
                                inventory.setItem(SLOT_CATEGORY, categoryItem());
                            }
                        }));
    }

    private void cycleCategory(int direction) {
        if (categories == null || categories.isEmpty()) return; // still loading

        // Cycle through: all (-1), cat 0, cat 1, ..., back to all
        int span = categories.size() + 1;
        int pos = (categoryIndex + 1 + direction + span) % span; // shift so "all" = 0
        categoryIndex = pos - 1;

        query = query.toBuilder()
                .category(categoryIndex < 0 ? null : categories.get(categoryIndex))
                .page(1)
                .build();
        refetch();
    }

    private boolean isStillViewing() {
        return player.isOnline()
                && player.getOpenInventory().getTopInventory().getHolder() == this;
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private void render() {
        errorState = false;
        lorePageBySlot.clear(); // fresh songs -> back to description page 1

        // Songs
        List<SongPreview> songs = currentPage.getContent();
        for (int i = 0; i < SONG_SLOTS; i++) {
            inventory.setItem(i, i < songs.size() ? songItem(songs.get(i), 0) : null);
        }
        if (songs.isEmpty()) {
            inventory.setItem(13, named(Material.STRUCTURE_VOID,
                    itemText("gui.empty-title"), List.of(itemText("gui.empty-lore"))));
        }

        renderDividerAndControls();
    }

    private void renderLoading() {
        errorState = false;
        for (int i = 0; i < SONG_SLOTS; i++) inventory.setItem(i, null);
        inventory.setItem(13, named(Material.CLOCK, itemText("gui.loading"), List.of()));
        renderDividerAndControls();
    }

    private void renderError() {
        errorState = true;
        for (int i = 0; i < SONG_SLOTS; i++) inventory.setItem(i, null);
        inventory.setItem(13, named(Material.BARRIER,
                itemText("gui.error-title"), List.of(itemText("gui.error-lore"))));
        renderDividerAndControls();
    }

    private void renderDividerAndControls() {
        ItemStack divider = named(Material.GRAY_STAINED_GLASS_PANE, Component.empty(), List.of());
        for (int i = SONG_SLOTS; i < 45; i++) inventory.setItem(i, divider);
        for (int i = 45; i < 54; i++) inventory.setItem(i, divider);

        boolean random = isRandom();

        if (random) {
            inventory.setItem(SLOT_NEXT, named(Material.SLIME_BALL,
                    itemText("gui.reroll"), List.of(itemText("gui.reroll-lore"))));
        } else {
            if (currentPage != null && currentPage.getPage() > 1) {
                inventory.setItem(SLOT_PREV, named(Material.ARROW,
                        itemText("gui.prev-page"), List.of()));
            }
            if (currentPage != null && currentPage.hasNextPage()) {
                inventory.setItem(SLOT_NEXT, named(Material.ARROW,
                        itemText("gui.next-page"), List.of()));
            }
        }

        inventory.setItem(SLOT_PAGE_INFO, pageInfoItem(random));
        if (!random) {
            // The API ignores the text query when sorting randomly, so a
            // search button here would be a lie.
            inventory.setItem(SLOT_SEARCH, named(Material.SPYGLASS,
                    itemText("gui.search"), List.of(itemText("gui.search-lore"))));
        }
        inventory.setItem(SLOT_STOP, named(Material.BARRIER,
                itemText("gui.stop"), List.of(itemText("gui.stop-lore"))));
        inventory.setItem(SLOT_SORT, sortItem());
        if (!random) {
            inventory.setItem(SLOT_ORDER, orderItem());
        }
        inventory.setItem(SLOT_CATEGORY, categoryItem());
    }

    // ------------------------------------------------------------------
    // Items
    // ------------------------------------------------------------------

    private ItemStack songItem(SongPreview song, int lorePage) {
        Material disc = DISCS[Math.floorMod(song.getPublicId().hashCode(), DISCS.length)];

        List<Component> lore = new ArrayList<>();
        lore.add(itemText("gui.song.by",
                Placeholder.unparsed("author", song.getDisplayAuthor())));
        lore.add(itemText("gui.song.stats",
                Placeholder.unparsed("duration", song.getFormattedDuration()),
                Placeholder.unparsed("notes", String.valueOf(song.getNoteCount())),
                Placeholder.unparsed("plays", String.valueOf(song.getPlayCount()))));

        String desc = song.getDescription();
        boolean paginated = false;
        if (desc != null && !desc.isBlank()) {
            List<String> lines = wrap(desc, DESC_WRAP_WIDTH);
            int totalPages = Math.max(1,
                    (lines.size() + DESC_LINES_PER_PAGE - 1) / DESC_LINES_PER_PAGE);
            paginated = totalPages > 1;
            int page = Math.min(lorePage, totalPages - 1);

            lore.add(Component.empty());
            if (paginated) {
                lore.add(itemText("gui.song.description-header",
                        Placeholder.unparsed("page", String.valueOf(page + 1)),
                        Placeholder.unparsed("total", String.valueOf(totalPages))));
            }
            int from = page * DESC_LINES_PER_PAGE;
            int to = Math.min(lines.size(), from + DESC_LINES_PER_PAGE);
            for (String line : lines.subList(from, to)) {
                lore.add(itemText("gui.song.description-line",
                        Placeholder.unparsed("line", line)));
            }
        }

        boolean isDownloaded = plugin.getDownloadManager().isDownloaded(song.getPublicId());

        lore.add(Component.empty());
        if (isDownloaded) {
            lore.add(itemText("gui.song.downloaded"));
        }
        lore.add(itemText("gui.song.id", Placeholder.unparsed("id", song.getPublicId())));
        if (paginated) {
            lore.add(itemText("gui.song.hint-paginate"));
        }
        lore.add(itemText(isDownloaded ? "gui.song.hint-actions-downloaded"
                : "gui.song.hint-actions"));
        lore.add(itemText("gui.song.hint-details"));

        ItemStack item = named(disc, itemText("gui.song.name",
                Placeholder.unparsed("title", song.getTitle())), lore);
        if (isDownloaded) {
            ItemMeta meta = item.getItemMeta();
            meta.setEnchantmentGlintOverride(true);
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Word-wraps text to the given width, preserving the author's paragraph
     * breaks and hard-breaking words longer than a whole line.
     */
    private static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (String token : paragraph.split(" ")) {
                String word = token;
                if (word.isEmpty()) continue;

                // Hard-break words longer than a whole line (URLs, etc.)
                while (word.length() > width) {
                    if (line.length() > 0) {
                        lines.add(line.toString());
                        line.setLength(0);
                    }
                    lines.add(word.substring(0, width));
                    word = word.substring(width);
                }

                if (line.length() == 0) {
                    line.append(word);
                } else if (line.length() + 1 + word.length() <= width) {
                    line.append(' ').append(word);
                } else {
                    lines.add(line.toString());
                    line.setLength(0);
                    line.append(word);
                }
            }
            lines.add(line.toString()); // keeps intentional blank lines
        }
        // Drop trailing blank lines
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    private ItemStack pageInfoItem(boolean random) {
        Component name;
        List<Component> lore;
        boolean downloadedOnly = query.isDownloadedOnly();
        List<Component> extra = new ArrayList<>();

        if (random) {
            name = itemText("gui.page-info-random");
        } else if (currentPage != null) {
            name = itemText("gui.page-info",
                    Placeholder.unparsed("page", String.valueOf(currentPage.getPage())),
                    Placeholder.unparsed("total", String.valueOf(currentPage.getTotalPages())));
            extra.add(itemText("gui.page-info-total",
                    Placeholder.unparsed("total", String.valueOf(currentPage.getTotal()))));
        } else {
            name = itemText("gui.page-info-loading");
        }
        extra.add(itemText(downloadedOnly
                ? "gui.page-info-mode-downloaded" : "gui.page-info-mode-all"));
        extra.add(itemText(downloadedOnly
                ? "gui.page-info-hint-all" : "gui.page-info-hint-downloaded"));
        lore = extra;
        return named(Material.PAPER, name, lore);
    }

    private ItemStack sortItem() {
        Sort active = effectiveSort();
        List<Component> lore = new ArrayList<>();
        for (Sort sort : Sort.values()) {
            lore.add(itemText(sort == active ? "gui.sort-entry-active" : "gui.sort-entry",
                    Placeholder.component("sort", sortName(sort))));
        }
        lore.add(itemText("gui.sort-hint"));
        return named(Material.COMPARATOR,
                itemText("gui.sort", Placeholder.component("sort", sortName(active))), lore);
    }

    private ItemStack orderItem() {
        boolean asc = query.isEffectivelyAscending();
        List<Component> lore = new ArrayList<>();
        lore.add(itemText(asc ? "gui.order-entry-active" : "gui.order-entry",
                Placeholder.component("order", msg("gui.order-ascending"))));
        lore.add(itemText(!asc ? "gui.order-entry-active" : "gui.order-entry",
                Placeholder.component("order", msg("gui.order-descending"))));
        lore.add(itemText("gui.order-hint"));
        return named(Material.LEVER,
                itemText("gui.order", Placeholder.component("order",
                        msg(asc ? "gui.order-ascending" : "gui.order-descending"))),
                lore);
    }

    private ItemStack categoryItem() {
        Component current = query.getCategory() == null
                ? msg("gui.category-all")
                : Component.text(query.getCategory());
        List<Component> lore = new ArrayList<>();
        if (query.isDownloadedOnly()) {
            lore.add(itemText("gui.category-online-only"));
        } else if (categories == null) {
            lore.add(itemText("gui.category-loading"));
        } else {
            lore.add(itemText("gui.category-hint"));
        }
        return named(Material.BOOK,
                itemText("gui.category", Placeholder.component("category", current)), lore);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Opens the native search dialog (Minecraft 1.21.6+ dialog system,
     * via Paper's Dialog API): one text field that accepts the exact same
     * flag syntax as the chat commands (listed in the dialog body), plus
     * Search/Clear/Cancel. Submitting opens a fresh browser with the results.
     */
    private void openSearchDialog() {
        openSearchDialog(buildInitialQueryText());
    }

    /**
     * Opens the search dialog prefilled with {@code prefill}.
     *
     * Buttons: Search (submits), Clear (opens a fresh browser with default
     * filters -- the same view as bare /nbw, since a dialog button can't blank
     * an input in place), and Cancel.
     */
    private void openSearchDialog(String prefill) {
        List<DialogInput> inputs = List.of(
                DialogInput.text("query", msg("dialog.query-label"))
                        .initial(prefill)
                        .maxLength(200)
                        .width(300)
                        .build());

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(msg("dialog.title"))
                        .canCloseWithEscape(true)
                        .inputs(inputs)
                        .body(List.of(DialogBody.plainMessage(msg("dialog.help"))))
                        .build())
                .type(DialogType.multiAction(
                        List.of(
                                ActionButton.builder(msg("dialog.button-search"))
                                        .action(DialogAction.customClick(this::handleSearchDialogSubmit,
                                                ClickCallback.Options.builder().uses(1).build()))
                                        .build(),
                                ActionButton.builder(msg("dialog.button-clear"))
                                        .action(DialogAction.customClick((response, audience) ->
                                                Bukkit.getScheduler().runTask(plugin, () -> {
                                                    if (player.isOnline()) {
                                                        new SongBrowserGui(plugin, player,
                                                                SongQuery.builder().build()).open();
                                                    }
                                                }),
                                                ClickCallback.Options.builder().uses(1).build()))
                                        .build()),
                        ActionButton.builder(msg("dialog.button-cancel")).build(),
                        2)));

        player.showDialog(dialog);
    }

    /** Serializes the current query back to flag syntax to prefill the dialog. */
    private String buildInitialQueryText() {
        StringBuilder sb = new StringBuilder();
        if (query.getQ() != null && !query.getQ().isBlank()) {
            sb.append(query.getQ());
        }
        if (query.getSort() != null) {
            sb.append(" --sort ").append(query.getSort().getFriendlyName());
        }
        if (query.getAscending() != null) {
            sb.append(" --order ").append(query.getAscending() ? "asc" : "desc");
        }
        if (query.getCategory() != null && !query.getCategory().isBlank()) {
            sb.append(" --category ").append(query.getCategory());
        }
        if (query.isDownloadedOnly()) {
            sb.append(" --downloaded");
        }
        String text = sb.toString().trim();
        // The input's maxLength is 200; an over-long initial value is
        // undefined client behavior, so clamp defensively.
        return text.length() > 200 ? text.substring(0, 200) : text;
    }

    private void handleSearchDialogSubmit(DialogResponseView response, Audience audience) {
        String raw = response.getText("query");
        String[] tokens = raw == null || raw.isBlank()
                ? new String[0] : raw.trim().split("\\s+");

        // Dialog callbacks may not run on the main thread; hop before Bukkit
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            SongQuery parsed = io.github.noteblockworldmc.command.QueryParser
                    .parse(plugin, player, tokens);
            if (parsed == null) return; // parse error already messaged
            new SongBrowserGui(plugin, player, parsed).open();
        });
    }

    private boolean isRandom() {
        return query.getSort() == Sort.RANDOM;
    }

    private Sort effectiveSort() {
        return query.getSort() != null ? query.getSort() : Sort.RECENT;
    }

    private static Sort cycleSort(Sort current, int direction) {
        Sort[] values = Sort.values();
        return values[Math.floorMod(current.ordinal() + direction, values.length)];
    }

    private static int indexOfIgnoreCase(List<String> list, String value) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).equalsIgnoreCase(value)) return i;
        }
        return -1;
    }

    private Component msg(String key, TagResolver... resolvers) {
        return plugin.getMessages().get(key, resolvers);
    }

    /** Localized item text (implicit item italics disabled). */
    private Component itemText(String key, TagResolver... resolvers) {
        return plugin.getMessages().item(key, resolvers);
    }

    private Component sortName(Sort sort) {
        return msg("sort-name." + sort.getFriendlyName());
    }

    private static ItemStack named(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        if (!lore.isEmpty()) meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }
}
