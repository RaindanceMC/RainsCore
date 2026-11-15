package de.raindancer.core.ui.choose;

import de.raindancer.core.ui.text.Text;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * One value out of a short, named list — every answer on the page at once, with the current one
 * marked.
 *
 * <h2>Why Core owns this, and why it replaced cycling</h2>
 * A named set used to be advanced one click at a time wherever it appeared: click, read the lore to
 * see where you landed, click again. That is fine for a flag with two values and wrong for
 * everything else — overshooting the answer you wanted on a five-value setting means four more
 * clicks to come back to it, and nothing on screen ever shows you what the other answers <em>are</em>
 * until you have been through them. {@link ItemChooser} and {@link ColorChooser} already made this
 * argument for materials and colours; this is the same argument for the small lists neither of them
 * covers.
 *
 * <h2>What the caller decides</h2>
 * The options, in the order they should be read, and what to do with the one that is picked. Nothing
 * here knows what the value means — the label on each button is the option's own text, tidied for
 * reading ({@code LAST_PORTAL} shows as "Last portal"), never re-invented.
 */
public final class OptionChooser extends PaginatedMenu<String> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final String label;
    private final List<String> options;
    private final String current;
    private final Consumer<String> onChosen;

    /**
     * @param label    what is being set, for the window title — "Death policy"
     * @param options  every value it may take, exactly as the caller wants them handed back
     * @param current  the one it is now, marked on the page; may be null or unknown
     * @param onChosen handed the chosen option, and only ever the picked one — backing out calls nothing
     */
    public OptionChooser(Player viewer, Brand brand, Menu parent, String label, List<String> options,
                         String current, Consumer<String> onChosen) {
        super(viewer, brand, parent);
        this.label = label == null || label.isBlank() ? "Choose" : label;
        this.options = options == null ? List.of() : List.copyOf(options);
        this.current = current;
        this.onChosen = onChosen;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<gray>" + label);
    }

    @Override
    public String breadcrumb() {
        return label;
    }

    @Override
    protected List<String> helpLines() {
        return List.of("Click the value you want.",
                "The one it is now is the lit-up one.");
    }

    @Override
    protected List<String> entries() {
        return options;
    }

    @Override
    protected ItemStack icon(String option) {
        boolean chosen = option != null && option.equalsIgnoreCase(current);
        // Lit up rather than merely ticked in the lore: on a page of otherwise identical buttons the
        // only thing anybody scans for is which one looks different.
        return Icons.of(chosen ? Material.LIME_DYE : Material.GRAY_DYE,
                (chosen ? "<green>" : "<white>") + Text.literal(readable(option)),
                chosen ? List.of("<dark_gray>This is what it is set to.")
                        : List.of("<yellow>▶ Click to use this"));
    }

    @Override
    protected void onClick(String option, InventoryClickEvent event) {
        if (onChosen != null) {
            onChosen.accept(option);
        }
        // Back to the page that asked, the way every other chooser here ends — see
        // Menu.backToWhoeverOpenedThis for why that is a method and not a convention.
        backToWhoeverOpenedThis();
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>Nothing to choose from",
                "<dark_gray>This setting has no listed values.");
    }

    /**
     * {@code LAST_PORTAL} → {@code Last portal}. The stored value is never changed by this — only
     * what the button says, because a screen full of SHOUTING_SNAKE_CASE is a screen nobody reads.
     */
    public static String readable(String option) {
        if (option == null || option.isBlank()) {
            return "-";
        }
        String spaced = option.replace('_', ' ').replace('-', ' ').toLowerCase(Locale.ROOT).trim();
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}
