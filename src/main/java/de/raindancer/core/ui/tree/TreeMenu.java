package de.raindancer.core.ui.tree;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.menu.Backdrop;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A skill tree, one page per branch: nodes on a {@link TreeGrid}, joined by lines, paged with the chrome's arrows.
 *
 * <p>For a player wearing the pack the frames and lines are drawn by the window title over the page's painting, in
 * the {@link #look()}'s font, so a line lights up the moment both its ends are taken. Without it the lines are glass
 * panes — lime when lit — and the icon alone says how a node stands.
 *
 * <p>Turning a page or taking a node changes the title, and a window cannot be retitled: those reopen the window.
 */
public abstract class TreeMenu extends Menu {

    /** A node as one player sees it. */
    public record Node(String id, int column, int row, NodeState state, ItemStack icon) {
    }

    /** A line from a node to one that needs it. */
    public record Edge(String from, String to) {
    }

    /**
     * One branch.
     *
     * @param scene   the painting behind it, or {@code null} for the house's
     * @param summary the chrome's middle button — points left, what the branch is — or {@code null} for a counter
     */
    public record Page(Component title, List<Node> nodes, List<Edge> edges, Backdrop scene, ItemStack summary) {
    }

    private int page;
    private Page shown;

    protected TreeMenu(Player viewer, Brand brand, Menu parent) {
        super(viewer, brand, parent);
    }

    protected abstract int pages();

    protected abstract Page page(int index);

    /** A click on a node. Call {@link #reopen()} once its state has changed. */
    protected abstract void onNode(Node node, InventoryClickEvent event);

    /** A click on the chrome's middle button, the page's summary. Nothing by default. */
    protected void onSummary(InventoryClickEvent event) {
    }

    protected TreeLook look() {
        return TreeLook.BRASS;
    }

    public int pageIndex() {
        return page;
    }

    /** Opens on another branch. */
    public void turnTo(int index) {
        page = Math.floorMod(index, Math.max(1, pages()));
        shown = null;
        reopen();
    }

    private Page current() {
        if (shown == null) {
            page = Math.floorMod(page, Math.max(1, pages()));
            shown = page(page);
        }
        return shown;
    }

    @Override
    public void reopen() {
        shown = null;
        super.reopen();
    }

    @Override
    public void refresh() {
        shown = null;
        super.refresh();
    }

    @Override
    protected Component title() {
        return current().title();
    }

    @Override
    protected Backdrop backdrop() {
        Backdrop scene = current().scene();
        return scene != null ? scene : Backdrop.house(layout().rows());
    }

    @Override
    protected Component overlay() {
        return Component.text(TreeGlyphs.draw(placements(current()))).font(look().font());
    }

    /** Lines first, so the frames lie over their ends. */
    static List<TreeGlyphs.Placement> placements(Page page) {
        Map<String, Node> byId = byId(page);
        List<TreeGlyphs.Placement> out = new ArrayList<>();
        for (Edge edge : page.edges()) {
            Node from = byId.get(edge.from());
            Node to = byId.get(edge.to());
            if (from == null || to == null) {
                continue;
            }
            TreeGrid.Link link = TreeGrid.link(from.column(), from.row(), to.column(), to.row());
            out.add(new TreeGlyphs.Placement(TreeSprite.line(link.shape(), lit(from, to)), link.slotRow(),
                    link.slotColumn()));
        }
        for (Node node : page.nodes()) {
            out.add(new TreeGlyphs.Placement(TreeSprite.frame(node.state()), node.row() * 2, node.column() * 2));
        }
        return out;
    }

    static boolean lit(Node from, Node to) {
        return taken(from.state()) && taken(to.state());
    }

    private static boolean taken(NodeState state) {
        return state == NodeState.OWNED || state == NodeState.MAXED;
    }

    private static Map<String, Node> byId(Page page) {
        Map<String, Node> byId = new HashMap<>();
        for (Node node : page.nodes()) {
            byId.put(node.id(), node);
        }
        return byId;
    }

    @Override
    protected void render() {
        Page page = current();
        for (Node node : page.nodes()) {
            set(TreeGrid.slot(node.column(), node.row()), node.icon(), event -> onNode(node, event));
        }
        if (designed()) {
            return;
        }
        Map<String, Node> byId = byId(page);
        for (Edge edge : page.edges()) {
            Node from = byId.get(edge.from());
            Node to = byId.get(edge.to());
            if (from == null || to == null) {
                continue;
            }
            TreeGrid.Link link = TreeGrid.link(from.column(), from.row(), to.column(), to.row());
            set(link.slotRow() * 9 + link.slotColumn(), Icons.filler(lit(from, to)
                    ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE));
        }
    }

    /** The painting shows through the empty slots; the classic window gets the quiet black field. */
    @Override
    protected void decorate() {
        if (!designed()) {
            fillEmpty(Material.BLACK_STAINED_GLASS_PANE);
        }
    }

    @Override
    protected void paintPagingChrome(int chromeRow) {
        int pages = pages();
        if (pages > 1) {
            set(chromeRow + MenuLayout.CHROME_PREVIOUS, Icons.previousPage(Math.floorMod(page - 1, pages) + 1, pages),
                    event -> turnTo(page - 1));
            set(chromeRow + MenuLayout.CHROME_NEXT, Icons.nextPage(Math.floorMod(page + 1, pages) + 1, pages),
                    event -> turnTo(page + 1));
        }
        ItemStack summary = current().summary();
        if (summary != null) {
            set(chromeRow + MenuLayout.CHROME_PAGE, summary, this::onSummary);
        } else {
            set(chromeRow + MenuLayout.CHROME_PAGE, Icons.pageCounter(page + 1, pages));
        }
    }
}
