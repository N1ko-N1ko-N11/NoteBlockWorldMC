package io.github.noteblockworldmc.gui;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

import java.util.EnumSet;
import java.util.Set;

/**
 * Routes inventory events to SongBrowserGui instances.
 * Identification is done via the InventoryHolder pattern — no title
 * matching, no static registries, no memory leaks on close.
 */
public class GuiListener implements Listener {

    /**
     * The click types the GUI assigns meaning to. Everything else is
     * swallowed: the song handler treats "not right-click" as a plain
     * left-click, so without this filter, pressing Q (drop), a number key,
     * middle-click or double-click while hovering a song would START
     * PLAYBACK as if it were left-clicked.
     */
    private static final Set<ClickType> MEANINGFUL_CLICKS = EnumSet.of(
            ClickType.LEFT, ClickType.SHIFT_LEFT,
            ClickType.RIGHT, ClickType.SHIFT_RIGHT,
            ClickType.SWAP_OFFHAND);

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof SongBrowserGui gui)) {
            return;
        }
        // Cancel EVERY click while this view is open — including ones in the
        // player's own (bottom) inventory. That is deliberate, not sloppy:
        // shift-clicks and double-click-collect originate in the bottom
        // inventory but move or pull items across the top one. Narrowing
        // this cancel to top-inventory clicks is the classic GUI-plugin
        // regression that reintroduces item theft.
        event.setCancelled(true);

        // Only clicks in the top (GUI) inventory are actions
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        if (!MEANINGFUL_CLICKS.contains(event.getClick())) {
            return;
        }
        gui.handleClick(event.getRawSlot(), event.getClick());
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        // getInventory() is the view's TOP inventory regardless of which
        // slots were dragged over, so this also cancels drags that touch
        // only the player's own slots while the browser is open. That
        // strictness is intentional: a drag spanning both inventories would
        // otherwise deposit items into the GUI.
        if (event.getInventory().getHolder() instanceof SongBrowserGui) {
            event.setCancelled(true);
        }
    }
}
