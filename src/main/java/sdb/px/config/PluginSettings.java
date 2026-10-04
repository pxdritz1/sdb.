package sdb.px.config;

import org.bukkit.configuration.file.FileConfiguration;

public record PluginSettings(
        boolean enabled, boolean debug, int maxActiveModules, double perTick, double perSecond, double perHour) {
    public static PluginSettings load(FileConfiguration config) {
        Object enabledValue = config.get("enabled");
        if (!(enabledValue instanceof Boolean enabled)) {
            throw new IllegalArgumentException("Configuration value 'enabled' must be true or false.");
        }

        Object debugValue = config.get("debug");
        if (debugValue != null && !(debugValue instanceof Boolean)) {
            throw new IllegalArgumentException("Configuration value 'debug' must be true or false.");
        }

        int maxActiveModules = readModuleLimit(config.get("modules.max-active"));
        double perTick = readRateLimit(config.get("limits.per-tick"), "limits.per-tick");
        double perSecond = readRateLimit(config.get("limits.per-second"), "limits.per-second");
        double perHour = readRateLimit(config.get("limits.per-hour"), "limits.per-hour");
        return new PluginSettings(
                enabled, Boolean.TRUE.equals(debugValue), maxActiveModules, perTick, perSecond, perHour);
    }

    private static int readModuleLimit(Object value) {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) {
            throw new IllegalArgumentException("Configuration value 'modules.max-active' must be an integer.");
        }

        long numericValue = ((Number) value).longValue();
        if (numericValue < 0 || numericValue > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Configuration value 'modules.max-active' must be a non-negative integer.");
        }
        return Math.toIntExact(numericValue);
    }

    private static double readRateLimit(Object value, String path) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Configuration value '" + path + "' must be a non-negative number.");
        }

        double rate = number.doubleValue();
        if (!Double.isFinite(rate) || rate < 0) {
            throw new IllegalArgumentException(
                    "Configuration value '" + path + "' must be a finite non-negative number.");
        }
        return rate;
    }
}
