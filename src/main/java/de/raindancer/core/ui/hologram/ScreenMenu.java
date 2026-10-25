package de.raindancer.core.ui.hologram;

import de.raindancer.core.RainsCore;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.PaginatedMenu;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Every kind of screen this player may put up; a click puts one up over their head. */
final class ScreenMenu extends PaginatedMenu<ScreenKind> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Screens screens;
    private final boolean staff;

    ScreenMenu(Player viewer, Screens screens, boolean staff) {
        super(viewer, RainsCore.get().chatFor("Core").brand(), null);
        this.screens = screens;
        this.staff = staff;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Screens");
    }

    @Override
    public String breadcrumb() {
        return "Screens";
    }

    @Override
    protected List<ScreenKind> entries() {
        return screens.kinds().stream().filter(kind -> staff || screens.forPlayers(kind.id())).toList();
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>No screens to put up");
    }

    @Override
    protected ItemStack icon(ScreenKind kind) {
        List<String> lore = new ArrayList<>();
        if (!kind.description().isBlank()) {
            lore.add("<gray>" + MINI.escapeTags(kind.description()));
        }
        lore.add(kind.personal() ? "<aqua>Everybody near it sees their own" : "<dark_gray>The same for everybody");
        lore.add("");
        lore.add("<yellow>Click to put it up over your head");
        return Icons.of(kind.icon(), "<white>" + MINI.escapeTags(kind.title()), lore);
    }

    @Override
    protected void onClick(ScreenKind kind, InventoryClickEvent event) {
        viewer.closeInventory();
        ScreenCommand.place(viewer, screens, kind, staff);
    }

    @Override
    protected void decorate() {
        super.decorate();
        List<String> lore = new ArrayList<>(List.of(
                "<gray>/screen up, /screen down nudge the nearest",
                "<gray>/screen remove takes it down",
                "<gray>/screen list shows yours"));
        if (!staff) {
            lore.addFirst("<gray>Up now: <white>" + screens.countOf(viewer.getUniqueId()) + "/" + screens.limit());
            lore.add("<dark_gray>Only where you may build — your base.");
        }
        toolbar(4, Icons.of(Material.BOOK, staff ? "<white>Screens" : "<white>Your screens", lore), click -> { });
    }
}
