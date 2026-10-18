package de.raindancer.core.platform.permission;

import de.raindancer.core.data.store.YamlStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Groups on a server without LuckPerms: the seeded defaults, applied as plain grants. */
class LocalGroupsTest {

    @TempDir
    Path folder;

    private static final UUID ALEX = UUID.randomUUID();
    private Grants grants;
    private LocalGroups groups;

    @BeforeEach
    void setUp() {
        grants = new Grants(folder);
        groups = new LocalGroups(new YamlStore(folder.resolve("groups.yml")), grants);
        groups.seed(GroupSeed.named("support").weight(10).granting("rains.moderation.warn"));
        groups.seed(GroupSeed.named("mod").weight(20).inheriting("support").granting("rains.moderation.ban"));
        groups.seed(GroupSeed.named("builder").weight(5).granting("rainswarps.warp.staff")
                .grantingIn("worldedit.*", "world", "build"));
    }

    @Test
    @DisplayName("joining a group grants its nodes and its parents' nodes")
    void joinGrantsInherited() {
        assertThat(groups.join(ALEX, "mod")).isTrue();

        assertThat(grants.nodesFor(ALEX)).contains("rains.moderation.ban", "rains.moderation.warn");
        assertThat(groups.directGroupsOf(ALEX)).containsExactly("mod");
        assertThat(groups.groupsOf(ALEX)).containsExactlyInAnyOrder("mod", "support");
    }

    @Test
    @DisplayName("a node limited to a world is not handed out everywhere without LuckPerms")
    void contextualNodesAreSkipped() {
        groups.join(ALEX, "builder");

        assertThat(grants.nodesFor(ALEX)).contains("rainswarps.warp.staff").doesNotContain("worldedit.*");
    }

    @Test
    @DisplayName("leaving takes back only what no other group of theirs still gives")
    void leaveKeepsWhatAnotherGroupGives() {
        groups.join(ALEX, "mod");
        groups.join(ALEX, "support");

        groups.leave(ALEX, "mod");

        assertThat(grants.nodesFor(ALEX)).contains("rains.moderation.warn").doesNotContain("rains.moderation.ban");
        assertThat(groups.directGroupsOf(ALEX)).containsExactly("support");
    }

    @Test
    @DisplayName("a node granted by something else is never taken by leaving a group")
    void foreignGrantSurvives() {
        grants.grant(ALEX, "rains.moderation.ban");
        groups.join(ALEX, "mod");

        groups.leave(ALEX, "mod");

        assertThat(grants.nodesFor(ALEX)).contains("rains.moderation.ban");
    }

    @Test
    @DisplayName("an unknown group cannot be joined")
    void unknownGroup() {
        assertThat(groups.join(ALEX, "nonsense")).isFalse();
        assertThat(groups.exists("nonsense")).isFalse();
        assertThat(groups.exists("mod")).isTrue();
    }

    @Test
    @DisplayName("seeding twice keeps the first seed — the same rule LuckPerms groups follow")
    void seedOnce() {
        assertThat(groups.seed(GroupSeed.named("mod").granting("something.else"))).isFalse();
        groups.join(ALEX, "mod");

        assertThat(grants.nodesFor(ALEX)).contains("rains.moderation.ban").doesNotContain("something.else");
    }

    @Test
    @DisplayName("members survive a restart and are listed per group")
    void membersPersist() {
        groups.join(ALEX, "mod");
        UUID bo = UUID.randomUUID();
        groups.join(bo, "support");
        groups.flush();

        LocalGroups again = new LocalGroups(new YamlStore(folder.resolve("groups.yml")), grants);
        again.load();

        assertThat(again.membersOf("mod")).containsExactly(ALEX);
        assertThat(again.membersOf("support")).containsExactly(bo);
    }

    @Test
    @DisplayName("watchers hear who changed")
    void watchers() {
        List<UUID> heard = new ArrayList<>();
        groups.onChange(heard::add);

        groups.join(ALEX, "mod");
        groups.join(ALEX, "mod");
        groups.leave(ALEX, "mod");

        assertThat(heard).containsExactly(ALEX, ALEX);
    }

    @Test
    @DisplayName("without LuckPerms the groups are not editable")
    void notEditable() {
        assertThat(groups.editable()).isFalse();
        assertThat(PermissionGroups.NONE.join(ALEX, "mod")).isFalse();
    }

    @Test
    @DisplayName("dropping direct nodes takes the named ones held directly, and says which")
    void dropDirect() {
        grants.grant(ALEX, "rains.moderation.ban");
        grants.grant(ALEX, "something.personal");

        assertThat(groups.dropDirect(ALEX, java.util.List.of("rains.moderation.ban", "never.held")))
                .containsExactly("rains.moderation.ban");
        assertThat(grants.nodesFor(ALEX)).containsExactly("something.personal");
    }
}
