package de.raindancer.core.ui.choose;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.chat.Style;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/**
 * Picking a number without typing it.
 *
 * <h2>Why Core owns this</h2>
 * Six places needed it — an entry fee, its XP levels, the pantry threshold, an effect level, a claim's depth, a
 * fence height — and each had grown its own answer. Nudge buttons at ±1 and ±10, which is forty clicks to set a
 * fee of four hundred; or a chat prompt, which closes the menu and loses whatever was half-configured.
 *
 * <p>The arithmetic is static and tested on its own, because that is where every bug in the screens this
 * replaces actually was: a stepper that wrapped past its maximum, a range that would not open when a server
 * lowered its limit under an existing value.
 *
 * <h2>Nothing happens until Accept</h2>
 * Back is the way out. A dialog that changes nothing until it is accepted needs no second word for "never
 * mind", and the caller is handed the value once rather than watching it change.
 */
public final class AmountChooser extends Menu {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** The steps, smallest span outward. ±1 and ±10 alone is why every screen grew a prompt beside it. */
    private static final int[] STEPS = {-100, -10, -1, 1, 10, 100};

    /** Where the steppers sit: three either side of the value, so the number is between the directions. */
    private static final int[] STEP_SLOTS = {0, 1, 2, 6, 7, 8};

    private final String label;
    private final int min;
    private final int max;
    private final IntConsumer onAccept;
    private final int start;
    private int value;
    private int[] steps = STEPS;
    private boolean wraps;
    private IntFunction<String> shown = String::valueOf;
    private IntConsumer preview;
    private IntFunction<String> stepNames = step -> (step > 0 ? "+" : "") + step;
    private boolean accepted;

    /**
     * @param label what is being set, shown under the number — "Entry fee", "XP levels"
     * @param start where to open; brought inside the range if a server lowered its limit under an existing value
     * @param onAccept handed the value once, when Accept is clicked. Never called if they back out
     */
    public AmountChooser(Player viewer, Brand brand, Menu parent, String label,
                         int start, int min, int max, IntConsumer onAccept) {
        super(viewer, brand, parent, 3);
        this.label = label == null || label.isBlank() ? "Amount" : label;
        this.min = Math.min(min, max);
        this.max = Math.max(min, max);
        this.value = stepped(start, 0, min, max);
        this.start = this.value;
        this.onAccept = onAccept;
    }

    /**
     * Its own step sizes instead of 1, 10 and 100 — each becomes a minus and a plus button, at most four a side
     * (the finest kept). A time of day wants an hour, ten minutes, a minute and a single tick.
     */
    public AmountChooser stepsOf(int... sizes) {
        this.steps = stepsFor(sizes).stream().mapToInt(Integer::intValue).toArray();
        return this;
    }

    /** What each step button says: "+1 hour" rather than +1000. Handed the signed step. */
    public AmountChooser namingSteps(IntFunction<String> naming) {
        if (naming != null) {
            this.stepNames = naming;
        }
        return this;
    }

    /** Round the ends instead of stopping at them — for something without an end, like a time of day. */
    public AmountChooser wrapping() {
        this.wraps = true;
        return this;
    }

    /** How the value is written: "14:30" rather than 8500. */
    public AmountChooser showing(IntFunction<String> format) {
        this.shown = format == null ? String::valueOf : format;
        return this;
    }

    /**
     * Shown live while choosing: handed every value as it changes, and the value it opened on again if they
     * leave without Accept — so the preview never outlives the dialog.
     */
    public AmountChooser previewing(IntConsumer show) {
        this.preview = show;
        return this;
    }

    /** The minus and plus buttons for these sizes, largest minus first: sign, zero and duplicates tidied. */
    public static List<Integer> stepsFor(int[] sizes) {
        java.util.TreeSet<Integer> spans = new java.util.TreeSet<>();
        if (sizes != null) {
            for (int size : sizes) {
                if (size != 0 && size != Integer.MIN_VALUE) {
                    spans.add(Math.abs(size));
                }
            }
        }
        List<Integer> kept = spans.stream().limit(4).toList();
        List<Integer> all = new ArrayList<>(kept.size() * 2);
        for (int index = kept.size() - 1; index >= 0; index--) {
            all.add(-kept.get(index));
        }
        all.addAll(kept);
        return List.copyOf(all);
    }

