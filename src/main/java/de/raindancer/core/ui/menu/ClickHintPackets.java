package de.raindancer.core.ui.menu;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Draws click hints ({@link ClickHints}) in what reaches a player beyond Core's menus: chat — buttons, their hovers,
 * the action bar — and every item they see in an inventory, as the packets leave. The items on the server keep their
 * words, so nothing is ever stored with an icon, and whoever has no pack keeps reading them.
 *
 * <p>Not for players in creative: their client sends the items it was shown back as the real ones, icons and all.
 * Reflective, since Core compiles against the API only; on a server it does not fit, the hints stay words.
 */
public final class ClickHintPackets {

    private static final LogChannel log = Log.of("gui");
    private static final String HANDLER = "rains_click_hints";
    private static final String PACKET_HANDLER = "packet_handler";
    private static final String GAME = "net.minecraft.network.protocol.game.";

    private volatile boolean broken;
    private volatile Types types;

    /** The server's own classes and how to take their packets apart and build them again. */
    private record Types(
            Class<?> chat, Method chatContent, Method chatOverlay, Constructor<?> newChat, Method asAdventure,
            Class<?> content, Method contentId, Method contentState, Method contentItems, Method contentCarried,
            Constructor<?> newContent,
            Class<?> slot, Method slotId, Method slotState, Method slotSlot, Method slotItem, Constructor<?> newSlot,
            Class<?> inventory, Method inventorySlot, Method inventoryItem, Constructor<?> newInventory,
            Class<?> cursor, Method cursorItem, Constructor<?> newCursor,
            Method asBukkit, Method asServer) {
    }

