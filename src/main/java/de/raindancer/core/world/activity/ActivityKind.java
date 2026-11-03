package de.raindancer.core.world.activity;

/** What a player did. A listener that switches over these keeps a default: more are added as plugins need them. */
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
    RAID,
    /** Raw stone the world made, mined — what is the block ("deepslate"), see {@link Terrain}. */
    DIG,
    /** Dirt, sand, gravel or clay the world made, dug — what is the block. */
    EXCAVATE,
    /** A log or stem the world grew, chopped — what is the block ("oak_log"). */
    CHOP,
    /** Berries picked, or a mushroom or flower the world grew — what is the block or the berries. */
    FORAGE,
    /** Something crafted from two kinds of ingredient or more — what is the result, the amount how many. */
    CRAFT,
    /** Something smelted that is not food, taken out of a furnace — what is the item, the amount how many. */
    SMELT,
    /** Food cooked in a furnace or smoker, taken out — what is the item, the amount how many. */
    COOK,
    /** Potions brewed in a stand the player last worked — what is the ingredient, the amount how many bottles. */
    BREW,
    /** A block placed, not on a spot the player built on in the last ten minutes — what is the block. */
    BUILD,
    /** An animal tamed — what is its type. */
    TAME,
    /** A biome entered for the first time ever (Adventuring Time) — what is the biome. */
    EXPLORE,
    /** Blocks walked, sprinted, swum and climbed in the last minute, not away — the amount is how many. */
    TRAVEL,
    /** An item repaired, combined or renamed on an anvil — what is the item, the amount the levels it cost. */
    REPAIR
}
