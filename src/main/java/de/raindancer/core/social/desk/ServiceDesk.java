package de.raindancer.core.social.desk;

import org.bukkit.Material;

import java.util.Locale;
import java.util.Optional;

/** A kind of attendant several plugins sell through: whatever each offers there is listed at every one. */
public enum ServiceDesk {

    INSURANCE("Insurance agent", "Insurance", Material.TOTEM_OF_UNDYING),
    BANK("Banker", "Bank", Material.GOLD_INGOT),
    GUILD_BANK("Guild banker", "Guild bank", Material.GOLD_BLOCK),
    GUILD_VAULT("Vault keeper", "Guild vault", Material.ENDER_CHEST),
    GUILD_ENCHANTS("Guild enchanter", "Guild enchantments", Material.ENCHANTING_TABLE),
    /** Whoever answers /help, standing by the spawn — see {@code de.raindancer.core.ui.help.HelpGuide}. */
    GUIDE("Guide", "Help", Material.ENCHANTED_BOOK);

    private final String title;
    private final String menuTitle;
    private final Material icon;

    ServiceDesk(String title, String menuTitle, Material icon) {
        this.title = title;
        this.menuTitle = menuTitle;
        this.icon = icon;
    }

    /** What the attendant is called over their head. */
    public String title() {
        return title;
    }

    public String menuTitle() {
        return menuTitle;
    }

    public Material icon() {
        return icon;
    }

    public static Optional<ServiceDesk> read(String written) {
        if (written == null) {
            return Optional.empty();
        }
        String key = written.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (ServiceDesk desk : values()) {
            if (desk.name().equals(key)) {
                return Optional.of(desk);
            }
        }
        return Optional.empty();
    }
}
