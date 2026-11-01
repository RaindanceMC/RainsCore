package de.raindancer.core.ui.board;

import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LeaderboardsTest {

    @AfterEach
    void clear() {
        Leaderboards.clear();
    }

    private static Leaderboard board(String id, List<Leaderboard.Standing> standings) {
        return new Leaderboard(id, "Fishing level", "Who has fished the most", Material.FISHING_ROD,
                () -> standings);
    }

    @Test
    @DisplayName("a registered board is found by its id and answers its top, cut to what was asked")
    void registerAndAsk() {
        UUID ada = UUID.randomUUID();
        UUID bo = UUID.randomUUID();
        Leaderboards.register(getClass().getClassLoader(), board("fishing:level", List.of(
                new Leaderboard.Standing(ada, "Ada", "level 25"), new Leaderboard.Standing(bo, "Bo", "level 20"))));
        assertThat(Leaderboards.find("fishing:level")).isPresent();
        assertThat(Leaderboards.top("fishing:level", 1)).extracting(Leaderboard.Standing::name).containsExactly("Ada");
        assertThat(Leaderboards.place("fishing:level", 2)).map(Leaderboard.Standing::player).contains(bo);
        assertThat(Leaderboards.place("fishing:level", 3)).isEmpty();
        assertThat(Leaderboards.ids()).containsExactly("fishing:level");
    }

    @Test
    @DisplayName("a board that throws, or one nobody registered, answers empty rather than breaking its reader")
    void failSafe() {
        Leaderboards.register(getClass().getClassLoader(), new Leaderboard("broken:board", "Broken", "", Material.STONE,
                () -> {
                    throw new IllegalStateException("database gone");
                }));
        assertThat(Leaderboards.top("broken:board", 3)).isEmpty();
        assertThat(Leaderboards.top("nobody:here", 3)).isEmpty();
    }

    @Test
    @DisplayName("a plugin's boards go when its classes do, and a board id is namespaced")
    void forgetAndValidate() {
        ClassLoader loader = new ClassLoader() { };
        Leaderboards.register(loader, board("fishing:level", List.of()));
        assertThat(Leaderboards.forgetFrom(loader)).isEqualTo(1);
        assertThat(Leaderboards.ids()).isEmpty();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> board("no-namespace", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
