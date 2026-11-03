package de.raindancer.core.world.activity;

import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ActivitiesTest {

    @AfterEach
    void clear() {
        Activities.clear();
    }

    @Test
    @DisplayName("an activity keeps its what in lower case and never a negative amount")
    void activity() {
        Activity mined = Activity.of(ActivityKind.MINE, "Iron");
        assertThat(mined.what()).isEqualTo("iron");
        assertThat(mined.amount()).isEqualTo(1);
        assertThat(new Activity(ActivityKind.FISH, null, -4).amount()).isZero();
        assertThat(new Activity(ActivityKind.FISH, null, -4).what()).isEmpty();
    }

    @Test
    @DisplayName("ores come in groups with a rarity weight; both iron ores are iron; crops and grown blocks are known")
    void ores() {
        assertThat(Ores.oreOf(Material.IRON_ORE)).contains("iron");
        assertThat(Ores.oreOf(Material.DEEPSLATE_IRON_ORE)).contains("iron");
        assertThat(Ores.oreOf(Material.ANCIENT_DEBRIS)).contains("ancient-debris");
        assertThat(Ores.oreOf(Material.STONE)).isEmpty();
        assertThat(Ores.weight("diamond")).isGreaterThan(Ores.weight("iron"));
        assertThat(Ores.weight("nothing")).isEqualTo(1);
        assertThat(Ores.AGED_CROPS).contains(Material.WHEAT).doesNotContain(Material.MELON);
        assertThat(Ores.GROWN_BLOCKS).contains(Material.MELON, Material.PUMPKIN);
        assertThat(Ores.watched()).contains(Material.IRON_ORE, Material.MELON);
    }

    @Test
    @DisplayName("every listener hears every activity until it stops; one that throws does not stop the others")
    void listening() {
        List<String> heard = new ArrayList<>();
        Object first = Activities.listen(null, (player, activity) -> heard.add("a:" + activity.what()));
        Activities.listen(null, (player, activity) -> {
            throw new IllegalStateException("broken");
        });
        Activities.listen(null, (player, activity) -> heard.add("c:" + activity.what()));
        Activities.tell(null, Activity.of(ActivityKind.HARVEST, "wheat"));
        assertThat(heard).containsExactly("a:wheat", "c:wheat");
        Activities.stop(first);
        Activities.tell(null, Activity.of(ActivityKind.HARVEST, "carrots"));
        assertThat(heard).containsExactly("a:wheat", "c:wheat", "c:carrots");
        assertThat(Activities.listening()).isEqualTo(2);
    }

    @Test
    @DisplayName("nothing is told for an amount of nothing")
    void nothing() {
        List<Activity> heard = new ArrayList<>();
        Activities.listen(null, (player, activity) -> heard.add(activity));
        Activities.tell(null, new Activity(ActivityKind.ENCHANT, "", 0));
        assertThat(heard).isEmpty();
    }
}
