package de.raindancer.core.social.support;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.network.NetworkBus;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who holds the Prime subscription, across the network. A shop (Tebex) runs {@code prime grant <player> <days>} on
 * every purchase and renewal and {@code prime revoke <player>} on expiry; everything Prime unlocks asks here.
 *
 * <p>{@code months} is every calendar month (UTC) a paid period touched: a month's Prime-only drop belongs to
 * whoever paid for any part of it, logged in or not. Kept after Prime ends, so it returns on renewal.
 *
 * <pre>
 * players:
 *   &lt;uuid&gt;:
 *     since: 2026-11-13T12:00:00Z   # start of the current (or last) unbroken run
 *     until: 2026-12-14T12:00:00Z
 *     months: [2026-11, 2026-12]
 * </pre>
 */
public final class PrimeMembers {

    public record Member(UUID player, Instant since, Instant until, SortedSet<YearMonth> months) {

        public Member {
            months = java.util.Collections.unmodifiableSortedSet(new TreeSet<>(months));
        }

        public boolean activeAt(Instant now) {
            return until.isAfter(now);
        }
    }

    private final YamlStore store;
    private final Map<UUID, Member> members = new ConcurrentHashMap<>();
    private final Map<UUID, Member> unwritten = new ConcurrentHashMap<>();
    private volatile boolean readable = true;

    public PrimeMembers(YamlStore store) {
        this.store = store;
    }

    public synchronized void load() {
        Optional<YamlConfiguration> read = store.readIfReadable();
        if (read.isEmpty()) {
            readable = false;
            return;
        }
        readable = true;
        Map<UUID, Member> fresh = new HashMap<>();
        ConfigurationSection players = read.get().getConfigurationSection("players");
        if (players != null) {
            for (String rawId : players.getKeys(false)) {
                ConfigurationSection theirs = players.getConfigurationSection(rawId);
                if (theirs == null) {
                    continue;
                }
                try {
                    UUID who = UUID.fromString(rawId);
                    SortedSet<YearMonth> months = new TreeSet<>();
                    for (String month : theirs.getStringList("months")) {
                        try {
                            months.add(YearMonth.parse(month));
                        } catch (DateTimeParseException skipped) {
                            // a hand-edited month; the rest still count
                        }
                    }
                    fresh.put(who, new Member(who, instant(theirs.get("since")), instant(theirs.get("until")), months));
                } catch (IllegalArgumentException | DateTimeParseException handEdited) {
                    // one broken entry must not cost everybody else theirs
                }
            }
        }
        fresh.putAll(unwritten);
        members.keySet().retainAll(fresh.keySet());
        members.putAll(fresh);
    }

    /** Written by hand without quotes, YAML hands a timestamp over as a Date. */
    private static Instant instant(Object raw) {
        if (raw instanceof java.util.Date date) {
            return date.toInstant();
        }
        return Instant.parse(raw == null ? "" : raw.toString());
    }

    public boolean isPrime(UUID player, Instant now) {
        Member member = player == null ? null : members.get(player);
        return member != null && member.activeAt(now);
    }

    public Optional<Member> of(UUID player) {
        return player == null ? Optional.empty() : Optional.ofNullable(members.get(player));
    }

    public SortedSet<YearMonth> months(UUID player) {
        return of(player).map(Member::months).orElse(java.util.Collections.emptySortedSet());
    }

    /** Prime right now, longest-running first. */
    public List<Member> current(Instant now) {
        return members.values().stream().filter(member -> member.activeAt(now))
                .sorted(Comparator.comparing(Member::since).thenComparing(member -> member.player().toString()))
                .toList();
    }

    public List<Member> all() {
        return List.copyOf(members.values());
    }

    /** Adds {@code length} from the end of a running Prime, or from now. Held until {@link #save}. */
    public synchronized Member grant(UUID player, Duration length, Instant now) {
        if (length.isNegative() || length.isZero()) {
            throw new IllegalArgumentException("a grant must be longer than nothing: " + length);
        }
        Member before = members.get(player);
        boolean running = before != null && before.activeAt(now);
        Instant from = running ? before.until() : now;
        Instant until = from.plus(length);
        SortedSet<YearMonth> months = new TreeSet<>(before == null ? java.util.Set.of() : before.months());
        YearMonth month = YearMonth.from(from.atZone(ZoneOffset.UTC));
        YearMonth last = YearMonth.from(until.minusNanos(1).atZone(ZoneOffset.UTC));
        while (!month.isAfter(last)) {
            months.add(month);
            month = month.plusMonths(1);
        }
        Member granted = new Member(player, running ? before.since() : now, until, months);
        put(granted);
        return granted;
    }

    /** Ends it now; the months had stay. @return whether they were Prime */
    public synchronized boolean revoke(UUID player, Instant now) {
        Member before = members.get(player);
        if (before == null || !before.activeAt(now)) {
            return false;
        }
        put(new Member(player, before.since(), now, before.months()));
        return true;
    }

    private void put(Member member) {
        members.put(member.player(), member);
        unwritten.put(member.player(), member);
    }

    public synchronized boolean save() {
        if (!readable) {
            load();
            if (!readable) {
                return false;
            }
        }
        if (unwritten.isEmpty()) {
            return true;
        }
        Map<UUID, Member> sent = Map.copyOf(unwritten);
        boolean written = store.update(yaml -> sent.forEach((who, member) -> {
            String base = "players." + who;
            yaml.set(base + ".since", member.since().toString());
            yaml.set(base + ".until", member.until().toString());
            yaml.set(base + ".months", member.months().stream().map(YearMonth::toString).toList());
        }));
        if (written) {
            sent.forEach(unwritten::remove);
            load();
        }
        return written;
    }

    public boolean isDirty() {
        return !unwritten.isEmpty();
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
