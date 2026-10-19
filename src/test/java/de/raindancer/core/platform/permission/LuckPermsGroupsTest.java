package de.raindancer.core.platform.permission;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.data.DataMutateResult;
import net.luckperms.api.model.data.NodeMap;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.group.GroupManager;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeBuilderRegistry;
import net.luckperms.api.node.types.DisplayNameNode;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.node.types.PermissionNode;
import net.luckperms.api.node.types.WeightNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The promise that makes LuckPerms the place to edit staff groups: a group that exists is never touched
 * again — not on a restart, not on a promotion.
 */
class LuckPermsGroupsTest {

    private final LuckPerms luckPerms = mock(LuckPerms.class);
    private final GroupManager groupManager = mock(GroupManager.class);
    private MockedStatic<LuckPermsProvider> provider;
    private PermissionNode.Builder permission;

    @BeforeEach
    void setUp() {
        NodeBuilderRegistry registry = mock(NodeBuilderRegistry.class);
        permission = mock(PermissionNode.Builder.class, Mockito.RETURNS_SELF);
        when(permission.build()).thenReturn(mock(PermissionNode.class));
        InheritanceNode.Builder inheritance = mock(InheritanceNode.Builder.class, Mockito.RETURNS_SELF);
        when(inheritance.build()).thenReturn(mock(InheritanceNode.class));
        WeightNode.Builder weight = mock(WeightNode.Builder.class, Mockito.RETURNS_SELF);
        when(weight.build()).thenReturn(mock(WeightNode.class));
        DisplayNameNode.Builder display = mock(DisplayNameNode.Builder.class, Mockito.RETURNS_SELF);
        when(display.build()).thenReturn(mock(DisplayNameNode.class));
        when(registry.forPermission()).thenReturn(permission);
        when(registry.forInheritance()).thenReturn(inheritance);
        when(registry.forWeight()).thenReturn(weight);
        when(registry.forDisplayName()).thenReturn(display);
        when(luckPerms.getNodeBuilderRegistry()).thenReturn(registry);
        when(luckPerms.getGroupManager()).thenReturn(groupManager);
        when(groupManager.saveGroup(any())).thenReturn(CompletableFuture.completedFuture(null));
        provider = Mockito.mockStatic(LuckPermsProvider.class);
        provider.when(LuckPermsProvider::get).thenReturn(luckPerms);
    }

    @AfterEach
    void tearDown() {
        provider.close();
    }

    private static GroupSeed mod() {
        return GroupSeed.named("mod").displayName("Moderator").weight(20).inheriting("support")
                .granting("rains.moderation.ban").grantingIn("worldedit.*", "world", "build");
    }

    @Test
    @DisplayName("an existing group is left exactly as it is")
    void existingGroupUntouched() {
        Group existing = mock(Group.class);
        when(groupManager.getGroup("mod")).thenReturn(existing);

        boolean created = new LuckPermsGroups(luckPerms).seed(mod());

        assertThat(created).isFalse();
        verify(existing, never()).data();
        verify(groupManager, never()).createAndLoadGroup(anyString());
        verify(groupManager, never()).saveGroup(any());
    }

    @Test
    @DisplayName("a missing group is created with the seed's nodes, its parent and its weight")
    void missingGroupCreated() {
        Group fresh = mock(Group.class);
        NodeMap data = mock(NodeMap.class);
        DataMutateResult ok = mock(DataMutateResult.class);
        when(ok.wasSuccessful()).thenReturn(true);
        when(data.add(any(Node.class))).thenReturn(ok);
        when(fresh.data()).thenReturn(data);
        when(groupManager.getGroup("mod")).thenReturn(null);
        when(groupManager.loadGroup("mod")).thenReturn(CompletableFuture.completedFuture(java.util.Optional.empty()));
        when(groupManager.createAndLoadGroup("mod")).thenReturn(CompletableFuture.completedFuture(fresh));

        boolean created = new LuckPermsGroups(luckPerms).seed(mod());

        assertThat(created).isTrue();
        // ban, worldedit in build, the parent, the weight, the display name
        verify(data, atLeast(5)).add(any(Node.class));
        verify(groupManager).saveGroup(fresh);
    }

