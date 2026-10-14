package de.raindancer.core.ui.identity;

import de.raindancer.core.testkit.TestDatabases;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@org.junit.jupiter.api.Tag("database")
class NameBadgesTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    @TempDir
    Path directory;
    private Database database;
    private Identities identities;

    @BeforeEach
    void setUp() {
        NameBadges.clear();
        database = TestDatabases.open(directory.resolve("core.db"), CoreSchema.CORE, () -> false);
        identities = new Identities(database);
    }

    @AfterEach
    void tearDown() {
        NameBadges.clear();
        database.close();
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    @DisplayName("with nothing registered a name is drawn exactly as before")
    void nothingRegistered() {
        identities.setPrefix(ALICE, "<gold>[Admin] ");
        assertThat(plain(identities.chatName(ALICE, "Alice"))).isEqualTo("[Admin] Alice");
        assertThat(plain(identities.tablistName(ALICE, "Alice"))).isEqualTo("[Admin] Alice");
    }

    @Test
    @DisplayName("a badge goes in front of the prefix, in chat and in the tablist, and only for whom it answers")
    void inFront() {
        identities.setPrefix(ALICE, "<gold>[Admin] ");
        NameBadges.register("guilds", player -> player.equals(ALICE)
                ? Optional.of(Component.text("[ELF]")) : Optional.empty());

        assertThat(plain(identities.chatName(ALICE, "Alice"))).isEqualTo("[ELF] [Admin] Alice");
        assertThat(plain(identities.tablistName(ALICE, "Alice"))).isEqualTo("[ELF] [Admin] Alice");
        assertThat(plain(identities.chatName(BOB, "Bob"))).isEqualTo("Bob");
    }

    @Test
    @DisplayName("several badges keep the order they were registered in; registering again under a name replaces")
    void orderAndReplace() {
        NameBadges.register("a", player -> Optional.of(Component.text("[A]")));
        NameBadges.register("b", player -> Optional.of(Component.text("[B]")));
        assertThat(plain(NameBadges.before(ALICE))).isEqualTo("[A] [B] ");

        NameBadges.register("a", player -> Optional.of(Component.text("[A2]")));
        assertThat(plain(NameBadges.before(ALICE))).isEqualTo("[A2] [B] ");
        assertThat(NameBadges.owners()).containsExactly("a", "b");
    }

    @Test
    @DisplayName("unregistering takes the badge away")
    void unregister() {
        NameBadges.register("guilds", player -> Optional.of(Component.text("[ELF]")));
        assertThat(NameBadges.unregister("guilds")).isTrue();
        assertThat(NameBadges.unregister("guilds")).isFalse();
        assertThat(plain(identities.chatName(ALICE, "Alice"))).isEqualTo("Alice");
    }

    @Test
    @DisplayName("a badge that throws is skipped, never taking the chat line with it")
    void throwingBadge() {
        AtomicInteger asked = new AtomicInteger();
        NameBadges.register("broken", player -> {
            asked.incrementAndGet();
            throw new IllegalStateException("database closed");
        });
        NameBadges.register("fine", player -> Optional.of(Component.text("[OK]")));

        assertThat(plain(identities.chatName(ALICE, "Alice"))).isEqualTo("[OK] Alice");
        assertThat(asked).hasValue(1);
    }

    @Test
    @DisplayName("a null answer counts as no badge")
    void nullAnswer() {
        NameBadges.register("lazy", player -> null);
        assertThat(plain(identities.chatName(ALICE, "Alice"))).isEqualTo("Alice");
    }

    @Test
    @DisplayName("a plugin going away takes its badges with it")
    void forgetFrom() {
        NameBadges.register("guilds", player -> Optional.of(Component.text("[ELF]")));
        assertThat(NameBadges.forgetFrom(NameBadgesTest.class.getClassLoader())).isEqualTo(1);
        assertThat(NameBadges.owners()).isEmpty();
        assertThat(NameBadges.forgetFrom(null)).isZero();
    }

    @Test
    @DisplayName("a badge registered without places stays out of the nametag, as it always did")
    void unplacedStaysOffTheNametag() {
        NameBadges.register("guilds", player -> Optional.of(Component.text("[ELF]")));
        assertThat(plain(identities.nametag(ALICE, "Alice"))).isEqualTo("Alice");
        assertThat(plain(NameBadges.before(ALICE, NameBadges.Place.TABLIST))).isEqualTo("[ELF] ");
        assertThat(plain(NameBadges.before(ALICE, NameBadges.Place.NAMETAG))).isEmpty();
    }

    @Test
    @DisplayName("a placed badge is asked per place: chat, tablist and nametag each get only what it answers there")
    void placed() {
        identities.setPrefix(ALICE, "<gold>[Admin] ");
        NameBadges.registerPlaced("ranks", (player, place) -> place == NameBadges.Place.TABLIST
                ? Optional.empty() : Optional.of(Component.text("[OWNER]")));

        assertThat(plain(identities.chatName(ALICE, "Alice"))).isEqualTo("[OWNER] [Admin] Alice");
        assertThat(plain(identities.tablistName(ALICE, "Alice"))).isEqualTo("[Admin] Alice");
        assertThat(plain(identities.nametag(ALICE, "Alice"))).isEqualTo("[OWNER] [Admin] Alice");
    }

    @Test
    @DisplayName("before(player) without a place is the chat's answer, so older callers draw what they did")
    void beforeWithoutPlaceIsChat() {
        NameBadges.registerPlaced("ranks", (player, place) -> Optional.of(Component.text("[" + place + "]")));
        assertThat(plain(NameBadges.before(ALICE))).isEqualTo("[CHAT] ");
    }

    @Test
    @DisplayName("placed and unplaced badges share one order, and one owner name replaces across both kinds")
    void placedAndUnplacedShareOwners() {
        NameBadges.register("a", player -> Optional.of(Component.text("[A]")));
        NameBadges.registerPlaced("b", (player, place) -> Optional.of(Component.text("[B]")));
        assertThat(plain(NameBadges.before(ALICE, NameBadges.Place.CHAT))).isEqualTo("[A] [B] ");

        NameBadges.registerPlaced("a", (player, place) -> Optional.of(Component.text("[A2]")));
        assertThat(plain(NameBadges.before(ALICE, NameBadges.Place.NAMETAG))).isEqualTo("[A2] [B] ");
        assertThat(NameBadges.owners()).containsExactly("a", "b");
    }

    @Test
    @DisplayName("a placed badge is forgotten with the plugin whose code it is, not with Core's wrapper")
    void forgetPlaced() {
        NameBadges.registerPlaced("ranks", (player, place) -> Optional.of(Component.text("[OWNER]")));
        assertThat(NameBadges.forgetFrom(NameBadgesTest.class.getClassLoader())).isEqualTo(1);
        assertThat(NameBadges.owners()).isEmpty();
    }

    @Test
    @DisplayName("a long badge in a nametag is clipped with the rest, never past the nametag's limit")
    void nametagStillClipped() {
        NameBadges.registerPlaced("ranks", (player, place) -> Optional.of(Component.text("[" + "X".repeat(40) + "]")));
        assertThat(plain(identities.nametag(ALICE, "Alice")).length()).isEqualTo(Identities.MAX_NAMETAG_CHARS);
    }
}
