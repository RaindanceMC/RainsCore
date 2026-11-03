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

    private static PermissionNode node(String key, boolean contextFree) {
        PermissionNode node = mock(PermissionNode.class);
        when(node.getPermission()).thenReturn(key);
        when(node.getKey()).thenReturn(key);
        when(node.getValue()).thenReturn(true);
        net.luckperms.api.context.ImmutableContextSet contexts = mock(net.luckperms.api.context.ImmutableContextSet.class);
        when(contexts.isEmpty()).thenReturn(contextFree);
        when(node.getContexts()).thenReturn(contexts);
        return node;
    }

    @Test
    @DisplayName("putting a group on duty moves its context-free powers under rains:powers=on, keeping the always-on ones")
    void putOnDuty() {
        Group mod = mock(Group.class);
        NodeMap data = mock(NodeMap.class);
        DataMutateResult ok = mock(DataMutateResult.class);
        when(ok.wasSuccessful()).thenReturn(true);
        when(data.add(any(Node.class))).thenReturn(ok);
        when(data.remove(any(Node.class))).thenReturn(ok);
        when(mod.data()).thenReturn(data);
        PermissionNode ban = node("rains.moderation.ban", true);
        PermissionNode chat = node("rains.moderation.staffchat", true);
        PermissionNode built = node("worldedit.*", false);
        when(mod.getNodes(net.luckperms.api.node.NodeType.PERMISSION)).thenReturn(java.util.List.of(ban, chat, built));
        when(groupManager.getGroup("mod")).thenReturn(mod);

        int moved = new LuckPermsGroups(luckPerms).putOnDuty("mod", java.util.Set.of("rains.moderation.staffchat"));

        assertThat(moved).isEqualTo(1);
        verify(data).remove(ban);
        verify(data, never()).remove(chat);
        verify(data, never()).remove(built);
        verify(permission).withContext(Duty.POWERS, Duty.ON);
        verify(groupManager).saveGroup(mod);
    }

    @Test
    @DisplayName("a group already on duty, or missing, is not written")
    void alreadyOnDuty() {
        Group mod = mock(Group.class);
        PermissionNode onDuty = node("rains.moderation.ban", false);
        when(mod.getNodes(net.luckperms.api.node.NodeType.PERMISSION)).thenReturn(java.util.List.of(onDuty));
        when(groupManager.getGroup("mod")).thenReturn(mod);
        when(groupManager.getGroup("nobody")).thenReturn(null);
        when(groupManager.loadGroup("nobody")).thenReturn(CompletableFuture.completedFuture(java.util.Optional.empty()));

        LuckPermsGroups groups = new LuckPermsGroups(luckPerms);
        assertThat(groups.putOnDuty("mod", java.util.Set.of())).isZero();
        assertThat(groups.putOnDuty("nobody", java.util.Set.of())).isZero();
        verify(groupManager, never()).saveGroup(any());
    }
}
