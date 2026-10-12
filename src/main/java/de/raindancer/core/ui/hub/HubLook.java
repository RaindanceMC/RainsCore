package de.raindancer.core.ui.hub;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** What a door looks like: an icon and an accent colour, plus the small text helpers the menus share. */
public record HubLook(Material icon, String accent) {

    private static final Map<String, HubLook> KNOWN = Map.ofEntries(
            look("anticheat", Material.SHIELD, "#FF5C5C"),
            look("chained", Material.LEAD, "#9AA5B1"),
            look("chat", Material.WRITABLE_BOOK, "#5CC8FF"),
            look("claims", Material.GOLDEN_SHOVEL, "#FFC04D"),
            look("cosmetics", Material.AMETHYST_SHARD, "#D68CFF"),
            look("economy", Material.GOLD_INGOT, "#F5D142"),
            look("essentials", Material.COMPASS, "#6EE7B7"),
            look("farmworld", Material.WHEAT, "#9BE564"),
            look("homes", Material.RED_BED, "#FF7B9C"),
            look("hungergames", Material.BOW, "#E8A33D"),
            look("invsnap", Material.ENDER_CHEST, "#7C6CFF"),
            look("jobs", Material.IRON_PICKAXE, "#C9A27E"),
            look("mannequin", Material.ARMOR_STAND, "#E0B0FF"),
            look("moderation", Material.IRON_SWORD, "#FF6B6B"),
            look("names", Material.NAME_TAG, "#FFB3DE"),
            look("pack", Material.PAINTING, "#7DD3FC"),
            look("performance", Material.CLOCK, "#4ADE80"),
            look("playerutils", Material.PLAYER_HEAD, "#67E8F9"),
            look("roles", Material.GOLDEN_HELMET, "#FBBF24"),
            look("rtp", Material.ENDER_PEARL, "#34D399"),
            look("sends", Material.HOPPER, "#F59E0B"),
            look("speedrun", Material.CLOCK, "#FB7185"),
            look("tpa", Material.ENDER_EYE, "#A78BFA"),
            look("veintoggle", Material.DIAMOND_PICKAXE, "#22D3EE"),
            look("wallsroads", Material.STONE_BRICKS, "#A8A29E"),
            look("warp", Material.END_PORTAL_FRAME, "#C084FC"),
            look("worldgate", Material.OBSIDIAN, "#8B5CF6"),
            look("worldutils", Material.GRASS_BLOCK, "#86EFAC"),
            look("xaeromap", Material.FILLED_MAP, "#FCD34D"),
            look("xpbottle", Material.EXPERIENCE_BOTTLE, "#A3E635"));

    /** Substrings of a command name and what they look like; the first that fits wins, so order matters. */
    private static final Map<String, Material> COMMAND_HINTS = java.util.stream.Stream.of(new Object[][]{
            {"home", Material.RED_BED}, {"warp", Material.END_PORTAL_FRAME}, {"tpa", Material.ENDER_EYE},
            {"tp", Material.ENDER_PEARL}, {"spawn", Material.GRASS_BLOCK}, {"back", Material.RECOVERY_COMPASS},
            {"pay", Material.GOLD_NUGGET}, {"bal", Material.GOLD_INGOT}, {"bank", Material.GOLD_BLOCK},
            {"shop", Material.EMERALD}, {"sell", Material.EMERALD}, {"auction", Material.CLOCK},
            {"loan", Material.PAPER}, {"bill", Material.MAP}, {"daily", Material.SUNFLOWER},
            {"casino", Material.DIAMOND}, {"slots", Material.DIAMOND}, {"dice", Material.DIAMOND},
            {"coinflip", Material.SUNFLOWER}, {"lottery", Material.NAME_TAG}, {"baltop", Material.GOLDEN_HELMET},
            {"claim", Material.GOLDEN_SHOVEL}, {"trust", Material.LIME_DYE}, {"msg", Material.WRITABLE_BOOK},
            {"mail", Material.WRITTEN_BOOK}, {"chat", Material.WRITABLE_BOOK}, {"rules", Material.BOOK},
            {"report", Material.RED_DYE}, {"ban", Material.BARRIER}, {"mute", Material.SKELETON_SKULL},
            {"kick", Material.IRON_BOOTS}, {"vanish", Material.GLASS}, {"invsee", Material.CHEST},
            {"settings", Material.COMPARATOR}, {"role", Material.GOLDEN_HELMET}, {"quest", Material.MAP},
            {"job", Material.IRON_PICKAXE}, {"rtp", Material.COMPASS}, {"seen", Material.SPYGLASS},
            {"nick", Material.NAME_TAG}, {"afk", Material.CLOCK}, {"admin", Material.COMMAND_BLOCK},
            {"prefix", Material.NAME_TAG}, {"changelog", Material.WRITTEN_BOOK}, {"help", Material.KNOWLEDGE_BOOK},
    }).collect(java.util.stream.Collectors.toMap(row -> (String) row[0], row -> (Material) row[1],
            (a, b) -> a, java.util.LinkedHashMap::new));

    private static final Material[] FALLBACK_ICONS = {Material.BOOK, Material.LANTERN, Material.BELL,
            Material.BEACON, Material.PRISMARINE_SHARD, Material.COPPER_INGOT};
    private static final String[] FALLBACK_ACCENTS = {"#7DD3FC", "#F9A8D4", "#FDE68A", "#A7F3D0", "#C4B5FD",
            "#FDBA74"};

    private static Map.Entry<String, HubLook> look(String id, Material icon, String accent) {
        return Map.entry(id, new HubLook(icon, accent));
    }

    /** The look of a module id, tolerant of dashes and case; an unknown id gets a stable fallback. */
    public static HubLook of(String id) {
        String key = id == null ? "" : id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        HubLook known = KNOWN.get(key);
        if (known != null) {
            return known;
        }
        int pick = Math.floorMod(key.hashCode(), FALLBACK_ICONS.length);
        return new HubLook(FALLBACK_ICONS[pick], FALLBACK_ACCENTS[pick]);
    }

    /** An icon for a command from its name, or {@code fallback} when nothing in the name suggests one. */
    public static Material forCommand(String command, Material fallback) {
        String name = command == null ? "" : command.toLowerCase(Locale.ROOT).replaceAll("^/+", "");
        for (Map.Entry<String, Material> hint : COMMAND_HINTS.entrySet()) {
            if (name.contains(hint.getKey())) {
                return hint.getValue();
            }
        }
        return fallback;
    }

    /** {@code accent} mixed toward white by {@code amount} (0 = unchanged, 1 = white). */
    public static String lighter(String accent, double amount) {
        int rgb = Integer.parseInt(accent.substring(1), 16);
        double mix = Math.max(0, Math.min(1, amount));
        int out = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            int channel = (rgb >> shift) & 0xFF;
            out = (out << 8) | (int) Math.round(channel + (255 - channel) * mix);
        }
        return String.format("#%06X", out);
    }

    /** The first {@code max} lines, the last one ending in an ellipsis when something was cut. */
    public static List<String> clip(List<String> lines, int max) {
        if (lines.size() <= max) {
            return lines;
        }
        List<String> kept = new ArrayList<>(lines.subList(0, max));
        kept.set(max - 1, kept.get(max - 1) + "…");
        return kept;
    }

    /** Greedy word wrap; a word longer than the width gets its own line instead of being cut. */
    public static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return lines;
        }
        StringBuilder line = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines;
    }
}
