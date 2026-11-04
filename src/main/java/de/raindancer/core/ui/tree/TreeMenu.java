package de.raindancer.core.ui.tree;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.menu.Backdrop;
import de.raindancer.core.ui.menu.Arrow;
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
 * A skill tree, one page per branch — or one big canvas — nodes on a {@link TreeGrid}, joined by lines. A canvas
 * bigger than the window scrolls ({@link TreeView}): the chrome's left and right arrows scroll while there is more that
 * way and turn the page after, up and down sit on either side of them; shift-click moves a whole window.
 *
 * <p>For a player wearing the pack the frames and lines are drawn by the window title over the page's painting, in
 * the {@link #look()}'s font, so a line lights up the moment both its ends are taken. Without it the lines are glass
 * panes — lime when lit — and the icon alone says how a node stands.
 *
 * <p>Turning a page or taking a node changes the title, and a window cannot be retitled: those reopen the window.
 */
public abstract class TreeMenu extends Menu {

    /** A node as one player sees it, at its place on the canvas. */
    public record Node(String id, int column, int row, NodeState state, ItemStack icon, boolean mastery) {

        public Node(String id, int column, int row, NodeState state, ItemStack icon) {
            this(id, column, row, state, icon, false);
        }
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
    /** Where the window was on each page, so turning back finds it where it was left. */
    private final Map<Integer, int[]> scrolled = new HashMap<>();
    private TreeView view;

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
        remember();
        page = Math.floorMod(index, Math.max(1, pages()));
        shown = null;
        reopen();
    }

    /** Scrolls the window over the page's canvas, a column or a row at a time. */
    public void scroll(int columns, int rows) {
        current();
        view.scroll(columns, rows);
        remember();
        reopen();
    }

    /** Opens the page with the window over that node — a branch's start, its mastery badge. */
    public void showAt(int column, int row) {
        current();
        view.lookAt(column, row);
        remember();
        reopen();
    }

    private void remember() {
        if (view != null) {
            scrolled.put(page, new int[]{view.column(), view.row()});
        }
    }

    private Page current() {
        if (shown == null) {
            page = Math.floorMod(page, Math.max(1, pages()));
            shown = page(page);
            view = TreeView.of(shown.nodes().stream().map(node -> new int[]{node.column(), node.row()}).toList());
            int[] at = scrolled.get(page);
            if (at != null) {
                view.scroll(at[0], at[1]);
            }
        }
        return shown;
    }

    /** The window over the current page's canvas. */
    protected TreeView view() {
        current();
        return view;
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
        Page page = current();
        return Component.text(TreeGlyphs.draw(placements(page, view))).font(look().font());
    }

    /** Lines first, so the frames lie over their ends. A whole-page window, for a tree that fits in one. */
    static List<TreeGlyphs.Placement> placements(Page page) {
        return placements(page, TreeView.of(List.of()));
    }

    /** What the window shows: nodes in it, and lines with both ends in it. */
    static List<TreeGlyphs.Placement> placements(Page page, TreeView view) {
        Map<String, Node> byId = byId(page);
        List<TreeGlyphs.Placement> out = new ArrayList<>();
        for (Edge edge : page.edges()) {
            Node from = byId.get(edge.from());
            Node to = byId.get(edge.to());
            if (from == null || to == null || !view.shows(from.column(), from.row())
                    || !view.shows(to.column(), to.row())) {
                continue;
            }
            int[] a = view.local(from.column(), from.row());
            int[] b = view.local(to.column(), to.row());
            TreeGrid.Link link = TreeGrid.link(a[0], a[1], b[0], b[1]);
            out.add(new TreeGlyphs.Placement(TreeSprite.line(link.shape(), lit(from, to)), link.slotRow(),
                    link.slotColumn()));
        }
        for (Node node : page.nodes()) {
            if (!view.shows(node.column(), node.row())) {
                continue;
            }
            int[] at = view.local(node.column(), node.row());
            out.add(new TreeGlyphs.Placement(TreeSprite.frame(node.state(), node.mastery()), at[1] * 2, at[0] * 2));
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
            if (!view.shows(node.column(), node.row())) {
                continue;
            }
            int[] at = view.local(node.column(), node.row());
            set(TreeGrid.slot(at[0], at[1]), node.icon(), event -> onNode(node, event));
        }
        if (designed()) {
            return;
        }
        Map<String, Node> byId = byId(page);
        for (Edge edge : page.edges()) {
            Node from = byId.get(edge.from());
            Node to = byId.get(edge.to());
            if (from == null || to == null || !view.shows(from.column(), from.row())
                    || !view.shows(to.column(), to.row())) {
                continue;
            }
            int[] a = view.local(from.column(), from.row());
            int[] b = view.local(to.column(), to.row());
            TreeGrid.Link link = TreeGrid.link(a[0], a[1], b[0], b[1]);
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

    /** Slots of the chrome row the window scrolls up and down with; left and right share the page arrows'. */
    static final int CHROME_UP = 2;
    static final int CHROME_DOWN = 7;

    @Override
    protected void paintPagingChrome(int chromeRow) {
        int pages = pages();
        current();
        // Left and right scroll the canvas while there is more of it that way, then turn the page.
        if (view.canScrollLeft()) {
            set(chromeRow + MenuLayout.CHROME_PREVIOUS, scrollIcon(Arrow.LEFT, "◀ Left", view.hiddenLeft(), "column"),
                    event -> scroll(event.isShiftClick() ? -TreeGrid.COLUMNS : -1, 0));
        } else if (pages > 1) {
            set(chromeRow + MenuLayout.CHROME_PREVIOUS, Icons.previousPage(Math.floorMod(page - 1, pages) + 1, pages),
                    event -> turnTo(page - 1));
        }
        if (view.canScrollRight()) {
            set(chromeRow + MenuLayout.CHROME_NEXT, scrollIcon(Arrow.RIGHT, "Right ▶", view.hiddenRight(), "column"),
                    event -> scroll(event.isShiftClick() ? TreeGrid.COLUMNS : 1, 0));
        } else if (pages > 1) {
            set(chromeRow + MenuLayout.CHROME_NEXT, Icons.nextPage(Math.floorMod(page + 1, pages) + 1, pages),
                    event -> turnTo(page + 1));
        }
        if (view.canScrollUp()) {
            set(chromeRow + CHROME_UP, scrollIcon(Arrow.UP, "▲ Up", view.hiddenUp(), "row"),
                    event -> scroll(0, event.isShiftClick() ? -TreeGrid.ROWS : -1));
        }
        if (view.canScrollDown()) {
            set(chromeRow + CHROME_DOWN, scrollIcon(Arrow.DOWN, "▼ Down", view.hiddenDown(), "row"),
                    event -> scroll(0, event.isShiftClick() ? TreeGrid.ROWS : 1));
        }
        ItemStack summary = current().summary();
        if (summary != null) {
            set(chromeRow + MenuLayout.CHROME_PAGE, summary, this::onSummary);
        } else {
            set(chromeRow + MenuLayout.CHROME_PAGE, Icons.pageCounter(page + 1, pages));
        }
    }

    private static ItemStack scrollIcon(Arrow way, String title, int hidden, String unit) {
        return Icons.arrow(way, "<white>" + title, java.util.List.of(
                "<gray>" + hidden + " more " + unit + (hidden == 1 ? "" : "s") + " that way.",
                "<dark_gray>Shift-click for a whole window."));
    }
}
