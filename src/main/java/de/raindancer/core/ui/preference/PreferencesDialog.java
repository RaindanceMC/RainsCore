package de.raindancer.core.ui.preference;

import de.raindancer.core.RainsCore;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.dialog.DialogPage;
import de.raindancer.core.ui.dialog.DialogReturn;
import de.raindancer.core.ui.text.Text;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * {@code /preferences}: every setting a player makes for themselves, from every plugin, in one window. A switch flips
 * where it is; a page of its own (the sidebar) opens with Back leading here.
 */
public final class PreferencesDialog {

    static final String HEADING = "<gradient:#A7F3D0:#7DD3FC>Preferences</gradient>";

    private PreferencesDialog() {
    }

    public static void open(Player viewer) {
        page(viewer).show(viewer);
    }

    static DialogPage page(Player viewer) {
        List<Preference> offered = Preferences.forPlayer(viewer);
        DialogPage page = DialogPage.titled(HEADING);
        if (offered.isEmpty()) {
            return page.text("<gray>Nothing to set here yet.");
        }
        page.text("<gray>Only for you. Click one to change it.");
        for (Preference preference : offered) {
            String tooltip = "<gray>" + preference.description()
                    + (preference.group().isBlank() ? "" : "\n<dark_gray>" + Text.literal(preference.group()));
            if (preference.isPage()) {
                page.button("<white>" + Text.literal(preference.title()) + " ›", tooltip, answers ->
                        preference.page().accept(viewer, new DialogReturn(viewer, brand(), "Preferences",
                                () -> open(viewer))));
            } else {
                String state = preference.state(viewer);
                String colour = state.equals("Off") ? "<red>" : state.equals("On") ? "<green>" : "<yellow>";
                page.button("<white>" + Text.literal(preference.title()) + ": " + colour + Text.literal(state),
                        tooltip, answers -> {
                            preference.next(viewer);
                            open(viewer);
                        });
            }
        }
        return page;
    }

    private static Brand brand() {
        return RainsCore.isAvailable() ? RainsCore.get().chatFor("SMP").brand() : new Brand("Preferences");
    }
}
