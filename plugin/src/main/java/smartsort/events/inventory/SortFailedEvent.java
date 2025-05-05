package smartsort.events.inventory;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.Inventory;

public class SortFailedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Inventory inventory;
    private final Player player;
    private final String reason;

    public SortFailedEvent(Inventory inventory, Player player, String reason) {
        this.inventory = inventory;
        this.player = player;
        this.reason = reason;
    }

    public Inventory getInventory() {
        return inventory;
    }

    public Player getPlayer() {
        return player;
    }

    public String getReason() {
        return reason;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
