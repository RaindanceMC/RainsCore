package de.raindancer.core.world.npc;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkinsTest {

    @Test
    void mineSkinLinksAndIdsAreRecognised() {
        assertEquals(Optional.of("355f8c81"), Skins.mineSkinId("mineskin:355f8c81"));
        assertEquals(Optional.of("355f8c81"), Skins.mineSkinId("https://minesk.in/355f8c81"));
        assertEquals(Optional.of("f67525367a6e4153b1caef59caac270d"),
                Skins.mineSkinId("https://mineskin.org/skins/f67525367a6e4153b1caef59caac270d"));
        assertEquals(Optional.of("f67525367a6e4153b1caef59caac270d"),
                Skins.mineSkinId("https://mineskin.org/skins/f6752536-7a6e-4153-b1ca-ef59caac270d?x=1"));
    }

    @Test
    void playerNamesAndOtherLinksAreNotMineSkin() {
        assertTrue(Skins.mineSkinId("lillyyxoxo").isEmpty());
        assertTrue(Skins.mineSkinId("https://namemc.com/skin/abc").isEmpty());
        assertTrue(Skins.mineSkinId("mineskin:not-hex!").isEmpty());
        assertTrue(Skins.mineSkinId(null).isEmpty());
    }

    @Test
    void theTextureIsReadFromMineSkinsAnswer() {
        String answer = "{\"success\":true,\"skin\":{\"uuid\":\"f675\",\"texture\":{\"data\":{\"value\":\"VAL\","
                + "\"signature\":\"SIG\"},\"hash\":{\"skin\":\"c1\"}}}}";
        assertEquals(Optional.of(new Skins.Texture("VAL", "SIG")), Skins.parse(answer));
        assertTrue(Skins.parse("{\"success\":false}").isEmpty());
        assertTrue(Skins.parse("not json").isEmpty());
    }

    @Test
    void eachSkinWearsItsOwnStableProfileId() {
        assertEquals(Skins.profileId("355f8c81"), Skins.profileId("355f8c81"));
        assertNotEquals(Skins.profileId("355f8c81"), Skins.profileId("12345678"));
        assertNotEquals(Attendants.SUIT_ID, Skins.profileId("355f8c81"));
    }
}
