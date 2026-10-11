package de.raindancer.core.moderation.punishment;

import io.papermc.paper.connection.PlayerLoginConnection;
import io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

/**
 * Where a punishment stops being a record and starts stopping somebody.
 *
 * <p>Three lines each, deliberately: every decision is {@link PunishmentGuard}'s, which is tested
 * without a server. This only asks and applies the answer.
 *
 * <h2>Why the login check is the async pre-login one</h2>
 * {@code AsyncPlayerPreLoginEvent} fires before the player object exists, which is early enough that
 * a banned player never joins at all — no join message, no chunks loaded for them, nothing else's
 * join handler run. {@code PlayerLoginEvent} would work too and is later; this is cheaper and
 * quieter, and a ban should be as though they had never knocked.
 */
public final class PunishmentListener implements Listener {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final PunishmentGuard guard;
    private final BlockedAttempts attempts;
    private final byte[] addressKey;
    private final Server server;

    public PunishmentListener(PunishmentGuard guard) {
        this(guard, null, new byte[0], null);
    }

    /** @param attempts where what a punishment stopped is written down; null to write nothing */
    public PunishmentListener(PunishmentGuard guard, BlockedAttempts attempts, byte[] addressKey, Server server) {
        this.guard = guard;
        this.attempts = attempts;
        this.addressKey = addressKey == null ? new byte[0] : addressKey.clone();
        this.server = server;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        guard.stopping(event.getUniqueId(), PunishmentKind.BAN).ifPresent(ban -> {
            guard.joinRefusal(event.getUniqueId()).ifPresent(screen ->
                    event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, screen));
            if (attempts != null) {
                attempts.record(event.getUniqueId(), event.getName(), BlockedAttempts.Kind.JOIN, ban, "",
                        BlockedAttempts.hashAddress(addressKey,
                                event.getAddress() == null ? null : event.getAddress().getHostAddress()));
            }
        });
    }

    /**
     * Somebody the server's own ban list turned away — a ban from before this plugin, or an address ban. Ours never
     * get this far: they are refused at pre-login, above.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onValidateLogin(PlayerConnectionValidateLoginEvent event) {
        if (attempts == null || server == null || event.isAllowed()
                || !(event.getConnection() instanceof PlayerLoginConnection login)) {
            return;
        }
        com.destroystokyo.paper.profile.PlayerProfile profile = login.getAuthenticatedProfile();
        if (profile == null || profile.getId() == null) {
            return;
        }
        java.net.InetSocketAddress from = event.getConnection().getClientAddress();
        java.net.InetAddress address = from == null ? null : from.getAddress();
        boolean profileBanned = ((org.bukkit.ban.ProfileBanList) server.getBanList(io.papermc.paper.ban.BanListType.PROFILE)).isBanned(profile);
        boolean addressBanned = address != null && ((org.bukkit.ban.IpBanList) server.getBanList(io.papermc.paper.ban.BanListType.IP)).isBanned(address);
        if (!profileBanned && !addressBanned) {
            return;   // full, not whitelisted — not a punishment
        }
        String why = event.getKickMessage() == null ? "" : PLAIN.serialize(event.getKickMessage());
        attempts.record(profile.getId(), profile.getName(), BlockedAttempts.Kind.JOIN, null,
                (addressBanned && !profileBanned ? "address ban: " : "server ban list: ") + why, "",
                BlockedAttempts.hashAddress(addressKey, address == null ? null : address.getHostAddress()));
    }

    /**
     * A muted player's message never reaches anybody.
     *
     * <p>{@code ignoreCancelled} so a message another plugin has already stopped is not answered
     * twice, and early so this is decided before anything spends work formatting a message that is
     * not going to be sent.
     *
     * <p>{@code LOW} rather than {@code LOWEST} on purpose, and the one tick lower matters:
     * {@code PromptListener} sits at {@code LOWEST} and consumes lines that are answers to a
     * question rather than chat. If this ran first a muted player could not answer a settings
     * prompt or name a claim — and a mute means "you may not talk to people", not "you may not use
     * the menus". A prompt answer never reaches anybody either way.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        guard.speakRefusal(event.getPlayer().getUniqueId()).ifPresent(reason -> {
            event.setCancelled(true);
            event.getPlayer().sendMessage(reason);
            if (attempts != null) {
                attempts.record(event.getPlayer().getUniqueId(), event.getPlayer().getName(),
                        BlockedAttempts.Kind.CHAT,
                        guard.stopping(event.getPlayer().getUniqueId(), PunishmentKind.MUTE).orElse(null),
                        PLAIN.serialize(event.message()), null);
            }
        });
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        guard.buildRefusal(event.getPlayer().getUniqueId()).ifPresent(reason -> {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(reason);
            built(event.getPlayer(), "broke", event.getBlock());
        });
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        guard.buildRefusal(event.getPlayer().getUniqueId()).ifPresent(reason -> {
            event.setCancelled(true);
            // The action bar rather than chat: somebody frozen who keeps trying would otherwise
            // fill their own chat with the same line.
            event.getPlayer().sendActionBar(reason);
            built(event.getPlayer(), "placed", event.getBlockPlaced());
        });
    }

    private void built(Player player, String did, Block block) {
        if (attempts == null) {
            return;
        }
        Location at = block.getLocation();
        attempts.record(player.getUniqueId(), player.getName(), BlockedAttempts.Kind.BUILD,
                guard.stopping(player.getUniqueId(), PunishmentKind.FREEZE).orElse(null),
                did + " " + block.getType().getKey().getKey() + " at " + at.getWorld().getName() + " "
                        + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ(), null);
    }
}
