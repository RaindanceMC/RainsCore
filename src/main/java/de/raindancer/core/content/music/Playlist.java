package de.raindancer.core.content.music;

import java.util.List;
import java.util.Random;

/** The tracks in random order, never the same one twice in a row. One per running camera. */
public final class Playlist {

    private final List<Track> tracks;
    private final Random random;
    private Track last;

    public Playlist(List<Track> tracks, Random random) {
        if (tracks.isEmpty()) {
            throw new IllegalArgumentException("a playlist needs a track");
        }
        this.tracks = List.copyOf(tracks);
        this.random = random;
    }

    public Track next() {
        Track pick = tracks.get(random.nextInt(tracks.size()));
        if (tracks.size() > 1 && pick.equals(last)) {
            pick = tracks.get((tracks.indexOf(pick) + 1 + random.nextInt(tracks.size() - 1)) % tracks.size());
        }
        last = pick;
        return pick;
    }
}
