package de.raindancer.core.ui.chat;

import de.raindancer.core.RainsCore;
import de.raindancer.core.platform.util.FontWidth;
import de.raindancer.core.ui.menu.ClickHints;
import de.raindancer.core.ui.text.Runs;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code [/bugreport]} in a chat line as a button that runs it for whoever clicks — the whole command written on it,
 * so everybody reads what it does before they press.
 *
 * <p>Drawn, for a Java player wearing the pack: a raised button from Core's font {@link #FONT} — a left cap, a
 * background made of tiles as wide as the text, a right cap — then the pen moved back and the command written on it
 * in the game's own font. Everybody else gets the same click on bracketed text.
 */
public final class CommandButtons {

    public static final Key FONT = Key.key("rainscore", "buttons");
    private static final Key GAME_FONT = Key.key("minecraft", "default");
    private static final Pattern BUTTON = Pattern.compile("\\[(/[^\\[\\]\\s][^\\[\\]\\n]{0,63})]");
    private static final int MOST = 3;
    private static final TextColor EDGE = TextColor.color(0x8C96FF);

    static final char LEFT = '\uE000';
    static final char RIGHT = '\uE001';
    /** Background tiles 1, 2, 4 … 64 pixels wide. */
    static final char FIRST_TILE = '\uE002';
    static final int TILE_SIZES = 7;
    static final char[] TILES;
    /** {@code BACK + i} moves the pen back 2^i, {@code FORWARD + i} forward. */
    static final char BACK = '\uE010';
    static final char FORWARD = '\uE018';
    /** Each cap's width, and the room between the caps and the text. */
    static final int CAP = 2;
    static final int PAD = 3;

    static {
        TILES = new char[TILE_SIZES + 2];
        TILES[0] = LEFT;
        TILES[1] = RIGHT;
        for (int i = 0; i < TILE_SIZES; i++) {
            TILES[i + 2] = (char) (FIRST_TILE + i);
        }
    }

    private CommandButtons() {
    }

    /** The line with its {@code [/command]}s as buttons; the very same line when there are none. */
    public static Component render(Component line, boolean drawn) {
        if (line == null) {
            return null;
        }
        List<Runs.Run> runs = Runs.of(line);
        String all = Runs.joined(runs);
        Matcher matcher = BUTTON.matcher(all);
        List<Component> out = new ArrayList<>();
        int at = 0;
        int made = 0;
        while (made < MOST && matcher.find()) {
            Runs.copy(runs, at, matcher.start(), out, style -> style);
            Style there = Runs.styleAt(runs, matcher.start()).decoration(TextDecoration.OBFUSCATED, false);
            out.add(button(matcher.group(1).strip(), there, drawn));
            at = matcher.end();
            made++;
        }
        if (made == 0) {
            return line;
        }
        Runs.copy(runs, at, all.length(), out, style -> style);
        return Runs.line(out);
    }

    /** A command and a few plain words, nothing else: what a plugin's line writes on its own for "type this". */
    private static final Pattern BARE = Pattern.compile("/[a-z][a-z0-9_:-]{0,31}(?: [a-z0-9_.:-]{1,24}){0,3}");

    /**
     * A plugin's line with each command it writes on its own — in a style of its own, {@code <white>/skills</white>}
     * — as a button. Not for what players typed: their text could put any command on a button somebody else presses.
     */
    public static Component bare(Component line, boolean drawn) {
        if (line == null) {
            return null;
        }
        List<Runs.Run> runs = Runs.of(line);
        List<Component> out = new ArrayList<>();
        int made = 0;
        for (Runs.Run run : runs) {
            String text = run.text();
            String command = text == null ? "" : text.strip();
            if (made < MOST && !command.isEmpty() && run.style().clickEvent() == null
                    && BARE.matcher(command).matches()) {
                int start = text.indexOf(command);
                if (start > 0) {
                    out.add(Component.text(text.substring(0, start), run.style()));
                }
                out.add(button(command, run.style().decoration(TextDecoration.OBFUSCATED, false), drawn));
                if (start + command.length() < text.length()) {
                    out.add(Component.text(text.substring(start + command.length()), run.style()));
                }
                made++;
            } else {
                out.add(text == null ? run.opaque().style(run.style()) : Component.text(text, run.style()));
            }
        }
        return made == 0 ? line : Runs.line(out);
    }

