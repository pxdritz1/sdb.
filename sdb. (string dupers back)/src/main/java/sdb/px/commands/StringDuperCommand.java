package sdb.px.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import sdb.px.StringDuperPlugin;

import java.util.Locale;

public final class StringDuperCommand implements CommandExecutor {
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
                case "status" -> sender.sendMessage(Component.text("String duplication is ", NamedTextColor.YELLOW)
                        .append(Component.text(
                                plugin.isMechanicEnabled() ? "enabled." : "disabled.",
                                plugin.isMechanicEnabled() ? NamedTextColor.GREEN : NamedTextColor.RED)));
                default -> sendUsage(sender);
            }
            return true;
    }

    private void setEnabled(CommandSender sender, boolean enabled) {
            plugin.setMechanicEnabled(enabled);
            sender.sendMessage(Component.text(
                    "String duplication is now " + (enabled ? "enabled." : "disabled."),
                    NamedTextColor.GREEN));
    }

    private static void sendUsage(CommandSender sender) {
            sender.sendMessage(Component.text(
                    "Usage: /stringduper <on|off|toggle|status>", NamedTextColor.YELLOW));
    }
}