    /** The value a step lands on: round the range when it wraps, else clamped to it. */
    public static int stepped(int from, int step, int min, int max, boolean wraps) {
        int low = Math.min(min, max);
        int high = Math.max(min, max);
        if (!wraps) {
            return stepped(from, step, low, high);
        }
        long span = (long) high - low + 1;
        return (int) (low + Math.floorMod((long) from + step - low, span));
    }

    /** Whether a step would move the value, round the range when it wraps. */
    public static boolean reachable(int from, int step, int min, int max, boolean wraps) {
        return stepped(from, step, min, max, wraps) != stepped(from, 0, min, max, wraps);
    }

    /** The value a step lands on: clamped to the range, never wrapped. */
    public static int stepped(int from, int step, int min, int max) {
        int low = Math.min(min, max);
        int high = Math.max(min, max);
        long wanted = (long) from + step;
        return (int) Math.max(low, Math.min(high, wanted));
    }

    /** Whether a step would move the value at all. Shown greyed rather than hidden when it would not. */
    public static boolean reachable(int from, int step, int min, int max) {
        return stepped(from, step, min, max) != stepped(from, 0, min, max);
    }

    /** The steps this offers, for the test that pins them. */
    public static List<Integer> steps() {
        List<Integer> all = new ArrayList<>(STEPS.length);
        for (int step : STEPS) {
            all.add(step);
        }
        return List.copyOf(all);
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<" + Style.titleLabel() + ">" + label);
    }

    @Override
    protected void render() {
        // The number itself, stacked so the count is legible on the icon as well as in its name.
        set(MenuLayout.HEADER_SUBJECT, Icons.of(Material.PAPER, "<white><bold>" + shown.apply(value),
                "<gray>" + label,
                "<dark_gray>anything from " + shown.apply(min) + " to " + shown.apply(max)));

        int side = steps.length / 2;
        for (int index = 0; index < steps.length; index++) {
            int step = steps[index];
            boolean reachable = reachable(value, step, min, max, wraps);
            // The default three a side keep their old places; a caller's own sizes sit right beside the value.
            int slot = steps == STEPS ? STEP_SLOTS[index]
                    : index < side ? MenuLayout.HEADER_SUBJECT - side + index : MenuLayout.HEADER_SUBJECT + 1 + index - side;
            set(slot,
                    Icons.of(step < 0 ? Material.RED_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE,
                            (step < 0 ? "<red>" : "<green>") + stepNames.apply(step),
                            reachable ? "<gray>Click to apply" : "<dark_gray>out of range"),
                    click -> change(stepped(value, step, min, max, wraps)));
        }

        set(11, Icons.of(Material.HOPPER, "<yellow>Least <gray>(" + shown.apply(min) + ")"),
                click -> change(min));

        set(13, Icons.of(Material.LIME_CONCRETE, "<green><bold>Accept", "<gray>Use " + shown.apply(value)),
                click -> {
                    accepted = true;
                    if (onAccept != null) {
                        onAccept.accept(value);
                    }
                    // Back to whoever opened this, so the caller's screen redraws with the new number.
                    // This one always did; the other five did not, which is why it is a shared method now.
                    backToWhoeverOpenedThis();
                });

        set(15, Icons.of(Material.BEACON, "<yellow>Most <gray>(" + shown.apply(max) + ")"),
                click -> change(max));
    }

    private void change(int to) {
        value = to;
        if (preview != null) {
            preview.accept(value);
        }
        refresh();
    }

    @Override
    public void handleClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        // Left without Accept — Back, Escape, another window — and a preview is showing: put it back.
        if (!accepted && preview != null && value != start) {
            value = start;
            preview.accept(start);
            // Back has already drawn the page behind this one, with the preview still in it.
            if (parent() != null) {
                parent().refresh();
            }
        }
        super.handleClose(event);
    }
}
