package de.raindancer.core.social.support;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.network.NetworkBus;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who has supported the server — bought its owner a coffee — across the whole network: one place for whatever
 * thanks them (a chat badge, a cosmetic) to ask, and for whatever learns of a donation to write.
 *
 * <pre>
 * players:
 *   &lt;uuid&gt;:
 *     since: 2026-10-26T10:00:00Z
 *     coffees: 3
 *     cents: 1500        # what they gave, before fees, in the smallest unit of the currency
 *     net-cents: 1281    # what reached the owner, as far as anybody could tell
 *     supports: 2        # how many times they supported
 *     member: true       # a membership running right now
 *     thanked: true
 * </pre>
 * A reload keeps what this server has not written yet; a document that cannot be read is never written over.
 * Reads are from memory and safe from any thread; {@link #load} and {@link #save} are disk work.
 */
public final class Supporters {

    /**
     * @param thanked  whether they have been told, since their latest coffee
     * @param cents    everything they gave, before fees
     * @param netCents what reached the owner after fees, as estimated when it arrived
     */
    public record Supporter(UUID player, Instant since, int coffees, boolean thanked, long cents, long netCents,
                            int supports, boolean member) {

        public Supporter(UUID player, Instant since, int coffees, boolean thanked) {
            this(player, since, coffees, thanked, 0, 0, 1, false);
        }

        public Supporter(UUID player, Instant since, int coffees, boolean thanked, long cents, long netCents) {
            this(player, since, coffees, thanked, cents, netCents, 1, false);
        }
    }

    private final YamlStore store;
    private final Map<UUID, Supporter> supporters = new ConcurrentHashMap<>();
    private final Map<UUID, Supporter> unwritten = new ConcurrentHashMap<>();
    private final Set<UUID> revoked = ConcurrentHashMap.newKeySet();
    private volatile boolean readable = true;

    public Supporters(YamlStore store) {
        this.store = store;
    }

    public synchronized void load() {
        Optional<YamlConfiguration> read = store.readIfReadable();
        if (read.isEmpty()) {
            readable = false;
            return;
        }
        readable = true;
        Map<UUID, Supporter> fresh = new HashMap<>();
        ConfigurationSection players = read.get().getConfigurationSection("players");
        if (players != null) {
            for (String rawId : players.getKeys(false)) {
                ConfigurationSection theirs = players.getConfigurationSection(rawId);
                if (theirs == null) {
                    continue;
                }
                try {
                    UUID who = UUID.fromString(rawId);
                    fresh.put(who, new Supporter(who, Instant.parse(theirs.getString("since", "")),
                            Math.max(1, theirs.getInt("coffees", 1)), theirs.getBoolean("thanked"),
                            Math.max(0, theirs.getLong("cents")), Math.max(0, theirs.getLong("net-cents")),
                            Math.max(1, theirs.getInt("supports", 1)), theirs.getBoolean("member")));
                } catch (IllegalArgumentException | DateTimeParseException handEdited) {
                    // one broken entry must not cost everybody else theirs
                }
            }
        }
        fresh.putAll(unwritten);
        revoked.forEach(fresh::remove);
        supporters.keySet().retainAll(fresh.keySet());
        supporters.putAll(fresh);
    }

    public boolean isSupporter(UUID player) {
        return player != null && supporters.containsKey(player);
    }

    public Optional<Supporter> of(UUID player) {
        return player == null ? Optional.empty() : Optional.ofNullable(supporters.get(player));
    }

    public List<Supporter> all() {
        return List.copyOf(supporters.values());
    }

    /** Adds {@code coffees} (at least one) and asks for a new thank-you. Held until {@link #save}. */
    public synchronized Supporter credit(UUID player, int coffees, Instant at) {
        return credit(player, coffees, 0, 0, at);
    }

    /** The same, with what they gave: before fees and, estimated, after. */
    public synchronized Supporter credit(UUID player, int coffees, long cents, long netCents, Instant at) {
        Supporter before = supporters.get(player);
        Supporter now = new Supporter(player, before == null ? at : before.since(),
                (before == null ? 0 : before.coffees()) + Math.max(1, coffees), false,
                (before == null ? 0 : before.cents()) + Math.max(0, cents),
                (before == null ? 0 : before.netCents()) + Math.max(0, netCents),
                (before == null ? 0 : before.supports()) + 1, before != null && before.member());
        put(now);
        return now;
    }

    /** A membership started, renewed or ended. Only for somebody who is a supporter already. */
    public synchronized void member(UUID player, boolean member) {
        Supporter before = supporters.get(player);
        if (before != null && before.member() != member) {
            put(new Supporter(player, before.since(), before.coffees(), before.thanked(), before.cents(),
                    before.netCents(), before.supports(), member));
        }
    }

    public synchronized void thanked(UUID player) {
        Supporter before = supporters.get(player);
        if (before != null && !before.thanked()) {
            put(new Supporter(player, before.since(), before.coffees(), true, before.cents(), before.netCents(),
                    before.supports(), before.member()));
        }
    }

    /** @return whether they were one */
    public synchronized boolean revoke(UUID player) {
        if (supporters.remove(player) == null) {
            return false;
        }
        unwritten.remove(player);
        revoked.add(player);
        return true;
    }

    private void put(Supporter supporter) {
        revoked.remove(supporter.player());
        supporters.put(supporter.player(), supporter);
        unwritten.put(supporter.player(), supporter);
    }

    public synchronized boolean save() {
        if (!readable) {
            load();
            if (!readable) {
                return false;
            }
        }
        if (unwritten.isEmpty() && revoked.isEmpty()) {
            return true;
        }
        Map<UUID, Supporter> sent = Map.copyOf(unwritten);
        Set<UUID> gone = new HashSet<>(revoked);
        boolean written = store.write(yaml -> {
            gone.forEach(who -> yaml.set("players." + who, null));
            supporters.forEach((who, supporter) -> {
                String base = "players." + who;
                yaml.set(base + ".since", supporter.since().toString());
                yaml.set(base + ".coffees", supporter.coffees());
                yaml.set(base + ".cents", supporter.cents());
                yaml.set(base + ".net-cents", supporter.netCents());
                yaml.set(base + ".supports", supporter.supports());
                yaml.set(base + ".member", supporter.member());
                yaml.set(base + ".thanked", supporter.thanked());
            });
        });
        if (written) {
            sent.forEach(unwritten::remove);
            revoked.removeAll(gone);
            load();
        }
        return written;
    }

    public boolean isDirty() {
        return !unwritten.isEmpty() || !revoked.isEmpty();
    }

    /** Reloads when another server writes the document, after writing what this one holds. Close to stop. */
    public NetworkBus.Subscription watch() {
        return store.onChange(() -> {
            synchronized (this) {
                save();
                load();
            }
        });
    }
}
