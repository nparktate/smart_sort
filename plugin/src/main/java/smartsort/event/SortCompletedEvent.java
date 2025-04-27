// SortCompletedEvent.java
package smartsort.event;

import java.util.List;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public class SortCompletedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Inventory inventory;
    private final Player player;
    private final List<ItemStack> sortedItems;

    public SortCompletedEvent(
        Inventory inventory,
        Player player,
        List<ItemStack> sortedItems
    ) {
        this.inventory = inventory;
        this.player = player;
        this.sortedItems = sortedItems;
    }

    public Inventory getInventory() {
        return inventory;
    }

    public Player getPlayer() {
        return player;
    }

    public List<ItemStack> getSortedItems() {
        return sortedItems;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
