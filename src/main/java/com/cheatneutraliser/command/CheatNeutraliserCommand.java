package com.cheatneutraliser.command;

import com.cheatneutraliser.CheatNeutraliser;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public final class CheatNeutraliserCommand implements CommandExecutor {
    private final CheatNeutraliser plugin;

    public CheatNeutraliserCommand(CheatNeutraliser plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sender.sendMessage(ChatColor.AQUA + "CheatNeutraliser " + ChatColor.WHITE + plugin.getDescription().getVersion());
            sender.sendMessage(ChatColor.GRAY + "Packet guard: " + ChatColor.GREEN + "ACTIVE");
            sender.sendMessage(ChatColor.GRAY + "Tracked players: " + ChatColor.WHITE + plugin.getPacketGuard().getTrackedPlayers());
            sender.sendMessage(ChatColor.GRAY + "Mode: " + ChatColor.WHITE + "neutralise / no-kick");
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            plugin.reloadConfig();
            sender.sendMessage(ChatColor.AQUA + "CheatNeutraliser configuration reloaded.");
            return true;
        }
        if (args[0].equalsIgnoreCase("debug")) {
            boolean enabled = plugin.getConfig().getBoolean("logging.debug", false);
            sender.sendMessage(ChatColor.GRAY + "Debug logging: " + (enabled ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF"));
            return true;
        }
        sender.sendMessage(ChatColor.RED + "Usage: /" + label + " <status|reload|debug>");
        return true;
    }
}
