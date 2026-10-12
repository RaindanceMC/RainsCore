package de.raindancer.core.ui.identity;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Marks;
import de.raindancer.core.ui.text.Gradients;
import de.raindancer.core.ui.text.NameStyle;
import de.raindancer.core.ui.text.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/**
 * Who a player is, as everybody else sees them.
 *
 * <h2>Why chat and the nametag are separate</h2>
 * They look like the same thing and they are not. A chat line is a component the server builds and
 * can make as long as it likes; a nametag is drawn by the client above a moving head, has no room
 * for a sentence, and on a vanilla client is one line. So a player carries a rank prefix in both, a
 * decorative suffix in chat only, and something short above their head — and the two are set
 * independently, with the nametag falling back to the chat prefix when nobody has bothered.
 *
 * <h2>Why prefixes are stored as MiniMessage and names never are</h2>
 * A prefix is something an administrator wrote, and being able to colour it is the entire point, so
 * it is stored as markup and parsed. A player's <em>name</em> is not: it is inserted as plain text,
 * so somebody calling themselves {@code <red>} shows up as nine characters instead of recolouring
 * everybody's chat. That distinction is the one thing in this class worth getting right.
 *
 * <h2>Thread safety</h2>
 * Safe from any thread. Identities are a {@link ConcurrentHashMap} and a flush takes a snapshot.
 */
public final class Identities {

    private static final LogChannel log = Log.of("identity");
    /**
     * Style only — see {@link Text#styled}. A prefix, a suffix or a nickname may be coloured; it may not
     * be a button, and a prefix with a click in it is refused on the way in rather than stored.
     */
    private static final MiniMessage MINI = Text.styleOnly();
    private static final PlainTextComponentSerializer PLAIN =
            PlainTextComponentSerializer.plainText();

    /**
     * How long a nametag may be, in characters.
     *
     * <p>The client draws it over the world, above a head that moves. Past about this it stops being
     * a label and starts being a banner obscuring whatever is behind the player.
     */
    public static final int MAX_NAMETAG_CHARS = 32;

    /** How long any one piece of an identity may be, as typed. */
    private static final int MAX_STORED_CHARS = 128;

    /** Everything one player carries. Immutable; changed by replacing it. */
    private record Identity(String prefix, String suffix, String nametagPrefix, String colour,
                            String subtitle) {

        static final Identity BLANK = new Identity("", "", "", "", "");

        boolean isBlank() {
            return prefix.isEmpty() && suffix.isEmpty() && nametagPrefix.isEmpty()
                    && colour.isEmpty() && subtitle.isEmpty();
        }
    }

    private final Database database;
    private final Map<UUID, Identity> identities = new ConcurrentHashMap<>();
    /**
     * Who goes by another name this session, as a plugin with a {@code /nick} told us. Not stored here:
     * the plugin owning the nickname keeps it and hands it over again on every join.
     */
    private final Map<UUID, String> nicknames = new ConcurrentHashMap<>();
    /** Which players have changed, so a save writes one row rather than every row. */
    private final Set<UUID> changed = ConcurrentHashMap.newKeySet();

    /** How long an animated gradient takes to flow once round its colours. */
    public static final long ANIMATION_PERIOD_MS = 3000;

    private final java.util.function.LongSupplier clock;

    public Identities(Database database) {
        this(database, System::currentTimeMillis);
    }

    /** With a clock of the caller's — so a test can say what moment an animated name is drawn at. */
    public Identities(Database database, java.util.function.LongSupplier clock) {
        this.database = database;
        this.clock = clock;
    }

    /** Whether this player's name moves, so whoever draws it knows to draw it again soon. */
    public boolean isAnimated(UUID player) {
        return nameStyle(player).isAnimated();
    }

    // ---------------------------------------------------------------------------- reading

    /**
     * A player's name as it appears in chat: prefix, the name in their colour, then suffix.
     *
     * @param name the player's actual name, inserted as text and never parsed
     */
    public Component chatName(UUID player, String name) {
        Identity identity = identityOf(player);
        Component built = NameBadges.before(player, NameBadges.Place.CHAT);
        if (!identity.prefix().isEmpty()) {
            built = built.append(parse(identity.prefix()));
        }
        built = built.append(colouredName(shownName(player, name), identity.colour()));
        if (!identity.suffix().isEmpty()) {
            built = built.append(parse(identity.suffix()));
        }
        return built;
    }

