package de.raindancer.core.content.music;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("the music folder: the owner's .ogg files become sounds in the server's resource pack")
class MusicFolderTest {

    @TempDir
    Path data;

    @Test
    @DisplayName("each track is copied into the pack with a streamed sound event, and its length is known")
    void builds() throws IOException {
        Path music = Files.createDirectories(data.resolve("music"));
        try (InputStream in = getClass().getResourceAsStream("/music/three-seconds.ogg")) {
            Files.write(music.resolve("Calm Evening.ogg"), in.readAllBytes());
        }
        Files.writeString(music.resolve("notes.txt"), "not music");
        Files.writeString(music.resolve("broken.ogg"), "not really");

        Path pack = data.resolve("pack/music");
        List<Track> tracks = MusicFolder.build(music, pack, "rainswelcome", "Welcome music");

        assertThat(tracks).singleElement().satisfies(track -> {
            assertThat(track.name()).isEqualTo("calm_evening");
            assertThat(track.seconds()).isCloseTo(3.0, within(0.05));
        });
        assertThat(pack.resolve("assets/rainswelcome/sounds/music/calm_evening.ogg")).exists();
        JsonObject sounds = JsonParser.parseString(Files.readString(pack.resolve("assets/rainswelcome/sounds.json")))
                .getAsJsonObject();
        JsonObject sound = sounds.getAsJsonObject("music.calm_evening").getAsJsonArray("sounds").get(0)
                .getAsJsonObject();
        assertThat(sound.get("name").getAsString()).isEqualTo("rainswelcome:music/calm_evening");
        assertThat(sound.get("stream").getAsBoolean()).isTrue();
        JsonObject meta = JsonParser.parseString(Files.readString(pack.resolve("pack.mcmeta"))).getAsJsonObject()
                .getAsJsonObject("pack");
        assertThat(meta.get("min_format").getAsInt()).as("the formats every other module's pack declares, so a "
                + "merged pack keeps an overlap and no client calls it outdated").isEqualTo(88);
        assertThat(meta.get("max_format").getAsInt()).isEqualTo(97);
    }

    @Test
    @DisplayName("no folder, or nothing in it: no tracks, and a folder is made for the owner to fill")
    void empty() throws IOException {
        Path music = data.resolve("music");
        assertThat(MusicFolder.build(music, data.resolve("pack/music"), "rainswelcome", "Welcome music")).isEmpty();
        assertThat(music).isDirectory();
    }

    @Test
    @DisplayName("a track taken out of the folder is gone from the pack on the next build")
    void removed() throws IOException {
        Path music = Files.createDirectories(data.resolve("music"));
        Path pack = data.resolve("pack/music");
        try (InputStream in = getClass().getResourceAsStream("/music/three-seconds.ogg")) {
            Files.write(music.resolve("dawn.ogg"), in.readAllBytes());
        }
        MusicFolder.build(music, pack, "rainswelcome", "Welcome music");
        Files.delete(music.resolve("dawn.ogg"));

        assertThat(MusicFolder.build(music, pack, "rainswelcome", "Welcome music")).isEmpty();
        assertThat(pack.resolve("assets/rainswelcome/sounds/music/dawn.ogg")).doesNotExist();
    }
}
