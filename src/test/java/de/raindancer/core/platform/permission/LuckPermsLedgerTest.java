package de.raindancer.core.platform.permission;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.data.DataMutateResult;
import net.luckperms.api.model.data.NodeMap;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeBuilderRegistry;
import net.luckperms.api.node.types.PermissionNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The ledger is what makes LuckPerms safe to share: Core only ever takes away what Core itself gave.
 * A node an admin set by hand — before or after — is theirs.
 */
class LuckPermsLedgerTest {

    @TempDir
    Path folder;

    private static final UUID ALEX = UUID.randomUUID();
    private final LuckPerms luckPerms = mock(LuckPerms.class);
    private final NodeMap data = mock(NodeMap.class);
    private MockedStatic<LuckPermsProvider> provider;

    @BeforeEach
    void setUp() {
        PermissionNode.Builder builder = mock(PermissionNode.Builder.class, Mockito.RETURNS_SELF);
        when(builder.build()).thenReturn(mock(PermissionNode.class));
        NodeBuilderRegistry registry = mock(NodeBuilderRegistry.class);
        when(registry.forPermission()).thenReturn(builder);
        when(luckPerms.getNodeBuilderRegistry()).thenReturn(registry);
        provider = Mockito.mockStatic(LuckPermsProvider.class);
        provider.when(LuckPermsProvider::get).thenReturn(luckPerms);

        User user = mock(User.class);
        when(user.data()).thenReturn(data);
        UserManager users = mock(UserManager.class);
        when(users.getUser(ALEX)).thenReturn(user);
        when(users.saveUser(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(luckPerms.getUserManager()).thenReturn(users);
    }

    @AfterEach
    void tearDown() {
        provider.close();
    }

    private static DataMutateResult result(boolean success) {
        DataMutateResult result = mock(DataMutateResult.class);
        when(result.wasSuccessful()).thenReturn(success);
        return result;
    }

    @Test
    @DisplayName("granting a node the player already had from an admin does not make it Core's to take")
    void alreadyHeldIsNotOurs() {
        DataMutateResult addFalse = result(false);
        when(data.add(any(Node.class))).thenReturn(addFalse);
        DataMutateResult removeTrue = result(true);
        when(data.remove(any(Node.class))).thenReturn(removeTrue);
        LuckPermsGrantStore store = new LuckPermsGrantStore(folder, luckPerms);

        store.grant(ALEX, "claims.admin");
        store.revoke(ALEX, "claims.admin");

        verify(data, never()).remove(any(Node.class));
    }

    @Test
    @DisplayName("revoking a node Core never granted leaves it alone")
    void revokeOnlyWhatWeGave() {
        DataMutateResult removeTrue = result(true);
        when(data.remove(any(Node.class))).thenReturn(removeTrue);
        LuckPermsGrantStore store = new LuckPermsGrantStore(folder, luckPerms);

        assertThat(store.revoke(ALEX, "worldedit.*")).isFalse();
        verify(data, never()).remove(any(Node.class));
    }

    @Test
    @DisplayName("what Core did grant, it can take away again")
    void ourOwnComesBack() {
        DataMutateResult addTrue = result(true);
        when(data.add(any(Node.class))).thenReturn(addTrue);
        DataMutateResult removeTrue = result(true);
        when(data.remove(any(Node.class))).thenReturn(removeTrue);
        LuckPermsGrantStore store = new LuckPermsGrantStore(folder, luckPerms);

        assertThat(store.grant(ALEX, "claims.admin")).isTrue();
        assertThat(store.revoke(ALEX, "claims.admin")).isTrue();
        verify(data).remove(any(Node.class));
    }

    @Test
    @DisplayName("clearing returns only once LuckPerms has saved, so the next change cannot read the nodes back")
    void clearWaitsForTheSave() {
        DataMutateResult ok = result(true);
        when(data.add(any(Node.class))).thenReturn(ok);
        when(data.remove(any(Node.class))).thenReturn(ok);
        User user = luckPerms.getUserManager().getUser(ALEX);
        PermissionNode held = mock(PermissionNode.class);
        when(held.getPermission()).thenReturn("claims.admin");
        when(user.getNodes()).thenReturn(java.util.List.of(held));
        LuckPermsGrantStore store = new LuckPermsGrantStore(folder, luckPerms);
        store.grant(ALEX, "claims.admin");
        CompletableFuture<Void> slowSave = new CompletableFuture<>();
        when(luckPerms.getUserManager().saveUser(any())).thenReturn(slowSave);
        new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
            }
            slowSave.complete(null);
        }).start();

        long started = System.nanoTime();
        store.clear(ALEX);

        assertThat(slowSave).isDone();
        assertThat((System.nanoTime() - started) / 1_000_000).isGreaterThanOrEqualTo(250);
    }

    @Test
    @DisplayName("flushing writes only when something changed since the last write or read — no ping-pong between servers")
    void flushOnlyWhenChanged() throws Exception {
        DataMutateResult ok = result(true);
        when(data.add(any(Node.class))).thenReturn(ok);
        LuckPermsGrantStore store = new LuckPermsGrantStore(folder, luckPerms);
        java.nio.file.Path ledger = store.file();

        store.flush();
        assertThat(ledger).doesNotExist();

        store.grant(ALEX, "claims.admin");
        store.flush();
        assertThat(ledger).exists();

        java.nio.file.Files.delete(ledger);
        store.flush();
        assertThat(ledger).as("nothing new, nothing written").doesNotExist();
    }
}
