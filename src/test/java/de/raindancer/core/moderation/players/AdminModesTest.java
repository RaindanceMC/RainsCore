package de.raindancer.core.moderation.players;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AdminModesTest {

    private static final UUID STAFF = UUID.randomUUID();
    private static final UUID PLAYER = UUID.randomUUID();

    @AfterEach
    void clear() {
        AdminModes.clear();
    }

    @Test
    @DisplayName("without a plugin running admin mode, nobody is in it")
    void nobodyProvides() {
        assertThat(AdminModes.isOn(STAFF)).isFalse();
    }

    @Test
    @DisplayName("whoever runs admin mode answers who is in it")
    void provided() {
        AdminModes.provide(Set.of(STAFF)::contains);

        assertThat(AdminModes.isOn(STAFF)).isTrue();
        assertThat(AdminModes.isOn(PLAYER)).isFalse();
        assertThat(AdminModes.isOn(null)).isFalse();
    }

    @Test
    @DisplayName("a switch reaches every listener, and a broken listener does not stop the others")
    void listeners() {
        List<String> heard = new ArrayList<>();
        AdminModes.listen((who, on) -> {
            throw new IllegalStateException("broken");
        });
        AdminModes.listen((who, on) -> heard.add(who + "=" + on));

        AdminModes.switched(STAFF, true);
        AdminModes.switched(STAFF, false);

        assertThat(heard).containsExactly(STAFF + "=true", STAFF + "=false");
    }

    @Test
    @DisplayName("a provider that throws counts as nobody in admin mode")
    void brokenProvider() {
        AdminModes.provide(who -> {
            throw new IllegalStateException("broken");
        });
        assertThat(AdminModes.isOn(STAFF)).isFalse();
    }

    @Test
    @DisplayName("a plugin going away takes its provider and listeners with it")
    void forgetFrom() {
        AdminModes.Source source = Set.of(STAFF)::contains;
        AdminModes.provide(source);
        AdminModes.listen((who, on) -> { });

        int dropped = AdminModes.forgetFrom(AdminModesTest.class.getClassLoader());

        assertThat(dropped).isEqualTo(2);
        assertThat(AdminModes.isOn(STAFF)).isFalse();
    }
}
