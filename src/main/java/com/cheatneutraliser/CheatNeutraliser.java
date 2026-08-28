package com.cheatneutraliser;

import com.cheatneutraliser.analysis.AsyncAnalyzer;
import com.cheatneutraliser.packet.PacketGuard;
import com.cheatneutraliser.command.CheatNeutraliserCommand;
import com.github.retrooper.packetevents.PacketEvents;
import org.bukkit.plugin.java.JavaPlugin;

public final class CheatNeutraliser extends JavaPlugin {
    private PacketGuard packetGuard;
    private AsyncAnalyzer analyzer;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        if (PacketEvents.getAPI() == null) {
            getLogger().severe("PacketEvents is required but its API is unavailable. Disabling CheatNeutraliser.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // PacketEvents normally owns the decoder exception path. Its default
        // safety setting can send a literal "Invalid packet" disconnect before
        // Bukkit creates a PlayerKickEvent, so cancelling PlayerKickEvent alone
        // is too late. CheatNeutraliser owns the neutralisation policy and keeps
        // PacketEvents from turning decoder exceptions into an automatic kick.
        applyPacketExceptionPolicy();

        analyzer = new AsyncAnalyzer(this);
        packetGuard = new PacketGuard(this, analyzer);
        packetGuard.start();

        CheatNeutraliserCommand command = new CheatNeutraliserCommand(this);
        if (getCommand("cheatneutraliser") != null) {
            getCommand("cheatneutraliser").setExecutor(command);
        }

        getLogger().info("CheatNeutraliser enabled: protocol safety + heuristic packet analysis + neutralisation active.");
    }

    public void applyPacketExceptionPolicy() {
        if (PacketEvents.getAPI() == null) return;
        boolean neutralise = getConfig().getBoolean("neutralisation.enabled", true)
                && "NEUTRALISE".equalsIgnoreCase(getConfig().getString("neutralisation.mode", "NEUTRALISE"));

        // PacketEvents exposes this setting specifically for packet processing
        // exceptions. In neutralise mode we do not want PacketEvents itself to
        // disconnect the player with "Invalid packet" before our plugin can
        // observe and account for the event.
        PacketEvents.getAPI().getSettings().kickOnPacketException(!neutralise);
    }

    @Override
    public void onDisable() {
        if (packetGuard != null) {
            packetGuard.stop();
        }
        if (analyzer != null) {
            analyzer.shutdown();
        }
    }

    public PacketGuard getPacketGuard() {
        return packetGuard;
    }

    public AsyncAnalyzer getAnalyzer() {
        return analyzer;
    }
}
