package de.raindancer.core.social.desk;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.chat.Style;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** What an attendant offers: every plugin's button for this desk, in two rows. */
public final class DeskMenu extends Menu {

    private static final LogChannel log = Log.of("desk");

    private final ServiceDesk desk;

    public DeskMenu(Player viewer, ServiceDesk desk) {
        super(viewer, ServiceDesks.brand(), null);
        this.desk = desk;
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<" + Style.titleLabel() + ">" + desk.menuTitle());
    }

    @Override
    public String breadcrumb() {
        return desk.menuTitle();
    }

    @Override
    protected void render() {
        List<DeskOffer> open = ServiceDesks.offers(desk).stream().filter(DeskMenu::available).toList();
        if (open.isEmpty()) {
            band(MenuLayout.RULES, 4, Icons.of(Material.BARRIER, "<gray>Nothing on offer",
                    "<dark_gray>No plugin on this server sells anything here right now."));
            return;
        }
        int shown = 0;
        for (DeskOffer offer : open) {
            if (shown == 14) {
                log.warn("{} has more than 14 offers; the rest are not shown.", desk.title());
                break;
            }
            ItemStack icon;
            try {
                icon = offer.icon(viewer);
            } catch (RuntimeException broken) {
                log.error(broken, "An offer at the {} could not draw its button; it is left out.", desk.title());
                continue;
            }
            if (icon == null) {
                continue;
            }
            int band = shown < 7 ? MenuLayout.RULES : MenuLayout.LAND;
            band(band, shown % 7 + 1, icon, click -> {
                try {
                    offer.use(viewer, this::refresh);
                } catch (RuntimeException broken) {
                    log.error(broken, "An offer at the {} failed when clicked.", desk.title());
                }
            });
            shown++;
        }
    }

    /** Talking to an attendant: their one offer straight away, or the desk when there is more (or nothing). */
    static void talk(Player viewer, ServiceDesk desk) {
        java.util.Optional<DeskOffer> only = ServiceDesks.soleOffer(desk, offer -> available(offer) && shows(offer, viewer));
        if (only.isEmpty()) {
            new DeskMenu(viewer, desk).open();
            return;
        }
        try {
            only.get().use(viewer, () -> new DeskMenu(viewer, desk).open());
        } catch (RuntimeException broken) {
            log.error(broken, "An offer at the {} failed when talked to.", desk.title());
        }
    }

    private static boolean shows(DeskOffer offer, Player viewer) {
        try {
            return offer.icon(viewer) != null;
        } catch (RuntimeException broken) {
            return false;
        }
    }

    private static boolean available(DeskOffer offer) {
        try {
            return offer.available();
        } catch (RuntimeException broken) {
            log.error(broken, "Whether an offer is available could not be asked; it is left out.");
            return false;
        }
    }

    @Override
    protected List<String> helpLines() {
        return List.of("Everything you can arrange here.", "Click one to see or change it.");
    }
}
