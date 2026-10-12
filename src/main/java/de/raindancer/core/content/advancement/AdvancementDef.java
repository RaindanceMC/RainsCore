package de.raindancer.core.content.advancement;

import org.bukkit.Material;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A real, vanilla advancement a plugin defines: it gets the toast, the chat line and its own page in
 * the advancements screen, and anything that listens to {@code PlayerAdvancementDoneEvent} (the
 * economy's payout by frame, for one) sees it like any other.
 *
 * @param parent     another advancement of the same plugin ({@code root}) or a full key
 *                   ({@code minecraft:story/root}); null for the root of a tab of its own
 * @param steps      how many things make it done; one is "simply done or not"
 * @param background the tab's background texture, only meaningful on a root
 */
public record AdvancementDef(String plugin, String id, String parent, String title, String description,
                             Material icon, AdvancementFrame frame, int steps, boolean hidden, boolean toast,
                             boolean announce, String background) {

    private static final Pattern PART = Pattern.compile("[a-z0-9_.-]+");

    public AdvancementDef {
        plugin = clean(plugin, "plugin");
        id = clean(id, "id");
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("An advancement needs a title — it is what the player is shown.");
        }
        description = description == null ? "" : description;
        icon = icon == null ? Material.BOOK : icon;
        frame = frame == null ? AdvancementFrame.TASK : frame;
        steps = Math.max(1, steps);
        parent = parent == null || parent.isBlank() ? null : parent.trim().toLowerCase(Locale.ROOT);
    }

    private static String clean(String value, String what) {
        String normalised = value == null ? "" : value.trim();
        if (!PART.matcher(normalised).matches()) {
            throw new IllegalArgumentException("An advancement " + what + " is lower-case letters, digits and _ . -"
                    + " (got '" + value + "').");
        }
        return normalised;
    }

    public static Builder builder(String plugin, String id) {
        return new Builder(plugin, id);
    }

    /** {@code enchants:first} — unique across the server. */
    public String key() {
        return plugin + ":" + id;
    }

    /** The parent as a full key, or null for a root. */
    public String parentKey() {
        if (parent == null) {
            return null;
        }
        return parent.contains(":") ? parent : plugin + ":" + parent;
    }

    String criterion(int step) {
        return "step_" + step;
    }

    public static final class Builder {
        private final String plugin;
        private final String id;
        private String parent;
        private String title;
        private String description;
        private Material icon;
        private AdvancementFrame frame;
        private int steps = 1;
        private boolean hidden;
        private boolean toast = true;
        private boolean announce = true;
        private String background;

        private Builder(String plugin, String id) {
            this.plugin = plugin;
            this.id = id;
        }

        public Builder parent(String value) {
            this.parent = value;
            return this;
        }

        /** In MiniMessage. */
        public Builder title(String value) {
            this.title = value;
            return this;
        }

        /** In MiniMessage. */
        public Builder description(String value) {
            this.description = value;
            return this;
        }

        public Builder icon(Material value) {
            this.icon = value;
            return this;
        }

        public Builder frame(AdvancementFrame value) {
            this.frame = value;
            return this;
        }

        public Builder steps(int value) {
            this.steps = value;
            return this;
        }

        public Builder hidden(boolean value) {
            this.hidden = value;
            return this;
        }

        public Builder toast(boolean value) {
            this.toast = value;
            return this;
        }

        public Builder announce(boolean value) {
            this.announce = value;
            return this;
        }

        public Builder background(String value) {
            this.background = value;
            return this;
        }

        public AdvancementDef build() {
            return new AdvancementDef(plugin, id, parent, title, description, icon, frame, steps, hidden, toast,
                    announce, background);
        }
    }
}
