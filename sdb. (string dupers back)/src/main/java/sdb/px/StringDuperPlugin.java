package sdb.px;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import sdb.px.commands.StringDuperCommand;
import sdb.px.dupe.TripwireDuplicationService;
import sdb.px.ratelimit.GlobalRateLimiter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class StringDuperPlugin extends JavaPlugin {
    private boolean mechanicEnabled;
    private TripwireDuplicationService duplicationService;

    @Override
    public void onEnable() {
        migrateLegacyConfig();
        saveDefaultConfig();
        mechanicEnabled = getConfig().getBoolean("enabled", true);

        double perTick = getConfig().getDouble("limits.per-tick", 0.4);
        double perSecond = getConfig().getDouble("limits.per-second", 8.0);
        double perHour = getConfig().getDouble("limits.per-hour", 28800.0);
        int maxActive = getConfig().getInt("modules.max-active", 4);
        boolean debug = getConfig().getBoolean("debug", false);
        GlobalRateLimiter rateLimiter = new GlobalRateLimiter(perTick, perSecond, perHour);
        duplicationService = new TripwireDuplicationService(
                this, rateLimiter, maxActive, debug);

        getServer().getPluginManager().registerEvents(duplicationService, this);
        PluginCommand command = getCommand("stringduper");
        if (command == null) {
            throw new IllegalStateException("The stringduper command is missing from plugin.yml");
        }
        StringDuperCommand executor = new StringDuperCommand(this);
        command.setExecutor(executor);
        getLogger().info("Configuration loaded from " + getDataFolder().toPath().resolve("config.yml")
                + ": enabled=" + mechanicEnabled
                + ", debug=" + debug
                + ", modules.max-active=" + maxActive
                + ", limits.per-tick=" + perTick
                + ", limits.per-second=" + perSecond
                + ", limits.per-hour=" + perHour
                + ", effective-rate-per-second=" + rateLimiter.effectiveRatePerSecond());
        getLogger().info(getPluginMeta().getName() + " has been enabled.");
    }

    @Override
    public void onDisable() {
        if (duplicationService != null) {
            duplicationService.shutdown();
        }
    }

    public boolean isMechanicEnabled() {
        return mechanicEnabled;
    }

    public void setMechanicEnabled(boolean enabled) {
        mechanicEnabled = enabled;
        getConfig().set("enabled", enabled);
        saveConfig();
    }

    private void migrateLegacyConfig() {
        Path newConfig = getDataFolder().toPath().resolve("config.yml");
        Path legacyConfig = getDataFolder().toPath()
                .resolveSibling("StringDuper")
                .resolve("config.yml");
        if (Files.exists(newConfig) || !Files.isRegularFile(legacyConfig)) {
            return;
        }
        try {
            Files.createDirectories(newConfig.getParent());
            Files.copy(legacyConfig, newConfig);
            getLogger().info("Migrated configuration from " + legacyConfig + " to " + newConfig + ".");
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to migrate legacy StringDuper configuration.", exception);
        }
    }
}
