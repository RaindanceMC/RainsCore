package de.raindancer.core.social.friends;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SocialsTest {

    @Test
    void arrivingRightAfterLeavingAnotherServerIsSwitchingNotJoining() {
        assertEquals(Socials.Arrival.JOINED, Socials.arrival(null, 0, false, 100_000, "ctf"));
        assertEquals(Socials.Arrival.SWITCHED, Socials.arrival("hub", 95_000, false, 100_000, "ctf"));
        assertEquals(Socials.Arrival.JOINED, Socials.arrival("hub", 100_000 - Socials.SWITCH_MILLIS - 1, false, 100_000, "ctf"),
                "left the network a while ago");
        assertEquals(Socials.Arrival.JOINED, Socials.arrival("ctf", 99_000, false, 100_000, "ctf"),
                "back on the same server: a reconnect, told as joining");
    }

    @Test
    void stillMarkedOnlineElsewhereIsSwitchingEvenIfTheOldServerHasNotWrittenTheGoodbyeYet() {
        assertEquals(Socials.Arrival.SWITCHED, Socials.arrival("hub", 10_000, true, 100_000, "ctf"));
    }
}
