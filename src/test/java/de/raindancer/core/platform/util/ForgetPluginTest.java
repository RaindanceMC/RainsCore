package de.raindancer.core.platform.util;

import de.raindancer.core.testkit.TestDatabases;

import de.raindancer.core.content.achievement.Achievements;
import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.world.combat.Combat;
import de.raindancer.core.world.manage.WorldEntryRules;
import de.raindancer.core.world.protection.Land;
import de.raindancer.core.world.protection.LandPolicies;
import de.raindancer.core.world.protection.LandProvider;
import de.raindancer.core.world.protection.ProtectedArea;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A plugin disabled without taking back what it registered: Core drops it, and nothing that belongs
 * to anybody else. In a unit test every class has one loader, so "this plugin" is the test's loader and
 * "some other plugin" is a fresh, empty one.
 */
@org.junit.jupiter.api.Tag("database")
class ForgetPluginTest {

    private final ClassLoader thisPlugin = getClass().getClassLoader();
    private final ClassLoader otherPlugin = new URLClassLoader(new URL[0], null);

    @TempDir
    Path folder;

    @Test
    @DisplayName("combat rules and world entry rules go with the plugin that added them")
    void rules() {
        Combat combat = new Combat();
        combat.alsoAsk("arena", attack -> null);
        WorldEntryRules entry = new WorldEntryRules();
        entry.register("arena", (player, world) -> Optional.empty());

        assertThat(combat.forgetFrom(otherPlugin)).isZero();
        assertThat(entry.forgetFrom(otherPlugin)).isZero();
        assertThat(combat.forgetFrom(thisPlugin)).isEqualTo(1);
        assertThat(entry.forgetFrom(thisPlugin)).isEqualTo(1);
    }

    @Test
    @DisplayName("a land provider left behind is stood down, so the plugin's next start can register again")
    void landProvider() {
        Land land = new Land(LandPolicies.builtIn(), null, System::currentTimeMillis);
        LandProvider stale = new LandProvider() {
            @Override
            public String name() {
                return "claims";
            }

            @Override
            public Optional<ProtectedArea> at(Location location) {
                return Optional.empty();
            }

            @Override
            public boolean hasAnyIn(World world) {
                return false;
            }
        };
        land.provider(stale);

        assertThat(land.forgetFrom(otherPlugin)).isFalse();
        assertThat(land.hasProvider()).isTrue();
        assertThat(land.forgetFrom(thisPlugin)).isTrue();
        assertThat(land.hasProvider()).isFalse();
    }

    @Test
    @DisplayName("an achievement listener goes with its plugin")
    void listeners() {
        Database database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE, () -> false);
        try {
            Achievements achievements = new Achievements(folder.resolve("a.yml"), database,
                    System::currentTimeMillis);
            achievements.onEarned((player, achievement) -> { });

            assertThat(achievements.forgetFrom(otherPlugin)).isZero();
            assertThat(achievements.forgetFrom(thisPlugin)).isEqualTo(1);
        } finally {
            database.close();
        }
    }
}
