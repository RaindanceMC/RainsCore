package de.raindancer.core.platform.permission;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.group.GroupManager;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.node.types.PermissionNode;
import net.luckperms.api.query.QueryOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link GrantStore} backed by an installed LuckPerms.
 *
 * <h2>Two different things get granted, and they are kept apart on purpose</h2>
 * <ul>
 *   <li>{@link #setPreset} — a coarse, named bundle such as a staff rank — becomes a LuckPerms
 *       <b>group</b>, {@code rains_<presetId>}, whose permissions this store keeps in sync with whatever
 *       the caller says the preset should grant. The player is added to that group and removed from
 *       every other {@code rains_*} group, because the ranks this exists for are a ladder: holding two
 *       at once is not a state that should be reachable.</li>
 *   <li>{@link #grant}, {@link #revoke} and {@link #grantAll} — one node at a time, the exception rather
 *       than the rule — become <b>per-user</b> LuckPerms nodes, exactly the way a server admin would add
 *       one by hand in the LuckPerms editor.</li>
 * </ul>
 *
 * <h2>The ledger, and why it exists despite LuckPerms already remembering everything</h2>
 * This store never touches a group or a node it did not itself create. A player's LuckPerms user may
 * carry permissions from groups an admin built for entirely different reasons, and {@link #clear} taking
 * <em>all</em> of somebody's nodes away because they were once staff would reach into every one of them.
 * So a small local file — {@code grants-luckperms.yml} — remembers exactly what this store granted:
 * which {@code rains_*} preset somebody currently holds, and which individual nodes were granted to them
 * directly. Only that is ever touched.
 *
 * <h2>Blocking calls</h2>
 * Loading an offline player's LuckPerms user is not instant — it is the same {@code loadUser(...).join()}
 * LuckPerms' own commands use for exactly this. Every method here that can reach an offline player is
 * only ever called from a command handler or a menu click, never from a hot path.
 */
final class LuckPermsGrantStore implements GrantStore {

    private static final LogChannel log = Log.of("permissions");
    private static final String GROUP_PREFIX = "rains_";

    private final LuckPerms luckPerms;
    private final YamlStore ledgerStore;

    /** Which preset (rank key) each person currently holds, if any. */
    private final Map<UUID, String> presetOf = new ConcurrentHashMap<>();

    /** Which nodes were granted to each person directly, one at a time, rather than through a preset. */
    private final Map<UUID, Set<String>> ownedNodes = new ConcurrentHashMap<>();

    LuckPermsGrantStore(Path folder, LuckPerms luckPerms) {
        this.luckPerms = luckPerms;
        // LuckPerms shares the grants over its own messaging; the ledger of what Rain granted is the network's too.
        this.ledgerStore = YamlStore.network(folder.resolve("grants-luckperms.yml"));
        this.ledgerStore.onChange(() -> {
            flush();
            load();
        });
    }

    @Override
    public Path file() {
        return ledgerStore.file();
    }

    // ---------------------------------------------------------------------------- individual nodes

    @Override
    public boolean grant(UUID who, String node) {
        if (who == null || node == null || node.isBlank()) {
            return false;
        }
        String trimmed = node.trim();
        User user = loadUser(who);
        if (user == null) {
            return false;
        }
        boolean added = user.data().add(PermissionNode.builder(trimmed).build()).wasSuccessful();
        if (added) {
            luckPerms.getUserManager().saveUser(user);
            // Ours only if this is what put it there. A node the player already held — set by an
            // admin, or by a group — is not this store's to take away later.
            ownedNodesOf(who).add(trimmed);
        }
        return added;
    }

    @Override
    public boolean revoke(UUID who, String node) {
        if (who == null || node == null || node.isBlank()) {
            return false;
        }
        String trimmed = node.trim();
        Set<String> owned = ownedNodes.get(who);
        if (owned == null || !owned.contains(trimmed)) {
            // Never granted by this store: somebody else's decision, not ours to undo.
            return false;
        }
        User user = loadUser(who);
        boolean removed = false;
        if (user != null) {
            removed = user.data().remove(PermissionNode.builder(trimmed).build()).wasSuccessful();
            if (removed) {
                luckPerms.getUserManager().saveUser(user);
            }
        }
        boolean ledgerChanged = owned.remove(trimmed);
        forgetOwnedIfEmpty(who);
        return removed || ledgerChanged;
    }

    @Override
    public boolean set(UUID who, Collection<String> nodes) {
        // Only ever used by the one-time import: a flat set of per-user nodes, no preset involved.
        if (who == null) {
            return false;
        }
        Set<String> wanted = new LinkedHashSet<>();
        if (nodes != null) {
            for (String node : nodes) {
                if (node != null && !node.isBlank()) {
                    wanted.add(node.trim());
                }
            }
        }
        User user = loadUser(who);
        if (user == null) {
            return false;
        }
        boolean changed = false;
        Set<String> currentlyOwned = ownedNodesOf(who);
        for (String had : new ArrayList<>(currentlyOwned)) {
            if (!wanted.contains(had)) {
                user.data().remove(PermissionNode.builder(had).build());
                currentlyOwned.remove(had);
                changed = true;
            }
        }
        for (String want : wanted) {
            if (currentlyOwned.add(want)) {
                user.data().add(PermissionNode.builder(want).build());
                changed = true;
            }
        }
        if (changed) {
            luckPerms.getUserManager().saveUser(user);
        }
        forgetOwnedIfEmpty(who);
        return changed;
    }

    @Override
    public boolean grantAll(UUID who, Collection<String> nodes) {
        return grantAllSaving(who, nodes) != null;
    }

    /** The same, answering with LuckPerms' own save — null when nothing changed and nothing was saved. */
    private CompletableFuture<Void> grantAllSaving(UUID who, Collection<String> nodes) {
        if (who == null || nodes == null || nodes.isEmpty()) {
            return null;
        }
        User user = loadUser(who);
        if (user == null) {
            return null;
        }
        boolean changed = false;
        Set<String> owned = ownedNodesOf(who);
        for (String node : nodes) {
            if (node == null || node.isBlank()) {
                continue;
            }
            String trimmed = node.trim();
            if (!owned.contains(trimmed)
                    && user.data().add(PermissionNode.builder(trimmed).build()).wasSuccessful()) {
                // Recorded only when this is what added it — see grant().
                owned.add(trimmed);
                changed = true;
            }
        }
        forgetOwnedIfEmpty(who);
        return changed ? luckPerms.getUserManager().saveUser(user) : null;
    }

    // ---------------------------------------------------------------------------- the preset (rank groups)

    @Override
    public boolean setPreset(UUID who, String presetId, Collection<String> nodes) {
        if (who == null || presetId == null || presetId.isBlank()) {
            return false;
        }
        String groupName = groupNameFor(presetId);
        syncGroupNodes(groupName, nodes);

        User user = loadUser(who);
        if (user == null) {
            return false;
        }
        boolean changed = false;
        for (Node node : new ArrayList<>(user.getNodes())) {
            if (node instanceof InheritanceNode inheritance
                    && inheritance.getGroupName().startsWith(GROUP_PREFIX)
                    && !inheritance.getGroupName().equals(groupName)) {
                user.data().remove(node);
                changed = true;
            }
        }
        boolean alreadyMember = user.getNodes().stream().anyMatch(node ->
                node instanceof InheritanceNode inheritance && inheritance.getGroupName().equals(groupName));
        if (!alreadyMember) {
            user.data().add(InheritanceNode.builder(groupName).build());
            changed = true;
        }
        if (changed) {
            luckPerms.getUserManager().saveUser(user);
        }
        String before = presetOf.put(who, presetId);
        return changed || !presetId.equals(before);
    }

    private void syncGroupNodes(String groupName, Collection<String> nodes) {
        GroupManager groupManager = luckPerms.getGroupManager();
        Group group = groupManager.getGroup(groupName);
        if (group == null) {
            group = groupManager.createAndLoadGroup(groupName).join();
        }
        // The group is entirely ours — namespaced under GROUP_PREFIX — so replacing every permission
        // node it carries is safe: nothing else should ever be adding one by hand.
        for (Node existing : new ArrayList<>(group.getNodes())) {
            if (existing instanceof PermissionNode) {
                group.data().remove(existing);
            }
        }
        if (nodes != null) {
            for (String node : nodes) {
                if (node != null && !node.isBlank()) {
                    group.data().add(PermissionNode.builder(node.trim()).build());
                }
            }
        }
        groupManager.saveGroup(group);
    }

    private static String groupNameFor(String presetId) {
        String cleaned = presetId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
        return GROUP_PREFIX + (cleaned.isEmpty() ? "preset" : cleaned);
    }

    // ---------------------------------------------------------------------------- clearing

    @Override
    public boolean clear(UUID who) {
        if (who == null) {
            return false;
        }
        boolean hadAnything = presetOf.containsKey(who) || ownedNodes.containsKey(who);
        User user = loadUser(who);
        if (user != null) {
            Set<String> owned = ownedNodes.getOrDefault(who, Set.of());
            boolean changed = false;
            for (Node node : new ArrayList<>(user.getNodes())) {
                if (node instanceof InheritanceNode inheritance
                        && inheritance.getGroupName().startsWith(GROUP_PREFIX)) {
                    user.data().remove(node);
                    changed = true;
                } else if (node instanceof PermissionNode permission
                        && owned.contains(permission.getPermission())) {
                    user.data().remove(node);
                    changed = true;
                }
            }
            if (changed) {
                // Waited for: whatever changes this user next loads them from storage, and would
                // otherwise read the nodes back before this save lands and write them again.
                luckPerms.getUserManager().saveUser(user).join();
            }
        }
        presetOf.remove(who);
        ownedNodes.remove(who);
        return hadAnything;
    }

    // ---------------------------------------------------------------------------- asking

    @Override
    public boolean has(UUID who, String node) {
        if (who == null || node == null || node.isBlank()) {
            return false;
        }
        return nodesFor(who).contains(node.trim());
    }

    @Override
    public Set<String> nodesFor(UUID who) {
        if (who == null) {
            return new LinkedHashSet<>();
        }
        User user = loadUser(who);
        if (user == null) {
            return new LinkedHashSet<>();
        }
        Set<String> effective = new LinkedHashSet<>();
        for (Node node : user.resolveInheritedNodes(QueryOptions.nonContextual())) {
            if (node instanceof PermissionNode permission && permission.getValue()) {
                effective.add(permission.getPermission());
            }
        }
        return effective;
    }

    @Override
    public int countFor(UUID who) {
        return nodesFor(who).size();
    }

    @Override
    public Set<UUID> everybody() {
        Set<UUID> all = new LinkedHashSet<>(presetOf.keySet());
        all.addAll(ownedNodes.keySet());
        return Set.copyOf(all);
    }

    // ---------------------------------------------------------------------------- the ledger

    @Override
    public void load() {
        // Read first, replace after: a ledger that cannot be read right now is kept, not forgotten.
        var fresh = ledgerStore.readIfReadable();
        if (fresh.isEmpty()) {
            return;
        }
        var yaml = fresh.get();
        presetOf.clear();
        ownedNodes.clear();
        var presets = yaml.getConfigurationSection("preset");
        if (presets != null) {
            for (String id : presets.getKeys(false)) {
                try {
                    presetOf.put(UUID.fromString(id), presets.getString(id));
                } catch (IllegalArgumentException notAnId) {
                    log.error("{} in grants-luckperms.yml's preset section is not a player id and was "
                            + "skipped.", id);
                }
            }
        }
        var owned = yaml.getConfigurationSection("owned");
        if (owned != null) {
            for (String id : owned.getKeys(false)) {
                try {
                    UUID who = UUID.fromString(id);
                    List<String> nodes = owned.getStringList(id);
                    if (!nodes.isEmpty()) {
                        ownedNodesOf(who).addAll(nodes);
                    }
                } catch (IllegalArgumentException notAnId) {
                    log.error("{} in grants-luckperms.yml's owned section is not a player id and was "
                            + "skipped.", id);
                }
            }
        }
    }

    @Override
    public boolean flush() {
        return ledgerStore.write(yaml -> {
            presetOf.forEach((who, presetId) -> yaml.set("preset." + who, presetId));
            ownedNodes.forEach((who, nodes) -> {
                if (!nodes.isEmpty()) {
                    yaml.set("owned." + who, new ArrayList<>(nodes));
                }
            });
        });
    }

    // ---------------------------------------------------------------------------- the one-time import

    /**
     * Imports a pre-LuckPerms {@code grants.yml}, if one is sitting in {@code folder} — a server that
     * just installed LuckPerms and used to run on the local store.
     *
     * <p>Every node found is granted to the same person directly through LuckPerms — flat, individual
     * nodes, not a preset, because this file cannot say which node came from which staff rank. Existing
     * moderation ranks form their proper {@code rains_*} groups the next time each person is promoted or
     * has their preset reapplied, which happens routinely on join; nothing about their effective
     * permissions changes in the meantime.
     *
     * <p>The old file is renamed to {@code grants.yml.imported-into-luckperms} rather than deleted, so
     * the import is never the only place the data existed.
     */
    void importFromLocalIfPresent(Path oldGrantsFile) {
        if (!Files.isRegularFile(oldGrantsFile)) {
            return;
        }
        YamlStore oldStore = new YamlStore(oldGrantsFile);
        var imported = new AtomicInteger();
        List<CompletableFuture<Void>> saves = new ArrayList<>();
        LocalGrantStore.readInto(oldStore, (who, nodes) -> {
            CompletableFuture<Void> saved = grantAllSaving(who, nodes);
            if (saved != null) {
                saves.add(saved);
                imported.incrementAndGet();
            }
        });
        // LuckPerms saves on its own executor. The old file is only put aside once every save is
        // confirmed: renamed after a save LuckPerms refused, the import never runs again and the
        // permissions are nowhere. Waited for here because this runs once, at startup.
        try {
            CompletableFuture.allOf(saves.toArray(CompletableFuture[]::new))
                    .get(30, TimeUnit.SECONDS);
        } catch (Exception notSaved) {
            log.error("LuckPerms did not confirm saving the permissions imported from grants.yml ({}). "
                    + "grants.yml is left where it is and will be imported again on the next start.",
                    notSaved.toString());
            return;
        }
        try {
            Files.move(oldGrantsFile,
                    oldGrantsFile.resolveSibling(oldGrantsFile.getFileName() + ".imported-into-luckperms"),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException moveFailed) {
            log.warn("Imported grants.yml into LuckPerms but could not rename it aside afterwards: {}. "
                    + "It is safe to delete by hand once the import above is confirmed.",
                    moveFailed.toString());
        }
        flush();
        log.info("LuckPerms detected: imported {} player(s)' permissions from grants.yml into LuckPerms "
                + "as individual nodes. The old file was kept, renamed to "
                + "grants.yml.imported-into-luckperms. Existing moderation staff ranks form their proper "
                + "LuckPerms groups the next time each person is promoted, demoted or rejoins.",
                imported.get());
    }

    // ---------------------------------------------------------------------------- internals

    private User loadUser(UUID who) {
        if (who == null) {
            return null;
        }
        UserManager users = luckPerms.getUserManager();
        User cached = users.getUser(who);
        if (cached != null) {
            return cached;
        }
        try {
            return users.loadUser(who).join();
        } catch (RuntimeException loadFailed) {
            log.warn("Could not load {} from LuckPerms: {}", who, loadFailed.toString());
            return null;
        }
    }

    private Set<String> ownedNodesOf(UUID who) {
        return ownedNodes.computeIfAbsent(who, id -> ConcurrentHashMap.newKeySet());
    }

    private void forgetOwnedIfEmpty(UUID who) {
        Set<String> owned = ownedNodes.get(who);
        if (owned != null && owned.isEmpty()) {
            ownedNodes.remove(who, owned);
        }
    }
}
