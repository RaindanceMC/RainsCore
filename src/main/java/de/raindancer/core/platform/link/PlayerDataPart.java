package de.raindancer.core.platform.link;

import io.papermc.paper.persistence.PersistentDataContainerView;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;

import java.io.IOException;
import java.util.Base64;
import java.util.Map;

/** One namespace of a player's persistent data, carried as bytes from the server that keeps it to one that shows it. */
public final class PlayerDataPart {

    private PlayerDataPart() {
    }

    /** Only the keys of {@code namespace}, serialised; empty map when the player has none. */
    public static Map<String, String> export(PersistentDataContainerView saved, String namespace) throws IOException {
        PersistentDataContainer only = saved.getAdapterContext().newPersistentDataContainer();
        saved.copyTo(only, true);
        for (NamespacedKey key : only.getKeys()) {
            if (!key.getNamespace().equals(namespace)) {
                only.remove(key);
            }
        }
        return Map.of("bytes", Base64.getEncoder().encodeToString(only.serializeToBytes()));
    }

    /** Replaces everything under {@code namespace} on the player with what was exported; other namespaces stay. */
    public static void adopt(PersistentDataContainer live, String namespace, Map<String, String> exported) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(exported.getOrDefault("bytes", ""));
        } catch (IllegalArgumentException garbled) {
            return;
        }
        PersistentDataContainer incoming = live.getAdapterContext().newPersistentDataContainer();
        try {
            incoming.readFromBytes(bytes, true);
        } catch (IOException garbled) {
            return;
        }
        for (NamespacedKey key : live.getKeys()) {
            if (key.getNamespace().equals(namespace)) {
                live.remove(key);
            }
        }
        for (NamespacedKey key : incoming.getKeys()) {
            if (!key.getNamespace().equals(namespace)) {
                incoming.remove(key);
            }
        }
        incoming.copyTo(live, true);
    }
}
