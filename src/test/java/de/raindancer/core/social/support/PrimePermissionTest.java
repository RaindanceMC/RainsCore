package de.raindancer.core.social.support;

import de.raindancer.core.data.store.Documents;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.permission.GroupSeed;
import de.raindancer.core.platform.permission.PermissionGroups;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrimePermissionTest {

    @TempDir
    Path folder;

    private final Plugin plugin = mock(Plugin.class);
    private final UUID id = UUID.randomUUID();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-13T12:00:00Z"));
    private final PermissionGroups groups = mock(PermissionGroups.class);
    private PrimeMembers members;
    private PrimePermission permission;
    private Player player;
    private PermissionAttachment attachment;

    @BeforeEach
    void setUp() {
        members = new PrimeMembers(new YamlStore(folder.resolve("prime.yml"), null, Documents.Scope.NETWORK));
        members.load();
        permission = new PrimePermission(plugin, members, () -> groups, now::get);
        player = mock(Player.class);
        attachment = mock(PermissionAttachment.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.addAttachment(plugin)).thenReturn(attachment);
        when(groups.editable()).thenReturn(true);
    }

    @Test
    @DisplayName("a Prime member gets rains.prime once; when it runs out, it goes")
    void attachAndDetach() {
        members.grant(id, Duration.ofDays(1), now.get());
        permission.sync(player);
        permission.sync(player);

        verify(attachment).setPermission(PrimePermission.NODE, true);
        assertThat(permission.holding()).isEqualTo(1);

        now.set(now.get().plus(Duration.ofDays(2)));
        permission.sync(player);
        verify(attachment).remove();
        assertThat(permission.holding()).isZero();
    }

    @Test
    @DisplayName("nobody else is given the node")
    void notPrime() {
        permission.sync(player);
        verify(player, never()).addAttachment(plugin);
    }

    @Test
    @DisplayName("the group follows: current members join, run-out members leave, hand-added non-members stay")
    void groupFollows() {
        UUID expired = UUID.randomUUID();
        UUID byHand = UUID.randomUUID();
        members.grant(id, Duration.ofDays(10), now.get());
        members.grant(expired, Duration.ofDays(1), now.get().minus(Duration.ofDays(5)));
        when(groups.exists(PrimePermission.GROUP)).thenReturn(true);
        when(groups.membersOf(PrimePermission.GROUP)).thenReturn(Set.of(expired, byHand));
        when(groups.join(any(), anyString())).thenReturn(true);
        when(groups.leave(any(), anyString())).thenReturn(true);

        int changed = permission.reconcileGroup();

        assertThat(changed).isEqualTo(2);
        verify(groups).join(id, PrimePermission.GROUP);
        verify(groups).leave(expired, PrimePermission.GROUP);
        verify(groups, never()).leave(byHand, PrimePermission.GROUP);
    }

    @Test
    @DisplayName("the group is created with rains.prime when missing, and left alone without LuckPerms")
    void seed() {
        when(groups.exists(PrimePermission.GROUP)).thenReturn(false);
        permission.seedGroup();
        verify(groups).seed(any(GroupSeed.class));

        PermissionGroups none = mock(PermissionGroups.class);
        when(none.editable()).thenReturn(false);
        PrimePermission without = new PrimePermission(plugin, members, () -> none, now::get);
        without.seedGroup();
        assertThat(without.reconcileGroup()).isZero();
        verify(none, never()).seed(any());
        verify(none, never()).join(any(), anyString());
    }

    @Test
    @DisplayName("the seed names the group Prime and grants the node")
    void seedContents() {
        GroupSeed seed = PrimePermission.seed();
        assertThat(seed.name()).isEqualTo("prime");
        assertThat(seed.displayName()).isEqualTo("Prime");
        assertThat(seed.nodes()).extracting(GroupSeed.Node::permission).contains(PrimePermission.NODE);
    }
}
