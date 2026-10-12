package de.raindancer.core.content.advancement;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Loaded with {@code persist=false} on purpose: Paper never replaces an advancement it already holds, so
 * a stored copy would freeze the first definition for ever. They are loaded afresh on every start and
 * after every data reload instead — {@link CustomAdvancements#republish()}.
 */
public final class BukkitAdvancementSink implements AdvancementSink {

    private static final LogChannel log = Log.of("advancements");

    @Override
    public Set<String> publish(Map<String, String> jsonByKey) {
        Map<org.bukkit.NamespacedKey, String> toLoad = new LinkedHashMap<>();
        jsonByKey.forEach((key, json) -> toLoad.put(NamespacedKey.fromString(key), json));
        Set<String> loaded = new HashSet<>();
        for (Map.Entry<NamespacedKey, String> entry : toLoad.entrySet()) {
            try {
                if (Bukkit.getAdvancement(entry.getKey()) != null
                        || Bukkit.getUnsafe().loadAdvancement(entry.getKey(), entry.getValue(), false) != null) {
                    loaded.add(entry.getKey().toString());
                } else {
                    log.warn("The server refused advancement {}.", entry.getKey());
                }
            } catch (RuntimeException broken) {
                log.error(broken, "Advancement {} could not be loaded.", entry.getKey());
            }
        }
        return loaded;
    }

    @Override
    public Set<String> met(UUID player, String key) {
        AdvancementProgress progress = progress(player, key);
        return progress == null ? Set.of() : new HashSet<>(progress.getAwardedCriteria());
    }

    @Override
    public boolean grant(UUID player, String key, String criterion) {
        AdvancementProgress progress = progress(player, key);
        return progress != null && progress.awardCriteria(criterion);
    }

    @Override
    public boolean revoke(UUID player, String key, String criterion) {
        AdvancementProgress progress = progress(player, key);
        return progress != null && progress.revokeCriteria(criterion);
    }

    private static AdvancementProgress progress(UUID who, String key) {
        Player player = Bukkit.getPlayer(who);
        Advancement advancement = Bukkit.getAdvancement(NamespacedKey.fromString(key));
        return player == null || advancement == null ? null : player.getAdvancementProgress(advancement);
    }
}
