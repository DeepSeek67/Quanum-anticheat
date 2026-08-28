package com.cheatneutraliser.analysis;

import com.cheatneutraliser.CheatNeutraliser;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Off-thread analysis coordinator. The packet callback never performs the
 * expensive profile evaluation itself. Only immutable snapshots cross the
 * executor boundary.
 */
public final class AsyncAnalyzer {
    public record Decision(int score, boolean block, String reason, String profile, int signals) {}

    private final CheatNeutraliser plugin;
    private final ExecutorService executor;
    private final BehaviorEngine behaviorEngine;
    private final ConcurrentHashMap<UUID, AtomicInteger> scores = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastEvidenceNanos = new ConcurrentHashMap<>();

    public AsyncAnalyzer(CheatNeutraliser plugin) {
        this.plugin = plugin;
        this.behaviorEngine = new BehaviorEngine(plugin);

        int threads = Math.max(1, plugin.getConfig().getInt("analysis.worker-threads", 2));
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "CheatNeutraliser-Analyzer");
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newFixedThreadPool(threads, factory);
    }

    public CompletableFuture<Decision> analyze(PacketSnapshot snapshot) {
        return CompletableFuture.supplyAsync(() -> evaluate(snapshot), executor);
    }

    private Decision evaluate(PacketSnapshot snapshot) {
        BehaviorEngine.Evidence evidence = behaviorEngine.evaluate(snapshot);
        AtomicInteger score = scores.computeIfAbsent(snapshot.playerId(), ignored -> new AtomicInteger());

        int decay = Math.max(0, plugin.getConfig().getInt("analysis.score-decay-per-second", 6));
        long now = snapshot.nowNanos();
        long previous = lastEvidenceNanos.getOrDefault(snapshot.playerId(), now);
        long elapsed = Math.max(0L, now - previous);
        int elapsedSeconds = (int) Math.min(10L, elapsed / 1_000_000_000L);
        int decayAmount = evidence.points() == 0 ? decay * Math.max(1, elapsedSeconds) : decay * elapsedSeconds;

        int delta = evidence.points();
        final int scoreDelta = delta;
        int current = score.updateAndGet(old -> {
            int afterDecay = Math.max(0, old - decayAmount);
            return Math.max(0, Math.min(100, afterDecay + scoreDelta));
        });

        if (evidence.points() > 0) {
            lastEvidenceNanos.put(snapshot.playerId(), now);
        }

        int blockScore = Math.max(1, Math.min(100,
                plugin.getConfig().getInt("analysis.block-score", 90)));
        boolean block = evidence.points() > 0 && current >= blockScore;

        return new Decision(current, block, evidence.reason(), evidence.profile(), evidence.signals());
    }

    public int getScore(UUID playerId) {
        AtomicInteger value = scores.get(playerId);
        return value == null ? 0 : value.get();
    }

    public void clear(UUID playerId) {
        scores.remove(playerId);
        lastEvidenceNanos.remove(playerId);
    }

    public void shutdown() {
        executor.shutdownNow();
        scores.clear();
        lastEvidenceNanos.clear();
    }
}
