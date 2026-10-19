package de.raindancer.core.platform.permission;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.network.NetworkBus;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Groups without LuckPerms: the seeds as they were shipped, handed out as plain {@link Grants}.
 *
 * <p>Deliberately not a permissions plugin (see {@link Grants}): the groups cannot be edited, nodes
 * limited to a context are left out rather than granted everywhere, and only what this class granted is
 * ever taken back. Members are kept in {@code groups.yml}, along with which nodes were ours to give.
 */
public final class LocalGroups implements PermissionGroups {

    private static final LogChannel log = Log.of("permissions");

    private final YamlStore store;
    private final Grants grants;
    private final Map<String, GroupSeed> seeds = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> members = new ConcurrentHashMap<>();
    /** What this class granted each player — the only nodes it may take back. */
    private final Map<UUID, Set<String>> granted = new ConcurrentHashMap<>();
    private final List<Consumer<UUID>> watchers = new CopyOnWriteArrayList<>();
    private final Set<String> warnedContextual = ConcurrentHashMap.newKeySet();

    public LocalGroups(YamlStore store, Grants grants) {
        this.store = store;
        this.grants = grants;
    }

    @Override
    public boolean editable() {
        return false;
    }

    @Override
    public boolean seed(GroupSeed seed) {
        if (seed == null || seed.name().isEmpty()) {
            return false;
        }
        boolean fresh = seeds.putIfAbsent(seed.name(), seed) == null;
        if (fresh) {
            // Members loaded before their group was seeded get its nodes now.
            members.forEach((who, groups) -> {
                if (groups.contains(seed.name())) {
                    apply(who);
                }
            });
        }
        return fresh;
    }

    @Override
    public boolean exists(String group) {
        return group != null && seeds.containsKey(key(group));
    }

    @Override
    public synchronized boolean join(UUID who, String group) {
        if (who == null || !exists(group)) {
            return false;
        }
        if (!members.computeIfAbsent(who, id -> ConcurrentHashMap.newKeySet()).add(key(group))) {
            return false;
        }
        apply(who);
        flush();
        changed(who);
        return true;
    }

    @Override
    public synchronized boolean leave(UUID who, String group) {
        Set<String> theirs = who == null ? null : members.get(who);
        if (theirs == null || !theirs.remove(key(group))) {
            return false;
        }
        if (theirs.isEmpty()) {
            members.remove(who);
        }
        apply(who);
        flush();
        changed(who);
        return true;
    }

    @Override
    public Set<String> directGroupsOf(UUID who) {
        Set<String> theirs = who == null ? null : members.get(who);
        return theirs == null ? Set.of() : Set.copyOf(theirs);
    }

    @Override
    public Set<String> groupsOf(UUID who) {
        Set<String> all = new LinkedHashSet<>();
        Deque<String> open = new ArrayDeque<>(directGroupsOf(who));
        while (!open.isEmpty()) {
            String group = open.pop();
            if (all.add(group)) {
                GroupSeed seed = seeds.get(group);
                if (seed != null) {
                    open.addAll(seed.parents());
                }
            }
        }
        return Set.copyOf(all);
    }

    @Override
    public Set<UUID> membersOf(String group) {
        Set<UUID> found = new LinkedHashSet<>();
        String wanted = key(group);
        members.forEach((who, groups) -> {
            if (groups.contains(wanted)) {
                found.add(who);
            }
        });
        return Set.copyOf(found);
    }

    @Override
    public synchronized Set<String> dropDirect(UUID who, java.util.Collection<String> nodes) {
        Set<String> dropped = new LinkedHashSet<>();
        if (who == null || nodes == null) {
            return dropped;
        }
        Set<String> ours = granted.getOrDefault(who, Set.of());
        for (String node : nodes) {
            // What a group of theirs gives is not direct; leave it to the group.
            if (!ours.contains(node) && grants.has(who, node) && grants.revoke(who, node)) {
                dropped.add(node);
            }
        }
        return Set.copyOf(dropped);
    }

