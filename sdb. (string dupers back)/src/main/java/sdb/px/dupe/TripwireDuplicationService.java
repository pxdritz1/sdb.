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
    private final Map<CycleKey, PendingCycle> queuedCycles = new LinkedHashMap<>();
    private final Map<CycleKey, ActiveCycle> activeCycles = new HashMap<>();
    private final Map<CycleKey, PendingFlowRefresh> pendingFlowRefreshes = new LinkedHashMap<>();
    private final Set<CycleKey> rateLimitWaitLogged = new HashSet<>();
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
            logFlow(source, target, null, false, tripwireValid, false, false,
                    "rejected", null, Failure.INVALID_SOURCE);
            return;
        }
        if (!tripwireValid) {
            logFlow(source, target, null, true, false, false, false,
                    "rejected", null, Failure.TRIPWIRE_NOT_ATTACHED);
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
                logFlow(source, target, null, true, true, false, false,
                        "rejected", null, failure);
                return;
            }
            if (!farms.register(farm, now)) {
                logFlow(source, target, farm, true, true, true, false,
                        "rejected", null, Failure.MAX_ACTIVE_FARMS);
                return;
            }
        }

        CycleKey cycleKey = CycleKey.from(farm, target);
        boolean alreadyQueued = queuedCycles.containsKey(cycleKey)
                || activeCycles.containsKey(cycleKey);
        if (!rateLimiter.hasPositiveRate()) {
            event.setCancelled(true);
            logFlow(source, target, farm, true, true, true, true,
                    "rejected", false, Failure.RATE_LIMIT_ZERO);
            return;
        }
        event.setCancelled(true);
        if (!alreadyQueued) {
            BlockData originalData = target.getBlockData().clone();
            queuedCycles.put(cycleKey, new PendingCycle(farm, source, target, originalData));
            startTicker();
        }
        logFlow(source, target, farm, true, true, true, true,
                alreadyQueued ? "already-pending" : "queued", null, Failure.NONE);
        if (!alreadyQueued) {
            logCycle("queued", queuedCycles.get(cycleKey), "not-tested", Failure.NONE);
        }
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
        rateLimitWaitLogged.clear();
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
            rateLimitWaitLogged.clear();
            restoreDueCycles(false, false);
            stopTickerIfIdle();
            return;
        }
        restoreDueCycles(false, true);
        processPendingFlowRefreshes();
        startQueuedCycle();
        stopTickerIfIdle();
    }

    private void restoreDueCycles(boolean forceLoad, boolean continueCycles) {
        Iterator<Map.Entry<CycleKey, ActiveCycle>> iterator = activeCycles.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<CycleKey, ActiveCycle> entry = iterator.next();
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
        Iterator<Map.Entry<CycleKey, PendingCycle>> iterator = queuedCycles.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<CycleKey, PendingCycle> entry = iterator.next();
            PendingCycle pending = entry.getValue();
            if (!isFarmLoaded(pending.farm())) {
                continue;
            }
            if (!lineFinder.isValidLine(pending.farm())
                    || pending.block().getType() != Material.TRIPWIRE) {
                iterator.remove();
                rateLimitWaitLogged.remove(entry.getKey());
                logCycle("rejected", pending, "not-tested", Failure.FARM_CHANGED);
                continue;
            }
            if (!rateLimiter.tryAcquire()) {
                if (rateLimitWaitLogged.add(entry.getKey())) {
                    logCycle("rate-limited", pending, "denied", Failure.NONE);
                }
                return;
            }
            rateLimitWaitLogged.remove(entry.getKey());

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
                logCycle("cycle-failed", pending, "allowed", Failure.FARM_CHANGED);
                return;
            }
            activeCycles.put(entry.getKey(), new ActiveCycle(pending, currentTick + 1, true));
            logCycle("cycle-started", pending, "allowed", Failure.NONE);
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
            logCycle("restore-rejected", pending, "allowed", restored.failure());
            if (continueCycles && !isFarmLoaded(pending.farm())) {
                pendingFlowRefreshes.put(
                        CycleKey.from(pending.farm(), block),
                        new PendingFlowRefresh(pending.farm(), pending.source()));
            }
            return true;
        }
        boolean registered = farms.register(restored.farm(), System.nanoTime());
        if (produceOutput) {
            block.getWorld().dropItem(
                    block.getLocation().add(0.5, 0.1, 0.5),
                    new ItemStack(Material.STRING, 1));
        }
        logCycle("produced", pending, "allowed", Failure.NONE);
        if (continueCycles && registered) {
            pendingFlowRefreshes.put(
                    CycleKey.from(pending.farm(), block),
                    new PendingFlowRefresh(restored.farm(), pending.source()));
        }
        return true;
    }

    private void processPendingFlowRefreshes() {
        Iterator<Map.Entry<CycleKey, PendingFlowRefresh>> iterator =
                pendingFlowRefreshes.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<CycleKey, PendingFlowRefresh> entry = iterator.next();
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
            Block source, Block target, TripwireFarm farm, boolean sourceValid, boolean tripwireValid,
            boolean lineRecognized, boolean structureValid,
            String cycleState, Boolean rateLimiterAllowed, Failure failure) {
        if (!debug) {
            return;
        }
        Tripwire tripwire = target.getBlockData() instanceof Tripwire data ? data : null;
        int position = farm == null ? -1 : farm.wires().indexOf(target) + 1;
        plugin.getLogger().info("BlockFromToEvent module=" + (farm == null ? "unresolved" : farm.key())
                + " length=" + (farm == null ? 0 : farm.wires().size())
                + " position=" + (position < 1 ? "unmatched" : position)
                + " source=" + source.getType() + "@" + describeBlock(source)
                + " source-waterlogged=" + isWaterlogged(source)
                + " tripwire=" + target.getType() + "@" + describeBlock(target)
                + " tripwire-data=" + (tripwire == null ? "none" : tripwire)
                + " hooks=" + (farm == null ? "unresolved"
                        : describeBlock(farm.firstHook()) + "/" + describeBlock(farm.secondHook()))
                + " source-valid=" + sourceValid
                + " tripwire-valid=" + tripwireValid
                + " line-recognized=" + lineRecognized
                + " structure-valid=" + structureValid
                + " cycle-state=" + cycleState
                + " rate-limiter-allowed="
                + (rateLimiterAllowed == null ? "not-yet-tested" : rateLimiterAllowed)
                + " failure-reason=" + failure);
    }

    private void logCycle(
            String stage, PendingCycle pending, String rateLimiterState, Failure failure) {
        if (debug) {
            int position = pending.farm().wires().indexOf(pending.block()) + 1;
            plugin.getLogger().info("Tripwire cycle stage=" + stage
                    + " module=" + pending.farm().key()
                    + " length=" + pending.farm().wires().size()
                    + " position=" + (position < 1 ? "unmatched" : position)
                    + " source=" + describeBlock(pending.source())
                    + " tripwire=" + describeBlock(pending.block())
                    + " hooks=" + describeBlock(pending.farm().firstHook())
                    + "/" + describeBlock(pending.farm().secondHook())
                    + " rate-limiter-state=" + rateLimiterState
                    + " failure-reason=" + failure);
        }
    }

    private static String describeBlock(Block block) {
        return block.getWorld().getUID() + ":" + block.getX() + "," + block.getY() + "," + block.getZ();
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

    private record CycleKey(TripwireFarm.Key module, FarmRegistry.BlockKey position) {
        private static CycleKey from(TripwireFarm farm, Block block) {
            return new CycleKey(farm.key(), FarmRegistry.BlockKey.from(block));
        }
    }
}
