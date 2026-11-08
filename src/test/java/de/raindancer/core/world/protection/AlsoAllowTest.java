package de.raindancer.core.world.protection;

import de.raindancer.core.ui.messages.Messages;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Something besides the ground itself opening it up — a raid: whoever opted in may break into the bases of others
 * who opted in. Only ever a yes on top of the area's no, never a no on top of its yes.
 */
class AlsoAllowTest {

    private final World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getName" -> "world";
                case "hashCode" -> 1;
                case "equals" -> proxy == args[0];
                default -> null;
            });
    private final FakePlayer owner = new FakePlayer();
    private final FakePlayer raider = new FakePlayer();
    private final FakeArea base = FakeArea.named("base").ownedBy(owner.id());
    private Land land;
    private Location inside;

    @BeforeEach
    void setUp() {
        Messages messages = new Messages(Path.of("target", "also-allow-messages.yml"));
        messages.load(getClass().getResourceAsStream("/messages.yml"));
        land = new Land(LandPolicies.builtIn(), messages, () -> 1_000L);
        land.provider(new LandProvider() {
            @Override
            public String name() {
                return "one base";
            }

            @Override
            public Optional<ProtectedArea> at(Location location) {
                return Optional.of(base);
            }

            @Override
            public boolean hasAnyIn(World w) {
                return true;
            }
        });
        inside = new Location(world, 1, 64, 1);
    }

    @Test
    @DisplayName("nobody else asked: the area's no stands")
    void nothingAdded() {
        assertThat(land.can(raider.player(), inside, LandAction.BREAK)).isFalse();
        assertThat(land.allowedByOthers(raider.player(), base, LandAction.BREAK)).isFalse();
    }

    @Test
    @DisplayName("an extra rule saying yes opens it, for that player and action only")
    void opened() {
        LandRule raids = (player, area, action) -> player.getUniqueId().equals(raider.id()) && action == LandAction.BREAK;
        land.alsoAllow(raids);
        assertThat(land.can(raider.player(), inside, LandAction.BREAK)).isTrue();
        assertThat(land.allowSilently(raider.player(), inside, LandAction.BREAK)).isTrue();
        assertThat(land.can(raider.player(), inside, LandAction.TRADE)).isFalse();
        assertThat(land.can(new FakePlayer().player(), inside, LandAction.BREAK)).isFalse();
        assertThat(land.allowedByOthers(raider.player(), base, LandAction.BREAK)).isTrue();

        assertThat(land.stopAllowing(raids)).isTrue();
        assertThat(land.can(raider.player(), inside, LandAction.BREAK)).isFalse();
    }

    @Test
    @DisplayName("not asked at all when the area already says yes - it is asked on refusals only")
    void notAskedForAYes() {
        AtomicInteger asked = new AtomicInteger();
        land.alsoAllow((player, area, action) -> {
            asked.incrementAndGet();
            return false;
        });
        assertThat(land.can(owner.player(), inside, LandAction.BREAK)).isTrue();
        assertThat(asked).hasValue(0);
        assertThat(land.can(raider.player(), inside, LandAction.BREAK)).isFalse();
        assertThat(asked).hasValue(1);
    }

    @Test
    @DisplayName("a rule that throws is a no, not a broken event")
    void throwingIsNo() {
        land.alsoAllow((player, area, action) -> {
            throw new IllegalStateException("boom");
        });
        assertThat(land.can(raider.player(), inside, LandAction.BREAK)).isFalse();
    }
}
