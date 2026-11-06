package de.raindancer.core.world.combat;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wolf;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** That the listener says <em>how</em> a hit landed, so a rule can tell a fist from a sword or a bow. */
@DisplayName("how an attack was delivered, read off the damage event")
class CombatListenerMeansTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    private final List<Attack> seen = new ArrayList<>();
    private CombatListener listener;
    private Combat combat;

    @BeforeEach
    void setUp() {
        combat = new Combat();
        combat.alsoAsk(attack -> {
            seen.add(attack);
            return null;
        });
        listener = new CombatListener(combat, () -> 0L, null);
    }

    private static Location somewhere() {
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        Location location = mock(Location.class);
        when(location.getWorld()).thenReturn(world);
        return location;
    }

    private static Player holding(UUID id, ItemStack mainHand) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        Location at = somewhere();
        when(player.getLocation()).thenReturn(at);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(inventory.getItemInMainHand()).thenReturn(mainHand);
        when(player.getInventory()).thenReturn(inventory);
        return player;
    }

    private static ItemStack empty() {
        ItemStack stack = mock(ItemStack.class);
        when(stack.isEmpty()).thenReturn(true);
        return stack;
    }

    private static ItemStack sword() {
        ItemStack stack = mock(ItemStack.class);
        when(stack.isEmpty()).thenReturn(false);
        return stack;
    }

    private Attack hit(Object damager) {
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getDamager()).thenReturn((org.bukkit.entity.Entity) damager);
        Player bob = holding(BOB, empty());
        when(event.getEntity()).thenReturn(bob);
        listener.onDamage(event);
        return seen.getLast();
    }

    @Test
    @DisplayName("an empty main hand is a fist")
    void fist() {
        assertThat(hit(holding(ALICE, empty())).means()).isEqualTo(Attack.Means.BARE_HANDED);
    }

    @Test
    @DisplayName("a null main hand is a fist too")
    void nullHand() {
        assertThat(hit(holding(ALICE, null)).means()).isEqualTo(Attack.Means.BARE_HANDED);
    }

    @Test
    @DisplayName("anything in the main hand is a held item")
    void heldItem() {
        assertThat(hit(holding(ALICE, sword())).means()).isEqualTo(Attack.Means.HELD_ITEM);
    }

    @Test
    @DisplayName("an arrow is ranged, and still traced back to whoever shot it")
    void arrow() {
        Arrow arrow = mock(Arrow.class);
        Player alice = holding(ALICE, empty());
        when(arrow.getShooter()).thenReturn(alice);
        Location at = somewhere();
        when(arrow.getLocation()).thenReturn(at);

        Attack attack = hit(arrow);

        assertThat(attack.means()).isEqualTo(Attack.Means.RANGED);
        assertThat(attack.attackerId()).isEqualTo(ALICE);
    }

    @Test
    @DisplayName("a pet biting is not its owner's fist")
    void pet() {
        Wolf wolf = mock(Wolf.class);
        when(wolf.isTamed()).thenReturn(true);
        Player alice = holding(ALICE, empty());
        when(wolf.getOwner()).thenReturn(alice);
        Location at = somewhere();
        when(wolf.getLocation()).thenReturn(at);

        assertThat(hit(wolf).means()).isEqualTo(Attack.Means.OTHER);
    }

    @Test
    @DisplayName("a mob a plugin bound to a player is that player attacking, like a tamed wolf")
    void companion() {
        org.bukkit.entity.Fox fox = mock(org.bukkit.entity.Fox.class);
        UUID foxId = UUID.randomUUID();
        when(fox.getUniqueId()).thenReturn(foxId);
        Location at = somewhere();
        when(fox.getLocation()).thenReturn(at);
        combat.companions().bind(foxId, ALICE);

        Attack attack = hit(fox);

        assertThat(attack.attacker()).isEqualTo(Attack.Fighter.PLAYER);
        assertThat(attack.attackerId()).isEqualTo(ALICE);
        assertThat(attack.throughPet()).isTrue();
        assertThat(attack.means()).isEqualTo(Attack.Means.OTHER);
    }

    @Test
    @DisplayName("a snowball a companion threw is its owner's too")
    void companionsProjectile() {
        org.bukkit.entity.Snowman golem = mock(org.bukkit.entity.Snowman.class);
        UUID golemId = UUID.randomUUID();
        when(golem.getUniqueId()).thenReturn(golemId);
        combat.companions().bind(golemId, ALICE);
        org.bukkit.entity.Snowball ball = mock(org.bukkit.entity.Snowball.class);
        when(ball.getShooter()).thenReturn(golem);
        Location at = somewhere();
        when(ball.getLocation()).thenReturn(at);

        Attack attack = hit(ball);

        assertThat(attack.attackerId()).isEqualTo(ALICE);
        assertThat(attack.throughPet()).isTrue();
    }
}
