package sdb.px.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.InvalidConfigurationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import sdb.px.StringDuperPlugin;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

public final class StringDuperCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUGGESTIONS = List.of("on", "off", "toggle", "reload");
    private final StringDuperPlugin plugin;

    public StringDuperCommand(StringDuperPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args) {
        if (!sender.hasPermission("stringduper.admin")) {
                sender.sendMessage(Component.text("You do not have permission to use this command.", NamedTextColor.RED));
            return true;
        }
        if (args.length != 1) {
                sendUsage(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "on" -> setEnabled(sender, true);
            case "off" -> setEnabled(sender, false);
            case "toggle" -> setEnabled(sender, !plugin.isMechanicEnabled());
            case "reload" -> reload(sender);
                case "status" -> sender.sendMessage(Component.text("sdb. duplication is ", NamedTextColor.YELLOW)
                        .append(Component.text(
                                plugin.isMechanicEnabled() ? "enabled." : "disabled.",
                                plugin.isMechanicEnabled() ? NamedTextColor.GREEN : NamedTextColor.RED)));
                default -> sendUsage(sender);
            }
            return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args) {
        if (!sender.hasPermission("stringduper.admin") || args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return SUGGESTIONS.stream()
                .filter(suggestion -> suggestion.startsWith(prefix))
                .filter(suggestion -> !suggestion.equalsIgnoreCase(prefix))
                .toList();
    }

    private void setEnabled(CommandSender sender, boolean enabled) {
            plugin.setMechanicEnabled(enabled);
            sender.sendMessage(Component.text(
                    "sdb. duplication is now " + (enabled ? "enabled." : "disabled."),
                    NamedTextColor.GREEN));
    }

    private void reload(CommandSender sender) {
        try {
            plugin.reloadRuntimeConfiguration();
            sender.sendMessage(Component.text("sdb. configuration reloaded.", NamedTextColor.GREEN));
        } catch (IOException | InvalidConfigurationException | IllegalArgumentException exception) {
            plugin.getLogger().log(Level.SEVERE, "Unable to reload plugin configuration.", exception);
            sender.sendMessage(Component.text(
                    "Unable to reload sdb. configuration. Check the server console.",
                    NamedTextColor.RED));
        }
    }

    private static void sendUsage(CommandSender sender) {
            sender.sendMessage(Component.text(
                    "Usage: /stringduper <on|off|toggle|reload|status>", NamedTextColor.YELLOW));
    }
}
