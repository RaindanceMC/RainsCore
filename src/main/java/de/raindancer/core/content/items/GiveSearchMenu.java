package de.raindancer.core.content.items;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.chat.Style;
import de.raindancer.core.ui.choose.AmountChooser;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.PaginatedMenu;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code /give <player> search <words>}: every item on the server that answers — vanilla's and every plugin's —
 * closest first, as the shop's search finds them. A click asks how many, a shift-click gives a stack.
 */
public final class GiveSearchMenu extends PaginatedMenu<String> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final String typed;
    private final List<Player> targets;
    private final List<String> found;
    private final Map<String, ItemStack> shown = new HashMap<>();

    /** @param names every item's key and the name a player reads, to search */
    public GiveSearchMenu(Player viewer, List<Player> targets, String typed, Map<String, String> names) {
        super(viewer, new Brand("Give"), null);
        this.typed = typed == null ? "" : typed.strip();
        this.targets = List.copyOf(targets);
        this.found = GiveSearch.rank(this.typed, names);
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<" + Style.titleLabel() + ">Give <" + Style.itemLore() + ">· \""
                + MINI.escapeTags(typed) + "\"");
    }

    @Override
    public String breadcrumb() {
        return "Give";
    }

    @Override
    protected List<String> entries() {
        return found;
    }

    private ItemStack one(String key) {
        return shown.computeIfAbsent(key, any -> GiveCommand.make(key, 1).orElseGet(() -> new ItemStack(Material.BARRIER)));
    }

    @Override
    protected ItemStack icon(String key) {
        ItemStack item = one(key);
        return Icons.showing(item, null, List.of("<" + Style.itemLore() + ">" + key, "",
                "<" + Style.ok() + ">Click<" + Style.itemLore() + ">: choose how many",
                "<" + Style.ok() + ">Shift-click<" + Style.itemLore() + ">: a stack of " + item.getMaxStackSize(),
                "<" + Style.itemLore() + ">for " + who()));
    }

    private String who() {
        List<Player> online = online();
        if (online.size() == 1) {
            return online.getFirst().equals(viewer) ? "yourself" : online.getFirst().getName();
        }
        return online.size() + " players";
    }

    private List<Player> online() {
        return targets.stream().filter(Player::isOnline).toList();
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.BARRIER, "<" + Style.bad() + ">Nothing matches \"" + MINI.escapeTags(typed) + "\"",
                "<" + Style.itemLore() + ">Try fewer words, or part of a name.");
    }

    @Override
    protected void onClick(String key, InventoryClickEvent event) {
        List<Player> online = online();
        if (online.isEmpty()) {
            viewer.sendMessage(Component.translatable("argument.entity.notfound.player"));
            return;
        }
        int stack = one(key).getMaxStackSize();
        if (event.isShiftClick()) {
            GiveCommand.give(viewer, online, key, stack);
            return;
        }
        new AmountChooser(viewer, brand(), this, "How many?", 1, 1, GiveRequest.MOST,
                amount -> GiveCommand.give(viewer, online(), key, amount))
                .stepsOf(1, Math.max(2, stack), 640)
                .open();
    }

    @Override
    protected List<String> helpLines() {
        return List.of("<gray>Everything on the server that answers your words,",
                "<gray>vanilla's items and every plugin's, closest first.",
                "<gray>Click to choose how many, shift-click for a stack.");
    }
}
