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
    static final String PIXEL = "";

    private static final Duration KEPT = Duration.ofMinutes(30);
    private static final Duration RETRY = Duration.ofMinutes(5);
    private static final int MOST = 512;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    /** A fetched face, or null for one that could not be had — not asked again until {@link #RETRY} has passed. */
    private record Known(Component face, long at) {
    }

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
            if (own.hasTextures()) {
                profile = CompletableFuture.completedFuture(own);
            } else if (!Bukkit.getOnlineMode() && own.getName() != null) {
                // An offline-mode id is nobody's at Mojang; the name is what such a server's clients show skins by.
                profile = Bukkit.createProfile(own.getName()).update();
            } else {
                profile = own.update();
            }
        } catch (RuntimeException noServer) {
            return CompletableFuture.completedFuture(null);
        }
        return profile.thenCompose(found -> {
            URL skin = found == null ? null : found.getTextures().getSkin();
            if (skin == null) {
                return CompletableFuture.<byte[]>completedFuture(null);
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(skin.toString())).timeout(Duration.ofSeconds(4)).build();
            return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                    .thenApply(response -> response.statusCode() == 200 ? response.body() : null);
        }).handle((png, failed) -> {
            Component face = null;
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
        TextComponent.Builder face = Component.text().font(FONT).shadowColor(ShadowColor.none());
        for (int y = 0; y < 8; y++) {
            if (y > 0) {
                face.append(Component.newline());
            }
            for (int x = 0; x < 8; x++) {
                int under = skin.getRGB(8 + x, 8 + y);
                int hat = skin.getRGB(40 + x, 8 + y);
                int alpha = hat >>> 24;
                int rgb = alpha == 0 ? under : alpha == 255 ? hat : blend(under, hat, alpha);
                face.append(Component.text(PIXEL, TextColor.color(rgb & 0xFFFFFF)));
            }
        }
        return face.build();
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
