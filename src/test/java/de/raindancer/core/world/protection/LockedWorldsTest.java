package de.raindancer.core.world.protection;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LockedWorldsTest {

    @AfterEach
    void clear() {
        LockedWorlds.clear();
    }

    private static Player player(GameMode mode, boolean mayBuild) {
        Player player = mock(Player.class);
        when(player.getGameMode()).thenReturn(mode);
        when(player.hasPermission(LockedWorlds.BUILD_PERMISSION)).thenReturn(mayBuild);
        return player;
    }

    @Test
    @DisplayName("a locked world is locked whatever case its name is asked in; others are not")
    void locking() {
        Plugin hub = mock(Plugin.class);

        LockedWorlds.lock(hub, "Hub");

        assertThat(LockedWorlds.isLocked("hub")).isTrue();
        assertThat(LockedWorlds.isLocked("HUB")).isTrue();
        assertThat(LockedWorlds.isLocked("world")).isFalse();
        assertThat(LockedWorlds.isLocked(null)).isFalse();
        assertThat(LockedWorlds.locked()).containsExactly("hub");
        LockedWorlds.unlock("hub");
        assertThat(LockedWorlds.isLocked("hub")).isFalse();
    }

    @Test
    @DisplayName("by default only somebody in creative with the build permission builds in a locked world")
    void defaultBuilders() {
        LockedWorlds.lock(mock(Plugin.class), "hub");

        assertThat(LockedWorlds.isBuilding("hub", player(GameMode.CREATIVE, true))).isTrue();
        assertThat(LockedWorlds.isBuilding("hub", player(GameMode.SURVIVAL, true))).as("not in creative").isFalse();
        assertThat(LockedWorlds.isBuilding("hub", player(GameMode.CREATIVE, false))).as("no permission").isFalse();
    }

    @Test
    @DisplayName("a plugin can say who builds in its own world")
    void ownBuilders() {
        LockedWorlds.lock(mock(Plugin.class), "arena", player -> player.getGameMode() == GameMode.SPECTATOR);

        assertThat(LockedWorlds.isBuilding("arena", player(GameMode.SPECTATOR, false))).isTrue();
        assertThat(LockedWorlds.isBuilding("arena", player(GameMode.CREATIVE, true))).isFalse();
    }

    @Test
    @DisplayName("a builder predicate that throws counts as nobody building — the world stays locked")
    void brokenBuilderLocks() {
        LockedWorlds.lock(mock(Plugin.class), "hub", player -> {
            throw new IllegalStateException("broken");
        });

        assertThat(LockedWorlds.isBuilding("hub", player(GameMode.CREATIVE, true))).isFalse();
    }

    @Test
    @DisplayName("a plugin's locks go with its class loader when it is unloaded")
    void forgottenWithThePlugin() {
        Plugin hub = mock(Plugin.class);
        LockedWorlds.lock(hub, "hub");

        assertThat(LockedWorlds.forgetFrom(new ClassLoader() { })).isZero();
        assertThat(LockedWorlds.forgetFrom(hub.getClass().getClassLoader())).isEqualTo(1);
        assertThat(LockedWorlds.locked()).isEqualTo(List.of());
    }

    @Test
    @DisplayName("a locked world is open only where its owner says so, and a throwing owner opens nothing")
    void openPlaces() {
        Plugin arena = mock(Plugin.class);
        org.bukkit.World world = mock(org.bukkit.World.class);
        when(world.getName()).thenReturn("Arena");
        org.bukkit.Location inside = new org.bukkit.Location(world, 5, 64, 5);
        org.bukkit.Location outside = new org.bukkit.Location(world, 500, 64, 500);

        LockedWorlds.lock(arena, "arena", null, where -> where.getX() < 100);
        assertThat(LockedWorlds.isOpen(inside)).isTrue();
        assertThat(LockedWorlds.isOpen(outside)).isFalse();

        LockedWorlds.lock(arena, "arena", null, where -> {
            throw new IllegalStateException("broken");
        });
        assertThat(LockedWorlds.isOpen(inside)).isFalse();

        LockedWorlds.lock(arena, "arena");
        assertThat(LockedWorlds.isOpen(inside)).as("a plain lock has no open places").isFalse();
        assertThat(LockedWorlds.isOpen(null)).isFalse();
    }
}
