package de.raindancer.core.platform.client;

import de.raindancer.client.protocol.Span;
import de.raindancer.client.protocol.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.flattener.ComponentFlattener;
import net.kyori.adventure.text.flattener.FlattenerListener;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.translation.GlobalTranslator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * A component as the mod is sent it: flattened here, into runs of one style each, so that no game version on
 * the other end has to parse this one's idea of a component.
 */
public final class TextSpans {

    /** Letters per span: 256 bytes fit on the wire, and a letter is at most four. */
    private static final int LETTERS = 60;

    private TextSpans() {
    }

    public static Text of(Component component, Locale locale) {
        List<Span> spans = new ArrayList<>();
        ComponentFlattener.basic().flatten(GlobalTranslator.render(component, locale), new FlattenerListener() {
            private final Deque<Style> styles = new ArrayDeque<>();

            @Override
            public void pushStyle(Style style) {
                Style outer = styles.peek();
                styles.push(outer == null ? style : outer.merge(style));
            }

            @Override
            public void component(String text) {
                Style style = styles.peek() == null ? Style.empty() : styles.peek();
                int argb = style.color() == null ? 0 : 0xFF000000 | style.color().value();
                String font = style.font() == null ? "" : style.font().asString();
                for (int from = 0; from < text.length(); ) {
                    int to = text.offsetByCodePoints(from, Math.min(LETTERS, text.codePointCount(from, text.length())));
                    add(spans, new Span(text.substring(from, to), argb, flags(style), font));
                    from = to;
                }
            }

            @Override
            public void popStyle(Style style) {
                styles.pop();
            }
        });
        return new Text(fitted(spans));
    }

    /** Onto the end, or into the last span when it looks the same and has room. */
    private static void add(List<Span> spans, Span next) {
        if (next.text().isEmpty()) {
            return;
        }
        Span last = spans.isEmpty() ? null : spans.getLast();
        if (last != null && last.argb() == next.argb() && last.style() == next.style() && last.font().equals(next.font())
                && last.text().length() + next.text().length() <= LETTERS) {
            spans.set(spans.size() - 1, new Span(last.text() + next.text(), last.argb(), last.style(), last.font()));
        } else {
            spans.add(next);
        }
    }

    /**
     * More runs than the wire carries — a long rainbow, letter by letter. The front keeps its styles; the rest
     * is packed into as few runs as its letters need, each in the style its first letter had. The letters all
     * arrive; only colour detail at the far end is given up.
     */
    private static List<Span> fitted(List<Span> spans) {
        if (spans.size() <= Text.MAX_SPANS) {
            return spans;
        }
        for (int keep = Text.MAX_SPANS - 1; keep >= 0; keep--) {
            List<Span> packed = new ArrayList<>(spans.subList(0, keep));
            Span current = null;
            for (Span each : spans.subList(keep, spans.size())) {
                if (current != null && current.text().length() + each.text().length() <= LETTERS) {
                    current = new Span(current.text() + each.text(), current.argb(), current.style(), current.font());
                    packed.set(packed.size() - 1, current);
                } else {
                    current = each;
                    packed.add(current);
                }
            }
            if (packed.size() <= Text.MAX_SPANS) {
                return packed;
            }
        }
        return spans.subList(0, Text.MAX_SPANS);
    }

    private static int flags(Style style) {
        int flags = 0;
        flags |= style.hasDecoration(TextDecoration.BOLD) ? Span.BOLD : 0;
        flags |= style.hasDecoration(TextDecoration.ITALIC) ? Span.ITALIC : 0;
        flags |= style.hasDecoration(TextDecoration.UNDERLINED) ? Span.UNDERLINED : 0;
        flags |= style.hasDecoration(TextDecoration.STRIKETHROUGH) ? Span.STRIKETHROUGH : 0;
        flags |= style.hasDecoration(TextDecoration.OBFUSCATED) ? Span.OBFUSCATED : 0;
        return flags;
    }
}
