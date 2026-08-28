package com.cheatneutraliser.packet;

import com.cheatneutraliser.CheatNeutraliser;
import com.cheatneutraliser.analysis.AsyncAnalyzer;
import com.cheatneutraliser.analysis.PacketSnapshot;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class PacketGuard {
    private final CheatNeutraliser plugin;
    private final AsyncAnalyzer analyzer;
    private final ConcurrentHashMap<UUID, RateWindow> windows = new ConcurrentHashMap<>();
    private PacketListener listener;

    public PacketGuard(CheatNeutraliser plugin, AsyncAnalyzer analyzer) {
        this.plugin = plugin;
        this.analyzer = analyzer;
    }

    public void start() {
        if (PacketEvents.getAPI() == null) {
            plugin.getLogger().severe("PacketEvents API is unavailable. CheatNeutraliser will not start its packet guard.");
            return;
        }

        listener = new PacketListener();
        PacketEvents.getAPI().getEventManager().registerListener(listener);
    }

    public void stop() {
        if (listener != null && PacketEvents.getAPI() != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(listener);
            listener = null;
        }
        windows.clear();
    }

    public int getTrackedPlayers() {
        return windows.size();
    }

    private final class PacketListener extends PacketListenerAbstract {
        @Override
        public void onPacketReceive(PacketReceiveEvent event) {
            Player player = event.getPlayer();
            if (player == null) {
                return;
            }

            UUID uuid = player.getUniqueId();
            RateWindow window = windows.computeIfAbsent(uuid, ignored -> new RateWindow());

            long now = System.nanoTime();
            window.roll(now);
            int secondCount = window.second.incrementAndGet();
            int burstCount = window.burst.incrementAndGet();

            String packetName = event.getPacketName();
            int bytes = readableBytes(event.getByteBuf());

            boolean malformed = bytes > plugin.getConfig().getInt("analysis.max-packet-bytes", 2_097_152);
            boolean impossibleOrder = window.isImpossibleOrder(packetName);
            boolean rateExceeded = secondCount > plugin.getConfig().getInt("analysis.max-packets-per-second", 800)
                    || burstCount > plugin.getConfig().getInt("analysis.burst-packets-per-100ms", 120);
            boolean scoreExceeded = analyzer.getScore(uuid) >= plugin.getConfig().getInt("analysis.block-score", 90);

            if (plugin.getConfig().getBoolean("neutralisation.block-malformed", true) && malformed) {
                event.setCancelled(true);
                logBlocked(player, packetName, "malformed/oversized packet");
                return;
            }
            if (plugin.getConfig().getBoolean("neutralisation.block-rate-limit", true) && rateExceeded) {
                event.setCancelled(true);
                logBlocked(player, packetName, "packet rate limit");
                return;
            }
            if (plugin.getConfig().getBoolean("neutralisation.block-impossible-packet-order", true) && impossibleOrder) {
                event.setCancelled(true);
                logBlocked(player, packetName, "invalid packet order");
                return;
            }
            if (plugin.getConfig().getBoolean("neutralisation.enabled", true) && scoreExceeded) {
                event.setCancelled(true);
                logBlocked(player, packetName, "adaptive risk threshold");
                return;
            }

            PacketSnapshot snapshot = new PacketSnapshot(
                    uuid,
                    packetName,
                    bytes,
                    now,
                    secondCount,
                    burstCount,
                    true,
                    malformed,
                    impossibleOrder
            );

            analyzer.analyze(snapshot).thenAccept(decision -> {
                if (decision.block() && plugin.getConfig().getBoolean("logging.debug", false)) {
                    plugin.getLogger().info("Risk threshold reached for " + player.getName()
                            + " (score=" + decision.score() + ", reason=" + decision.reason() + ")");
                }
            });
        }
    }

    /**
     * PacketEvents deliberately exposes the raw buffer as Object so plugins do not
     * need to depend directly on a particular Netty version. Keep that property
     * here instead of importing Netty classes into the plugin's compile classpath.
     */
    private static int readableBytes(Object rawBuffer) {
        if (rawBuffer == null) {
            return 0;
        }

        try {
            Method method = rawBuffer.getClass().getMethod("readableBytes");
            Object result = method.invoke(rawBuffer);
            return result instanceof Number number ? Math.max(0, number.intValue()) : 0;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return 0;
        }
    }

    private void logBlocked(Player player, String packetName, String reason) {
        if (plugin.getConfig().getBoolean("logging.blocked-packets", true)) {
            plugin.getLogger().warning("Blocked " + packetName + " from " + player.getName() + ": " + reason);
        }
    }

    private static final class RateWindow {
        volatile long secondStart = System.nanoTime();
        volatile long burstStart = secondStart;
        final AtomicInteger second = new AtomicInteger();
        final AtomicInteger burst = new AtomicInteger();
        volatile String lastPacket = "";

        void roll(long now) {
            if (now - secondStart >= 1_000_000_000L) {
                synchronized (this) {
                    if (now - secondStart >= 1_000_000_000L) {
                        secondStart = now;
                        second.set(0);
                    }
                }
            }
            if (now - burstStart >= 100_000_000L) {
                synchronized (this) {
                    if (now - burstStart >= 100_000_000L) {
                        burstStart = now;
                        burst.set(0);
                    }
                }
            }
        }

        boolean isImpossibleOrder(String packet) {
            boolean impossible = packet.contains("Login") && lastPacket.contains("Play");
            lastPacket = packet;
            return impossible;
        }
    }
}
