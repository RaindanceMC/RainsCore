package de.raindancer.core.platform.permission;

import de.raindancer.core.platform.network.NetworkBus;

import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Permission groups — staff ranks, most of all — and who is in them.
 *
 * <p>With LuckPerms installed these are LuckPerms groups ({@link LuckPermsGroups}): a plugin seeds the
 * defaults once, and from then on the group is edited in LuckPerms like any other. Without it,
 * {@link LocalGroups} applies the seeded defaults as plain {@link Grants}, uneditable — enough for a test
 * server, not meant for a real one.
 *
 * <p>Calls that may have to load a player from storage ({@link #join}, {@link #leave},
 * {@link #directGroupsOf} for somebody offline, {@link #membersOf}, {@link #seed}) block: never on the
 * server thread. {@link #groupsOf} for somebody online answers from memory.
 */
public interface PermissionGroups {

    /** No groups at all: everything answers empty and refuses. */
    PermissionGroups NONE = new PermissionGroups() {
        @Override
        public boolean editable() {
            return false;
        }

        @Override
        public boolean seed(GroupSeed seed) {
            return false;
        }

        @Override
        public boolean exists(String group) {
            return false;
        }

        @Override
        public boolean join(UUID who, String group) {
            return false;
        }

        @Override
        public boolean leave(UUID who, String group) {
            return false;
        }

        @Override
        public Set<String> directGroupsOf(UUID who) {
            return Set.of();
        }

        @Override
        public Set<String> groupsOf(UUID who) {
            return Set.of();
        }

        @Override
        public Set<UUID> membersOf(String group) {
            return Set.of();
        }

        @Override
        public NetworkBus.Subscription onChange(Consumer<UUID> watcher) {
            return () -> { };
        }

        @Override
        public Set<String> dropDirect(UUID who, java.util.Collection<String> nodes) {
            return Set.of();
        }
    };

    /** Whether the groups can be edited by hand — true with LuckPerms ({@code /lp editor}). */
    boolean editable();

    /**
     * Makes the group as seeded if it does not exist yet. An existing group is left exactly as it is.
     *
     * @return whether it was created now
     */
    boolean seed(GroupSeed seed);

    boolean exists(String group);

    /** Puts them directly in the group. @return whether that changed anything */
    boolean join(UUID who, String group);

    /** Takes them out of the group (only a direct membership). @return whether they were in it */
    boolean leave(UUID who, String group);

    /** The groups they are directly in. */
    Set<String> directGroupsOf(UUID who);

    /** Every group they hold, inherited ones included. */
    Set<String> groupsOf(UUID who);

    /**
     * LuckPerms' primary group for them — what a "rank" line shows. May load an offline player: never on the server
     * thread. Empty without LuckPerms.
     */
    default java.util.Optional<String> primaryGroup(UUID who) {
        return java.util.Optional.empty();
    }

    /** Everybody directly in the group. */
    Set<UUID> membersOf(String group);

    /**
     * Removes those of {@code nodes} that are set directly on the player (not through a group) — for
     * moving permissions that were handed out one by one into a group.
     *
     * @return the ones that were removed
     */
    Set<String> dropDirect(UUID who, java.util.Collection<String> nodes);

    /**
     * Told whenever somebody's groups may have changed — through this API, a LuckPerms command, the
     * editor, or another server. The id is null when anybody's may have.
     */
    NetworkBus.Subscription onChange(Consumer<UUID> watcher);
}
