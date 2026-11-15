package de.raindancer.core.moderation.invsee;

import de.raindancer.core.data.nbt.ItemBytes;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.moderation.audit.Audit;
import de.raindancer.core.moderation.audit.AuditEntry;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.platform.util.Scheduling;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Looking inside somebody's inventory — online or not.
 *
 * <h2>What this class is for</h2>
 * The three things that make an invsee safe live in three separate classes, each testable without a
 * server: who may edit ({@link InventoryViews}), what happens to an offline edit when its owner logs
 * back in ({@link OfflineEdits}), and where the items come from ({@link InventorySource}). This is
 * the one place that knows all three, and the only place that has to decide which kind of player is
 * being looked at.
 *
 * <h2>Why the offline half exists at all</h2>
 * Because the players a moderator most needs to look at are exactly the ones who have logged out.
 * "Only while they are online" sounds like a small gap and is the half that matters — somebody
 * reported after they left, a duplication traced through the people who received the items,
 * belongings to give back to a returning player.
 *
 * <p>Nobody is ever stopped from joining to make that work. If somebody logs in while their saved
 * inventory is open in a window, the window shuts and the edit is dropped unwritten — see
 * {@link #somebodyJoined}.
 */
public final class Inventories {

    private static final LogChannel log = Log.of("invsee");

    /**
     * What happened when somebody tried to look.
     *
     * <p>Each one carries the <em>key</em> of what to say rather than the sentence, so the wording
     * lives in {@code messages.yml} with everything else and this enum stays about outcomes. The
     * English beside it is the fallback for a server with no message file — see
     * {@link #saying(de.raindancer.core.ui.messages.Messages)}.
     */
    public enum Outcome {

        OPENED("invsee.opened", "Opened."),
        YOURSELF("invsee.yourself", "Your own inventory is a key rather than a menu."),
        BEING_EDITED("invsee.being-edited", "Somebody else is editing that inventory."),
        NEVER_SEEN("invsee.never-seen", "This server has never seen that player."),
        UNREADABLE("invsee.unreadable", "Their inventory could not be read. The log says why."),
        NOT_ALLOWED("invsee.not-allowed", "You may not do that."),
        SWITCHED_OFF("invsee.switched-off", "Looking inside inventories is switched off on this server.");

        private final String key;
        private final String builtIn;

        Outcome(String key, String builtIn) {
            this.key = key;
            this.builtIn = builtIn;
        }

        /** Which line of the message file this is. */
        public String key() {
            return key;
        }

        /** What to tell the moderator, in whatever words this server uses. */
        public Component saying(Messages words) {
            return words == null ? Component.text(builtIn) : words.prefixed(key);
        }

        /** The built-in wording, for a caller with no message file at all. */
        public String saying() {
            return builtIn;
        }

        public boolean opened() {
            return this == OPENED;
        }
    }

    private final Plugin plugin;
    private final InventoryViews views;
    private final OfflineEdits offlineEdits;
    private final InventorySource online;
    private final PlayerDataInventorySource saved;
    /** Whether somebody is on the server — a seam, so the decision is not a static call. */
    private final Predicate<UUID> isOnline;
    /** Owners whose window has closed and whose file is being written right now. */
    private final Set<UUID> writing = ConcurrentHashMap.newKeySet();

    /**
     * Where every look and every change is written down.
     *
     * <p>Not optional. Being able to open anybody's inventory and take things out of it is the most
     * abusable power on a server, and the only thing that makes it safe to hand out is that using it
     * leaves a trace somebody else can read.
     */
    private Audit audit = null;

    /** Where the wording lives. Null on a server that has not set one up. */
    private Messages messages = null;

    /** Whether inventories can be watched at all. On by default — see {@link #enabled(boolean)}. */
    private volatile boolean enabled = true;
    /** Whether a watcher may change anything, rather than only look. */
    private volatile boolean allowEditing = true;
    /** Whether a watcher granted editing may also touch armour and the off-hand. */
    private volatile boolean allowEquipment = true;
    /** Whether the ender chest is offered beside the inventory. */
    private volatile boolean showEnderChest = true;

    public Inventories(Plugin plugin, InventoryViews views, OfflineEdits offlineEdits,
                       Path playerData) {
        this(plugin, views, offlineEdits, java.util.List.of(playerData));
    }

    /** @param playerData every folder a save file may be in, most likely first — see {@link PlayerDataInventorySource} */
    public Inventories(Plugin plugin, InventoryViews views, OfflineEdits offlineEdits,
                       java.util.List<Path> playerData) {
        this(plugin, views, offlineEdits, new OnlineInventorySource(),
                new PlayerDataInventorySource(playerData, new ItemBytes.OfTheServer()),
                who -> Bukkit.getPlayer(who) != null);
    }

    public Inventories(Plugin plugin, InventoryViews views, OfflineEdits offlineEdits,
                       InventorySource online, PlayerDataInventorySource saved,
                       Predicate<UUID> isOnline) {
        this.plugin = plugin;
        this.views = views;
        this.offlineEdits = offlineEdits;
        this.online = online;
        this.saved = saved;
        this.isOnline = isOnline;
    }

    // ------------------------------------------------------------------------------- looking

    /** Which source somebody's items come from right now. */
    public InventorySource sourceFor(UUID who) {
        return isOnline.test(who) ? online : saved;
    }

    /** What somebody is carrying, wherever it has to be read from. */
    public Optional<Carried<ItemStack>> read(UUID who) {
        return who == null ? Optional.empty() : sourceFor(who).read(who);
    }

    /** Whether this server has ever saved them — the honest form of "does this player exist". */
    public boolean knows(UUID who) {
        return who != null && (isOnline.test(who) || saved.has(who));
    }

    /**
     * Opens a window onto somebody.
     *
     * <p>Answered through a callback rather than returned, because reading a logged-out player
     * means reading and un-gzipping a file, and doing that on the thread a command arrived on is a
     * stall on the thread that is also running the world. Everything that can be decided without
     * touching the disk is decided immediately; the rest happens off the server's threads and comes
     * back on the moderator's own.
     *
     * <p>The order of the two locks matters. The file is claimed <em>before</em> it is read, so that
     * a player joining at any point from here on is noticed and the edit yields to them rather than
     * being written over the top of a player the server has since loaded.
     *
     * @param then told what happened, always on the moderator's own thread
     */
    public void open(Player watcher, UUID owner, String ownerName, Access wanted,
                     Consumer<Outcome> then) {
        Consumer<Outcome> answer = then == null ? outcome -> { } : then;
        if (!enabled) {
            answer.accept(Outcome.SWITCHED_OFF);
            return;
        }
        if (watcher == null || owner == null) {
            answer.accept(Outcome.NOT_ALLOWED);
            return;
        }
        if (watcher.getUniqueId().equals(owner)) {
            answer.accept(Outcome.YOURSELF);
            return;
        }
        Access level = capToSettings(wanted == null ? Access.READ_ONLY : wanted);
        boolean live = isOnline.test(owner);
        if (live) {
            // Nothing to read from disk: it is all in memory, and it has to be read on the thread
            // that owns the player anyway.
            finishOpening(watcher, owner, ownerName, level, true, online.read(owner), answer);
            return;
        }
        if (!saved.has(owner)) {
            // They may have just quit: the file this checks for is written when they leave, and on
            // Folia that write happens on a save thread rather than finishing before the next packet
            // from a moderator is even read. A name that got this far already came from the server's
            // own cache of who it has seen, so "never seen" is not yet the honest answer — give the
            // write a moment to land before it is.
            Scheduling.async(plugin, () -> awaitSaveFile(watcher, owner, ownerName, level, answer,
                    NEVER_SEEN_RETRIES));
            return;
        }
        openSaved(watcher, owner, ownerName, level, answer);
    }

    /** How long to give a just-written playerdata file to appear before believing it never will. */
    private static final int NEVER_SEEN_RETRIES = 5;
    private static final long NEVER_SEEN_RETRY_DELAY_MS = 200L;

    /**
     * Off-thread: polls for the file a quit save writes, rather than trusting the first look.
     *
     * <p>Rescheduled rather than slept through — a thread pool sized for quick disk reads is not
     * somewhere to hold one open doing nothing, and {@code Thread.sleep} on it is exactly that.
     */
    private void awaitSaveFile(Player watcher, UUID owner, String ownerName, Access level,
                               Consumer<Outcome> answer, int attemptsLeft) {
        if (saved.has(owner)) {
            Scheduling.entity(plugin, watcher, () -> openSaved(watcher, owner, ownerName, level, answer));
            return;
        }
        if (attemptsLeft <= 0) {
            log.warn("{} tried to invsee {} ({}), but no save file ever appeared at {} after {} "
                    + "attempts, {}ms apart. Either they were never saved under this id, or their "
                    + "data lives somewhere this is not looking.", watcher.getName(),
                    nameOf(owner, ownerName), owner, saved.fileFor(owner), NEVER_SEEN_RETRIES,
                    NEVER_SEEN_RETRY_DELAY_MS);
            Scheduling.entity(plugin, watcher, () -> answer.accept(Outcome.NEVER_SEEN));
            return;
        }
        Scheduling.asyncLater(plugin, NEVER_SEEN_RETRY_DELAY_MS, TimeUnit.MILLISECONDS,
                () -> awaitSaveFile(watcher, owner, ownerName, level, answer, attemptsLeft - 1));
    }

    /** The offline half of {@link #open}, once the save file is known to be there. */
    private void openSaved(Player watcher, UUID owner, String ownerName, Access level,
                           Consumer<Outcome> answer) {
        if (level.canEdit() && !offlineEdits.begin(owner, watcher.getUniqueId())) {
            answer.accept(Outcome.BEING_EDITED);
            return;
        }
        Scheduling.async(plugin, () -> {
            Optional<Carried<ItemStack>> carried = saved.read(owner);
            Scheduling.entity(plugin, watcher, () ->
                    finishOpening(watcher, owner, ownerName, level, false, carried, answer));
        });
    }

    /**
     * The server's own ceiling on what a watcher gets, whatever the caller asked for.
     *
     * <p>Applied here rather than trusted to every caller: a command that checks a permission for
     * {@code EDIT} has no way to know the server turned editing off entirely afterwards, and a second
     * copy of that check in every caller is the usual way one of them forgets it.
     */
    Access capToSettings(Access wanted) {
        if (!allowEditing) {
            return Access.READ_ONLY;
        }
        if (wanted == Access.EDIT_EVERYTHING && !allowEquipment) {
            return Access.EDIT;
        }
        return wanted;
    }

    /** The half that has to happen on the moderator's thread: opening the window. */
    private void finishOpening(Player watcher, UUID owner, String ownerName, Access level,
                               boolean live, Optional<Carried<ItemStack>> carried,
                               Consumer<Outcome> answer) {
        if (carried.isEmpty()) {
            offlineEdits.finish(owner, watcher.getUniqueId());
            answer.accept(Outcome.UNREADABLE);
            return;
        }
        if (!watcher.isOnline()) {
            // They left while their file was being read. Nothing to open, and the hold has to go.
            offlineEdits.finish(owner, watcher.getUniqueId());
            answer.accept(Outcome.NOT_ALLOWED);
            return;
        }
        if (!views.open(watcher.getUniqueId(), owner, level)) {
            offlineEdits.finish(owner, watcher.getUniqueId());
            answer.accept(Outcome.BEING_EDITED);
            return;
        }
        InventoryWindow window = new InventoryWindow(plugin, watcher, owner,
                nameOf(owner, ownerName), level, live, sourceFor(owner), carried.get());
        window.audit(audit);
        window.messages(messages);
        window.enderChest(showEnderChest);
        window.open();
        log.info("{} is {} the inventory of {} ({}).", watcher.getName(),
                level.saying().toLowerCase(), nameOf(owner, ownerName),
                live ? "online" : "from their save file");
        // Recorded on opening rather than only on changing: looking through somebody's belongings is
        // itself the thing a player would want to know had happened, whether or not anything moved.
        record(AuditEntry.of("invsee", "looked inside")
                .by(watcher.getUniqueId(), watcher.getName())
                .to(owner, nameOf(owner, ownerName))
                .saying(level.saying(messages) + (live ? ", online" : ", from their save file"))
                .with("access", level.name())
                .with("source", live ? "live" : "playerdata"));
        answer.accept(Outcome.OPENED);
    }

    private String nameOf(UUID who, String given) {
        if (given != null && !given.isBlank()) {
            return given;
        }
        OfflinePlayer known = Bukkit.getOfflinePlayer(who);
        return known.getName() == null ? who.toString() : known.getName();
    }

    // ------------------------------------------------------------------------------ closing

    /**
     * A window has closed: the offline half is written and both locks are let go.
     *
     * <p>The write happens here rather than on each click because it is a whole file. It is also the
     * last moment at which it can happen, which is why a failure is logged loudly rather than
     * swallowed: a moderator who is told nothing assumes their change took.
     *
     * @return whether everything that needed writing was written
     */
    public void closed(InventoryWindow window) {
        if (window == null) {
            return;
        }
        UUID watcher = window.watcher().getUniqueId();
        views.close(watcher);
        if (window.isLive() || !window.access().canEdit()) {
            // Nothing to write: a live inventory was changed as it went, and a read-only window
            // changed nothing at all.
            offlineEdits.finish(window.owner(), watcher);
            return;
        }
        if (!offlineEdits.isStillTheirs(window.owner(), watcher)
                && !isOnline.test(window.owner())) {
            // Their hold lapsed while the window sat open — a moderator who went to make tea. The
            // hold exists to stop a second moderator, not to void the first one's work, and the
            // owner is still away, so it is taken again rather than the edit being thrown out. If
            // somebody else has it by now, this fails and the change is given back below.
            offlineEdits.begin(window.owner(), watcher);
        }
        Carried<ItemStack> toWrite = window.carried();
        writing.add(window.owner());
        Scheduling.async(plugin, () -> {
            // Asked and done in one atomic step. The other thing that can happen at this instant is
            // the owner logging in, and "check, then write" as two steps is a write that lands
            // after the server has already read that file — thrown away at best. The file write is
            // inside that step on purpose; it is a few kilobytes of gzip, and the alternative is a
            // window in which a login can be missed.
            boolean written;
            try {
                written = offlineEdits.writeAndFinish(window.owner(), watcher,
                        () -> saved.write(window.owner(), toWrite));
            } finally {
                writing.remove(window.owner());
            }
            if (written) {
                return;
            }
            Scheduling.entity(plugin, window.watcher(), () -> {
                // Closing a window destroys what is in it, so anything the moderator added that is
                // not now the owner's has to go back to them — and what they took out is still in
                // the file, so it comes back off them. Without the first, "the change was not
                // written" means "the items are gone"; without the second, it means "copied".
                window.undoUnwritten();
                told(watcher, say("invsee.not-saved"));
                told(watcher, Component.text("Their inventory was not saved. Nothing was changed, "
                                + "and what you added is back with you.")
                        .color(NamedTextColor.RED));
            });
            log.info("{}'s changes to {} were not written. Their file is untouched and the items "
                    + "are undone on the moderator's side.", window.watcher().getName(),
                    window.ownerName());
        });
    }

    /**
     * A moderator has gone: every hold they still have is let go — except one whose window has
     * already closed and whose write is on its way. Paper closes a disconnecting player's window
     * before it fires their quit, so releasing that hold here would make the write refuse, drop the
     * edit and leave what they added in a window nobody can be given it back from.
     *
     * @return the players whose holds were let go
     */
    public Set<UUID> editorLeft(UUID moderator) {
        return offlineEdits.editorLeft(moderator, writing::contains);
    }

    /** Says the moderator is still there, so an offline hold does not expire under them. */
    public void stillLooking(InventoryWindow window) {
        if (window != null && !window.isLive()) {
            offlineEdits.touch(window.owner(), window.watcher().getUniqueId());
        }
    }

    // -------------------------------------------------------------------------------- rules

    /**
     * Somebody has logged in, and any edit of their save file yields to them.
     *
     * <p>Nobody is ever held out of the server for this. A player turned away by a server they have
     * done nothing wrong on concludes it is broken, and no moderator's convenience is worth that.
     * So the arriving player wins: every window onto them is shut, the pending write is cancelled,
     * and their file is left exactly as it was.
     *
     * <p>Nothing real is lost, because nothing had been written. The moderator is told what happened
     * and what to do instead, which is to open the now-live inventory and make the change there —
     * where it takes effect immediately and cannot be overwritten by anybody.
     *
     * @return who was editing them, if anybody
     */
    public Optional<UUID> somebodyJoined(UUID who) {
        Optional<UUID> editor = offlineEdits.ownerCameBack(who);
        // Shuts every window onto them, editor and onlookers alike: a window showing a file that
        // is no longer the truth is a window that will be acted on.
        Set<UUID> watchers = views.ownerLeft(who);
        editor.ifPresent(moderator -> tell(moderator, who));
        watchers.stream()
                .filter(watcher -> editor.filter(watcher::equals).isEmpty())
                .forEach(watcher -> told(watcher,
                        say("invsee.owner-returned-watcher")));
        return editor;
    }

    private void tell(UUID moderator, UUID owner) {
        told(moderator, say("invsee.owner-returned", "who", nameOf(owner, null)));
    }

    /**
     * One message, in this server's words.
     *
     * <p>Falls back to a bare sentence when no message file has been handed over, because a moderator
     * whose change was dropped has to be told <em>something</em> — "the wording is missing" is not it.
     */
    private Component say(String key, Object... values) {
        Messages words = messages;
        return words == null ? Component.text(key) : words.prefixed(key, values);
    }

    private void told(UUID who, Component message) {
        Player player = Bukkit.getPlayer(who);
        if (player != null) {
            player.sendMessage(message);
        }
    }

    /** Who is editing them, so the refusal can say how long it is likely to be. */
    public Optional<UUID> editorOf(UUID who) {
        return offlineEdits.editorOf(who);
    }

    /** Tells this where to write down what moderators do. */
    public void audit(Audit audit) {
        this.audit = audit;
    }

    /** Tells this where the wording comes from. */
    public void messages(Messages messages) {
        this.messages = messages;
    }

    /** The wording, for a caller that wants to say an outcome itself. */
    public Messages messages() {
        return messages;
    }

    /**
     * Whether inventories can be watched at all. Off refuses every {@link #open}, whatever access was
     * asked for — the one setting this whole class had until now silently ignored.
     */
    public void enabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Whether a watcher may change anything, rather than only look. */
    public void allowEditing(boolean allowed) {
        this.allowEditing = allowed;
    }

    /** Whether a watcher granted editing may also touch armour and the off-hand. */
    public void allowEquipment(boolean allowed) {
        this.allowEquipment = allowed;
    }

    /** Whether the ender chest is offered beside the inventory. */
    public void showEnderChest(boolean show) {
        this.showEnderChest = show;
    }

    /** Writes an entry, if there is a journal to write it to. */
    private void record(AuditEntry.Builder entry) {
        if (audit != null) {
            audit.record(entry);
        }
    }

    public InventoryViews views() {
        return views;
    }

    public OfflineEdits offlineEdits() {
        return offlineEdits;
    }

    /** The saved-file source, for a plugin that wants to read one without opening a window. */
    public PlayerDataInventorySource saved() {
        return saved;
    }

    /** Clears out abandoned holds. Called on the same timer as everything else that sweeps. */
    public int sweep() {
        return offlineEdits.sweep();
    }
}
