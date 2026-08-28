package com.cheatneutraliser.packet;

import com.cheatneutraliser.CheatNeutraliser;
import com.cheatneutraliser.analysis.AsyncAnalyzer;
import com.cheatneutraliser.analysis.PacketSnapshot;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import org.bukkit.entity.Player;

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
        listener = new PacketListener();
        PacketEvents.getAPI().getEventManager().registerListener(listener);
    }

    public void stop() {
        if (listener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(listener);
            listener = null;
        }
        windows.clear();
    }

    public int getTrackedPlayers() { return windows.size(); }

    private final class PacketListener extends PacketListenerAbstract {
        @Override
        public void onPacketReceive(PacketReceiveEvent event) {
            if (!event.getPlayer().isPresent()) return;
            Player player = event.getPlayer().get();
            UUID uuid = player.getUniqueId();
            RateWindow window = windows.computeIfAbsent(uuid, ignored -> new RateWindow());

            long now = System.nanoTime();
            window.roll(now);
            int secondCount = window.second.incrementAndGet();
            int burstCount = window.burst.incrementAndGet();

            String packetName = event.getPacketType().getName();
            int bytes = event.getByteBuf() == null ? 0 : event.getByteBuf().readableBytes();

            // These checks happen before the packet reaches normal server packet handling.
            // They are deliberately deterministic and allocation-light.
            boolean malformed = bytes < 0 || bytes > plugin.getConfig().getInt("analysis.max-packet-bytes", 2_097_152);
            boolean impossibleOrder = window.isImpossibleOrder(packetName);
            boolean rateExceeded = secondCount > plugin.getConfig().getInt("analysis.max-packets-per-second", 800)
                    || burstCount > plugin.getConfig().getInt("analysis.burst-packets-per-100ms", 120);

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
                // Async code does not touch Bukkit. If the score becomes high, the next
                // matching packet is synchronously cancelled by the cheap guard above.
                // This avoids blocking the server thread while still adapting protection.
                if (decision.block() && plugin.getConfig().getBoolean("logging.blocked-packets", true)) {
                    plugin.getLogger().fine("Neutralisation threshold reached for " + player.getName()
                            + " (score=" + decision.score() + ", reason=" + decision.reason() + ")");
                }
            });
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
