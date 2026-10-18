package de.raindancer.core.platform.permission;

import de.raindancer.core.platform.network.NetworkBus;
import de.raindancer.core.platform.network.NetworkEvent;
import net.luckperms.api.messenger.IncomingMessageConsumer;
import net.luckperms.api.messenger.Messenger;
import net.luckperms.api.messenger.message.OutgoingMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** LuckPerms' update messages ride on the network bus, so no Redis and no proxy plugin is needed. */
class LuckPermsBusMessengerTest {

    private static final class RecordingBus implements NetworkBus {
        final List<String> published = new ArrayList<>();
        final List<Consumer<NetworkEvent>> handlers = new ArrayList<>();
        boolean closed;

        @Override
        public String server() {
            return "smp";
        }

        @Override
        public boolean publish(String topic, String key, String payload) {
            published.add(topic + "|" + payload);
            return true;
        }

        @Override
        public Subscription subscribe(String topic, Consumer<NetworkEvent> handler) {
            handlers.add(handler);
            return () -> closed = true;
        }

        @Override
        public void close() {
        }
    }

    @Test
    @DisplayName("an outgoing message is published on the luckperms topic")
    void outgoing() {
        RecordingBus bus = new RecordingBus();
        Messenger messenger = new LuckPermsBusMessenger(bus).obtain(mock(IncomingMessageConsumer.class));
        OutgoingMessage message = mock(OutgoingMessage.class);
        when(message.asEncodedString()).thenReturn("{\"type\":\"update\"}");

        messenger.sendOutgoingMessage(message);

        assertThat(bus.published).containsExactly(LuckPermsBusMessenger.TOPIC + "|{\"type\":\"update\"}");
    }

    @Test
    @DisplayName("what another server published reaches LuckPerms, and closing stops listening")
    void incoming() {
        RecordingBus bus = new RecordingBus();
        IncomingMessageConsumer consumer = mock(IncomingMessageConsumer.class);
        Messenger messenger = new LuckPermsBusMessenger(bus).obtain(consumer);

        bus.handlers.getFirst().accept(new NetworkEvent(1, 0, "hub", LuckPermsBusMessenger.TOPIC, "", "{\"x\":1}"));
        messenger.close();

        verify(consumer).consumeIncomingMessageAsString("{\"x\":1}");
        assertThat(bus.closed).isTrue();
    }
}
