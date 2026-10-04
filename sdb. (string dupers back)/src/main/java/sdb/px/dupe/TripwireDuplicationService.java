package sdb.px.dupe;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Tripwire;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import sdb.px.StringDuperPlugin;
import sdb.px.ratelimit.GlobalRateLimiter;
import sdb.px.dupe.TripwireLineFinder.Discovery;
import sdb.px.dupe.TripwireLineFinder.Failure;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

public final class TripwireDuplicationService implements Listener {
    private final StringDuperPlugin plugin;
    private final GlobalRateLimiter rateLimiter;
    private final FarmRegistry farms;
    private final TripwireLineFinder lineFinder = new TripwireLineFinder();
    private final boolean debug;
    private final Map<TripwireFarm.Key, PendingCycle> queuedCycles = new LinkedHashMap<>();
    private final Map<TripwireFarm.Key, ActiveCycle> activeCycles = new HashMap<>();
    private final Map<TripwireFarm.Key, PendingFlowRefresh> pendingFlowRefreshes = new LinkedHashMap<>();
    private final Set<FarmRegistry.BlockKey> internalChanges = new HashSet<>();
    private BukkitTask ticker;
    private long currentTick;
    private boolean shuttingDown;

    public TripwireDuplicationService(
            StringDuperPlugin plugin, GlobalRateLimiter rateLimiter, int maxActive, boolean debug) {
        this.plugin = plugin;
        this.rateLimiter = rateLimiter;
        farms = new FarmRegistry(maxActive);
        this.debug = debug;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onWaterFlow(BlockFromToEvent event) {
        if (shuttingDown || !plugin.isMechanicEnabled()) {
            return;
        }
        Block target = event.getToBlock();
        if (target.getType() != Material.TRIPWIRE) {
            return;
        }

        Block source = event.getBlock();
        boolean waterlogged = source.getBlockData() instanceof Waterlogged data && data.isWaterlogged();
        boolean waterSource = source.getType() == Material.WATER || waterlogged;
        boolean tripwireValid = target.getBlockData() instanceof Tripwire tripwire && tripwire.isAttached();
        if (!waterSource) {
            logFlow(source, target, false, tripwireValid, false, false, false, Failure.INVALID_SOURCE);
            return;
        }
        if (!tripwireValid) {
            logFlow(source, target, true, false, false, false, false, Failure.TRIPWIRE_NOT_ATTACHED);
            return;
        }

        long now = System.nanoTime();
        TripwireFarm farm = findAcceptedFarm(target);
        if (farm == null) {
            farm = farms.getAt(target, now);
        }
        Failure failure = Failure.NONE;
        if (farm == null) {
            Discovery discovery = lineFinder.discover(target);
            farm = discovery.farm();
            failure = discovery.failure();
            if (farm == null) {
                logFlow(source, target, true, true, false, false, false, failure);
                return;
            }
            if (!farms.register(farm, now)) {
                logFlow(source, target, true, true, true, false, false, Failure.MAX_ACTIVE_FARMS);
                return;
            }
        }

        boolean alreadyQueued = queuedCycles.containsKey(farm.key())
                || activeCycles.containsKey(farm.key());
        if (!rateLimiter.hasPositiveRate()) {
            event.setCancelled(true);
            logFlow(source, target, true, true, true, true, false, Failure.RATE_LIMIT_ZERO);
            return;
        }
        event.setCancelled(true);
        if (!alreadyQueued) {
            BlockData originalData = target.getBlockData().clone();
            queuedCycles.put(farm.key(), new PendingCycle(farm, source, target, originalData));
            startTicker();
        }
        logFlow(source, target, true, true, true, true, null, Failure.NONE);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        farms.invalidateNear(event.getBlock());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        farms.invalidateNear(event.getBlockPlaced());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPhysics(BlockPhysicsEvent event) {
        if (!internalChanges.contains(FarmRegistry.BlockKey.from(event.getBlock()))) {
            farms.invalidateNear(event.getBlock());
        }
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        farms.invalidateChunk(event.getWorld(), event.getChunk().getX(), event.getChunk().getZ());
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!queuedCycles.isEmpty() || !activeCycles.isEmpty() || !pendingFlowRefreshes.isEmpty()) {
            startTicker();
        }
    }

    public void shutdown() {
        shuttingDown = true;
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        queuedCycles.clear();
        pendingFlowRefreshes.clear();
        restoreDueCycles(true, false);
        if (!activeCycles.isEmpty()) {
            plugin.getLogger().log(Level.SEVERE, "Unable to restore one or more tripwires during shutdown.");
        }
        farms.clear();
    }

    private void startTicker() {
        if (ticker == null) {
            ticker = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        }
    }

    private void tick() {
        if (shuttingDown || !plugin.isEnabled()) {
            shutdown();
            return;
        }
        currentTick++;
        if (!plugin.isMechanicEnabled()) {
            queuedCycles.clear();
            pendingFlowRefreshes.clear();
            restoreDueCycles(false, false);
            stopTickerIfIdle();
            return;
        }
        restoreDueCycles(false, true);
        startQueuedCycle();
        processPendingFlowRefreshes();
        stopTickerIfIdle();
    }

    private void restoreDueCycles(boolean forceLoad, boolean continueCycles) {
        Iterator<Map.Entry<TripwireFarm.Key, ActiveCycle>> iterator = activeCycles.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<TripwireFarm.Key, ActiveCycle> entry = iterator.next();
            ActiveCycle cycle = entry.getValue();
            if (!continueCycles || forceLoad || cycle.restoreAtTick() <= currentTick) {
                try {
                    if (restore(cycle.pending(), forceLoad, continueCycles, cycle.produce())) {
                        iterator.remove();
                    }
                } catch (RuntimeException exception) {
                    plugin.getLogger().log(
                            Level.SEVERE,
                            "Unable to restore tripwire at " + cycle.pending().block().getLocation(),
                            exception);
                }
            }
        }
    }

