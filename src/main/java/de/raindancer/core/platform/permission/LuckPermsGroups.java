package de.raindancer.core.platform.permission;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.network.NetworkBus;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.node.NodeMutateEvent;
import net.luckperms.api.event.sync.PostSyncEvent;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.group.GroupManager;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.matcher.NodeMatcher;
import net.luckperms.api.node.types.DisplayNameNode;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.node.types.PermissionNode;
import net.luckperms.api.node.types.WeightNode;
import net.luckperms.api.query.QueryOptions;
import org.bukkit.plugin.Plugin;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Groups as LuckPerms groups. Seeds only create what is missing; everything after that is LuckPerms'.
 *
 * <p>Membership is a plain inheritance node on the user ({@code group.<name>}), exactly what
 * {@code /lp user <name> parent add <group>} writes — so promoting here and promoting in LuckPerms are
 * the same thing, and either shows up in the other.
 */
public final class LuckPermsGroups implements PermissionGroups {

    private static final LogChannel log = Log.of("permissions");

    private final LuckPerms luckPerms;
    private final List<Consumer<UUID>> watchers = new CopyOnWriteArrayList<>();
    private final List<EventSubscription<?>> subscriptions = new CopyOnWriteArrayList<>();

    public LuckPermsGroups(LuckPerms luckPerms) {
        this.luckPerms = luckPerms;
    }

    /**
     * Groups in this server's LuckPerms, listening to its changes, with LuckPerms' messaging offered the
     * network bus. Only call when LuckPerms is installed.
     */
    public static PermissionGroups startOn(Plugin plugin, NetworkBus bus) {
        LuckPerms luckPerms = net.luckperms.api.LuckPermsProvider.get();
        LuckPermsGroups groups = new LuckPermsGroups(luckPerms);
        groups.listen(plugin);
        // Only taken up where LuckPerms' config says messaging-service: custom.
        luckPerms.registerMessengerProvider(new LuckPermsBusMessenger(bus));
        return groups;
    }

    public static void stop(PermissionGroups groups) {
        if (groups instanceof LuckPermsGroups inLuckPerms) {
            inLuckPerms.close();
        }
    }

    /** Hooks LuckPerms' events, so a change made in the editor, by command or on another server is heard. */
    public void listen(Plugin plugin) {
        var events = luckPerms.getEventBus();
        subscriptions.add(events.subscribe(plugin, NodeMutateEvent.class, event -> {
            if (event.getTarget() instanceof User user) {
                changed(user.getUniqueId());
            } else {
                // A group's own parents changed: anybody's inherited groups may have.
                changed(null);
            }
        }));
        subscriptions.add(events.subscribe(plugin, UserDataRecalculateEvent.class,
                event -> changed(event.getUser().getUniqueId())));
        subscriptions.add(events.subscribe(plugin, PostSyncEvent.class, event -> changed(null)));
    }

    public void close() {
        subscriptions.forEach(EventSubscription::close);
        subscriptions.clear();
    }

    @Override
    public boolean editable() {
        return true;
    }

    @Override
    public boolean seed(GroupSeed seed) {
        if (seed == null || seed.name().isEmpty() || exists(seed.name())) {
            return false;
        }
        GroupManager groups = luckPerms.getGroupManager();
        Group group = groups.createAndLoadGroup(seed.name()).join();
        for (GroupSeed.Node node : seed.nodes()) {
            PermissionNode.Builder built = PermissionNode.builder(node.permission());
            for (Map.Entry<String, String> context : node.context().entrySet()) {
                built = built.withContext(context.getKey(), context.getValue());
            }
            group.data().add(built.build());
        }
        for (String parent : seed.parents()) {
            group.data().add(InheritanceNode.builder(parent).build());
        }
        if (seed.weight() > 0) {
            group.data().add(WeightNode.builder(seed.weight()).build());
        }
        if (!seed.displayName().isEmpty()) {
            group.data().add(DisplayNameNode.builder(seed.displayName()).build());
        }
        groups.saveGroup(group).join();
        log.info("Created the LuckPerms group {} with its default permissions — edit it in /lp editor, "
                + "it will not be changed by Rain's plugins again.", seed.name());
        return true;
    }

