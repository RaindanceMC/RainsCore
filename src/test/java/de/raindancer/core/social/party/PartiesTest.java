package de.raindancer.core.social.party;

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
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartiesTest {

    @TempDir
    Path folder;
    private Database database;
    private final AtomicLong now = new AtomicLong(1_000);
    private Parties parties;
    private final UUID leader = UUID.randomUUID();
    private final UUID friend = UUID.randomUUID();
    private final UUID third = UUID.randomUUID();

    @BeforeEach
    void open() {
        database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE);
        parties = new Parties(database, now::get);
    }

    @AfterEach
    void close() {
        database.close();
    }

    @Test
    void anInviteAcceptedMakesAPartyLedByWhoInvited() {
        assertEquals(Parties.Outcome.INVITED, parties.invite(leader, friend));
        assertEquals(Parties.Outcome.JOINED, parties.accept(friend, leader));
        Parties.Party party = parties.of(friend).orElseThrow();
        assertEquals(leader, party.leader());
        assertEquals(List.of(leader, friend), party.members());
        assertEquals(party, parties.of(leader).orElseThrow());
    }

    @Test
    void onlyTheLeaderInvitesAndKicks() {
        parties.invite(leader, friend);
        parties.accept(friend, leader);
        assertEquals(Parties.Outcome.NOT_LEADER, parties.invite(friend, third));
        assertEquals(Parties.Outcome.NOT_LEADER, parties.kick(friend, leader));
        assertEquals(Parties.Outcome.KICKED, parties.kick(leader, friend));
        assertTrue(parties.of(friend).isEmpty());
    }

    @Test
    void anInviteRunsOutAfterTwoMinutes() {
        parties.invite(leader, friend);
        now.addAndGet(Parties.INVITE_MILLIS + 1);
        assertEquals(Parties.Outcome.NO_INVITE, parties.accept(friend, leader));
    }

    @Test
    void whenTheLeaderLeavesTheNextOneLeadsAndTheLastOneEndsIt() {
        parties.invite(leader, friend);
        now.addAndGet(10);
        parties.accept(friend, leader);
        parties.invite(leader, third);
        now.addAndGet(10);
        parties.accept(third, leader);
        assertEquals(Parties.Outcome.LEFT, parties.leave(leader));
        assertEquals(friend, parties.of(third).orElseThrow().leader());
        parties.leave(friend);
        assertEquals(List.of(third), parties.of(third).orElseThrow().members());
        parties.leave(third);
        assertTrue(parties.of(third).isEmpty());
    }

    @Test
    void somebodyInAPartyCannotJoinAnother() {
        parties.invite(leader, friend);
        parties.accept(friend, leader);
        assertEquals(Parties.Outcome.ALREADY_IN_ONE, parties.invite(third, friend));
    }

    @Test
    void aPartyHasRoomForEight() {
        for (int i = 0; i < Parties.MOST - 1; i++) {
            UUID member = UUID.randomUUID();
            parties.invite(leader, member);
            assertEquals(Parties.Outcome.JOINED, parties.accept(member, leader));
        }
        assertEquals(Parties.Outcome.FULL, parties.invite(leader, third));
    }
}
