package de.raindancer.core.ui.screen;

import de.raindancer.core.ui.chat.Style;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.Locale;
import java.util.function.Supplier;

/**
 * The frame every join refusal and kick is shown in: the server's name in its brand gradient, a divider, the body,
 * and an optional footer. Text and colour only — at login no resource pack is loaded yet, so nothing else would show.
 *
 * <p>The body stays the caller's own (usually a messages.yml line); only the frame is shared, so a ban, a whitelist
 * code and a maintenance notice read as one server's screens. Velocity plugins can't load Core and draw the same
 * frame from their own copy of these constants.
 */
public final class LeaveScreen {

    public static final String MARK = "✦";
    public static final String DIVIDER = "━━━━━━━━━━━━━━";

    private static volatile Supplier<String> title = () -> "";
    private static volatile Supplier<String> footer = () -> "";

    private LeaveScreen() {
    }

    /** Installed once at startup by RainsCorePlugin; asked per screen, so a changed setting shows on the next kick. */
    public static void configure(Supplier<String> title, Supplier<String> footer) {
        if (title != null && footer != null) {
            LeaveScreen.title = title;
            LeaveScreen.footer = footer;
        }
    }

    /** {@code body} in this server's frame. */
    public static Component framed(Component body) {
        return frame(ask(title), body, ask(footer));
    }

    private static String ask(Supplier<String> setting) {
        try {
            String value = setting.get();
            return value == null ? "" : value;
        } catch (RuntimeException broken) {
            return "";
        }
    }

    /** In the configured brand gradient. */
    public static Component frame(String name, Component body, String footer) {
        return frame(name, body, footer, Style.brandFrom(), Style.brandTo());
    }

    public static Component frame(String name, Component body, String footer, String from, String to) {
        TextComponent.Builder screen = Component.text();
        if (name != null && !name.isBlank()) {
            screen.append(header(name, from, to)).append(Component.newline())
                    .append(Component.text(DIVIDER, NamedTextColor.DARK_GRAY))
                    .append(Component.newline()).append(Component.newline());
        }
        screen.append(body);
        if (footer != null && !footer.isBlank()) {
            screen.append(Component.newline()).append(Component.newline())
                    .append(Component.text(footer.strip(), NamedTextColor.DARK_GRAY));
        }
        return screen.build();
    }

    /** "✦ NAME ✦", bold, each letter a step along the gradient. */
    public static Component header(String name, String from, String to) {
        String shown = MARK + " " + name.strip().toUpperCase(Locale.ROOT) + " " + MARK;
        TextColor start = colour(from, NamedTextColor.LIGHT_PURPLE);
        TextColor end = colour(to, start);
        TextComponent.Builder header = Component.text().decoration(TextDecoration.BOLD, true);
        int[] points = shown.codePoints().toArray();
        for (int i = 0; i < points.length; i++) {
            float progress = points.length == 1 ? 0 : (float) i / (points.length - 1);
            header.append(Component.text(Character.toString(points[i]), TextColor.lerp(progress, start, end)));
        }
        return header.build();
    }

    private static TextColor colour(String value, TextColor fallback) {
        if (value == null) {
            return fallback;
        }
        String cleaned = value.strip().toLowerCase(Locale.ROOT);
        TextColor hex = TextColor.fromHexString(cleaned);
        if (hex != null) {
            return hex;
        }
        NamedTextColor named = NamedTextColor.NAMES.value(cleaned);
        return named == null ? fallback : named;
    }
}
