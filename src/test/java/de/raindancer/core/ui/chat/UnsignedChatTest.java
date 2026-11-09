package de.raindancer.core.ui.chat;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundChatSessionUpdatePacket;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import net.minecraft.network.protocol.status.ServerStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("unsigned chat: no chat reports, no 'can't be verified' warning")
class UnsignedChatTest {

    private final UnsignedChat chat = new UnsignedChat(getClass().getClassLoader(), () -> true);

    @Test
    @DisplayName("the game tells the client the server enforces secure chat, so it shows no warning")
    void loginClaimsSecureChat() {
        ClientboundLoginPacket login = new ClientboundLoginPacket(7, false, Set.of("overworld"), true, false);

        Object sent = chat.outbound(login);

        assertThat(sent).isEqualTo(new ClientboundLoginPacket(7, false, Set.of("overworld"), true, true));
    }

    @Test
    @DisplayName("a login that already says so goes out as it was")
    void loginAlreadySecure() {
        ClientboundLoginPacket login = new ClientboundLoginPacket(7, false, Set.of(), true, true);
        assertThat(chat.outbound(login)).isSameAs(login);
    }

    @Test
    @DisplayName("player chat goes out as a system line, decorated as the chat type would, nothing left to report")
    void playerChatBecomesSystemChat() {
        ClientboundPlayerChatPacket said = new ClientboundPlayerChatPacket(UUID.randomUUID(),
                new ClientboundPlayerChatPacket.Body("hi", 1L), Optional.empty(),
                new ClientboundPlayerChatPacket.Bound("Hydr0Drag0n"));

        Object sent = chat.outbound(said);

        assertThat(sent).isInstanceOf(ClientboundSystemChatPacket.class);
        ClientboundSystemChatPacket line = (ClientboundSystemChatPacket) sent;
        assertThat(line.content().text()).isEqualTo("<Hydr0Drag0n> hi");
        assertThat(line.overlay()).isFalse();
    }

    @Test
    @DisplayName("what a chat plugin made of the message is what is shown, not the signed original")
    void unsignedContentWins() {
        ClientboundPlayerChatPacket said = new ClientboundPlayerChatPacket(UUID.randomUUID(),
                new ClientboundPlayerChatPacket.Body("hi", 1L), Optional.of(Component.literal("✦ hi")),
                new ClientboundPlayerChatPacket.Bound("Rain"));

        ClientboundSystemChatPacket line = (ClientboundSystemChatPacket) chat.outbound(said);

        assertThat(line.content().text()).isEqualTo("<Rain> ✦ hi");
    }

    @Test
    @DisplayName("the server list says secure chat too")
    void statusClaimsSecureChat() {
        ClientboundStatusResponsePacket status = new ClientboundStatusResponsePacket(
                new ServerStatus("Hyades", Optional.empty(), false));

        Object sent = chat.outbound(status);

        assertThat(((ClientboundStatusResponsePacket) sent).status().enforcesSecureChat()).isTrue();
        assertThat(((ClientboundStatusResponsePacket) sent).status().description()).isEqualTo("Hyades");
    }

    @Test
    @DisplayName("the client's chat key never reaches the server, so nothing it says is signed")
    void chatSessionIsDropped() {
        assertThat(chat.dropInbound(new ServerboundChatSessionUpdatePacket("key"))).isTrue();
        assertThat(chat.dropInbound("anything else")).isFalse();
    }

    @Test
    @DisplayName("everything else goes out untouched")
    void othersUntouched() {
        Object other = new ClientboundSystemChatPacket(Component.literal("x"), true);
        assertThat(chat.outbound(other)).isSameAs(other);
    }

    @Test
    @DisplayName("switched off, nothing is changed")
    void off() {
        UnsignedChat offChat = new UnsignedChat(getClass().getClassLoader(), () -> false);
        ClientboundLoginPacket login = new ClientboundLoginPacket(7, false, Set.of(), true, false);

        assertThat(offChat.outbound(login)).isSameAs(login);
        assertThat(offChat.dropInbound(new ServerboundChatSessionUpdatePacket("key"))).isFalse();
    }

    @Test
    @DisplayName("on a server whose classes do not fit, it stands down instead of breaking connections")
    void doesNotFit() {
        UnsignedChat broken = new UnsignedChat(new ClassLoader(null) { }, () -> true);
        Object login = new ClientboundLoginPacket(7, false, Set.of(), true, false);

        assertThat(broken.works()).isFalse();
        assertThat(broken.outbound(login)).isSameAs(login);
    }
}
