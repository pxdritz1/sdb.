package sdb.px.dupe;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

final class FarmRegistry {
    private static final long INACTIVE_TIMEOUT_NANOS = 60_000_000_000L;
    private static final BlockFace[] NEIGHBORS = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN
    };
    private final int maxActive;
    private final LinkedHashMap<TripwireFarm.Key, TripwireFarm> farms = new LinkedHashMap<>();
    private final Map<BlockKey, TripwireFarm.Key> positions = new HashMap<>();
    private final Map<TripwireFarm.Key, Long> lastActivity = new HashMap<>();

    FarmRegistry(int maxActive) {
        this.maxActive = Math.max(0, maxActive);
    }

    TripwireFarm getAt(Block block, long now) {
        expireInactive(now);
        TripwireFarm.Key key = positions.get(BlockKey.from(block));
        TripwireFarm farm = key == null ? null : farms.get(key);
        if (farm != null) {
            lastActivity.put(key, now);
        }
        return farm;
    }

    TripwireFarm get(TripwireFarm.Key key) {
        return farms.get(key);
    }

    boolean register(TripwireFarm farm, long now) {
        expireInactive(now);
        if (maxActive == 0) {
            return false;
        }
        if (!farms.containsKey(farm.key()) && farms.size() >= maxActive) {
            return false;
        }
        removeFarm(farm.key());
        farms.put(farm.key(), farm);
        lastActivity.put(farm.key(), now);
        for (Block block : farm.wires()) {
            positions.put(BlockKey.from(block), farm.key());
        }
        positions.put(BlockKey.from(farm.firstHook()), farm.key());
        positions.put(BlockKey.from(farm.secondHook()), farm.key());
        return true;
    }

    void invalidateNear(Block block) {
        UUID worldId = block.getWorld().getUID();
        remove(new BlockKey(worldId, block.getX(), block.getY(), block.getZ()));
        for (BlockFace face : NEIGHBORS) {
            remove(new BlockKey(
                    worldId,
                    block.getX() + face.getModX(),
                    block.getY() + face.getModY(),
                    block.getZ() + face.getModZ()));
        }
    }

    void invalidateChunk(World world, int chunkX, int chunkZ) {
        Iterator<Map.Entry<TripwireFarm.Key, TripwireFarm>> iterator = farms.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<TripwireFarm.Key, TripwireFarm> entry = iterator.next();
            TripwireFarm farm = entry.getValue();
            boolean inChunk = isInChunk(farm.firstHook(), world, chunkX, chunkZ)
                    || isInChunk(farm.secondHook(), world, chunkX, chunkZ)
                    || farm.wires().stream().anyMatch(block -> isInChunk(block, world, chunkX, chunkZ));
            if (inChunk) {
                removePositions(farm);
                lastActivity.remove(entry.getKey());
                iterator.remove();
            }
        }
    }

    void clear() {
        farms.clear();
        positions.clear();
        lastActivity.clear();
    }

    private void remove(BlockKey blockKey) {
        TripwireFarm.Key key = positions.get(blockKey);
        if (key != null) {
            removeFarm(key);
        }
    }

    private void removeFarm(TripwireFarm.Key key) {
        TripwireFarm removed = farms.remove(key);
        lastActivity.remove(key);
        if (removed != null) {
            removePositions(removed);
        }
    }

    private void expireInactive(long now) {
        Iterator<Map.Entry<TripwireFarm.Key, Long>> iterator = lastActivity.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<TripwireFarm.Key, Long> entry = iterator.next();
            if (now - entry.getValue() >= INACTIVE_TIMEOUT_NANOS) {
                TripwireFarm farm = farms.remove(entry.getKey());
                if (farm != null) {
                    removePositions(farm);
                }
                iterator.remove();
            }
        }
    }

    private void removePositions(TripwireFarm farm) {
        for (Block block : farm.wires()) {
            positions.remove(BlockKey.from(block), farm.key());
        }
        positions.remove(BlockKey.from(farm.firstHook()), farm.key());
        positions.remove(BlockKey.from(farm.secondHook()), farm.key());
    }

    private static boolean isInChunk(Block block, World world, int chunkX, int chunkZ) {
        return block.getWorld().getUID().equals(world.getUID())
                && (block.getX() >> 4) == chunkX
                && (block.getZ() >> 4) == chunkZ;
    }

    record BlockKey(UUID worldId, int x, int y, int z) {
        static BlockKey from(Block block) {
            return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }
    }
}
