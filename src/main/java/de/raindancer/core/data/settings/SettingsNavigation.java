package de.raindancer.core.data.settings;

import de.raindancer.core.platform.util.Closest;
import de.raindancer.core.ui.text.Text;
import de.raindancer.core.ui.identity.Symbols;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Where a settings screen goes and what it says — everything about the settings GUI that does not
 * need a server.
 *
 * <h2>Why this is separate from the menu that draws it</h2>
 * The same reason {@link de.raindancer.core.ui.menu.MenuLayout} is separate from the menu: a window
 * cannot be opened without a server, but every decision worth getting right here is arithmetic over
 * a tree. Which page shows what, what the trail says, whether a click changes a value or has to ask
 * for one — all of that is tested rather than clicked through, and the menu is left with nothing but
 * turning it into buttons.
 */
public final class SettingsNavigation {

    /** How much of the trail fits in a window title before it starts running off the edge. */
    private static final int TRAIL_LENGTH = 3;

    /** What clicking a setting did. */
    public enum Click {
        /** Flipped on the spot — a flag, and nothing else. */
        CYCLED,
        /** It needs a value typed: free text, or a number with no range to show. */
        NEEDS_TYPING,
        /** It needs one of a named set picked — an enum, out of a page listing every answer. */
        NEEDS_OPTION_CHOICE,
        /** It needs a number picked out of its own range, rather than typed into chat. */
        NEEDS_NUMBER_CHOICE,
        /** It needs a block or item picked, out of Core's own chooser rather than typed by hand. */
        NEEDS_MATERIAL_CHOICE,
        /** It needs a colour picked from Core's swatch grid, rather than cycled or typed. */
        NEEDS_COLOR_CHOICE,
        /** A list with named entries ({@link Choices}): a page with a button per entry, each switched on its own. */
        NEEDS_TOGGLES,
        /** Nothing answers to that key. */
        UNKNOWN
    }

    private final SettingsRegistry registry;

    public SettingsNavigation(SettingsRegistry registry) {
        this.registry = registry;
    }

    /**
     * The page at a path.
     *
     * @param path null for the front page; an unknown path also gives the front page, because a
     *             window that will not open is worse than one that opens somewhere sensible
     */
    public SettingsPage page(String path) {
        if (path == null || path.isBlank()) {
            return frontPage();
        }
        if (path.equals(CHANGED)) {
            return new SettingsPage(CHANGED, "Changed settings", List.of(), changed(), List.of("Changed settings"),
                    null, null);
        }
        int colon = path.indexOf(':');
        if (colon > 0) {
            return pluginPage(path.substring(0, colon), path.substring(colon + 1));
        }
        SettingsTopics topics = registry.topics();
        Optional<SettingsTopic> found = topics.at(path);
        if (found.isEmpty()) {
            return page(null);
        }
        SettingsTopic topic = found.get();
        return new SettingsPage(topic.path(), topic.title(), topic.visibleChildren(),
                topic.settings(), trailTo(topic));
    }

    // ---------------------------------------------------------------------------- by plugin

    /** The page of every setting that differs from what its plugin shipped with. */
    public static final String CHANGED = "changed";

    /** How wide a line of a tooltip may get before it is wrapped. */
    private static final int LORE_WIDTH = 38;

    /**
     * One plugin, as its door on the front page. Every record whose id is the plugin's or starts with it and a dash
     * ({@code guilds-npcs}) belongs to it, so a plugin with three records is still one door.
     */
    public record Plugin(String id, String name, Material icon, String description, List<SettingsStore<?>> stores) {

        /** Its own sections, and nobody else's. */
        public SettingsTopics tree() {
            return SettingsRegistry.topics(stores);
        }
    }

