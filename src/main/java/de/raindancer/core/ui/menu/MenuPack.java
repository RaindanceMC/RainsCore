package de.raindancer.core.ui.menu;

import de.raindancer.core.content.pack.JarPack;
import de.raindancer.core.content.pack.ResourcePacks;
import de.raindancer.core.platform.log.Log;
import org.bukkit.plugin.Plugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Core's own share of the server pack: the menu paintings ({@link Backdrop}) and the invisible filler. Painted in
 * {@code art/menus}; {@code files.txt} is the index of what ships.
 */
public final class MenuPack {

    static final String ROOT = "/de/raindancer/core/pack/";
    private static final String OWNER = "RainsCore";
    private static final String NAME = "menus";

    private MenuPack() {
    }

    public static List<String> files() {
        try (InputStream in = MenuPack.class.getResourceAsStream(ROOT + "files.txt")) {
            if (in == null) {
                return List.of();
            }
            return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines()
                    .map(String::trim).filter(line -> !line.isEmpty()).toList();
        } catch (IOException unreadable) {
            return List.of();
        }
    }

    /**
     * Offers the paintings and lets every menu ask who sees them: wearing the pack, the server's setting on, the
     * player's own switch on.
     */
    public static void offer(Plugin plugin, ResourcePacks packs, java.util.function.BooleanSupplier serverOn) {
        try {
            JarPack.offer(plugin, packs, MenuPack.class, ROOT, files(), OWNER, NAME, "Painted menu windows");
            MenuStyle.install(packs::isWearing, serverOn, id -> {
                org.bukkit.entity.Player player = plugin.getServer().getPlayer(id);
                return player == null || MenuStyle.PAINTED.isOn(player);
            });
        } catch (IOException failure) {
            Log.of("gui").warn("The menu paintings could not be written ({}); menus keep the classic look.",
                    failure.getMessage());
        }
    }

    /**
     * The button on a player's own profile that flips {@link MenuStyle#PAINTED}. Nothing on somebody else's profile,
     * and nothing while the server has the paintings off.
     */
    public static de.raindancer.core.ui.profile.ProfileExtension profileSwitch(
            java.util.function.BooleanSupplier serverOn) {
        return (viewer, subject, parent) -> {
            if (!serverOn.getAsBoolean() || !viewer.getUniqueId().equals(subject.getUniqueId())) {
                return null;
            }
            boolean on = MenuStyle.PAINTED.isOn(viewer);
            org.bukkit.inventory.ItemStack icon = Icons.of(org.bukkit.Material.PAINTING,
                    (on ? "<green>" : "<gray>") + "Painted menus: " + (on ? "on" : "off"),
                    "<gray>Menus drawn as wood, brass and parchment",
                    "<gray>while you wear the server pack.",
                    "",
                    "<yellow>Click<gray> to switch them " + (on ? "off" : "on") + " for you");
            return new de.raindancer.core.ui.profile.ProfileButton(icon, click -> {
                MenuStyle.PAINTED.toggle(viewer);
                // A window cannot be retitled, and the painting is its title: open the page anew.
                parent.reopen();
            });
        };
    }

    public static void withdraw(ResourcePacks packs) {
        MenuStyle.uninstall();
        if (packs != null) {
            packs.withdraw(OWNER.toLowerCase(java.util.Locale.ROOT) + ":" + NAME);
        }
    }
}
