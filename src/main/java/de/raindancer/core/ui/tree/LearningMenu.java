package de.raindancer.core.ui.tree;

import de.raindancer.core.RainsCore;
import de.raindancer.core.platform.util.Times;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.menu.Backdrop;
import de.raindancer.core.ui.menu.ConfirmMenu;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One {@link SkillTree} somebody spends points on — a player's skill, a pet, a guild: the canvas, a node clicked takes
 * its next rank, the middle button turns to the next branch and, shift-clicked, resets the tree. What the tree belongs
 * to answers through a {@link Learner}; this screen only shows and asks.
 */
public class LearningMenu extends TreeMenu {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** Whatever owns the points and the ranks. */
    public interface Learner {

        SkillTree tree();

        String title();

        String blurb();

        Material icon();

        Map<String, Integer> ranks();

        /** The level a node's level is measured against. */
        int level();

        /** What that level is called, as in "Needs pet level 5". */
        String levelWord();

        int points();

        int pointsLeft();

        /** One effect's worth, said for a player ("+4% damage"); null when it is not to be shown. */
        String describe(String effect, double value);

        Optional<SkillTree.Refusal> refusal(String node);

        /** Takes the next rank. @return why not, if not */
        Optional<SkillTree.Refusal> learn(String node);

        /** When a reset may be done again, in epoch milliseconds; 0 for now. */
        long resetAgainAt();

        /** Every point back. @return whether it happened */
        boolean reset();

        /** Lines under a node of its own — what it costs here when that differs, why it is special. */
        default List<String> extra(TreeNode node) {
            return List.of();
        }

        /** The node's cost here, when the learner makes it differ from the tree's. */
        default int cost(TreeNode node) {
            return node.cost();
        }

        default Backdrop backdrop(String branch) {
            return null;
        }

        default TreeLook look() {
            return null;
        }
    }

    private final Learner learner;
    private int branchShown;

    public LearningMenu(Player viewer, Brand brand, Menu parent, Learner learner) {
        super(viewer, brand, parent);
        this.learner = learner;
    }

    @Override
    protected int pages() {
        return 1;
    }

    @Override
    protected Page page(int index) {
        Map<String, Integer> ranks = learner.ranks();
        SkillTree tree = learner.tree();
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        for (TreeNode node : tree.nodes()) {
            NodeState state = tree.state(ranks, node);
            nodes.add(new Node(node.id(), node.column(), node.row(), state, icon(ranks, node, state), node.mastery()));
            for (String needed : node.requires()) {
                edges.add(new Edge(needed, node.id()));
            }
        }
        String first = tree.branches().isEmpty() ? null : tree.branches().getFirst().id();
        return new Page(MINI.deserialize("<dark_gray>" + learner.title()), nodes, edges,
                first == null ? null : learner.backdrop(first), summary());
    }

    @Override
    protected Backdrop backdrop() {
        Backdrop scene = learner.backdrop(branchAt(view().row()));
        return scene != null ? scene : super.backdrop();
    }

    @Override
    protected TreeLook look() {
        TreeLook own = learner.look();
        return own != null ? own : super.look();
    }

    private String branchAt(int row) {
        String best = null;
        int bestTop = Integer.MIN_VALUE;
        for (TreeBranch branch : learner.tree().branches()) {
            int top = learner.tree().nodesOf(branch.id()).stream().mapToInt(TreeNode::row).min()
                    .orElse(Integer.MAX_VALUE);
            if (best == null || (top <= row + 1 && top > bestTop)) {
                best = branch.id();
                bestTop = top <= row + 1 ? top : Integer.MIN_VALUE;
            }
        }
        return best;
    }

    private ItemStack icon(Map<String, Integer> ranks, TreeNode node, NodeState state) {
        int rank = ranks.getOrDefault(node.id(), 0);
        String colour = switch (state) {
            case MAXED -> "<gold>";
            case OWNED -> "<yellow>";
            case OPEN -> "<green>";
            case LOCKED -> "<gray>";
            case BARRED -> "<dark_red>";
        };
        List<String> lore = new ArrayList<>();
        String kind = node.mastery() ? "Mastery — earned by finishing the branch"
                : node.capstone() ? "Capstone" : node.choice() ? "Choice — shuts the other side" : null;
        if (kind != null) {
            lore.add("<gold>" + kind);
        }
        int shown = Math.max(1, Math.min(rank, node.ranks()));
        node.effects().forEach((id, value) -> {
            String said = learner.describe(id, value * shown);
            if (said != null) {
                lore.add("<gray>" + said);
            }
        });
        if (node.ranks() > 1 && rank < node.ranks()) {
            List<String> each = new ArrayList<>();
            node.effects().forEach((id, value) -> {
                String said = learner.describe(id, value);
                if (said != null) {
                    each.add(said);
                }
            });
            if (!each.isEmpty()) {
                lore.add("<dark_gray>Each rank: " + String.join(", ", each));
            }
        }
        lore.addAll(learner.extra(node));
        lore.add("");
        int cost = learner.cost(node);
        lore.add("<gray>Rank <white>" + rank + "<gray>/" + node.ranks() + (cost == 0 ? " · <white>free"
                : " · <white>" + cost + "<gray> point" + (cost == 1 ? "" : "s") + " a rank"));
        if (rank < node.ranks() && node.levelFor(rank + 1) > 0) {
            lore.add("<gray>Needs " + learner.levelWord() + " <white>" + node.levelFor(rank + 1));
        }
        lore.add(learner.refusal(node.id()).map(why -> "<red>" + reason(why, node, rank))
                .orElse("<yellow>Click to learn the next rank."));
        Material material = Optional.ofNullable(Material.matchMaterial(node.icon())).orElse(Material.PAPER);
        ItemStack icon = Icons.of(material, colour + node.title()
                + (node.ranks() > 1 ? " <white>" + rank + "<gray>/" + node.ranks() : ""), lore);
        if (rank > 0) {
            icon.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
        }
        return icon;
    }

