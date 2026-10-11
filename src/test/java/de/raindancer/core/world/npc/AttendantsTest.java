package de.raindancer.core.world.npc;

import org.bukkit.Location;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AttendantsTest {

    @Test
    @DisplayName("an attendant looks the way whoever placed it was looking, level, from the same spot")
    void looksTheWayThePlacerLooks() {
        Location placer = new Location(null, 10.5, 64, -3.5, 37f, 40f);

        Location standing = Attendants.standing(placer);

        assertThat(standing.getYaw()).isEqualTo(37f);
        assertThat(standing.getPitch()).isZero();
        assertThat(standing.getX()).isEqualTo(10.5);
        assertThat(standing.getZ()).isEqualTo(-3.5);
        assertThat(placer.getPitch()).as("the placer's own location is left alone").isEqualTo(40f);
    }
}
