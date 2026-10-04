package sdb.px;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import sdb.px.commands.StringDuperCommand;
import sdb.px.dupe.TripwireDuplicationService;
import sdb.px.ratelimit.GlobalRateLimiter;

public final class StringDuperPlugin extends JavaPlugin {
    private boolean mechanicEnabled;
    private TripwireDuplicationService duplicationService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        mechanicEnabled = getConfig().getBoolean("enabled", true);

        GlobalRateLimiter rateLimiter = new GlobalRateLimiter(
                getConfig().getDouble("limits.per-tick", 0.46),
                getConfig().getDouble("limits.per-second", 20.0),
                getConfig().getDouble("limits.per-hour", 5000.0));
        duplicationService = new TripwireDuplicationService(
                this,
                rateLimiter,
                getConfig().getInt("modules.max-active", 4),
                getConfig().getBoolean("debug", false));

        getServer().getPluginManager().registerEvents(duplicationService, this);
        PluginCommand command = getCommand("stringduper");
        if (command == null) {
            throw new IllegalStateException("The stringduper command is missing from plugin.yml");
        }
        StringDuperCommand executor = new StringDuperCommand(this);
        command.setExecutor(executor);
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
}
