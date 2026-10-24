package de.raindancer.core.platform.command;

import de.raindancer.core.ui.chat.ClickCommand;
import de.raindancer.core.data.settings.SettingsCommand;
import de.raindancer.core.ui.profile.ProfileCommand;
import de.raindancer.core.ui.profile.ProfileLink;
import de.raindancer.core.world.manage.WorldCommand;
import io.papermc.paper.command.brigadier.Commands;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ready-made commands for the things Core knows about — none of which Core registers.
 *
 * <h2>Why Core registers nothing</h2>
 * Because a library that takes {@code /warp} for itself has decided something that is not its to
 * decide. A server may already have a warp plugin, may want the settings behind a different name,
 * or may want none of them. Core's job is to make writing those commands a line rather than a
 * weekend; owning the names is a different job, and one nobody asked it to do.
 *
 * <p>So the handlers live here as building blocks, and a plugin registers whichever it wants:
 *
 * <pre>{@code
 * public final class MyBootstrap implements PluginBootstrap {
 *     public void bootstrap(BootstrapContext context) {
 *         context.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
 *             CoreCommands.clickCallback(event.registrar());   // see below — buttons need this
 *             CoreCommands.settings(event.registrar(), "settings");
 *         });
 *     }
 * }
 * }</pre>
 *
 * <h2>The one that is not really a command</h2>
 * {@link #clickCallback} is different in kind. A clickable thing in chat can only do one of three
 * things — open a URL, put text in the box, or run a command — so a button with a server-side
 * callback is a command by necessity, not by choice. Nobody types it and it takes an opaque token.
 * Without it registered somewhere, {@code buttons()} still produces readable text but nothing is
 * clickable, and Core says so once rather than leaving you wondering.
 *
 * <h2>Register these in a bootstrapper, not in onEnable</h2>
 * Paper fires the {@code COMMANDS} lifecycle event during the bootstrap phase. A handler registered
 * in {@code onEnable} is registered after that has already happened, so it never runs — with no
 * warning, no exception, and no line in the log. The command simply does not exist and
 * {@code dispatchCommand} answers false as though nobody had ever heard of it.
 *
 * <p>That is not a theoretical footnote. Core itself was written that way, and every chat button in
 * the library was dead on a real server for weeks: the callback registry worked perfectly and the
 * command it pointed at was not there. Nothing below the server line could have caught it, because
 * the machinery was right and only the registration was in the wrong place.
 */
public final class CoreCommands {

    private CoreCommands() {
    }

    /**
     * The callback command chat buttons need.
     *
     * <p>Not a command anybody types — it takes a token and runs whatever was registered against it.
     * Register it under a name nothing else uses and tell {@code buttons()} what you called it, or
     * take the default of {@code rcclick} and leave the button helper alone.
     *
     * @param name what to call it, without a slash
     */
    public static void clickCallback(Commands registrar, String name) {
        registrar.register(name, "Runs a button you clicked in chat.", new ClickCommand());
    }

    /** The same, as {@code rcclick} — which is what {@code buttons()} expects by default. */
    public static void clickCallback(Commands registrar) {
        clickCallback(registrar, "rcclick");
    }

    /**
     * The command a clicked player name opens their profile through — see {@link ProfileLink} and
     * {@code ProfileMenu}. Same shape as {@link #clickCallback}, and the same reason: a clickable
     * thing in chat can only run a command, so this has to exist before any name in chat is clickable
     * at all, whatever module put it there.
     */
    public static void profile(Commands registrar, String name) {
        registrar.register(name, "Opens a player's profile. Not meant to be typed.", new ProfileCommand());
    }

    /** The same, as {@link ProfileLink#COMMAND} — which is what {@code ProfileLink} expects. */
    public static void profile(Commands registrar) {
        profile(registrar, ProfileLink.COMMAND);
    }

    /** Reading and changing every plugin's settings, from chat. */
    public static void settings(Commands registrar, String name, String... aliases) {
        registrar.register(name, "Everything every plugin on this server can be told to do.",
                List.of(aliases), new SettingsCommand());
    }

    /** Whether {@code /commands} has already been taken this run. See {@link #commandList}. */
    private static final AtomicBoolean DIRECTORY_TAKEN =
            new AtomicBoolean();

    /**
     * The directory of every command on this server, as a book.
     *
     * <p>The one command in this class that <b>declines a second registration</b>, and it has to. Every
     * standalone module plugin registers it — none of them can know whether another is installed — and
     * Paper answers a clash by namespacing the loser, so a server with six of them would list
     * {@code /commands}, {@code /rainswarps:commands} and four more in its help, all showing the same
     * book. It is the same book because the directory is Core's: the first registration is complete on
     * its own, and the rest are noise.
     *
     * <p>This class's static state is shared across all of them precisely because it is Core's —
     * {@code join-classpath: true} means one loaded copy of this class for every dependent plugin.
     *
     * @return whether this call is the one that registered it
     */
    public static boolean commandList(Commands registrar, String name, String... aliases) {
        if (!DIRECTORY_TAKEN.compareAndSet(false, true)) {
            return false;
        }
        registrar.register(name, "Every command on this server, in a book.", List.of(aliases),
                new CommandsCommand());
        return true;
    }

    /** The same, as {@code /commands}. */
    public static boolean commandList(Commands registrar) {
        return commandList(registrar, "commands", "cmds");
    }

    /**
     * Switching to any loaded world, and wiping one and making it again — {@code rainscore.world.switch}
     * and {@code rainscore.world.regen}, each falling back to Bukkit's own operator-only default since
     * Core declares no permissions of its own. See {@link WorldCommand} for a plain "wipe this one
     * world" — a farm world's own {@code /farmworld} is farmworld-module's now, built on Core's
     * {@link de.raindancer.core.world.manage.WorldRegenerator} the same way this command is.
     */
    private static final AtomicBoolean PREFIX_TAKEN = new AtomicBoolean();

    /**
     * {@code /prefix}: how every plugin signs its messages. Taken by the first plugin that asks and
     * declined for the rest, like {@link #commandList}, because there is one prefix for the server.
     */
    public static boolean prefix(Commands registrar, String name, String... aliases) {
        if (!PREFIX_TAKEN.compareAndSet(false, true)) {
            return false;
        }
        registrar.register(name, "How every plugin signs its messages: one tag or each its own, in any colours.",
                List.of(aliases), new de.raindancer.core.ui.chat.PrefixCommand());
        return true;
    }

    public static boolean prefix(Commands registrar) {
        return prefix(registrar, "prefix");
    }

    private static final AtomicBoolean CHANGELOG_TAKEN = new AtomicBoolean();

    /**
     * {@code /changelog}: what changed lately, and for staff drafting and publishing it. Taken by the first
     * plugin that asks, like {@link #commandList}, because there is one changelog for the server.
     */
    public static boolean changelog(Commands registrar, String name, String... aliases) {
        if (!CHANGELOG_TAKEN.compareAndSet(false, true)) {
            return false;
        }
        registrar.register(name, "What changed on this server lately.", List.of(aliases),
                new de.raindancer.core.ui.changelog.ChangelogCommand());
        return true;
    }

    public static boolean changelog(Commands registrar) {
        return changelog(registrar, "changelog", "news");
    }

    private static final AtomicBoolean HELP_TAKEN = new AtomicBoolean();

    /**
     * {@code /help}: the guide — every feature here explained as this server has it set up, in dialog windows. One
     * per server, so taken by the first plugin that asks, like {@link #commandList}. Replaces Bukkit's own
     * {@code /help}, which lists command names and nothing a player can use.
     */
    public static boolean help(Commands registrar, String name, String... aliases) {
        if (!HELP_TAKEN.compareAndSet(false, true)) {
            return false;
        }
        registrar.register(name, de.raindancer.core.ui.help.HelpCommand.DESCRIPTION, List.of(aliases),
                new de.raindancer.core.ui.help.HelpCommand());
        return true;
    }

    public static boolean help(Commands registrar) {
        return help(registrar, "help", "?", "guide");
    }

    private static final AtomicBoolean HUB_TAKEN = new AtomicBoolean();

    /**
     * {@code /hub} (alias {@code /smp}): the front door to everything installed. One per server, so taken by
     * the first plugin that asks, like {@link #commandList}.
     */
    public static boolean hub(Commands registrar, String name, String... aliases) {
        if (!HUB_TAKEN.compareAndSet(false, true)) {
            return false;
        }
        registrar.register(name, "The server's hub: every feature installed, and how to use it.",
                List.of(aliases), new de.raindancer.core.ui.hub.HubCommand());
        return true;
    }

    public static boolean hub(Commands registrar) {
        return hub(registrar, "hub", "smp");
    }

    private static final AtomicBoolean MAINTENANCE_TAKEN = new AtomicBoolean();

    /** {@code /maintenance}: one per server, so taken by the first plugin that asks. */
    public static boolean maintenance(Commands registrar, String name, String... aliases) {
        if (!MAINTENANCE_TAKEN.compareAndSet(false, true)) {
            return false;
        }
        registrar.register(name, "Close the server for maintenance: only ops and its own list can join.",
                List.of(aliases), new de.raindancer.core.moderation.maintenance.MaintenanceCommand());
        return true;
    }

    public static boolean maintenance(Commands registrar) {
        return maintenance(registrar, "maintenance", "wartung", "wartungsmodus");
    }

    public static void worlds(Commands registrar, String name, String... aliases) {
        registrar.register(name, "Switch to a loaded world, or wipe one and make it again.",
                List.of(aliases), new WorldCommand());
    }

    private static final AtomicBoolean DOCUMENTS_TAKEN = new AtomicBoolean();

    /**
     * {@code /documents} (alias {@code /files}): export a document — what used to be a plugin's YAML file —
     * to edit it by hand, and import it back. One per server, taken by the first plugin that asks.
     */
    public static boolean documents(Commands registrar) {
        if (!DOCUMENTS_TAKEN.compareAndSet(false, true)) {
            return false;
        }
        registrar.register("documents", "Edit a plugin's settings or texts by hand: export, edit, import.",
                List.of("files"), new de.raindancer.core.data.store.DocumentsCommand());
        return true;
    }

    private static final AtomicBoolean NETWORK_TAKEN = new AtomicBoolean();

    /**
     * {@code /network}: the network's servers and who is on them, and which server is the bank
     * ({@code /network bank here} moves it) — and with it {@code /friend}, {@code /party} and {@code /pc}. One per
     * server, taken by the first plugin that asks.
     */
    public static boolean network(Commands registrar) {
        if (!NETWORK_TAKEN.compareAndSet(false, true)) {
            return false;
        }
        registrar.register("network", "The network's servers, and which one keeps the money.",
                List.of(), new de.raindancer.core.platform.network.NetworkCommand());
        // Friends and parties are the network's too: whoever brings /network brings them, on every server alike.
        registrar.register("friend", "Friends across the network: add, accept, list, join.",
                List.of("friends", "f"), new de.raindancer.core.social.friends.FriendCommand());
        registrar.register("party", "A party across the network that follows its leader.",
                List.of("p"), new de.raindancer.core.social.party.PartyCommand(false));
        registrar.register("pc", "Say something to your party.", List.of(),
                new de.raindancer.core.social.party.PartyCommand(true));
        return true;
    }
}
