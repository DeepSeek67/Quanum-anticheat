package com.cheatneutraliser.analysis;

import com.cheatneutraliser.CheatNeutraliser;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Off-thread behavioural scorer. Normal Minecraft traffic must produce zero
 * score; only corroborated anomalies increase a player's risk score.
 */
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

        // Hard protocol anomalies are intentionally rare and score heavily.
        if (s.malformed()) {
            delta += 55;
            reason = "malformed packet";
        }
        if (s.impossibleOrder()) {
            delta += 40;
            reason = "impossible packet sequence";
        }
        if (s.packetBytes() > plugin.getConfig().getInt("analysis.max-packet-bytes", 2_097_152)) {
            delta += 75;
            reason = "packet exceeds configured size";
        }

        // Rate checks alone are not enough to flag a player. Require both a high
        // rate and a meaningful action category before adding behavioural risk.
        int pps = plugin.getConfig().getInt("analysis.max-packets-per-second", 800);
        int burst = plugin.getConfig().getInt("analysis.burst-packets-per-100ms", 120);
        if (s.packetsInWindow() > pps && (s.movementPacketsInWindow() > 25 || s.combatPacketsInWindow() > 15
                || s.interactionPacketsInWindow() > 20 || s.inventoryPacketsInWindow() > 20)) {
            delta += 30;
            reason = "sustained high action rate";
        }
        if (s.recentPackets() > burst && s.samePacketStreak() >= 12) {
            delta += 25;
            reason = "repeated packet burst";
        }

        // Repeated packet timing can be suspicious when it is both extremely fast
        // and concentrated in an action family. A normal 20 TPS client should not
        // continuously send the same action packet dozens of times in a 100 ms window.
        if (s.deltaNanos() > 0 && s.deltaNanos() < 2_000_000L && s.samePacketStreak() >= 8) {
            delta += 20;
            reason = "impossible packet timing pattern";
        }

        List<String> profiles = plugin.getConfig().getStringList("client-profiles.enabled");
        if (!profiles.isEmpty() && delta > 0) {
            double strictness = 1.0D + Math.max(0.0D, plugin.getConfig().getDouble(
                    "client-profiles.additional-strictness-per-profile", 0.10D)) * profiles.size();
            int strongestBonus = 0;
            String packet = s.packetName() == null ? "" : s.packetName().toUpperCase(Locale.ROOT);
            for (String configured : profiles) {
                String name = configured == null ? "" : configured.trim().toUpperCase(Locale.ROOT);
                if (name.isEmpty()) {
                    continue;
                }
                int bonus = profileBehaviorBonus(name, packet, s);
                if (bonus > strongestBonus) {
                    strongestBonus = bonus;
                    profile = name;
                }
            }
            // Profiles refine an existing anomaly; they never turn ordinary traffic
            // into a violation by themselves.
            delta = (int) Math.ceil((delta + strongestBonus) * strictness);
        }

        final int scoreDelta = delta;
        final int decay = Math.max(0, plugin.getConfig().getInt("analysis.score-decay-per-second", 3));
        AtomicInteger score = scores.computeIfAbsent(s.playerId(), ignored -> new AtomicInteger());
        int current = score.updateAndGet(old -> Math.max(0, Math.min(100, old + scoreDelta - decay)));
        int blockScore = plugin.getConfig().getInt("analysis.block-score", 90);
        return new Decision(current, scoreDelta > 0 && current >= blockScore, reason, profile);
    }

    private static int profileBehaviorBonus(String profile, String packet, PacketSnapshot snapshot) {
        boolean movement = packet.contains("FLYING") || packet.contains("POSITION") || packet.contains("LOOK") || packet.contains("MOVE");
        boolean combat = packet.contains("USE_ENTITY") || packet.contains("ATTACK") || packet.contains("SWING");
        boolean interaction = packet.contains("BLOCK") || packet.contains("CLICK") || packet.contains("DIG") || packet.contains("USE_ITEM");
        boolean inventory = packet.contains("CONTAINER") || packet.contains("CLICK_WINDOW") || packet.contains("CREATIVE");
        boolean timing = snapshot.deltaNanos() > 0 && snapshot.deltaNanos() < 2_000_000L;

        return switch (profile) {
            case "WURST" -> (movement && timing ? 5 : 0) + (combat && timing ? 5 : 0)
                    + (interaction && timing ? 3 : 0) + (snapshot.samePacketStreak() >= 8 ? 4 : 0);
            case "PRESTIGE" -> (movement && timing ? 7 : 0) + (combat && timing ? 7 : 0)
                    + (interaction && timing ? 4 : 0) + (snapshot.samePacketStreak() >= 8 ? 5 : 0);
            case "VAPE" -> (combat && timing ? 7 : 0) + (movement && timing ? 3 : 0)
                    + (snapshot.samePacketStreak() >= 8 ? 4 : 0);
            case "IMPACT" -> (movement && timing ? 4 : 0) + (interaction && timing ? 4 : 0)
                    + (snapshot.samePacketStreak() >= 8 ? 3 : 0);
            default -> 0;
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
