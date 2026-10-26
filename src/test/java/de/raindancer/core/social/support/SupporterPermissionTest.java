package de.raindancer.core.social.support;

import de.raindancer.core.data.store.Documents;
import de.raindancer.core.data.store.YamlStore;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SupporterPermissionTest {

    @TempDir
    Path folder;

    private final Plugin plugin = mock(Plugin.class);
    private final UUID id = UUID.randomUUID();
    private Supporters supporters;
    private SupporterPermission permission;
    private Player player;
    private PermissionAttachment attachment;

    @BeforeEach
    void setUp() {
        supporters = new Supporters(new YamlStore(folder.resolve("supporters.yml"), null, Documents.Scope.NETWORK));
        supporters.load();
        permission = new SupporterPermission(plugin, supporters);
        player = mock(Player.class);
        attachment = mock(PermissionAttachment.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.addAttachment(plugin)).thenReturn(attachment);
    }

    @Test
    @DisplayName("nobody who has not supported is given the node")
    void notASupporter() {
        permission.sync(player);

        verify(player, never()).addAttachment(plugin);
        assertThat(permission.holding()).isZero();
    }

    @Test
    @DisplayName("a supporter is given rains.supporter, once, however often it is checked")
    void supporter() {
        supporters.credit(id, 1, Instant.now());

        permission.sync(player);
        permission.sync(player);

        verify(player, times(1)).addAttachment(plugin);
        verify(attachment).setPermission(SupporterPermission.NODE, true);
        verify(player, times(1)).recalculatePermissions();
        assertThat(permission.holding()).isEqualTo(1);
    }

    @Test
    @DisplayName("taken away as soon as they are no supporter any more, and on leaving")
    void revoked() {
        supporters.credit(id, 1, Instant.now());
        permission.sync(player);

        supporters.revoke(id);
        permission.sync(player);

        verify(attachment).remove();
        assertThat(permission.holding()).isZero();

        supporters.credit(id, 1, Instant.now());
        permission.sync(player);
        permission.forget(id);
        assertThat(permission.holding()).isZero();
    }
}
