package de.raindancer.core.data.loadout;

import de.raindancer.core.data.nbt.ItemBytes;
import de.raindancer.core.data.nbt.ItemText;
import de.raindancer.core.testkit.TestItems;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoadoutsTest {

    /** "MATERIAL:amount" as bytes; "BROKEN" decodes to nothing, like an item from a removed mod. */
    private static final class PlainBytes implements ItemBytes {

        @Override
        public int dataVersion() {
            return 1;
        }

        @Override
        public byte[] toBytes(ItemStack item) {
            return (item.getType().name() + ":" + item.getAmount()).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public boolean isNothing(ItemStack item) {
            return item == null || item.isEmpty();
        }

        @Override
        public Optional<ItemStack> fromBytes(byte[] bytes) {
            try {
                String[] parts = new String(bytes, StandardCharsets.UTF_8).split(":");
                return Optional.of(TestItems.of(Material.valueOf(parts[0]), Integer.parseInt(parts[1])));
            } catch (RuntimeException notOurs) {
                return Optional.empty();
            }
        }
    }

    private final ItemText codec = new ItemText(new PlainBytes());
    private final Loadouts loadouts = new Loadouts(codec);

    private Player player(ItemStack[] contents, ItemStack[] ender) {
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        Inventory enderChest = mock(Inventory.class);
        when(player.getInventory()).thenReturn(inventory);
        when(player.getEnderChest()).thenReturn(enderChest);
        when(inventory.getContents()).thenReturn(contents);
        when(enderChest.getContents()).thenReturn(ender);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.getActivePotionEffects()).thenReturn(List.of());
        when(player.getHealth()).thenReturn(20.0);
        when(player.getFoodLevel()).thenReturn(20);
        AttributeInstance maxHealth = mock(AttributeInstance.class);
        when(maxHealth.getValue()).thenReturn(20.0);
        when(player.getAttribute(Attribute.MAX_HEALTH)).thenReturn(maxHealth);
        return player;
    }

    private static ItemStack[] slots(int size) {
        return new ItemStack[size];
    }

    private static String encoded(String text) {
        return java.util.Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("capturing keeps every slot in its place — a gap stays a gap")
    void captureKeepsPositions() {
        ItemStack[] contents = slots(Loadout.INVENTORY_SLOTS);
        contents[4] = TestItems.of(Material.DIAMOND_SWORD);
        contents[39] = TestItems.of(Material.DIAMOND_HELMET);
        ItemStack[] ender = slots(Loadout.ENDER_SLOTS);
        ender[26] = TestItems.of(Material.DIAMOND, 64);
        Player player = player(contents, ender);
        when(player.getLevel()).thenReturn(30);
        when(player.getExp()).thenReturn(0.5f);

        Loadout captured = loadouts.capture(player);

        assertThat(captured.inventory()).hasSize(Loadout.INVENTORY_SLOTS);
        assertThat(captured.inventory().get(0)).isEmpty();
        assertThat(codec.read(captured.inventory().get(4)).getType()).isEqualTo(Material.DIAMOND_SWORD);
        assertThat(codec.read(captured.inventory().get(39)).getType()).isEqualTo(Material.DIAMOND_HELMET);
        assertThat(codec.read(captured.enderChest().get(26)).getAmount()).isEqualTo(64);
        assertThat(captured.level()).isEqualTo(30);
        assertThat(captured.exp()).isEqualTo(0.5f);
        assertThat(captured.gameMode()).isEqualTo("SURVIVAL");
    }

    @Test
    @DisplayName("applying puts every item back into the slot it came from and empties the rest")
    void applyRestoresSlots() {
        Player player = player(slots(Loadout.INVENTORY_SLOTS), slots(Loadout.ENDER_SLOTS));
        List<String> inventory = new ArrayList<>(Collections.nCopies(Loadout.INVENTORY_SLOTS, ""));
        inventory.set(7, codec.write(TestItems.of(Material.TORCH, 12)));
        Loadout loadout = new Loadout(inventory, List.of(), 5, 0.1f, 8.0, 12, 1f, "CREATIVE", true, true,
                List.of(), null);

        assertThat(loadouts.apply(player, loadout)).isTrue();

        ArgumentCaptor<ItemStack[]> set = ArgumentCaptor.forClass(ItemStack[].class);
        verify(player.getInventory()).setContents(set.capture());
        assertThat(set.getValue()).hasSize(Loadout.INVENTORY_SLOTS);
        assertThat(set.getValue()[7].getType()).isEqualTo(Material.TORCH);
        assertThat(set.getValue()[7].getAmount()).isEqualTo(12);
        assertThat(set.getValue()[0]).isNull();
        verify(player.getEnderChest()).setContents(any(ItemStack[].class));
        verify(player).setGameMode(GameMode.CREATIVE);
        verify(player).setLevel(5);
        verify(player).setHealth(8.0);
        verify(player).setFoodLevel(12);
        verify(player).setAllowFlight(true);
    }

    @Test
    @DisplayName("health is never set above what the player can have")
    void healthIsClamped() {
        Player player = player(slots(Loadout.INVENTORY_SLOTS), slots(Loadout.ENDER_SLOTS));
        Loadout loadout = new Loadout(List.of(), List.of(), 0, 0f, 40.0, 20, 5f, "SURVIVAL", false, false,
                List.of(), null);

        loadouts.apply(player, loadout);

        verify(player).setHealth(20.0);
    }

    @Test
    @DisplayName("a loadout holding an item this server cannot read is refused before anything changes")
    void unreadableIsRefused() {
        Player player = player(slots(Loadout.INVENTORY_SLOTS), slots(Loadout.ENDER_SLOTS));
        List<String> inventory = new ArrayList<>(Collections.nCopies(Loadout.INVENTORY_SLOTS, ""));
        inventory.set(2, encoded("BROKEN"));
        Loadout loadout = new Loadout(inventory, List.of(encoded("NOPE:1")), 0, 0f, 20, 20, 5f, "SURVIVAL",
                false, false, List.of(), null);

        assertThat(loadouts.unreadable(loadout)).isEqualTo(2);
        assertThat(loadouts.apply(player, loadout)).isFalse();

        verify(player.getInventory(), never()).setContents(any(ItemStack[].class));
        verify(player, never()).setHealth(anyDouble());
    }

    @Test
    @DisplayName("an unknown game mode leaves the player's own as it is")
    void unknownGameMode() {
        Player player = player(slots(Loadout.INVENTORY_SLOTS), slots(Loadout.ENDER_SLOTS));
        loadouts.apply(player, Loadout.empty("FLOATING"));
        verify(player, never()).setGameMode(any());
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("beside flight Core's leases lend")
    class LeasedFlight {

        private final java.util.UUID id = java.util.UUID.randomUUID();
        private final de.raindancer.core.world.movement.Flight flight =
                new de.raindancer.core.world.movement.Flight(mock(org.bukkit.plugin.Plugin.class), () -> 1_000L);
        private final Loadouts leased = new Loadouts(codec, flight);
        private boolean allowFlight;

        private Player flier() {
            Player player = player(slots(Loadout.INVENTORY_SLOTS), slots(Loadout.ENDER_SLOTS));
            when(player.getUniqueId()).thenReturn(id);
            when(player.getAllowFlight()).thenAnswer(call -> allowFlight);
            org.mockito.Mockito.doAnswer(call -> allowFlight = call.getArgument(0))
                    .when(player).setAllowFlight(org.mockito.ArgumentMatchers.anyBoolean());
            org.bukkit.World world = mock(org.bukkit.World.class);
            when(world.hasCollisionsIn(any())).thenReturn(true);
            when(player.getWorld()).thenReturn(world);
            when(player.getBoundingBox()).thenReturn(new org.bukkit.util.BoundingBox(0, 64, 0, 0.6, 65.8, 0.6));
            when(player.getPersistentDataContainer())
                    .thenReturn(mock(org.bukkit.persistence.PersistentDataContainer.class));
            return player;
        }

        @Test
        @DisplayName("a claim's flight is not captured as the side's own")
        void lentFlightIsNotCaptured() {
            Player player = flier();
            flight.grant(player, "claims");

            assertThat(leased.capture(player).allowFlight()).isFalse();
        }

        @Test
        @DisplayName("flight nothing lends is captured as before")
        void ownFlightIsCaptured() {
            Player player = flier();
            allowFlight = true;

            assertThat(leased.capture(player).allowFlight()).isTrue();
        }

        @Test
        @DisplayName("putting on a side without flight does not end a lease's flight")
        void applyingKeepsLeasedFlight() {
            Player player = flier();
            flight.grant(player, de.raindancer.core.world.movement.Flight.FLY);

            leased.apply(player, Loadout.empty("SURVIVAL"));

            assertThat(allowFlight).isTrue();
            assertThat(flight.hasOwnFlight(player)).isTrue();
        }

        @Test
        @DisplayName("a side captured on a claim's flight, put back after the claim let go, does not fly")
        void restoredSideDoesNotKeepTheClaimsFlight() {
            Player player = flier();
            flight.grant(player, "claims");
            Loadout survival = leased.capture(player);
            flight.revoke(player, "claims");

            leased.apply(player, survival);

            assertThat(allowFlight).isFalse();
        }
    }
}
