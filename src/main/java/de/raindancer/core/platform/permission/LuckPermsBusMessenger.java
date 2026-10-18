package de.raindancer.core.platform.permission;

import de.raindancer.core.platform.network.NetworkBus;
import net.luckperms.api.messenger.IncomingMessageConsumer;
import net.luckperms.api.messenger.Messenger;
import net.luckperms.api.messenger.MessengerProvider;
import net.luckperms.api.messenger.message.OutgoingMessage;

/**
 * Carries LuckPerms' "something changed, sync" messages over the network bus.
 *
 * <p>Only used where LuckPerms' config says {@code messaging-service: custom}. With every server's
 * LuckPerms on the same database, that is all a network needs — no Redis, no LuckPerms on the proxy.
 */
public final class LuckPermsBusMessenger implements MessengerProvider {

    public static final String TOPIC = "luckperms";

    private final NetworkBus bus;

    public LuckPermsBusMessenger(NetworkBus bus) {
        this.bus = bus;
    }

    @Override
    public String getName() {
        return "rains-network";
    }

    @Override
    public Messenger obtain(IncomingMessageConsumer consumer) {
        NetworkBus.Subscription listening = bus.subscribe(TOPIC,
                event -> consumer.consumeIncomingMessageAsString(event.payload()));
        return new Messenger() {
            @Override
            public void sendOutgoingMessage(OutgoingMessage message) {
                bus.publish(TOPIC, "", message.asEncodedString());
            }

            @Override
            public void close() {
                listening.close();
            }
        };
    }
}
