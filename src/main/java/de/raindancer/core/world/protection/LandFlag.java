package de.raindancer.core.world.protection;

import org.bukkit.Material;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Area-wide protection switches: how the world behaves on a piece of protected ground.
 *
 * <p>The boolean a flag carries always means "this thing is allowed". A server owner can force one to a
 * fixed value or stop enforcing it entirely — see {@link FlagPolicy}.
 *
 * <p>A flag is either <em>audience aware</em> or area-wide. An audience-aware flag governs something that
 * happens <em>to a person</em> — fall damage, PvP — and so carries one value per {@link LandAudience}, which
 * is what lets an owner be spared what a stranger is not. Everything else describes the world itself (fire,
 * decay, pistons) and cannot sensibly differ per onlooker, so it carries one value for everybody.
 *
 * <h2>No wording here</h2>
 * A flag has an identity, a default and an icon. What it is <em>called</em> is not its business, and used to
 * be: every name and description here read "… inside the claim", which is wrong in Core twice over — it
 * assumes the ground is a claim, and it hard-codes English into an enum.
 *
 * <p>So the label comes from {@link de.raindancer.core.ui.messages.Messages} under
 * {@code land.flag.<key>.name} and {@code land.flag.<key>.description}. Core ships neutral wording — "inside
 * the area" — and a plugin that knows better says so:
 *
 * <pre>{@code
 * // the claims module, on enable
 * messages.define("land.flag.pvp.description", "Players may damage each other inside this claim");
 * }</pre>
 *
 * <p>Which also means the server owner can reword any of them in {@code messages.yml}, and their edit beats
 * both defaults — the whole point of that four-layer arrangement.
 */
public enum LandFlag {

    PVP(Material.DIAMOND_SWORD, false, true),
    MONSTER_SPAWNING(Material.ZOMBIE_HEAD, false),
    ANIMAL_SPAWNING(Material.WHEAT, true),
    SPAWNER_SPAWNING(Material.SPAWNER, true),
    /** A wandering trader and its llamas turning up on their own; neither monsters nor animals to the other two. */
    TRADER_SPAWNING(Material.EMERALD, true),

    /**
     * Whether a mob may spawn here as the side effect of a potion.
     *
     * <p>Separate from {@link #MONSTER_SPAWNING}, which is the world doing it on its own: this is somebody
     * throwing a splash potion of infestation and getting a nest of silverfish, or one of oozing and getting
     * a pile of slimes. Neither reason reaches {@code isNaturalReason} — a player chose to throw it — so
     * without a flag of its own the spawn happened regardless of what {@code MONSTER_SPAWNING} said, which
     * turned a "no monsters here" claim into one a single potion could still fill.
     *
     * <p>Not audience aware: the mobs that come out do not know who threw the potion, and a claim that keeps
     * them out keeps them out for everybody.
     */
    POTION_SPAWNING(Material.LINGERING_POTION, false),
    MONSTER_ENTRY(Material.IRON_DOOR, false),
    MONSTER_TARGETING(Material.GHAST_TEAR, true, true),
    EXPLOSIONS(Material.TNT, false),
    EXPLOSION_DAMAGE(Material.GUNPOWDER, false, true),
    MOB_DAMAGE(Material.BONE, true, true),

    /**
     * Whether a person may hurt the peaceful creatures here.
     *
     * <p>The other direction from {@link #MOB_DAMAGE}, and the one an owner asks for first: a pen full of cows
     * that a visitor cannot start killing. Covers everything alive that is not a monster and not a player —
     * animals, villagers, golems, tamed wolves, squid — because "which of those counts as livestock" is a
     * question nobody wants to answer per creature.
     *
     * <p>A flag as well as {@link LandAction#DAMAGE_ANIMALS}, which is not a duplicate: the action is granted to
     * named people, this is the ground's own rule for a whole tier. An owner who wants their farm safe from
     * everybody but themselves sets this once instead of auditing every grant.
     *
     * <p>Audience aware, and the tier is the <em>attacker's</em> — that is the whole point.
     */
    HIT_MOBS(Material.PORKCHOP, true, true),

