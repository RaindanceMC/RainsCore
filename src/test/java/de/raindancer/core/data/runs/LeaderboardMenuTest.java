package de.raindancer.core.data.runs;

import de.raindancer.core.testkit.TestDatabases;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.testkit.TestPlayers;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The leaderboard screen's icons, built server-free with the testkit. */
@org.junit.jupiter.api.Tag("database")
class LeaderboardMenuTest {

    @TempDir
    Path folder;
    private Database database;
    private RunHistory history;
    private final Player sam = TestPlayers.player("Sam");
    private final Player alex = TestPlayers.player("Alex");

    @BeforeEach
    void setUp() {
        database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE, () -> false);
        history = new RunHistory(database, "speedrun");
        history.add(Run.timed("speedrun/solo", Duration.ofMinutes(20)).player(alex.getUniqueId(), "Alex")
                .startedAt(Instant.ofEpochSecond(10)).split("nether", Duration.ofMinutes(5)).build());
        history.add(Run.timed("speedrun/solo", Duration.ofMinutes(25)).player(sam.getUniqueId(), "Sam")
                .startedAt(Instant.ofEpochSecond(20)).split("nether", Duration.ofMinutes(6)).build());
        history.add(Run.timed("speedrun/solo", Duration.ofMinutes(30)).player(sam.getUniqueId(), "Sam")
                .startedAt(Instant.ofEpochSecond(30)).build());
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    private static String text(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static List<String> lore(ItemStack icon) {
        return icon.lore().stream().map(LeaderboardMenuTest::text).toList();
    }

    @Test
    @DisplayName("gold for first, the gap to the record for the rest, best splits starred, the viewer's own marked")
    void icons() {
        LeaderboardMenu menu = new LeaderboardMenu(sam, null, null, history, "speedrun/solo");
        List<Run> board = menu.entries();

        assertThat(board).hasSize(2).as("each player's best");
        ItemStack first = menu.icon(board.get(0));
        ItemStack second = menu.icon(board.get(1));

        assertThat(first.getType()).isEqualTo(Material.GOLD_BLOCK);
        assertThat(text(first.getItemMeta().displayName())).isEqualTo("1st Alex");
        assertThat(lore(first)).contains("20:00.000", "nether 5:00.000 ★").doesNotContain("This is yours.");
        assertThat(second.getType()).isEqualTo(Material.IRON_BLOCK);
        assertThat(lore(second)).contains("+5:00.000 off the record", "nether 6:00.000", "This is yours.");
    }

    @Test
    @DisplayName("every run, when asked, includes repeats")
    void everyRun() {
        assertThat(new LeaderboardMenu(sam, null, null, history, "speedrun/solo").everyRun().entries()).hasSize(3);
    }
}
