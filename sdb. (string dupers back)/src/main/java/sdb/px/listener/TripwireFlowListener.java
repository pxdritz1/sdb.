package sdb.px.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;
import sdb.px.StringDuperPlugin;
import sdb.px.dupe.TripwireDuplicationService;

public final class TripwireFlowListener implements Listener {
    private final StringDuperPlugin plugin;
    private final TripwireDuplicationService duplicationService;

    public TripwireFlowListener(StringDuperPlugin plugin, TripwireDuplicationService duplicationService) {
        this.plugin = plugin;
        this.duplicationService = duplicationService;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockFromTo(BlockFromToEvent event) {
        if (plugin.isEnabledGlobally()) {
            duplicationService.onWaterFlow(event);
        }
    }
}