    /** Why a node may not be taken, said for a player. */
    public String reason(SkillTree.Refusal why, TreeNode node, int rank) {
        return switch (why) {
            case MAXED -> "At its highest rank.";
            case BARRED -> "Shut: the other side of this choice is taken.";
            case NEEDS -> "Needs a node next to it first.";
            case UNFINISHED -> "Finish every other node of the branch first.";
            case LEVEL -> "Needs " + learner.levelWord() + " " + node.levelFor(rank + 1) + ".";
            case NO_POINTS -> "Not enough points.";
        };
    }

    private ItemStack summary() {
        List<String> lore = new ArrayList<>();
        if (!learner.blurb().isBlank()) {
            lore.add("<gray>" + learner.blurb());
            lore.add("");
        }
        lore.add("<gray>" + Character.toUpperCase(learner.levelWord().charAt(0)) + learner.levelWord().substring(1)
                + " <white>" + learner.level());
        lore.add("<green>Points to spend: <white>" + learner.pointsLeft() + "<gray> of " + learner.points());
        int mastered = 0;
        int branches = 0;
        for (TreeBranch branch : learner.tree().branches()) {
            if (!learner.tree().masteries(branch.id()).isEmpty()) {
                branches++;
                if (learner.tree().mastered(learner.ranks(), branch.id())) {
                    mastered++;
                }
            }
        }
        if (branches > 0) {
            lore.add("<gold>Mastered: <white>" + mastered + "<gray> of " + branches + " branch"
                    + (branches == 1 ? "" : "es"));
        }
        if (learner.tree().branches().size() > 1) {
            lore.add("");
            lore.add("<yellow>Click<gray> for the next branch.");
        }
        if (!learner.ranks().isEmpty()) {
            long again = learner.resetAgainAt() - System.currentTimeMillis();
            lore.add(again > 0 ? "<dark_gray>Reset again in " + Times.describe(Duration.ofMillis(again)) + "."
                    : "<yellow>Shift-click<gray> to take every point back.");
        }
        return Icons.of(learner.icon(), "<gold>" + learner.title(), lore);
    }

    @Override
    protected void onNode(Node node, InventoryClickEvent event) {
        Optional<SkillTree.Refusal> refused = learner.learn(node.id());
        Optional<TreeNode> found = learner.tree().node(node.id());
        if (refused.isPresent()) {
            cue(Cues.NO);
            found.ifPresent(tree -> viewer.sendMessage(MINI.deserialize("<red>" + tree.title() + ": "
                    + reason(refused.get(), tree, learner.ranks().getOrDefault(tree.id(), 0)))));
            return;
        }
        cue(found.map(TreeNode::mastery).orElse(false) ? Cues.EARNED : Cues.OK);
        reopen();
    }

    @Override
    protected void onSummary(InventoryClickEvent event) {
        if (!event.isShiftClick()) {
            List<TreeBranch> branches = learner.tree().branches();
            if (branches.size() > 1) {
                branchShown = (branchShown + 1) % branches.size();
                int[] start = start(branches.get(branchShown).id());
                showAt(start[0], start[1]);
            }
            return;
        }
        if (learner.ranks().isEmpty()) {
            return;
        }
        if (learner.resetAgainAt() > System.currentTimeMillis()) {
            cue(Cues.NO);
            return;
        }
        new ConfirmMenu(viewer, brand(), this, "<red>Reset " + learner.title() + "?",
                List.of("<gray>Every point spent comes back.", "<gray>Then it waits a while before the next reset."),
                () -> {
                    learner.reset();
                    reopen();
                }).open();
    }


    private int[] start(String branch) {
        int column = Integer.MAX_VALUE;
        int row = Integer.MAX_VALUE;
        for (TreeNode node : learner.tree().nodesOf(branch)) {
            if (node.column() < column || (node.column() == column && node.row() < row)) {
                column = node.column();
                row = node.row();
            }
        }
        return new int[]{column == Integer.MAX_VALUE ? 0 : column, row == Integer.MAX_VALUE ? 0 : row};
    }

    @Override
    public String breadcrumb() {
        return learner.title();
    }

    @Override
    protected List<String> helpLines() {
        return List.of(
                "The arrows below scroll the tree; shift-click",
                "them for a whole window. Click a node to learn its",
                "next rank: a node needs one next to it first, a",
                "choice shuts its other side. A branch ends in a",
                "mastery badge, free once the rest of it is done.",
                "",
                "Shift-click the middle button to reset the tree.");
    }
}