    /**
     * Whether a person may hurt the monsters here.
     *
     * <p>Separate from {@link #HIT_MOBS} because the two are wanted for opposite reasons. Hitting animals is
     * switched off to protect a farm; hitting monsters is switched off to stop a claim being farmed — a spawn
     * where nobody should be fighting, an arena where the mobs are scenery, a shop that does not want a skeleton
     * fight in the doorway.
     *
     * <p>{@link #MONSTER_TARGETING} is the mirror of this and does not replace it: a monster that may not take
     * aim at you can still be shot at, which is exactly how a claim with targeting off becomes a safe grinder.
     */
    HIT_MONSTERS(Material.IRON_SWORD, true, true),

    /**
     * Whether a person may lay hands on a creature here without hurting it.
     *
     * <p>Shearing a sheep, milking a cow, saddling a pig, opening a llama's chest, dyeing, breeding by hand,
     * naming with a tag. None of it produces damage, so {@link #HIT_MOBS} never sees it: a flock can be sheared
     * bare and a claim with every damage rule off has lost its wool.
     *
     * <p>A flag as well as {@link LandAction#ANIMALS}, for the same reason {@link #HIT_MOBS} is one alongside
     * the damage permission — the grant is per person, this is per tier.
     */
    INTERACT_MOBS(Material.SHEARS, true, true),

    /**
     * Whether a person may pick things up off the ground here.
     *
     * <p>Wanted by two quite different places. A shop or a spawn where a dropped stack is not for whoever walks
     * past first; and an arena or an event ground where the floor is meant to stay as it was laid out.
     *
     * <p>Distinct from {@link #ITEM_DROPS}, which decides whether a pile appears when somebody dies. This decides
     * whether anybody may take one that is lying there, however it got there.
     */
    ITEM_PICKUP(Material.HOPPER, true, true),

    /**
     * Whether a person may collect experience orbs here.
     *
     * <p>The half of the same complaint that no permission covered: orbs are not items, so a claim that stopped
     * people taking the drops still handed them the levels. An arena where a kill earns nothing, a grinder whose
     * owner keeps the experience, a spawn where a dropped orb is not a race.
     */
    XP_PICKUP(Material.EXPERIENCE_BOTTLE, true, true),
    FIRE_SPREAD(Material.CAMPFIRE, false),
    LEAF_DECAY(Material.OAK_LEAVES, true),
    ENDERMAN_GRIEF(Material.ENDER_PEARL, false),
    MOB_GRIEF(Material.ROTTEN_FLESH, false),
    PISTONS_FROM_OUTSIDE(Material.PISTON, false),
    FLUIDS_FROM_OUTSIDE(Material.BUCKET, false),

    /**
     * Whether water and lava flow <em>within</em> the area at all.
     *
     * <p>The other half of the fluid question, and the one people ask for second. {@code FLUIDS_FROM_OUTSIDE}
     * stops somebody pouring lava over the wall; this stops the pool inside spreading over a build when a
     * block that was holding it back is broken — which is the damage that happens by accident rather than by
     * malice, and the one nobody thinks about until it has.
     *
     * <p>Area wide: a flow either happens or it does not, and it cannot happen for one onlooker and not another.
     */
    FLUID_FLOW(Material.WATER_BUCKET, true),
    SNOW_ICE_FORM(Material.PACKED_ICE, true),
    FALL_DAMAGE(Material.FEATHER, true, true),
    HUNGER(Material.COOKED_BEEF, true, true),
    /**
     * Whether somebody dying here keeps what they were carrying.
     *
     * <p>Was called {@code ITEM_DROP_ON_DEATH} and meant the opposite of what it said: the name read as "items
     * drop", the description said "keep inventory", and true meant keeping. An old file's value is read into
     * this one unchanged — see {@link #byKey} — so a server that had it on keeps it on.
     *
     * <p>Off is vanilla, and then {@link #ITEM_DROPS} decides whether the pile appears.
     */
    KEEP_INVENTORY(Material.CHEST, false, true),

    /**
     * Whether the pile appears at all when somebody dies without keeping their things.
     *
     * <p>The third outcome, and the one that had no flag: not kept and not dropped, simply gone. An arena that
     * hands out its own kit wants exactly this — with plain vanilla the floor fills with other people's armour,
     * and with keep-inventory nobody loses anything and there is no stake.
     *
     * <p>Only consulted when {@link #KEEP_INVENTORY} is off. Keeping beats dropping: somebody who keeps their
     * inventory has nothing to drop.
     */
    ITEM_DROPS(Material.DROPPER, true, true),
    ELYTRA_FLIGHT(Material.ELYTRA, true, true, EnumSet.of(LandAudience.OWNER)),

