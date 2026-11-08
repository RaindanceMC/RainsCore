package de.raindancer.core.ui.scoreboard;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * The player's own sidebar: every section as a row they can switch off, or move up and down; the sidebar as a
 * whole on or off. The title — the server's name — is not on this page, because it is not theirs.
 *
 * <p>Whoever draws the sidebar opens this and says how it is switched and redrawn: the page knows the sections
 * (Core's) and the layout (the player's), not who paints them.
 */
public final class SidebarMenu extends Menu {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final BooleanSupplier shown;
    private final Runnable toggle;
    private final Runnable redraw;

    /**
     * @param shown  whether this player's sidebar is on at all
     * @param toggle switches it; null when the drawer offers no switch
     * @param redraw draws this player's sidebar again, after a change; null if the drawer redraws on its own
     */
    public SidebarMenu(Player viewer, Brand brand, Menu parent, BooleanSupplier shown, Runnable toggle, Runnable redraw) {
        super(viewer, brand, parent);
        this.shown = shown == null ? () -> true : shown;
        this.toggle = toggle;
        this.redraw = redraw == null ? () -> { } : redraw;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Your sidebar");
    }

    @Override
    protected void render() {
        SidebarLayout mine = SidebarPreferences.of(viewer);
        List<SidebarLayout.Known> known = SidebarSections.known();
        List<SidebarSections.Section> sections = SidebarSections.sections();
        set(MenuLayout.HEADER_SUBJECT, Icons.of(Material.OAK_SIGN, "<white>Your sidebar",
                "<gray>What is on the right of your screen,", "<gray>in the order you like.",
                "<dark_gray>The server's name at the top stays."));

        List<String> arranged = mine.arrange(known);
        java.util.Map<String, SidebarSections.Fit> fits = SidebarSections.fits(viewer.getUniqueId(), mine, Sidebar.MAX_LINES);
        for (String owner : arranged) {
            SidebarSections.Section section = sections.stream().filter(each -> each.owner().equals(owner)).findFirst().orElse(null);
            if (section == null) {
                continue;
            }
            boolean on = mine.shows(owner);
            int place = arranged.indexOf(owner);
            List<String> lore = new ArrayList<>();
            lore.add(switch (fits.getOrDefault(owner, on ? SidebarSections.Fit.SHOWN : SidebarSections.Fit.HIDDEN)) {
                case SHOWN -> "<green>Shown";
                case SHORTENED -> "<yellow>Shown, made smaller to fit";
                case NO_ROOM -> "<red>Does not fit <gray>— move it up, or hide another";
                case EMPTY -> "<green>Shown <dark_gray>(nothing to say right now)";
                case HIDDEN -> "<gray>Hidden";
            });
            SidebarSections.Size size = section.size();
            if (size != null) {
                lore.add("<gray>Showing <white>" + size.clamp(mine.sizeOf(owner, size.fallback())) + "</white> "
                        + MINI.escapeTags(size.unit()) + " <dark_gray>(" + size.min() + "–" + Math.min(9, size.max()) + ")");
            }
            lore.add("");
            lore.add("<yellow>Click<gray> to " + (on ? "hide" : "show") + " it");
            if (place > 0) {
                lore.add("<yellow>Shift click<gray> to move it up");
            }
            if (place < arranged.size() - 1) {
                lore.add("<yellow>Right click<gray> to move it down");
            }
            if (size != null) {
                lore.add("<yellow>1</yellow><gray>–</gray><yellow>" + Math.min(9, size.max())
                        + "</yellow><gray> while pointing at it: how many");
            }
            // Left to right, then the next row, in the sidebar's order: seven to a row, twenty-one at most.
            if (place >= 21) {
                break;
            }
            cell(MenuLayout.WHO + place / 7, 1 + place % 7, Icons.of(on ? Material.LIME_DYE : Material.GRAY_DYE,
                    (on ? "<white>" : "<gray>") + (place + 1) + ". " + MINI.escapeTags(section.title()), lore), click -> {
                SidebarLayout now = SidebarPreferences.of(viewer);
                SidebarLayout next;
                if (click.getClick() == org.bukkit.event.inventory.ClickType.NUMBER_KEY && size != null) {
                    next = now.sized(owner, size.clamp(click.getHotbarButton() + 1));
                } else if (click.isShiftClick()) {
                    next = now.movedUp(owner, SidebarSections.known());
                } else if (click.isRightClick()) {
                    next = now.movedDown(owner, SidebarSections.known());
                } else {
                    next = now.toggled(owner);
                }
                SidebarPreferences.save(viewer, next);
                redraw.run();
                refresh();
            });
        }
        if (arranged.isEmpty()) {
            band(MenuLayout.RULES, 4, Icons.of(Material.COBWEB, "<gray>Nothing on the sidebar yet",
                    "<dark_gray>No feature here adds a section."));
        }

        if (toggle != null) {
            boolean on = shown.getAsBoolean();
            toolbar(3, Icons.of(on ? Material.OAK_SIGN : Material.BIRCH_SIGN,
                    on ? "<white>Sidebar: <green>shown" : "<white>Sidebar: <gray>hidden",
                    "<gray>The whole thing, on the right of the screen.", "",
                    "<yellow>Click<gray> to " + (on ? "hide" : "show") + " it"), click -> {
                toggle.run();
                refresh();
            });
        }
        toolbar(5, !mine.isDefault(), Icons.of(Material.RECOVERY_COMPASS, "<white>Back to the server's order",
                "<gray>Everything shown, in the order it came.", "", "<yellow>Click<gray> to reset"),
                "Already the server's order", click -> {
            SidebarPreferences.save(viewer, SidebarLayout.DEFAULT);
            redraw.run();
            refresh();
        });
    }

    @Override
    protected List<String> helpLines() {
        return List.of("Each row is one section of your sidebar,",
                "top to bottom as it is shown.",
                "Click to hide or show it; shift click moves",
                "it up, right click moves it down. A number",
                "key while pointing at one says how many it lists.",
                "",
                "The server's name at the top stays.");
    }
}
