package de.raindancer.core.platform.permission;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import org.bukkit.configuration.ConfigurationSection;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * The store a server without a permissions plugin needs: a list of nodes granted to a named person,
 * kept in memory and in {@code grants.yml}.
 *
 * <p>This is the whole of what {@link Grants} used to be before {@link LuckPermsGrantStore} existed —
 * moved here verbatim, as {@link GrantStore}'s local implementation.
 */
final class LocalGrantStore implements GrantStore {

    private static final LogChannel log = Log.of("permissions");

    private final ConcurrentHashMap<UUID, Set<String>> granted = new ConcurrentHashMap<>();
    private final YamlStore store;
    /** Whether anything changed since the last write — an idle server writes nothing. */
    private final AtomicBoolean dirty = new AtomicBoolean();

    LocalGrantStore(Path folder) {
        // What a player was granted is theirs on every server of the network.
        this.store = YamlStore.network(folder.resolve("grants.yml"));
        this.store.onChange(() -> {
            flush();
            load();
        });
    }

    @Override
    public Path file() {
        return store.file();
    }

    @Override
    public boolean grant(UUID who, String node) {
        if (who == null || node == null || node.isBlank()) {
            return false;
        }
        return changed(grantEach(who, List.of(node)));
    }

    @Override
    public boolean revoke(UUID who, String node) {
        if (who == null || node == null || node.isBlank()) {
            return false;
        }
        AtomicBoolean removed = new AtomicBoolean();
        // One step: taken out and, if that emptied it, dropped together. Two steps let a grant
        // arriving in between land in a set that was then thrown away.
        granted.computeIfPresent(who, (key, theirs) -> {
            removed.set(theirs.remove(node.trim()));
            return theirs.isEmpty() ? null : theirs;
        });
        return changed(removed.get());
    }

    @Override
    public boolean set(UUID who, Collection<String> nodes) {
        if (who == null) {
            return false;
        }
        Set<String> fresh = cleaned(nodes == null ? List.of() : nodes);
        if (fresh.isEmpty()) {
            return changed(granted.remove(who) != null);
        }
        Set<String> before = granted.put(who, fresh);
        return changed(before == null || !before.equals(fresh));
    }

    @Override
    public boolean setPreset(UUID who, String presetId, Collection<String> nodes) {
        // No group concept locally — a preset is just a set() by another name.
        return set(who, nodes);
    }

    @Override
    public boolean grantAll(UUID who, Collection<String> nodes) {
        if (who == null || nodes == null || nodes.isEmpty()) {
            return false;
        }
        return changed(grantEach(who, nodes));
    }

    /** Adds the nodes in one step with anything taking them away. @return whether any was new */
    private boolean grantEach(UUID who, Collection<String> nodes) {
        AtomicBoolean added = new AtomicBoolean();
        granted.compute(who, (key, theirs) -> {
            Set<String> into = theirs == null ? ConcurrentHashMap.newKeySet() : theirs;
            for (String node : nodes) {
                if (node != null && !node.isBlank() && into.add(node.trim())) {
                    added.set(true);
                }
            }
            return into.isEmpty() ? null : into;
        });
        return added.get();
    }

    private static Set<String> cleaned(Collection<String> nodes) {
        Set<String> fresh = ConcurrentHashMap.newKeySet();
        for (String node : nodes) {
            if (node != null && !node.isBlank()) {
                fresh.add(node.trim());
            }
        }
        return fresh;
    }

    private boolean changed(boolean did) {
        if (did) {
            dirty.set(true);
        }
        return did;
    }

    private void replaceQuietly(UUID who, Collection<String> nodes) {
        Set<String> fresh = cleaned(nodes);
        if (fresh.isEmpty()) {
            granted.remove(who);
        } else {
            granted.put(who, fresh);
        }
    }

    @Override
    public boolean clear(UUID who) {
        return who != null && changed(granted.remove(who) != null);
    }

    @Override
    public boolean has(UUID who, String node) {
        if (who == null || node == null || node.isBlank()) {
            return false;
        }
        Set<String> theirs = granted.get(who);
        return theirs != null && theirs.contains(node.trim());
    }

    @Override
    public Set<String> nodesFor(UUID who) {
        if (who == null) {
            return new LinkedHashSet<>();
        }
        Set<String> theirs = granted.get(who);
        return theirs == null ? new LinkedHashSet<>() : new LinkedHashSet<>(theirs);
    }

    @Override
    public int countFor(UUID who) {
        Set<String> theirs = who == null ? null : granted.get(who);
        return theirs == null ? 0 : theirs.size();
    }

    @Override
    public Set<UUID> everybody() {
        return Set.copyOf(granted.keySet());
    }

    @Override
    public void load() {
        granted.clear();
        readInto(store, this::replaceQuietly);
        dirty.set(false);
    }

    @Override
    public boolean flush() {
        if (!dirty.getAndSet(false)) {
            return true;
        }
        boolean written = store.write(yaml -> granted.forEach((who, nodes) ->
                yaml.set("granted." + who, new ArrayList<>(nodes))));
        if (!written) {
            dirty.set(true);
        }
        return written;
    }

    // ---------------------------------------------------------------------------- shared file format

    /**
     * Reads a {@code grants.yml}-shaped file's {@code granted} section straight into a caller-supplied
     * sink, without needing a whole store around it.
     *
     * <p>Package-private and static so {@link LuckPermsGrantStore} can read a server's pre-LuckPerms
     * {@code grants.yml} the exact same way this class does, for the one-time import.
     */
    static void readInto(YamlStore fromStore, BiConsumer<UUID, Collection<String>> sink) {
        ConfigurationSection root = fromStore.read().getConfigurationSection("granted");
        if (root == null) {
            return;
        }
        List<String> unreadable = new ArrayList<>();
        for (String id : root.getKeys(false)) {
            UUID who;
            try {
                who = UUID.fromString(id);
            } catch (IllegalArgumentException notAnId) {
                unreadable.add(id);
                continue;
            }
            sink.accept(who, root.getStringList(id));
        }
        if (!unreadable.isEmpty()) {
            log.error("{} entry/entries in {} are not player ids and have been skipped: {}. Anybody "
                            + "they belonged to has no granted permissions this session.",
                    unreadable.size(), fromStore.file().getFileName(), String.join(", ", unreadable));
        }
    }
}
