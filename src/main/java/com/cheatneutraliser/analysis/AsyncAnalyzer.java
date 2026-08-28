package com.cheatneutraliser.analysis;

import com.cheatneutraliser.CheatNeutraliser;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;

public final class AsyncAnalyzer {
    public record Decision(int score, boolean block, String reason, String profile) {}

    private final CheatNeutraliser plugin;
    private final ExecutorService executor;
    private final ConcurrentHashMap<UUID, AtomicInteger> scores = new ConcurrentHashMap<>();

    public AsyncAnalyzer(CheatNeutraliser plugin) {
        this.plugin = plugin;
        int threads = Math.max(1, plugin.getConfig().getInt("analysis.worker-threads", 2));
        this.executor = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "CheatNeutraliser-Analyzer");
            thread.setDaemon(true);
            return thread;
        });
    }

    public CompletableFuture<Decision> analyze(PacketSnapshot snapshot) {
        return CompletableFuture.supplyAsync(() -> evaluate(snapshot), executor);
    }

    private Decision evaluate(PacketSnapshot s) {
        int delta = 0;
        String reason = "normal";
        String profile = "GENERIC";

        if (!s.knownMinecraftPacket()) {
            delta += 40;
            reason = "unknown packet type";
        }
        if (s.malformed()) {
            delta += 60;
            reason = "malformed packet";
        }
        if (s.impossibleOrder()) {
            delta += 45;
            reason = "invalid packet order";
        }
        if (s.packetBytes() > plugin.getConfig().getInt("analysis.max-packet-bytes", 2_097_152)) {
            delta += 80;
            reason = "packet exceeds configured size";
        }
        if (s.packetsInWindow() > plugin.getConfig().getInt("analysis.max-packets-per-second", 800)) {
            delta += 45;
            reason = "packet rate exceeded";
        }
        if (s.recentPackets() > plugin.getConfig().getInt("analysis.burst-packets-per-100ms", 120)) {
            delta += 35;
            reason = "packet burst exceeded";
        }

        List<String> profiles = plugin.getConfig().getStringList("client-profiles.enabled");
        double strictness = 1.0D;
        if (!profiles.isEmpty()) {
            strictness += Math.max(0.0D, plugin.getConfig().getDouble(
                    "client-profiles.additional-strictness-per-profile", 0.10D)) * profiles.size();

            String packet = s.packetName() == null ? "" : s.packetName().toUpperCase(Locale.ROOT);
            int profileBonus = 0;
            for (String configured : profiles) {
                String name = configured == null ? "" : configured.trim().toUpperCase(Locale.ROOT);
                if (name.isEmpty()) {
                    continue;
                }
                profile = name;
                profileBonus += profileBehaviorBonus(name, packet, s);
            }
            delta += profileBonus;
        }

        delta = (int) Math.ceil(delta * strictness);

        final int scoreDelta = delta;
        final String decisionReason = reason;
        final String suspectedProfile = profile;

        AtomicInteger score = scores.computeIfAbsent(s.playerId(), ignored -> new AtomicInteger());
        int current = score.updateAndGet(old -> Math.max(0, Math.min(100, old + scoreDelta)));
        int blockScore = plugin.getConfig().getInt("analysis.block-score", 90);
        return new Decision(current, current >= blockScore, decisionReason, suspectedProfile);
    }

    private static int profileBehaviorBonus(String profile, String packet, PacketSnapshot snapshot) {
        boolean movement = packet.contains("FLYING") || packet.contains("POSITION") || packet.contains("LOOK") || packet.contains("MOVE");
        boolean combat = packet.contains("USE_ENTITY") || packet.contains("ATTACK") || packet.contains("SWING");
        boolean interaction = packet.contains("BLOCK") || packet.contains("CLICK") || packet.contains("DIG") || packet.contains("USE_ITEM");
        boolean burst = snapshot.recentPackets() > 60;

        return switch (profile) {
            case "WURST" -> (movement ? 3 : 0) + (combat ? 3 : 0) + (interaction ? 2 : 0) + (burst ? 4 : 0);
            case "PRESTIGE" -> (movement ? 5 : 0) + (combat ? 5 : 0) + (interaction ? 3 : 0) + (burst ? 6 : 0);
            case "VAPE" -> (combat ? 5 : 0) + (movement ? 2 : 0) + (burst ? 4 : 0);
            case "IMPACT" -> (movement ? 3 : 0) + (interaction ? 3 : 0) + (burst ? 3 : 0);
            default -> (movement || combat || interaction) ? 1 : 0;
        };
    }

    public int getScore(UUID playerId) {
        AtomicInteger value = scores.get(playerId);
        return value == null ? 0 : value.get();
    }

    public void clear(UUID playerId) { scores.remove(playerId); }

    public void shutdown() {
        executor.shutdownNow();
        scores.clear();
    }
}
