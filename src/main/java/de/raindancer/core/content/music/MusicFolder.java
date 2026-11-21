package de.raindancer.core.content.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
        JsonObject sounds = new JsonObject();
        List<Track> tracks = shelf(music, pack, namespace, "", sounds);
        finish(pack, namespace, label, sounds);
        return tracks;
    }

    /**
     * The same with a folder per shelf — a radio's stations, say: {@code music/<shelf>/*.ogg} becomes
     * {@code <namespace>:music.<shelf>.<name>}, every shelf in the one pack. A shelf's folder is made when missing.
     *
     * @param shelves lower-case names, a–z, 0–9 and underscores
     * @return every shelf in the order given, empty ones too
     */
    public static Map<String, List<Track>> build(Path music, Path pack, String namespace, String label,
                                                 List<String> shelves) throws IOException {
        deleteTree(pack);
        JsonObject sounds = new JsonObject();
        Map<String, List<Track>> all = new LinkedHashMap<>();
        for (String shelf : shelves) {
            if (!shelf.matches("[a-z0-9_]+")) {
                throw new IllegalArgumentException("not a shelf name: " + shelf);
            }
            Path folder = Files.createDirectories(music.resolve(shelf));
            all.put(shelf, shelf(folder, pack, namespace, shelf, sounds));
        }
        finish(pack, namespace, label, sounds);
        return all;
    }

    private static List<Track> shelf(Path folder, Path pack, String namespace, String shelf, JsonObject sounds)
            throws IOException {
        List<Track> tracks = new ArrayList<>();
        String prefix = shelf.isEmpty() ? "" : shelf + ".";
        Path into = pack.resolve("assets").resolve(namespace).resolve("sounds").resolve("music");
        if (!shelf.isEmpty()) {
            into = into.resolve(shelf);
        }
        String path = namespace + ":music/" + (shelf.isEmpty() ? "" : shelf + "/");
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : files.filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ogg"))
                    .sorted().toList()) {
                String fileName = file.getFileName().toString();
                String name = Track.slug(fileName);
                OptionalDouble seconds = OggLength.seconds(Files.readAllBytes(file));
                if (name.isEmpty() || seconds.isEmpty() || tracks.stream().anyMatch(t -> t.name().equals(prefix + name))) {
                    continue;
                }
                Files.createDirectories(into);
                Files.copy(file, into.resolve(name + ".ogg"));
                JsonObject sound = new JsonObject();
                sound.addProperty("name", path + name);
                sound.addProperty("stream", true);
                JsonArray list = new JsonArray();
                list.add(sound);
                JsonObject event = new JsonObject();
                event.add("sounds", list);
                sounds.add("music." + prefix + name, event);
                tracks.add(new Track(namespace, prefix + name, seconds.getAsDouble(),
                        fileName.replaceFirst("(?i)\\.ogg$", "").strip()));
            }
        }
        return List.copyOf(tracks);
    }

    private static void finish(Path pack, String namespace, String label, JsonObject sounds) throws IOException {
        if (sounds.isEmpty()) {
            return;
        }
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
