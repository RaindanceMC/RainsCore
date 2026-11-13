package de.raindancer.core.ui.scoreboard;

import de.raindancer.client.protocol.ClientFeature;
import de.raindancer.client.protocol.HudPanel;
import de.raindancer.client.protocol.HudPanelClear;
import de.raindancer.client.protocol.Text;
import de.raindancer.core.platform.client.ClientMods;
import de.raindancer.core.platform.client.TextSpans;
import net.kyori.adventure.text.Component;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * A sidebar for a player without the client mod, a panel in the mod's HUD for one with it. Decided when a
 * board is made; a player whose mod says hello after that is moved by {@link Scoreboards#redraw}.
 */
public final class RoutedBoardFactory implements BoardFactory {

    private final BoardFactory vanilla;
    private final ClientMods clients;

    public RoutedBoardFactory(BoardFactory vanilla, ClientMods clients) {
        this.vanilla = vanilla;
        this.clients = clients;
    }

    @Override
    public Board create(UUID player) {
        return clients.supports(player, ClientFeature.HUD) ? new Panel(player) : vanilla.create(player);
    }

    private final class Panel implements Board {

        private final UUID player;

        private Panel(UUID player) {
            this.player = player;
        }

        @Override
        public void update(Component title, List<Component> lines) {
            Locale locale = clients.of(player).map(session -> session.locale()).orElse(Locale.ROOT);
            List<Text> shown = lines.stream().limit(HudPanel.MAX_LINES).map(line -> TextSpans.of(line, locale)).toList();
            clients.send(player, new HudPanel(TextSpans.of(title, locale), shown));
        }

        @Override
        public void delete() {
            clients.send(player, new HudPanelClear());
        }
    }
}
