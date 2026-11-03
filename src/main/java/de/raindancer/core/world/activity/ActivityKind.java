package de.raindancer.core.world.activity;

/** What a player did. */
public enum ActivityKind {
    /** A minute played, not away. */
    PLAY,
    /** A hostile mob killed, not from a spawner or a spawn egg — what is its type ("zombie"). */
    MONSTER,
    /** A wither, ender dragon, elder guardian or warden killed — what is its type. */
    BOSS,
    /** An advancement that is announced in chat — what is its key. */
    ADVANCEMENT,
    /** An ore the world made, mined — what is its group ("iron"), see {@link Ores}. */
    MINE,
    /** A fish or treasure reeled in — what is the item ("cod"), empty when it was no item. */
    FISH,
    /** Two animals bred, neither from a spawner, a spawn egg, a command or a plugin — what is their type. */
    BREED,
    /** A villager or wandering trader traded with — what is the item traded for ("emerald"). */
    TRADE,
    /** A fully grown crop, or a melon or pumpkin the world grew, broken — what is the block ("wheat"). */
    HARVEST,
    /** Enchanting levels spent — the amount is how many (1 to 3); what is the levels the enchantment cost ("30"). */
    ENCHANT,
    /** A raid won, as one of its heroes. */
    RAID
}
