package sdb.px;

import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
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
    private GlobalRateLimiter rateLimiter;

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
        rateLimiter = new GlobalRateLimiter(perTick, perSecond, perHour);
        duplicationService = new TripwireDuplicationService(
                this, rateLimiter, maxActive, debug);

        getServer().getPluginManager().registerEvents(duplicationService, this);
        PluginCommand command = getCommand("stringduper");
        if (command == null) {
            throw new IllegalStateException("The stringduper command is missing from plugin.yml");
        }
        StringDuperCommand executor = new StringDuperCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
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
        if (duplicationService != null) {
            duplicationService.onMechanicStateChanged();
        }
    }

    public void reloadRuntimeConfiguration() throws IOException, InvalidConfigurationException {
        Path configPath = getDataFolder().toPath().resolve("config.yml");
        YamlConfiguration loaded = new YamlConfiguration();
        loaded.load(configPath.toFile());
        RuntimeSettings settings = RuntimeSettings.from(loaded);

        rateLimiter.updateLimits(settings.perTick(), settings.perSecond(), settings.perHour());
        getConfig().set("enabled", settings.enabled());
        getConfig().set("debug", settings.debug());
        getConfig().set("modules.max-active", settings.maxActive());
        getConfig().set("limits.per-tick", settings.perTick());
        getConfig().set("limits.per-second", settings.perSecond());
        getConfig().set("limits.per-hour", settings.perHour());
        mechanicEnabled = settings.enabled();
        duplicationService.updateRuntimeSettings(settings.maxActive(), settings.debug());

        getLogger().info("Configuration reloaded from " + configPath
                + ": enabled=" + mechanicEnabled
                + ", debug=" + settings.debug()
                + ", modules.max-active=" + settings.maxActive()
                + ", limits.per-tick=" + settings.perTick()
                + ", limits.per-second=" + settings.perSecond()
                + ", limits.per-hour=" + settings.perHour()
                + ", effective-rate-per-second=" + rateLimiter.effectiveRatePerSecond());
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

    private record RuntimeSettings(
            boolean enabled,
            boolean debug,
            int maxActive,
            double perTick,
            double perSecond,
            double perHour) {
        private static RuntimeSettings from(YamlConfiguration config) {
            return new RuntimeSettings(
                    booleanValue(config, "enabled", true),
                    booleanValue(config, "debug", false),
                    integerValue(config, "modules.max-active", 4),
                    limitValue(config, "limits.per-tick", 0.4),
                    limitValue(config, "limits.per-second", 8.0),
                    limitValue(config, "limits.per-hour", 28800.0));
        }

        private static boolean booleanValue(YamlConfiguration config, String path, boolean fallback) {
            Object value = valueAt(config, path);
            if (value == null) {
                return fallback;
            }
            if (value instanceof Boolean bool) {
                return bool;
            }
            throw new IllegalArgumentException(path + " must be true or false.");
        }

        private static int integerValue(YamlConfiguration config, String path, int fallback) {
            Object value = valueAt(config, path);
            if (value == null) {
                return fallback;
            }
            if (!(value instanceof Number number)) {
                throw new IllegalArgumentException(path + " must be a non-negative integer.");
            }
            double numericValue = number.doubleValue();
            if (!Double.isFinite(numericValue) || numericValue < 0.0
                    || numericValue > Integer.MAX_VALUE || numericValue != Math.rint(numericValue)) {
                throw new IllegalArgumentException(path + " must be a non-negative integer.");
            }
            return (int) numericValue;
        }

        private static double limitValue(YamlConfiguration config, String path, double fallback) {
            Object value = valueAt(config, path);
            if (value == null) {
                return fallback;
            }
            if (!(value instanceof Number number)) {
                throw new IllegalArgumentException(path + " must be a finite non-negative number.");
            }
            double numericValue = number.doubleValue();
            if (!Double.isFinite(numericValue) || numericValue < 0.0) {
                throw new IllegalArgumentException(path + " must be a finite non-negative number.");
            }
            return numericValue;
        }

        private static Object valueAt(YamlConfiguration config, String path) {
            int separator = path.indexOf('.');
            if (separator > 0) {
                String parentPath = path.substring(0, separator);
                Object parent = config.get(parentPath);
                if (parent != null && !(parent instanceof ConfigurationSection)) {
                    throw new IllegalArgumentException(parentPath + " must be a configuration section.");
                }
            }
            return config.get(path);
        }
    }
}
