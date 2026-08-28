package com.cheatneutraliser.analysis;

import com.cheatneutraliser.CheatNeutraliser;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;

public final class AsyncAnalyzer {
    public record Decision(int score, boolean block, String reason) {}

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

        AtomicInteger score = scores.computeIfAbsent(s.playerId(), ignored -> new AtomicInteger());
        int current = score.updateAndGet(old -> Math.max(0, Math.min(100, old + delta)));
        int blockScore = plugin.getConfig().getInt("analysis.block-score", 90);
        return new Decision(current, current >= blockScore, reason);
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
