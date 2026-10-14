package de.raindancer.core.moderation.punishment;

import de.raindancer.core.ui.chat.Style;
import de.raindancer.core.ui.text.Text;
import net.kyori.adventure.text.Component;
import de.raindancer.core.ui.messages.Messages;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Whether a punishment actually stops somebody, and what they are told when it does.
 *
 * <h2>Why this exists</h2>
 * Because for a while it did not, and that was the real gap. {@link Punishments} recorded a ban
 * perfectly — the reason, the length, who lifted it and when — and <em>nothing anywhere stopped a
 * banned player from joining</em>. A ban that does not ban is worse than no ban at all, because
 * everybody involved believes it worked.
 *
 * <p>It also has to live here rather than in whichever plugin owns the moderation commands. Any
 * plugin can call {@link Punishments#punish}: the claims module freezing somebody's hands while a
 * grief is investigated is a reasonable thing for it to do, and it should not have to write its own
 * listeners to make that mean anything. Written once, every plugin's punishments are enforced.
 *
 * <h2>Why the decision is separate from the listeners</h2>
 * "May this player join, and if not what do they see" is a question about a punishment and a clock.
 * The Bukkit handler that asks it is three lines and needs a server. Everything worth getting right
 * is on this side of that line, which is why this class has no Bukkit types in it beyond a
 * {@link Component}.
 *
 * <h2>Switching it off</h2>
 * A server already driving punishments from somewhere else can turn enforcement off, per kind or
 * altogether, and keep the record. The record is useful on its own — it is the history a moderator
 * reads — so recording and enforcing are deliberately separate switches.
 */
public final class PunishmentGuard {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Punishments punishments;
    private final LongSupplier clock;

    /** Which kinds are actually acted on. All of the lasting ones, until somebody says otherwise. */
    private final Set<PunishmentKind> enforced =
            EnumSet.of(PunishmentKind.BAN, PunishmentKind.MUTE, PunishmentKind.FREEZE);

    private volatile boolean enabled = true;
    private volatile String appealMessage = "";

    /**
     * Where the wording comes from.
     *
     * <p>Null until a server hands one over, and the wording built in below is used until then — so a
     * plugin that only wants the rules does not have to set up a message file to get a working ban
     * screen, and a test does not need one either.
     */
    private volatile Messages messages;

    public PunishmentGuard(Punishments punishments, LongSupplier clock) {
        this.punishments = punishments;
        this.clock = clock;
    }

    /** Tells this where the wording lives. */
    public void messages(Messages messages) {
        this.messages = messages;
    }

    // ---------------------------------------------------------------------------- settings

    /** Whether punishments are acted on at all. Off still records everything. */
    public void enabled(boolean on) {
        this.enabled = on;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Whether one kind is acted on. */
    public synchronized void enforce(PunishmentKind kind, boolean on) {
        if (kind == null) {
            return;
        }
        if (on) {
            enforced.add(kind);
        } else {
            enforced.remove(kind);
        }
    }

    public synchronized boolean isEnforced(PunishmentKind kind) {
        return enabled && kind != null && enforced.contains(kind);
    }

    /**
     * The line under a ban telling somebody how to appeal.
     *
     * <p>Left out entirely when empty, rather than shown as a blank line: a ban screen with an
     * empty gap where the appeal should be reads as something that failed to load.
     */
    public void appealMessage(String message) {
        this.appealMessage = message == null ? "" : message.trim();
    }

    // ---------------------------------------------------------------------------- the questions

    /** Whether this player may join. */
    public boolean mayJoin(UUID player) {
        return stopping(player, PunishmentKind.BAN).isEmpty();
    }

    /** Whether they may talk. */
    public boolean maySpeak(UUID player) {
        return stopping(player, PunishmentKind.MUTE).isEmpty();
    }

    /** Whether they may change the world around them. */
    public boolean mayBuild(UUID player) {
        return stopping(player, PunishmentKind.FREEZE).isEmpty();
    }

    /** The screen a banned player is shown, or empty when they may join. */
    public Optional<Component> joinRefusal(UUID player) {
        return stopping(player, PunishmentKind.BAN).map(this::banScreen);
    }

    /** What a muted player is told when they try to talk. */
    public Optional<Component> speakRefusal(UUID player) {
        return stopping(player, PunishmentKind.MUTE)
                .map(punishment -> oneLine("punishment.muted", "You are muted", punishment));
    }

    /** What a frozen player is told when they try to build. */
    public Optional<Component> buildRefusal(UUID player) {
        return stopping(player, PunishmentKind.FREEZE)
                .map(punishment -> oneLine("punishment.frozen", "You cannot build right now",
                        punishment));
    }

    /**
     * Reads the player's record from the database rather than trusting this server's copy — for a join,
     * where the answer must be the network's even if the event about a ban given elsewhere never came.
     * Off the server's threads (the pre-login event already is).
     */
    public void readFromDatabase(UUID player) {
        punishments.refresh(player);
    }

    /** The punishment stopping this player, if one is and it is being enforced. */
    public Optional<Punishment> stopping(UUID player, PunishmentKind kind) {
        if (player == null || !isEnforced(kind)) {
            return Optional.empty();
        }
        return punishments.active(player, kind);
    }

    // ---------------------------------------------------------------------------- the words

    /**
     * The full screen a banned player is disconnected with.
     *
     * <p>Says why and how much longer, in that order, because those are the two things somebody
     * actually wants — and how long <em>is left</em>, not when it started: "2 days" is useful and a
     * timestamp in the server's timezone is not.
     */
    private Component banScreen(Punishment ban) {
        Messages words = messages;
        if (words == null) {
            return builtInBanScreen(ban);
        }
        StringBuilder built = new StringBuilder();
        append(built, words, "punishment.banned.heading");
        built.append("\n\n");
        append(built, words, "punishment.banned.reason", "reason", ban.reason());
        built.append('\n');
        if (ban.isPermanent()) {
            append(built, words, "punishment.banned.permanent");
        } else {
            append(built, words, "punishment.banned.temporary", "time", remaining(ban));
        }
        if (!appealMessage.isEmpty()) {
            built.append("\n\n");
            append(built, words, "punishment.appeal", "message", appealMessage);
        }
        return MINI.deserialize(built.toString());
    }

    /**
     * The ban screen with no message file behind it.
     *
     * <p>Kept rather than deleted: a plugin using only the rules must still get a usable screen, and
     * "the wording is missing" is not something to tell a player who is being refused entry.
     */
    private Component builtInBanScreen(Punishment ban) {
        StringBuilder built = new StringBuilder();
        built.append("<").append(Style.bad()).append("><b>You are banned from this server.</b>\n\n");
        built.append("<gray>").append(escape(ban.reason())).append("\n");
        built.append("<dark_gray>");
        if (ban.isPermanent()) {
            built.append("This ban does not expire.");
        } else {
            built.append("Time left: ").append(remaining(ban));
        }
        if (!appealMessage.isEmpty()) {
            built.append("\n\n<gray>").append(escape(appealMessage));
        }
        return MINI.deserialize(built.toString());
    }

    /**
     * One line of a message file, appended as markup rather than as a rendered component.
     *
     * <p>Because the ban screen is one component built out of several lines, and MiniMessage has to
     * see the whole thing at once for a tag opened on one line and closed on the next to work.
     */
    private static void append(StringBuilder into, Messages words, String key, Object... values) {
        into.append(fillIn(words, key, values));
    }

    /**
     * A message's markup with its values escaped into it.
     *
     * <p>The escaping is the point, and is the reason this cannot simply concatenate: a reason a
     * moderator typed is text somebody chose, being pasted into markup. Without it a reason of
     * {@code <red>} recolours the rest of the screen and an unclosed tag swallows what follows.
     */
    private static String fillIn(Messages words, String key, Object... values) {
        return Text.fill(words.raw(key), values);
    }

    /** What a muted or frozen player is told, in one line of chat. */
    private Component oneLine(String key, String builtIn, Punishment punishment) {
        Messages words = messages;
        if (words == null) {
            return builtInOneLine(builtIn, punishment);
        }
        String wanted = punishment.isPermanent() ? key : key + "-temporary";
        return MINI.deserialize(fillIn(words, wanted,
                "reason", punishment.reason(),
                "time", remaining(punishment)));
    }

    private Component builtInOneLine(String what, Punishment punishment) {
        StringBuilder built = new StringBuilder("<").append(Style.bad()).append('>')
                .append(what).append(": <gray>").append(escape(punishment.reason()));
        if (!punishment.isPermanent()) {
            built.append(" <dark_gray>(").append(remaining(punishment)).append(" left)");
        }
        return MINI.deserialize(built.toString());
    }

    private String remaining(Punishment punishment) {
        return punishment.remainingAt(Instant.ofEpochMilli(clock.getAsLong()))
                .map(Durations::describe)
                .orElse("for ever");
    }

    /**
     * Keeps a moderator's typed reason out of the markup.
     *
     * <p>A reason is text somebody wrote in a command, being pasted into MiniMessage. Without this
     * a reason of {@code <red>} would recolour the rest of the screen, and an unclosed tag would
     * swallow the appeal line.
     */
    private static String escape(String raw) {
        return Text.literal(raw);
    }
}
