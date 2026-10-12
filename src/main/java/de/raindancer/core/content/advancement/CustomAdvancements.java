package de.raindancer.core.content.advancement;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Advancements any plugin can add: real ones, with the toast and the screen, granted by the plugin.
 *
 * <pre>
 * core.advancements().define(AdvancementDef.builder("enchants", "root").title("&lt;gold&gt;Enchanter")
 *         .icon(Material.ENCHANTING_TABLE).background("minecraft:gui/advancements/backgrounds/stone").build());
 * core.advancements().define(AdvancementDef.builder("enchants", "flight").parent("root").title("Up").build());
 * core.advancements().award(player.getUniqueId(), "enchants:flight");   // safe every time
 * </pre>
 *
 * <p>Not {@code Achievements}: those are points and progress the plugin keeps itself and announces in
 * chat. These are the game's own, so they show in the advancements screen, and whatever listens to
 * {@code PlayerAdvancementDoneEvent} (the economy's payout by frame) treats them like any other.
 *
 * <p>Definitions are loaded when made and again after a data reload. A changed definition applies after
 * a restart. Granting and revoking need the player's own thread.
 */
public final class CustomAdvancements {

    private static final LogChannel log = Log.of("advancements");

    private final AdvancementSink sink;
    private final Map<String, AdvancementDef> defined = new LinkedHashMap<>();
    private final Set<String> loaded = new HashSet<>();

    public CustomAdvancements(AdvancementSink sink) {
        this.sink = sink;
    }

    public synchronized void define(AdvancementDef def) {
        if (def == null) {
            return;
        }
        defined.put(def.key(), def);
        loadWhatCan();
    }

    /** Loads every definition again, parents first — after the server threw its advancements away. */
    public synchronized void republish() {
        loaded.clear();
        loadWhatCan();
    }

    public synchronized boolean isLoaded(String key) {
        return loaded.contains(normalise(key));
    }

    /** Meets every step. True only if this is what completed it. */
    public boolean award(UUID player, String key) {
        AdvancementDef def = known(key);
        return def != null && progress(player, def, def.steps());
    }

    /** Meets steps up to {@code steps} (never fewer than before). True only if this completed it. */
    public boolean progress(UUID player, String key, int steps) {
        AdvancementDef def = known(key);
        return def != null && progress(player, def, steps);
    }

    public boolean has(UUID player, String key) {
        AdvancementDef def = known(key);
        return def != null && stepsDone(player, key) >= def.steps();
    }

    public int stepsDone(UUID player, String key) {
        AdvancementDef def = known(key);
        if (def == null) {
            return 0;
        }
        Set<String> met = sink.met(player, def.key());
        int done = 0;
        for (int step = 1; step <= def.steps(); step++) {
            if (met.contains(def.criterion(step))) {
                done++;
            }
        }
        return done;
    }

    /** Takes every step back, for a mistake or a test. True if anything was taken. */
    public boolean revoke(UUID player, String key) {
        AdvancementDef def = known(key);
        boolean any = false;
        if (def != null) {
            for (int step = 1; step <= def.steps(); step++) {
                any |= sink.revoke(player, def.key(), def.criterion(step));
            }
        }
        return any;
    }

    private boolean progress(UUID player, AdvancementDef def, int steps) {
        boolean wasDone = stepsDone(player, def.key()) >= def.steps();
        boolean changed = false;
        for (int step = 1; step <= Math.min(def.steps(), steps); step++) {
            changed |= sink.grant(player, def.key(), def.criterion(step));
        }
        return changed && !wasDone && stepsDone(player, def.key()) >= def.steps();
    }

    private synchronized AdvancementDef known(String key) {
        if (key == null) {
            return null;
        }
        String normalised = normalise(key);
        AdvancementDef def = defined.get(normalised);
        if (def == null) {
            log.warn("'{}' was asked for, but nothing defines it.", key);
            return null;
        }
        return loaded.contains(normalised) ? def : null;
    }

    private void loadWhatCan() {
        boolean progressed = true;
        while (progressed) {
            progressed = false;
            List<AdvancementDef> ready = new ArrayList<>();
            for (AdvancementDef def : defined.values()) {
                if (!loaded.contains(def.key()) && parentReady(def)) {
                    ready.add(def);
                }
            }
            if (ready.isEmpty()) {
                return;
            }
            Map<String, String> batch = new LinkedHashMap<>();
            for (AdvancementDef def : ready) {
                batch.put(def.key(), AdvancementJson.of(def));
            }
            Set<String> accepted = sink.publish(batch);
            loaded.addAll(accepted);
            progressed = !accepted.isEmpty();
        }
    }

    private boolean parentReady(AdvancementDef def) {
        String parent = def.parentKey();
        if (parent == null || !defined.containsKey(parent)) {
            // Not ours: a vanilla or another plugin's advancement, which the server either has or has not.
            return parent == null || !parent.startsWith(def.plugin() + ":");
        }
        return loaded.contains(parent);
    }

    private static String normalise(String key) {
        return key.trim().toLowerCase(Locale.ROOT);
    }
}
