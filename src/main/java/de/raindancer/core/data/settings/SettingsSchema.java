package de.raindancer.core.data.settings;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * One plugin's settings record, read into a model that {@code config.yml}, the commands and the GUI
 * are all built from.
 *
 * <h2>What this replaces, and why</h2>
 * There used to be a hand-written catalogue: an 835-line list of {@code Setting} objects, read
 * through string keys ({@code settings.bool("gameplay.remove-phantoms")}), returning {@code Object}
 * for the caller to cast, filed into a closed enum of groups that had grown entries belonging to
 * three other plugins, and shipped alongside a {@code config.yml} that duplicated it and needed a
 * build-failing test to stay in step. Every one of those is a way for the declaration and the use to
 * disagree.
 *
 * <p>Here the record <em>is</em> the declaration. Its components are the settings, their types are
 * the setting types, and {@code DEFAULTS} is an instance the compiler has already checked. Nothing
 * is written twice, so nothing can drift.
 *
 * <h2>Everything is checked at startup</h2>
 * A mistake in a settings record — a topic that does not exist, a default outside its own range, two
 * components claiming one key, a type nothing can store — throws while the schema is being read,
 * which is during {@code onEnable}. That is on purpose: the alternative is a server that runs for a
 * week before somebody opens the one screen that cannot render.
 *
 * @param <T> the record type
 */
public final class SettingsSchema<T> {

    /**
     * What a setting may be.
     *
     * <p>Deliberately short. Every type here has an obvious YAML representation, an obvious way to
     * be typed at a command, and an obvious control in a GUI; a type without all three cannot be
     * offered to a server owner honestly, so it is refused rather than half-supported.
     */
    private static final Set<Class<?>> SIMPLE_TYPES = Set.of(
            Boolean.class, Integer.class, Long.class, Double.class, String.class,
            Material.class, NamedTextColor.class, List.class);

    private final Class<T> type;
    private final String id;
    private final T defaults;
    private final SettingsTopics topics;
    private final Map<String, Setting<?>> byKey;

    private SettingsSchema(Class<T> type, String id, T defaults, SettingsTopics topics,
                           Map<String, Setting<?>> byKey) {
        this.type = type;
        this.id = id;
        this.defaults = defaults;
        this.topics = topics;
        this.byKey = byKey;
    }

    /**
     * Reads a settings record.
     *
     * @param type     a record carrying {@link Settings}
     * @param defaults an instance holding every default; usually the record's own {@code DEFAULTS}
     * @throws IllegalArgumentException on any mistake in the declaration, with the offending name in
     *                                  the message
     */
    public static <T> SettingsSchema<T> of(Class<T> type, T defaults) {
        if (type == null) {
            throw new IllegalArgumentException("A settings schema needs a type.");
        }
        if (!type.isRecord()) {
            throw new IllegalArgumentException(type.getSimpleName()
                    + " is not a record. Settings are a record because the snapshot handed to a "
                    + "plugin has to be immutable — a mutable one would let one caller's edit "
                    + "appear in another's copy without anything being saved.");
        }
        Settings declaration = type.getAnnotation(Settings.class);
        if (declaration == null) {
            throw new IllegalArgumentException(type.getSimpleName()
                    + " is missing @Settings, so nothing knows which plugin it belongs to.");
        }
        String owner = "@Settings(\"" + declaration.id() + "\")";

        RecordComponent[] components = type.getRecordComponents();
        if (components.length == 0) {
            throw new IllegalArgumentException(owner
                    + " declares no settings. An empty settings record would put an empty page in "
                    + "the menu, which is worse than no page at all.");
        }
        if (defaults == null) {
            throw new IllegalArgumentException(owner
                    + " was given no DEFAULTS instance to read its default values from.");
        }

        SettingsTopics topics = new SettingsTopics(List.of(declaration.topics()), owner);
        Map<String, Setting<?>> byKey = new LinkedHashMap<>();
        for (RecordComponent component : components) {
            Setting<?> setting = read(component, defaults, topics, owner);
            if (byKey.putIfAbsent(setting.key(), setting) != null) {
                throw new IllegalArgumentException(owner + " has two settings called '"
                        + setting.key() + "'. A key is its path in config.yml, so two of them would "
                        + "be one line in the file that two components disagree about.");
            }
            topics.file(setting, owner);
        }
        List<String> clashes = clashes(byKey.keySet(), List.of());
        if (!clashes.isEmpty()) {
            throw new IllegalArgumentException(owner + ": " + String.join("; ", clashes));
        }
        // Not Map.copyOf: that returns an immutable map whose iteration order is unspecified, and
        // the order here is load-bearing. It is the record's component order, which is what
        // instantiate() pairs against the canonical constructor's parameters, what config.yml is
        // written in, and what a command completes in. Copying it into a map that reorders silently
        // built every snapshot with the components shuffled.
        return new SettingsSchema<>(type, declaration.id(), defaults, topics,
                Collections.unmodifiableMap(new LinkedHashMap<>(byKey)));
    }

