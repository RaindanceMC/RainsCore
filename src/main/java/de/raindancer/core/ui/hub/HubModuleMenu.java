package de.raindancer.core.ui.hub;

import de.raindancer.core.RainsCore;
import de.raindancer.core.platform.command.CommandNote;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.ui.text.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** One door opened: what the module is, and every command of it the viewer may use. */
public final class HubModuleMenu extends HubPage {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final HubEntry entry;

    public HubModuleMenu(Player viewer, Brand brand, Menu parent, HubEntry entry) {
        super(viewer, brand, parent);
        this.entry = entry;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<gradient:" + entry.accent() + ":" + HubLook.lighter(entry.accent(), 0.55) + ">"
                + Text.literal(entry.name()) + "</gradient>");
    }

    @Override
    public String breadcrumb() {
        return entry.name();
    }

    @Override
    void header() {
        set(MenuLayout.HEADER_SUBJECT, HubMenu.door(entry, Hub.notesFor(entry, RainsCore.get().commands(), viewer()::hasPermission).size(), false));
    }

    @Override
    List<Tile> tiles() {
        List<Tile> tiles = new ArrayList<>();
        for (CommandNote note : Hub.notesFor(entry, RainsCore.get().commands(), viewer()::hasPermission)) {
            tiles.add(new Tile(commandIcon(note), event -> use(note)));
        }
        return tiles;
    }

    private ItemStack commandIcon(CommandNote note) {
        List<String> lore = new ArrayList<>();
        for (String line : HubLook.wrap(note.sentence(), 34)) {
            lore.add("<gray>" + Text.literal(line));
        }
        if (!note.options().isEmpty()) {
            lore.add("");
            note.options().forEach(option -> lore.add("<dark_gray>/" + Text.literal(option)));
        }
        lore.add("");
        lore.add("<dark_gray>» <white>" + (runsDirectly(note) ? "Click to run" : "Click to type it"));
        return tidy(Icons.of(HubLook.forCommand(note.command(), entry.icon()),
                "<" + entry.accent() + ">" + Text.literal(note.slashed()), lore), false);
    }

    /** A command with no arguments can simply be run; one that takes some needs the player to finish it. */
    static boolean runsDirectly(CommandNote note) {
        return note.options().isEmpty() && note.command().indexOf(' ') < 0;
    }

    private void use(CommandNote note) {
        viewer().closeInventory();
        if (runsDirectly(note)) {
            de.raindancer.core.platform.util.TypedCommands.run(viewer(), note.command());
            return;
        }
        viewer().sendMessage(Component.text("» Click to type ", net.kyori.adventure.text.format.NamedTextColor.GRAY)
                .append(Component.text(note.slashed(), net.kyori.adventure.text.format.NamedTextColor.WHITE)
                        .clickEvent(ClickEvent.suggestCommand(note.slashed() + " "))));
    }

    @Override
    ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>Nothing to type here",
                "<dark_gray>" + Text.literal(entry.name()) + " works without commands.");
    }
}
