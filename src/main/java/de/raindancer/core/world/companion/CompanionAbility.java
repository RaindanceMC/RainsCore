package de.raindancer.core.world.companion;

/**
 * What a ridden companion does when its rider swings at the air — one per companion, chosen by its plugin. Whatever
 * it does breaks and burns nothing, and hurts only what its rider may fight (see {@link Abilities#hits}).
 */
public enum CompanionAbility {

    /** Three wither skulls where the rider looks; whatever they hit withers for a moment. */
    WITHER_SKULLS("Wither Skulls", 6),
    /** A sonic boom straight ahead, through armour, along twenty blocks. */
    SONIC_BOOM("Sonic Boom", 12),
    /** A ghast's fireball that bursts where it lands — no fire, no crater. */
    FIREBALL("Fireball", 8),
    /** Five small fireballs fanned out ahead; whatever they hit burns a while — never a block. */
    FLAME_VOLLEY("Flame Volley", 7),
    /** A gust in front that throws everything in it away and up. */
    GUST("Gust", 7),
    /** A roar that hurts, throws back and briefly stuns everything around. */
    ROAR("Roar", 10),
    /** The elder curse on everything around: slow, weak, tired. */
    CURSE("Elder Curse", 15),
    /** A rain that mends the rider and every player around. */
    MENDING_RAIN("Mending Rain", 15),
    /** Rider and mount vanish from sight; whoever was after them is blinded. */
    ILLUSION("Illusion", 20);

    private final String title;
    private final int cooldownSeconds;

    CompanionAbility(String title, int cooldownSeconds) {
        this.title = title;
        this.cooldownSeconds = cooldownSeconds;
    }

    public String title() {
        return title;
    }

    public int cooldownSeconds() {
        return cooldownSeconds;
    }
}
