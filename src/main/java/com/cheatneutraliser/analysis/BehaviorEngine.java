package com.cheatneutraliser.analysis;

import com.cheatneutraliser.CheatNeutraliser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Multi-signal behavioural engine. This class is deliberately independent of
 * Bukkit's main thread: it consumes an immutable PacketSnapshot only.
 *
 * A profile never creates evidence on its own. It can only add weight when the
 * underlying packet behaviour has already crossed a conservative gate. This
 * prevents enabling a client profile from causing false positives for vanilla
 * players.
 */
public final class BehaviorEngine {
    public record Evidence(int points, String reason, String profile, int signals) {
        public static Evidence none() {
            return new Evidence(0, "normal", "GENERIC", 0);
        }
    }

    private final CheatNeutraliser plugin;

    public BehaviorEngine(CheatNeutraliser plugin) {
        this.plugin = plugin;
    }

    public Evidence evaluate(PacketSnapshot snapshot) {
        if (snapshot == null) return Evidence.none();

        int points = 0;
        int signals = 0;
        String reason = "normal";
        String strongestProfile = "GENERIC";

        int maxBytes = plugin.getConfig().getInt("analysis.max-packet-bytes", 2_097_152);
        int pps = plugin.getConfig().getInt("analysis.max-packets-per-second", 800);
        int burst = plugin.getConfig().getInt("analysis.burst-packets-per-100ms", 120);
        int streak = plugin.getConfig().getInt("analysis.same-packet-streak", 12);
        long ultraFast = plugin.getConfig().getLong("analysis.ultra-fast-packet-nanos", 2_000_000L);

        if (snapshot.malformed()) {
            points += 60;
            signals++;
            reason = "malformed packet metadata";
        }

        if (snapshot.packetBytes() > maxBytes) {
            points += 80;
            signals++;
            reason = "packet size limit";
        }

        if (snapshot.impossibleOrder()) {
            points += 50;
            signals++;
            reason = "impossible packet sequence";
        }

        boolean actionBurst = snapshot.packetsInWindow() > pps
                && (snapshot.movementPacketsInWindow() >= 30
                || snapshot.combatPacketsInWindow() >= 20
                || snapshot.interactionPacketsInWindow() >= 25
                || snapshot.inventoryPacketsInWindow() >= 25);

        if (actionBurst) {
            points += 28;
            signals++;
            reason = "sustained action burst";
        }

        boolean repeatedBurst = snapshot.recentPackets() > burst && snapshot.samePacketStreak() >= streak;
        if (repeatedBurst) {
            points += 24;
            signals++;
            reason = "repeated packet burst";
        }

        boolean ultraFastRepeat = snapshot.deltaNanos() > 0
                && snapshot.deltaNanos() < ultraFast
                && snapshot.samePacketStreak() >= Math.max(4, streak / 2);
        if (ultraFastRepeat) {
            points += 22;
            signals++;
            reason = "repeated ultra-fast packet cadence";
        }

        // A single timing sample is weak evidence. Require two independent
        // characteristics before client profiles are allowed to add weight.
        if (signals == 0) return Evidence.none();

        List<String> profiles = plugin.getConfig().getStringList("client-profiles.enabled");
        Set<String> uniqueProfiles = new HashSet<>();
        int bestProfileScore = 0;

        for (String configured : profiles) {
            if (configured == null || configured.isBlank()) continue;
            String profile = configured.trim().toUpperCase(Locale.ROOT);
            if (!uniqueProfiles.add(profile)) continue;

            int profileScore = profileScore(profile, snapshot, actionBurst, repeatedBurst, ultraFastRepeat);
            if (profileScore > 0 && profileScore > bestProfileScore) {
                bestProfileScore = profileScore;
                strongestProfile = profile;
            }
        }

        // Multiple profiles do not blindly multiply the score. Each profile must
        // independently match a real anomaly, and only the strongest match is
        // selected for attribution. A small corroboration bonus is applied when
        // several different profiles agree with the same evidence.
        int agreeingProfiles = 0;
        for (String configured : uniqueProfiles) {
            int profileScore = profileScore(configured, snapshot, actionBurst, repeatedBurst, ultraFastRepeat);
            if (profileScore > 0) agreeingProfiles++;
        }

        if (bestProfileScore > 0) {
            points += bestProfileScore;
            if (agreeingProfiles > 1) {
                points += Math.min(15, (agreeingProfiles - 1) * 5);
                reason = reason + " + profile corroboration";
            }
        }

        int minimumSignals = Math.max(1, plugin.getConfig().getInt("analysis.minimum-independent-signals", 2));
        if (signals < minimumSignals && points < 70) {
            return Evidence.none();
        }

        return new Evidence(Math.min(100, points), reason, strongestProfile, signals);
    }

    private int profileScore(String profile, PacketSnapshot s, boolean actionBurst,
                             boolean repeatedBurst, boolean ultraFastRepeat) {
        List<String> checks = plugin.getConfig().getStringList("client-profiles.rules." + profile);
        if (checks.isEmpty()) {
            checks = defaultChecks(profile);
        }

        int score = 0;
        for (String check : checks) {
            if (check == null) continue;
            String normalized = check.trim().toLowerCase(Locale.ROOT);
            switch (normalized) {
                case "movement-timing" -> {
                    if (isMovement(s) && ultraFastRepeat) score += 8;
                }
                case "combat-timing" -> {
                    if (isCombat(s) && ultraFastRepeat) score += 10;
                }
                case "interaction-burst" -> {
                    if (s.interactionPacketsInWindow() >= 20 && (actionBurst || repeatedBurst)) score += 8;
                }
                case "inventory-burst" -> {
                    if (s.inventoryPacketsInWindow() >= 20 && (actionBurst || repeatedBurst)) score += 7;
                }
                case "repeated-actions" -> {
                    if (repeatedBurst) score += 8;
                }
                case "high-action-rate" -> {
                    if (actionBurst) score += 8;
                }
                case "impossible-order" -> {
                    if (s.impossibleOrder()) score += 12;
                }
                case "oversized-packet" -> {
                    if (s.malformed() || s.packetBytes() > plugin.getConfig().getInt("analysis.max-packet-bytes", 2_097_152)) score += 12;
                }
                default -> { }
            }
        }
        return Math.min(30, score);
    }

    private static List<String> defaultChecks(String profile) {
        return switch (profile) {
            case "WURST" -> List.of("movement-timing", "interaction-burst", "repeated-actions", "high-action-rate");
            case "PRESTIGE" -> List.of("combat-timing", "movement-timing", "repeated-actions", "high-action-rate");
            case "VAPE" -> List.of("combat-timing", "repeated-actions", "high-action-rate");
            case "IMPACT" -> List.of("movement-timing", "interaction-burst", "repeated-actions");
            default -> List.of("repeated-actions", "high-action-rate");
        };
    }

    private static boolean isMovement(PacketSnapshot s) {
        String packet = name(s);
        return packet.contains("FLYING") || packet.contains("POSITION")
                || packet.contains("ROTATION") || packet.contains("LOOK") || packet.contains("MOVE");
    }

    private static boolean isCombat(PacketSnapshot s) {
        String packet = name(s);
        return packet.contains("USE_ENTITY") || packet.contains("ATTACK")
                || packet.contains("SWING") || packet.contains("INTERACT_ENTITY");
    }

    private static String name(PacketSnapshot s) {
        return s.packetName() == null ? "" : s.packetName().toUpperCase(Locale.ROOT);
    }
}
