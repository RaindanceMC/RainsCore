package de.raindancer.core.world.protection;

import de.raindancer.core.ui.messages.Messages;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Another plugin asking for a piece of ground to be the server's, without knowing who keeps the claims. */
class HoldForServerTest {

    private final Land land = new Land(LandPolicies.builtIn(),
            new Messages(Path.of("target", "hold-test-messages.yml")), () -> 0L);

    private final World world = (World) Proxy.newProxyInstance(
            World.class.getClassLoader(), new Class<?>[]{World.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getName" -> "world";
                case "hashCode" -> 1;
                case "equals" -> proxy == args[0];
                default -> null;
            });

    /** Only the bare three methods — the way every provider written before this existed looks. */
    private static class OldProvider implements LandProvider {
        @Override public String name() { return "old"; }
        @Override public Optional<ProtectedArea> at(Location location) { return Optional.empty(); }
        @Override public boolean hasAnyIn(World world) { return false; }
    }

    private static final class Holding extends OldProvider {
        final List<String> calls = new ArrayList<>();

        @Override
        public Optional<String> holdForServer(World world, String name, int x1, int z1, int x2, int z2) {
            calls.add("hold " + name + " " + x1 + " " + z1 + " " + x2 + " " + z2);
            return Optional.empty();
        }

        @Override
        public boolean releaseForServer(String name) {
            calls.add("release " + name);
            return true;
        }
    }

    @Test
    void nobodyAnsweringSaysSoInsteadOfPretending() {
        assertThat(land.holdForServer(world, "rtp-north", 0, 0, 6, 6)).isPresent();
        assertThat(land.releaseForServer("rtp-north")).isFalse();
    }

    @Test
    void aProviderThatCannotHoldGroundRefusesWithItsName() {
        land.provider(new OldProvider());
        assertThat(land.holdForServer(world, "rtp-north", 0, 0, 6, 6)).hasValueSatisfying(
                why -> assertThat(why).contains("old"));
        assertThat(land.releaseForServer("rtp-north")).isFalse();
    }

    @Test
    void theCornersArriveSortedWhicheverWayTheyWereGiven() {
        Holding holding = new Holding();
        land.provider(holding);
        assertThat(land.holdForServer(world, "rtp-north", 6, 9, 0, 3)).isEmpty();
        assertThat(land.releaseForServer("rtp-north")).isTrue();
        assertThat(holding.calls).containsExactly("hold rtp-north 0 3 6 9", "release rtp-north");
    }

    @Test
    void nothingToHoldWithoutAWorldOrAName() {
        Holding holding = new Holding();
        land.provider(holding);
        assertThat(land.holdForServer(null, "rtp-north", 0, 0, 6, 6)).isPresent();
        assertThat(land.holdForServer(world, " ", 0, 0, 6, 6)).isPresent();
        assertThat(holding.calls).isEmpty();
    }
}
