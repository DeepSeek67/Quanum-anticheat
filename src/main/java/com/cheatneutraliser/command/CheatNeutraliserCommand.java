package com.cheatneutraliser.command;

import com.cheatneutraliser.CheatNeutraliser;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.List;

public final class CheatNeutraliserCommand implements CommandExecutor {
    private final CheatNeutraliser plugin;

    public CheatNeutraliserCommand(CheatNeutraliser plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            List<String> profiles = plugin.getConfig().getStringList("client-profiles.enabled");
            String mode = plugin.getConfig().getString("neutralisation.mode", "NEUTRALISE");
            boolean kick = plugin.getConfig().getBoolean("kick.enabled", false)
                    && "KICK".equalsIgnoreCase(mode);
            boolean packetExceptionKick = !plugin.getConfig().getBoolean("neutralisation.enabled", true)
                    || !"NEUTRALISE".equalsIgnoreCase(mode);

            sender.sendMessage(ChatColor.AQUA + "CheatNeutraliser " + ChatColor.WHITE + plugin.getDescription().getVersion());
            sender.sendMessage(ChatColor.GRAY + "Packet guard: " + ChatColor.GREEN + "ACTIVE");
            sender.sendMessage(ChatColor.GRAY + "Tracked players: " + ChatColor.WHITE + plugin.getPacketGuard().getTrackedPlayers());
            sender.sendMessage(ChatColor.GRAY + "Mode: " + ChatColor.WHITE + mode.toUpperCase());
            sender.sendMessage(ChatColor.GRAY + "Profiles: " + ChatColor.WHITE + (profiles.isEmpty() ? "none" : String.join(", ", profiles)));
            sender.sendMessage(ChatColor.GRAY + "PacketEvents auto-kick: " + (packetExceptionKick ? ChatColor.RED + "ON" : ChatColor.GREEN + "OFF"));
            sender.sendMessage(ChatColor.GRAY + "Optional kick enforcement: " + (kick ? ChatColor.RED + "ON" : ChatColor.GREEN + "OFF"));
            sender.sendMessage(ChatColor.GRAY + "Metadata-independent checks: " + ChatColor.GREEN + "ON");
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            plugin.reloadConfig();
            plugin.applyPacketExceptionPolicy();
            sender.sendMessage(ChatColor.AQUA + "CheatNeutraliser configuration reloaded.");
            sender.sendMessage(ChatColor.GRAY + "Active profiles: " + ChatColor.WHITE
                    + String.join(", ", plugin.getConfig().getStringList("client-profiles.enabled")));
            sender.sendMessage(ChatColor.GRAY + "Mode: " + ChatColor.WHITE
                    + plugin.getConfig().getString("neutralisation.mode", "NEUTRALISE").toUpperCase());
            sender.sendMessage(ChatColor.GRAY + "PacketEvents auto-kick: "
                    + (plugin.getConfig().getBoolean("neutralisation.enabled", true)
                    && "NEUTRALISE".equalsIgnoreCase(plugin.getConfig().getString("neutralisation.mode", "NEUTRALISE"))
                    ? ChatColor.GREEN + "OFF"
                    : ChatColor.RED + "ON"));
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
