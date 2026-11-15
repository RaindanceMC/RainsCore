package de.raindancer.core.platform.permission;

import de.raindancer.core.platform.util.PluginCode;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Permissions this server has granted somebody, remembered across restarts.
 *
 * <h2>What this is not</h2>
 * <b>Not a permissions plugin.</b> No groups, no inheritance, no contexts, no wildcards, no negative
 * nodes — and it should never grow them itself. LuckPerms exists and is better at every one of those,
 * which is exactly why {@link LuckPermsGrantStore} hands this class's questions to LuckPerms rather than
 * this class reinventing any of it.
 *
 * <p>It layers rather than replaces. A server without LuckPerms gets {@link LocalGrantStore}: a list of
 * nodes granted to a named person, applied when they join, and still there tomorrow. A server that has
 * LuckPerms installed gets {@link LuckPermsGrantStore} instead, transparently — every caller of this
 * class asks the exact same questions either way and neither knows or needs to know which store is
 * underneath.
 *
 * <h2>Which store, and when that is decided</h2>
 * Chosen once, at construction, by whichever constructor is used — never re-decided afterwards. A server
 * that installs LuckPerms mid-session does not have its grants migrate under it; that only happens on
 * the restart LuckPerms needed anyway.
 *
 * <h2>Nodes are stored exactly as written</h2>
 * Deliberately not normalised. Bukkit compares permission strings literally, so lower-casing a node
 * here would silently grant one that does not exist — and the symptom is a moderator whose commands all
 * refuse them for no visible reason.
 *
 * <h2>Thread safety</h2>
 * Read from permission checks, which happen on every command and inside render loops; written from
 * commands and menu clicks. Safe from any thread. {@link #load} and {@link #flush} touch disk (or, with
 * LuckPerms, the network) and should not be called on the server thread.
 */
public final class Grants {

    private static final LogChannel log = Log.of("permissions");

    private final GrantStore store;
    private final boolean usesLuckPerms;

    /**
     * Told whenever somebody's grants actually change, so their live session can follow.
     *
     * <p>Here rather than at the call sites because it was a call site's job before, and every call
     * site forgot: a promotion written here did nothing until the player reconnected. Seven mutators
     * each having to remember is seven chances to reintroduce it, and the failure is silent — the file
     * says one thing and the player experiences another.
     */
    private final List<Consumer<UUID>> watchers =
            new CopyOnWriteArrayList<>();

    /** Always the local file store — used by every test, and by anything that has no server to ask. */
    public Grants(Path folder) {
        this.store = new LocalGrantStore(folder);
        this.usesLuckPerms = false;
    }

    /**
     * Picks the store: LuckPerms if the server has it installed and it answers, the local file
     * otherwise.
     *
     * <p>The one time this differs from the constructor above — the one every real server should use.
     * When LuckPerms is found and a pre-LuckPerms {@code grants.yml} already exists in {@code folder},
     * it is imported once: every node it held is granted to the same person directly through LuckPerms,
     * and the old file is renamed to {@code grants.yml.imported-into-luckperms} rather than deleted, so
     * nobody's permissions silently vanish on the restart that installed a permissions plugin.
     */
    public Grants(Path folder, Plugin plugin) {
        LuckPerms luckPerms = tryLuckPerms(plugin);
        if (luckPerms != null) {
            LuckPermsGrantStore lp = new LuckPermsGrantStore(folder, luckPerms);
            lp.importFromLocalIfPresent(folder.resolve("grants.yml"));
            this.store = lp;
            this.usesLuckPerms = true;
        } else {
            this.store = new LocalGrantStore(folder);
            this.usesLuckPerms = false;
        }
    }

    private static LuckPerms tryLuckPerms(Plugin plugin) {
        if (plugin == null || plugin.getServer() == null
                || plugin.getServer().getPluginManager().getPlugin("LuckPerms") == null) {
            return null;
        }
        try {
            return LuckPermsProvider.get();
        } catch (IllegalStateException notYetLoaded) {
            log.warn("LuckPerms is installed but was not ready yet when Grants asked for it. Falling "
                    + "back to the local file store for this session. If this persists, check that "
                    + "LuckPerms loads before this plugin.");
            return null;
        }
    }

    /** Whether LuckPerms is what actually holds these grants. For {@link GrantListener}. */
    public boolean usesLuckPerms() {
        return usesLuckPerms;
    }

