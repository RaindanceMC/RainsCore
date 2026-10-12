package de.raindancer.core.content.advancement;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

/**
 * The datapack JSON of an advancement. Every criterion is the {@code impossible} trigger, so nothing in
 * vanilla can grant it: only the plugin that knows the thing happened.
 */
final class AdvancementJson {

    private AdvancementJson() {
    }

    static String of(AdvancementDef def) {
        JsonObject root = new JsonObject();
        if (def.parentKey() != null) {
            root.addProperty("parent", def.parentKey());
        }

        JsonObject display = new JsonObject();
        JsonObject icon = new JsonObject();
        icon.addProperty("id", def.icon().getKey().toString());
        display.add("icon", icon);
        display.add("title", component(def.title()));
        display.add("description", component(def.description()));
        display.addProperty("frame", def.frame().json());
        display.addProperty("show_toast", def.toast());
        display.addProperty("announce_to_chat", def.announce());
        display.addProperty("hidden", def.hidden());
        if (def.parentKey() == null && def.background() != null) {
            display.addProperty("background", def.background());
        }
        root.add("display", display);

        JsonObject criteria = new JsonObject();
        JsonArray requirements = new JsonArray();
        for (int step = 1; step <= def.steps(); step++) {
            JsonObject criterion = new JsonObject();
            criterion.addProperty("trigger", "minecraft:impossible");
            criteria.add(def.criterion(step), criterion);
            JsonArray one = new JsonArray();
            one.add(def.criterion(step));
            requirements.add(one);
        }
        root.add("criteria", criteria);
        root.add("requirements", requirements);
        return root.toString();
    }

    private static com.google.gson.JsonElement component(String miniMessage) {
        return JsonParser.parseString(GsonComponentSerializer.gson()
                .serialize(MiniMessage.miniMessage().deserialize(miniMessage)));
    }
}