    /** Whether this viewer gets the drawn button: a Java player wearing the pack. */
    public static boolean drawnFor(Audience viewer) {
        if (!(viewer instanceof Player player) || ClickHints.isBedrock(player.getUniqueId())
                || !RainsCore.isAvailable()) {
            return false;
        }
        var packs = RainsCore.get().resourcePacks();
        return packs != null && packs.isWearing(player.getUniqueId());
    }

    private static Component button(String command, Style there, boolean drawn) {
        ClickEvent click = ClickEvent.runCommand(command);
        HoverEvent<Component> hover = HoverEvent.showText(MiniMessage.miniMessage().deserialize(
                "<yellow>Click<gray> to run <white>" + MiniMessage.miniMessage().escapeTags(command)));
        Style base = Style.style().clickEvent(click).hoverEvent(hover).decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, false).decoration(TextDecoration.UNDERLINED, false).build();
        if (!drawn) {
            return Component.textOfChildren(
                    Component.text("[", base.color(EDGE)),
                    Component.text(command, base.color(NamedTextColor.WHITE)),
                    Component.text("]", base.color(EDGE)));
        }
        int textWidth = FontWidth.of(command);
        int width = width(textWidth);
        Style glyphs = base.font(FONT).color(NamedTextColor.WHITE).shadowColor(ShadowColor.none());
        String background = LEFT + back(1) + middle(width - 2 * CAP) + RIGHT + back(1);
        int textStart = CAP + PAD;
        return Component.textOfChildren(
                Component.text(background + move(textStart - width), glyphs),
                Component.text(command, base.font(GAME_FONT).color(NamedTextColor.WHITE)),
                Component.text(move(width - textStart - textWidth), glyphs));
    }

    /** A button's whole width for a text that advances {@code textWidth}: the caps, the room, the text's ink. */
    static int width(int textWidth) {
        return textWidth - 1 + 2 * PAD + 2 * CAP;
    }

    /** Background tiles adding up to {@code width}, each followed by a step back over the gap a glyph leaves. */
    static String middle(int width) {
        StringBuilder out = new StringBuilder();
        int left = Math.max(0, width);
        for (int i = TILE_SIZES - 1; i >= 0; i--) {
            int tile = 1 << i;
            while (left >= tile) {
                out.append((char) (FIRST_TILE + i)).append(back(1));
                left -= tile;
            }
        }
        return out.toString();
    }

    private static String back(int distance) {
        return move(-distance);
    }

    /** Invisible characters that move the pen {@code distance} pixels, back for a negative one. */
    static String move(int distance) {
        StringBuilder out = new StringBuilder();
        int left = Math.abs(distance);
        char base = distance < 0 ? BACK : FORWARD;
        for (int power = 7; power >= 0; power--) {
            while (left >= (1 << power)) {
                out.append((char) (base + power));
                left -= 1 << power;
            }
        }
        return out.toString();
    }

    /** How far one of the button font's characters moves the pen: a picture its width and the gap after it. */
    static int advance(char c) {
        if (c == LEFT || c == RIGHT) {
            return CAP + 1;
        }
        if (c >= FIRST_TILE && c < FIRST_TILE + TILE_SIZES) {
            return (1 << (c - FIRST_TILE)) + 1;
        }
        if (c >= BACK && c < BACK + 8) {
            return -(1 << (c - BACK));
        }
        if (c >= FORWARD && c < FORWARD + 8) {
            return 1 << (c - FORWARD);
        }
        return 0;
    }
}
