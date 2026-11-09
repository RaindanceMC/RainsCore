package de.raindancer.core.ui.chat;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.Collection;

/**
 * Everything a plugin here says in chat, and the only way it says it.
 *
 * <h2>Why this exists</h2>
 * There were five of these: {@code Notifier}, claims' {@code Messages}, RRP's {@code Msg}, and a
 * {@code Chrome} class copied three times between homes, TPA and the ghast lines. Each had its own
 * idea of what a prefix was and which of them escaped player-supplied text before pasting it into
 * MiniMessage — the last being a formatting injection waiting to happen, since a home called
 * {@code <red>} was being parsed as markup by two of the five.
 *
 * <h2>This is chat. The action bar is somewhere else.</h2>
 * An earlier version of this class routed "personal" messages to the action bar behind a single
 * server-wide setting, inherited from {@code smpcore.util.Feedback}. That was wrong twice over: one
 * boolean cannot decide for every message a plugin sends, and most messages do not belong above the
 * hotbar at all. The rule now is a question the caller answers at the call site — <b>does this still
 * matter a second later?</b>
 *
 * <table border="1">
 *   <caption>Where a message goes</caption>
 *   <tr><th>{@link de.raindancer.core.ui.actionbar.ActionBars}</th><th>Here</th></tr>
 *   <tr><td>A teleport countdown, a cast bar, progress</td>
 *       <td>"Home set", "Request sent to Bentex_OG"</td></tr>
 *   <tr><td>Flight commentary while a ghast is in the air</td>
 *       <td>A refusal the player may want to read twice</td></tr>
 *   <tr><td>"You have entered Raindancer118's claim"</td>
 *       <td>Lists, headings, manuals — anything with rows</td></tr>
 *   <tr><td>A warning about where the player is standing now</td>
 *       <td>Anything naming another player who might reply</td></tr>
 * </table>
 *
 * <h2>Untrusted text</h2>
 * Anything a player typed — a home name, a claim name, a pack's title, another plugin's error —
 * goes in through {@link #arg}, never concatenated into the template. {@link #arg} uses
 * {@link Placeholder#unparsed}, so a claim called {@code <rainbow>} shows up as those nine
 * characters instead of recolouring the rest of the line, and an unclosed tag cannot swallow the
 * message it was pasted into.
 *
 * <h2>Thread safety</h2>
 * Building a component is safe from any thread, and so is sending: nothing here touches the world,
 * so none of it needs a region thread.
 */
public final class Chat {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Brand brand;
    private final Audiences audiences;

    /**
     * @param brand     this plugin's name, as a player sees it
     * @param audiences where "everybody" and "the console" come from
     */
    public Chat(Brand brand, Audiences audiences) {
        this.brand = brand == null ? new Brand("Rain") : brand;
        this.audiences = audiences;
    }

    public Brand brand() {
        return brand;
    }

    // ------------------------------------------------------------------ safe placeholders

    /**
     * One piece of untrusted text, safe to put in a message.
     *
     * <p>Use it for everything a player or a remote server produced. The value is inserted as text,
     * never as markup.
     */
    public static TagResolver arg(String name, Object value) {
        return Placeholder.unparsed(name, value == null ? "" : String.valueOf(value));
    }

    /**
     * A piece of text that is <em>meant</em> to carry formatting — a heading built elsewhere, a
     * player's coloured display name.
     *
     * <p>Deliberately harder to reach for than {@link #arg}, and deliberately named for what it
     * costs: everything passed here is parsed. Never hand it something a player typed.
     */
    public static TagResolver formatted(String name, Component value) {
        return Placeholder.component(name, value == null ? Component.empty() : value);
    }

    // ---------------------------------------------------------------------- building

    /** A component from MiniMessage, with no prefix — for a line that goes inside something else. */
    public Component mm(String miniMessage, TagResolver... arguments) {
        return MINI.deserialize(miniMessage == null ? "" : miniMessage, arguments);
    }

