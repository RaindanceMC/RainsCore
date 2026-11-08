package de.raindancer.core.ui.menu;

import de.raindancer.core.ui.chat.Brand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * "Are you sure?", as a page of its own — the one of these on the server.
 *
 * <h2>Why it is here rather than in each plugin</h2>
 * Because it had been written three times: in the claims module, in the moderation module and in the
 * warps module. Every copy had the same two columns and slightly different words, which is the worst
 * shape for this particular page — three places to fix the next thing in one of, and the copy nobody
 * fixes is always the one guarding the button that deletes something.
 *
 * <p>It is also the page where consistency is the whole feature. <b>No is on the left and Yes is on
 * the right</b>, everywhere, because that is a habit people build: a dialog that swaps them is one
 * they learn to click through and then get wrong exactly once. {@code ConfirmMenuGrammarTest} pins
 * that, since it cannot be asserted by clicking.
 *
 * <h2>Why a page and not a chat prompt</h2>
 * Because the thing being confirmed is on screen. Somebody who opened the wrong warp's page sees the
 * wrong warp's name here, which is the only reason asking helps at all. Three rows rather than six:
 * a question with two answers on a full page reads as an empty page with two buttons lost in it.
 *
 * <h2>What a plugin still decides</h2>
 * The question, the consequences, and one closing line — {@code "This cannot be undone."} for a
 * deletion, {@code "It goes on their record either way."} for a punishment. The wording is the
 * plugin's; the shape is not.
 */
public class ConfirmMenu extends Menu {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** What most confirmations are about. */
    public static final String CANNOT_BE_UNDONE = "<dark_gray>This cannot be undone.";

    private final String question;
    private final List<String> consequences;
    private final String closingLine;
    private final Runnable onYes;

    /**
     * @param question     the title, as MiniMessage — usually {@code "<red>Delete X?"}
     * @param consequences what saying yes actually does, a line each
     * @param onYes        run on the confirming click, on the viewer's own thread
     */
    public ConfirmMenu(Player viewer, Brand brand, Menu parent, String question,
                       List<String> consequences, Runnable onYes) {
        this(viewer, brand, parent, question, consequences, CANNOT_BE_UNDONE, onYes);
    }

    /**
     * The same, with the closing line said differently.
     *
     * <p>A punishment is not undone by saying no — it goes on the record either way — so
     * "this cannot be undone" would be the wrong sentence there, and a wrong sentence on a
     * confirmation is worse than none.
     */
    public ConfirmMenu(Player viewer, Brand brand, Menu parent, String question,
                       List<String> consequences, String closingLine, Runnable onYes) {
        super(viewer, brand, parent, 3);
        this.question = question;
        this.consequences = consequences == null ? List.of() : List.copyOf(consequences);
        this.closingLine = closingLine;
        this.onYes = onYes;
    }

    /** Whether the question is asked in the game's own dialog rather than in a three-row chest: {@code -Drains.anvil=true} keeps the chest. */
    private static final boolean DIALOGS = !Boolean.getBoolean("rains.anvil");

