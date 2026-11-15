package de.raindancer.core.social.team;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The second half of a team's identity, and the reason a server is not limited to sixteen teams.
 *
 * <h2>Why colour alone runs out</h2>
 * {@link TeamColour} has sixteen values and cannot have more. That is not a decision anybody here made:
 * Minecraft's chat colours, scoreboard team colours and dyes are a set of sixteen, and a seventeenth "colour"
 * would either be a duplicate under another name or a hex value that a nametag and a chat prefix cannot
 * render. A tournament that wanted twenty teams had to either give two of them the same colour — which is the
 * one thing colour exists to prevent — or refuse to found the seventeenth.
 *
 * <p>An emblem is a character, and characters do not run out. Sixteen colours against the fourteen emblems
 * below is <b>224 identities</b> that are distinguishable at a glance, and a red ♦ is as obviously not a red
 * ♣ as a red is not a blue.
 *
 * <h2>Why a symbol rather than, say, a number</h2>
 * Because it has to work in the three places a team is seen, and only a symbol works in all of them:
 *
 * <ul>
 *   <li><b>A nametag above somebody's head</b>, read from across the arena, at an angle, moving. "Team 17" is
 *       unreadable there and ♦ is not.</li>
 *   <li><b>A scoreboard line</b>, where every character costs width somebody else's name needs.</li>
 *   <li><b>An inventory icon</b>, which is why each emblem also names a {@link Material} — a page of teams
 *       has to be scannable, and sixteen shulker boxes in fourteen colours is not.</li>
 * </ul>
 *
 * <p>All fourteen are in the Unicode range Minecraft's default font renders. That is the whole constraint on
 * the list and the reason it is a fixed enum rather than a string a server owner types: a glyph the client
 * cannot draw appears as a hollow box, and a team whose emblem is a hollow box is a team with no identity at
 * all — which nobody would discover until somebody joined it.
 *
 * @see TeamColour for the other half, and for why there are exactly sixteen of those
 */
public enum TeamEmblem {

    /**
     * No emblem — the team is told apart by colour alone.
     *
     * <p>First on purpose, so that a server with four teams gets four plain coloured teams and never sees this
     * mechanism at all. The emblems are what a server reaches for when it runs out of colours, and a feature
     * that imposes itself on the servers that do not need it is one they work around.
     */
    NONE("", "Plain", Material.WHITE_BANNER),

    DIAMOND("♦", "Diamond", Material.DIAMOND),
    CLUB("♣", "Club", Material.OAK_SAPLING),
    HEART("♥", "Heart", Material.POPPY),
    SPADE("♠", "Spade", Material.IRON_SHOVEL),
    STAR("★", "Star", Material.NETHER_STAR),
    CIRCLE("●", "Circle", Material.ENDER_PEARL),
    SQUARE("■", "Square", Material.STONE),
    TRIANGLE("▲", "Triangle", Material.ARROW),
    CROSS("✖", "Cross", Material.CROSSBOW),
    ANCHOR("⚓", "Anchor", Material.IRON_INGOT),
    CROWN("♛", "Crown", Material.GOLDEN_HELMET),
    BOLT("⚡", "Bolt", Material.LIGHTNING_ROD),
    FLAG("⚑", "Flag", Material.RED_BANNER),
    SKULL("☠", "Skull", Material.SKELETON_SKULL);

    private final String glyph;
    private final String title;
    private final Material icon;

    TeamEmblem(String glyph, String title, Material icon) {
        this.glyph = glyph;
        this.title = title;
        this.icon = icon;
    }

    /** The character itself, or an empty string for {@link #NONE}. */
    public String glyph() {
        return glyph;
    }

    /** What to call it on a screen. */
    public String title() {
        return title;
    }

    /**
     * A sensible item to draw it as, for a team whose members have not chosen one.
     *
     * <p>A <em>suggestion</em> and not the answer. The badge on a team is whatever its members picked out of
     * the item chooser — see {@link Team#badge()} — and this is only what a team starts with so that a page of
     * teams is scannable before anybody has been to the trouble. Diamonds for ♦ and a poppy for ♥ is the
     * obvious guess and obvious guesses are what a default is for.
     */
    public Material suggestedBadge() {
        return icon;
    }

    /** How it is stored and typed — lower case, so a file edited by hand still reads. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Whether this emblem actually shows anything. */
    public boolean isVisible() {
        return this != NONE;
    }

    /**
     * The glyph followed by a space, or nothing at all.
     *
     * <p>The form every nametag and scoreboard line wants, and the reason it is a method rather than left to
     * the caller: {@code emblem.glyph() + " " + name} puts a leading space in front of every plain team's
     * name, which is invisible in a diff and obvious on a scoreboard.
     */
    public String prefix() {
        return isVisible() ? glyph + " " : "";
    }

    /**
     * One emblem, however it was typed.
     *
     * <p>Empty rather than a default for something unrecognised. A stored emblem that silently became
     * {@link #NONE} would merge two teams' identities without anything saying so, which is exactly the
     * collision this whole enum exists to avoid.
     */
    public static Optional<TeamEmblem> named(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String wanted = name.strip().toLowerCase(Locale.ROOT);
        for (TeamEmblem emblem : values()) {
            if (emblem.key().equals(wanted) || emblem.glyph().equals(name.strip())) {
                return Optional.of(emblem);
            }
        }
        return Optional.empty();
    }

    /** The ones that actually show something, in order — what a registry hands out after the plain one. */
    public static List<TeamEmblem> visible() {
        List<TeamEmblem> shown = new ArrayList<>();
        for (TeamEmblem emblem : values()) {
            if (emblem.isVisible()) {
                shown.add(emblem);
            }
        }
        return List.copyOf(shown);
    }

    /**
     * How many teams can be told apart at once.
     *
     * <p>Sixteen colours times every emblem including the plain one. Stated as a method because it is the
     * number a screen shows an owner who is asking how many teams they may have, and computing it in the
     * screen would be the kind of arithmetic that goes stale when a glyph is added.
     */
    public static int distinctIdentities() {
        return TeamColour.values().length * values().length;
    }

    /** What holding it means, for lore. */
    public String describe() {
        return isVisible()
                ? "Shown as " + glyph + " before the team's name, so two teams may share a colour"
                : "No emblem. This team is told apart by its colour alone";
    }
}
