package de.raindancer.core.data.settings;

import org.bukkit.Material;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * One configurable value, described well enough that {@code config.yml}, a command and a GUI screen
 * can all be built from it without any of them being told anything twice.
 *
 * <p>Made by {@link SettingsSchema} from a record component; never constructed by hand. The type
 * parameter is the component's own type, so {@code settings.get(FENCES_ENABLED)} gives back a
 * {@code Boolean} with no cast and a typo gives a compile error rather than a runtime one — the
 * single biggest complaint about the stringly-typed catalogue this replaces.
 */
public final class Setting<T> {

    private final String key;
    private final Class<T> type;
    private final String topicPath;
    private final String title;
    private final String description;
    private final Material icon;
    private final Integer min;
    private final Integer max;
    private final T defaultValue;
    private final List<String> choices;
    private final boolean listedMeansOff;
    private final Method accessor;

    Setting(String key, Class<T> type, String topicPath, String title, String description,
            Material icon, Integer min, Integer max, T defaultValue, List<String> choices,
            Method accessor) {
        this(key, type, topicPath, title, description, icon, min, max, defaultValue, choices, false, accessor);
    }

    Setting(String key, Class<T> type, String topicPath, String title, String description,
            Material icon, Integer min, Integer max, T defaultValue, List<String> choices,
            boolean listedMeansOff, Method accessor) {
        this.key = key;
        this.type = type;
        this.topicPath = topicPath;
        this.title = title;
        this.description = description;
        this.icon = icon;
        this.min = min;
        this.max = max;
        this.defaultValue = defaultValue;
        this.choices = List.copyOf(choices);
        this.listedMeansOff = listedMeansOff;
        this.accessor = accessor;
    }

    /** Its path in {@code config.yml}, and the token a command takes. Unique within a plugin. */
    public String key() {
        return key;
    }

    /** The boxed type of the record component: {@code Boolean}, {@code Integer}, an enum, … */
    public Class<T> type() {
        return type;
    }

    /** Which {@link SettingsTopic} it appears under. */
    public String topicPath() {
        return topicPath;
    }

    public String title() {
        return title;
    }

    /** One sentence, or empty. Never invented. */
    public String description() {
        return description;
    }

    public Material icon() {
        return icon;
    }

    /** The lower bound, or null when the type has none. */
    public Integer min() {
        return min;
    }

    /** The upper bound, or null when the type has none. */
    public Integer max() {
        return max;
    }

    public T defaultValue() {
        return defaultValue;
    }

    /**
     * What this may be set to, lower case, when the answer is a fixed list — the constants of an
     * enum, or for a list the entries its {@link Choices} names. Empty for everything else, which is
     * what a GUI checks to decide between cycling and asking.
     */
    public List<String> choices() {
        return choices;
    }

    /** For a list with {@link Choices}: whether being listed means switched off. */
    public boolean listedMeansOff() {
        return listedMeansOff;
    }

    /**
     * This setting's value in a given snapshot.
     *
     * <p>The accessor is the record's own, so this cannot go out of step with the component the
     * schema was built from.
     */
    @SuppressWarnings("unchecked")
    public T valueIn(Object instance) {
        try {
            return (T) accessor.invoke(instance);
        } catch (IllegalAccessException | InvocationTargetException unreachable) {
            // The accessor of a record component is public and does nothing but return a field.
            throw new IllegalStateException("Could not read " + key + " from " + instance, unreachable);
        }
    }

    @Override
    public String toString() {
        return key + " (" + type.getSimpleName() + ")";
    }
}
