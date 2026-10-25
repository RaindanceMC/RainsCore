package de.raindancer.core.platform.permission;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Going on or off duty changes which commands somebody holds, and the client only knows the commands it was sent —
 * so it is sent them again, or /gamemode stays "unknown" in admin mode until they rejoin.
 */
class DutyCommandsTest {

    private final List<Runnable> queued = new ArrayList<>();

    private static Player player(UUID id) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        return player;
    }

    @Test
    @DisplayName("the one who switched is sent their commands again, on their own thread")
    void one() {
        UUID hydroId = UUID.randomUUID();
        Player hydro = player(hydroId);
        Player other = player(UUID.randomUUID());
        Consumer<UUID> refresh = DutyCommands.refreshing(id -> id.equals(hydroId) ? hydro : null,
                () -> List.of(hydro, other), (who, task) -> queued.add(task));

        refresh.accept(hydroId);
        verify(hydro, never()).updateCommands();
        assertThat(queued).hasSize(1);
        queued.forEach(Runnable::run);
        verify(hydro).updateCommands();
        verify(other, never()).updateCommands();
    }

    @Test
    @DisplayName("the server's switch flipping sends everybody theirs")
    void everybody() {
        Player a = player(UUID.randomUUID());
        Player b = player(UUID.randomUUID());
        DutyCommands.refreshing(id -> null, () -> List.of(a, b), (who, task) -> task.run()).accept(null);
        verify(a).updateCommands();
        verify(b).updateCommands();
    }

    @Test
    @DisplayName("somebody already gone costs nothing")
    void gone() {
        DutyCommands.refreshing(id -> null, List::of, (who, task) -> queued.add(task)).accept(UUID.randomUUID());
        assertThat(queued).isEmpty();
    }
}
