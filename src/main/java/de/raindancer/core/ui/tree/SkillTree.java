package de.raindancer.core.ui.tree;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * A skill tree as a plugin ships it in YAML, checked as it is read, and the rules every tree shares: what a set of
 * ranks costs and adds up to, how a node stands, and why another rank may not be taken. What the effects mean is the
 * plugin's; here they are ids and numbers.
 *
 * <pre>
 * branches:
 *   smith: {title: Smith, icon: anvil, blurb: Hammers.}
 * nodes:
 *   root: {branch: smith, at: [0, 1], title: Root, icon: iron_ingot, ranks: 3, cost: 1, effects: {speed: 5}}
 *   left: {branch: smith, at: [1, 0], requires: [root], choice: true, excludes: [right], level: 10, level-step: 5}
 * </pre>
 *
 * A node needs ONE of what it {@code requires} — a neighbour in its branch, so the menu can draw the line — and is
 * shut while one of what it {@code excludes} is taken.
 */
public final class SkillTree {

    /** Why another rank of a node may not be taken, in the order they are asked. */
    public enum Refusal { MAXED, BARRED, NEEDS, LEVEL, NO_POINTS }

    private final List<TreeBranch> branches;
    private final Map<String, TreeNode> nodes;

    private SkillTree(List<TreeBranch> branches, Map<String, TreeNode> nodes) {
        this.branches = List.copyOf(branches);
        this.nodes = Collections.unmodifiableMap(nodes);
    }

