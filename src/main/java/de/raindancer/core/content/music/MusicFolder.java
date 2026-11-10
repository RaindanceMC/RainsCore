package de.raindancer.core.content.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.stream.Stream;

/**
 * An owner's own music: every {@code .ogg} (Vorbis — the only kind the game plays) in a plugin's music folder
 * becomes a streamed sound in a small resource pack, {@code <namespace>:music.<name>}, for Core's server pack to
 * merge in ({@link MusicPack} offers it). The pack folder is rebuilt from scratch each time, so a track taken out
 * of the music folder is gone from the pack too.
 */
public final class MusicFolder {

    private MusicFolder() {
    }

    /**
     * @param music     the owner's folder of .ogg files, made when missing
     * @param pack      the pack folder to (re)build — generated, wiped first
     * @param namespace the plugin's resource-pack namespace
     * @param label     what the pack is, for its description
     */
    public static List<Track> build(Path music, Path pack, String namespace, String label) throws IOException {
        Files.createDirectories(music);
        deleteTree(pack);
        List<Track> tracks = new ArrayList<>();
        JsonObject sounds = new JsonObject();
        Path into = pack.resolve("assets").resolve(namespace).resolve("sounds").resolve("music");
        try (Stream<Path> files = Files.list(music)) {
            for (Path file : files.filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ogg"))
                    .sorted().toList()) {
                String name = Track.slug(file.getFileName().toString());
                OptionalDouble seconds = OggLength.seconds(Files.readAllBytes(file));
                if (name.isEmpty() || seconds.isEmpty() || tracks.stream().anyMatch(t -> t.name().equals(name))) {
                    continue;
                }
                Files.createDirectories(into);
                Files.copy(file, into.resolve(name + ".ogg"));
                JsonObject sound = new JsonObject();
                sound.addProperty("name", namespace + ":music/" + name);
                sound.addProperty("stream", true);
                JsonArray list = new JsonArray();
                list.add(sound);
                JsonObject event = new JsonObject();
                event.add("sounds", list);
                sounds.add("music." + name, event);
                tracks.add(new Track(namespace, name, seconds.getAsDouble()));
            }
        }
        if (!tracks.isEmpty()) {
            Files.writeString(pack.resolve("assets").resolve(namespace).resolve("sounds.json"), sounds.toString());
            JsonObject meta = new JsonObject();
            JsonObject inner = new JsonObject();
            inner.addProperty("description", label);
            // The range every other pack of this network declares, so a merged pack keeps an overlap.
            inner.addProperty("min_format", 88);
            inner.addProperty("max_format", 97);
            meta.add("pack", inner);
            Files.writeString(pack.resolve("pack.mcmeta"), meta.toString());
        }
        return List.copyOf(tracks);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> all = Files.walk(root)) {
            for (Path path : all.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
