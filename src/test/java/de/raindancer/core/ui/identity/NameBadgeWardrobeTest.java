package de.raindancer.core.ui.identity;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NameBadgeWardrobeTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    @BeforeEach
    void setUp() {
        NameBadges.clear();
    }

    @AfterEach
    void tearDown() {
        NameBadges.clear();
    }

    private static String plain(UUID player, NameBadges.Place place) {
        return PlainTextComponentSerializer.plainText().serialize(NameBadges.before(player, place));
    }

    @Test
    @DisplayName("only badges registered with a label are offered to wear, in registration order")
    void offered() {
        NameBadges.register("mode", player -> Optional.of(Component.text("[Mode]")));
        NameBadges.register("guild", "Guild tag", player -> Optional.of(Component.text("[ABC]")));
        NameBadges.registerPlaced("guild-honours", "Guild stars", (player, place) -> Optional.of(Component.text("*")));

        assertThat(NameBadges.wearables()).containsExactly(
                new NameBadges.Wearable("guild", "Guild tag"), new NameBadges.Wearable("guild-honours", "Guild stars"));

        NameBadges.unregister("guild");
        assertThat(NameBadges.wearables()).extracting(NameBadges.Wearable::owner).containsExactly("guild-honours");
    }

    @Test
    @DisplayName("the wardrobe takes off a wearable badge, for that player only and everywhere it shows")
    void hidden() {
        NameBadges.register("mode", player -> Optional.of(Component.text("[Mode]")));
        NameBadges.register("guild", "Guild tag", player -> Optional.of(Component.text("[ABC]")));
        Set<UUID> hidGuild = Set.of(ALICE);
        NameBadges.wardrobe((player, owner) -> !(owner.equals("guild") && hidGuild.contains(player)));

        assertThat(plain(ALICE, NameBadges.Place.CHAT)).isEqualTo("[Mode] ");
        assertThat(plain(ALICE, NameBadges.Place.TABLIST)).isEqualTo("[Mode] ");
        assertThat(plain(BOB, NameBadges.Place.CHAT)).isEqualTo("[Mode] [ABC] ");
    }

    @Test
    @DisplayName("a badge nobody may choose stays on even when the wardrobe says no")
    void notWearableStays() {
        NameBadges.register("mode", player -> Optional.of(Component.text("[Mode]")));
        NameBadges.wardrobe((player, owner) -> false);
        assertThat(plain(ALICE, NameBadges.Place.CHAT)).isEqualTo("[Mode] ");
    }

    @Test
    @DisplayName("a wardrobe that throws hides nothing and costs no chat line")
    void brokenWardrobe() {
        NameBadges.register("guild", "Guild tag", player -> Optional.of(Component.text("[ABC]")));
        NameBadges.wardrobe((player, owner) -> {
            throw new IllegalStateException("boom");
        });
        assertThat(plain(ALICE, NameBadges.Place.CHAT)).isEqualTo("[ABC] ");
    }

    @Test
    @DisplayName("the preview shows a badge the player has, worn or not, so a menu can show what they would wear")
    void preview() {
        NameBadges.register("guild", "Guild tag", player -> player.equals(ALICE)
                ? Optional.of(Component.text("[ABC]")) : Optional.empty());
        NameBadges.wardrobe((player, owner) -> false);

        assertThat(NameBadges.preview(ALICE, "guild", NameBadges.Place.CHAT)).isPresent();
        assertThat(NameBadges.preview(BOB, "guild", NameBadges.Place.CHAT)).isEmpty();
        assertThat(NameBadges.preview(ALICE, "nobody", NameBadges.Place.CHAT)).isEmpty();
        assertThat(NameBadges.wears(ALICE, "guild")).isFalse();
    }

    @Test
    @DisplayName("the wardrobe goes with the plugin that brought it")
    void forgotten() {
        NameBadges.register("guild", "Guild tag", player -> Optional.of(Component.text("[ABC]")));
        NameBadges.wardrobe((player, owner) -> false);
        assertThat(NameBadges.hasWardrobe()).isTrue();

        NameBadges.forgetFrom(getClass().getClassLoader());

        assertThat(NameBadges.hasWardrobe()).isFalse();
        assertThat(NameBadges.wearables()).isEmpty();
    }
}