    /**
     * Whether a riptide trident may be used here.
     *
     * <p>Its own flag rather than folded into the elytra one, because they are refused for different reasons.
     * An elytra is banned to stop people flying over a wall; a trident is banned because it launches somebody
     * through one from a standing start, in rain, with no run-up — which is the trick that gets past a border
     * an elytra rule already covers.
     *
     * <p>Audience aware and allowed by default, like the elytra: most claims do not care, and the ones that do
     * usually want the owner exempt.
     */
    RIPTIDE(Material.TRIDENT, true, true, EnumSet.of(LandAudience.OWNER)),
    ENDER_PEARL_IN(Material.ENDER_PEARL, true, true,
            EnumSet.of(LandAudience.OWNER, LandAudience.TRUSTED)),
    TELEPORT_IN(Material.COMPASS, true, true,
            EnumSet.of(LandAudience.OWNER, LandAudience.TRUSTED)),

    /**
     * Whether potions may be used here at all — drunk, thrown or left lingering.
     *
     * <p>Separate from PvP, and both halves are wanted. An arena wants its fighters to bring what they brought
     * and nothing more; a shop or a spawn wants no clouds on the floor; a claim owner may want to drink their
     * own potions in peace while nobody throws anything over the wall.
     *
     * <p>Audience aware, because "the owner may, visitors may not" is the common setting and the whole reason
     * the tiers exist.
     */
    POTIONS(Material.SPLASH_POTION, true, true),

    /**
     * Whether a totem of undying saves somebody here.
     *
     * <p>An arena's whole point is that losing means something, and a totem is the item that removes that.
     * Audience aware, because "the owner may keep theirs" is a reasonable thing to want in a claim and a
     * ridiculous one in an arena — and both are set the same way.
     */
    TOTEMS(Material.TOTEM_OF_UNDYING, true, true),

    /**
     * Whether redstone runs here.
     *
     * <p>Not about who may <em>place</em> it — that is the BUILD action — but whether what is placed does
     * anything. Switching it off freezes the machines: no pistons firing, no dispensers, no doors opening
     * themselves. Wanted for a spawn where somebody has built a lag machine, and for an arena where a hidden
     * dispenser is not part of the fight.
     *
     * <p>Area wide, because a circuit cannot run for the owner and not for a visitor — it is one machine.
     */
    REDSTONE(Material.REDSTONE, true),

    /**
     * Whether animals may be bred here.
     *
     * <p>The flag people reach for after their first lag report: two players, forty cows, one chunk. Separate
     * from ANIMAL_SPAWNING, which is about the world putting animals there — this is about somebody standing in
     * a pen with a bucket of wheat.
     */
    BREEDING(Material.WHEAT, true, true),

    /**
     * Whether leads work here.
     *
     * <p>Distinct from the ANIMALS permission, which is about who may touch an animal. This is about whether
     * anybody can walk one off the property — the trick that empties a pen without breaking a single block, so
     * nothing in the block protection ever sees it.
     */
    LEADS(Material.LEAD, true, true),

    /**
     * Whether boats and minecarts may be put down here.
     *
     * <p>Distinct from the VEHICLES permission, which decides who may use them. This decides whether they exist
     * here at all: a spawn where forty abandoned boats have accumulated, an arena where a boat is a way onto a
     * wall nobody meant to be climbable.
     */
    BOATS(Material.OAK_BOAT, true, true),

    /**
     * Whether somebody may walk in at all.
     *
     * <p>The counterpart to the teleport and pearl flags, and the one that makes them a complete set: without
     * it an owner can close every other way in and still have people wander through the front. With it, a tier
     * that is not allowed is turned back at the border.
     *
     * <p>A flag as well as {@link LandAction#ENTER}, which is not a duplicate: the action is a permission
     * granted to named people, this is the ground's own rule for a whole tier. An owner keeping their garden
     * shut to visitors sets this once, rather than editing the public grant and then remembering it is there.
     */
    WALK_IN(Material.LEATHER_BOOTS, true, true),

