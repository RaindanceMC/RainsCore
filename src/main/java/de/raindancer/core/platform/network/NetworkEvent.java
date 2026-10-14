package de.raindancer.core.platform.network;

/**
 * Something one server of a network told the others: "punishments of player X changed".
 *
 * @param id      the event's place in the network's log, increasing
 * @param at      when it was published, epoch millis of the publishing server
 * @param server  the id of the server that published it
 * @param topic   what it is about — usually the store that changed ({@code punishments})
 * @param key     which part of it — usually a player's or an entry's id; may be empty
 * @param payload anything small the receivers need beyond "go and read it again"; may be empty
 */
public record NetworkEvent(long id, long at, String server, String topic, String key, String payload) {

    public NetworkEvent {
        key = key == null ? "" : key;
        payload = payload == null ? "" : payload;
    }
}
