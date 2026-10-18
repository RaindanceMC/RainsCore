package de.raindancer.core.ui.choose;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.core.ui.text.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Any number out of a named list — a button per entry, each switched on its own, the page staying open so several
 * can be flipped in a row. {@link OptionChooser}'s sibling for when the answer is a set rather than one value.
 */
public final class ToggleChooser extends PaginatedMenu<String> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final String label;
    private final List<String> options;
    private final Supplier<List<String>> listed;
    private final boolean listedMeansOff;
    private final Consumer<String> onToggled;

    /**
     * @param listed         what is in the list now — asked again after every click
     * @param listedMeansOff whether the list is of what is switched off, so a listed entry shows as off
     * @param onToggled      handed the entry clicked; it puts it in or takes it out
     */
    public ToggleChooser(Player viewer, Brand brand, Menu parent, String label, List<String> options,
                         Supplier<List<String>> listed, boolean listedMeansOff, Consumer<String> onToggled) {
        super(viewer, brand, parent);
        this.label = label == null || label.isBlank() ? "Choose" : label;
        this.options = options == null ? List.of() : List.copyOf(options);
        this.listed = listed;
        this.listedMeansOff = listedMeansOff;
        this.onToggled = onToggled;
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
        return List.of("Click one to switch it on or off.", "Green is on.");
    }

    @Override
    protected List<String> entries() {
        return options;
    }

    @Override
    protected ItemStack icon(String option) {
        boolean on = listed.get().contains(option) != listedMeansOff;
        return Icons.of(on ? Material.LIME_DYE : Material.GRAY_DYE,
                (on ? "<green>" : "<gray>") + Text.literal(readable(option)),
                List.of(on ? "<green>On" : "<red>Off", "<yellow>▶ Click to switch " + (on ? "off" : "on")));
    }

    @Override
    protected void onClick(String option, InventoryClickEvent event) {
        if (onToggled != null) {
            onToggled.accept(option);
        }
        refresh();
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>Nothing to choose from",
                "<dark_gray>This setting has no listed entries.");
    }

    /** {@code rainsenchants:double_jump} → {@code Double jump}: the namespace is for files, not for reading. */
    public static String readable(String option) {
        String bare = option == null ? "" : option.substring(option.indexOf(':') + 1);
        return OptionChooser.readable(bare);
    }
}
