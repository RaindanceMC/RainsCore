package de.raindancer.core.content.enchant;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * An enchantment a plugin adds to the game's own registry, so it shows on the item, works in the anvil
 * and survives everything a vanilla enchantment survives.
 *
 * <p>Deliberately not offered by the enchanting table, villagers or loot: nothing here is added to those
 * tags, so how a plugin hands it out (a boss drop, a shop book) stays that plugin's decision.
 */
public record EnchantDef(String namespace, String path, String name, int maxLevel, Gear gear, int anvilCost,
                         int weight) {

    private static final Pattern PART = Pattern.compile("[a-z0-9_./-]+");

    public EnchantDef {
        if (!PART.matcher(String.valueOf(namespace)).matches() || !PART.matcher(String.valueOf(path)).matches()) {
            throw new IllegalArgumentException("An enchantment key is namespace:path in lower case.");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("An enchantment needs a name — it is the line on the item.");
        }
        if (maxLevel < 1 || maxLevel > 255) {
            throw new IllegalArgumentException("An enchantment's max level is 1 to 255.");
        }
        if (gear == null) {
            throw new IllegalArgumentException("An enchantment needs gear to go on.");
        }
    }

    public static Builder builder(String key) {
        int colon = key == null ? -1 : key.indexOf(':');
        if (colon < 1) {
            throw new IllegalArgumentException("An enchantment key needs a namespace: 'plugin:name' (got '" + key + "').");
        }
        String lower = key.toLowerCase(Locale.ROOT);
        return new Builder(lower.substring(0, colon), lower.substring(colon + 1));
    }

    public String key() {
        return namespace + ":" + path;
    }

    public static final class Builder {
        private final String namespace;
        private final String path;
        private String name;
        private int maxLevel = 1;
        private Gear gear;
        private int anvilCost = 4;
        private int weight = 1;

        private Builder(String namespace, String path) {
            this.namespace = namespace;
            this.path = path;
        }

        /** In MiniMessage; the level is appended by the game. */
        public Builder name(String value) {
            this.name = value;
            return this;
        }

        public Builder maxLevel(int value) {
            this.maxLevel = value;
            return this;
        }

        public Builder gear(Gear value) {
            this.gear = value;
            return this;
        }

        public Builder anvilCost(int value) {
            this.anvilCost = value;
            return this;
        }

        public EnchantDef build() {
            return new EnchantDef(namespace, path, name, maxLevel, gear, anvilCost, weight);
        }
    }
}