    /**
     * What floats above their head: the nametag prefix, or the chat prefix when there is none, then
     * the name — clipped to something that fits over a moving player.
     */
    public Component nametag(UUID player, String name) {
        Identity identity = identityOf(player);
        String prefix = identity.nametagPrefix().isEmpty()
                ? identity.prefix()
                : identity.nametagPrefix();
        Component built = NameBadges.before(player, NameBadges.Place.NAMETAG);
        if (!prefix.isEmpty()) {
            built = built.append(parse(prefix));
        }
        built = built.append(colouredName(shownName(player, name), identity.colour()));
        return clip(built);
    }

    /**
     * A player's line in the tablist: the nametag prefix in front of everything else — essentials
     * module's own AFK tracker is the one caller {@link #setNametagPrefix} has, so in practice this
     * is where "[AFK] " comes from — then the chat prefix, the name in their colour, and the chat
     * suffix.
     *
     * <p>Not {@link #nametag}: that one clips to 32 characters for a client drawing it over a
     * moving head, and falls back to the chat prefix instead of showing both — right for a floating
     * label with room for one badge, wrong for a tablist column with room for both a rank and an
     * "away" marker at once. Not {@link #chatName} either: that one never carries the nametag
     * prefix at all, which is why a player who went AFK used to vanish from the tablist's own idea
     * of who is away the moment {@code Tablists.refresh()} next ran — the periodic rebuild rebuilds
     * every name from {@code chatName}, so a badge that only lived there was overwritten within
     * seconds of being set.
     */
    public Component tablistName(UUID player, String name) {
        Identity identity = identityOf(player);
        Component built = NameBadges.before(player, NameBadges.Place.TABLIST);
        if (!identity.nametagPrefix().isEmpty()) {
            built = built.append(parse(identity.nametagPrefix()));
        }
        if (!identity.prefix().isEmpty()) {
            built = built.append(parse(identity.prefix()));
        }
        built = built.append(colouredName(shownName(player, name), identity.colour()));
        if (!identity.suffix().isEmpty()) {
            built = built.append(parse(identity.suffix()));
        }
        return built;
    }

    /**
     * The name to draw for a player: their nickname when a plugin has set one, otherwise {@code name}.
     */
    public String shownName(UUID player, String name) {
        return player == null ? name : nicknames.getOrDefault(player, name);
    }

    /**
     * What a {@code /nick} plugin says this player is called — plain text, painted in their name style
     * like the real name would be. Null or blank goes back to the real name. Session only.
     */
    public void setNickname(UUID player, String plain) {
        if (player == null) {
            return;
        }
        if (plain == null || plain.isBlank()) {
            nicknames.remove(player);
        } else {
            nicknames.put(player, plain.strip());
        }
    }

    /** The second line under a nametag, for whoever is drawing one. */
    public Optional<Component> subtitle(UUID player) {
        String subtitle = identityOf(player).subtitle();
        return subtitle.isEmpty() ? Optional.empty() : Optional.of(parse(subtitle));
    }

    public Optional<String> prefix(UUID player) {
        return notEmpty(identityOf(player).prefix());
    }

    public Optional<String> suffix(UUID player) {
        return notEmpty(identityOf(player).suffix());
    }

    public Optional<String> nametagPrefix(UUID player) {
        return notEmpty(identityOf(player).nametagPrefix());
    }

    /**
     * The name to show for a player, whether or not they are on the server.
     *
     * <p>Here because "who is this uuid" is a question every plugin has and none of them should be answering
     * for itself. Before this, one plugin kept its own cache of uuid-to-name, which is one more set of answers
     * to drift the first time somebody renames — and a plugin without a cache writes a raw uuid into a
     * sentence a player is meant to read.
     *
     * <p>Answers from the server's own player list, which is what makes it correct for offline players too.
     * Empty for a uuid the server has genuinely never seen, so a caller can say "somebody" rather than print
     * thirty-six characters of hex.
     */
    public Optional<String> nameOf(UUID player) {
        if (player == null) {
            return Optional.empty();
        }
        Player online = Bukkit.getPlayer(player);
        if (online != null) {
            return Optional.of(online.getName());
        }
        String saved = Bukkit.getOfflinePlayer(player).getName();
        return saved == null || saved.isBlank() ? Optional.empty() : Optional.of(saved);
    }

