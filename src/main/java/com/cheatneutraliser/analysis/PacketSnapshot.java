package com.cheatneutraliser.analysis;

import java.util.UUID;

public record PacketSnapshot(
        UUID playerId,
        String packetName,
        int packetBytes,
        long nowNanos,
        int packetsInWindow,
        int recentPackets,
        boolean knownMinecraftPacket,
        boolean malformed,
        boolean impossibleOrder
) {}