    /**
     * What is wrong with putting these keys in one file: a key both a value and the parent of another ("perks" and
     * "perks.start-share" — YAML holds one or the other, so saving one wipes the other), or a key in both sets.
     *
     * @param mine   the keys of one settings record
     * @param others keys other records already keep in the same file
     */
    public static List<String> clashes(java.util.Collection<String> mine, java.util.Collection<String> others) {
        List<String> found = new java.util.ArrayList<>();
        java.util.Set<String> all = new java.util.LinkedHashSet<>(others);
        for (String key : mine) {
            if (others.contains(key)) {
                found.add("'" + key + "' is already a setting in the same file");
            }
        }
        all.addAll(mine);
        for (String key : all) {
            for (String other : all) {
                if (other.startsWith(key + ".") && (mine.contains(key) || mine.contains(other))) {
                    found.add("'" + key + "' is a value and also holds '" + other
                            + "' — YAML keeps one or the other, so saving one would wipe the other");
                }
            }
        }
        return found;
    }

    // ------------------------------------------------------------------ reading one component

    private static Setting<?> read(RecordComponent component, Object defaults,
                                   SettingsTopics topics, String owner) {
        String name = component.getName();
        Class<?> boxed = box(component.getType());
        if (!isSupported(boxed)) {
            throw new IllegalArgumentException(owner + " declares '" + name + "' as "
                    + component.getType().getSimpleName()
                    + ", which nothing knows how to store, type at a command or draw in a menu. "
                    + "Supported: boolean, int, long, double, String, List<String>, an enum, "
                    + "Material, NamedTextColor.");
        }

        In in = component.getAnnotation(In.class);
        if (in == null) {
            throw new IllegalArgumentException(owner + " does not say which topic '" + name
                    + "' belongs in. Add @In(\"…\"): a setting with nowhere to live would be "
                    + "reachable by command and invisible in the menu.");
        }
        String topicPath = SettingsTopics.normalise(in.value());
        if (!topics.has(topicPath)) {
            throw new IllegalArgumentException(owner + " files '" + name + "' under '" + topicPath
                    + "', which no @Topic declares. Check the spelling.");
        }

        Key key = component.getAnnotation(Key.class);
        String yamlKey = key != null && !key.value().isBlank()
                ? key.value().trim()
                : kebab(name);

        Title title = component.getAnnotation(Title.class);
        Describe describe = component.getAnnotation(Describe.class);
        Icon icon = component.getAnnotation(Icon.class);
        Range range = component.getAnnotation(Range.class);
        Choices named = component.getAnnotation(Choices.class);
        if (named != null && boxed != List.class) {
            throw new IllegalArgumentException(owner + " puts @Choices on '" + name + "', which is not a List<String>: "
                    + "only a list has entries to switch on and off.");
        }

        Object defaultValue = defaultOf(component, defaults, owner, name);
        Integer min = range != null && isNumeric(boxed) ? range.min() : null;
        Integer max = range != null && isNumeric(boxed) ? range.max() : null;
        checkDefaultInRange(defaultValue, min, max, owner, name);

        return build(yamlKey, boxed, topicPath,
                title != null ? title.value() : SettingsTopics.readable(name),
                describe != null ? describe.value() : "",
                // None of its own: the menu draws one for what kind of setting it is.
                icon != null ? icon.value() : Material.AIR,
                min, max, defaultValue, named != null ? choicesOf(named, owner, name) : choicesOf(boxed),
                named != null && named.listedMeansOff(), component);
    }

