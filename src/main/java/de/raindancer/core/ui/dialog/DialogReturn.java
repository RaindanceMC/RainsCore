package de.raindancer.core.ui.dialog;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.text.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;

/**
 * The parent a chest page gets when a dialog opened it: never drawn, its {@link #open()} puts the dialog back. So a
 * long list that stays a paged chest (a record, a report queue) still has Back, and Back lands where the player was.
 */
public final class DialogReturn extends Menu {

    private final String name;
    private final Runnable reopen;

    /**
     * @param name   what the dialog is called, for the Back button and the chest's title — text, not markup
     * @param reopen shows the dialog again, built fresh
     */
    public DialogReturn(Player viewer, Brand brand, String name, Runnable reopen) {
        super(viewer, brand, null);
        this.name = name == null ? "" : name;
        this.reopen = reopen;
    }

    @Override
    public void open() {
        reopen.run();
    }

    /** A module's page that changed something asks its parent to redraw: that is the dialog, shown afresh. */
    @Override
    public void refresh() {
        reopen.run();
    }

    @Override
    public void reopen() {
        reopen.run();
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<white>" + Text.literal(name));
    }

    @Override
    protected void render() {
        // Never drawn: opening it shows the dialog.
    }

    @Override
    public String breadcrumb() {
        return name;
    }
}
