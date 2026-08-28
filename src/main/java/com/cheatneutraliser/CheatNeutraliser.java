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

        analyzer = new AsyncAnalyzer(this);
        packetGuard = new PacketGuard(this, analyzer);
        packetGuard.start();

        CheatNeutraliserCommand command = new CheatNeutraliserCommand(this);
        if (getCommand("cheatneutraliser") != null) {
            getCommand("cheatneutraliser").setExecutor(command);
        }

        getLogger().info("CheatNeutraliser enabled: packet safety + asynchronous neutralisation active.");
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
