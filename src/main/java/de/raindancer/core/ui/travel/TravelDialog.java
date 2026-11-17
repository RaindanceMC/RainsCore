package de.raindancer.core.ui.travel;

import de.raindancer.core.RainsCore;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.dialog.DialogPage;
import de.raindancer.core.ui.dialog.DialogReturn;
import de.raindancer.core.ui.text.Text;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;

/** {@code /travel}: every way to get somewhere, from every plugin, in one window. */
public final class TravelDialog {

    static final String HEADING = "<gradient:#7DD3FC:#A78BFA>Travel</gradient>";

    private TravelDialog() {
    }

    public static void open(Player viewer) {
        page(viewer).show(viewer);
    }

    static DialogPage page(Player viewer) {
        List<TravelDoor> doors = TravelDoors.doors();
        DialogPage page = DialogPage.titled(HEADING);
        if (doors.isEmpty()) {
            return page.text("<gray>No way to travel is set up on this server.");
        }
        page.text("<gray>Where to?").columns(2);
        for (TravelDoor door : doors) {
            Optional<String> locked = door.lockedFor(viewer);
            page.button(locked.isEmpty(), "<white>" + Text.literal(door.title()), "<gray>" + door.description(),
                    locked.orElse(null), answers -> door.open().accept(viewer,
                            new DialogReturn(viewer, brand(), "Travel", () -> open(viewer))));
        }
        return page;
    }

    private static Brand brand() {
        return RainsCore.isAvailable() ? RainsCore.get().chatFor("SMP").brand() : new Brand("Travel");
    }
}
