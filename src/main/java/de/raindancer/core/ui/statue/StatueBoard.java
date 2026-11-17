package de.raindancer.core.ui.statue;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * What statues can stand for, offered by the plugin that knows who is on it ({@link StatueBoards}): the richest
 * players, Prime members, the server's creators. Core puts the statues up, dresses them in the skins of whoever
 * holds their place, and lets a thank-you board's people take turns when there are more of them than statues.
 *
 * @param id        what staff type ({@code richest}); lower case, digits, {@code _} and {@code -}; written into every
 *                  spot, so keep it once statues stand for it
 * @param heading   MiniMessage floating over a ranking's statues
 * @param caption   MiniMessage under a name, before what the place is held with ({@code <gray>won })
 * @param ranks     for a sentence, "the #2 of the {@code ranks}" ({@code richest players})
 * @param mark      MiniMessage before a thank-you statue's name ({@code PRIME}); unused for a ranking
 * @param thankYou  a thank-you rather than a ranking: up to {@link StatueSpot#MOST} statues placed one at a time, on the
 *                  ground, unlabelled, taking turns; a ranking is a top three on pedestals
 * @param standings best (or longest-standing) first. Asked off the server thread every few seconds, so it may read
 *                  what it needs, but it should still be quick
 */
public record StatueBoard(String id, String heading, String caption, String ranks, String mark, boolean thankYou,
                          Supplier<List<Standing>> standings) {

    private static final Pattern ID = Pattern.compile("[a-z0-9_-]+");
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /**
     * One place on a board.
     *
     * @param worth what the place is held with, as shown after the caption ({@code 5,000 coins}); null for none
     */
    public record Standing(UUID player, String name, Component worth) {

        public Standing {
            worth = worth == null ? Component.empty() : worth;
        }
    }

    public StatueBoard {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("A statue board's id is lower case letters, digits, underscores and hyphens (got '"
                    + id + "').");
        }
        if (standings == null) {
            throw new IllegalArgumentException("A statue board needs its standings.");
        }
        heading = heading == null ? "" : heading;
        caption = caption == null ? "" : caption;
        ranks = ranks == null || ranks.isBlank() ? id : ranks;
        mark = mark == null ? "" : mark;
    }

    /** A top three on pedestals under a label. */
    public static StatueBoard ranking(String id, String heading, String caption, String ranks,
                                      Supplier<List<Standing>> standings) {
        return new StatueBoard(id, heading, caption, ranks, "", false, standings);
    }

    /** Statues of everybody on it, placed one at a time, taking turns when there are more people than statues. */
    public static StatueBoard thanks(String id, String heading, String caption, String mark, String ranks,
                                     Supplier<List<Standing>> standings) {
        return new StatueBoard(id, heading, caption, ranks, mark, true, standings);
    }

    /** Whether a typed id names this board. */
    public static boolean validId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    /** The medal colour of a place, as MiniMessage: gold, silver, bronze, then white. */
    public static String medal(int place) {
        return switch (place) {
            case 1 -> "<gold>";
            case 2 -> "<gray>";
            case 3 -> "<#cd7f32>";
            default -> "<white>";
        };
    }

    /** The name over a statue, MiniMessage; {@code name} null for a place nobody holds yet. */
    public String title(int place, String name) {
        if (thankYou) {
            String before = mark.isEmpty() ? "" : mark + " ";
            return name == null ? before + "<dark_gray>This spot is waiting"
                    : before + "<white>" + MINI.escapeTags(name);
        }
        if (name == null) {
            return "<gray><bold>#" + place + "</bold> <dark_gray>Nobody yet";
        }
        return medal(place) + "<bold>#" + place + "</bold> <white>" + MINI.escapeTags(name);
    }

    /** The line under a statue's name: the caption and what the place is held with; empty for nobody. */
    public Component line(Standing holder) {
        if (holder == null) {
            return Component.empty();
        }
        return MINI.deserialize(caption).append(holder.worth());
    }
}
