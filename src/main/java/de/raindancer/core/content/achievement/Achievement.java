package de.raindancer.core.content.achievement;

import org.bukkit.Material;

import java.util.Locale;
import java.util.Optional;

/**
 * Something a player can do that is worth telling them about.
 *
 * <h2>Why not a vanilla advancement</h2>
 * A vanilla advancement is a datapack JSON file keyed to vanilla triggers, which means "claim your
 * first plot" or "visit every stop on the ghast network" cannot be expressed at all — there is no
 * trigger for either. Generating the datapack from the server would also mean rewriting and
 * reloading it every time a plugin added one. So these are ours, and the toast that announces them
 * is a message rather than the vanilla popup.
 *
 * @param goal   how many of something is needed, or null for one that is simply done or not
 * @param hidden whether it stays out of the list until it is earned
 */
public record Achievement(String plugin, String id, String title, String description,
                          Material icon, int points, Integer goalCount, boolean hidden) {

    public Achievement {
        if (plugin == null || plugin.isBlank()) {
            throw new IllegalArgumentException("An achievement must say which plugin defines it.");
        }
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("An achievement needs an id.");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException(
                    "An achievement needs a title. It is what the player is shown.");
        }
        plugin = plugin.trim().toLowerCase(Locale.ROOT);
        id = id.trim().toLowerCase(Locale.ROOT);
        description = description == null ? "" : description.trim();
        icon = icon == null ? Material.BOOK : icon;
        points = Math.max(0, points);
        if (goalCount != null && goalCount < 1) {
            goalCount = null;
        }
    }

    public static Builder builder(String plugin, String id) {
        return new Builder(plugin, id);
    }

    /** {@code claims:first-claim} — unique across the server. */
    public String key() {
        return plugin + ":" + id;
    }

    /** How many of something is needed, or empty for one that is simply done or not. */
    public Optional<Integer> goal() {
        return Optional.ofNullable(goalCount);
    }

    /** Whether this is one a player works towards rather than simply earns. */
    public boolean hasGoal() {
        return goalCount != null;
    }

    public Achievement withTitle(String newTitle) {
        return new Achievement(plugin, id, newTitle, description, icon, points, goalCount, hidden);
    }

    public Achievement withDescription(String newDescription) {
        return new Achievement(plugin, id, title, newDescription, icon, points, goalCount, hidden);
    }

    public Achievement withPoints(int newPoints) {
        return new Achievement(plugin, id, title, description, icon, newPoints, goalCount, hidden);
    }

    public static final class Builder {
        private final String plugin;
        private final String id;
        private String title;
        private String description;
        private Material icon;
        private int points;
        private Integer goal;
        private boolean hidden;

        private Builder(String plugin, String id) {
            this.plugin = plugin;
            this.id = id;
        }

        /** What the player is shown, in MiniMessage. */
        public Builder title(String value) {
            this.title = value;
            return this;
        }

        /** One line saying what they have to do. */
        public Builder description(String value) {
            this.description = value;
            return this;
        }

        public Builder icon(Material value) {
            this.icon = value;
            return this;
        }

        /** What it is worth, for a leaderboard. */
        public Builder points(int value) {
            this.points = value;
            return this;
        }

        /** How many of something is needed — for one a player works towards. */
        public Builder goal(int value) {
            this.goal = value;
            return this;
        }

        /**
         * Whether it stays out of the list until it is earned.
         *
         * <p>For the ones where knowing about it spoils it. Used sparingly: a list of question
         * marks is not a list of things to aim at.
         */
        public Builder hidden(boolean value) {
            this.hidden = value;
            return this;
        }

        public Achievement build() {
            return new Achievement(plugin, id, title, description, icon, points, goal, hidden);
        }
    }
}
