package sdb.px.dupe;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.block.Block;

public final class ModuleRegistry {
    private static final long INACTIVE_TIMEOUT_TICKS = 20L * 60L;
    private static final int MAX_REMOVALS_PER_TICK = 64;
    private final int maxActiveModules;
    private final LinkedHashMap<ModuleKey, ModuleRecord> modules = new LinkedHashMap<>(16, 0.75f, true);

    public ModuleRegistry(int maxActiveModules) {
        this.maxActiveModules = maxActiveModules;
    }

    public boolean register(ModuleKey key, Set<BlockKey> blocks, long currentTick) {
        ModuleRecord record = modules.get(key);
        if (record == null && modules.size() >= maxActiveModules) {
            return false;
        }
        if (record == null) {
            modules.put(key, new ModuleRecord(Set.copyOf(blocks), currentTick));
        } else {
            record.blocks = Set.copyOf(blocks);
            record.lastActivityTick = currentTick;
        }
        return true;
    }

    public void recordActivity(ModuleKey key, long currentTick) {
        ModuleRecord record = modules.get(key);
        if (record != null) {
            record.lastActivityTick = currentTick;
        }
    }

    public boolean contains(BlockKey block) {
        return modules.values().stream().anyMatch(record -> record.blocks.contains(block));
    }

    public void removeAt(BlockKey block) {
        modules.entrySet().removeIf(entry -> entry.getValue().blocks.contains(block));
    }

    public void tick(long currentTick) {
        Iterator<Map.Entry<ModuleKey, ModuleRecord>> iterator = modules.entrySet().iterator();
        int removals = 0;
        while (iterator.hasNext() && removals < MAX_REMOVALS_PER_TICK) {
            Map.Entry<ModuleKey, ModuleRecord> module = iterator.next();
            if (currentTick - module.getValue().lastActivityTick >= INACTIVE_TIMEOUT_TICKS) {
                iterator.remove();
                removals++;
            }
        }
    }

    public record BlockKey(UUID worldId, int x, int y, int z) {
        public static BlockKey from(Block block) {
            return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }
    }

    public record ModuleKey(UUID worldId, int x, int y, int z) {
    }

    private static final class ModuleRecord {
        private Set<BlockKey> blocks;
        private long lastActivityTick;

        private ModuleRecord(Set<BlockKey> blocks, long lastActivityTick) {
            this.blocks = blocks;
            this.lastActivityTick = lastActivityTick;
        }
    }
}
