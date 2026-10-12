package de.raindancer.core.platform.link;

import java.util.Map;

/**
 * One message over a {@link ServerLink}.
 *
 * @param id      unique on the side that sent it
 * @param topic   what it is about — the handler it goes to
 * @param data    the contents, text only, so either side can be any version of anything
 * @param replyTo the id of the message this answers, or 0 when it answers nothing
 * @param ask     whether the sender waits for an answer
 */
public record LinkMessage(long id, String topic, Map<String, String> data, long replyTo, boolean ask) {

    public LinkMessage {
        topic = topic == null ? "" : topic;
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    public boolean isReply() {
        return replyTo != 0;
    }
}
