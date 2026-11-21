package de.raindancer.core.content.music;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.sound.SoundStop;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Music for one player for a while — a cutscene, a camera: the owner's tracks shuffled one after another, or one
 * sound by its key when they have no pack or there are no tracks.
 *
 * <p>Played on the jukebox slider: the client starts its own background music on the music slider every few
 * minutes by itself, and only a whole slider can be silenced from the server, so {@link #tick} keeps that one
 * quiet. Only the one player hears it ({@code player.playSound}). Never bind it to a silent entity: the client
 * drops every sound bound to one.
 */
public final class MusicSession {

    private static final Sound.Source OURS = Sound.Source.RECORD;
    private static final double GAP_SECONDS = 4;

    private final Player player;
    private final Entity emitter;
    private final LongSupplier clock;
    private final Consumer<Track> onTrack;
    private float volume;
    private Playlist playlist;
    private String playing = "";
    private Track current;
    private long nextAt;
    private long quietAt;

    /**
     * @param tracks   the owner's tracks; empty or {@code wearsPack} false plays {@code fallback} once instead
     * @param fallback a sound key such as minecraft:music.menu; blank plays nothing then
     * @param emitter  where the sound comes from — the camera they look through, or the player
     */
    public MusicSession(Player player, Entity emitter, List<Track> tracks, boolean wearsPack, String fallback,
                        float volume, LongSupplier clock) {
        this(player, emitter, tracks, wearsPack, fallback, volume, clock, track -> { });
    }

    /**
     * Only the tracks, for someone wearing the pack — a radio station. Since 2.115.0.
     *
     * @param onTrack told of every track as it starts, the first one included — for a "now playing" line
     */
    public MusicSession(Player player, Entity emitter, List<Track> tracks, float volume, LongSupplier clock,
                        Consumer<Track> onTrack) {
        this(player, emitter, tracks, true, "", volume, clock, onTrack);
    }

    private MusicSession(Player player, Entity emitter, List<Track> tracks, boolean wearsPack, String fallback,
                         float volume, LongSupplier clock, Consumer<Track> onTrack) {
        this.player = player;
        this.emitter = emitter;
        this.clock = clock;
        this.volume = volume;
        this.onTrack = onTrack;
        player.stopSound(SoundStop.source(Sound.Source.MUSIC));
        quietAt = clock.getAsLong() + 5_000;
        if (wearsPack && !tracks.isEmpty()) {
            playlist = new Playlist(tracks, new Random());
            next();
        } else if (fallback != null && !fallback.isBlank() && Key.parseable(fallback.strip())) {
            play(fallback.strip());
        }
    }

    /** Now and then while it runs: the next track when one ends, the game's own music kept quiet. */
    public void tick() {
        if (playing.isEmpty()) {
            return;
        }
        long now = clock.getAsLong();
        if (now >= quietAt) {
            player.stopSound(SoundStop.source(Sound.Source.MUSIC));
            quietAt = now + 5_000;
        }
        if (playlist != null && now >= nextAt) {
            next();
        }
    }

    public String playing() {
        return playing;
    }

    /** The track heard now; none for a fallback sound or once stopped. Since 2.115.0. */
    public Optional<Track> current() {
        return Optional.ofNullable(current);
    }

    /** Straight on to another track. False, and nothing done, with no tracks or once stopped. Since 2.115.0. */
    public boolean skip() {
        if (playlist == null || playing.isEmpty()) {
            return false;
        }
        next();
        return true;
    }

    public float volume() {
        return volume;
    }

    /**
     * A new volume, 0 to 1. The game cannot change a sound already playing, so the track starts over at it.
     * Since 2.115.0.
     */
    public void volume(float volume) {
        this.volume = Math.max(0f, Math.min(1f, volume));
        if (current != null && !playing.isEmpty()) {
            Track again = current;
            stop();
            start(again);
        }
    }

    public void stop() {
        if (!playing.isEmpty()) {
            player.stopSound(SoundStop.namedOnSource(Key.key(playing), OURS));
            playing = "";
        }
        current = null;
    }

    private void next() {
        stop();
        start(playlist.next());
    }

    private void start(Track track) {
        current = track;
        play(track.sound());
        nextAt = clock.getAsLong() + (long) ((track.seconds() + GAP_SECONDS) * 1000);
        onTrack.accept(track);
    }

    private void play(String key) {
        playing = key;
        Sound sound = Sound.sound(Key.key(key), OURS, volume, 1f);
        if (emitter != null && emitter.isValid()) {
            player.playSound(sound, emitter);
        } else {
            player.playSound(sound, Sound.Emitter.self());
        }
    }
}
