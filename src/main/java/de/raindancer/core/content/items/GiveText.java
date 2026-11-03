package de.raindancer.core.content.items;

import de.raindancer.core.ui.chat.Style;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.util.List;

/** What {@code /give} says, in the server's colours; the item is its own hoverable name. */
public final class GiveText {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    /** Past this many names the line counts players instead. */
    private static final int MOST_NAMES = 3;

    private GiveText() {
    }

    public static Component given(int amount, Component item, List<String> names) {
        return MINI.deserialize("<" + Style.ok() + ">✔</" + Style.ok() + "> <" + Style.itemLore() + ">Gave <"
                        + Style.itemName() + "><amount> ×</" + Style.itemName() + "> <item> to <"
                        + Style.itemName() + "><who>",
                Placeholder.unparsed("amount", String.valueOf(amount)), Placeholder.component("item", item),
                Placeholder.unparsed("who", who(names)));
    }

    /** @param from who gave it, or null for the console and command blocks */
    public static Component received(int amount, Component item, String from) {
        return MINI.deserialize("<" + Style.ok() + ">+</" + Style.ok() + "> <" + Style.itemName() + "><amount> ×</"
                        + Style.itemName() + "> <item>" + (from == null ? "" : " <" + Style.itemLore() + ">from <"
                        + Style.itemName() + "><from>"),
                Placeholder.unparsed("amount", String.valueOf(amount)), Placeholder.component("item", item),
                Placeholder.unparsed("from", from == null ? "" : from));
    }

    /** The button after a "did you mean": click and the corrected give runs. */
    public static final String BUTTON = "<green>[Give that]";

    public static Component didYouMean(String meant, Component button) {
        return MINI.deserialize("<" + Style.warn() + ">?</" + Style.warn() + "> <" + Style.itemLore()
                        + ">Did you mean <" + Style.itemName() + "><meant></" + Style.itemName() + "><" + Style.itemLore()
                        + ">? <button>",
                Placeholder.unparsed("meant", meant), Placeholder.component("button", button));
    }

    public static Component usage() {
        return MINI.deserialize("<" + Style.itemLore() + ">Usage: <" + Style.itemName() + "><shape></" + Style.itemName()
                        + "> <" + Style.itemLore() + ">— e.g. <" + Style.itemName() + "><ours></" + Style.itemName()
                        + "> <" + Style.itemLore() + ">or <" + Style.itemName() + "><vanilla>",
                Placeholder.unparsed("shape", "/give <player> <item> [amount]"),
                Placeholder.unparsed("ours", "/give @s fishing:rod-void"),
                Placeholder.unparsed("vanilla", "/give @s diamond 64"));
    }

    private static String who(List<String> names) {
        if (names.size() > MOST_NAMES) {
            return names.size() + " players";
        }
        if (names.size() == 1) {
            return names.getFirst();
        }
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.getLast();
    }
}
