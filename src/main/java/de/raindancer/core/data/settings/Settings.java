package de.raindancer.core.data.settings;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a record as one plugin's settings.
 *
 * <p>The record is the single source: {@code config.yml}, its comments, the validation, the tab
 * completion and every settings screen are derived from it. There is no second list to keep in step
 * with it, which is the mistake the catalogue this replaces was built around — it shipped a
 * {@code config.yml} beside the catalogue and needed a build-failing test to keep the two honest.
 *
 * @see SettingsSchema
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Settings {

    /**
     * The plugin this belongs to, e.g. {@code "claims"}. Ids sharing what comes before the first dash
     * ({@code guilds}, {@code guilds-npcs}) are one plugin's door on the front page of the menu.
     */
    String id();

    /** What its door on the front page says; empty for its own topic's title, else the id read out. */
    String name() default "";

    /** Its door's icon; air for its own topic's icon. */
    org.bukkit.Material icon() default org.bukkit.Material.AIR;

    /**
     * The topics its settings are filed under.
     *
     * <p>Only subtopics of the three standard roots — see {@link SettingsTopics}. A plugin that
     * declares a root of its own is refused: one plugin inventing "Miscellaneous" is how the menu
     * became a wall of unrelated buttons in the first place.
     */
    Topic[] topics() default {};
}
