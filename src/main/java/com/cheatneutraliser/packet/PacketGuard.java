package com.cheatneutraliser.packet;

import com.cheatneutraliser.CheatNeutraliser;
import com.cheatneutraliser.analysis.AsyncAnalyzer;
import com.cheatneutraliser.analysis.PacketSnapshot;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.Method;

public final class PacketGuard {
    private final CheatNeutraliser plugin;
    private final AsyncAnalyzer analyzer;
    private final ConcurrentHashMap<UUID, RateWindow> windows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Boolean> enforcement = new ConcurrentHashMap<>();
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
        enforcement.clear();
    }

    public int getTrackedPlayers() {
        return windows.size();
    }

    public void resetPlayer(UUID uuid) {
        windows.remove(uuid);
        enforcement.remove(uuid);
        analyzer.clear(uuid);
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

            // A malformed/oversized packet is NEUTRALISED, never kicked by itself.
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
                if (decision.block()) {
                    // The current packet can only be cancelled on the packet thread.
                    // The async result raises the score so subsequent packets are
                    // immediately neutralised by the fast gate above.
                    if (plugin.getConfig().getBoolean("logging.debug", false)) {
                        plugin.getLogger().info("Risk threshold reached for " + player.getName()
                                + " (score=" + decision.score() + ", reason=" + decision.reason()
                                + ", profile=" + decision.profile() + ")");
                    }

                    if (shouldKick(decision)) {
                        plugin.getServer().getScheduler().runTask(plugin, () -> enforceKick(player, decision));
                    }
                }
            });
        }
    }

    private boolean shouldKick(AsyncAnalyzer.Decision decision) {
        if (!plugin.getConfig().getBoolean("kick.enabled", false)) {
            return false;
        }
        if (!"KICK".equalsIgnoreCase(plugin.getConfig().getString("neutralisation.mode", "NEUTRALISE"))) {
            return false;
        }
        if (decision.score() < plugin.getConfig().getInt("kick.score-threshold", 95)) {
            return false;
        }
        if (plugin.getConfig().getBoolean("kick.require-profile", true)) {
            List<String> profiles = plugin.getConfig().getStringList("client-profiles.enabled");
            return !profiles.isEmpty() && !"GENERIC".equalsIgnoreCase(decision.profile());
        }
        return true;
    }

    private void enforceKick(Player player, AsyncAnalyzer.Decision decision) {
        if (!player.isOnline() || enforcement.putIfAbsent(player.getUniqueId(), Boolean.TRUE) != null) {
            return;
        }

        int minutes = Math.max(0, plugin.getConfig().getInt("kick.rejoin-delay-minutes", 5));
        String profile = decision.profile() == null || decision.profile().isBlank()
                ? "UNKNOWN"
                : decision.profile().toUpperCase(Locale.ROOT);
        String prefix = color(plugin.getConfig().getString("kick.prefix", "&f[server]"));
        String message = plugin.getConfig().getString(
                "kick.message",
                "&f[server] &b&lSecurity check triggered. Suspected profile: &e&l%profile%&f.\n&fYou may join again in &e%minutes% minutes&f."
        );
        message = message.replace("%profile%", profile)
                .replace("%minutes%", Integer.toString(minutes))
                .replace("%player%", player.getName())
                .replace("%prefix%", prefix);

        StringBuilder kickMessage = new StringBuilder();
        if (plugin.getConfig().getBoolean("kick.title.enabled", true)) {
            kickMessage.append(color(plugin.getConfig().getString("kick.title.text", "&b&lSECURITY")));
            kickMessage.append("\n\n");
        }
        kickMessage.append(color(message));

        String reason = prefix + " security enforcement (" + profile + ")";
        if (minutes > 0) {
            Instant expires = Instant.now().plusSeconds(minutes * 60L);
            // Profile bans are UUID/profile based on modern Paper, so changing a
            // username does not bypass the temporary restriction.
            player.ban(reason, expires, "CheatNeutraliser", false);
        }

        player.kickPlayer(kickMessage.toString());
        plugin.getLogger().warning("Security enforcement for " + player.getName()
                + " (profile=" + profile + ", score=" + decision.score()
                + ", rejoin-delay=" + minutes + "m)");
    }

    private static String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
    }

    /**
     * PacketEvents exposes the raw buffer as Object so plugins do not need to
     * compile against a particular Netty version.
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