    /** The same, signed with this plugin's tag. */
    public Component prefixed(String miniMessage, TagResolver... arguments) {
        return MINI.deserialize(brand.chatPrefix() + (miniMessage == null ? "" : miniMessage),
                arguments);
    }

    // ----------------------------------------------------------------------- to one person

    /**
     * Something this recipient should read: an answer, a confirmation, a refusal.
     *
     * <p>Plain — no colour of its own. For the three that mean something use {@link #ok},
     * {@link #warn} or {@link #no}.
     */
    public void tell(Audience recipient, String miniMessage, TagResolver... arguments) {
        if (recipient != null) {
            recipient.sendMessage(CommandButtons.bare(prefixed(miniMessage, arguments),
                    CommandButtons.drawnFor(recipient)));
        }
    }

    /** It worked. */
    public void ok(Audience recipient, String miniMessage, TagResolver... arguments) {
        tell(recipient, colour(Style.ok(), miniMessage), arguments);
    }

    /** It worked, but be careful — or it half worked. */
    public void warn(Audience recipient, String miniMessage, TagResolver... arguments) {
        tell(recipient, colour(Style.warn(), miniMessage), arguments);
    }

    /**
     * No.
     *
     * <p>Named {@code no} rather than {@code error} because that is what it is used for nine times
     * out of ten — a refusal the player caused, not a fault. A fault they should know about is still
     * this; a fault they should not is {@link de.raindancer.core.platform.log.Log}'s business.
     */
    public void no(Audience recipient, String miniMessage, TagResolver... arguments) {
        tell(recipient, colour(Style.bad(), miniMessage), arguments);
    }

    /**
     * A line of a list, with no tag in front of it.
     *
     * <p>A twelve-row list with the plugin's tag on every row is twelve tags and one list. The
     * heading carries the tag; the rows are indented under it.
     */
    public void row(Audience recipient, String miniMessage, TagResolver... arguments) {
        if (recipient != null) {
            recipient.sendMessage(CommandButtons.bare(mm(miniMessage, arguments), CommandButtons.drawnFor(recipient)));
        }
    }

    /** A finished component, unchanged and unprefixed — for a caller that built its own. */
    public void raw(Audience recipient, Component message) {
        if (recipient != null && message != null) {
            recipient.sendMessage(message);
        }
    }

    /** An empty line, for separating paragraphs. */
    public void blank(Audience recipient) {
        if (recipient != null) {
            recipient.sendMessage(Component.empty());
        }
    }

    // ------------------------------------------------------------------------ to everybody

    /** Everyone who can be spoken to. */
    public void broadcast(String miniMessage, TagResolver... arguments) {
        send(audiences == null ? null : audiences.everyone(), prefixed(miniMessage, arguments));
    }

    /** A chosen few — a town's council, the online moderators. */
    public void broadcast(Collection<? extends Audience> recipients, String miniMessage,
                          TagResolver... arguments) {
        send(recipients, prefixed(miniMessage, arguments));
    }

    /**
     * The server console.
     *
     * <p>Distinct from {@link de.raindancer.core.platform.log.Log}: this is a message <em>to the operator</em>
     * — the result of a command they typed at the console — not a record of something that happened.
     * Anything worth keeping goes through the logger instead, and lands in a file.
     */
    public void console(String miniMessage, TagResolver... arguments) {
        if (audiences != null) {
            raw(audiences.console(), prefixed(miniMessage, arguments));
        }
    }

    private static void send(Collection<? extends Audience> recipients, Component message) {
        if (recipients == null) {
            return;
        }
        for (Audience recipient : recipients) {
            if (recipient != null) {
                recipient.sendMessage(message);
            }
        }
    }

    /**
     * Wraps a message in one of the palette's colours.
     *
     * <p>Around the whole message rather than merged into it, so a template that colours a word of
     * its own keeps that word's colour and everything else takes the outer one.
     */
    private static String colour(String colour, String miniMessage) {
        return "<" + colour + ">" + (miniMessage == null ? "" : miniMessage) + "</" + colour + ">";
    }
}
