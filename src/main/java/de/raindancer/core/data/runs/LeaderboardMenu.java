package de.raindancer.core.data.runs;

import de.raindancer.core.platform.util.Times;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.identity.Symbols;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.core.ui.choose.OptionChooser;
import de.raindancer.core.ui.text.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * A leaderboard as a screen, for any game that keeps a {@link RunHistory}: gold, silver and bronze at
 * the top, every run's time or score with how far it is off the record, the viewer's own runs marked
 * and their best and place in the header.
 *
 * <pre>{@code
 * new LeaderboardMenu(player, brand, null, core.runHistory("speedrun"), "speedrun/any%/solo")
 *         .switchingWithin("speedrun/")      // a button to pick another category of this game
 *         .open();
 * }</pre>
 *
 * <p>One plugin with several modes passes the mode as the start of the category ({@code "manhunt/"},
 * {@code "speedrun/"}) and gets a category picker limited to that mode. Toggling between one run per
 * player and every run is built in.
 */
public class LeaderboardMenu extends PaginatedMenu<Run> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    /** Splits shown under a run before the rest are left out. */
    private static final int SPLITS_SHOWN = 6;

    private final RunHistory history;
    private String category;
    private String switchingWithin;
    private boolean bestPerPlayer = true;
    private Function<Run, String> shown = RunText::score;
    private boolean scoresAreTimes = true;
    // The board as last listed, so each icon does not sort the whole history again.
    private List<Run> board = List.of();

    public LeaderboardMenu(Player viewer, Brand brand, Menu parent, RunHistory history, String category) {
        super(viewer, brand, parent);
        this.history = Objects.requireNonNull(history, "history");
        this.category = Objects.requireNonNull(category, "category");
    }

    /** Adds a button to switch to another category starting with this — a mode's own variants. */
    public LeaderboardMenu switchingWithin(String prefix) {
        this.switchingWithin = prefix;
        return this;
    }

    /** Shows every run instead of each player's best. */
    public LeaderboardMenu everyRun() {
        this.bestPerPlayer = false;
        return this;
    }

    /** How a score reads, when it is not a time for lower-wins runs and a number otherwise. */
    public LeaderboardMenu showing(Function<Run, String> howAScoreReads) {
        this.shown = howAScoreReads == null ? RunText::score : howAScoreReads;
        this.scoresAreTimes = howAScoreReads == null;
        return this;
    }

    /** The category on screen. */
    public String category() {
        return category;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<gold>Leaderboard <dark_gray>" + Symbols.ARROW + " <gray>" + Text.literal(category));
    }

    @Override
    public String breadcrumb() {
        return "the leaderboard";
    }

    @Override
    protected List<String> helpLines() {
        return List.of("The best runs in " + category + ", best first.",
                bestPerPlayer ? "Each player's best run only." : "Every run, including repeats.",
                "Runs struck from the rankings are not shown.");
    }

    @Override
    protected List<Run> entries() {
        board = history.leaderboard(category, bestPerPlayer ? RunHistory.Board.onePerPlayer() : RunHistory.Board.everyRun());
        return board;
    }

    @Override
    protected ItemStack icon(Run run) {
        int place = board.indexOf(run) + 1;
        boolean mine = run.includes(viewer.getUniqueId());
        Material material = switch (place) {
            case 1 -> Material.GOLD_BLOCK;
            case 2 -> Material.IRON_BLOCK;
            case 3 -> Material.COPPER_BLOCK;
            default -> mine ? Material.EMERALD : Material.PAPER;
        };
        List<String> lore = new ArrayList<>();
        lore.add("<white>" + Text.literal(shown.apply(run)));
        if (place > 1 && !board.isEmpty()) {
            long behind = run.lowerWins() ? run.score() - board.getFirst().score() : board.getFirst().score() - run.score();
            lore.add("<gray>" + Text.literal(RunText.gap(behind, scoresAreTimes && run.lowerWins())) + " off the record");
        }
        lore.add("<dark_gray>" + Text.literal(Times.ago(run.startedAt().toEpochMilli())));
        Map<String, Long> splits = run.splits();
        if (!splits.isEmpty()) {
            lore.add("");
            int listed = 0;
            for (Map.Entry<String, Long> split : splits.entrySet()) {
                if (listed++ == SPLITS_SHOWN) {
                    lore.add("<dark_gray>… and " + (splits.size() - SPLITS_SHOWN) + " more");
                    break;
                }
                Optional<Duration> best = history.bestSplit(category, split.getKey());
                boolean isBest = best.isPresent() && best.get().toMillis() == split.getValue();
                lore.add("<gray>" + Text.literal(split.getKey()) + " <white>"
                        + RunText.clock(Duration.ofMillis(split.getValue())) + (isBest ? " <gold>★" : ""));
            }
        }
        if (mine) {
            lore.add("");
            lore.add("<green>This is yours.");
        }
        String colour = place == 1 ? "<gold>" : place == 2 ? "<white>" : place == 3 ? "<#c87533>" : "<gray>";
        return Icons.of(material, colour + RunText.ordinal(place) + " <white>" + Text.literal(RunText.who(run)), lore);
    }

    @Override
    protected void onClick(Run run, InventoryClickEvent event) {
        // The lore holds everything there is about a run; a click has nothing more to show.
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.CLOCK, "<gray>No runs in " + Text.literal(category) + " yet",
                "<gray>Finish one and it shows up here.");
    }

    @Override
    protected void decorate() {
        set(MenuLayout.HEADER_SUBJECT, recordIcon());
        set(MenuLayout.HEADER_RIGHT, yourBestIcon());
        toolbar(3, Icons.of(bestPerPlayer ? Material.PLAYER_HEAD : Material.BOOK,
                        bestPerPlayer ? "<white>Each player's best" : "<white>Every run",
                        "<gray>Click to show " + (bestPerPlayer ? "every run" : "each player's best only")),
                event -> {
                    bestPerPlayer = !bestPerPlayer;
                    refresh();
                });
        List<String> categories = switchingWithin == null ? List.of() : history.categories(switchingWithin);
        if (categories.size() > 1) {
            toolbar(5, Icons.of(Material.COMPASS, "<white>Category: " + Text.literal(category),
                            "<gray>Click to pick another"),
                    event -> new OptionChooser(viewer, brand(), this, "Category", categories, category, chosen -> {
                        category = chosen;
                        reopen();
                    }).open());
        }
        super.decorate();
    }

    private ItemStack recordIcon() {
        return history.record(category)
                .map(record -> Icons.of(Material.NETHER_STAR, "<gold>Record: " + Text.literal(shown.apply(record)),
                        "<gray>by <white>" + Text.literal(RunText.who(record)),
                        "<dark_gray>" + Text.literal(Times.ago(record.startedAt().toEpochMilli()))))
                .orElseGet(() -> Icons.of(Material.NETHER_STAR, "<gray>No record yet", "<gray>The first run sets it."));
    }

    private ItemStack yourBestIcon() {
        Optional<Run> best = history.personalBest(viewer.getUniqueId(), category);
        if (best.isEmpty()) {
            return Icons.of(Material.CLOCK, "<gray>No run of yours yet", "<gray>Your best and your place show here.");
        }
        String place = history.placeOf(viewer.getUniqueId(), category).map(RunText::ordinal).orElse("-");
        return Icons.of(Material.CLOCK, "<green>Your best: " + Text.literal(shown.apply(best.get())),
                "<gray>Place: <white>" + place,
                "<dark_gray>" + Text.literal(Times.ago(best.get().startedAt().toEpochMilli())));
    }
}
