package sdb.px;

import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import sdb.px.command.StringDuperCommand;
import sdb.px.config.PluginSettings;
import sdb.px.dupe.ModuleRegistry;
import sdb.px.dupe.TripwireDuplicationService;
import sdb.px.listener.TripwireFlowListener;
import sdb.px.ratelimit.GlobalRateLimiter;

public final class StringDuperPlugin extends JavaPlugin {
    private boolean enabled;
    private BukkitTask maintenanceTask;
    private TripwireDuplicationService duplicationService;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        PluginSettings settings;
        try {
            settings = PluginSettings.load(getConfig());
        } catch (IllegalArgumentException exception) {
            getLogger().log(Level.SEVERE, exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        enabled = settings.enabled();
        ModuleRegistry modules = new ModuleRegistry(settings.maxActiveModules());
        GlobalRateLimiter rateLimiter = new GlobalRateLimiter(
                settings.perTick(), settings.perSecond(), settings.perHour());
        duplicationService = new TripwireDuplicationService(this, modules, rateLimiter, settings.debug());

        getServer().getPluginManager().registerEvents(new TripwireFlowListener(this, duplicationService), this);
        StringDuperCommand command = new StringDuperCommand(this);
        getCommand("stringduper").setExecutor(command);
        getCommand("stringduper").setTabCompleter(command);
        maintenanceTask = getServer().getScheduler().runTaskTimer(this, duplicationService::tick, 1L, 1L);
        getLogger().info("StringDuper has been enabled.");
    }

    @Override
    public void onDisable() {
        if (maintenanceTask != null) {
            maintenanceTask.cancel();
        }
        if (duplicationService != null) {
            duplicationService.shutdown();
        }
    }

    public boolean isEnabledGlobally() {
        return enabled;
    }

    public void setEnabledGlobally(boolean enabled) {
        this.enabled = enabled;
    }
}
