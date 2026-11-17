package de.raindancer.core.ui.book;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WelcomeBookTest {

    @AfterEach
    void clear() {
        WelcomeBook.clear();
        WelcomeBook.active(false);
    }

    private static BookChapter chapter(String id, int order, String... pages) {
        return new BookChapter(id, order, id.substring(0, 1).toUpperCase() + id.substring(1), player -> List.of(pages));
    }

    @Test
    @DisplayName("chapters come in their order; a second with the same id is ignored; withdrawn ones go")
    void registry() {
        BookChapter rules = chapter("rules", 10, "r1");
        WelcomeBook.offer(null, chapter("data", 90, "d1"));
        WelcomeBook.offer(null, rules);
        WelcomeBook.offer(null, chapter("start", 0, "s1"));
        WelcomeBook.offer(null, chapter("rules", 5, "other"));

        assertThat(WelcomeBook.chapters()).extracting(BookChapter::id).containsExactly("start", "rules", "data");
        WelcomeBook.withdraw(rules);
        assertThat(WelcomeBook.chapters()).extracting(BookChapter::id).containsExactly("start", "data");
    }

    @Test
    @DisplayName("the first page greets the player on this server and lists the chapters with the page each starts on")
    void contents() {
        List<WelcomeBook.Written> written = List.of(
                new WelcomeBook.Written("Getting started", List.of("s1")),
                new WelcomeBook.Written("Rules", List.of("r1", "r2", "r3")),
                new WelcomeBook.Written("Empty", List.of()),
                new WelcomeBook.Written("Your data", List.of("d1")));

        List<String> pages = WelcomeBook.compose("Hyades", "Ada", written);

        assertThat(pages).hasSize(6);
        assertThat(pages.getFirst()).contains("Hyades").contains("Ada")
                .contains("Getting started").contains("Rules").contains("Your data").doesNotContain("Empty")
                .contains("change_page:2").contains("change_page:3").contains("change_page:6");
        assertThat(pages.subList(1, 6)).containsExactly("s1", "r1", "r2", "r3", "d1");
    }

    @Test
    @DisplayName("a book never grows past the hundred pages the game shows")
    void capped() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            many.add("p" + i);
        }
        assertThat(WelcomeBook.compose("S", "P", List.of(new WelcomeBook.Written("Lots", many))))
                .hasSize(WelcomeBook.MOST_PAGES);
    }

    @Test
    @DisplayName("names in the greeting stay text: a player called <red>x cannot colour the page")
    void namesAreText() {
        String first = WelcomeBook.compose("Hyades", "<red>x", List.of()).getFirst();
        String shown = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(first));
        assertThat(shown).contains("Welcome, <red>x!");
    }

    @Test
    @DisplayName("whether Core hands the book out, so plugins know to stay quiet in chat")
    void active() {
        assertThat(WelcomeBook.takesOver()).isFalse();
        WelcomeBook.active(true);
        assertThat(WelcomeBook.takesOver()).isTrue();
    }
}
