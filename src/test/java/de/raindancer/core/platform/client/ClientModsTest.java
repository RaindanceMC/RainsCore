package de.raindancer.core.platform.client;

import de.raindancer.client.protocol.ClientCodec;
import de.raindancer.client.protocol.ClientFeature;
import de.raindancer.client.protocol.FeatureRange;
import de.raindancer.client.protocol.FeatureVersion;
import de.raindancer.client.protocol.Hello;
import de.raindancer.client.protocol.Theme;
import de.raindancer.client.protocol.Welcome;
import de.raindancer.client.protocol.Wire;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ClientModsTest {

    private static final UUID RAIN = UUID.randomUUID();

    private record Sent(UUID player, String channel, byte[] frame) {
    }

    private final List<Sent> sent = new ArrayList<>();
    private final List<String> reported = new ArrayList<>();
    private long now;

    private final ClientMods.Reports reports = new ClientMods.Reports() {
        @Override
        public void flooding(UUID player) {
            reported.add("flooding " + player);
        }

        @Override
        public void outdated(UUID player, String modVersion) {
            reported.add("outdated " + modVersion);
        }

        @Override
        public void failed(String what, Throwable why) {
            reported.add("failed " + why.getMessage());
        }
    };

    private final ClientMods clients = new ClientMods((player, channel, frame) -> sent.add(new Sent(player, channel, frame)),
            () -> now, () -> "Hyades", () -> Theme.HARBOR_SUNSET, reports);

    private static byte[] hello(int protocol, String modVersion, FeatureRange... features) {
        return ClientCodec.encode(new Hello(protocol, modVersion, "26.3", List.of(features), 3, "de_de"));
    }

    private static byte[] hello() {
        return hello(Wire.PROTOCOL, "0.1.0", FeatureRange.of(ClientFeature.SESSION));
    }

    @Test
    void nobodyHasTheModUntilTheySaySo() {
        assertThat(clients.of(RAIN)).isEmpty();
        assertThat(clients.supports(RAIN, ClientFeature.SESSION)).isFalse();
        assertThat(clients.version(RAIN, ClientFeature.SESSION)).isZero();
        assertThat(sent).isEmpty();
    }

    @Test
    void aHelloIsAnsweredWithAWelcomeAndTheSession() {
        clients.received(RAIN, hello());

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).player()).isEqualTo(RAIN);
        assertThat(sent.get(0).channel()).isEqualTo(Wire.CHANNEL);
        assertThat(ClientCodec.decodeClientbound(sent.get(0).frame())).contains(new Welcome(1,
                List.of(new FeatureVersion(ClientFeature.SESSION.id(), 1)), "Hyades", Theme.HARBOR_SUNSET));

        ClientSession session = clients.of(RAIN).orElseThrow();
        assertThat(session.modVersion()).isEqualTo("0.1.0");
        assertThat(session.minecraft()).isEqualTo("26.3");
        assertThat(session.guiScale()).isEqualTo(3);
        assertThat(session.locale()).isEqualTo(Locale.GERMANY);
        assertThat(session.features()).containsEntry(ClientFeature.SESSION, 1);
        assertThat(clients.supports(RAIN, ClientFeature.SESSION)).isTrue();
        assertThat(clients.version(RAIN, ClientFeature.SESSION)).isEqualTo(1);
        assertThat(clients.sessions()).containsExactly(session);
    }

    @Test
    void readyIsToldAfterTheWelcomeIsOnItsWay() {
        List<String> order = new ArrayList<>();
        clients.onReady(session -> order.add("ready " + session.modVersion() + " after " + sent.size() + " sent"));
        clients.received(RAIN, hello());
        assertThat(order).containsExactly("ready 0.1.0 after 1 sent");
    }

    @Test
    void leavingEndsTheSessionAndTellsOnlyAboutPlayersWhoHadOne() {
        List<UUID> gone = new ArrayList<>();
        clients.onGone(gone::add);

        clients.left(UUID.randomUUID());
        assertThat(gone).isEmpty();

        clients.received(RAIN, hello());
        clients.left(RAIN);
        assertThat(gone).containsExactly(RAIN);
        assertThat(clients.of(RAIN)).isEmpty();
        assertThat(clients.send(RAIN, new Welcome(1, List.of(), "x", Theme.HARBOR_SUNSET))).isFalse();
    }

    @Test
    void aPacketGoesOnlyToAClientThatAgreedItsFeature() {
        Welcome packet = new Welcome(1, List.of(), "again", Theme.HARBOR_SUNSET);
        assertThat(clients.send(RAIN, packet)).isFalse();
        assertThat(sent).isEmpty();

        clients.received(RAIN, hello());
        assertThat(clients.send(RAIN, packet)).isTrue();
        assertThat(sent).hasSize(2);
    }

    @Test
    void aModTooOldIsToldToUpdateAndGetsNoSession() {
        ClientMods newer = new ClientMods((player, channel, frame) -> sent.add(new Sent(player, channel, frame)),
                () -> now, () -> "Hyades", () -> Theme.HARBOR_SUNSET, reports,
                List.of(new FeatureRange(ClientFeature.SESSION.id(), 2, 3)));
        newer.received(RAIN, hello(Wire.PROTOCOL, "0.0.1", new FeatureRange(ClientFeature.SESSION.id(), 1, 1)));
        assertThat(newer.of(RAIN)).isEmpty();
        assertThat(sent).isEmpty();
        assertThat(reported).containsExactly("outdated 0.0.1");
    }

    @Test
    void aHelloInAProtocolOlderThanAnyIsOutdatedToo() {
        clients.received(RAIN, hello(0, "0.0.1", FeatureRange.of(ClientFeature.SESSION)));
        assertThat(clients.of(RAIN)).isEmpty();
        assertThat(reported).containsExactly("outdated 0.0.1");
    }

    @Test
    void aModTooNewForThisServerGetsNoSessionAndNoNagging() {
        clients.received(RAIN, hello(Wire.PROTOCOL, "9.0.0", new FeatureRange(ClientFeature.SESSION.id(), 5, 6)));
        assertThat(clients.of(RAIN)).isEmpty();
        assertThat(sent).isEmpty();
        assertThat(reported).isEmpty();
    }

    @Test
    void aModFromTheFutureIsAnsweredInTheProtocolThisServerSpeaks() {
        clients.received(RAIN, hello(7, "9.0.0", new FeatureRange(ClientFeature.SESSION.id(), 1, 5), new FeatureRange(4711, 1, 2)));
        Welcome welcome = (Welcome) ClientCodec.decodeClientbound(sent.get(0).frame()).orElseThrow();
        assertThat(welcome.protocol()).isEqualTo(Wire.PROTOCOL);
        assertThat(welcome.agreed()).containsExactly(new FeatureVersion(ClientFeature.SESSION.id(), 1));
    }

    @Test
    void whatAClientClaimsAboutItselfIsCleanedBeforeAnybodyShowsIt() {
        clients.received(RAIN, ClientCodec.encode(new Hello(1, "<red>1.0\n§c", "26.3 <click:run_command:/op x>",
                List.of(FeatureRange.of(ClientFeature.SESSION)), 9000, "not a locale!!")));
        ClientSession session = clients.of(RAIN).orElseThrow();
        assertThat(session.modVersion()).isEqualTo("?red?1.0??c");
        assertThat(session.minecraft()).doesNotContain("<", ">", ":", " ", "/");
        assertThat(session.guiScale()).isEqualTo(16);
        assertThat(session.locale()).isEqualTo(Locale.ROOT);
    }

    @Test
    void garbageAndUnknownPacketsAreDroppedWithoutAWord() {
        clients.received(RAIN, new byte[0]);
        clients.received(RAIN, new byte[]{(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 1});
        clients.received(RAIN, new byte[]{99, 0, 1, 2, 3});
        assertThat(clients.of(RAIN)).isEmpty();
        assertThat(sent).isEmpty();
        assertThat(reported).isEmpty();
    }

    @Test
    void aFloodIsDroppedAndReportedOnce() {
        for (int second = 0; second < 4; second++) {
            for (int frame = 0; frame < ClientMods.FRAMES_PER_SECOND + 20; frame++) {
                now = second * 1000L + frame;
                clients.received(RAIN, hello());
            }
        }
        assertThat(sent).hasSize(4 * ClientMods.FRAMES_PER_SECOND);
        assertThat(reported).containsExactly("flooding " + RAIN);
    }

    @Test
    void aSecondHelloStartsOver() {
        List<ClientSession> ready = new ArrayList<>();
        clients.onReady(ready::add);
        clients.received(RAIN, hello());
        clients.received(RAIN, hello(Wire.PROTOCOL, "0.2.0", FeatureRange.of(ClientFeature.SESSION)));
        assertThat(ready).extracting(ClientSession::modVersion).containsExactly("0.1.0", "0.2.0");
        assertThat(clients.of(RAIN).orElseThrow().modVersion()).isEqualTo("0.2.0");
    }

    @Test
    void oneListenerFailingDoesNotStopTheNext() {
        List<String> told = new ArrayList<>();
        clients.onReady(session -> {
            throw new IllegalStateException("boom");
        });
        clients.onReady(session -> told.add("second"));
        clients.received(RAIN, hello());
        assertThat(told).containsExactly("second");
        assertThat(reported).containsExactly("failed boom");
        assertThat(clients.of(RAIN)).isPresent();
    }

    @Test
    void aPluginThatIsGoneIsNotToldAnyMore() {
        List<String> told = new ArrayList<>();
        clients.onReady(session -> told.add("ready"));
        clients.onGone(player -> told.add("gone"));
        assertThat(clients.forgetFrom(getClass().getClassLoader())).isEqualTo(2);
        clients.received(RAIN, hello());
        clients.left(RAIN);
        assertThat(told).isEmpty();
    }
}
