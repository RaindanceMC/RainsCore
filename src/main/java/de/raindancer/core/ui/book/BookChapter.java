package de.raindancer.core.ui.book;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * One plugin's part of the book a new player is handed ({@link WelcomeBook}): its title in the contents and its pages,
 * MiniMessage, written for that player when the book is made. A page is what fits the game's page, about fourteen short
 * lines; no pages leaves the chapter out.
 */
public record BookChapter(String id, int order, String title, Function<Player, List<String>> pages) {

    public BookChapter {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(pages, "pages");
    }
}
