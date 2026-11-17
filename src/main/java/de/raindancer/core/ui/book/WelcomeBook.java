package de.raindancer.core.ui.book;

import de.raindancer.core.platform.util.PluginCode;
import de.raindancer.core.ui.text.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The book a new player is handed on their first join instead of a screen of chat: a contents page, then every plugin's
 * chapter — getting started, the rules, what is kept about them. Plugins offer chapters here; while Core hands the book
 * out ({@link #takesOver()}) they leave their first-join lines out of the chat. Static and process-wide, like
 * {@code TravelDoors}.
 */
public final class WelcomeBook {

    /** The most pages the game shows in a written book. */
    public static final int MOST_PAGES = 100;
    public static final NamespacedKey TAG = new NamespacedKey("rainscore", "welcome_book");

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final List<Entry> offered = new ArrayList<>();
    private static volatile boolean active;

    private record Entry(Plugin owner, BookChapter chapter) {
    }

    /** One chapter as written for a player: its title and its pages. */
    public record Written(String title, List<String> pages) {
    }

    private WelcomeBook() {
    }

    /** Offers a chapter; a second with the same id is ignored. */
    public static void offer(Plugin owner, BookChapter chapter) {
        if (chapter == null) {
            return;
        }
        synchronized (offered) {
            if (offered.stream().noneMatch(each -> each.chapter().id().equals(chapter.id()))) {
                offered.add(new Entry(owner, chapter));
            }
        }
    }

    public static void withdraw(BookChapter chapter) {
        synchronized (offered) {
            offered.removeIf(each -> each.chapter() == chapter || each.chapter().id().equals(chapter.id()));
        }
    }

    /** Every chapter, by order and then id. */
    public static List<BookChapter> chapters() {
        synchronized (offered) {
            return offered.stream().map(Entry::chapter)
                    .sorted(Comparator.comparingInt(BookChapter::order).thenComparing(BookChapter::id)).toList();
        }
    }

    public static int forgetFrom(ClassLoader loader) {
        synchronized (offered) {
            int before = offered.size();
            offered.removeIf(each -> (each.owner() != null && each.owner().getClass().getClassLoader() == loader)
                    || PluginCode.isFrom(each.chapter().pages(), loader));
            return before - offered.size();
        }
    }

    public static void clear() {
        synchronized (offered) {
            offered.clear();
        }
    }

    /** Set by Core from its settings. */
    public static void active(boolean on) {
        active = on;
    }

    /** Whether new players get the book, so a plugin leaves its first-join lines out of the chat. */
    public static boolean takesOver() {
        return active;
    }

    /**
     * The pages: a contents page greeting the player, with a link to where each chapter starts, then the chapters. Empty
     * chapters are left out; past {@link #MOST_PAGES} the rest is cut.
     */
    public static List<String> compose(String server, String player, List<Written> chapters) {
        List<Written> kept = chapters.stream().filter(chapter -> !chapter.pages().isEmpty()).toList();
        StringBuilder contents = new StringBuilder("<dark_aqua><bold>" + Text.literal(server) + "</bold>\n")
                .append("<black>Welcome, ").append(Text.literal(player)).append("!\n\n")
                .append("<dark_gray>What is in here:\n");
        List<String> rest = new ArrayList<>();
        for (Written chapter : kept) {
            int startsOn = rest.size() + 2;
            contents.append("<click:change_page:").append(startsOn).append("><dark_aqua>» ")
                    .append(Text.literal(chapter.title())).append("</dark_aqua></click><dark_gray> ").append(startsOn)
                    .append("\n");
            rest.addAll(chapter.pages());
        }
        List<String> pages = new ArrayList<>();
        pages.add(contents.append("\n<dark_gray>Click a chapter to go there.").toString());
        pages.addAll(rest);
        return pages.size() > MOST_PAGES ? List.copyOf(pages.subList(0, MOST_PAGES)) : List.copyOf(pages);
    }

    /** Writes every chapter for this player. A chapter that fails is left out rather than the book. */
    public static List<String> pagesFor(Player player, String server) {
        List<Written> written = new ArrayList<>();
        for (BookChapter chapter : chapters()) {
            try {
                List<String> pages = chapter.pages().apply(player);
                written.add(new Written(chapter.title(), pages == null ? List.of() : pages));
            } catch (RuntimeException broken) {
                // One plugin's broken chapter must not cost the player the whole book.
            }
        }
        return compose(server, player.getName(), written);
    }

    /** The book as an item, tagged as the welcome book. */
    public static ItemStack item(Player player, String server) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        List<Component> pages = pagesFor(player, server).stream().map(MINI::deserialize).toList();
        meta.title(Component.text("Start here"));
        meta.author(Component.text(server.length() > 32 ? server.substring(0, 32) : server));
        meta.pages(pages);
        meta.getPersistentDataContainer().set(TAG, PersistentDataType.BYTE, (byte) 1);
        book.setItemMeta(meta);
        return book;
    }
}