    private static List<String> choicesOf(Choices named, String owner, String name) {
        try {
            return List.copyOf(named.value().getDeclaredConstructor().newInstance().get());
        } catch (ReflectiveOperationException unmade) {
            throw new IllegalArgumentException(owner + "'s @Choices on '" + name + "' names "
                    + named.value().getName() + ", which has no public no-argument constructor.", unmade);
        }
    }

    /**
     * Ties the wildcard down so {@link Setting} can be generic without every caller casting.
     *
     * <p>The unchecked cast is sound: {@code boxed} is the component's own boxed type and
     * {@code defaultValue} was read from that component.
     */
    @SuppressWarnings("unchecked")
    private static <V> Setting<V> build(String key, Class<?> boxed, String topicPath, String title,
                                        String description, Material icon, Integer min, Integer max,
                                        Object defaultValue, List<String> choices, boolean listedMeansOff,
                                        RecordComponent component) {
        return new Setting<>(key, (Class<V>) boxed, topicPath, title, description, icon, min, max,
                (V) defaultValue, choices, listedMeansOff, component.getAccessor());
    }

    private static Object defaultOf(RecordComponent component, Object defaults, String owner,
                                    String name) {
        Object value;
        try {
            value = component.getAccessor().invoke(defaults);
        } catch (ReflectiveOperationException unreachable) {
            throw new IllegalStateException("Could not read the default for " + name, unreachable);
        }
        if (value == null) {
            throw new IllegalArgumentException(owner + " has no default for '" + name
                    + "': the DEFAULTS instance holds null. Every setting has to have a value, "
                    + "because a server that has never touched it still has to run.");
        }
        return value;
    }

    private static void checkDefaultInRange(Object value, Integer min, Integer max, String owner,
                                            String name) {
        if (min == null || !(value instanceof Number number)) {
            return;
        }
        double actual = number.doubleValue();
        if (actual < min || actual > max) {
            throw new IllegalArgumentException(owner + " gives '" + name + "' the default " + value
                    + ", which is outside its own @Range(" + min + ", " + max + ").");
        }
    }

