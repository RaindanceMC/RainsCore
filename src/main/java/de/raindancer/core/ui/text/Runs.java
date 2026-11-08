package de.raindancer.core.ui.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.Style;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * A line taken apart into stretches of one style each, for finding something in its text across colour changes —
 * "<yellow>Click<gray> to open" — and building it again with that part swapped. Rebuilt pieces go side by side under
 * an empty root with their whole style written out: one nested over the rest would hand its style on to it.
 */
public final class Runs {

    /** Stands in for a piece that is not plain text, so nothing is ever matched into or across it. */
    public static final char OPAQUE = '￼';

    /** A stretch of the line in one style: plain text, or a piece that is not ({@code text == null}). */
    public record Run(String text, Style style, Component opaque) {

        int length() {
            return text == null ? 1 : text.length();
        }
    }

    private Runs() {
    }

    public static List<Run> of(Component line) {
        List<Run> runs = new ArrayList<>();
        flatten(line, Style.empty(), runs);
        return runs;
    }

    /** The line's text, a non-text piece as {@link #OPAQUE}; positions in it are positions in the runs. */
    public static String joined(List<Run> runs) {
        StringBuilder all = new StringBuilder();
        for (Run run : runs) {
            all.append(run.text() == null ? String.valueOf(OPAQUE) : run.text());
        }
        return all.toString();
    }

    /** The text between two positions, each piece in its run's style as {@code restyle} has it. */
    public static void copy(List<Run> runs, int from, int to, List<Component> out, UnaryOperator<Style> restyle) {
        int start = 0;
        for (Run run : runs) {
            int end = start + run.length();
            int a = Math.max(from, start);
            int b = Math.min(to, end);
            if (a < b) {
                Style style = restyle.apply(run.style());
                out.add(run.text() == null ? run.opaque().style(style)
                        : Component.text(run.text().substring(a - start, b - start), style));
            }
            start = end;
            if (start >= to) {
                break;
            }
        }
    }

    /** The style of the run a position falls in. */
    public static Style styleAt(List<Run> runs, int position) {
        int start = 0;
        for (Run run : runs) {
            int end = start + run.length();
            if (position < end) {
                return run.style();
            }
            start = end;
        }
        return Style.empty();
    }

    /** Pieces side by side, as one line. */
    public static Component line(List<Component> pieces) {
        return Component.textOfChildren(pieces.toArray(new Component[0]));
    }

    private static void flatten(Component component, Style inherited, List<Run> out) {
        Style style = component.style().merge(inherited, Style.Merge.Strategy.IF_ABSENT_ON_TARGET);
        if (component instanceof TextComponent text) {
            if (!text.content().isEmpty()) {
                out.add(new Run(text.content(), style, null));
            }
        } else {
            out.add(new Run(null, style, component.children(List.of()).style(style)));
        }
        for (Component child : component.children()) {
            flatten(child, style, out);
        }
    }
}