    /**
     * The plugins with anything to set, by name.
     *
     * <p>The front page used to be the tree every plugin's categories were merged into. Claims filed under
     * "management", two plugins shared "appearance", one plugin was spread over three doors — and only seven doors
     * fitted on a page. Nobody found anything without already knowing where it was filed.
     */
    public List<Plugin> plugins() {
        Map<String, List<SettingsStore<?>>> families = new java.util.LinkedHashMap<>();
        for (SettingsStore<?> store : registry.stores()) {
            families.computeIfAbsent(familyOf(store.schema().id()), family -> new ArrayList<>()).add(store);
        }
        List<Plugin> plugins = new ArrayList<>();
        families.forEach((family, stores) -> {
            SettingsTopics tree = SettingsRegistry.topics(stores);
            if (tree.visibleRoots().isEmpty()) {
                return;
            }
            Optional<SettingsTopic> own = tree.at(family);
            String name = stores.stream().map(store -> store.schema().name()).filter(named -> !named.isBlank())
                    .findFirst().orElse(own.map(SettingsTopic::title).orElse(SettingsTopics.readable(family)));
            Material icon = stores.stream().map(store -> store.schema().icon()).filter(SettingsNavigation::drawn)
                    .findFirst().orElse(own.map(SettingsTopic::icon).filter(SettingsNavigation::drawn)
                            .orElse(tree.visibleRoots().stream().map(SettingsTopic::icon)
                                    .filter(SettingsNavigation::drawn).findFirst().orElse(Material.BOOK)));
            plugins.add(new Plugin(family, name, icon, own.map(SettingsTopic::description).orElse(""),
                    List.copyOf(stores)));
        });
        plugins.sort(Comparator.comparing(plugin -> plugin.name().toLowerCase(Locale.ROOT)));
        return plugins;
    }

    /** A plugin by its id, any of its records' ids, or its name as the door says it. */
    public Optional<Plugin> plugin(String typed) {
        String wanted = squash(typed);
        if (wanted.isEmpty()) {
            return Optional.empty();
        }
        return plugins().stream().filter(plugin -> squash(plugin.id()).equals(wanted)
                || squash(familyOf(typed == null ? "" : typed.strip().toLowerCase(Locale.ROOT))).equals(squash(plugin.id()))
                || squash(plugin.name()).equals(wanted)).findFirst();
    }

    private static boolean drawn(Material icon) {
        return icon != null && icon != Material.AIR;
    }

    static String familyOf(String id) {
        int dash = id.indexOf('-');
        return dash > 0 ? id.substring(0, dash) : id;
    }