    /**
     * Whether somebody inside can be seen from outside.
     *
     * <p>Privacy rather than protection, and the one owners ask for that no permission covers: a walled garden
     * is not private if everybody outside can watch you in it. Off hides the people inside from everybody
     * standing out, and shows them again the moment either party crosses the border.
     *
     * <p>Audience aware, and the tier is the <em>watcher's</em> — so "the people I trust can see in, strangers
     * cannot" is one setting rather than a list. Owners and trusted players see in by default; only visitors
     * are turned away by switching it off.
     *
     * <p>Hiding a player is a promise every subsystem has to keep, so this is enforced by hiding them from the
     * watcher's client rather than by suppressing anything: somebody hidden this way is genuinely not sent, not
     * merely absent from a list somewhere.
     */
    VISIBLE_FROM_OUTSIDE(Material.SPYGLASS, true, true),

    /**
     * Whether equipment loses durability here — worn or held, all of it.
     *
     * <p>Not only armour despite the name a server owner will actually use for it. An elytra worn in
     * the same slot wears down the same way; a sword swung in a fight, a pickaxe swung at a block and
     * a bow drawn in the hand all wear down for exactly the reason this flag exists to switch off — an
     * arena handing out a reusable kit does not want the sword blunted by the fight it was handed out
     * for. Splitting "worn" from "held" would be two flags an owner has to remember are the same
     * request, so this covers every durability loss a player's own equipment takes.
     *
     * <p>Audience aware: an arena handing out a reusable kit wants it off for everybody, and an owner
     * who only wants their own gear spared while a visitor's still wears out sets the two tiers apart —
     * both are real requests and neither is more common than the other.
     */
    ARMOR_DURABILITY(Material.IRON_CHESTPLATE, true, true);

    private final Material icon;
    private final boolean builtInDefault;
    private final boolean audienceAware;
    private final Set<LandAudience> legacyExempt;

    LandFlag(Material icon, boolean builtInDefault) {
        this(icon, builtInDefault, false, EnumSet.noneOf(LandAudience.class));
    }

    LandFlag(Material icon, boolean builtInDefault, boolean audienceAware) {
        this(icon, builtInDefault, audienceAware, EnumSet.noneOf(LandAudience.class));
    }

    LandFlag(Material icon, boolean builtInDefault, boolean audienceAware,
             Set<LandAudience> legacyExempt) {
        this.icon = icon;
        this.builtInDefault = builtInDefault;
        this.audienceAware = audienceAware;
        this.legacyExempt = legacyExempt;
    }

    /** The message key holding this flag's name. See the class comment. */
    public String nameKey() {
        return "land.flag." + key() + ".name";
    }

    /** The message key holding its one-line explanation. */
    public String descriptionKey() {
        return "land.flag." + key() + ".description";
    }

    public Material icon() {
        return icon;
    }

    /** What a server that has said nothing about this flag gets. */
    public boolean builtInDefault() {
        return builtInDefault;
    }

    /**
     * Whether this flag may be set separately per {@link LandAudience}.
     *
     * <p>False for everything that describes the world rather than a person: one patch of leaves cannot decay
     * for visitors and stay put for the owner.
     */
    public boolean audienceAware() {
        return audienceAware;
    }

    /**
     * The tiers that were hard-coded as exempt from this flag before it became audience aware.
     *
     * <p>Used only when reading ground saved by an older version: back then "teleport in: denied" meant
     * "denied for strangers", because owners and trusted players were waved through in the listener. That
     * exemption now lives in the per-tier values, so an area keeps behaving the way it was set up rather than
     * silently locking its owner out of their own home.
     */
    public Set<LandAudience> legacyExemptAudiences() {
        return legacyExempt;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static Optional<LandFlag> byKey(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalised = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (LandFlag flag : values()) {
            if (flag.name().equals(normalised)) {
                return Optional.of(flag);
            }
        }
        // An old file's name for what is now KEEP_INVENTORY. Read rather than ignored, because ignoring it
        // would silently switch keep-inventory off on every server that had it on.
        if (normalised.equals("ITEM_DROP_ON_DEATH")) {
            return Optional.of(KEEP_INVENTORY);
        }
        return Optional.empty();
    }

    /**
     * Flag keys that used to exist here and deliberately no longer do.
     *
     * <p>{@code show-enter-message}, {@code show-border-on-enter} and {@code show-titles} were never world
     * protection — they decide whether a plugin says something when you arrive, which is that plugin's
     * business. A loader finding one of these in an old file can say "that moved" instead of "unknown flag".
     */
    public static boolean wasRetired(String raw) {
        if (raw == null) {
            return false;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT).replace('_', '-')) {
            case "show-enter-message", "show-border-on-enter", "show-titles" -> true;
            default -> false;
        };
    }
}