    public void inject(Player player) {
        if (broken) {
            return;
        }
        try {
            Channel channel = channelOf(player);
            if (types == null) {
                types = resolve(player.getClass().getMethod("getHandle").invoke(player).getClass().getClassLoader(),
                        player.getClass().getClassLoader());
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
                    log.warn("No click icons in chat and items for {}: {}", player.getName(), failed.toString());
                }
            });
        } catch (ReflectiveOperationException | RuntimeException failed) {
            broken = true;
            log.warn("Click icons do not fit this server version in chat and items ({}); menus still have them.",
                    failed.toString());
        }
    }

    public void eject(Player player) {
        try {
            Channel channel = channelOf(player);
            channel.eventLoop().execute(() -> {
                if (channel.pipeline().get(HANDLER) != null) {
                    channel.pipeline().remove(HANDLER);
                }
            });
        } catch (ReflectiveOperationException | RuntimeException gone) {
            // Already disconnected: the pipeline went with it.
        }
    }

    public boolean broken() {
        return broken;
    }

    private static Types resolve(ClassLoader server, ClassLoader api) throws ReflectiveOperationException {
        Class<?> nmsComponent = Class.forName("net.minecraft.network.chat.Component", true, server);
        Class<?> nmsItem = Class.forName("net.minecraft.world.item.ItemStack", true, server);
        Class<?> chat = Class.forName(GAME + "ClientboundSystemChatPacket", true, server);
        Class<?> content = Class.forName(GAME + "ClientboundContainerSetContentPacket", true, server);
        Class<?> slot = Class.forName(GAME + "ClientboundContainerSetSlotPacket", true, server);
        Class<?> inventory = Class.forName(GAME + "ClientboundSetPlayerInventoryPacket", true, server);
        Class<?> cursor = Class.forName(GAME + "ClientboundSetCursorItemPacket", true, server);
        Class<?> adventure = Class.forName("io.papermc.paper.adventure.PaperAdventure", true, server);
        Class<?> craftItem = Class.forName("org.bukkit.craftbukkit.inventory.CraftItemStack", true, server);
        Method asBukkit = null;
        for (Method method : craftItem.getMethods()) {
            // Takes an ItemInstance on 26.x, an ItemStack before: either way the one-argument copy to Bukkit.
            if (method.getName().equals("asBukkitCopy") && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isAssignableFrom(nmsItem)) {
                asBukkit = method;
            }
        }
        if (asBukkit == null) {
            throw new NoSuchMethodException("CraftItemStack.asBukkitCopy");
        }
        return new Types(
                chat, chat.getMethod("content"), chat.getMethod("overlay"),
                chat.getConstructor(Component.class, boolean.class),
                adventure.getMethod("asAdventure", nmsComponent),
                content, content.getMethod("containerId"), content.getMethod("stateId"), content.getMethod("items"),
                content.getMethod("carriedItem"), content.getConstructor(int.class, int.class, List.class, nmsItem),
                slot, slot.getMethod("getContainerId"), slot.getMethod("getStateId"), slot.getMethod("getSlot"),
                slot.getMethod("getItem"), slot.getConstructor(int.class, int.class, int.class, nmsItem),
                inventory, inventory.getMethod("slot"), inventory.getMethod("contents"),
                inventory.getConstructor(int.class, nmsItem),
                cursor, cursor.getMethod("contents"), cursor.getConstructor(nmsItem),
                asBukkit, craftItem.getMethod("asNMSCopy", org.bukkit.inventory.ItemStack.class));
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
            Object sent = msg;
            try {
                Types t = types;
                ClickHints.Audience audience = ClickHints.audience(viewer);
                if (t != null && audience != ClickHints.Audience.NONE) {
                    sent = redraw(t, msg, audience);
                }
            } catch (Throwable unexpected) {
                // Throwing here would take the player's connection with it; the packet goes as it was.
                sent = msg;
            }
            super.write(ctx, sent, promise);
        }

        private Object redraw(Types t, Object msg, ClickHints.Audience audience) throws ReflectiveOperationException {
            Class<?> type = msg.getClass();
            if (type == t.chat()) {
                Component said = (Component) t.asAdventure().invoke(null, t.chatContent().invoke(msg));
                Component drawn = ClickHints.rewrite(said, audience);
                return drawn == said ? msg : t.newChat().newInstance(drawn, t.chatOverlay().invoke(msg));
            }
            if (type != t.content() && type != t.slot() && type != t.inventory() && type != t.cursor()) {
                return msg;
            }
            Player player = Bukkit.getPlayer(viewer);
            if (player == null || player.getGameMode() == GameMode.CREATIVE) {
                return msg;
            }
            if (type == t.content()) {
                List<?> items = (List<?>) t.contentItems().invoke(msg);
                List<Object> drawn = new ArrayList<>(items.size());
                boolean changed = false;
                for (Object item : items) {
                    Object one = item(t, item, audience);
                    changed |= one != item;
                    drawn.add(one);
                }
                Object carried = t.contentCarried().invoke(msg);
                Object carriedDrawn = item(t, carried, audience);
                if (!changed && carriedDrawn == carried) {
                    return msg;
                }
                return t.newContent().newInstance(t.contentId().invoke(msg), t.contentState().invoke(msg), drawn,
                        carriedDrawn);
            }
            if (type == t.slot()) {
                Object item = t.slotItem().invoke(msg);
                Object drawn = item(t, item, audience);
                return drawn == item ? msg : t.newSlot().newInstance(t.slotId().invoke(msg), t.slotState().invoke(msg),
                        t.slotSlot().invoke(msg), drawn);
            }
            if (type == t.inventory()) {
                Object item = t.inventoryItem().invoke(msg);
                Object drawn = item(t, item, audience);
                return drawn == item ? msg : t.newInventory().newInstance(t.inventorySlot().invoke(msg), drawn);
            }
            Object item = t.cursorItem().invoke(msg);
            Object drawn = item(t, item, audience);
            return drawn == item ? msg : t.newCursor().newInstance(drawn);
        }

        /** The server's item as the player is shown it: the same object when nothing in it changes. */
        private Object item(Types t, Object item, ClickHints.Audience audience) throws ReflectiveOperationException {
            if (item == null) {
                return null;
            }
            org.bukkit.inventory.ItemStack bukkit = (org.bukkit.inventory.ItemStack) t.asBukkit().invoke(null, item);
            org.bukkit.inventory.ItemStack drawn = ClickHints.rewrite(bukkit, audience);
            return drawn == bukkit ? item : t.asServer().invoke(null, drawn);
        }
    }
}
