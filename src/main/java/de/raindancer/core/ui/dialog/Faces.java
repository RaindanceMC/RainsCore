package de.raindancer.core.ui.dialog;

import com.destroystokyo.paper.profile.PlayerProfile;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A player's face, big, in a dialog: the 8×8 face of their skin with the hat layer on top, drawn as eight lines of
 * square glyphs from Core's resource pack ({@code rainscore:face}), each in its pixel's colour — nine times the size
 * of a head item, which a dialog draws at sixteen pixels and cannot scale.
 *
 * <p>The skin has to be fetched first ({@link #load}); until it is, {@link #cached} has nothing and a dialog shows
 * the head item instead. Bedrock players have no Java skin, and a Bedrock viewer sees a form without Core's font, so
 * neither gets a face.
 */
public final class Faces {

    public static final Key FONT = Key.key("rainscore", "face");
    /** One pixel: a square, then a step back over the gap a glyph leaves after itself. */
    static final String PIXEL = "\uE000\uE001";
    /**
     * How wide a face is drawn, in pixels. When the client wraps the line it counts a square as ten — the step back is
     * not counted — so eight need 80 and nine 90; 85 sits in the middle and wraps after every eighth. (Measured with
     * the real client: 73 and 77 wrapped after seven.)
     */
    public static final int WIDTH = 85;

    private static final Duration KEPT = Duration.ofMinutes(30);
    private static final Duration RETRY = Duration.ofMinutes(5);
    private static final int MOST = 512;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    /** A fetched face, or null for one that could not be had — not asked again until {@link #RETRY} has passed. */
    private record Known(Component face, long at) {
    }

    private static final de.raindancer.core.platform.log.LogChannel log = de.raindancer.core.platform.log.Log.of("gui");
    private static final Map<UUID, Known> KNOWN = new ConcurrentHashMap<>();

    private Faces() {
    }

    /** Their face, if it was fetched lately. */
    public static Optional<Component> cached(UUID who) {
        Known known = KNOWN.get(who);
        if (known == null || known.face() == null || System.currentTimeMillis() - known.at() > KEPT.toMillis()) {
            return Optional.empty();
        }
        return Optional.of(known.face());
    }

    /**
     * Fetches their face if it is not known yet. Completes — never exceptionally — once it is known or cannot be,
     * on whatever thread the fetch ended on.
     */
    public static CompletableFuture<Void> load(UUID who) {
        Known known = KNOWN.get(who);
        long now = System.currentTimeMillis();
        if (isBedrock(who) || known != null && now - known.at() < (known.face() == null ? RETRY : KEPT).toMillis()) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<PlayerProfile> profile;
        try {
            PlayerProfile own = Bukkit.getOfflinePlayer(who).getPlayerProfile();
            if (own.hasTextures() && own.getTextures().getSkin() != null) {
                profile = CompletableFuture.completedFuture(own);
            } else if (!Bukkit.getOnlineMode() && own.getName() != null) {
                // An offline-mode id is nobody's at Mojang, and Paper's own lookup by name answers it from the user
                // cache with that same id: Mojang is asked whose the name is, as such a server's clients do.
                String name = own.getName();
                HttpRequest whose = HttpRequest.newBuilder(URI.create("https://api.mojang.com/users/profiles/minecraft/"
                        + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8)))
                        .timeout(Duration.ofSeconds(4)).build();
                profile = HTTP.sendAsync(whose, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
                    if (response.statusCode() != 200) {
                        return null;
                    }
                    String id = com.google.gson.JsonParser.parseString(response.body()).getAsJsonObject()
                            .get("id").getAsString();
                    PlayerProfile real = Bukkit.createProfile(UUID.fromString(id.replaceFirst(
                            "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5")), name);
                    real.complete(true, true);
                    return real;
                });
            } else {
                profile = CompletableFuture.supplyAsync(() -> {
                    own.complete(true, true);
                    return own;
                });
            }
        } catch (RuntimeException noServer) {
            log.debug("No face for {}: {}", who, noServer);
            return CompletableFuture.completedFuture(null);
        }
        return profile.thenCompose(found -> {
            URL skin = found == null ? null : found.getTextures().getSkin();
            if (skin == null) {
                log.debug("No face for {}: the profile {} has no skin", who, found == null ? null : found.getName());
                return CompletableFuture.<byte[]>completedFuture(null);
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(skin.toString())).timeout(Duration.ofSeconds(4)).build();
            return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                    .thenApply(response -> {
                        if (response.statusCode() != 200) {
                            log.debug("No face for {}: {} answered {}", who, skin, response.statusCode());
                            return null;
                        }
                        return response.body();
                    });
        }).handle((png, failed) -> {
            Component face = null;
            if (failed != null) {
                log.debug("No face for {}: {}", who, failed);
            } else if (png == null) {
                log.debug("No face for {}: no skin to be had", who);
            }
            if (png != null) {
                try {
                    face = face(ImageIO.read(new ByteArrayInputStream(png)));
                } catch (java.io.IOException | RuntimeException unreadable) {
                    face = null;
                }
            }
            if (KNOWN.size() > MOST) {
                KNOWN.clear();
            }
            KNOWN.put(who, new Known(face, System.currentTimeMillis()));
            return null;
        });
    }

    /** The face of a skin, hat on top; null when the picture is no skin. */
    static Component face(BufferedImage skin) {
        if (skin == null || skin.getWidth() != 64 || skin.getHeight() != 64 && skin.getHeight() != 32) {
            return null;
        }
        boolean wearsHat = hatSeeThrough(skin);
        TextComponent.Builder face = Component.text().font(FONT).shadowColor(ShadowColor.none());
        // No line breaks: a dialog measured each as lines of their own and left a face's height of nothing below it.
        // The face is drawn WIDTH wide instead, which wraps it after every eighth pixel.
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int under = skin.getRGB(8 + x, 8 + y);
                int over = skin.getRGB(40 + x, 8 + y);
                int alpha = wearsHat ? over >>> 24 : 0;
                int rgb = alpha == 0 ? under : alpha == 255 ? over : blend(under, over, alpha);
                face.append(Component.text(PIXEL, TextColor.color(rgb & 0xFFFFFF)));
            }
        }
        return face.build();
    }

    /**
     * The game's rule for old skins: a hat layer without a single see-through pixel was painted over by an editor
     * that knew no layers, and is not worn.
     */
    private static boolean hatSeeThrough(BufferedImage skin) {
        for (int y = 0; y < 16; y++) {
            for (int x = 32; x < 64; x++) {
                if (skin.getRGB(x, y) >>> 24 < 128) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int blend(int under, int over, int alpha) {
        int red = mix(under >> 16 & 0xFF, over >> 16 & 0xFF, alpha);
        int green = mix(under >> 8 & 0xFF, over >> 8 & 0xFF, alpha);
        int blue = mix(under & 0xFF, over & 0xFF, alpha);
        return red << 16 | green << 8 | blue;
    }

    private static int mix(int under, int over, int alpha) {
        return (over * alpha + under * (255 - alpha)) / 255;
    }

    /** Floodgate gives Bedrock players ids whose upper half is zero; no Java account has one. */
    public static boolean isBedrock(UUID id) {
        return id.getMostSignificantBits() == 0;
    }
}
