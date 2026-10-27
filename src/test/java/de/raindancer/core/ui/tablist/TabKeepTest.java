package de.raindancer.core.ui.tablist;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TabKeepTest {

    private final UUID viewer = UUID.randomUUID();
    private final UUID resident = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final Set<UUID> secluded = new HashSet<>();
    private final Set<UUID> vanished = new HashSet<>();
    private final TabKeep keep = new TabKeep();

    TabKeepTest() {
        keep.keepListedWhile((who, whom) -> who.equals(viewer) && secluded.contains(whom));
        keep.vanished(() -> vanished);
    }

    @Test
    @DisplayName("somebody whose body is only hidden stays on the tab list")
    void keepsTheEntry() {
        secluded.add(resident);
        assertThat(keep.drop(viewer, List.of(resident))).isTrue();
        assertThat(keep.drop(UUID.randomUUID(), List.of(resident))).as("not hidden from this viewer").isFalse();
        assertThat(keep.drop(viewer, List.of(other))).isFalse();
        assertThat(keep.drop(viewer, List.of(resident, other))).as("a removal that is partly real goes through").isFalse();
        assertThat(keep.drop(viewer, List.of())).isFalse();
    }

    @Test
    @DisplayName("a vanished player is never kept on anybody's list, whatever else hides them")
    void vanishedGoes() {
        secluded.add(resident);
        vanished.add(resident);
        assertThat(keep.drop(viewer, List.of(resident))).isFalse();
    }

    @Test
    @DisplayName("an entry kept for a hide that has ended is handed back once: shown again, or vanished meanwhile")
    void staleEntries() {
        secluded.add(resident);
        keep.drop(viewer, List.of(resident));
        assertThat(keep.stale()).isEmpty();

        vanished.add(resident);
        assertThat(keep.stale()).containsExactly(new TabKeep.Listing(viewer, resident));
        assertThat(keep.stale()).as("handed back only once").isEmpty();

        vanished.clear();
        keep.drop(viewer, List.of(resident));
        secluded.clear();
        assertThat(keep.stale()).containsExactly(new TabKeep.Listing(viewer, resident));
    }

    @Test
    @DisplayName("somebody leaving while kept listed is named for every viewer still listing them; a leaving viewer is forgotten")
    void quitting() {
        secluded.add(resident);
        keep.drop(viewer, List.of(resident));
        assertThat(keep.quit(resident)).containsExactly(viewer);
        assertThat(keep.quit(resident)).isEmpty();

        keep.drop(viewer, List.of(resident));
        assertThat(keep.quit(viewer)).isEmpty();
        secluded.clear();
        assertThat(keep.stale()).as("the viewer's own entries left with them").isEmpty();
    }

    @Test
    @DisplayName("nothing registered keeps nothing, and a reason that throws keeps nothing")
    void defaults() {
        TabKeep empty = new TabKeep();
        assertThat(empty.drop(viewer, List.of(resident))).isFalse();
        empty.keepListedWhile((who, whom) -> {
            throw new IllegalStateException("boom");
        });
        assertThat(empty.drop(viewer, List.of(resident))).isFalse();
    }
}
