package com.cheatneutraliser.packet;

import com.cheatneutraliser.CheatNeutraliser;
import com.cheatneutraliser.analysis.AsyncAnalyzer;
import com.cheatneutraliser.analysis.PacketSnapshot;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerKickEvent;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fast packet-path gate. It does only bounded, constant-time work before the
 * packet reaches Minecraft and sends richer snapshots to the async analyzer.
 */
public final class PacketGuard implements Listener {
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

        Bukkit.getPluginManager().registerEvents(this, plugin);
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

    /**
     * Packet decoder/other plugins can still request a Bukkit kick for an invalid
     * packet before our normal receive listener gets a chance to cancel it. In
     * neutralise mode, suppress that kick. The malformed packet remains rejected
     * by the protocol layer; the player is not punished for the decoder failure.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInvalidPacketKick(PlayerKickEvent event) {
        if (!plugin.getConfig().getBoolean("neutralisation.intercept-invalid-packet-kicks", true)) {
            return;
        }
        if (!plugin.getConfig().getBoolean("neutralisation.enabled", true)) {
            return;
        }
        if ("KICK".equalsIgnoreCase(plugin.getConfig().getString("neutralisation.mode", "NEUTRALISE"))) {
            return;
        }

        String reason = ChatColor.stripColor(event.getReason() == null ? "" : event.getReason());
        String lower = reason.toLowerCase(Locale.ROOT);
        boolean invalidPacket = lower.contains("invalid packet")
                || lower.contains("packet exception")
                || lower.contains("packet decoding")
                || lower.contains("failed to decode packet")
                || lower.contains("decoder exception");
        if (!invalidPacket) {
            return;
        }

        event.setCancelled(true);
        plugin.getLogger().warning("Suppressed invalid-packet kick for " + event.getPlayer().getName()
                + ": " + reason);
    }

    private final class PacketListener extends PacketListenerAbstract {
        @Override
        public void onPacketReceive(PacketReceiveEvent event) {
            Player player = event.getPlayer();
            if (player == null || event.isCancelled()) {
                return;
            }

            UUID uuid = player.getUniqueId();
            RateWindow window = windows.computeIfAbsent(uuid, ignored -> new RateWindow());
            long now = System.nanoTime();
            WindowMetrics metrics = window.record(event.getPacketName(), now);

            String packetName = event.getPacketName();
            int bytes = readableBytes(event.getByteBuf());
            boolean malformed = bytes > plugin.getConfig().getInt("analysis.max-packet-bytes", 2_097_152);
            boolean impossibleOrder = window.isImpossibleOrder(packetName);
            int pps = plugin.getConfig().getInt("analysis.max-packets-per-second", 800);
            int burst = plugin.getConfig().getInt("analysis.burst-packets-per-100ms", 120);
            boolean rateExceeded = metrics.secondCount() > pps || metrics.burstCount() > burst;
            boolean scoreExceeded = analyzer.getScore(uuid) >= plugin.getConfig().getInt("analysis.block-score", 90);

            // These checks are deliberately synchronous so a packet can be stopped
            // before normal server processing. None of them kicks the player.
            if (plugin.getConfig().getBoolean("neutralisation.block-malformed", true) && malformed) {
                event.setCancelled(true);
                logBlocked(player, packetName, "oversized packet");
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
                logBlocked(player, packetName, "adaptive behavioural threshold");
                return;
            }

            PacketSnapshot snapshot = new PacketSnapshot(
                    uuid,
                    packetName,
                    bytes,
                    now,
                    metrics.deltaNanos(),
                    metrics.secondCount(),
                    metrics.burstCount(),
                    metrics.samePacketStreak(),
                    metrics.movementCount(),
                    metrics.combatCount(),
                    metrics.interactionCount(),
                    metrics.inventoryCount(),
                    metrics.uniquePackets(),
                    packetName != null && !packetName.isBlank(),
                    malformed,
                    impossibleOrder
            );

            analyzer.analyze(snapshot).thenAccept(decision -> {
                if (!decision.block()) {
                    return;
                }
                if (plugin.getConfig().getBoolean("logging.debug", false)) {
                    plugin.getLogger().info("Behaviour risk for " + player.getName()
                            + " score=" + decision.score() + " reason=" + decision.reason()
                            + " profile=" + decision.profile());
                }
                if (shouldKick(decision)) {
                    Bukkit.getScheduler().runTask(plugin, () -> enforceKick(player, decision));
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
                ? "UNKNOWN" : decision.profile().toUpperCase(Locale.ROOT);
        String prefix = color(plugin.getConfig().getString("kick.prefix", "&f[server]"));
        String message = plugin.getConfig().getString("kick.message",
                "&f[server] &b&lSecurity check triggered. Suspected profile: &e&l%profile%&f.\n"
                        + "&fYou may join again in &e%minutes% minutes&f.");
        message = message.replace("%profile%", profile)
                .replace("%minutes%", Integer.toString(minutes))
                .replace("%player%", player.getName())
                .replace("%prefix%", prefix);

        StringBuilder kickMessage = new StringBuilder();
        if (plugin.getConfig().getBoolean("kick.title.enabled", true)) {
            kickMessage.append(color(plugin.getConfig().getString("kick.title.text", "&b&lSECURITY"))).append("\n\n");
        }
        kickMessage.append(color(message));

        String reason = prefix + " security enforcement (" + profile + ")";
        if (minutes > 0) {
            player.ban(reason, java.time.Instant.now().plusSeconds(minutes * 60L), "CheatNeutraliser", false);
        }
        player.kickPlayer(kickMessage.toString());
    }

    private static String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
    }

    private void logBlocked(Player player, String packetName, String reason) {
        if (plugin.getConfig().getBoolean("logging.blocked-packets", true)) {
            plugin.getLogger().warning("Blocked " + packetName + " from " + player.getName() + ": " + reason);
        }
    }

    private static int readableBytes(Object rawBuffer) {
        if (rawBuffer == null) return 0;
        try {
            Method method = rawBuffer.getClass().getMethod("readableBytes");
            Object result = method.invoke(rawBuffer);
            return result instanceof Number number ? Math.max(0, number.intValue()) : 0;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return 0;
        }
    }

    private static String category(String packet) {
        if (packet == null) return "OTHER";
        String p = packet.toUpperCase(Locale.ROOT);
        if (p.contains("FLYING") || p.contains("POSITION") || p.contains("ROTATION") || p.contains("LOOK") || p.contains("MOVE")) return "MOVEMENT";
        if (p.contains("USE_ENTITY") || p.contains("ATTACK") || p.contains("SWING") || p.contains("INTERACT_ENTITY")) return "COMBAT";
        if (p.contains("BLOCK") || p.contains("DIG") || p.contains("CLICK") || p.contains("USE_ITEM")) return "INTERACTION";
        if (p.contains("CONTAINER") || p.contains("WINDOW") || p.contains("CREATIVE") || p.contains("SLOT")) return "INVENTORY";
        return "OTHER";
    }

    private record WindowMetrics(long deltaNanos, int secondCount, int burstCount, int samePacketStreak,
                                 int movementCount, int combatCount, int interactionCount, int inventoryCount,
                                 int uniquePackets) {}

    private static final class RateWindow {
        volatile long secondStart = System.nanoTime();
        volatile long burstStart = secondStart;
        final AtomicInteger second = new AtomicInteger();
        final AtomicInteger burst = new AtomicInteger();
        final AtomicInteger movement = new AtomicInteger();
        final AtomicInteger combat = new AtomicInteger();
        final AtomicInteger interaction = new AtomicInteger();
        final AtomicInteger inventory = new AtomicInteger();
        final Set<String> unique = ConcurrentHashMap.newKeySet();
        volatile long lastPacketNanos;
        volatile String lastPacket = "";
        volatile int samePacketStreak;

        WindowMetrics record(String packet, long now) {
            roll(now);
            int currentSecond = second.incrementAndGet();
            int currentBurst = burst.incrementAndGet();
            String category = category(packet);
            switch (category) {
                case "MOVEMENT" -> movement.incrementAndGet();
                case "COMBAT" -> combat.incrementAndGet();
                case "INTERACTION" -> interaction.incrementAndGet();
                case "INVENTORY" -> inventory.incrementAndGet();
                default -> { }
            }
            if (packet != null) unique.add(packet);
            long delta = lastPacketNanos == 0 ? 0 : Math.max(0, now - lastPacketNanos);
            if (packet != null && packet.equals(lastPacket)) samePacketStreak++;
            else samePacketStreak = 1;
            lastPacket = packet == null ? "" : packet;
            lastPacketNanos = now;
            return new WindowMetrics(delta, currentSecond, currentBurst, samePacketStreak,
                    movement.get(), combat.get(), interaction.get(), inventory.get(), unique.size());
        }

        void roll(long now) {
            if (now - secondStart >= 1_000_000_000L) {
                synchronized (this) {
                    if (now - secondStart >= 1_000_000_000L) {
                        secondStart = now;
                        second.set(0);
                        movement.set(0);
                        combat.set(0);
                        interaction.set(0);
                        inventory.set(0);
                        unique.clear();
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
            if (packet == null) return false;
            boolean impossible = packet.contains("Login") && lastPacket.contains("Play");
            return impossible;
        }
    }
}