    public Optional<String> colour(UUID player) {
        return notEmpty(identityOf(player).colour());
    }

    /**
     * How their name is painted — a one-stop style for a plain colour, {@link NameStyle#NONE} for
     * nothing. The colour column and this are the same value: {@link NameStyle#encode} packs a lone
     * colour to the bare hex code the column always held.
     */
    public NameStyle nameStyle(UUID player) {
        return NameStyle.parse(identityOf(player).colour());
    }

    /** Whether this player's name is painted at all — a colour, a gradient or a decoration. */
    public boolean hasNameStyle(UUID player) {
        return !nameStyle(player).isEmpty();
    }

    /**
     * {@code text} painted the way this player's name is — for an @-mention of them, a name in a list, a
     * signature. Plain text when they have no style. Never markup: {@code text} is shown as written.
     */
    public Component painted(UUID player, String text) {
        return colouredName(text, identityOf(player).colour());
    }

    /** Everybody who has anything set. */
    public Set<UUID> known() {
        return Set.copyOf(identities.keySet());
    }

    // ---------------------------------------------------------------------------- writing

    /**
     * Sets the chat prefix. Null or blank clears it.
     *
     * @return whether it was accepted; false means it would not parse, or was absurdly long
     */
    public boolean setPrefix(UUID player, String miniMessage) {
        return change(player, miniMessage, (identity, value) -> new Identity(value,
                identity.suffix(), identity.nametagPrefix(), identity.colour(),
                identity.subtitle()));
    }

    public boolean setSuffix(UUID player, String miniMessage) {
        return change(player, miniMessage, (identity, value) -> new Identity(identity.prefix(),
                value, identity.nametagPrefix(), identity.colour(), identity.subtitle()));
    }

    public boolean setNametagPrefix(UUID player, String miniMessage) {
        return change(player, miniMessage, (identity, value) -> new Identity(identity.prefix(),
                identity.suffix(), value, identity.colour(), identity.subtitle()));
    }

    /** The colour their name is drawn in — a MiniMessage colour name or a hex code. */
    public boolean setColour(UUID player, String colour) {
        if (colour != null && !colour.isBlank() && !isColour(colour)) {
            return false;
        }
        // Stored as it was judged: " RED" passes the check, and stored raw it names no colour at all.
        String cleaned = colour == null ? null : colour.trim().toLowerCase(Locale.ROOT);
        return change(player, cleaned, (identity, value) -> new Identity(identity.prefix(),
                identity.suffix(), identity.nametagPrefix(), value, identity.subtitle()));
    }

    /**
     * Paints their name: a colour, a gradient of up to {@link NameStyle#MAX_STOPS} stops, decorations.
     * {@link NameStyle#NONE} clears it. Replaces whatever {@link #setColour} set — they are one value.
     *
     * @return false for a null player or too many stops; nothing is cut short silently
     */
    public boolean setNameStyle(UUID player, NameStyle style) {
        if (style == null) {
            style = NameStyle.NONE;
        }
        if (style.colours().size() > NameStyle.MAX_STOPS) {
            return false;
        }
        return change(player, style.encode(), (identity, value) -> new Identity(identity.prefix(),
                identity.suffix(), identity.nametagPrefix(), value, identity.subtitle()));
    }

    /** The second line under their nametag. */
    public boolean setSubtitle(UUID player, String miniMessage) {
        return change(player, miniMessage, (identity, value) -> new Identity(identity.prefix(),
                identity.suffix(), identity.nametagPrefix(), identity.colour(), value));
    }

    /**
     * Everything this server shows for a player, as plain strings — for another server of the network to
     * {@link #adopt} when they arrive there. Empty strings for what is not set.
     */
    public Map<String, String> export(UUID player) {
        Identity identity = identityOf(player);
        return Map.of("prefix", identity.prefix(), "suffix", identity.suffix(),
                "nametagPrefix", identity.nametagPrefix(), "colour", identity.colour(),
                "subtitle", identity.subtitle(), "nickname", player == null ? "" : nicknames.getOrDefault(player, ""));
    }

