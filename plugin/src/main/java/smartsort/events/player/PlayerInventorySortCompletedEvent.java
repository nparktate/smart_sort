package smartsort.events.player;

import java.util.Map;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

public class PlayerInventorySortCompletedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final Map<String, ItemStack> slotAssignments;

    public PlayerInventorySortCompletedEvent(
        Player player,
        Map<String, ItemStack> slotAssignments
    ) {
        this.player = player;
        this.slotAssignments = slotAssignments;
    }

    public Player getPlayer() {
        return player;
    }

    public Map<String, ItemStack> getSlotAssignments() {
        return slotAssignments;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
