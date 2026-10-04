package sdb.px.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import sdb.px.StringDuperPlugin;
import sdb.px.dupe.TripwireDuplicationService;

public final class TripwireFlowListener implements Listener {
    private final StringDuperPlugin plugin;
    private final TripwireDuplicationService duplicationService;

    public TripwireFlowListener(StringDuperPlugin plugin, TripwireDuplicationService duplicationService) {
        this.plugin = plugin;
        this.duplicationService = duplicationService;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onBlockFromTo(BlockFromToEvent event) {
        if (plugin.isEnabledGlobally()) {
            duplicationService.onWaterFlow(event);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        duplicationService.onBlockPlaced(event.getBlockPlaced());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        duplicationService.onBlockChanged(event.getBlock());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPhysics(BlockPhysicsEvent event) {
        duplicationService.onBlockChanged(event.getBlock());
    }
}
