package de.raindancer.core.ui.messages;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

/**
 * A chat line another server of the network wrote — an answer it rendered with its own wording — made safe to
 * show here: its words, colours and hover text are kept, but every click action and insertion is taken off. A
 * line that arrives over the network is trusted to say something, never to make the reader run a command.
 */
public final class FromElsewhere {

    private FromElsewhere() {
    }

    /** A line sent as JSON; something that is not a component is shown as the plain text it is. */
    public static Component line(String json) {
        Component read;
        try {
            read = GsonComponentSerializer.gson().deserialize(json);
        } catch (RuntimeException notAComponent) {
            return Component.text(json == null ? "" : json);
        }
        return harmless(read);
    }

    /** {@code component} and everything in it, without click actions or insertions. */
    public static Component harmless(Component component) {
        Component bare = component.clickEvent(null).insertion(null)
                .children(component.children().stream().map(FromElsewhere::harmless).toList());
        // A translation's arguments are components of their own, and can carry a click as well.
        if (bare instanceof net.kyori.adventure.text.TranslatableComponent translatable) {
            return translatable.arguments(translatable.arguments().stream()
                    .map(argument -> argument.value() instanceof Component inner
                            ? net.kyori.adventure.text.TranslationArgument.component(harmless(inner)) : argument)
                    .toList());
        }
        return bare;
    }
}