    /**
     * Takes over what another server {@link #export exported}, whole: a part set there is set here, a part
     * empty there is cleared here. Each part goes through the same checks as when it was first set.
     */
    public void adopt(UUID player, Map<String, String> exported) {
        if (player == null || exported == null) {
            return;
        }
        setPrefix(player, exported.getOrDefault("prefix", ""));
        setSuffix(player, exported.getOrDefault("suffix", ""));
        setNametagPrefix(player, exported.getOrDefault("nametagPrefix", ""));
        setNameStyle(player, NameStyle.parse(exported.getOrDefault("colour", "")));
        setSubtitle(player, exported.getOrDefault("subtitle", ""));
        setNickname(player, exported.getOrDefault("nickname", ""));
    }

    /** Forgets everything about a player. */
    public void clear(UUID player) {
        if (player != null && identities.remove(player) != null) {
            changed.add(player);
        }
    }

    private boolean change(UUID player, String raw,
                           BiFunction<Identity, String, Identity> update) {
        if (player == null) {
            return false;
        }
        // Deliberately not trimmed: the trailing space in "<gold>[Admin] " is what separates the
        // prefix from the name, and trimming it — which the first version of this did — glued every
        // rank to every player's name.
        String value = raw == null ? "" : raw;
        if (value.isBlank()) {
            value = "";
        }
        if (value.length() > MAX_STORED_CHARS) {
            return false;
        }
        if (!value.isEmpty() && !isUsableMarkup(value)) {
            return false;
        }
        String accepted = value;
        // One atomic step: read, change and put back separately, a prefix and a suffix set at the same
        // moment from two threads kept only one of them.
        identities.compute(player, (key, before) -> {
            Identity updated = update.apply(before == null ? Identity.BLANK : before, accepted);
            return updated.isBlank() ? null : updated;
        });
        changed.add(player);
        return true;
    }

    // ------------------------------------------------------------------------ the database

    /** Whether anything is waiting to be written. */
    public boolean isDirty() {
        return !changed.isEmpty();
    }

