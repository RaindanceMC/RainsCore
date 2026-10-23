package de.raindancer.core.platform.permission;

import de.raindancer.core.moderation.players.AdminModes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Whether staff powers apply right now — the LuckPerms contexts rains:powers, rains:admin and rains:dev. */
class DutyTest {

    private static final UUID ALEX = UUID.randomUUID();
    private final Set<UUID> inAdminMode = new HashSet<>();
    private final AdminModes.Source source = inAdminMode::contains;

    @BeforeEach
    void setUp() {
        Duty.clear();
        AdminModes.provide(source);
    }

    @AfterEach
    void tearDown() {
        AdminModes.withdraw(source);
        Duty.clear();
    }

    @Test
    @DisplayName("with the switch off, powers are always on")
    void switchOff() {
        assertThat(Duty.powersOn(ALEX)).isTrue();
        assertThat(Duty.contextsOf(ALEX)).containsEntry(Duty.POWERS, Duty.ON);
    }

    @Test
    @DisplayName("with the switch on, powers follow admin mode")
    void switchOn() {
        Duty.powersNeedAdminMode(true);
        assertThat(Duty.contextsOf(ALEX)).containsEntry(Duty.POWERS, Duty.OFF);

        inAdminMode.add(ALEX);

        assertThat(Duty.powersOn(ALEX)).isTrue();
    }

    @Test
    @DisplayName("rains:admin follows admin mode alone, whatever the duty switch says")
    void adminMode() {
        assertThat(Duty.contextsOf(ALEX)).containsEntry(Duty.ADMIN, Duty.OFF);

        inAdminMode.add(ALEX);
        assertThat(Duty.contextsOf(ALEX)).containsEntry(Duty.ADMIN, Duty.ON)
                .containsEntry(Duty.POWERS, Duty.ON);

        Duty.powersNeedAdminMode(true);
        inAdminMode.remove(ALEX);
        assertThat(Duty.contextsOf(ALEX)).containsEntry(Duty.ADMIN, Duty.OFF);
    }

    @Test
    @DisplayName("dev mode is off until switched, and per player")
    void dev() {
        assertThat(Duty.contextsOf(ALEX)).containsEntry(Duty.DEV, Duty.OFF);

        assertThat(Duty.toggleDev(ALEX)).isTrue();
        assertThat(Duty.contextsOf(ALEX)).containsEntry(Duty.DEV, Duty.ON);
        assertThat(Duty.devOn(UUID.randomUUID())).isFalse();

        Duty.forget(ALEX);
        assertThat(Duty.devOn(ALEX)).isFalse();
    }

    @Test
    @DisplayName("listeners hear who changed; null when everybody may have")
    void listeners() {
        List<UUID> heard = new ArrayList<>();
        Duty.onChange(heard::add);

        Duty.toggleDev(ALEX);
        Duty.powersNeedAdminMode(true);
        Duty.powersNeedAdminMode(true);
        Duty.adminModeSwitched(ALEX);

        assertThat(heard).containsExactly(ALEX, null, ALEX);
    }
}
