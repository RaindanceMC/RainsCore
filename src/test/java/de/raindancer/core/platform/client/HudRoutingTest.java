package de.raindancer.core.platform.client;

import de.raindancer.client.protocol.ClientCodec;
import de.raindancer.client.protocol.ClientFeature;
import de.raindancer.client.protocol.ClientboundPacket;
import de.raindancer.client.protocol.FeatureRange;
import de.raindancer.client.protocol.Hello;
import de.raindancer.client.protocol.HudPanel;
import de.raindancer.client.protocol.HudPanelClear;
import de.raindancer.client.protocol.Text;
import de.raindancer.client.protocol.Theme;
import de.raindancer.client.protocol.Welcome;
import de.raindancer.core.ui.scoreboard.Board;
import de.raindancer.core.ui.scoreboard.RoutedBoardFactory;
import de.raindancer.core.ui.scoreboard.Scoreboards;
import de.raindancer.core.ui.scoreboard.ScoreboardPriority;
import de.raindancer.core.ui.scoreboard.Sidebar;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A sidebar, for a player with the mod and for one without, through the real service. */
class HudRoutingTest {

    private static final UUID MODDED = UUID.randomUUID();
    private static final UUID VANILLA = UUID.randomUUID();

    private final List<ClientboundPacket> toMod = new ArrayList<>();
    private final List<String> vanilla = new ArrayList<>();

    private final ClientMods clients = new ClientMods(
            (player, channel, frame) -> toMod.add(ClientCodec.decodeClientbound(frame).orElseThrow()),
            () -> 0, () -> "Hyades", () -> Theme.HARBOR_SUNSET, new ClientMods.Reports() {
        @Override
        public void flooding(UUID player) {
        }

        @Override
        public void outdated(UUID player, String modVersion) {
        }

        @Override
        public void failed(String what, Throwable why) {
            throw new AssertionError(what, why);
        }
    });

    private final Scoreboards scoreboards = new Scoreboards(new RoutedBoardFactory(player -> new Board() {
        @Override
        public void update(Component title, List<Component> lines) {
            vanilla.add("sidebar " + player + " " + lines.size());
        }

        @Override
        public void delete() {
            vanilla.add("sidebar gone " + player);
        }
    }, clients));

    private void joinWithMod() {
        clients.received(MODDED, ClientCodec.encode(new Hello(1, "0.2.0", "26.3",
                List.of(FeatureRange.of(ClientFeature.SESSION), FeatureRange.of(ClientFeature.HUD)), 2, "en_us")));
        assertThat(toMod.remove(0)).isInstanceOf(Welcome.class);
    }

    private static Sidebar sidebar(String... lines) {
        return Sidebar.of(Component.text("Hyades"), java.util.Arrays.stream(lines).map(Component::text).toList().toArray(Component[]::new));
    }

    @Test
    void aSidebarReachesTheModAsAPanelAndAVanillaPlayerAsASidebar() {
        joinWithMod();
        scoreboards.show(MODDED, "economy", sidebar("Drachma 12", "Job Mason"), ScoreboardPriority.NORMAL);
        scoreboards.show(VANILLA, "economy", sidebar("Drachma 3"), ScoreboardPriority.NORMAL);

        assertThat(toMod).containsExactly(new HudPanel(Text.of("Hyades"), List.of(Text.of("Drachma 12"), Text.of("Job Mason"))));
        assertThat(vanilla).containsExactly("sidebar " + VANILLA + " 1");
    }

    @Test
    void clearingTakesThePanelAway() {
        joinWithMod();
        scoreboards.show(MODDED, "economy", sidebar("Drachma 12"), ScoreboardPriority.NORMAL);
        scoreboards.clear(MODDED, "economy");
        assertThat(toMod).last().isEqualTo(new HudPanelClear());
        assertThat(vanilla).isEmpty();
    }

    @Test
    void aPlayerWhoseModSaysHelloLaterIsMovedOverInPlace() {
        scoreboards.show(MODDED, "economy", sidebar("Drachma 12"), ScoreboardPriority.NORMAL);
        assertThat(vanilla).containsExactly("sidebar " + MODDED + " 1");

        clients.onReady(session -> scoreboards.redraw(session.player()));
        joinWithMod();

        assertThat(vanilla).containsExactly("sidebar " + MODDED + " 1", "sidebar gone " + MODDED);
        assertThat(toMod).containsExactly(new HudPanel(Text.of("Hyades"), List.of(Text.of("Drachma 12"))));
        assertThat(scoreboards.ownerOf(MODDED)).contains("economy");
    }

    @Test
    void redrawingSomebodyWithNothingToShowDoesNothing() {
        scoreboards.redraw(VANILLA);
        assertThat(vanilla).isEmpty();
        assertThat(toMod).isEmpty();
    }

    @Test
    void moreLinesThanAPanelHoldsAreCutNotRefused() {
        joinWithMod();
        new RoutedBoardFactory(player -> null, clients).create(MODDED)
                .update(Component.text("Hyades"), Collections.nCopies(HudPanel.MAX_LINES + 8, Component.text("line")));
        assertThat(((HudPanel) toMod.get(0)).lines()).hasSize(HudPanel.MAX_LINES);
    }
}
