package sdb.px.dupe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Tripwire;
import org.bukkit.block.data.type.TripwireHook;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import sdb.px.ratelimit.GlobalRateLimiter;

public final class TripwireDuplicationService {
    private static final int MAX_TRIPWIRE_BLOCKS = 40;
    private static final List<BlockFace> AXES = List.of(BlockFace.NORTH, BlockFace.EAST);
    private static final List<BlockFace> HORIZONTAL_FACES =
            List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST);
    private final JavaPlugin plugin;
    private final ModuleRegistry modules;
    private final GlobalRateLimiter rateLimiter;
    private final boolean debug;
    private final Set<ModuleRegistry.BlockKey> pendingRefreshes = new HashSet<>();
    private final Map<ModuleRegistry.ModuleKey, PendingCycle> pendingCycles = new HashMap<>();
    private long currentTick;
    private boolean shuttingDown;

    public TripwireDuplicationService(
            JavaPlugin plugin, ModuleRegistry modules, GlobalRateLimiter rateLimiter, boolean debug) {
        this.plugin = plugin;
        this.modules = modules;
        this.rateLimiter = rateLimiter;
        this.debug = debug;
    }

    public void onWaterFlow(BlockFromToEvent event) {
        if (shuttingDown || !plugin.isEnabled()) {
            return;
        }

        Block source = event.getBlock();
        Block target = event.getToBlock();
        if (target.getType() != Material.TRIPWIRE) {
            return;
        }
        if (source.getType() != Material.WATER) {
            logFlow(source, target, false, false, null);
            return;
        }

        TripwireLine line = findLine(target);
        if (line == null) {
            logFlow(source, target, false, false, null);
            return;
        }

        event.setCancelled(true);
        if (!modules.register(line.key(), line.positions(), currentTick)) {
            logFlow(source, target, true, true, null);
            return;
        }
        if (pendingCycles.containsKey(line.key())) {
            logFlow(source, target, true, true, null);
            return;
        }

        boolean allowed = rateLimiter.tryAcquire();
        logFlow(source, target, true, true, allowed);
        if (!allowed) {
            return;
        }

        BlockData originalData = target.getBlockData().clone();
        target.setType(Material.AIR, false);
        if (target.getType() != Material.AIR) {
            modules.removeAt(ModuleRegistry.BlockKey.from(target));
            return;
        }

        PendingCycle cycle = new PendingCycle(line, target, originalData);
        pendingCycles.put(line.key(), cycle);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> restoreTripwire(cycle), 1L);
    }

    public void onBlockPlaced(Block block) {
        if (shuttingDown) {
            return;
        }
        scheduleRelevantRefreshes(block);
        for (BlockFace face : HORIZONTAL_FACES) {
            scheduleRelevantRefreshes(block.getRelative(face));
        }
        scheduleRelevantRefreshes(block.getRelative(BlockFace.UP));
        scheduleRelevantRefreshes(block.getRelative(BlockFace.DOWN));
    }

    public void onBlockChanged(Block block) {
        if (shuttingDown) {
            return;
        }
        if (isTripwireBlock(block) || modules.contains(ModuleRegistry.BlockKey.from(block))) {
            scheduleRefresh(block);
        }
    }

    public void tick() {
        currentTick++;
        rateLimiter.tick();
        modules.tick(currentTick);
    }

    public void shutdown() {
        shuttingDown = true;
        for (PendingCycle cycle : List.copyOf(pendingCycles.values())) {
            restoreTripwire(cycle);
        }
    }

    private void scheduleRelevantRefreshes(Block block) {
        if (isTripwireBlock(block) || modules.contains(ModuleRegistry.BlockKey.from(block))) {
            scheduleRefresh(block);
        }
    }

    private void scheduleRefresh(Block block) {
        ModuleRegistry.BlockKey key = ModuleRegistry.BlockKey.from(block);
        if (!pendingRefreshes.add(key)) {
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            pendingRefreshes.remove(key);
            modules.removeAt(key);
            Block changedBlock = block.getWorld().getBlockAt(block.getX(), block.getY(), block.getZ());
            TripwireLine line = findLine(changedBlock);
            if (line != null) {
                modules.register(line.key(), line.positions(), currentTick);
            }
        }, 1L);
    }

    private void restoreTripwire(PendingCycle cycle) {
        TripwireLine line = cycle.line();
        Block block = cycle.block();
        if (!pendingCycles.containsKey(line.key())) {
            return;
        }

        if (isHookStillPresent(line) && (block.getType().isAir() || block.getType() == Material.WATER)) {
            block.setBlockData(cycle.data().clone(), true);
            TripwireLine restoredLine = findLine(block);
            if (restoredLine != null && restoredLine.key().equals(line.key())) {
                block.getWorld().dropItem(
                        block.getLocation().add(0.5, 0.1, 0.5), new ItemStack(Material.STRING, 1));
                modules.register(restoredLine.key(), restoredLine.positions(), currentTick);
                modules.recordActivity(restoredLine.key(), currentTick);
            } else {
                modules.removeAt(ModuleRegistry.BlockKey.from(block));
            }
        } else {
            modules.removeAt(ModuleRegistry.BlockKey.from(block));
        }
        pendingCycles.remove(line.key());
    }

    private TripwireLine findLine(Block block) {
        if (block.getType() == Material.TRIPWIRE) {
            return findLineFromWire(block);
        }
        if (block.getType() == Material.TRIPWIRE_HOOK
                && block.getBlockData() instanceof TripwireHook hook
                && HORIZONTAL_FACES.contains(hook.getFacing())) {
            Block firstWire = block.getRelative(hook.getFacing());
            if (firstWire.getType() == Material.TRIPWIRE) {
                TripwireLine line = findLineFromWire(firstWire);
                if (line != null && (line.firstHook().equals(block) || line.secondHook().equals(block))) {
                    return line;
                }
            }
        }
        return null;
    }

    private TripwireLine findLineFromWire(Block target) {
        if (!(target.getBlockData() instanceof Tripwire tripwire) || !tripwire.isAttached()) {
            return null;
        }
        for (BlockFace axis : AXES) {
            Endpoint first = findEndpoint(target, axis);
            Endpoint second = findEndpoint(target, axis.getOppositeFace());
            if (first == null || second == null) {
                continue;
            }
            List<Block> wires = new ArrayList<>();
            for (int index = first.wires().size() - 1; index >= 0; index--) {
                wires.add(first.wires().get(index));
            }
            wires.add(target);
            wires.addAll(second.wires());
            if (wires.size() <= MAX_TRIPWIRE_BLOCKS
                    && hasValidConnections(first.hook(), second.hook(), wires)) {
                return createLine(first.hook(), second.hook(), wires);
            }
        }
        return null;
    }

    private Endpoint findEndpoint(Block wire, BlockFace direction) {
        List<Block> wires = new ArrayList<>();
        Block cursor = wire;
        for (int distance = 1; distance <= MAX_TRIPWIRE_BLOCKS + 1; distance++) {
            cursor = cursor.getRelative(direction);
            if (cursor.getType() == Material.TRIPWIRE
                    && cursor.getBlockData() instanceof Tripwire tripwire
                    && tripwire.isAttached()) {
                wires.add(cursor);
                if (wires.size() > MAX_TRIPWIRE_BLOCKS) {
                    return null;
                }
                continue;
            }
            if (cursor.getType() == Material.TRIPWIRE_HOOK
                    && cursor.getBlockData() instanceof TripwireHook hook
                    && hook.isAttached()
                    && hook.getFacing() == direction.getOppositeFace()
                    && hasHookSupport(cursor, hook)) {
                return new Endpoint(cursor, List.copyOf(wires));
            }
            return null;
        }
        return null;
    }

    private boolean hasValidConnections(Block firstHook, Block secondHook, List<Block> wires) {
        if (wires.isEmpty()) {
            return false;
        }
        for (int index = 0; index < wires.size(); index++) {
            Block wire = wires.get(index);
            if (!(wire.getBlockData() instanceof Tripwire tripwire) || !tripwire.isAttached()) {
                return false;
            }
            Block previous = index == 0 ? firstHook : wires.get(index - 1);
            Block next = index == wires.size() - 1 ? secondHook : wires.get(index + 1);
            BlockFace towardPrevious = directionBetween(wire, previous);
            BlockFace towardNext = directionBetween(wire, next);
            if (towardPrevious == null
                    || towardNext == null
                    || !tripwire.hasFace(towardPrevious)
                    || !tripwire.hasFace(towardNext)
                    || tripwire.getFaces().size() != 2) {
                return false;
            }
        }
        if (!(firstHook.getBlockData() instanceof TripwireHook first)
                || !(secondHook.getBlockData() instanceof TripwireHook second)) {
            return false;
        }
        return first.isAttached()
                && second.isAttached()
                && first.getFacing() == directionBetween(firstHook, wires.get(0))
                && second.getFacing() == directionBetween(secondHook, wires.get(wires.size() - 1))
                && first.getFacing() == second.getFacing().getOppositeFace()
                && hasHookSupport(firstHook, first)
                && hasHookSupport(secondHook, second);
    }

    private TripwireLine createLine(Block firstHook, Block secondHook, List<Block> wires) {
        Block first = compareBlocks(firstHook, secondHook) <= 0 ? firstHook : secondHook;
        ModuleRegistry.ModuleKey key = new ModuleRegistry.ModuleKey(
                first.getWorld().getUID(), first.getX(), first.getY(), first.getZ());
        return new TripwireLine(key, firstHook, secondHook, List.copyOf(wires));
    }

    private boolean isHookStillPresent(TripwireLine line) {
        return line.firstHook().getType() == Material.TRIPWIRE_HOOK
                && line.secondHook().getType() == Material.TRIPWIRE_HOOK;
    }

    private static boolean hasHookSupport(Block block, TripwireHook hook) {
        return block.getRelative(hook.getFacing().getOppositeFace()).getType().isSolid();
    }

    private static BlockFace directionBetween(Block from, Block to) {
        if (from.getWorld() != to.getWorld()) {
            return null;
        }
        int x = to.getX() - from.getX();
        int y = to.getY() - from.getY();
        int z = to.getZ() - from.getZ();
        if (y != 0) {
            return null;
        }
        for (BlockFace face : HORIZONTAL_FACES) {
            if (x == face.getModX() && z == face.getModZ()) {
                return face;
            }
        }
        return null;
    }

    private static int compareBlocks(Block first, Block second) {
        int xOrder = Integer.compare(first.getX(), second.getX());
        if (xOrder != 0) {
            return xOrder;
        }
        int yOrder = Integer.compare(first.getY(), second.getY());
        return yOrder != 0 ? yOrder : Integer.compare(first.getZ(), second.getZ());
    }

    private static boolean isTripwireBlock(Block block) {
        return block.getType() == Material.TRIPWIRE || block.getType() == Material.TRIPWIRE_HOOK;
    }

    private void logFlow(Block source, Block target, boolean recognized, boolean valid, Boolean allowed) {
        if (debug) {
            plugin.getLogger().info("BlockFromToEvent source=" + source.getType()
                    + " destination=" + target.getType()
                    + " destination-data=" + target.getBlockData()
                    + " line-recognized=" + recognized
                    + " structure-valid=" + valid
                    + " rate-limiter-allowed=" + (allowed == null ? "not-checked" : allowed));
        }
    }

    private record Endpoint(Block hook, List<Block> wires) {
    }

    private record TripwireLine(
            ModuleRegistry.ModuleKey key, Block firstHook, Block secondHook, List<Block> wires) {
        private Set<ModuleRegistry.BlockKey> positions() {
            Set<ModuleRegistry.BlockKey> result = new HashSet<>();
            result.add(ModuleRegistry.BlockKey.from(firstHook));
            result.add(ModuleRegistry.BlockKey.from(secondHook));
            for (Block wire : wires) {
                result.add(ModuleRegistry.BlockKey.from(wire));
            }
            return Set.copyOf(result);
        }
    }

    private record PendingCycle(TripwireLine line, Block block, BlockData data) {
    }
}
