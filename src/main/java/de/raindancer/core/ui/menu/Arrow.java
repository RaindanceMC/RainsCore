package de.raindancer.core.ui.menu;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;

import java.util.Locale;
import java.util.Optional;

/**
 * The arrows of every menu: Back, and left, right, up and down for pages and scrolling. A viewer wearing the pack
 * sees Core's painted icon ({@link #model()}, art/menus/arrows.py); anyone else the vanilla arrow underneath, since
 * an item model the client does not have draws as the missing-texture cube. Build them with {@link Icons#arrow};
 * {@link Menu} swaps the icon in per viewer.
 */
public enum Arrow {
    BACK(Material.ARROW),
    LEFT(Material.SPECTRAL_ARROW),
    RIGHT(Material.SPECTRAL_ARROW),
    UP(Material.ARROW),
    DOWN(Material.ARROW);

    private final Material vanilla;

    Arrow(Material vanilla) {
        this.vanilla = vanilla;
    }

    public Material vanilla() {
        return vanilla;
    }

    /** What a stack is marked with, {@code left}. */
    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }

    public NamespacedKey model() {
        return new NamespacedKey(MenuStyle.NAMESPACE, "ui/arrow_" + tag());
    }

    /** The painted icon a stack marked {@code tag} shows, if its viewer has the pack. */
    public static Optional<Arrow> shown(String tag, boolean wearing) {
        if (!wearing || tag == null) {
            return Optional.empty();
        }
        for (Arrow arrow : values()) {
            if (arrow.tag().equals(tag)) {
                return Optional.of(arrow);
            }
        }
        return Optional.empty();
    }
}