    @Override
    public boolean exists(String group) {
        String name = key(group);
        if (name.isEmpty()) {
            return false;
        }
        GroupManager groups = luckPerms.getGroupManager();
        return groups.getGroup(name) != null || groups.loadGroup(name).join().isPresent();
    }

    @Override
    public boolean join(UUID who, String group) {
        if (who == null || !exists(group)) {
            return false;
        }
        return modify(who, user -> user.data().add(InheritanceNode.builder(key(group)).build()).wasSuccessful());
    }

    @Override
    public boolean leave(UUID who, String group) {
        if (who == null || key(group).isEmpty()) {
            return false;
        }
        return modify(who, user -> {
            boolean any = false;
            for (InheritanceNode node : user.getNodes(NodeType.INHERITANCE)) {
                // Every membership in the group goes, whatever context or expiry it was given with.
                if (node.getGroupName().equals(key(group))) {
                    any |= user.data().remove(node).wasSuccessful();
                }
            }
            return any;
        });
    }

    @Override
    public Set<String> dropDirect(UUID who, java.util.Collection<String> nodes) {
        Set<String> dropped = new LinkedHashSet<>();
        if (who == null || nodes == null || nodes.isEmpty()) {
            return dropped;
        }
        Set<String> wanted = Set.copyOf(nodes);
        modify(who, user -> {
            for (PermissionNode node : user.getNodes(NodeType.PERMISSION)) {
                if (wanted.contains(node.getPermission()) && user.data().remove(node).wasSuccessful()) {
                    dropped.add(node.getPermission());
                }
            }
            return !dropped.isEmpty();
        });
        return Set.copyOf(dropped);
    }

    private boolean modify(UUID who, java.util.function.Predicate<User> change) {
        AtomicBoolean changed = new AtomicBoolean();
        try {
            luckPerms.getUserManager().modifyUser(who, user -> changed.set(change.test(user))).join();
        } catch (RuntimeException failed) {
            log.warn("Could not change {}'s groups in LuckPerms: {}", who, failed.toString());
            return false;
        }
        return changed.get();
    }

    @Override
    public Set<String> directGroupsOf(UUID who) {
        User user = user(who);
        if (user == null) {
            return Set.of();
        }
        Set<String> direct = new LinkedHashSet<>();
        for (InheritanceNode node : user.getNodes(NodeType.INHERITANCE)) {
            if (node.getValue() && !node.hasExpired()) {
                direct.add(node.getGroupName());
            }
        }
        return Set.copyOf(direct);
    }

    @Override
    public Set<String> groupsOf(UUID who) {
        User user = user(who);
        if (user == null) {
            return Set.of();
        }
        QueryOptions options = luckPerms.getContextManager().getQueryOptions(user)
                .orElseGet(QueryOptions::nonContextual);
        Set<String> all = new LinkedHashSet<>();
        for (Group group : user.getInheritedGroups(options)) {
            all.add(group.getName());
        }
        return Set.copyOf(all);
    }

    @Override
    public Set<UUID> membersOf(String group) {
        try {
            return Set.copyOf(luckPerms.getUserManager()
                    .searchAll(NodeMatcher.key(InheritanceNode.builder(key(group)).build()))
                    .join().keySet());
        } catch (RuntimeException failed) {
            log.warn("Could not list the members of {} from LuckPerms: {}", group, failed.toString());
            return Set.of();
        }
    }

    @Override
    public NetworkBus.Subscription onChange(Consumer<UUID> watcher) {
        watchers.add(watcher);
        return () -> watchers.remove(watcher);
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

    private User user(UUID who) {
        if (who == null) {
            return null;
        }
        User cached = luckPerms.getUserManager().getUser(who);
        if (cached != null) {
            return cached;
        }
        try {
            return luckPerms.getUserManager().loadUser(who).join();
        } catch (RuntimeException failed) {
            log.warn("Could not load {} from LuckPerms: {}", who, failed.toString());
            return null;
        }
    }

    private static String key(String group) {
        return group == null ? "" : group.trim().toLowerCase(Locale.ROOT);
    }
}