    /**
     * An enum's constants, lower case — what a command completes and a GUI cycles through.
     *
     * <p>{@link NamedTextColor} is not a Java enum — it is an interface with sixteen static
     * instances — so it would otherwise fall through {@link #isSupported} needing a value typed by
     * hand for something that is exactly the shape a cycling GUI button already handles.
     * {@link NamedTextColor#NAMES} is the same sixteen names {@link SettingCodec} already parses and
     * writes, in the fixed declaration order a "next colour" button needs to mean the same thing
     * from one click to the next.
     */
    private static List<String> choicesOf(Class<?> boxed) {
        if (boxed == NamedTextColor.class) {
            return List.copyOf(NamedTextColor.NAMES.keys());
        }
        if (!boxed.isEnum()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (Object constant : boxed.getEnumConstants()) {
            names.add(((Enum<?>) constant).name().toLowerCase(Locale.ROOT));
        }
        return List.copyOf(names);
    }

    private static boolean isSupported(Class<?> boxed) {
        return SIMPLE_TYPES.contains(boxed) || boxed.isEnum();
    }

    private static boolean isNumeric(Class<?> boxed) {
        return boxed == Integer.class || boxed == Long.class || boxed == Double.class;
    }

    private static Class<?> box(Class<?> raw) {
        if (!raw.isPrimitive()) {
            return raw;
        }
        return switch (raw.getName()) {
            case "boolean" -> Boolean.class;
            case "int" -> Integer.class;
            case "long" -> Long.class;
            case "double" -> Double.class;
            case "float" -> Double.class;
            case "short", "byte" -> Integer.class;
            case "char" -> String.class;
            default -> raw;
        };
    }

    /**
     * Fits a number to the primitive the record actually declares.
     *
     * <h2>Why reflection needs this</h2>
     * Because {@link #box} deliberately widens on the way in: a {@code float} setting is read as a
     * {@code Double} and a {@code short} as an {@code Integer}, since that is what a YAML parser
     * produces and there is no point having four nearly identical codecs. Reflection, however, will
     * not narrow: handing a {@code Double} to a constructor that declares {@code float} throws
     * {@link IllegalArgumentException}, and it throws it while the plugin is starting — so a record
     * with one {@code float} field in it is a plugin that does not load, with a stack trace that
     * names the constructor rather than the field.
     *
     * <p>Anything that is not a number, or already fits, is passed through untouched.
     */
    private static Object narrow(Object value, Class<?> wanted) {
        if (!(value instanceof Number number)) {
            return value;
        }
        if (wanted == float.class || wanted == Float.class) {
            return number.floatValue();
        }
        if (wanted == double.class || wanted == Double.class) {
            return number.doubleValue();
        }
        if (wanted == long.class || wanted == Long.class) {
            return number.longValue();
        }
        if (wanted == int.class || wanted == Integer.class) {
            return number.intValue();
        }
        if (wanted == short.class || wanted == Short.class) {
            return number.shortValue();
        }
        if (wanted == byte.class || wanted == Byte.class) {
            return number.byteValue();
        }
        return value;
    }

    /** {@code fencesEnabled} becomes {@code fences-enabled} — the shape YAML keys have here. */
    static String kebab(String camel) {
        StringBuilder built = new StringBuilder(camel.length() + 4);
        for (int index = 0; index < camel.length(); index++) {
            char character = camel.charAt(index);
            if (Character.isUpperCase(character)) {
                if (index > 0) {
                    built.append('-');
                }
                built.append(Character.toLowerCase(character));
            } else {
                built.append(character);
            }
        }
        return built.toString();
    }

    // -------------------------------------------------------------------------- reading

    public Class<T> type() {
        return type;
    }

    /** The plugin this belongs to. */
    public String id() {
        return id;
    }

    /** What the plugin's door is called, if it says: {@link Settings#name()}. */
    public String name() {
        Settings declared = type.getAnnotation(Settings.class);
        return declared == null ? "" : declared.name();
    }

    /** The plugin's door icon, if it says: {@link Settings#icon()}. */
    public Material icon() {
        Settings declared = type.getAnnotation(Settings.class);
        return declared == null ? Material.AIR : declared.icon();
    }

    /** The instance every default is read from. */
    public T defaults() {
        return defaults;
    }

    /** The topic tree, for the GUI to walk. */
    public SettingsTopics topics() {
        return topics;
    }

    /** Every setting, in the order the record declares them. */
    public List<Setting<?>> settings() {
        return List.copyOf(byKey.values());
    }

    /** Every key, in declaration order — what a command completes. */
    public List<String> keys() {
        return List.copyOf(byKey.keySet());
    }

    /** One setting by key, or empty. Empty rather than an exception: the key may be a player's typo. */
    public Optional<Setting<?>> setting(String key) {
        return Optional.ofNullable(byKey.get(key == null ? "" : key.trim()));
    }

    /**
     * Builds a snapshot from a value per key.
     *
     * <p>Through the record's canonical constructor, so a record with a compact constructor that
     * validates or normalises its own components still gets to run it — this class is binding a
     * file to a type, not going behind the type's back.
     *
     * @param valuesByKey every key the schema knows; a missing one takes its default
     */
    public T instantiate(Map<String, Object> valuesByKey) {
        RecordComponent[] components = type.getRecordComponents();
        List<Setting<?>> ordered = settings();
        Class<?>[] parameterTypes = new Class<?>[components.length];
        Object[] arguments = new Object[components.length];
        for (int index = 0; index < components.length; index++) {
            Setting<?> setting = ordered.get(index);
            parameterTypes[index] = components[index].getType();
            Object given = valuesByKey.get(setting.key());
            arguments[index] = narrow(given != null ? given : setting.defaultValue(),
                    parameterTypes[index]);
        }
        try {
            return type.getDeclaredConstructor(parameterTypes).newInstance(arguments);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(
                    "Could not build " + type.getSimpleName() + " from its settings", failure);
        }
    }
}