    private void startQueuedCycle() {
        Iterator<Map.Entry<TripwireFarm.Key, PendingCycle>> iterator = queuedCycles.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<TripwireFarm.Key, PendingCycle> entry = iterator.next();
            PendingCycle pending = entry.getValue();
            if (!isFarmLoaded(pending.farm())) {
                continue;
            }
            if (!lineFinder.isValidLine(pending.farm())
                    || pending.block().getType() != Material.TRIPWIRE) {
                iterator.remove();
                logCycle(pending, false, Failure.FARM_CHANGED);
                continue;
            }
            if (!rateLimiter.tryAcquire()) {
                return;
            }

            if (!isLoaded(pending.block())) {
                continue;
            }
            iterator.remove();
            Block block = pending.block();
            FarmRegistry.BlockKey key = FarmRegistry.BlockKey.from(block);
            activeCycles.put(entry.getKey(), new ActiveCycle(pending, currentTick + 1, false));
            internalChanges.add(key);
            try {
                block.setType(Material.AIR, false);
            } catch (RuntimeException exception) {
                plugin.getLogger().log(
                        Level.SEVERE,
                        "Unable to begin tripwire cycle at " + block.getLocation(),
                        exception);
                return;
            } finally {
                internalChanges.remove(key);
            }
            if (!block.getType().isAir()) {
                farms.invalidateNear(block);
                logCycle(pending, true, Failure.FARM_CHANGED);
                return;
            }
            activeCycles.put(entry.getKey(), new ActiveCycle(pending, currentTick + 1, true));
            logCycle(pending, true, Failure.NONE);
            return;
        }
    }

    private boolean restore(
            PendingCycle pending, boolean forceLoad, boolean continueCycles, boolean produceOutput) {
        Block block = pending.block();
        if (!isLoaded(block)) {
            if (!forceLoad) {
                return false;
            }
            block.getWorld().getChunkAt(block.getX() >> 4, block.getZ() >> 4);
            if (!isLoaded(block)) {
                return false;
            }
        }

        FarmRegistry.BlockKey key = FarmRegistry.BlockKey.from(block);
        internalChanges.add(key);
        try {
            block.setBlockData(pending.originalData().clone(), true);
        } finally {
            internalChanges.remove(key);
        }
        if (block.getType() != Material.TRIPWIRE) {
            return false;
        }

        if (!produceOutput) {
            return true;
        }

        Discovery restored = lineFinder.discover(block);
        if (restored.farm() == null || !restored.farm().key().equals(pending.farm().key())) {
            farms.invalidateNear(block);
            logCycle(pending, true, restored.failure());
            if (continueCycles && !isFarmLoaded(pending.farm())) {
                pendingFlowRefreshes.put(
                        pending.farm().key(), new PendingFlowRefresh(pending.farm(), pending.source()));
            }
            return true;
        }
        boolean registered = farms.register(restored.farm(), System.nanoTime());
        if (produceOutput) {
            block.getWorld().dropItem(
                    block.getLocation().add(0.5, 0.1, 0.5),
                    new ItemStack(Material.STRING, 1));
        }
        logCycle(pending, true, Failure.NONE);
        if (continueCycles && registered) {
            pendingFlowRefreshes.put(
                    pending.farm().key(), new PendingFlowRefresh(restored.farm(), pending.source()));
        }
        return true;
    }

    private void processPendingFlowRefreshes() {
        Iterator<Map.Entry<TripwireFarm.Key, PendingFlowRefresh>> iterator =
                pendingFlowRefreshes.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<TripwireFarm.Key, PendingFlowRefresh> entry = iterator.next();
            PendingFlowRefresh refresh = entry.getValue();
            if (!isFarmLoaded(refresh.farm()) || !isLoaded(refresh.source())) {
                continue;
            }
            if (!isWaterSource(refresh.source()) || !lineFinder.isValidLine(refresh.farm())) {
                iterator.remove();
                continue;
            }
            try {
                refresh.source().fluidTick();
                iterator.remove();
            } catch (RuntimeException exception) {
                plugin.getLogger().log(
                        Level.SEVERE,
                        "Unable to refresh water flow at " + refresh.source().getLocation(),
                        exception);
            }
        }
    }

    private TripwireFarm findAcceptedFarm(Block target) {
        for (PendingCycle cycle : queuedCycles.values()) {
            if (cycle.farm().wires().contains(target)) {
                return cycle.farm();
            }
        }
        for (ActiveCycle cycle : activeCycles.values()) {
            if (cycle.pending().farm().wires().contains(target)) {
                return cycle.pending().farm();
            }
        }
        return null;
    }

    private void stopTickerIfIdle() {
        if (queuedCycles.isEmpty() && activeCycles.isEmpty() && pendingFlowRefreshes.isEmpty()
                && ticker != null) {
            ticker.cancel();
            ticker = null;
        }
    }

    private static boolean isFarmLoaded(TripwireFarm farm) {
        if (!isLoaded(farm.firstHook()) || !isLoaded(farm.secondHook())) {
            return false;
        }
        for (Block wire : farm.wires()) {
            if (!isLoaded(wire)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLoaded(Block block) {
        return block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4);
    }

    private static boolean isWaterSource(Block block) {
        return block.getType() == Material.WATER || isWaterlogged(block);
    }

    private void logFlow(
            Block source, Block target, boolean sourceValid, boolean tripwireValid,
            boolean lineRecognized, boolean structureValid,
            Boolean rateLimiterAllowed, Failure failure) {
        if (!debug) {
            return;
        }
        Tripwire tripwire = target.getBlockData() instanceof Tripwire data ? data : null;
        plugin.getLogger().info("BlockFromToEvent source=" + source.getType()
                + " source-waterlogged=" + isWaterlogged(source)
                + " destination=" + target.getType()
                + " tripwire-data=" + (tripwire == null ? "none" : tripwire)
                + " source-valid=" + sourceValid
                + " tripwire-valid=" + tripwireValid
                + " line-recognized=" + lineRecognized
                + " structure-valid=" + structureValid
                + " rate-limiter-allowed="
                + (rateLimiterAllowed == null ? "queued" : rateLimiterAllowed)
                + " failure-reason=" + failure);
    }

    private void logCycle(PendingCycle pending, boolean rateLimiterAllowed, Failure failure) {
        if (debug) {
            plugin.getLogger().info("Tripwire cycle farm=" + pending.farm().key()
                    + " rate-limiter-allowed=" + rateLimiterAllowed
                    + " failure-reason=" + failure);
        }
    }

    private static boolean isWaterlogged(Block block) {
        return block.getBlockData() instanceof Waterlogged waterlogged && waterlogged.isWaterlogged();
    }

    private record PendingCycle(TripwireFarm farm, Block source, Block block, BlockData originalData) {
    }

    private record ActiveCycle(PendingCycle pending, long restoreAtTick, boolean produce) {
    }

    private record PendingFlowRefresh(TripwireFarm farm, Block source) {
    }
}
