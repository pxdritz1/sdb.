package sdb.px.dupe;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Tripwire;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import sdb.px.ratelimit.GlobalRateLimiter;

public final class TripwireDuplicationService {
    private final JavaPlugin plugin;
    private final ModuleRegistry modules;
    private final GlobalRateLimiter rateLimiter;
    private long currentTick;

    public TripwireDuplicationService(
            JavaPlugin plugin, ModuleRegistry modules, GlobalRateLimiter rateLimiter) {
        this.plugin = plugin;
        this.modules = modules;
        this.rateLimiter = rateLimiter;
    }

    public void onWaterFlow(BlockFromToEvent event) {
        if (!plugin.isEnabled()) {
            return;
        }

        Block source = event.getBlock();
        Block target = event.getToBlock();
        if (!isWaterloggedTrapdoor(source) || !isAttachedTripwire(target)) {
            return;
        }

        ModuleRegistry.ModuleKey moduleKey = new ModuleRegistry.ModuleKey(
                source.getWorld().getUID(), source.getX(), source.getY(), source.getZ());
        if (!modules.canProcess(moduleKey) || !rateLimiter.tryAcquire()) {
            return;
        }

        event.setCancelled(true);
        target.getWorld().dropItem(target.getLocation().add(0.5, 0.1, 0.5), new ItemStack(Material.STRING));
        modules.recordActivity(moduleKey, currentTick);
    }

    public void tick() {
        currentTick++;
        rateLimiter.tick(System.nanoTime());
        modules.tick(currentTick);
    }

    private static boolean isWaterloggedTrapdoor(Block block) {
        return block.getType().name().endsWith("_TRAPDOOR")
                && block.getBlockData() instanceof Waterlogged waterlogged
                && waterlogged.isWaterlogged();
    }

    private static boolean isAttachedTripwire(Block block) {
        return block.getType() == Material.TRIPWIRE
                && block.getBlockData() instanceof Tripwire tripwire
                && tripwire.isAttached();
    }
}
