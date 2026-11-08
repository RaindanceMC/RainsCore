package de.raindancer.core.ui.identity;

import de.raindancer.core.ui.text.NameStyle;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Name styles that belong to one player: theirs on every server running Core without setting anything, and nobody
 * else's — neither the style nor anything that looks like it.
 *
 * <p>"Looks like" is measured as the eye sees it: both names sampled along their length in CIELAB, at every point of
 * the flow and either way round, and compared stretch by stretch. A lookalike has half its name barely different
 * ({@link #MEDIAN}) and even its most different tenth only moderately so ({@link #WORST_TENTH}) — so a plain purple,
 * or purple with a colour of its own in it, stays free.
 */
public final class ReservedNames {

    public static final UUID RAINDANCER118 = UUID.fromString("c9e2c821-7b5e-44e9-a5f7-7554a4da0120");

    /** Deep violet into lilac with a pale shine running through, bold and flowing. */
    public static final NameStyle AMETHYST_FLOW = new NameStyle(
            hex("#5B1E96", "#8932B8", "#C77DFF", "#F1D6FF", "#C77DFF", "#8932B8"), Set.of(TextDecoration.BOLD), true);

    private static final Map<UUID, NameStyle> OWN = Map.of(RAINDANCER118, AMETHYST_FLOW);

    /** A named style in somebody's own collection. */
    public record Preset(String title, NameStyle style) {
    }

    private static final Map<UUID, List<Preset>> PRESETS = Map.of(RAINDANCER118, List.of(
            new Preset("Amethyst Flow", AMETHYST_FLOW),
            flowing("Amethyst Night", "#3C1361", "#8932B8", "#C77DFF", "#8932B8"),
            flowing("Royal Amethyst", "#5B1E96", "#8932B8", "#C77DFF", "#FFD86F", "#C77DFF", "#8932B8"),
            flowing("Lightning", "#4C1D95", "#8932B8", "#7DD3FC", "#FFFFFF", "#7DD3FC", "#8932B8"),
            flowing("Nebula", "#8932B8", "#FF5EDB", "#FFC2F0", "#7B61FF"),
            flowing("Rain Glass", "#4361EE", "#4CC9F0", "#E0FBFC", "#4CC9F0", "#4361EE", "#8932B8"),
            flowing("Ember Amethyst", "#8932B8", "#FF2E63", "#FF8C00", "#FF2E63"),
            flowing("Midnight Aurora", "#00F5A0", "#00D9F5", "#8932B8", "#C77DFF"),
            flowing("Violet Storm", "#4361EE", "#8932B8", "#D16BFF", "#8932B8"),
            flowing("Thunderstorm", "#7B5CFF", "#4361EE", "#4CC9F0", "#E0FBFC"),
            flowing("Aurora", "#00F5A0", "#00D9F5", "#7B61FF", "#FF5EDB"),
            flowing("Neon Rain", "#00C6FF", "#5B5BFF", "#B000FF", "#FF00C8"),
            flowing("Golden Storm", "#FFD200", "#FF8C00", "#FF2E63", "#8E2DE2")));

    /** Somebody's own collection of name styles, to put on with a click; empty for nearly everybody. */
    public static List<Preset> presetsOf(UUID player) {
        return player == null ? List.of() : PRESETS.getOrDefault(player, List.of());
    }

    private static Preset flowing(String title, String... colours) {
        return new Preset(title, new NameStyle(hex(colours), Set.of(TextDecoration.BOLD), true));
    }

    static final double MEDIAN = 15;
    static final double WORST_TENTH = 24;
    private static final int SAMPLES = 48;

    private ReservedNames() {
    }

    /** The style a player has reserved, worn wherever they have set none of their own. */
    public static Optional<NameStyle> ownedBy(UUID player) {
        return Optional.ofNullable(player == null ? null : OWN.get(player));
    }

    /** Whether {@code style} is, or looks like, a style somebody other than {@code player} has reserved. */
    public static boolean reservedAgainst(UUID player, NameStyle style) {
        if (style == null || style.colours().isEmpty()) {
            return false;
        }
        for (Map.Entry<UUID, NameStyle> own : OWN.entrySet()) {
            if (!own.getKey().equals(player) && looksLike(style, own.getValue())) {
                return true;
            }
        }
        return false;
    }

    static boolean looksLike(NameStyle candidate, NameStyle reserved) {
        double[][] mine = samples(candidate);
        double[][] theirs = samples(reserved);
        double[][] backwards = new double[SAMPLES][];
        for (int i = 0; i < SAMPLES; i++) {
            backwards[i] = theirs[SAMPLES - 1 - i];
        }
        // Each measure at the point of the flow where it is smallest: the shine of one need not line up with the
        // other's for the two names to look alike.
        double median = Double.MAX_VALUE;
        double worstTenth = Double.MAX_VALUE;
        for (double[][] other : new double[][][]{theirs, backwards}) {
            for (int shift = 0; shift < SAMPLES; shift++) {
                double[] differences = new double[SAMPLES];
                for (int i = 0; i < SAMPLES; i++) {
                    differences[i] = distance(mine[i], other[(i + shift) % SAMPLES]);
                }
                Arrays.sort(differences);
                median = Math.min(median, differences[(SAMPLES - 1) / 2]);
                worstTenth = Math.min(worstTenth, differences[(int) (0.9 * (SAMPLES - 1))]);
            }
        }
        return median <= MEDIAN && worstTenth <= WORST_TENTH;
    }

    /** The name's colours along its length, in CIELAB: once round for a flowing style, end to end for a still one. */
    private static double[][] samples(NameStyle style) {
        List<TextColor> stops = style.colours();
        double[][] out = new double[SAMPLES][];
        for (int i = 0; i < SAMPLES; i++) {
            int rgb;
            if (stops.size() == 1) {
                rgb = stops.getFirst().value();
            } else if (style.isAnimated()) {
                double at = (double) i / SAMPLES * stops.size();
                int from = (int) at % stops.size();
                rgb = mix(stops.get(from).value(), stops.get((from + 1) % stops.size()).value(), at - (int) at);
            } else {
                double at = (double) i / (SAMPLES - 1) * (stops.size() - 1);
                int from = Math.min((int) at, stops.size() - 2);
                rgb = mix(stops.get(from).value(), stops.get(from + 1).value(), at - from);
            }
            out[i] = lab(rgb);
        }
        return out;
    }

    private static int mix(int a, int b, double k) {
        int r = (int) Math.round(((a >> 16) & 0xFF) * (1 - k) + ((b >> 16) & 0xFF) * k);
        int g = (int) Math.round(((a >> 8) & 0xFF) * (1 - k) + ((b >> 8) & 0xFF) * k);
        int bl = (int) Math.round((a & 0xFF) * (1 - k) + (b & 0xFF) * k);
        return (r << 16) | (g << 8) | bl;
    }

    private static double[] lab(int rgb) {
        double r = linear((rgb >> 16) & 0xFF);
        double g = linear((rgb >> 8) & 0xFF);
        double b = linear(rgb & 0xFF);
        double x = (r * 0.4124 + g * 0.3576 + b * 0.1805) / 0.95047;
        double y = r * 0.2126 + g * 0.7152 + b * 0.0722;
        double z = (r * 0.0193 + g * 0.1192 + b * 0.9505) / 1.08883;
        double fx = f(x);
        double fy = f(y);
        double fz = f(z);
        return new double[]{116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)};
    }

    private static double linear(int channel) {
        double u = channel / 255.0;
        return u > 0.04045 ? Math.pow((u + 0.055) / 1.055, 2.4) : u / 12.92;
    }

    private static double f(double t) {
        return t > 0.008856 ? Math.cbrt(t) : 7.787 * t + 16.0 / 116;
    }

    private static double distance(double[] a, double[] b) {
        return Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]));
    }

    private static List<TextColor> hex(String... colours) {
        return Arrays.stream(colours).map(TextColor::fromHexString).toList();
    }
}
