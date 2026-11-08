package de.raindancer.core.world.protection;

import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Seclusion.refresh costs per player, not per pair of players")
class SeclusionRefreshTest {

    private static final int PLAYERS = 60;

    private Plugin plugin;
    private Land land;
    private FlagRules rules;
    private ProtectedArea garden;
    private final List<Player> online = new ArrayList<>();
    private final List<Location> spots = new ArrayList<>();
    private int lookups;

    @BeforeEach
    void setUp() {
        plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        land = mock(Land.class);
        rules = mock(FlagRules.class);
        LandFlags landFlags = mock(LandFlags.class);
        garden = mock(ProtectedArea.class);
        when(garden.id()).thenReturn("garden");
        when(plugin.getServer()).thenReturn(server);
        when(land.landFlags()).thenReturn(landFlags);
        when(land.flags()).thenReturn(rules);
        when(land.hasProvider()).thenReturn(true);
        when(landFlags.isEnforced(LandFlag.VISIBLE_FROM_OUTSIDE)).thenReturn(true);
        for (int each = 0; each < PLAYERS; each++) {
            Player player = mock(Player.class);
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            Location spot = mock(Location.class);
            spots.add(spot);
            when(player.getLocation()).thenReturn(spot);
            online.add(player);
        }
        doReturnOnline(server);
        when(land.areaAt(any())).thenAnswer(call -> {
            lookups++;
            return spots.indexOf(call.getArgument(0)) < 5 ? Optional.of(garden) : Optional.empty();
        });
    }

    @SuppressWarnings("unchecked")
    private void doReturnOnline(Server server) {
        when(server.getOnlinePlayers()).thenReturn((java.util.Collection) online);
    }

    @Test
    @DisplayName("everybody's area is looked up once per pass, not once per pair")
    void oneLookupPerPlayer() {
        when(rules.isAllowed(any(), any(), any(), any())).thenReturn(false);
        when(land.isBypassing(any())).thenReturn(false);

        new Seclusion(plugin, land).refresh();

        assertThat(lookups).isLessThanOrEqualTo(PLAYERS);
    }

    @Test
    @DisplayName("when no area on the server keeps anybody out of sight, nothing is worked out per pair")
    void nothingToHide() {
        when(rules.isAllowed(any(), any(), any(), any())).thenReturn(true);

        new Seclusion(plugin, land).refresh();

        assertThat(lookups).isLessThanOrEqualTo(PLAYERS);
        org.mockito.Mockito.verify(land, org.mockito.Mockito.never()).isBypassing(any());
    }
}
