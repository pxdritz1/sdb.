package sdb.px.command;

import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import sdb.px.StringDuperPlugin;

public final class StringDuperCommand implements CommandExecutor, TabCompleter {
    private static final List<String> ACTIONS = List.of("on", "off", "toggle");
    private final StringDuperPlugin plugin;

    public StringDuperCommand(StringDuperPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player) || !player.isOp()) {
            sender.sendMessage("You do not have permission to use this command.");
            return true;
        }

        if (args.length != 1) {
            sender.sendMessage("Usage: /stringduper <on|off|toggle>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "on" -> setState(player, true);
            case "off" -> setState(player, false);
            case "toggle" -> setState(player, !plugin.isEnabledGlobally());
            default -> player.sendMessage("Unknown action. Use on, off, or toggle.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase();
        return ACTIONS.stream().filter(action -> action.startsWith(prefix)).toList();
    }

    private void setState(Player player, boolean enabled) {
        plugin.setEnabledGlobally(enabled);
        player.sendMessage(enabled ? "String duper enabled." : "String duper disabled.");
    }
}