    private static String squash(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private SettingsPage frontPage() {
        List<SettingsTopic> doors = new ArrayList<>();
        for (Plugin plugin : plugins()) {
            SettingsTopic door = new SettingsTopic(plugin.id() + ":", plugin.name(), plugin.icon(),
                    plugin.description(), null, true);
            plugin.tree().visibleRoots().forEach(door::addChild);
            doors.add(door);
        }
        return new SettingsPage(null, "Settings", doors, List.of(), List.of(), null, null);
    }

    /** {@code enchants:} is a plugin's own front; {@code enchants:enchants/shards} one of its sections. */
    private SettingsPage pluginPage(String id, String topicPath) {
        Optional<Plugin> found = plugin(id);
        if (found.isEmpty()) {
            return frontPage();
        }
        Plugin plugin = found.get();
        SettingsTopics tree = plugin.tree();
        List<SettingsTopic> roots = tree.visibleRoots();
        // One section only: it is the plugin's front, rather than a door to click through for nothing.
        boolean single = roots.size() == 1;
        if (topicPath.isBlank()) {
            return single ? topicPage(plugin, roots.getFirst(), null, true)
                    : new SettingsPage(plugin.id() + ":", plugin.name(), roots, List.of(), List.of(plugin.name()),
                    plugin.id(), null);
        }
        Optional<SettingsTopic> topic = tree.at(topicPath);
        if (topic.isEmpty()) {
            return pluginPage(id, "");
        }
        SettingsTopic at = topic.get();
        String parent;
        if (at.parent() == null) {
            parent = single ? null : plugin.id() + ":";
        } else if (at.parent().parent() == null && single) {
            parent = plugin.id() + ":";
        } else {
            parent = plugin.id() + ":" + at.parent().path();
        }
        return topicPage(plugin, at, parent, single && at.parent() == null);
    }

    private static SettingsPage topicPage(Plugin plugin, SettingsTopic topic, String parent, boolean front) {
        List<String> trail = new ArrayList<>();
        trail.add(plugin.name());
        List<String> titles = new ArrayList<>();
        for (SettingsTopic above = topic; above != null; above = above.parent()) {
            titles.addFirst(above.title());
        }
        for (String title : titles) {
            if (!title.equals(trail.getLast())) {
                trail.add(title);
            }
        }
        return new SettingsPage(front ? plugin.id() + ":" : plugin.id() + ":" + topic.path(),
                front ? plugin.name() : topic.title(), topic.visibleChildren(), topic.settings(),
                SettingsPage.shortenTrail(trail, TRAIL_LENGTH), plugin.id(), parent);
    }

    /** Where a setting lives, as a path {@link #page} opens. */
    public String pathOf(Setting<?> setting) {
        return storeHolding(setting).map(store -> familyOf(store.schema().id()) + ":" + setting.topicPath())
                .orElse(setting.topicPath());
    }

    /** Where a setting lives, as somebody reads it: Enchantments ▸ Shards. */
    public List<String> trailOf(Setting<?> setting) {
        return page(pathOf(setting)).trail();
    }

    /** The id of the plugin whose door it is behind. */
    public String pluginOf(Setting<?> setting) {
        return storeHolding(setting).map(store -> familyOf(store.schema().id())).orElse("");
    }

    /** Which plugin's door it is behind. */
    public String pluginNameOf(Setting<?> setting) {
        return storeHolding(setting).flatMap(store -> plugin(familyOf(store.schema().id())))
                .map(Plugin::name).orElse("");
    }

    private Optional<SettingsStore<?>> storeHolding(Setting<?> setting) {
        for (SettingsStore<?> store : registry.stores()) {
            if (store.schema().settings().contains(setting)) {
                return Optional.of(store);
            }
        }
        return Optional.empty();
    }

    /** What it is now, as the menu shows it. */
    public String now(Setting<?> setting) {
        return storeHolding(setting).map(store -> store.display(setting.key())).orElse("");
    }

    // ---------------------------------------------------------------------------- what changed

    /** Every setting that is not what its plugin shipped with, in the order the plugins came. */
    public List<Setting<?>> changed() {
        List<Setting<?>> found = new ArrayList<>();
        for (SettingsStore<?> store : registry.stores()) {
            for (Setting<?> setting : store.schema().settings()) {
                if (!store.display(setting.key()).equals(SettingCodec.display(setting.defaultValue()))) {
                    found.add(setting);
                }
            }
        }
        return found;
    }

    public boolean isChanged(Setting<?> setting) {
        return storeHolding(setting)
                .map(store -> !store.display(setting.key()).equals(SettingCodec.display(setting.defaultValue())))
                .orElse(false);
    }

    // ---------------------------------------------------------------------------- a typed word

    public enum Word { PLUGIN, SETTING, UNKNOWN }

    /**
     * @param target  the plugin's id or the setting's key; the word itself when unknown
     * @param closest what it was probably meant to be, when unknown
     */
    public record Resolved(Word kind, String target, List<String> closest) {
    }

    /** What a word typed after {@code /settings} is: a plugin, a setting, or neither. */
    public Resolved resolve(String typed) {
        String word = typed == null ? "" : typed.strip();
        Optional<Plugin> plugin = plugin(word);
        if (plugin.isPresent()) {
            return new Resolved(Word.PLUGIN, plugin.get().id(), List.of());
        }
        if (registry.setting(word).isPresent()) {
            return new Resolved(Word.SETTING, word, List.of());
        }
        List<String> candidates = new ArrayList<>(registry.keys());
        plugins().forEach(each -> candidates.add(each.id()));
        return new Resolved(Word.UNKNOWN, word, Closest.to(word, candidates, 3));
    }

    // ---------------------------------------------------------------------------- how a setting looks

    /**
     * A setting's icon, saying what kind it is at a glance: a lit or grey dye for a flag, the block itself, a colour's
     * wool, a sign for one of a list, a chest for several switched on and off, a name tag for text, a comparator for a
     * number. One the setting declares wins, except for a flag, whose dye is its value.
     */
    public Material iconFor(Setting<?> setting) {
        String now = now(setting);
        Class<?> type = setting.type();
        if (type == Boolean.class) {
            return "on".equals(now) ? Material.LIME_DYE : Material.GRAY_DYE;
        }
        if (drawn(setting.icon())) {
            return setting.icon();
        }
        if (type == Material.class) {
            Material block = Material.matchMaterial(now);
            return block != null && isItem(block) ? block : Material.GRASS_BLOCK;
        }
        if (type == NamedTextColor.class) {
            return woolOf(now);
        }
        if (type == List.class) {
            return hasToggles(setting) ? Material.CHEST : Material.WRITABLE_BOOK;
        }
        if (type.isEnum()) {
            return Material.OAK_SIGN;
        }
        if (type == String.class) {
            return Material.NAME_TAG;
        }
        return Material.COMPARATOR;
    }

    /** A small whole number shows as the stack's count, so a page of numbers can be read without hovering. */
    public int countFor(Setting<?> setting) {
        if (setting.type() != Integer.class && setting.type() != Long.class) {
            return 1;
        }
        try {
            long value = Long.parseLong(now(setting).trim());
            return value >= 1 && value <= 64 ? (int) value : 1;
        } catch (NumberFormatException notANumber) {
            return 1;
        }
    }

    private static boolean isItem(Material material) {
        try {
            return material.isItem();
        } catch (RuntimeException | LinkageError noServer) {
            return true;
        }
    }

    private static Material woolOf(String colour) {
        return switch (colour == null ? "" : colour) {
            case "black" -> Material.BLACK_WOOL;
            case "dark_blue", "blue" -> Material.BLUE_WOOL;
            case "dark_green" -> Material.GREEN_WOOL;
            case "dark_aqua" -> Material.CYAN_WOOL;
            case "dark_red", "red" -> Material.RED_WOOL;
            case "dark_purple" -> Material.PURPLE_WOOL;
            case "gold" -> Material.ORANGE_WOOL;
            case "gray" -> Material.LIGHT_GRAY_WOOL;
            case "dark_gray" -> Material.GRAY_WOOL;
            case "green" -> Material.LIME_WOOL;
            case "aqua" -> Material.LIGHT_BLUE_WOOL;
            case "light_purple" -> Material.MAGENTA_WOOL;
            case "yellow" -> Material.YELLOW_WOOL;
            default -> Material.WHITE_WOOL;
        };
    }

    /** {@code rainsenchants:double_jump} → "Double jump": a list entry as somebody reads it. */
    static String readableEntry(String entry) {
        String bare = entry == null ? "" : entry.substring(entry.indexOf(':') + 1);
        String spaced = bare.replace('_', ' ').replace('-', ' ').trim();
        return spaced.isEmpty() ? spaced : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    /** Words wrapped into lines no longer than {@code width}, broken only at spaces. */
    public static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return lines;
        }
        StringBuilder line = new StringBuilder();
        for (String word : text.strip().split("\\s+")) {
            if (!line.isEmpty() && line.length() + 1 + word.length() > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (!line.isEmpty()) {
                line.append(' ');
            }
            line.append(word);
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    /** The names from the root inwards, shortened to what a window title can hold. */
    private static List<String> trailTo(SettingsTopic topic) {
        List<String> names = new ArrayList<>();
        for (SettingsTopic above = topic; above != null; above = above.parent()) {
            names.addFirst(above.title());
        }
        return SettingsPage.shortenTrail(names, TRAIL_LENGTH);
    }

    // ---------------------------------------------------------------------------- clicking

    /**
     * Whether clicking this setting can change it, or whether it has to be typed or chosen.
     *
     * <p>A flag and a small, named choice have an obvious next value; a number does not — up by one or
     * by a hundred? — and a piece of text has nowhere to go at all.
     *
     * <p>{@link Material} and {@link NamedTextColor} are deliberately excluded even though
     * {@link Setting#choices()} lists every value each can be — that list exists so a hand-edited
     * {@code config.yml} and {@code /settings set}'s tab-completion show what is valid, not because
     * cycling through several hundred materials, or even sixteen colours, one click at a time is a
     * reasonable way to choose one. See {@link Click#NEEDS_MATERIAL_CHOICE} and
     * {@link Click#NEEDS_COLOR_CHOICE}.
     */
    public boolean canCycle(Setting<?> setting) {
        return setting != null && setting.type() == Boolean.class;
    }

    /** Whether this is one of a named set — an enum — with a page of answers to pick from. */
    public boolean hasOptions(Setting<?> setting) {
        return setting != null && !setting.choices().isEmpty()
                && setting.type() != Boolean.class
                && setting.type() != List.class
                && setting.type() != Material.class
                && setting.type() != NamedTextColor.class;
    }

    /** Whether this is a list whose entries are named, each switched on its own. */
    public boolean hasToggles(Setting<?> setting) {
        return setting != null && setting.type() == List.class && !setting.choices().isEmpty();
    }

    /** Puts {@code entry} into the list, or takes it out if it is there. Nothing else changes. */
    public void toggle(String key, String entry) {
        registry.set(key, String.join(",", toggled(listed(key), entry)));
    }

    /** What the list holds now. */
    public List<String> listed(String key) {
        List<String> entries = new java.util.ArrayList<>();
        for (String piece : registry.display(key).split(",")) {
            if (!piece.isBlank()) {
                entries.add(piece.trim());
            }
        }
        return entries;
    }

    /** {@code current} with {@code entry} taken out if it is in, or added at the end if it is not. */
    public static List<String> toggled(List<String> current, String entry) {
        List<String> next = new java.util.ArrayList<>(current);
        if (!next.remove(entry)) {
            next.add(entry);
        }
        return List.copyOf(next);
    }

    /** Whether this is a number with both ends of its range known, so it can be picked rather than typed. */
    public boolean hasRange(Setting<?> setting) {
        return setting != null && setting.min() != null && setting.max() != null
                && (setting.type() == Integer.class || setting.type() == int.class);
    }

    /** Clicks a setting: flips it, opens a chooser, or says it needs typing. */
    public Click click(String key) {
        Optional<Setting<?>> setting = registry.setting(key);
        if (setting.isEmpty()) {
            return Click.UNKNOWN;
        }
        if (canCycle(setting.get())) {
            registry.cycle(key);
            return Click.CYCLED;
        }
        // A named set is picked out of a list, never advanced one click at a time. Cycling made the
        // lore the only place that said where you had landed, and overshooting the answer you wanted
        // meant going all the way round again — on a five-value enum that is four clicks to undo one.
        if (hasToggles(setting.get())) {
            return Click.NEEDS_TOGGLES;
        }
        if (hasOptions(setting.get())) {
            return Click.NEEDS_OPTION_CHOICE;
        }
        // And a number with both ends known is picked out of its own range: typing one into chat
        // closes the window, and nothing about "40000" is easier to get right by hand.
        if (hasRange(setting.get())) {
            return Click.NEEDS_NUMBER_CHOICE;
        }
        // A block or an item is exactly what Core's own creative-inventory chooser is for. Typed by
        // hand, this is where a server ended up with a wall built from "gray_candle" — a name that
        // parses and is wrong, discovered only by looking at the wall rather than at the setting.
        if (setting.get().type() == Material.class) {
            return Click.NEEDS_MATERIAL_CHOICE;
        }
        if (setting.get().type() == NamedTextColor.class) {
            return Click.NEEDS_COLOR_CHOICE;
        }
        return Click.NEEDS_TYPING;
    }

    // ---------------------------------------------------------------------------- describing

    /**
     * The lines under a setting's name: what it does, what it is, and how to change it.
     *
     * <p>MiniMessage, so the menu can hand them straight to an icon. The last line is always what to
     * do about it — a button that shows a value without saying how to change it is a button people
     * click hopefully.
     */
    public List<String> describe(Setting<?> setting) {
        List<String> lines = new ArrayList<>();
        if (setting == null) {
            return lines;
        }
        wrap(setting.description(), LORE_WIDTH).forEach(line -> lines.add("<gray>" + line));
        if (!lines.isEmpty()) {
            lines.add("");
        }
        boolean changed = isChanged(setting);
        String label = "Now";
        String now = now(setting);
        if (hasToggles(setting)) {
            List<String> listed = listed(setting);
            label = setting.listedMeansOff() ? "Switched off" : "Chosen";
            now = listed.isEmpty() ? "nothing" : String.join(", ", listed.stream()
                    .map(SettingsNavigation::readableEntry).toList());
        }
        List<String> nowLines = wrap(label + ": " + now, LORE_WIDTH);
        for (int index = 0; index < nowLines.size(); index++) {
            String line = nowLines.get(index);
            lines.add(index == 0
                    ? "<gray>" + label + ": <white>" + Text.literal(line.substring(label.length() + 2))
                    : "<white>" + Text.literal(line));
        }
        if (changed) {
            lines.add("<gray>Default: <white>" + Text.literal(defaultText(setting)));
        }
        if (setting.min() != null) {
            lines.add("<gray>From <white>" + setting.min() + "<gray> to <white>" + setting.max());
        }
        // A handful of answers is worth listing; a material's hundreds or a list's switches are picked on their page.
        if (hasOptions(setting) && setting.type() != NamedTextColor.class && setting.choices().size() <= 12) {
            List<String> options = wrap("One of: " + String.join(", ", setting.choices()), LORE_WIDTH);
            for (int index = 0; index < options.size(); index++) {
                lines.add(index == 0 ? "<gray>One of: <white>" + Text.literal(options.get(0).substring(8))
                        : "<white>" + Text.literal(options.get(index)));
            }
        }
        String plugin = pluginNameOf(setting);
        lines.add("<dark_gray>" + (plugin.isEmpty() ? "" : Text.literal(plugin) + " · ") + setting.key());
        lines.add("");
        lines.add("<yellow>" + Symbols.ARROW + " " + clickHint(setting));
        if (changed) {
            lines.add("<yellow>" + Symbols.ARROW + " Right-click to put back the default");
        }
        return lines;
    }

    private String clickHint(Setting<?> setting) {
        if (canCycle(setting)) {
            return "Click to switch " + ("on".equals(now(setting)) ? "off" : "on");
        }
        if (hasToggles(setting)) {
            return "Click to switch them on and off";
        }
        if (hasRange(setting)) {
            return "Click to pick a number";
        }
        if (hasOptions(setting) || setting.type() == Material.class || setting.type() == NamedTextColor.class) {
            return "Click to choose";
        }
        return "Click to type a new value";
    }

    private List<String> listed(Setting<?> setting) {
        List<String> entries = new ArrayList<>();
        for (String piece : now(setting).split(",")) {
            if (!piece.isBlank()) {
                entries.add(piece.trim());
            }
        }
        return entries;
    }

    private static String defaultText(Setting<?> setting) {
        String shipped = SettingCodec.display(setting.defaultValue());
        if (setting.type() == List.class && !setting.choices().isEmpty()) {
            List<String> entries = java.util.Arrays.stream(shipped.split(",")).map(String::trim)
                    .filter(entry -> !entry.isEmpty()).map(SettingsNavigation::readableEntry).toList();
            return entries.isEmpty() ? "nothing" : String.join(", ", entries);
        }
        return shipped.isEmpty() ? "nothing" : shipped;
    }

    /** What a door says: what is behind it, how much, and how much of that was changed. */
    public List<String> describe(SettingsTopic topic) {
        List<String> lines = new ArrayList<>();
        if (topic == null) {
            return lines;
        }
        wrap(topic.description(), LORE_WIDTH).forEach(line -> lines.add("<gray>" + line));
        if (!lines.isEmpty()) {
            lines.add("");
        }
        List<SettingsTopic> children = topic.visibleChildren();
        // A plugin whose one section has its own name: say what is inside that, not the name again.
        if (children.size() == 1 && children.getFirst().title().equals(topic.title())) {
            children = children.getFirst().visibleChildren();
        }
        List<String> inside = children.stream().map(SettingsTopic::title).toList();
        if (!inside.isEmpty()) {
            String shown = String.join(", ", inside.subList(0, Math.min(5, inside.size())))
                    + (inside.size() > 5 ? " and " + (inside.size() - 5) + " more" : "");
            List<String> wrapped = wrap("Inside: " + shown, LORE_WIDTH);
            for (int index = 0; index < wrapped.size(); index++) {
                lines.add(index == 0 ? "<gray>Inside: <white>" + Text.literal(wrapped.get(0).substring(8))
                        : "<white>" + Text.literal(wrapped.get(index)));
            }
        }
        List<Setting<?>> all = topic.allSettings();
        long changed = all.stream().filter(this::isChanged).count();
        lines.add("<gray>" + all.size() + (all.size() == 1 ? " setting" : " settings")
                + (changed > 0 ? "<dark_gray> · <yellow>" + changed + " changed" : ""));
        lines.add("");
        lines.add("<yellow>" + Symbols.ARROW + " Click to open");
        return lines;
    }

    // ---------------------------------------------------------------------------- finding

    /**
     * The settings matching what somebody typed, best first.
     *
     * <p>Every word has to appear somewhere — in the key, the name, the description, the category
     * names above it, the category's description, or the plugin's id — so "fence block" narrows rather
     * than widens. An exact name or key comes first, then names that start with the words, then the
     * rest in the order the menu shows them.
     */
    public List<Setting<?>> search(String query) {
        String wanted = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (wanted.isEmpty()) {
            return List.of();
        }
        String[] words = wanted.split("[\\s_-]+");
        List<Setting<?>> found = new ArrayList<>();
        Map<Setting<?>, Integer> scores = new HashMap<>();
        for (String key : registry.keys()) {
            Setting<?> setting = registry.setting(key).orElse(null);
            if (setting == null || scores.containsKey(setting)) {
                continue;
            }
            String haystack = haystackOf(setting);
            boolean all = true;
            for (String word : words) {
                all &= haystack.contains(word);
            }
            if (all) {
                found.add(setting);
                scores.put(setting, score(setting, wanted));
            }
        }
        found.sort(Comparator.comparingInt((Setting<?> setting) -> -scores.get(setting)));
        return List.copyOf(found);
    }

    private String haystackOf(Setting<?> setting) {
        // The key three ways — fence-block, fence block, fenceblock — so however it is typed, it is found.
        StringBuilder text = new StringBuilder(setting.key()).append(' ').append(setting.key().replace('-', ' '))
                .append(' ').append(Closest.joined(setting.key())).append(' ').append(setting.title())
                .append(' ').append(setting.description());
        registry.storeOf(setting.key()).ifPresent(store -> {
            text.append(' ').append(store.schema().id());
            store.schema().topics().at(setting.topicPath()).ifPresent(topic -> {
                text.append(' ').append(topic.description());
                for (SettingsTopic above = topic; above != null; above = above.parent()) {
                    text.append(' ').append(above.title());
                }
            });
        });
        return text.toString().toLowerCase(Locale.ROOT);
    }

    private static int score(Setting<?> setting, String query) {
        String wanted = Closest.joined(query);
        String key = Closest.joined(setting.key());
        String title = Closest.joined(setting.title());
        if (key.equals(wanted) || title.equals(wanted)) {
            return 3;
        }
        if (key.startsWith(wanted) || title.startsWith(wanted)) {
            return 2;
        }
        return title.contains(wanted) || key.contains(wanted) ? 1 : 0;
    }

    /**
     * Keys close to one that was mistyped — for "did you mean". Close is a couple of letters off, or
     * containing what was typed; nothing is offered for a word that resembles no setting at all.
     */
    public List<String> closest(String typed, int limit) {
        return Closest.to(typed, registry.keys(), limit);
    }

    public SettingsRegistry registry() {
        return registry;
    }
}
