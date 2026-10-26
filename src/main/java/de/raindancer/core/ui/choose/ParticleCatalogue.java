package de.raindancer.core.ui.choose;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Every particle a server knows, sorted and given a face.
 *
 * <h2>Why this is harder than the sound catalogue</h2>
 * Because a sound key says what makes it — {@code block.bell.use} is a bell — and a particle name
 * does not. A list of {@code CRIT}, {@code DUST_PLUME}, {@code SCULK_CHARGE_POP} is a vocabulary
 * test, so both the grouping and the icon have to come from what the particle is <em>for</em>.
 *
 * <h2>The trap this exists to mark</h2>
 * Some particles do nothing at all unless they are given more than a name: {@code DUST} needs a
 * colour, {@code BLOCK} and {@code ITEM} need something to be made of. A chooser that offers them
 * like any other produces a setting that silently spawns nothing, which is the worst kind of
 * setting. {@link #needsExtraData} is how a menu can say so.
 */
public final class ParticleCatalogue {

    private final Supplier<List<String>> particles;
    private volatile Map<ParticleGroup, List<String>> sorted;

    public ParticleCatalogue(Supplier<List<String>> particles) {
        this.particles = particles;
    }

    public List<String> inGroup(ParticleGroup group) {
        return sorted().getOrDefault(group, List.of());
    }

    public List<ParticleGroup> groups() {
        return ParticleGroup.ordered().stream()
                .filter(group -> !inGroup(group).isEmpty())
                .toList();
    }

    public List<String> all() {
        List<String> everything = new ArrayList<>();
        sorted().values().forEach(everything::addAll);
        everything.sort(String::compareTo);
        return everything;
    }

    /** Every particle whose name contains this; an exact match first. */
    public List<String> search(String text) {
        if (text == null || text.isBlank()) {
            return all();
        }
        String wanted = text.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        return all().stream()
                .filter(name -> name.contains(wanted))
                .sorted(Comparator.comparing((String name) ->
                        name.equals(wanted) ? 0 : 1).thenComparing(name -> name))
                .toList();
    }

    public void refresh() {
        sorted = null;
    }

    private Map<ParticleGroup, List<String>> sorted() {
        Map<ParticleGroup, List<String>> known = sorted;
        if (known != null) {
            return known;
        }
        Map<ParticleGroup, List<String>> built = new EnumMap<>(ParticleGroup.class);
        for (String particle : particles.get()) {
            built.computeIfAbsent(groupOf(particle), group -> new ArrayList<>()).add(particle);
        }
        built.values().forEach(list -> list.sort(String::compareTo));
        sorted = built;
        return built;
    }

    // ---------------------------------------------------------------------------- the rules

    /**
     * Which drawer a particle belongs in.
     *
     * <p>Order matters: the colourable ones are caught first because {@code DUST_COLOR_TRANSITION}
     * would otherwise be read as dust-in-general, and combat before magic because
     * {@code ENCHANTED_HIT} is something that happens in a fight.
     */
    public static ParticleGroup groupOf(String particle) {
        String name = ParticleGroup.normalise(particle);
        if (name.isEmpty()) {
            return ParticleGroup.OTHER;
        }
        if (de.raindancer.core.ui.effect.ParticleShows.isOwn(name)) {
            return ParticleGroup.EMOTES;
        }
        if (needsExtraData(name) && !name.startsWith("BLOCK") && !name.startsWith("ITEM")
                && !name.startsWith("FALLING_DUST")) {
            return ParticleGroup.COLOURED;
        }
        if (name.startsWith("BLOCK") || name.startsWith("ITEM") || name.equals("FALLING_DUST")) {
            return ParticleGroup.BLOCKS;
        }
        if (contains(name, "FLAME", "LAVA", "SMOKE", "FIRE", "ASH_", "CAMPFIRE", "SMALL_FLAME",
                "SOUL", "SINGE", "EMBER")) {
            // SOUL is here rather than in magic because every particle with it in the name is a
            // soul *fire* one.
            return ParticleGroup.FIRE;
        }
        if (contains(name, "WATER", "BUBBLE", "SPLASH", "RAIN", "DRIP", "FISHING", "NAUTILUS",
                "UNDERWATER", "CURRENT")) {
            return name.equals("RAIN") ? ParticleGroup.WEATHER : ParticleGroup.WATER;
        }
        if (contains(name, "CRIT", "DAMAGE", "SWEEP", "EXPLOSION", "FLASH", "ANGRY_", "SONIC")) {
            return name.startsWith("ANGRY") ? ParticleGroup.EMOTES : ParticleGroup.COMBAT;
        }
        if (contains(name, "HEART", "VILLAGER", "COMPOSTER", "NOTE", "SPIT", "MOOD")) {
            return ParticleGroup.EMOTES;
        }
        if (contains(name, "ENCHANT", "PORTAL", "END_ROD", "WITCH", "DRAGON", "SPELL", "TOTEM",
                "REVERSE", "GLOW", "SHRIEK", "WARPED", "RAID", "OMINOUS", "TRIAL")) {
            return ParticleGroup.MAGIC;
        }
        if (contains(name, "CLOUD", "SNOW", "ASH", "SPORE", "POLLEN", "MYCELIUM", "WIND", "GUST")) {
            return ParticleGroup.WEATHER;
        }
        return ParticleGroup.OTHER;
    }

    private static boolean contains(String name, String... needles) {
        for (String needle : needles) {
            if (name.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The particles that spawn nothing unless they are given a colour or a material.
     *
     * <p>Worth marking rather than hiding: an owner who picks one and sees nothing has no way of
     * telling that from a broken plugin.
     */
    private static final Set<String> NEEDS_DATA = Set.of(
            "DUST", "DUST_COLOR_TRANSITION", "DUST_PILLAR", "BLOCK", "BLOCK_MARKER",
            "BLOCK_CRUMBLE", "FALLING_DUST", "ITEM", "ENTITY_EFFECT", "TRAIL", "SHRIEK",
            "SCULK_CHARGE", "VIBRATION", "TINTED_LEAVES");

    public static boolean needsExtraData(String particle) {
        return NEEDS_DATA.contains(ParticleGroup.normalise(particle));
    }

    // ---------------------------------------------------------------------------- the faces

    /**
     * Something to draw a particle as.
     *
     * <p>By name where the name says something — flame is a fire charge, lava is a lava bucket — and
     * by its group where it does not. A grid of identical grey panes with words on is the failure
     * this avoids; falling back to the group's own icon at least sorts the page visually.
     */
    public static String iconFor(String particle) {
        String name = ParticleGroup.normalise(particle);
        if (name.isEmpty()) {
            return ParticleGroup.OTHER.icon();
        }
        return switch (name) {
            case "FLAME", "SMALL_FLAME" -> "FIRE_CHARGE";
            case "SOUL_FIRE_FLAME", "SOUL" -> "SOUL_TORCH";
            case "LAVA" -> "LAVA_BUCKET";
            case "SMOKE", "LARGE_SMOKE" -> "COAL";
            case "CAMPFIRE_COSY_SMOKE", "CAMPFIRE_SIGNAL_SMOKE" -> "CAMPFIRE";
            case "HEART" -> "POPPY";
            case "ANGRY_VILLAGER" -> "IRON_SWORD";
            case "HAPPY_VILLAGER" -> "EMERALD";
            case "NOTE" -> "NOTE_BLOCK";
            case "PORTAL", "REVERSE_PORTAL" -> "OBSIDIAN";
            case "ENCHANT", "ENCHANTED_HIT" -> "ENCHANTING_TABLE";
            case "END_ROD" -> "END_ROD";
            case "WITCH" -> "POTION";
            case "DRAGON_BREATH" -> "DRAGON_BREATH";
            case "TOTEM_OF_UNDYING" -> "TOTEM_OF_UNDYING";
            case "CRIT" -> "IRON_SWORD";
            case "EXPLOSION", "EXPLOSION_EMITTER" -> "TNT";
            case "SNOWFLAKE" -> "SNOWBALL";
            case "CLOUD" -> "WHITE_WOOL";
            case "BUBBLE", "BUBBLE_POP", "BUBBLE_COLUMN_UP" -> "WATER_BUCKET";
            case "SPLASH", "RAIN" -> "WATER_BUCKET";
            case "FISHING" -> "FISHING_ROD";
            case "DUST", "DUST_COLOR_TRANSITION" -> "RED_DYE";
            case "BLOCK", "BLOCK_MARKER", "FALLING_DUST" -> "GRASS_BLOCK";
            case "ITEM" -> "ITEM_FRAME";
            case "COFFEE" -> "COCOA_BEANS";
            default -> groupOf(name).icon();
        };
    }

    /** A particle name, written for a menu. */
    public static String readable(String particle) {
        return Catalogue.readable(ParticleGroup.normalise(particle));
    }
}