    /**
     * Reads everybody's prefix, suffix and colour.
     *
     * <p>Must be called off the server's threads.
     */
    public void load() {
        identities.clear();
        changed.clear();
        if (!database.isUsable()) {
            log.error("The identity table is not available; nobody has a prefix this session.");
            return;
        }
        boolean read = database.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT player, prefix, suffix, nametag_prefix, colour, subtitle
                    FROM identity""");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String player = rows.getString("player");
                    try {
                        Identity identity = new Identity(
                                orEmpty(rows.getString("prefix")),
                                orEmpty(rows.getString("suffix")),
                                orEmpty(rows.getString("nametag_prefix")),
                                orEmpty(rows.getString("colour")),
                                orEmpty(rows.getString("subtitle")));
                        if (!identity.isBlank()) {
                            identities.put(UUID.fromString(player), identity);
                        }
                    } catch (RuntimeException broken) {
                        // One unreadable player is one player without a prefix, not a server where
                        // nobody has one.
                        log.warn("The identity of '{}' could not be read and was skipped ({})",
                                player, broken.getMessage());
                    }
                }
            }
            return true;
        }).orElse(false);
        if (!read) {
            log.error("The identities could not be read; nobody has a prefix this session.");
        }
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * Writes whatever changed.
     *
     * <p>A player whose identity is now blank has their row deleted rather than being written as five
     * empty strings: the table is then a list of people who have something set, which is the question
     * anything reading it asks.
     *
     * <p>Must be called off the server's threads.
     */
    public void flush() {
        synchronized (flushing) {
            writeChanges();
        }
    }

    /**
     * One flush at a time. The saving timer can still be mid-flush when shutdown flushes by hand, and
     * two at once either land out of order or answer "written" while the other still holds the rows.
     */
    private final Object flushing = new Object();

    private void writeChanges() {
        if (changed.isEmpty() || !database.isUsable()) {
            return;
        }
        // Drained rather than snapshotted — see Marks. Copying the marks and clearing them
        // afterwards loses any change that arrives while the write is running.
        Set<UUID> writing = Marks.drain(changed);
        boolean written = database.write(connection -> {
            try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT INTO identity (player, prefix, suffix, nametag_prefix, colour, subtitle)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT(player) DO UPDATE SET
                        prefix = excluded.prefix, suffix = excluded.suffix,
                        nametag_prefix = excluded.nametag_prefix, colour = excluded.colour,
                        subtitle = excluded.subtitle""");
                 PreparedStatement remove =
                         connection.prepareStatement("DELETE FROM identity WHERE player = ?")) {
                for (UUID player : writing) {
                    Identity identity = identities.get(player);
                    if (identity == null) {
                        remove.setString(1, player.toString());
                        remove.executeUpdate();
                        continue;
                    }
                    upsert.setString(1, player.toString());
                    upsert.setString(2, identity.prefix());
                    upsert.setString(3, identity.suffix());
                    upsert.setString(4, identity.nametagPrefix());
                    upsert.setString(5, identity.colour());
                    upsert.setString(6, identity.subtitle());
                    upsert.executeUpdate();
                }
            }
        });
        if (!written) {
            Marks.restore(changed, writing);
        }
    }

    // ---------------------------------------------------------------------------- internals

    private Identity identityOf(UUID player) {
        return player == null ? Identity.BLANK : identities.getOrDefault(player, Identity.BLANK);
    }

    /**
     * The player's name, in their colour, as text.
     *
     * <p>{@link Component#text} rather than MiniMessage, deliberately and permanently: a name is not
     * markup, and parsing it is how a player called {@code <rainbow>} recolours everybody's chat.
     */
    private Component colouredName(String name, String colour) {
        String text = name == null ? "" : name;
        NameStyle style = NameStyle.parse(colour);
        if (style.isEmpty()) {
            return Component.text(text);
        }
        double phase = (double) Math.floorMod(clock.getAsLong(), ANIMATION_PERIOD_MS) / ANIMATION_PERIOD_MS;
        return Gradients.styled(text, style, phase);
    }

    private static Component parse(String miniMessage) {
        try {
            return MINI.deserialize(miniMessage);
        } catch (RuntimeException broken) {
            // Stored values are checked on the way in, so this is a file edited by hand. One bad
            // prefix must not stop the player being named at all.
            return Component.text(miniMessage);
        }
    }

    /**
     * Whether this is markup MiniMessage actually understands.
     *
     * <p>Not simply "does it parse": MiniMessage does not throw on a tag it has never heard of, it
     * renders it as text — so {@code <notatag>[Oops] } would be stored happily and then appear
     * literally in front of the player's name for ever. And strict mode is no use either, because it
     * insists every tag be closed, which would reject the perfectly ordinary {@code <gold>[Admin] }.
     *
     * <p>So: parse it, and see whether anything that looks like a tag survived into the rendered
     * text. If it did, MiniMessage did not recognise it, and whoever typed it should be told now
     * rather than discovering it in chat.
     */
    private static boolean isUsableMarkup(String miniMessage) {
        try {
            String rendered = PLAIN.serialize(MINI.deserialize(miniMessage));
            return !UNPARSED_TAG.matcher(rendered).find();
        } catch (RuntimeException broken) {
            return false;
        }
    }

    /** Something shaped like a tag, left over after parsing — i.e. one nothing recognised. */
    private static final Pattern UNPARSED_TAG =
            Pattern.compile("</?[a-zA-Z_][^<>]*>");

    private static boolean isColour(String colour) {
        String cleaned = colour.trim().toLowerCase(Locale.ROOT);
        return cleaned.matches("#[0-9a-f]{6}")
                || NamedTextColor.NAMES.value(cleaned) != null;
    }

    /** Cuts a nametag down to something that fits over a moving player, tags kept whole. */
    private static Component clip(Component nametag) {
        String plain = PLAIN.serialize(nametag);
        if (plain.length() <= MAX_NAMETAG_CHARS) {
            return nametag;
        }
        // Rebuilt from plain text rather than walked: a nametag long enough to need clipping is one
        // somebody has abused, and keeping its gradient intact is not worth the complexity.
        return Component.text(plain.substring(0, MAX_NAMETAG_CHARS - 1) + "…");
    }

    private static Optional<String> notEmpty(String value) {
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }
}