    /**
     * The tree in a plugin's jar.
     *
     * @throws IllegalStateException when it is missing, unreadable or broken — a plugin ships its tree, so that is a bug
     */
    public static SkillTree resource(Class<?> owner, String path, Predicate<String> knownEffect) {
        try (InputStream in = owner.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException(path + " is missing from the jar.");
            }
            return read(new String(in.readAllBytes(), StandardCharsets.UTF_8), knownEffect);
        } catch (IOException unreadable) {
            throw new IllegalStateException(path + " could not be read.", unreadable);
        } catch (IllegalArgumentException broken) {
            throw new IllegalStateException(path + ": " + broken.getMessage(), broken);
        }
    }

    /** @throws IllegalArgumentException saying what is wrong with the tree */
    public static SkillTree read(String text, Predicate<String> knownEffect) {
        // Broken YAML reads as empty and is refused below for having no branches.
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new StringReader(text));
        List<TreeBranch> branches = new ArrayList<>();
        ConfigurationSection branchSection = section(yaml, "branches");
        for (String id : branchSection.getKeys(false)) {
            ConfigurationSection b = section(branchSection, id);
            branches.add(new TreeBranch(id, b.getString("title", id), b.getString("icon", "stone"),
                    b.getString("blurb", "")));
        }
        if (branches.isEmpty()) {
            throw new IllegalArgumentException("The skill tree has no branches.");
        }
        Set<String> branchIds = new HashSet<>();
        branches.forEach(branch -> branchIds.add(branch.id()));
        Map<String, TreeNode> nodes = new LinkedHashMap<>();
        ConfigurationSection nodeSection = section(yaml, "nodes");
        for (String id : nodeSection.getKeys(false)) {
            ConfigurationSection n = section(nodeSection, id);
            List<Integer> at = n.getIntegerList("at");
            if (at.size() != 2) {
                throw new IllegalArgumentException("Node " + id + " needs at: [column, row].");
            }
            String branch = n.getString("branch", "");
            if (!branchIds.contains(branch)) {
                throw new IllegalArgumentException("Node " + id + " is in no known branch (" + branch + ").");
            }
            Map<String, Double> effects = new TreeMap<>();
            ConfigurationSection effectSection = n.getConfigurationSection("effects");
            if (effectSection != null) {
                for (String key : effectSection.getKeys(false)) {
                    if (!knownEffect.test(key)) {
                        throw new IllegalArgumentException("Node " + id + " has an unknown effect: " + key + ".");
                    }
                    effects.put(key, effectSection.getDouble(key));
                }
            }
            TreeNode node = new TreeNode(id, branch, at.get(0), at.get(1), n.getString("title", id),
                    n.getString("icon", "stone"), Math.max(1, n.getInt("ranks", 1)), Math.max(1, n.getInt("cost", 1)),
                    n.getBoolean("choice"), n.getBoolean("capstone"), Math.max(0, n.getInt("level", 0)),
                    Math.max(0, n.getInt("level-step", 0)), n.getStringList("requires"), n.getStringList("excludes"),
                    effects);
            try {
                TreeGrid.slot(node.column(), node.row());
            } catch (IllegalArgumentException outside) {
                throw new IllegalArgumentException("Node " + id + ": " + outside.getMessage(), outside);
            }
            nodes.put(id, node);
        }
        Map<String, Set<Integer>> taken = new LinkedHashMap<>();
        for (TreeNode node : nodes.values()) {
            if (!taken.computeIfAbsent(node.branch(), b -> new HashSet<>())
                    .add(TreeGrid.slot(node.column(), node.row()))) {
                throw new IllegalArgumentException("Node " + node.id() + " sits where another node of "
                        + node.branch() + " sits.");
            }
            for (String needed : node.requires()) {
                TreeNode other = nodes.get(needed);
                if (other == null || !other.branch().equals(node.branch())) {
                    throw new IllegalArgumentException("Node " + node.id() + " needs " + needed
                            + ", which is not in its branch.");
                }
                try {
                    TreeGrid.link(other.column(), other.row(), node.column(), node.row());
                } catch (IllegalArgumentException far) {
                    throw new IllegalArgumentException("Node " + node.id() + " needs " + needed
                            + ", which is not its neighbour.", far);
                }
            }
            for (String shut : node.excludes()) {
                if (!nodes.containsKey(shut)) {
                    throw new IllegalArgumentException("Node " + node.id() + " shuts " + shut + ", which is no node.");
                }
            }
        }
        return new SkillTree(branches, nodes);
    }

    private static ConfigurationSection section(ConfigurationSection parent, String path) {
        ConfigurationSection found = parent.getConfigurationSection(path);
        if (found == null) {
            throw new IllegalArgumentException("The skill tree has no " + path + ".");
        }
        return found;
    }

    public List<TreeBranch> branches() {
        return branches;
    }

    public Optional<TreeBranch> branch(String id) {
        return branches.stream().filter(branch -> branch.id().equals(id)).findFirst();
    }

    public List<TreeNode> nodes() {
        return List.copyOf(nodes.values());
    }

    public List<TreeNode> nodesOf(String branch) {
        return nodes.values().stream().filter(node -> node.branch().equals(branch)).toList();
    }

    public Optional<TreeNode> node(String id) {
        return Optional.ofNullable(nodes.get(id));
    }

    private static int rank(Map<String, Integer> ranks, String id) {
        Integer rank = ranks.get(id);
        return rank == null ? 0 : Math.max(0, rank);
    }

    /** What these ranks add up to, by effect id; unknown nodes count for nothing, ranks past a node's last for its last. */
    public Map<String, Double> effects(Map<String, Integer> ranks) {
        Map<String, Double> sum = new TreeMap<>();
        ranks.forEach((id, rank) -> {
            TreeNode node = nodes.get(id);
            if (node == null || rank == null || rank <= 0) {
                return;
            }
            int counted = Math.min(rank, node.ranks());
            node.effects().forEach((effect, value) -> sum.merge(effect, value * counted, Double::sum));
        });
        return sum;
    }

    /** The points these ranks took; unknown nodes took none. */
    public int spent(Map<String, Integer> ranks) {
        long spent = 0;
        for (var entry : ranks.entrySet()) {
            TreeNode node = nodes.get(entry.getKey());
            if (node != null && entry.getValue() != null && entry.getValue() > 0) {
                spent += (long) node.cost() * Math.min(entry.getValue(), node.ranks());
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, spent);
    }

    /** The points these ranks took in one branch. */
    public int spentIn(Map<String, Integer> ranks, String branch) {
        Map<String, Integer> here = new LinkedHashMap<>();
        ranks.forEach((id, rank) -> {
            TreeNode node = nodes.get(id);
            if (node != null && node.branch().equals(branch)) {
                here.put(id, rank);
            }
        });
        return spent(here);
    }

    public NodeState state(Map<String, Integer> ranks, TreeNode node) {
        int rank = rank(ranks, node.id());
        if (rank >= node.ranks()) {
            return NodeState.MAXED;
        }
        if (rank > 0) {
            return NodeState.OWNED;
        }
        if (barred(ranks, node)) {
            return NodeState.BARRED;
        }
        return needsMet(ranks, node) ? NodeState.OPEN : NodeState.LOCKED;
    }

    /**
     * Why the next rank of {@code node} may not be taken, if it may not.
     *
     * @param level the level its rank is measured against (the player's, the guild's…)
     * @param left  points left to spend
     */
    public Optional<Refusal> refusal(Map<String, Integer> ranks, TreeNode node, int level, int left) {
        int rank = rank(ranks, node.id());
        if (rank >= node.ranks()) {
            return Optional.of(Refusal.MAXED);
        }
        if (rank == 0 && barred(ranks, node)) {
            return Optional.of(Refusal.BARRED);
        }
        if (rank == 0 && !needsMet(ranks, node)) {
            return Optional.of(Refusal.NEEDS);
        }
        if (level < node.levelFor(rank + 1)) {
            return Optional.of(Refusal.LEVEL);
        }
        if (left < node.cost()) {
            return Optional.of(Refusal.NO_POINTS);
        }
        return Optional.empty();
    }

    private static boolean barred(Map<String, Integer> ranks, TreeNode node) {
        return node.excludes().stream().anyMatch(other -> rank(ranks, other) > 0);
    }

    private static boolean needsMet(Map<String, Integer> ranks, TreeNode node) {
        return node.requires().isEmpty() || node.requires().stream().anyMatch(other -> rank(ranks, other) > 0);
    }
}