    /**
     * Ask to be told when somebody's grants change.
     *
     * <p>Wired to {@code GrantListener.apply} so a promotion or a revocation reaches the player who is
     * standing there, and to vanish's may-see set, which is likewise decided once and then cached.
     */
    /**
     * Drops whatever {@code loader}'s code registered here — for a plugin disabled without taking its
     * own back. See {@link PluginCode}.
     *
     * @return how many were dropped
     */
    public int forgetFrom(ClassLoader loader) {
        int before = watchers.size();
        watchers.removeIf(watcher -> PluginCode.isFrom(watcher, loader));
        return before - watchers.size();
    }

    public void onChange(Consumer<UUID> watcher) {
        if (watcher != null) {
            watchers.add(watcher);
        }
    }

    private void changed(UUID who) {
        for (Consumer<UUID> watcher : watchers) {
            try {
                watcher.accept(who);
            } catch (RuntimeException refreshFailed) {
                log.warn("Could not refresh {} after a grant change: {}", who, refreshFailed.toString());
            }
        }
    }

    /** Where this store's own bookkeeping is kept — for a diagnostic, and for a test. */
    public Path file() {
        return store.file();
    }

    // ---------------------------------------------------------------------------- granting

    /** Adds one node. @return whether this changed anything */
    public boolean grant(UUID who, String node) {
        boolean did = store.grant(who, node);
        if (did) {
            changed(who);
        }
        return did;
    }

    /** Takes one back. @return whether they had it */
    public boolean revoke(UUID who, String node) {
        boolean did = store.revoke(who, node);
        if (did) {
            changed(who);
        }
        return did;
    }

    /**
     * Replaces everything held directly with this set.
     *
     * <p>Replaces rather than adds, which is the whole point when a preset is applied: somebody moved
     * from Moderator down to Helper has to <em>lose</em> what Helper does not have.
     */
    public void set(UUID who, Collection<String> nodes) {
        if (store.set(who, nodes)) {
            changed(who);
        }
    }

    /**
     * Replaces the named preset somebody holds — a staff rank, most often — with exactly this node set.
     *
     * <p>The distinction from {@link #set} only matters with a LuckPerms store behind this: there, a
     * preset becomes a maintained LuckPerms group rather than a pile of per-user nodes, so an admin
     * reading the LuckPerms editor sees "Moderator" rather than eleven unrelated-looking strings.
     *
     * @param presetId a stable identifier for the preset — {@code StaffRank#key()}, for the one caller
     *                 that has one
     * @return whether this changed anything
     */
    public boolean setPreset(UUID who, String presetId, Collection<String> nodes) {
        boolean did = store.setPreset(who, presetId, nodes);
        if (did) {
            changed(who);
        }
        return did;
    }

    /**
     * Adds every one of these nodes, leaving whatever they already hold — granted or revoked —
     * completely alone.
     *
     * @return whether anything was actually new
     */
    public boolean grantAll(UUID who, Collection<String> nodes) {
        boolean did = store.grantAll(who, nodes);
        if (did) {
            changed(who);
        }
        return did;
    }

    /** Takes everything away. @return whether they had anything */
    public boolean clear(UUID who) {
        boolean did = store.clear(who);
        if (did) {
            changed(who);
        }
        return did;
    }

    // ---------------------------------------------------------------------------- asking

    /** Whether this server has granted them this node. */
    public boolean has(UUID who, String node) {
        return store.has(who, node);
    }

    /** Everything granted to them, as a copy. */
    public Set<String> nodesFor(UUID who) {
        return store.nodesFor(who);
    }

    /** How many nodes they hold. */
    public int countFor(UUID who) {
        return store.countFor(who);
    }

    /** Everybody with anything granted at all. */
    public Set<UUID> everybody() {
        return store.everybody();
    }

    // ---------------------------------------------------------------------------- applying

    /**
     * Hands every granted node to whatever sets permissions.
     *
     * <p>Only meaningful for the local store — see {@link #usesLuckPerms()}. With LuckPerms behind this,
     * the nodes are already live through LuckPerms' own mechanism and this is never called; it still
     * answers correctly if it is, since it only reads {@link #nodesFor}.
     */
    public void applyTo(UUID who, BiConsumer<String, Boolean> setter) {
        if (who == null || setter == null) {
            return;
        }
        for (String node : nodesFor(who)) {
            setter.accept(node, true);
        }
    }

    // ---------------------------------------------------------------------------- persistence

    /** Reads whatever this store keeps of its own. */
    public void load() {
        store.load();
    }

    /** Writes whatever this store keeps of its own. @return whether it reached the disk */
    public boolean flush() {
        return store.flush();
    }
}
