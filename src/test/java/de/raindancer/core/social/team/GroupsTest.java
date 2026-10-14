package de.raindancer.core.social.team;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GroupsTest {

    private static final UUID LEADER = UUID.randomUUID();
    private static final UUID OFFICER = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final Groups.Group ELVES = new Groups.Group("guild:elves", "The Elves", "ELF", LEADER,
            Set.of(LEADER, OFFICER), Set.of(LEADER, OFFICER, MEMBER));

    @AfterEach
    void clear() {
        Groups.clear();
    }

    private static Groups.Source source() {
        return new Groups.Source() {
            @Override
            public Optional<Groups.Group> group(String id) {
                return ELVES.id().equals(id) ? Optional.of(ELVES) : Optional.empty();
            }

            @Override
            public List<Groups.Group> of(UUID player) {
                return ELVES.members().contains(player) ? List.of(ELVES) : List.of();
            }
        };
    }

    @Test
    @DisplayName("a group is found by its id and by who is in it; the leader is always a manager and a member")
    void found() {
        Groups.register("guilds", source());
        assertThat(Groups.group("guild:elves")).contains(ELVES);
        assertThat(Groups.of(MEMBER)).containsExactly(ELVES);
        assertThat(Groups.managedBy(OFFICER)).containsExactly(ELVES);
        assertThat(Groups.managedBy(MEMBER)).isEmpty();
        assertThat(ELVES.isManager(LEADER)).isTrue();
        assertThat(ELVES.isMember(MEMBER)).isTrue();
        assertThat(Groups.group("guild:nobody")).isEmpty();
    }

    @Test
    @DisplayName("with its plugin gone a group is simply not there, and a throwing source is skipped")
    void gone() {
        Groups.register("broken", new Groups.Source() {
            @Override
            public Optional<Groups.Group> group(String id) {
                throw new IllegalStateException("gone");
            }

            @Override
            public List<Groups.Group> of(UUID player) {
                throw new IllegalStateException("gone");
            }
        });
        Groups.register("guilds", source());
        assertThat(Groups.group("guild:elves")).contains(ELVES);
        Groups.unregister("guilds");
        assertThat(Groups.group("guild:elves")).isEmpty();
        assertThat(Groups.of(MEMBER)).isEmpty();
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("a group's treasury pays what it can, all or nothing; nobody keeping the group pays nothing")
    void treasury() {
        java.util.Map<String, Long> banks = new java.util.HashMap<>(java.util.Map.of("guild:1", 500L));
        Groups.registerTreasury("guilds", new Groups.Treasury() {
            @Override
            public java.util.Optional<Boolean> withdraw(String groupId, de.raindancer.core.social.economy.Money amount, String reason) {
                Long held = banks.get(groupId);
                if (held == null) {
                    return java.util.Optional.empty();
                }
                if (held < amount.minor()) {
                    return java.util.Optional.of(false);
                }
                banks.put(groupId, held - amount.minor());
                return java.util.Optional.of(true);
            }

            @Override
            public java.util.Optional<de.raindancer.core.social.economy.Money> balance(String groupId) {
                return java.util.Optional.ofNullable(banks.get(groupId)).map(de.raindancer.core.social.economy.Money::of);
            }
        });
        try {
            org.assertj.core.api.Assertions.assertThat(Groups.pay("guild:1", de.raindancer.core.social.economy.Money.of(300), "upkeep")).isTrue();
            org.assertj.core.api.Assertions.assertThat(Groups.pay("guild:1", de.raindancer.core.social.economy.Money.of(300), "upkeep")).isFalse();
            org.assertj.core.api.Assertions.assertThat(Groups.balance("guild:1")).contains(de.raindancer.core.social.economy.Money.of(200));
            org.assertj.core.api.Assertions.assertThat(Groups.pay("guild:9", de.raindancer.core.social.economy.Money.of(1), "upkeep")).isFalse();
            org.assertj.core.api.Assertions.assertThat(Groups.balance("guild:9")).isEmpty();
        } finally {
            Groups.unregisterTreasury("guilds");
        }
    }
}
