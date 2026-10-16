package de.raindancer.core.social.friends;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.testkit.TestDatabases;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FriendsTest {

    @TempDir
    Path folder;
    private Database database;
    private Friends friends;
    private final UUID lilly = UUID.randomUUID();
    private final UUID tom = UUID.randomUUID();
    private final UUID ben = UUID.randomUUID();

    @BeforeEach
    void open() {
        database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE);
        friends = new Friends(database, () -> 1_000);
    }

    @AfterEach
    void close() {
        database.close();
    }

    @Test
    void aRequestAcceptedMakesTwoFriends() {
        assertEquals(Friends.Outcome.SENT, friends.ask(tom, lilly));
        assertEquals(List.of(tom), friends.requestsTo(lilly));
        assertEquals(Friends.Outcome.ALREADY_ASKED, friends.ask(tom, lilly));
        assertEquals(Friends.Outcome.ACCEPTED, friends.accept(lilly, tom));
        assertTrue(friends.are(tom, lilly));
        assertTrue(friends.are(lilly, tom));
        assertEquals(List.of(lilly), friends.of(tom));
        assertEquals(List.of(), friends.requestsTo(lilly));
        assertEquals(Friends.Outcome.ALREADY_FRIENDS, friends.ask(lilly, tom));
    }

    @Test
    void askingSomebodyWhoAskedYouIsYes() {
        friends.ask(tom, lilly);
        assertEquals(Friends.Outcome.ACCEPTED, friends.ask(lilly, tom));
        assertTrue(friends.are(tom, lilly));
    }

    @Test
    void aRequestCanBeTurnedDownAndAFriendshipEnded() {
        friends.ask(ben, lilly);
        assertEquals(Friends.Outcome.DENIED, friends.deny(lilly, ben));
        assertFalse(friends.are(ben, lilly));
        assertEquals(Friends.Outcome.NO_REQUEST, friends.accept(lilly, ben));
        friends.ask(tom, lilly);
        friends.accept(lilly, tom);
        assertEquals(Friends.Outcome.REMOVED, friends.remove(tom, lilly));
        assertFalse(friends.are(lilly, tom));
        assertEquals(Friends.Outcome.NOT_FRIENDS, friends.remove(tom, lilly));
    }

    @Test
    void nobodyIsTheirOwnFriend() {
        assertEquals(Friends.Outcome.SELF, friends.ask(tom, tom));
    }
}
