package de.raindancer.core.ui.tablist;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.UUID;

/**
 * Keeps players on tab lists while only their body is hidden — see {@link TabKeep} for which. A handler in each
 * player's Netty pipeline swallows the tab-list removal {@code hidePlayer} sends; reflective, since Core compiles
 * against the API only. On a server it does not fit, hidden players simply drop off tab lists as before.
 */
public final class TabKeeper {

    private static final LogChannel log = Log.of("tablist");
    private static final String HANDLER = "rains_tab_keeper";
    private static final String PACKET_HANDLER = "packet_handler";
    private static final String REMOVE = "net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket";
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.publicLookup();

    private final TabKeep keep;
    private volatile boolean broken;
    private volatile Class<?> removeType;
    private volatile MethodHandle newRemove;
    private volatile MethodHandle removedIds;

    public TabKeeper(TabKeep keep) {
        this.keep = keep;
    }

    public TabKeep keep() {
        return keep;
    }

    public void inject(Player player) {
        if (broken) {
            return;
        }
        try {
            Channel channel = channelOf(player);
            if (removeType == null) {
                Object handle = player.getClass().getMethod("getHandle").invoke(player);
                Class<?> type = Class.forName(REMOVE, true, handle.getClass().getClassLoader());
                newRemove = LOOKUP.findConstructor(type, MethodType.methodType(void.class, List.class));
                removedIds = LOOKUP.findVirtual(type, "profileIds", MethodType.methodType(List.class));
                removeType = type;
            }
            UUID viewer = player.getUniqueId();
            channel.eventLoop().execute(() -> {
                try {
                    if (channel.pipeline().get(HANDLER) != null) {
                        channel.pipeline().remove(HANDLER);
                    }
                    if (channel.pipeline().get(PACKET_HANDLER) != null) {
                        channel.pipeline().addBefore(PACKET_HANDLER, HANDLER, new Handler(viewer));
                    } else {
                        channel.pipeline().addLast(HANDLER, new Handler(viewer));
                    }
                } catch (RuntimeException failed) {
                    log.warn("Could not keep {}'s tab list: {}", player.getName(), failed.toString());
                }
            });
        } catch (ReflectiveOperationException | RuntimeException failed) {
            broken = true;
            log.warn("The tab keeper does not fit this server version ({}); players in private claims drop off "
                    + "tab lists while out of sight.", failed.toString());
        }
    }

    /** Takes the leaving player off the lists still showing them; Paper sends a hidden player's removal to nobody. */
    public void quit(Player player) {
        for (UUID viewer : keep.quit(player.getUniqueId())) {
            Player watching = Bukkit.getPlayer(viewer);
            if (watching != null) {
                unlist(watching, player.getUniqueId());
            }
        }
    }

    /** Removes kept entries whose reason ended while the body is still hidden — somebody who vanished meanwhile. */
    public void sweep() {
        for (TabKeep.Listing listing : keep.stale()) {
            Player viewer = Bukkit.getPlayer(listing.viewer());
            Player target = Bukkit.getPlayer(listing.target());
            if (viewer != null && (target == null || !viewer.canSee(target))) {
                unlist(viewer, listing.target());
            }
        }
    }

    private void unlist(Player viewer, UUID target) {
        MethodHandle make = newRemove;
        if (broken || make == null) {
            return;
        }
        try {
            Channel channel = channelOf(viewer);
            Object packet = make.invoke(List.of(target));
            channel.eventLoop().execute(() -> {
                // From our own handler onwards, so it is not swallowed by it.
                ChannelHandlerContext ours = channel.pipeline().context(HANDLER);
                if (ours != null) {
                    ours.writeAndFlush(packet, channel.voidPromise());
                } else {
                    channel.writeAndFlush(packet, channel.voidPromise());
                }
            });
        } catch (Throwable unexpected) {
            log.debug("Could not take {} off {}'s tab list: {}", target, viewer.getName(), unexpected.toString());
        }
    }

    public boolean broken() {
        return broken;
    }

    private static Channel channelOf(Player player) throws ReflectiveOperationException {
        Object handle = player.getClass().getMethod("getHandle").invoke(player);
        Object listener = handle.getClass().getField("connection").get(handle);
        Object connection = listener.getClass().getField("connection").get(listener);
        return (Channel) connection.getClass().getField("channel").get(connection);
    }

    private final class Handler extends ChannelDuplexHandler {

        private final UUID viewer;

        Handler(UUID viewer) {
            this.viewer = viewer;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            try {
                if (msg.getClass() == removeType) {
                    List<UUID> ids = ((List<?>) removedIds.invoke(msg)).stream()
                            .filter(UUID.class::isInstance).map(UUID.class::cast).toList();
                    if (keep.drop(viewer, ids)) {
                        promise.trySuccess();
                        return;
                    }
                }
            } catch (Throwable unexpected) {
                // Throwing here would take the player's connection with it.
            }
            super.write(ctx, msg, promise);
        }
    }
}
