package de.raindancer.core.world.companion;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Companion index: owners and neighbours without walking everybody")
class CompanionIndexTest {

    private final Plugin plugin = mock(Plugin.class);
    private final World overworld = world();
    private final World nether = world();
    private final CompanionIndex index = new CompanionIndex();

    private static World world() {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        return world;
    }

    private Companion companion(UUID owner) {
        Companion made = new Companion(plugin, owner, CompanionSpec.of(EntityType.WOLF), null);
        index.add(made);
        return made;
    }

    @Test
    @DisplayName("an owner's companions are found by owner, and gone ones are not")
    void byOwner() {
        UUID alice = UUID.randomUUID();
        Companion one = companion(alice);
        Companion two = companion(alice);
        companion(UUID.randomUUID());

        assertThat(index.of(alice)).containsExactlyInAnyOrder(one, two);
        index.remove(one);
        assertThat(index.of(alice)).containsExactly(two);
        index.remove(two);
        assertThat(index.of(alice)).isEmpty();
        assertThat(index.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("an owner's list can be walked while its companions are being removed")
    void removeWhileWalking() {
        UUID alice = UUID.randomUUID();
        companion(alice);
        companion(alice);
        companion(alice);
        index.of(alice).forEach(index::remove);
        assertThat(index.of(alice)).isEmpty();
    }

    @Test
    @DisplayName("neighbours are those in the same world within reach, however far the others are")
    void neighbours() {
        Companion me = companion(UUID.randomUUID());
        Companion beside = companion(UUID.randomUUID());
        Companion across = companion(UUID.randomUUID());
        Companion elsewhere = companion(UUID.randomUUID());
        Companion otherWorld = companion(UUID.randomUUID());
        index.moved(me, new Location(overworld, 100, 64, 100));
        index.moved(beside, new Location(overworld, 101, 64, 100));
        index.moved(across, new Location(overworld, 110, 64, 100));
        index.moved(elsewhere, new Location(overworld, 5000, 64, -300));
        index.moved(otherWorld, new Location(nether, 100, 64, 100));

        List<Companion> found = index.near(me.at, 2.5);

        assertThat(found).contains(me, beside).doesNotContain(elsewhere, otherWorld);
        assertThat(index.near(me.at, 20)).contains(across);
    }

    @Test
    @DisplayName("one that moves is found at its new place only, across cell and negative-coordinate borders")
    void moving() {
        Companion walker = companion(UUID.randomUUID());
        index.moved(walker, new Location(overworld, 1, 64, 1));
        index.moved(walker, new Location(overworld, -17, 64, -33));

        assertThat(index.near(new Location(overworld, 1, 64, 1), 3)).doesNotContain(walker);
        assertThat(index.near(new Location(overworld, -16, 64, -32), 3)).contains(walker);
    }

    @Test
    @DisplayName("a removed or gone companion is never found again, even if its brain reports once more")
    void removedStaysOut() {
        Companion gone = companion(UUID.randomUUID());
        Location at = new Location(overworld, 0, 64, 0);
        index.moved(gone, at);
        index.remove(gone);
        index.moved(gone, at);

        assertThat(index.near(at, 5)).isEmpty();
    }
}
