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

    /** Offers the paintings and lets every menu ask the packs who is wearing them. */
    public static void offer(Plugin plugin, ResourcePacks packs) {
        try {
            JarPack.offer(plugin, packs, MenuPack.class, ROOT, files(), OWNER, NAME, "Painted menu windows");
            MenuStyle.install(packs::isWearing);
        } catch (IOException failure) {
            Log.of("gui").warn("The menu paintings could not be written ({}); menus keep the classic look.",
                    failure.getMessage());
        }
    }

    public static void withdraw(ResourcePacks packs) {
        MenuStyle.uninstall();
        if (packs != null) {
            packs.withdraw(OWNER.toLowerCase(java.util.Locale.ROOT) + ":" + NAME);
        }
    }
}
