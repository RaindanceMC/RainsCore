package de.raindancer.core.platform.permission;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a group starts with the first time it is made — and only then.
 *
 * <p>A seed is a default, not a definition: once the group exists, whoever runs the server owns it
 * ({@code /lp editor}), and nothing seeds over it again. That is the difference from the old staff
 * presets, which rewrote their group on every promotion and silently undid every edit.
 *
 * @param name        the group's name, lower case — what {@code /lp group <name>} takes
 * @param displayName what LuckPerms shows instead of the name; empty for none
 * @param weight      LuckPerms' weight: higher wins where two groups disagree
 * @param parents     groups this one inherits from
 * @param nodes       permissions it grants
 */
public record GroupSeed(String name, String displayName, int weight, List<String> parents, List<Node> nodes) {

    /** One permission, optionally only where every context matches (e.g. {@code world=build}). */
    public record Node(String permission, Map<String, String> context) {

        public Node {
            context = context == null ? Map.of() : Map.copyOf(context);
        }

        public boolean everywhere() {
            return context.isEmpty();
        }
    }

    public GroupSeed {
        name = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        displayName = displayName == null ? "" : displayName;
        parents = parents == null ? List.of() : List.copyOf(parents);
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    public static GroupSeed named(String name) {
        return new GroupSeed(name, "", 0, List.of(), List.of());
    }

    public GroupSeed displayName(String shown) {
        return new GroupSeed(name, shown, weight, parents, nodes);
    }

    public GroupSeed weight(int value) {
        return new GroupSeed(name, displayName, value, parents, nodes);
    }

    public GroupSeed inheriting(String parent) {
        List<String> more = new ArrayList<>(parents);
        more.add(parent.trim().toLowerCase(Locale.ROOT));
        return new GroupSeed(name, displayName, weight, more, nodes);
    }

    public GroupSeed granting(String... permissions) {
        List<Node> more = new ArrayList<>(nodes);
        for (String permission : permissions) {
            more.add(new Node(permission, Map.of()));
        }
        return new GroupSeed(name, displayName, weight, parents, more);
    }

    public GroupSeed granting(Iterable<String> permissions) {
        List<Node> more = new ArrayList<>(nodes);
        permissions.forEach(permission -> more.add(new Node(permission, Map.of())));
        return new GroupSeed(name, displayName, weight, parents, more);
    }

    /** A permission that only applies where {@code key=value} — LuckPerms' contexts. */
    public GroupSeed grantingIn(String permission, String key, String value) {
        List<Node> more = new ArrayList<>(nodes);
        more.add(new Node(permission, Map.of(key, value)));
        return new GroupSeed(name, displayName, weight, parents, more);
    }
}
