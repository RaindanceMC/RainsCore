package de.raindancer.core.social.party;

import de.raindancer.core.data.sql.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Parties across the network, kept in Core's database: the leader invites, an invite lasts two minutes, eight at
 * most. When the leader leaves the one who joined next leads; the last one leaving ends it. Off the server thread.
 */
public final class Parties {

    public static final int MOST = 8;
    public static final long INVITE_MILLIS = 120_000;

    public enum Outcome { INVITED, JOINED, LEFT, KICKED, NOT_LEADER, NOT_IN_ONE, ALREADY_IN_ONE, NO_INVITE, FULL, SELF, FAILED }

    /** Members in the order they joined; the leader first. */
    public record Party(String id, UUID leader, List<UUID> members) {
    }

    private final Database database;
    private final LongSupplier clock;

    public Parties(Database database, LongSupplier clock) {
        this.database = database;
        this.clock = clock;
    }

    public Optional<Party> of(UUID player) {
        return database.read(connection -> partyOf(connection, player)).flatMap(found -> found);
    }

    /** {@code inviter}'s party, made now if they have none, invites {@code player}. */
    public Outcome invite(UUID inviter, UUID player) {
        if (inviter.equals(player)) {
            return Outcome.SELF;
        }
        Optional<Party> own = of(inviter);
        if (own.isPresent() && !own.get().leader().equals(inviter)) {
            return Outcome.NOT_LEADER;
        }
        if (own.isPresent() && own.get().members().size() >= MOST) {
            return Outcome.FULL;
        }
        if (of(player).isPresent()) {
            return Outcome.ALREADY_IN_ONE;
        }
        String party = own.map(Party::id).orElseGet(() -> UUID.randomUUID().toString());
        long now = clock.getAsLong();
        return database.write(connection -> {
            if (own.isEmpty()) {
                addMember(connection, party, inviter, true, now);
            }
            try (PreparedStatement invite = connection.prepareStatement(
                    "DELETE FROM party_invite WHERE party = ? AND player = ?")) {
                invite.setString(1, party);
                invite.setString(2, player.toString());
                invite.executeUpdate();
            }
            try (PreparedStatement invite = connection.prepareStatement(
                    "INSERT INTO party_invite (party, player, inviter, expires) VALUES (?, ?, ?, ?)")) {
                invite.setString(1, party);
                invite.setString(2, player.toString());
                invite.setString(3, inviter.toString());
                invite.setLong(4, now + INVITE_MILLIS);
                invite.executeUpdate();
            }
        }) ? Outcome.INVITED : Outcome.FAILED;
    }

    /** {@code player} takes {@code inviter}'s invite. */
    public Outcome accept(UUID player, UUID inviter) {
        if (of(player).isPresent()) {
            return Outcome.ALREADY_IN_ONE;
        }
        Optional<Party> party = of(inviter);
        long now = clock.getAsLong();
        if (party.isEmpty() || !invited(party.get().id(), player, now)) {
            return Outcome.NO_INVITE;
        }
        if (party.get().members().size() >= MOST) {
            return Outcome.FULL;
        }
        return database.write(connection -> {
            try (PreparedStatement drop = connection.prepareStatement("DELETE FROM party_invite WHERE player = ?")) {
                drop.setString(1, player.toString());
                drop.executeUpdate();
            }
            addMember(connection, party.get().id(), player, false, now);
        }) ? Outcome.JOINED : Outcome.FAILED;
    }

    public Outcome leave(UUID player) {
        Optional<Party> party = of(player);
        if (party.isEmpty()) {
            return Outcome.NOT_IN_ONE;
        }
        return database.write(connection -> drop(connection, party.get(), player)) ? Outcome.LEFT : Outcome.FAILED;
    }

    public Outcome kick(UUID leader, UUID player) {
        Optional<Party> party = of(leader);
        if (party.isEmpty() || !party.get().leader().equals(leader)) {
            return Outcome.NOT_LEADER;
        }
        if (!party.get().members().contains(player) || leader.equals(player)) {
            return Outcome.NOT_IN_ONE;
        }
        return database.write(connection -> drop(connection, party.get(), player)) ? Outcome.KICKED : Outcome.FAILED;
    }

    /** Who invited {@code player} and still may be taken up on it. */
    public List<UUID> invitesTo(UUID player) {
        long now = clock.getAsLong();
        return database.read(connection -> {
            List<UUID> found = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT inviter FROM party_invite WHERE player = ? AND expires >= ? ORDER BY expires")) {
                query.setString(1, player.toString());
                query.setLong(2, now);
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        found.add(UUID.fromString(rows.getString(1)));
                    }
                }
            }
            return found;
        }).orElse(List.of());
    }

    private boolean invited(String party, UUID player, long now) {
        return database.read(connection -> {
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT 1 FROM party_invite WHERE party = ? AND player = ? AND expires >= ?")) {
                query.setString(1, party);
                query.setString(2, player.toString());
                query.setLong(3, now);
                try (ResultSet rows = query.executeQuery()) {
                    return rows.next();
                }
            }
        }).orElse(false);
    }

    private static Optional<Party> partyOf(Connection connection, UUID player) throws SQLException {
        String party;
        try (PreparedStatement query = connection.prepareStatement("SELECT party FROM party_member WHERE player = ?")) {
            query.setString(1, player.toString());
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                party = rows.getString(1);
            }
        }
        List<UUID> members = new ArrayList<>();
        UUID leader = null;
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT player, leader FROM party_member WHERE party = ? ORDER BY leader DESC, joined, player")) {
            query.setString(1, party);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    UUID member = UUID.fromString(rows.getString(1));
                    members.add(member);
                    if (rows.getInt(2) == 1) {
                        leader = member;
                    }
                }
            }
        }
        return Optional.of(new Party(party, leader == null ? members.getFirst() : leader, List.copyOf(members)));
    }

    private static void addMember(Connection connection, String party, UUID player, boolean leader, long now)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO party_member (player, party, leader, joined) VALUES (?, ?, ?, ?)")) {
            insert.setString(1, player.toString());
            insert.setString(2, party);
            insert.setInt(3, leader ? 1 : 0);
            insert.setLong(4, now);
            insert.executeUpdate();
        }
    }

    private static void drop(Connection connection, Party party, UUID player) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM party_member WHERE player = ?")) {
            delete.setString(1, player.toString());
            delete.executeUpdate();
        }
        List<UUID> rest = party.members().stream().filter(member -> !member.equals(player)).toList();
        if (rest.isEmpty()) {
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM party_invite WHERE party = ?")) {
                delete.setString(1, party.id());
                delete.executeUpdate();
            }
            return;
        }
        if (party.leader().equals(player)) {
            try (PreparedStatement lead = connection.prepareStatement(
                    "UPDATE party_member SET leader = 1 WHERE player = ?")) {
                lead.setString(1, rest.getFirst().toString());
                lead.executeUpdate();
            }
        }
    }
}
