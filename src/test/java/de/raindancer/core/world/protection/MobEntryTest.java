package de.raindancer.core.world.protection;

import de.raindancer.core.ui.messages.Messages;
import io.papermc.paper.event.entity.EntityMoveEvent;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Zombie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A zombie actually stepping across a border, rather than the source-text scan {@code ProtectionRulesTest}
 * settles for.
 *
 * <h2>Why this exists alongside that one</h2>
 * A scan of {@code MobControlListener}'s own source can only say the right method names appear in the right
 * order — it cannot say a zombie crossing a real border is actually refused. This builds the real event,
 * a real {@link Land} wired to a fake claim, and asks the listener the question with a real hostile entity.
 */
class MobEntryTest {

    private static final World WORLD = (World) Proxy.newProxyInstance(
            World.class.getClassLoader(), new Class<?>[]{World.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getName" -> "world";
                case "toString" -> "a fake world";
                case "hashCode" -> 1;
                case "equals" -> proxy == args[0];
                default -> null;
            });

    /** Answers with {@code inside} for anything at x >= 100, and nothing otherwise — the border is x = 100. */
    private static final class Fake implements LandProvider {
        FakeArea inside;

        @Override
        public String name() {
            return "the fake provider";
        }

        @Override
        public Optional<ProtectedArea> at(Location location) {
            return location.getX() >= 100 ? Optional.ofNullable(inside) : Optional.empty();
        }

        @Override
        public boolean hasAnyIn(World world) {
            return inside != null;
        }
    }

    private final LandPolicies policies = LandPolicies.builtIn();
    private final Fake provider = new Fake();
    private Land land;
    private MobControlListener listener;

    @BeforeEach
    void setUp() {
        Messages messages = new Messages(Path.of("target", "mob-entry-test-messages.yml"));
        land = new Land(policies, messages, new AtomicLong(1_000_000L)::get);
        land.provider(provider);
        listener = new MobControlListener(land);
    }

    private static Location at(double x) {
        return new Location(WORLD, x, 64, 0);
    }

    private static LivingEntity zombie() {
        return (LivingEntity) Proxy.newProxyInstance(
                Zombie.class.getClassLoader(), new Class<?>[]{Zombie.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getType" -> EntityType.ZOMBIE;
                    case "toString" -> "a fake zombie";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> method.getReturnType().isPrimitive()
                            ? (method.getReturnType() == boolean.class ? false : 0)
                            : null;
                });
    }

    @Test
    @DisplayName("a zombie stepping from open ground into a claim with entry denied is stopped")
    void deniedEntryStopsAZombieAtTheBorder() {
        FakeArea claim = FakeArea.named("somebody's claim").with(LandFlag.MONSTER_ENTRY, false);
        provider.inside = claim;

        EntityMoveEvent event = new EntityMoveEvent(zombie(), at(99), at(100));
        listener.onEntityMove(event);

        assertThat(event.isCancelled())
                .as("MONSTER_ENTRY denied, and the zombie was outside a moment ago — this is an entry")
                .isTrue();
    }

    @Test
    @DisplayName("a zombie stepping from open ground into a claim with entry allowed walks in")
    void allowedEntryLetsAZombieThrough() {
        FakeArea claim = FakeArea.named("somebody's claim").with(LandFlag.MONSTER_ENTRY, true);
        provider.inside = claim;

        EntityMoveEvent event = new EntityMoveEvent(zombie(), at(99), at(100));
        listener.onEntityMove(event);

        assertThat(event.isCancelled()).isFalse();
    }

    @Test
    @DisplayName("a zombie already inside may keep walking about")
    void aZombieAlreadyInsideMayMoveAbout() {
        FakeArea claim = FakeArea.named("somebody's claim").with(LandFlag.MONSTER_ENTRY, false);
        provider.inside = claim;

        EntityMoveEvent event = new EntityMoveEvent(zombie(), at(101), at(102));
        listener.onEntityMove(event);

        assertThat(event.isCancelled())
                .as("both ends are the same claim — this is not an entry")
                .isFalse();
    }

    @Test
    @DisplayName("a non-hostile creature is never stopped by this flag")
    void nonHostileCreaturesAreIgnored() {
        FakeArea claim = FakeArea.named("somebody's claim").with(LandFlag.MONSTER_ENTRY, false);
        provider.inside = claim;

        LivingEntity cow = (LivingEntity) Proxy.newProxyInstance(
                org.bukkit.entity.Cow.class.getClassLoader(), new Class<?>[]{org.bukkit.entity.Cow.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getType" -> EntityType.COW;
                    case "toString" -> "a fake cow";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> method.getReturnType().isPrimitive()
                            ? (method.getReturnType() == boolean.class ? false : 0)
                            : null;
                });

        EntityMoveEvent event = new EntityMoveEvent(cow, at(99), at(100));
        listener.onEntityMove(event);

        assertThat(event.isCancelled()).isFalse();
    }

    @Test
    @DisplayName("somebody's pet monster walks in: it counts as its owner, not as a monster")
    void aCompanionIsNotStopped() {
        FakeArea claim = FakeArea.named("somebody's claim").with(LandFlag.MONSTER_ENTRY, false);
        provider.inside = claim;
        java.util.UUID petId = java.util.UUID.randomUUID();
        LivingEntity pet = (LivingEntity) Proxy.newProxyInstance(
                Zombie.class.getClassLoader(), new Class<?>[]{Zombie.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getType" -> EntityType.ZOMBIE;
                    case "getUniqueId" -> petId;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> method.getReturnType().isPrimitive()
                            ? (method.getReturnType() == boolean.class ? false : 0)
                            : null;
                });
        MobControlListener withPets = new MobControlListener(land, petId::equals);

        EntityMoveEvent event = new EntityMoveEvent(pet, at(99), at(100));
        withPets.onEntityMove(event);

        assertThat(event.isCancelled()).isFalse();
    }
}