    /**
     * Asked in the game's own confirmation dialog since 2.52.0 — the question as the title, what saying yes does
     * underneath, Yes and No — because that is what the client has a window for, and a chest with three blocks
     * in it was the next best thing. Every plugin's "are you sure?" goes through here, so every one of them asks
     * the same way. No, and Escape, go back to the page this was opened from; yes runs the action and then goes
     * back too, unless the action opened a page of its own.
     */
    @Override
    public void open() {
        if (!DIALOGS || viewer == null) {
            super.open();
            return;
        }
        // One paragraph for what it does and one for the closing line: a dialog spaces its messages generously,
        // so a message per lore line read as a list of headings.
        List<io.papermc.paper.registry.data.dialog.body.DialogBody> body = new ArrayList<>();
        if (!consequences.isEmpty()) {
            Component what = Component.empty();
            for (int at = 0; at < consequences.size(); at++) {
                what = what.append(Icons.loreLine(consequences.get(at)));
                if (at + 1 < consequences.size()) {
                    what = what.append(Component.newline());
                }
            }
            body.add(io.papermc.paper.registry.data.dialog.body.DialogBody.plainMessage(what, 300));
        }
        if (closingLine != null && !closingLine.isBlank()) {
            body.add(io.papermc.paper.registry.data.dialog.body.DialogBody.plainMessage(Icons.loreLine(closingLine), 300));
        }
        org.bukkit.plugin.Plugin plugin = org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(ConfirmMenu.class);
        net.kyori.adventure.text.event.ClickCallback.Options once = net.kyori.adventure.text.event.ClickCallback.Options
                .builder().uses(1).lifetime(java.time.Duration.ofMinutes(10)).build();
        var yes = io.papermc.paper.registry.data.dialog.ActionButton.builder(MINI.deserialize("<green>Yes, do it"))
                .tooltip(MINI.deserialize("<gray>Go ahead.")).width(150)
                .action(io.papermc.paper.registry.data.dialog.action.DialogAction.customClick((response, audience) ->
                        de.raindancer.core.platform.util.Scheduling.entity(plugin, viewer, () -> {
                            if (!viewer.isOnline()) {
                                return;
                            }
                            if (onYes != null) {
                                onYes.run();
                            }
                            // Back where this came from — unless the action already put a page on screen.
                            if (!(viewer.getOpenInventory().getTopInventory().getHolder(false) instanceof Menu)) {
                                leave();
                            }
                        }), once)).build();
        var no = io.papermc.paper.registry.data.dialog.ActionButton.builder(MINI.deserialize("<red>No, leave it alone"))
                .tooltip(MINI.deserialize("<gray>Nothing happens.")).width(150)
                .action(io.papermc.paper.registry.data.dialog.action.DialogAction.customClick((response, audience) ->
                        de.raindancer.core.platform.util.Scheduling.entity(plugin, viewer, () -> {
                            if (viewer.isOnline()) {
                                leave();
                            }
                        }), once)).build();
        io.papermc.paper.dialog.Dialog dialog = io.papermc.paper.dialog.Dialog.create(factory -> factory.empty()
                .base(io.papermc.paper.registry.data.dialog.DialogBase.builder(Icons.name(question))
                        .canCloseWithEscape(true).pause(false)
                        .afterAction(io.papermc.paper.registry.data.dialog.DialogBase.DialogAfterAction.CLOSE)
                        .body(body).build())
                .type(io.papermc.paper.registry.data.dialog.type.DialogType.confirmation(yes, no)));
        viewer.closeInventory();
        viewer.showDialog(dialog);
    }

    @Override
    protected Component title() {
        return MINI.deserialize(question);
    }

    @Override
    public String breadcrumb() {
        return "Are you sure?";
    }

    @Override
    protected void render() {
        List<String> lore = new ArrayList<>(consequences);
        if (closingLine != null && !closingLine.isBlank()) {
            lore.add("");
            lore.add(closingLine);
        }

        // Left. Nothing but going back — never the thing being confirmed, however the caller
        // arranged its lambdas.
        band(MenuLayout.WHO, 6, Icons.of(Material.RED_CONCRETE, "<red>No, leave it alone",
                        "<gray>Nothing happens."),
                click -> leave());

        band(MenuLayout.WHO, 4, Icons.of(Material.BOOK, "<gray>What this does", lore));

        // Right, always.
        band(MenuLayout.WHO, 2, Icons.of(Material.LIME_CONCRETE, "<green>Yes, do it",
                        "<gray>Go ahead."),
                click -> {
                    if (onYes != null) {
                        onYes.run();
                    }
                    // And then out, always. This used to do nothing after the action, so the dialog stayed on
                    // screen with no sign anything had happened — reported as "I click it and nothing
                    // happens", and the action had in fact run every time.
                    //
                    // It went unnoticed in most plugins by luck: their callbacks open a page of their own,
                    // which replaces this one and reads as an answer. A callback that instead refreshes the
                    // page underneath redraws an inventory nobody is looking at, and then there is nothing to
                    // see at all — the change only appears once somebody presses Back.
                    //
                    // After the action, never before: reopening first would redraw the parent from state the
                    // action has not changed yet, so it would show the old world and only catch up on the next
                    // click. And leaving is this dialog's own business — a caller that has to remember to
                    // close the thing it opened is a caller that will not.
                    backToWhoeverOpenedThis();
                });
    }
}
