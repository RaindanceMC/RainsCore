package de.raindancer.core.world.protection;

import de.raindancer.core.ui.messages.Messages;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Cow;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A creature actually spawning inside a claim, rather than the source-text scan {@code ProtectionRulesTest}
 * settles for.
 *
 * <h2>The mistake this exists to catch</h2>
 * {@code MONSTER_SPAWNING} and {@code SPAWNER_SPAWNING} are two different flags on purpose — {@link
 * MobControlListener#onSpawn} answers a spawner-triggered spawn with the second and everything else natural
 * with the first, before either one is consulted. A dungeon inside a claim that only ever turned off "monster
 * spawning" keeps its spawner running, which reads as the flag not working at all rather than as the separate
 * setting it actually is.
 */
class MobSpawnTest {

    private static final World WORLD = (World) Proxy.newProxyInstance(
            World.class.getClassLoader(), new Class<?>[]{World.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getName" -> "world";
                case "toString" -> "a fake world";
                case "hashCode" -> 1;
                case "equals" -> proxy == args[0];
                default -> null;
            });

    private static final Location INSIDE = new Location(WORLD, 0, 64, 0);

    /** Answers with the one area for every location — spawns are all judged the same spot here. */
    private static final class Fake implements LandProvider {
        FakeArea claim;

        @Override
        public String name() {
            return "the fake provider";
        }

        @Override
        public Optional<ProtectedArea> at(Location location) {
            return Optional.ofNullable(claim);
        }

        @Override
        public boolean hasAnyIn(World world) {
            return claim != null;
        }
    }

    private final LandPolicies policies = LandPolicies.builtIn();
    private final Fake provider = new Fake();
    private Land land;
    private MobControlListener listener;

    @BeforeEach
    void setUp() {
        Messages messages = new Messages(Path.of("target", "mob-spawn-test-messages.yml"));
        land = new Land(policies, messages, new AtomicLong(1_000_000L)::get);
        land.provider(provider);
        listener = new MobControlListener(land);
    }

    private static LivingEntity entityAt(Class<? extends LivingEntity> kind, EntityType type,
                                         Location location) {
        return kind.cast(Proxy.newProxyInstance(kind.getClassLoader(), new Class<?>[]{kind},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getType" -> type;
                    case "getLocation" -> location;
                    case "toString" -> "a fake " + type;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> method.getReturnType().isPrimitive()
                            ? (method.getReturnType() == boolean.class ? false : 0)
                            : null;
                }));
    }

    @Nested
    @DisplayName("a natural spawn")
    class Natural {

        @Test
        @DisplayName("a hostile mob is refused when MONSTER_SPAWNING is denied")
        void deniedMonsterSpawningStopsAZombie() {
            provider.claim = FakeArea.named("somebody's claim").with(LandFlag.MONSTER_SPAWNING, false);
            CreatureSpawnEvent event = new CreatureSpawnEvent(
                    entityAt(Zombie.class, EntityType.ZOMBIE, INSIDE),
                    CreatureSpawnEvent.SpawnReason.NATURAL);

            listener.onSpawn(event);

            assertThat(event.isCancelled()).isTrue();
        }

        @Test
        @DisplayName("a camel husk spawning in the desert is a monster spawn, not an animal one")
        void aCamelHuskReadsMonsterSpawning() {
            provider.claim = FakeArea.named("somebody's claim")
                    .with(LandFlag.MONSTER_SPAWNING, false).with(LandFlag.ANIMAL_SPAWNING, true);
            LivingEntity husk = (LivingEntity) Proxy.newProxyInstance(
                    org.bukkit.entity.CamelHusk.class.getClassLoader(),
                    new Class<?>[]{org.bukkit.entity.CamelHusk.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getType" -> EntityType.CAMEL_HUSK;
                        case "getSpawnCategory" -> org.bukkit.entity.SpawnCategory.MONSTER;
                        case "getLocation" -> INSIDE;
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> method.getReturnType().isPrimitive()
                                ? (method.getReturnType() == boolean.class ? false : 0)
                                : null;
                    });
            CreatureSpawnEvent event = new CreatureSpawnEvent(husk, CreatureSpawnEvent.SpawnReason.NATURAL);

            listener.onSpawn(event);

            assertThat(event.isCancelled()).isTrue();
        }

        @Test
        @DisplayName("the parched a husk brings along spawns with no reason at all, and is still the world's doing")
        void aParchedBroughtAlongByAHuskIsRefused() {
            provider.claim = FakeArea.named("somebody's claim").with(LandFlag.MONSTER_SPAWNING, false);
            // Husk.finalizeSpawn adds its camel husk and parched with a bare addFreshEntity, which Bukkit
            // reports as DEFAULT. The husk itself can be refused and those two stay behind in the claim.
            CreatureSpawnEvent event = new CreatureSpawnEvent(
                    entityAt(org.bukkit.entity.Parched.class, EntityType.PARCHED, INSIDE),
                    CreatureSpawnEvent.SpawnReason.DEFAULT);

            listener.onSpawn(event);

            assertThat(event.isCancelled()).isTrue();
        }

        @Test
        @DisplayName("a hostile mob spawns when MONSTER_SPAWNING is allowed")
        void allowedMonsterSpawningLetsAZombieThrough() {
            provider.claim = FakeArea.named("somebody's claim").with(LandFlag.MONSTER_SPAWNING, true);
            CreatureSpawnEvent event = new CreatureSpawnEvent(
                    entityAt(Zombie.class, EntityType.ZOMBIE, INSIDE),
                    CreatureSpawnEvent.SpawnReason.NATURAL);

            listener.onSpawn(event);

            assertThat(event.isCancelled()).isFalse();
        }

        @Test
        @DisplayName("a peaceful creature is judged by ANIMAL_SPAWNING, not MONSTER_SPAWNING")
        void peacefulCreaturesReadTheOtherFlag() {
            provider.claim = FakeArea.named("somebody's claim")
                    .with(LandFlag.MONSTER_SPAWNING, true)
                    .with(LandFlag.ANIMAL_SPAWNING, false);
            CreatureSpawnEvent event = new CreatureSpawnEvent(
                    entityAt(Cow.class, EntityType.COW, INSIDE),
                    CreatureSpawnEvent.SpawnReason.NATURAL);

            listener.onSpawn(event);

            assertThat(event.isCancelled())
                    .as("MONSTER_SPAWNING being allowed must not let a cow through a denied ANIMAL_SPAWNING")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("a wandering trader")
    class Trader {

        @Test
        @DisplayName("is refused, llamas too, where TRADER_SPAWNING is denied, whatever ANIMAL_SPAWNING says")
        void deniedTraderSpawning() {
            provider.claim = FakeArea.named("spawn").with(LandFlag.TRADER_SPAWNING, false)
                    .with(LandFlag.ANIMAL_SPAWNING, true);
            CreatureSpawnEvent trader = new CreatureSpawnEvent(
                    entityAt(org.bukkit.entity.WanderingTrader.class, EntityType.WANDERING_TRADER, INSIDE),
                    CreatureSpawnEvent.SpawnReason.NATURAL);
            CreatureSpawnEvent llama = new CreatureSpawnEvent(
                    entityAt(org.bukkit.entity.TraderLlama.class, EntityType.TRADER_LLAMA, INSIDE),
                    CreatureSpawnEvent.SpawnReason.NATURAL);

            listener.onSpawn(trader);
            listener.onSpawn(llama);

            assertThat(trader.isCancelled()).isTrue();
            assertThat(llama.isCancelled()).isTrue();
        }

        @Test
        @DisplayName("comes as before where nobody touched the flag")
        void allowedByDefault() {
            provider.claim = FakeArea.named("somebody's claim");
            CreatureSpawnEvent trader = new CreatureSpawnEvent(
                    entityAt(org.bukkit.entity.WanderingTrader.class, EntityType.WANDERING_TRADER, INSIDE),
                    CreatureSpawnEvent.SpawnReason.NATURAL);

            listener.onSpawn(trader);

            assertThat(trader.isCancelled()).isFalse();
        }
    }

    @Nested
    @DisplayName("a spawner")
    class Spawner {

        /**
         * The exact confusion this pair of flags invites: MONSTER_SPAWNING denied, SPAWNER_SPAWNING left at
         * its default (allowed), and a dungeon inside the claim keeps producing zombies regardless — read as
         * "monster spawning does not work" when it is doing exactly what it was asked.
         */
        @Test
        @DisplayName("MONSTER_SPAWNING denied does not stop a spawner — that is SPAWNER_SPAWNING's job")
        void monsterSpawningDoesNotGovernSpawners() {
            provider.claim = FakeArea.named("somebody's claim").with(LandFlag.MONSTER_SPAWNING, false);
            CreatureSpawnEvent event = new CreatureSpawnEvent(
                    entityAt(Zombie.class, EntityType.ZOMBIE, INSIDE),
                    CreatureSpawnEvent.SpawnReason.SPAWNER);

            listener.onSpawn(event);

            assertThat(event.isCancelled())
                    .as("SPAWNER_SPAWNING was never touched, and its built-in default is allowed")
                    .isFalse();
        }

        @Test
        @DisplayName("a spawner is refused once SPAWNER_SPAWNING is denied")
        void deniedSpawnerSpawningStopsIt() {
            provider.claim = FakeArea.named("somebody's claim").with(LandFlag.SPAWNER_SPAWNING, false);
            CreatureSpawnEvent event = new CreatureSpawnEvent(
                    entityAt(Zombie.class, EntityType.ZOMBIE, INSIDE),
                    CreatureSpawnEvent.SpawnReason.SPAWNER);

            listener.onSpawn(event);

            assertThat(event.isCancelled()).isTrue();
        }

        /**
         * A trial chamber's vault room, which vanilla tracks under its own reason rather than SPAWNER —
         * governed by nothing at all until this was noticed, so a claim over a trial chamber kept producing
         * mobs with both spawning flags denied.
         */
        @Test
        @DisplayName("a trial spawner reads SPAWNER_SPAWNING too, not a flag of its own")
        void trialSpawnersReadTheSameFlagAsAnOrdinarySpawner() {
            provider.claim = FakeArea.named("somebody's claim").with(LandFlag.SPAWNER_SPAWNING, false);
            CreatureSpawnEvent event = new CreatureSpawnEvent(
                    entityAt(Zombie.class, EntityType.ZOMBIE, INSIDE),
                    CreatureSpawnEvent.SpawnReason.TRIAL_SPAWNER);

            listener.onSpawn(event);

            assertThat(event.isCancelled()).isTrue();
        }
    }

    @Test
    @DisplayName("an egg, a breeding or a command spawn is not governed at all")
    void unnaturalReasonsAreLeftAlone() {
        provider.claim = FakeArea.named("somebody's claim").with(LandFlag.MONSTER_SPAWNING, false);
        CreatureSpawnEvent event = new CreatureSpawnEvent(
                entityAt(Zombie.class, EntityType.ZOMBIE, INSIDE),
                CreatureSpawnEvent.SpawnReason.SPAWNER_EGG);

        listener.onSpawn(event);

        assertThat(event.isCancelled()).isFalse();
    }

    @Test
    @DisplayName("open ground never refuses a spawn, whatever the built-in default says")
    void unclaimedGroundIsNeverTouched() {
        provider.claim = null;
        CreatureSpawnEvent event = new CreatureSpawnEvent(
                entityAt(Zombie.class, EntityType.ZOMBIE, INSIDE),
                CreatureSpawnEvent.SpawnReason.NATURAL);

        listener.onSpawn(event);

        assertThat(event.isCancelled())
                .as("MONSTER_SPAWNING's built-in default is denied — reading that as the answer for the "
                        + "wilderness once switched off every hostile mob on the entire server")
                .isFalse();
    }

    /**
     * The actual bug behind "monster spawning is off but they keep spawning right where I'm standing":
     * an admin's own forgotten {@code /claimadmin bypass} toggle used to suspend spawning for everyone
     * near them, silently, because {@code flagDenied} read the flag through {@code LandFlags.isAllowedAt}
     * — the same call {@code onEntityMove} deliberately does not make, for the same reason. Spawning is
     * the world acting on its own, not the admin doing something that would otherwise be refused, so a
     * bypassing admin standing nearby must not turn monster control off for everybody else. Reproduced
     * here by swapping {@code Bukkit}'s static server for the one test that needs to answer
     * {@code Bukkit.getPlayer(uuid)}, and put back immediately after — nothing else in this suite may see
     * it, or a later test's {@code Bukkit.getServer()} call would silently answer with this fake.
     */
    @Test
    @DisplayName("MONSTER_SPAWNING denied is honoured even while a bypassing admin stands right there")
    void bypassPresenceDoesNotSuspendSpawning() {
        java.util.UUID adminId = java.util.UUID.randomUUID();
        Player admin = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> adminId;
                    case "getLocation" -> INSIDE;
                    case "hasPermission" -> Land.BYPASS_PERMISSION.equals(args[0]);
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> adminId.hashCode();
                    default -> null;
                });
        org.bukkit.Server fakeServer = (org.bukkit.Server) Proxy.newProxyInstance(
                org.bukkit.Server.class.getClassLoader(), new Class<?>[]{org.bukkit.Server.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getPlayer" -> adminId.equals(args[0]) ? admin : null;
                    case "toString" -> "a fake server";
                    default -> method.getReturnType().isPrimitive()
                            ? (method.getReturnType() == boolean.class ? false : 0)
                            : null;
                });

        java.lang.reflect.Field serverField;
        org.bukkit.Server realServer;
        try {
            serverField = org.bukkit.Bukkit.class.getDeclaredField("server");
            serverField.setAccessible(true);
            realServer = (org.bukkit.Server) serverField.get(null);
            serverField.set(null, fakeServer);
        } catch (ReflectiveOperationException unreachable) {
            throw new AssertionError("Bukkit.server is not the field this test expects", unreachable);
        }
        try {
            provider.claim = FakeArea.named("somebody's claim").with(LandFlag.MONSTER_SPAWNING, false);

            CreatureSpawnEvent beforeBypass = new CreatureSpawnEvent(
                    entityAt(Zombie.class, EntityType.ZOMBIE, INSIDE), CreatureSpawnEvent.SpawnReason.NATURAL);
            listener.onSpawn(beforeBypass);
            assertThat(beforeBypass.isCancelled()).as("denied before anybody has bypassed anything").isTrue();

            land.toggleBypass(admin);
            CreatureSpawnEvent whileBypassing = new CreatureSpawnEvent(
                    entityAt(Zombie.class, EntityType.ZOMBIE, INSIDE), CreatureSpawnEvent.SpawnReason.NATURAL);
            listener.onSpawn(whileBypassing);
            assertThat(whileBypassing.isCancelled())
                    .as("the fix: an admin standing here with bypass on must not switch spawning back on "
                            + "for a mob that has nothing to do with them")
                    .isTrue();

            land.toggleBypass(admin);
            CreatureSpawnEvent afterBypass = new CreatureSpawnEvent(
                    entityAt(Zombie.class, EntityType.ZOMBIE, INSIDE), CreatureSpawnEvent.SpawnReason.NATURAL);
            listener.onSpawn(afterBypass);
            assertThat(afterBypass.isCancelled()).as("still denied once the toggle is off again").isTrue();
        } finally {
            try {
                serverField.set(null, realServer);
            } catch (ReflectiveOperationException unreachable) {
                throw new AssertionError("could not restore Bukkit.server", unreachable);
            }
        }
    }
}

