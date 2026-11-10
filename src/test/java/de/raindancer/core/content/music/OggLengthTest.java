package de.raindancer.core.content.music;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("how long an Ogg Vorbis track plays, read from the file itself")
class OggLengthTest {

    @Test
    @DisplayName("a three-second track is three seconds")
    void threeSeconds() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/music/three-seconds.ogg")) {
            assertThat(OggLength.seconds(in.readAllBytes())).hasValueCloseTo(3.0, within(0.05));
        }
    }

    @Test
    @DisplayName("something that is no Ogg Vorbis has no length, and nothing is thrown")
    void notOgg() {
        assertThat(OggLength.seconds("an mp3, really".getBytes())).isEmpty();
        assertThat(OggLength.seconds(new byte[0])).isEmpty();
    }
}
