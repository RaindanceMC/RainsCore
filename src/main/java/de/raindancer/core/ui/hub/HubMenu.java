package de.raindancer.core.ui.hub;

import de.raindancer.core.RainsCore;
import de.raindancer.core.platform.command.CommandDirectory;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.chat.Style;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.ui.profile.ProfileMenu;
import de.raindancer.core.ui.text.Text;
import de.raindancer.core.world.time.Times;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** {@code /hub}: the server's front door — a banner, a door per installed module, and the shortcuts. */
public final class HubMenu extends HubPage {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final int WRAP = 34;

    public HubMenu(Player viewer, Brand brand) {
        super(viewer, brand, null);
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<gradient:" + Style.brandFrom() + ":" + Style.brandTo() + ">Hub</gradient>");
    }

    @Override
    public String breadcrumb() {
        return "the hub";
    }

    @Override
    void header() {
        set(MenuLayout.HEADER_SUBJECT, banner());
        if (exists("settings")) {
            set(MenuLayout.HEADER_LEFT, Icons.of(Material.COMPARATOR,
                    "<gradient:#7DD3FC:#A5B4FC>Settings</gradient>",
                    "<gray>Everything you can tune,", "<gray>for you and for the server.", "",
                    "<dark_gray>» <white>Click to open"), event -> run("settings"));
        }
        if (exists("commands")) {
            set(MenuLayout.HEADER_RIGHT, Icons.of(Material.KNOWLEDGE_BOOK,
                    "<gradient:#FDE68A:#FDBA74>All commands</gradient>",
                    "<gray>Every command you may use,", "<gray>in one book.", "",
                    "<dark_gray>» <white>Click to open"), event -> run("commands"));
        }
        if (exists("changelog")) {
            toolbar(3, Icons.of(Material.WRITTEN_BOOK, "<gradient:#86EFAC:#6EE7B7>What's new</gradient>",
                    "<gray>What changed on the server lately.", "", "<dark_gray>» <white>Click to open"),
                    event -> run("changelog"));
        }
        toolbar(5, Icons.head(viewer(), "<gradient:#F9A8D4:#FBCFE8>Your profile</gradient>",
                "<gray>How the server sees you.", "", "<dark_gray>» <white>Click to open"),
                event -> ProfileMenu.open(viewer(), brand(), viewer().getUniqueId()));
    }

    private ItemStack banner() {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Welcome, <white>" + Text.literal(viewer().getName()) + "</white>.");
        lore.add("");
        lore.add("<dark_gray>▪ <gray>Online now: <white>" + Bukkit.getOnlinePlayers().size());
        if (RainsCore.isAvailable() && RainsCore.get().playtime().isKnown(viewer().getUniqueId())) {
            lore.add("<dark_gray>▪ <gray>You have played: <white>"
                    + Times.describe(RainsCore.get().playtime().played(viewer().getUniqueId())));
        }
        lore.add("<dark_gray>▪ <gray>Features here: <white>" + tiles().size());
        return tidy(Icons.of(Material.NETHER_STAR, "<bold><gradient:" + Style.brandFrom() + ":" + Style.brandTo() + ">"
                + Text.literal(serverName()) + "</gradient></bold>", lore), true);
    }

    static String serverName() {
        return RainsCore.isAvailable() ? RainsCore.get().serverName()
                : de.raindancer.core.ui.text.ServerName.resolve("", "", Bukkit.motd());
    }

    @Override
    List<Tile> tiles() {
        CommandDirectory directory = RainsCore.get().commands();
        List<Tile> tiles = new ArrayList<>();
        for (HubEntry entry : RainsCore.get().hub().visibleTo(viewer()::hasPermission, directory)) {
            int commands = Hub.notesFor(entry, directory, viewer()::hasPermission).size();
            tiles.add(new Tile(door(entry, commands, true),
                    event -> new HubModuleMenu(viewer(), brand(), this, entry).open()));
        }
        return tiles;
    }

    static ItemStack door(HubEntry entry, int commands, boolean clickable) {
        List<String> lore = new ArrayList<>();
        for (String line : HubLook.clip(HubLook.wrap(entry.description(), WRAP), 3)) {
            lore.add("<gray>" + Text.literal(line));
        }
        if (!lore.isEmpty()) {
            lore.add("");
        }
        if (commands > 0) {
            lore.add("<dark_gray>▪ <gray>" + commands + (commands == 1 ? " command" : " commands"));
        }
        if (!entry.version().isEmpty()) {
            lore.add("<dark_gray>▪ <gray>Version <white>" + Text.literal(entry.version()));
        }
        if (clickable) {
            lore.add("");
            lore.add("<dark_gray>» <white>Click to open");
        }
        return tidy(Icons.of(entry.icon(), "<bold><gradient:" + entry.accent() + ":" + HubLook.lighter(entry.accent(), 0.55)
                + ">" + Text.literal(entry.name()) + "</gradient></bold>", lore), !clickable);
    }

    @Override
    ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>Nothing installed yet");
    }

    private void run(String command) {
        viewer().closeInventory();
        viewer().performCommand(command);
    }
}
