package com.cheatneutraliser.analysis;

import java.util.UUID;

/**
 * Immutable, cheap-to-create snapshot of packet behaviour. The packet thread only
 * collects counters/timing; expensive scoring happens off-thread.
 */
public record PacketSnapshot(
        UUID playerId,
        String packetName,
        int packetBytes,
        long nowNanos,
        long deltaNanos,
        int packetsInWindow,
        int recentPackets,
        int samePacketStreak,
        int movementPacketsInWindow,
        int combatPacketsInWindow,
        int interactionPacketsInWindow,
        int inventoryPacketsInWindow,
        int uniquePacketsInWindow,
        boolean knownMinecraftPacket,
        boolean malformed,
        boolean impossibleOrder
) {}
