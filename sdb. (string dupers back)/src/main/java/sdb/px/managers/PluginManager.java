package sdb.px.managers;



public class PluginManager {
    private static PluginManager instance;
    
    private PluginManager() {}
    
    public static PluginManager getInstance() {
        if (instance == null) {
            instance = new PluginManager();
        }
        return instance;
    }

    public void initialize() {
        // Initialize your managers here
    }
}
