package de.raindancer.core.world.npc;

import com.destroystokyo.paper.profile.ProfileProperty;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.raindancer.core.platform.util.Scheduling;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Mannequin;
import org.bukkit.plugin.Plugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * Skins from MineSkin (mineskin.org): any skin picture, signed so every client shows it. Staff upload a PNG there and
 * write its link (or {@code mineskin:<id>}) wherever a skin is asked for. The texture is fetched once, in the
 * background, from MineSkin's public API (no key needed to read); until it is here an attendant wears the suit and is
 * dressed the moment it arrives. A skin that cannot be fetched stays the suit, with one warning.
 */
public final class Skins {

    /** A signed texture, as a game profile carries it. */
    public record Texture(String value, String signature) {
    }

    private static final Pattern ID = Pattern.compile("[0-9a-f]{8}|[0-9a-f]{32}");
    private static final String API = "https://api.mineskin.org/v2/skins/";

    private static final Map<String, Texture> known = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> asked = new ConcurrentHashMap<>();
    /** The stand-in profile ids handed out while a skin is on its way, and whose skin each stands in for. */
    private static final Map<UUID, String> standIns = new ConcurrentHashMap<>();
    /** Attendants placed while their skin was still on its way: dressed once it arrives. */
    private static final Map<String, List<Waiting>> waiting = new ConcurrentHashMap<>();
    private static final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private record Waiting(UUID entity, Location at) {
    }

    private Skins() {
    }

    /** The MineSkin id in {@code mineskin:<id>}, {@code https://minesk.in/<id>} or {@code https://mineskin.org/skins/<id>}. */
    public static Optional<String> mineSkinId(String skin) {
        if (skin == null) {
            return Optional.empty();
        }
        String text = skin.strip().toLowerCase(Locale.ROOT);
        String id;
        if (text.startsWith("mineskin:")) {
            id = text.substring("mineskin:".length());
        } else if (text.matches("https?://(www\\.)?(mineskin\\.org|minesk\\.in)/.*")) {
            String path = text.replaceFirst("[?#].*$", "").replaceFirst("/+$", "");
            id = path.substring(path.lastIndexOf('/') + 1);
        } else {
            return Optional.empty();
        }
        id = id.replace("-", "");
        return ID.matcher(id).matches() ? Optional.of(id) : Optional.empty();
    }

    /** The texture in MineSkin's answer to {@code GET /v2/skins/<id>}. */
    static Optional<Texture> parse(String answer) {
        try {
            JsonObject root = JsonParser.parseString(answer).getAsJsonObject();
            JsonObject data = root.getAsJsonObject("skin").getAsJsonObject("texture").getAsJsonObject("data");
            JsonElement value = data.get("value");
            JsonElement signature = data.get("signature");
            return value == null || signature == null ? Optional.empty()
                    : Optional.of(new Texture(value.getAsString(), signature.getAsString()));
        } catch (RuntimeException notTheAnswer) {
            return Optional.empty();
        }
    }

    /** The profile id a skin is worn under — its own, so a client never mixes two skins up. */
    static UUID profileId(String mineSkinId) {
        return UUID.nameUUIDFromBytes(("rainscore:mineskin:" + mineSkinId).getBytes(StandardCharsets.UTF_8));
    }

    /** The skin's profile if it has been fetched; otherwise asks for it and gives nothing yet. */
    static Optional<ResolvableProfile> profile(String mineSkinId) {
        Texture texture = known.get(mineSkinId);
        if (texture == null) {
            fetch(mineSkinId);
            return Optional.empty();
        }
        return Optional.of(ResolvableProfile.resolvableProfile().uuid(profileId(mineSkinId)).name("Attendant")
                .addProperty(new ProfileProperty("textures", texture.value(), texture.signature())).build());
    }

    /** {@code look} as the stand-in for this skin, remembered so the attendant wearing it is dressed later. */
    static ResolvableProfile placeholder(String mineSkinId, ResolvableProfile look) {
        standIns.put(profileId(mineSkinId), mineSkinId);
        return look;
    }

    /** The MineSkin skin {@code look} stands in for, if it is a stand-in. */
    static Optional<String> waitingFor(ResolvableProfile look) {
        UUID id = look == null ? null : look.uuid();
        return id == null ? Optional.empty() : Optional.ofNullable(standIns.get(id));
    }

    /** Dresses {@code attendant} in the skin once it arrives (now, if it already has). */
    static void dressWhenHere(String mineSkinId, Mannequin attendant) {
        if (known.containsKey(mineSkinId)) {
            profile(mineSkinId).ifPresent(attendant::setProfile);
            return;
        }
        waiting.computeIfAbsent(mineSkinId, id -> new CopyOnWriteArrayList<>())
                .add(new Waiting(attendant.getUniqueId(), attendant.getLocation()));
        fetch(mineSkinId);
    }

    private static void fetch(String mineSkinId) {
        if (asked.putIfAbsent(mineSkinId, true) != null) {
            return;
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(API + mineSkinId)).timeout(Duration.ofSeconds(10))
                .header("User-Agent", "RainsCore").GET().build();
        http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, failed) -> {
            Optional<Texture> texture = failed != null || response.statusCode() != 200 ? Optional.empty()
                    : parse(response.body());
            if (texture.isEmpty()) {
                Bukkit.getLogger().warning("[RainsCore] MineSkin skin '" + mineSkinId + "' could not be fetched ("
                        + (failed != null ? failed.getMessage() : "HTTP " + response.statusCode())
                        + "); the attendant keeps the suit.");
                waiting.remove(mineSkinId);
                return;
            }
            known.put(mineSkinId, texture.get());
            standIns.remove(profileId(mineSkinId));
            dressWaiting(mineSkinId);
        });
    }

    private static void dressWaiting(String mineSkinId) {
        List<Waiting> those = waiting.remove(mineSkinId);
        Plugin core = Bukkit.getPluginManager().getPlugin("RainsCore");
        if (those == null || core == null || !core.isEnabled()) {
            return;
        }
        ResolvableProfile look = profile(mineSkinId).orElseThrow();
        for (Waiting one : those) {
            Scheduling.region(core, one.at(), () -> {
                if (one.at().getWorld().getEntity(one.entity()) instanceof Mannequin attendant) {
                    attendant.setProfile(look);
                }
            });
        }
    }
}
