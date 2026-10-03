package sdb.px.dupe;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class ModuleRegistry {
    private static final long INACTIVE_TIMEOUT_TICKS = 20L * 60L;
    private static final int MAX_REMOVALS_PER_TICK = 64;
    private final int maxActiveModules;
    private final LinkedHashMap<ModuleKey, Long> modules = new LinkedHashMap<>(16, 0.75f, true);

    public ModuleRegistry(int maxActiveModules) {
        this.maxActiveModules = maxActiveModules;
    }

    public boolean canProcess(ModuleKey key) {
        return modules.containsKey(key) || modules.size() < maxActiveModules;
    }

    public void recordActivity(ModuleKey key, long currentTick) {
        modules.put(key, currentTick);
    }

    public void tick(long currentTick) {
        Iterator<Map.Entry<ModuleKey, Long>> iterator = modules.entrySet().iterator();
        int removals = 0;
        while (iterator.hasNext() && removals < MAX_REMOVALS_PER_TICK) {
            Map.Entry<ModuleKey, Long> module = iterator.next();
            if (currentTick - module.getValue() < INACTIVE_TIMEOUT_TICKS) {
                break;
            }
            iterator.remove();
            removals++;
        }
    }

    public record ModuleKey(UUID worldId, int x, int y, int z) {
    }
}
