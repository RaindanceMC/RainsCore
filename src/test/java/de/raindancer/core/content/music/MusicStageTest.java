package de.raindancer.core.content.music;

import de.raindancer.core.content.music.MusicStage.Layer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("the music stage: one thing plays to a player at a time, the rest wait")
class MusicStageTest {

    private final UUID player = UUID.randomUUID();
    private final List<String> heard = new ArrayList<>();

    @AfterEach
    void clear() {
        MusicStage.forget(player);
    }

    private MusicStage.Part part(String name) {
        return new MusicStage.Part() {
            @Override
            public void hush() {
                heard.add(name + " hushed");
            }

            @Override
            public void resume() {
                heard.add(name + " resumed");
            }
        };
    }

    @Test
    @DisplayName("alone, a part plays and is told nothing")
    void alone() {
        MusicStage.Cue join = MusicStage.enter(player, Layer.GREETING, part("join"));

        assertThat(join.live()).isTrue();
        assertThat(MusicStage.playing(player)).contains(Layer.GREETING);
        assertThat(heard).isEmpty();
    }

    @Test
    @DisplayName("the radio coming on hushes the greeting")
    void radioOverGreeting() {
        MusicStage.Cue join = MusicStage.enter(player, Layer.GREETING, part("join"));
        MusicStage.Cue radio = MusicStage.enter(player, Layer.CHOSEN, part("radio"));

        assertThat(radio.live()).isTrue();
        assertThat(join.live()).isFalse();
        assertThat(heard).containsExactly("join hushed");
    }

    @Test
    @DisplayName("while the radio plays, the AFK music comes in quiet and is never started")
    void awayUnderRadio() {
        MusicStage.Cue radio = MusicStage.enter(player, Layer.CHOSEN, part("radio"));
        MusicStage.Cue away = MusicStage.enter(player, Layer.AWAY, part("away"));

        assertThat(away.live()).isFalse();
        assertThat(radio.live()).isTrue();
        assertThat(heard).isEmpty();
    }

    @Test
    @DisplayName("the radio switched off lets the AFK music, waiting under it, play")
    void radioOffResumesAway() {
        MusicStage.Cue radio = MusicStage.enter(player, Layer.CHOSEN, part("radio"));
        MusicStage.Cue away = MusicStage.enter(player, Layer.AWAY, part("away"));

        radio.leave();

        assertThat(away.live()).isTrue();
        assertThat(heard).containsExactly("away resumed");
    }

    @Test
    @DisplayName("a moment pauses the radio, which comes back once it is over")
    void momentOverRadio() {
        MusicStage.Cue radio = MusicStage.enter(player, Layer.CHOSEN, part("radio"));
        MusicStage.Cue intro = MusicStage.enter(player, Layer.MOMENT, part("intro"));
        intro.leave();

        assertThat(radio.live()).isTrue();
        assertThat(heard).containsExactly("radio hushed", "radio resumed");
    }

    @Test
    @DisplayName("leaving while outranked changes nothing for what plays")
    void quietLeave() {
        MusicStage.Cue away = MusicStage.enter(player, Layer.AWAY, part("away"));
        MusicStage.Cue radio = MusicStage.enter(player, Layer.CHOSEN, part("radio"));
        heard.clear();

        away.leave();

        assertThat(radio.live()).isTrue();
        assertThat(heard).isEmpty();
    }

    @Test
    @DisplayName("on the same layer the later one plays")
    void laterWins() {
        MusicStage.Cue first = MusicStage.enter(player, Layer.MOMENT, part("first"));
        MusicStage.Cue second = MusicStage.enter(player, Layer.MOMENT, part("second"));

        assertThat(second.live()).isTrue();
        assertThat(first.live()).isFalse();
        second.leave();
        assertThat(heard).containsExactly("first hushed", "first resumed");
    }

    @Test
    @DisplayName("a part that leaves when hushed is gone, and nothing is resumed in its place")
    void leavesWhenHushed() {
        List<MusicStage.Cue> holder = new ArrayList<>();
        holder.add(MusicStage.enter(player, Layer.GREETING, new MusicStage.Part() {
            @Override
            public void hush() {
                holder.getFirst().leave();
            }

            @Override
            public void resume() {
                heard.add("join resumed");
            }
        }));
        MusicStage.Cue radio = MusicStage.enter(player, Layer.CHOSEN, part("radio"));
        radio.leave();

        assertThat(heard).isEmpty();
        assertThat(MusicStage.playing(player)).isEmpty();
    }

    @Test
    @DisplayName("leaving twice is harmless")
    void twice() {
        MusicStage.Cue radio = MusicStage.enter(player, Layer.CHOSEN, part("radio"));
        MusicStage.Cue away = MusicStage.enter(player, Layer.AWAY, part("away"));
        radio.leave();
        radio.leave();

        assertThat(heard).containsExactly("away resumed");
        assertThat(away.live()).isTrue();
    }

    @Test
    @DisplayName("forgetting a player who left drops everything without a word to anyone")
    void forget() {
        MusicStage.enter(player, Layer.CHOSEN, part("radio"));
        MusicStage.enter(player, Layer.MOMENT, part("intro"));
        heard.clear();

        MusicStage.forget(player);

        assertThat(MusicStage.playing(player)).isEmpty();
        assertThat(heard).isEmpty();
    }

    @Test
    @DisplayName("whether something above a layer is on, asked before starting at all")
    void outranked() {
        MusicStage.enter(player, Layer.CHOSEN, part("radio"));

        assertThat(MusicStage.outranked(player, Layer.GREETING)).isTrue();
        assertThat(MusicStage.outranked(player, Layer.CHOSEN)).isFalse();
        assertThat(MusicStage.outranked(UUID.randomUUID(), Layer.GREETING)).isFalse();
    }
}
