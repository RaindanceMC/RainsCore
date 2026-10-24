package de.raindancer.core.ui.help;

import de.raindancer.core.platform.command.CommandNote;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class HelpLibraryTest {

    private static HelpTopic topic(String yaml, String plugin) {
        var parsed = HelpTopicParser.parse(yaml, "test");
        assertThat(parsed.problems()).isEmpty();
        return parsed.topic().orElseThrow().from(plugin);
    }

    private static final HelpTopic ECONOMY = topic(HelpTopicParserTest.ECONOMY, "RainsEconomy");
    private static final HelpTopic SHOP = topic("""
            id: shop
            title: The Shop
            summary: Buying and selling with the server.
            category: Economy & trade
            order: 20
            commands: [shop, sell]
            module: economy
            sections:
              - question: How do I sell items?
                text: Hold it and type {cmd:/sell hand}. You get the price at once.
            """, "RainsEconomy");
    private static final HelpTopic STAFF = topic("""
            id: moderation
            title: Moderation
            summary: Tools for staff.
            category: Staff
            permission: rainsmoderation.staff
            sections:
              - question: How do I ban?
                text: /ban.
            """, "RainsModeration");

    private final HelpContexts.Fixed player = HelpContexts.fixed(Map.of("economy:pay.tax-percent", 5))
            .withModules(Set.of("economy"));

    private HelpLibrary library(List<HelpTopic> overrides, Set<String> hidden, List<HelpLibrary.Feature> features) {
        return HelpLibrary.of(List.of(STAFF, SHOP, ECONOMY), overrides, hidden, features, List.of());
    }

    @Test
    @DisplayName("a reader sees what their permissions, the settings and the installed modules allow")
    void visibility() {
        HelpLibrary library = library(List.of(), Set.of(), List.of());
        assertThat(library.visibleTo(player)).extracting(HelpTopic::id).containsExactly("economy", "shop");
        assertThat(library.visibleTo(player.withPermissions(Set.of("rainsmoderation.staff"))))
                .extracting(HelpTopic::id).containsExactly("economy", "shop", "moderation");
        assertThat(library.visibleTo(player.withModules(Set.of()))).isEmpty();
        // Sections too: no tax, no tax question; no admin permission, no admin question.
        assertThat(HelpLibrary.sectionsFor(ECONOMY, player)).hasSize(2);
        assertThat(HelpLibrary.sectionsFor(ECONOMY, HelpContexts.fixed(Map.of("economy:pay.tax-percent", 0))))
                .hasSize(1);
    }

    @Test
    @DisplayName("/help finds a topic by id, alias, command or title — never one the reader may not see")
    void find() {
        HelpLibrary library = library(List.of(), Set.of(), List.of());
        assertThat(library.find("economy", player)).contains(ECONOMY);
        assertThat(library.find("Money", player)).contains(ECONOMY);
        assertThat(library.find("/sell", player)).contains(SHOP);
        assertThat(library.find("sell hand", player)).contains(SHOP);
        assertThat(library.find("the shop", player)).contains(SHOP);
        assertThat(library.find("moderation", player)).isEmpty();
        assertThat(library.closest("econmy", player)).contains("economy");
    }

    @Test
    @DisplayName("a question is found by number or by its words")
    void sections() {
        assertThat(HelpLibrary.section(ECONOMY, "1", player)).map(HelpSection::title).contains("Earning");
        assertThat(HelpLibrary.section(ECONOMY, "taxed", player)).map(HelpSection::title).contains("Is paying taxed?");
        assertThat(HelpLibrary.section(ECONOMY, "9", player)).isEmpty();
    }

    @Test
    @DisplayName("search needs every word somewhere, ranks titles first, and names the question that answers")
    void search() {
        HelpLibrary library = library(List.of(), Set.of(), List.of());
        var hits = library.search("sell items", player);
        assertThat(hits).first().satisfies(hit -> {
            assertThat(hit.topic()).isEqualTo(SHOP);
            assertThat(hit.section().question()).isEqualTo("How do I sell items?");
        });
        // The answer is searched as the reader reads it — the tax as a number.
        assertThat(library.search("5 %", player)).extracting(hit -> hit.topic().id()).containsExactly("economy");
        assertThat(library.search("ban", player)).isEmpty();
        assertThat(library.search("zebra", player)).isEmpty();
    }

    @Test
    @DisplayName("the owner's topics replace shipped ones and hidden ones are gone")
    void overrides() {
        HelpTopic mine = topic("""
                id: shop
                title: Our market
                summary: Ours.
                sections:
                  - question: Where?
                    text: At spawn.
                """, "help.yml");
        HelpLibrary library = library(List.of(mine), Set.of("economy"), List.of());
        assertThat(library.visibleTo(player)).extracting(HelpTopic::title).containsExactly("Our market");
    }

    @Test
    @DisplayName("an installed feature without a guide still gets a topic, made of its commands")
    void generated() {
        var homes = new HelpLibrary.Feature("homes", "Homes", "Set homes and go back to them.",
                List.of(CommandNote.of("Homes", "home", "Go home."), CommandNote.of("Homes", "home set", "Set one.")));
        var economy = new HelpLibrary.Feature("economy", "Economy", "Money.",
                List.of(CommandNote.of("Economy", "pay", "Pay.")));
        HelpLibrary library = library(List.of(), Set.of(), List.of(homes, economy));
        HelpTopic generated = library.byId("homes").orElseThrow();
        assertThat(generated.generated()).isTrue();
        assertThat(generated.commands()).containsExactly("home");
        assertThat(library.find("home", player.withModules(Set.of("economy", "homes")))).contains(generated);
        // Economy has a written guide, so nothing is generated for it.
        assertThat(library.all()).filteredOn(HelpTopic::generated).extracting(HelpTopic::id).containsExactly("homes");
    }

    @Test
    @DisplayName("two plugins shipping one id, and links to topics that are not here, are problems")
    void problems() {
        HelpTopic again = SHOP.from("RainsShops");
        HelpLibrary library = HelpLibrary.of(List.of(SHOP, again, ECONOMY), List.of(), Set.of(), List.of(), List.of());
        assertThat(library.problems()).anyMatch(problem -> problem.contains("Two plugins ship the topic 'shop'"));
        HelpLibrary lonely = HelpLibrary.of(List.of(ECONOMY), List.of(), Set.of(), List.of(), List.of());
        assertThat(lonely.problems()).anyMatch(problem -> problem.contains("'shop', which is not here"));
    }
}
