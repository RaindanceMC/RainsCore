package de.raindancer.core.social.support;

import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ThanksTest {

    @AfterEach
    void back() {
        Thanks.supporters(true);
    }

    @Test
    @DisplayName("with coffee supporters, texts thank supporters and point at /support")
    void supporters() {
        assertThat(Thanks.supportersOn()).isTrue();
        assertThat(Thanks.who()).isEqualTo("supporters");
        assertThat(Thanks.command()).isEqualTo("/support");
    }

    @Test
    @DisplayName("without them, the thanks are Prime's: the texts say Prime members and point at /prime")
    void prime() {
        Thanks.supporters(false);

        assertThat(Thanks.supportersOn()).isFalse();
        assertThat(Thanks.who()).isEqualTo("Prime members");
        assertThat(Thanks.command()).isEqualTo("/prime");
    }

    @Test
    @DisplayName("without supporters, rains.supporter hangs under rains.prime, so every supporter perk is Prime's")
    void permissionUnderPrime() {
        Permission prime = new Permission(PrimePermission.NODE, PermissionDefault.FALSE);

        Thanks.linkToPrime(prime, false);
        assertThat(prime.getChildren()).containsEntry(SupporterPermission.NODE, true);

        Thanks.linkToPrime(prime, true);
        assertThat(prime.getChildren()).doesNotContainKey(SupporterPermission.NODE);
    }
}
