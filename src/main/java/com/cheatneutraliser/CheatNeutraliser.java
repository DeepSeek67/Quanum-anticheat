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
    public void onLoad() {
        PacketEvents.create(this);
        PacketEvents.getAPI().init();
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        analyzer = new AsyncAnalyzer(this);
        packetGuard = new PacketGuard(this, analyzer);
        packetGuard.start();
        getCommand("cheatneutraliser").setExecutor(new CheatNeutraliserCommand(this));
        getLogger().info("CheatNeutraliser enabled: packet safety + asynchronous neutralisation active.");
    }

    @Override
    public void onDisable() {
        if (packetGuard != null) packetGuard.stop();
        if (analyzer != null) analyzer.shutdown();
        PacketEvents.getAPI().terminate();
    }

    public PacketGuard getPacketGuard() { return packetGuard; }
    public AsyncAnalyzer getAnalyzer() { return analyzer; }
}