    @Test
    @DisplayName("a group that exists in storage but is not loaded yet is not created over")
    void storedButNotLoaded() {
        Group stored = mock(Group.class);
        when(groupManager.getGroup("mod")).thenReturn(null);
        when(groupManager.loadGroup("mod")).thenReturn(CompletableFuture.completedFuture(java.util.Optional.of(stored)));

        assertThat(new LuckPermsGroups(luckPerms).seed(mod())).isFalse();
        verify(groupManager, never()).createAndLoadGroup(anyString());
    }

    @Test
    @DisplayName("LuckPerms groups are editable")
    void editable() {
        assertThat(new LuckPermsGroups(luckPerms).editable()).isTrue();
    }

    @Test
    @DisplayName("a denied node reaches LuckPerms as false")
    void denial() {
        Group fresh = mock(Group.class);
        NodeMap data = mock(NodeMap.class);
        DataMutateResult ok = mock(DataMutateResult.class);
        when(data.add(any(Node.class))).thenReturn(ok);
        when(fresh.data()).thenReturn(data);
        when(groupManager.loadGroup("dev")).thenReturn(CompletableFuture.completedFuture(java.util.Optional.empty()));
        when(groupManager.createAndLoadGroup("dev")).thenReturn(CompletableFuture.completedFuture(fresh));

        new LuckPermsGroups(luckPerms).seed(GroupSeed.named("dev").denyingIn("worldedit.*", Duty.DEV, Duty.ON));

        verify(permission).value(false);
        verify(permission).withContext(Duty.DEV, Duty.ON);
    }

    private static PermissionNode held(String key, boolean value) {
        PermissionNode node = mock(PermissionNode.class);
        when(node.getPermission()).thenReturn(key);
        when(node.getValue()).thenReturn(value);
        net.luckperms.api.context.ImmutableContextSet none = mock(net.luckperms.api.context.ImmutableContextSet.class);
        when(none.isEmpty()).thenReturn(true);
        when(node.getContexts()).thenReturn(none);
        return node;
    }

    private Group existing(String name, PermissionNode... nodes) {
        Group group = mock(Group.class);
        NodeMap data = mock(NodeMap.class);
        DataMutateResult ok = mock(DataMutateResult.class);
        when(ok.wasSuccessful()).thenReturn(true);
        when(data.add(any(Node.class))).thenReturn(ok);
        when(data.remove(any(Node.class))).thenReturn(ok);
        when(group.data()).thenReturn(data);
        when(group.getNodes(net.luckperms.api.node.NodeType.PERMISSION)).thenReturn(java.util.List.of(nodes));
        when(groupManager.getGroup(name)).thenReturn(group);
        return group;
    }

    @Test
    @DisplayName("ensure adds a node an existing group lost, and saves it")
    void ensureAddsMissing() {
        Group dev = existing("dev", held("rains.moderation.staffchat", true));

        assertThat(new LuckPermsGroups(luckPerms).ensure("dev", "rains.moderation.dev")).isTrue();

        verify(dev.data()).add(any(Node.class));
        verify(groupManager).saveGroup(dev);
    }

    @Test
    @DisplayName("ensure leaves a group alone that already holds the node")
    void ensureLeavesPresent() {
        Group dev = existing("dev", held("rains.moderation.dev", true));

        assertThat(new LuckPermsGroups(luckPerms).ensure("dev", "rains.moderation.dev")).isFalse();

        verify(dev, never()).data();
        verify(groupManager, never()).saveGroup(any());
    }

    @Test
    @DisplayName("ensure turns a denial of the node back into a grant")
    void ensureReplacesDenial() {
        PermissionNode denied = held("rains.moderation.dev", false);
        Group dev = existing("dev", denied);

        assertThat(new LuckPermsGroups(luckPerms).ensure("dev", "rains.moderation.dev")).isTrue();

        verify(dev.data()).remove(denied);
        verify(dev.data()).add(any(Node.class));
    }

    @Test
    @DisplayName("ensure creates no group")
    void ensureNeedsTheGroup() {
        when(groupManager.getGroup("dev")).thenReturn(null);
        when(groupManager.loadGroup("dev")).thenReturn(CompletableFuture.completedFuture(java.util.Optional.empty()));

        assertThat(new LuckPermsGroups(luckPerms).ensure("dev", "rains.moderation.dev")).isFalse();
        verify(groupManager, never()).createAndLoadGroup(anyString());
    }
}