    @Override
    public NetworkBus.Subscription onChange(Consumer<UUID> watcher) {
        watchers.add(watcher);
        return () -> watchers.remove(watcher);
    }

    /** Grants exactly what their groups give now, takes back what they gave before and no longer do. */
    private void apply(UUID who) {
        Set<String> due = new LinkedHashSet<>();
        for (String group : groupsOf(who)) {
            GroupSeed seed = seeds.get(group);
            if (seed == null) {
                continue;
            }
            for (GroupSeed.Node node : seed.nodes()) {
                // No contexts without LuckPerms: on-duty powers simply always apply, dev mode's wildcard never.
                if (!node.value()) {
                    continue;   // a denial only means something next to LuckPerms' wildcards
                }
                if (node.everywhere() || node.context().keySet().equals(Set.of(Duty.POWERS))) {
                    due.add(node.permission());
                } else if (warnedContextual.add(group + " " + node.permission())) {
                    log.info("{} in group {} only applies in {} — without LuckPerms it is not granted "
                            + "at all.", node.permission(), group, node.context());
                }
            }
        }
        Set<String> ours = granted.computeIfAbsent(who, id -> ConcurrentHashMap.newKeySet());
        for (String had : new ArrayList<>(ours)) {
            if (!due.contains(had)) {
                grants.revoke(who, had);
                ours.remove(had);
            }
        }
        for (String node : due) {
            if (!ours.contains(node) && grants.grant(who, node)) {
                ours.add(node);
            }
        }
        if (ours.isEmpty()) {
            granted.remove(who);
        }
    }

    private void changed(UUID who) {
        for (Consumer<UUID> watcher : watchers) {
            try {
                watcher.accept(who);
            } catch (RuntimeException failed) {
                log.warn("A group watcher failed: {}", failed.toString());
            }
        }
    }

    public synchronized void load() {
        store.readIfReadable().ifPresent(this::adopt);
    }

    public synchronized boolean flush() {
        return store.write(yaml -> {
            members.forEach((who, groups) -> yaml.set("members." + who, new ArrayList<>(groups)));
            granted.forEach((who, nodes) -> yaml.set("granted." + who, new ArrayList<>(nodes)));
        });
    }

    /** Follows changes other servers make to {@code groups.yml}. */
    public NetworkBus.Subscription follow() {
        return store.onChange(this::reload);
    }

    /** Re-read after another server changed the file. */
    public synchronized void reload() {
        load();
        members.keySet().forEach(this::apply);
        changed(null);
    }

    private void adopt(YamlConfiguration yaml) {
        Map<UUID, Set<String>> readMembers = new HashMap<>();
        Map<UUID, Set<String>> readGranted = new HashMap<>();
        readInto(yaml, "members", readMembers);
        readInto(yaml, "granted", readGranted);
        members.clear();
        granted.clear();
        readMembers.forEach((who, groups) -> members.put(who, concurrent(groups)));
        readGranted.forEach((who, nodes) -> granted.put(who, concurrent(nodes)));
    }

    private static void readInto(YamlConfiguration yaml, String section, Map<UUID, Set<String>> into) {
        var root = yaml.getConfigurationSection(section);
        if (root == null) {
            return;
        }
        for (String id : root.getKeys(false)) {
            try {
                into.put(UUID.fromString(id), new LinkedHashSet<>(root.getStringList(id)));
            } catch (IllegalArgumentException notAnId) {
                log.error("{} in groups.yml is not a player id and was skipped.", id);
            }
        }
    }

    private static Set<String> concurrent(Set<String> from) {
        Set<String> set = ConcurrentHashMap.newKeySet();
        set.addAll(from);
        return set;
    }

    private static String key(String group) {
        return group == null ? "" : group.trim().toLowerCase(Locale.ROOT);
    }
}
