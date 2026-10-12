package de.raindancer.core.platform.link;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Messages as JSON — a list of them per request, so a busy poll carries everything waiting in one go. */
public final class LinkCodec {

    private LinkCodec() {
    }

    public static String encode(List<LinkMessage> messages) {
        JsonArray array = new JsonArray();
        for (LinkMessage message : messages) {
            JsonObject object = new JsonObject();
            object.addProperty("id", message.id());
            object.addProperty("topic", message.topic());
            object.addProperty("re", message.replyTo());
            object.addProperty("ask", message.ask());
            JsonObject data = new JsonObject();
            message.data().forEach(data::addProperty);
            object.add("data", data);
            array.add(object);
        }
        return array.toString();
    }

    /** @throws IllegalArgumentException for anything that is not a list of messages */
    public static List<LinkMessage> decode(String text) {
        try {
            List<LinkMessage> messages = new ArrayList<>();
            for (JsonElement element : JsonParser.parseString(text).getAsJsonArray()) {
                JsonObject object = element.getAsJsonObject();
                Map<String, String> data = new LinkedHashMap<>();
                if (object.has("data")) {
                    for (Map.Entry<String, JsonElement> entry : object.getAsJsonObject("data").entrySet()) {
                        data.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
                messages.add(new LinkMessage(object.get("id").getAsLong(), object.get("topic").getAsString(), data,
                        object.has("re") ? object.get("re").getAsLong() : 0,
                        object.has("ask") && object.get("ask").getAsBoolean()));
            }
            return messages;
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException("not a list of link messages", malformed);
        }
    }
}
