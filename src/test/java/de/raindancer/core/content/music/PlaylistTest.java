package de.raindancer.core.content.music;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("the playlist: tracks shuffled, one after another")
class PlaylistTest {

    private static final List<Track> TRACKS = List.of(new Track("rainswelcome", "dawn", 120),
            new Track("rainswelcome", "tide", 90), new Track("rainswelcome", "ember", 150));

    @Test
    @DisplayName("never the same track twice in a row")
    void noRepeat() {
        Playlist list = new Playlist(TRACKS, new Random(7));
        Track last = list.next();
        for (int i = 0; i < 200; i++) {
            Track now = list.next();
            assertThat(now).isNotEqualTo(last);
            last = now;
        }
    }

    @Test
    @DisplayName("every track gets its turn")
    void allPlay() {
        Playlist list = new Playlist(TRACKS, new Random(1));
        java.util.Set<String> heard = new java.util.HashSet<>();
        for (int i = 0; i < 30; i++) {
            heard.add(list.next().name());
        }
        assertThat(heard).containsExactlyInAnyOrder("dawn", "tide", "ember");
    }

    @Test
    @DisplayName("a single track plays again and again")
    void single() {
        Playlist list = new Playlist(List.of(new Track("rainswelcome", "dawn", 60)), new Random());
        assertThat(list.next().name()).isEqualTo("dawn");
        assertThat(list.next().name()).isEqualTo("dawn");
    }

    @Test
    @DisplayName("a track's sound is in its plugin's namespace, named after its file; a namespace must be a pack one")
    void soundKey() {
        assertThat(new Track("rainswelcome", "dawn", 60).sound()).isEqualTo("rainswelcome:music.dawn");
        assertThat(Track.slug("Calm Evening (Final).OGG")).isEqualTo("calm_evening_final");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new Track("Rains Welcome", "dawn", 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
