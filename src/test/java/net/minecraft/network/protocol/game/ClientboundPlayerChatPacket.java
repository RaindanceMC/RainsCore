package net.minecraft.network.protocol.game;

import net.minecraft.network.chat.Component;

import java.util.Optional;
import java.util.UUID;

/** Stand-in for the signed player chat packet. */
public record ClientboundPlayerChatPacket(UUID sender, Body body, Optional<Component> unsignedContent, Bound chatType) {

    public record Body(String content, long timeStamp) {
    }

    public record Bound(String name) {
        public Component decorate(Component content) {
            return Component.literal("<" + name + "> " + content.text());
        }
    }
}
