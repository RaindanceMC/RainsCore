package de.raindancer.core.ui.board;

import org.bukkit.Material;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * One ranking a plugin keeps — fishing levels, the richest players, the most captures — offered to anything that
 * shows rankings: screens, statues, podiums. The plugin keeps the numbers; whoever shows them only asks.
 *
 * @param id          {@code plugin:name}, as typed in commands ({@code fishing:level})
 * @param title       what it ranks, short ({@code Fishing level}); a caption under a statue
 * @param description a sentence for a picker
 * @param top         the board, best first; may be asked often, so a plugin keeps it cheap
 */
public record Leaderboard(String id, String title, String description, Material icon,
                          Supplier<List<Standing>> top) {

    /**
     * One place on a board.
     *
     * @param value what the place is held with, as shown ({@code level 25}, {@code 940 kg Astral Leviathan})
     */
    public record Standing(UUID player, String name, String value) {
    }

    public Leaderboard {
        if (id == null || !id.matches("[a-z0-9_-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("A leaderboard id is plugin:name in lower case (got '" + id + "').");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("A leaderboard needs a title.");
        }
        if (top == null) {
            throw new IllegalArgumentException("A leaderboard needs its standings.");
        }
        description = description == null ? "" : description;
        icon = icon == null ? Material.PAPER : icon;
    }
}
