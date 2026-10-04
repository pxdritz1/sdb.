package sdb.px;

import org.bukkit.plugin.java.JavaPlugin;
import sdb.px.managers.PluginManager;
import sdb.px.listeners.PlayerListener;

public class sdb. (string dupers back) extends JavaPlugin {
    
    @Override
    public void onEnable() {
        
        // Initialize managers
        PluginManager.getInstance().initialize();
        
        // Register listeners
        getServer().getPluginManager().registerEvents(new PlayerListener(), this);
        
        getLogger().info(getDescription().getName() + " has been enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info(getDescription().getName() + " has been disabled!");
    }
    
}
