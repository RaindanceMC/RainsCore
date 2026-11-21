package de.raindancer.core.content.music;

import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.sound.SoundStop;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("a music session: skipping, the track playing now, changing the volume")
class MusicSessionTest {

    private static final List<Track> TRACKS = List.of(new Track("rainsradio", "calm.dawn", 120, "Dawn"),
            new Track("rainsradio", "calm.tide", 90, "Tide"), new Track("rainsradio", "calm.ember", 150, "Ember"));

    private final Player player = mock(Player.class);
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final List<Track> started = new ArrayList<>();

    private MusicSession session() {
        return new MusicSession(player, player, TRACKS, 0.5f, now::get, started::add);
    }

    private List<Sound> played() {
        ArgumentCaptor<Sound> sounds = ArgumentCaptor.forClass(Sound.class);
        verify(player, atLeastOnce()).playSound(sounds.capture(), any(Sound.Emitter.class));
        return sounds.getAllValues();
    }

    @Test
    @DisplayName("the first track starts at once, and the listener hears of it")
    void starts() {
        MusicSession session = session();

        assertThat(started).hasSize(1);
        assertThat(session.current()).contains(started.getFirst());
        assertThat(played()).singleElement().satisfies(sound -> {
            assertThat(sound.name().asString()).isEqualTo(started.getFirst().sound());
            assertThat(sound.source()).isEqualTo(Sound.Source.RECORD);
            assertThat(sound.volume()).isEqualTo(0.5f);
        });
    }

    @Test
    @DisplayName("skipping stops the track and plays another one")
    void skip() {
        MusicSession session = session();
        Track first = started.getFirst();

        assertThat(session.skip()).isTrue();

        assertThat(started).hasSize(2);
        assertThat(started.get(1)).isNotEqualTo(first);
        assertThat(session.current()).contains(started.get(1));
        verify(player).stopSound(SoundStop.namedOnSource(net.kyori.adventure.key.Key.key(first.sound()),
                Sound.Source.RECORD));
    }

    @Test
    @DisplayName("a track that has ended is followed by the next on the tick")
    void next() {
        MusicSession session = session();
        now.addAndGet((long) ((started.getFirst().seconds() + 5) * 1000));

        session.tick();

        assertThat(started).hasSize(2);
    }

    @Test
    @DisplayName("a new volume plays the track again at it, since the game cannot turn a playing sound up")
    void volume() {
        MusicSession session = session();
        Track first = started.getFirst();

        session.volume(0.2f);

        assertThat(session.volume()).isEqualTo(0.2f);
        assertThat(session.current()).contains(first);
        assertThat(played()).hasSize(2).last().satisfies(sound -> {
            assertThat(sound.volume()).isEqualTo(0.2f);
            assertThat(sound.name().asString()).isEqualTo(first.sound());
        });
    }

    @Test
    @DisplayName("stopped, it is silent for good: no current track, a tick starts nothing, a skip does nothing")
    void stopped() {
        MusicSession session = session();
        session.stop();
        now.addAndGet(1_000_000);

        session.tick();

        assertThat(session.current()).isEmpty();
        assertThat(session.skip()).isFalse();
        assertThat(started).hasSize(1);
    }
}
