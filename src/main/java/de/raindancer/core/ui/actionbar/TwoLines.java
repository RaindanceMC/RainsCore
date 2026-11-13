package de.raindancer.core.ui.actionbar;

import de.raindancer.core.platform.util.FontWidth;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Two lines above the hotbar. The action bar draws one line, centred; the second is written in
 * {@code rainscore:below} — the game's own font glyphs set ten pixels lower, which lands in the free row over the
 * armour bar — and laid under the first with spacing glyphs of that font, so both come out centred.
 * <p>
 * Widths come from {@link FontWidth}, the default font's advances, which {@code below} shares glyph for glyph.
 * Without the server pack the lower line shows beside the upper one, in the default font.
 */
public final class TwoLines {

    public static final Key FONT = Key.key("rainscore", "below");

    private static final char PLUS = '';
    private static final char MINUS = '';
    private static final int STEPS = 9;

    private TwoLines() {
    }

    /** {@code upper} where the action bar shows, {@code lower} under it, both centred. */
    public static Component of(Component upper, Component lower) {
        int a = FontWidth.of(plain(upper));
        int b = FontWidth.of(plain(lower));
        int back = -Math.round((a + b) / 2f);
        int after = -back - b;
        return Component.text().append(upper)
                .append(Component.text(spaces(back)).font(FONT))
                .append(Component.text().font(FONT).append(lower))
                .append(Component.text(spaces(after)).font(FONT))
                .build();
    }

    /** Spacing glyphs of {@link #FONT} that move the next glyph {@code pixels} on, either way. */
    static String spaces(int pixels) {
        StringBuilder out = new StringBuilder();
        char base = pixels < 0 ? MINUS : PLUS;
        int left = Math.abs(pixels);
        int largest = 1 << (STEPS - 1);
        while (left > 2 * largest - 1) {
            out.append((char) (base + STEPS - 1));
            left -= largest;
        }
        for (int bit = STEPS - 1; bit >= 0; bit--) {
            if ((left & (1 << bit)) != 0) {
                out.append((char) (base + bit));
            }
        }
        return out.toString();
    }

    static boolean isSpacing(char ch) {
        return (ch >= PLUS && ch < PLUS + STEPS) || (ch >= MINUS && ch < MINUS + STEPS);
    }

    static int advance(int ch) {
        if (ch >= PLUS && ch < PLUS + STEPS) {
            return 1 << (ch - PLUS);
        }
        if (ch >= MINUS && ch < MINUS + STEPS) {
            return -(1 << (ch - MINUS));
        }
        return 0;
    }

    private static String plain(Component text) {
        return PlainTextComponentSerializer.plainText().serialize(text);
    }
}
