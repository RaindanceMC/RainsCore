package de.raindancer.core.moderation.punishment;

import de.raindancer.core.moderation.audit.Audit;
import de.raindancer.core.moderation.audit.AuditEntry;
import de.raindancer.core.moderation.audit.AuditSearch;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.PluginCode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * What punished players tried to do and were stopped from: a banned player knocking, a muted one talking, a
 * frozen one building. Kept in the audit journal ({@code moderation / blocked-<kind>}), so it is searched,
 * shown and forgotten with it ({@code audit-retention-days}); handed to watchers as it happens.
 *
 * <h2>Personal data</h2>
 * Name, id, what was tried, and — for a join — the address only as a keyed hash: enough to see two accounts
 * knocking from the same place, not enough to say where that is.
 *
 * <h2>Threads</h2>
 * {@link #record} only queues and may be called from any thread; {@link #search} reads the database and
 * belongs off the server's threads.
 */
public final class BlockedAttempts {

    private static final LogChannel log = Log.of("punishment");

    public static final String FEATURE = "moderation";

    /** Building while frozen is kept once per this per player: a held button is one attempt, not hundreds. */
    public static final Duration BUILD_QUIET = Duration.ofSeconds(30);

    /** What was stopped. */
    public enum Kind {
        JOIN("blocked-join", "join"),
        CHAT("blocked-chat", "chat"),
        COMMAND("blocked-command", "command"),
        BUILD("blocked-build", "build");

        private final String action;
        private final String word;

        Kind(String action, String word) {
            this.action = action;
            this.word = word;
        }

        /** The audit action. */
        public String action() {
            return action;
        }

        /** How staff type and read it. */
        public String word() {
            return word;
        }

        public static java.util.Optional<Kind> of(String typed) {
            for (Kind kind : values()) {
                if (kind.word.equalsIgnoreCase(typed) || kind.action.equalsIgnoreCase(typed)
                        || kind.name().equalsIgnoreCase(typed)) {
                    return java.util.Optional.of(kind);
                }
            }
            return java.util.Optional.empty();
        }
    }

    /**
     * One attempt.
     *
     * @param punishment what stopped it; null when it was not one of Core's (the server's own ban list)
     * @param detail     what they tried: the line they wanted to say, the command, the block
     * @param address    a keyed hash of where they came from, joins only; null otherwise
     */
    public record Attempt(Instant at, UUID player, String name, Kind kind, PunishmentKind punishment,
                          String reason, String detail, String address) {
    }

    private static final List<Consumer<Attempt>> watchers = new CopyOnWriteArrayList<>();

    private final Audit audit;
    private final LongSupplier clock;
    private final Map<UUID, Long> lastBuild = new ConcurrentHashMap<>();

    public BlockedAttempts(Audit audit, LongSupplier clock) {
        this.audit = audit;
        this.clock = clock;
    }

    /** Called with every attempt as it is made, on whichever thread made it. */
    public static void watch(Consumer<Attempt> watcher) {
        if (watcher != null && !watchers.contains(watcher)) {
            watchers.add(watcher);
        }
    }

    public static void unwatch(Consumer<Attempt> watcher) {
        watchers.remove(watcher);
    }

    public static int forgetFrom(ClassLoader loader) {
        int before = watchers.size();
        watchers.removeIf(watcher -> PluginCode.isFrom(watcher, loader));
        return before - watchers.size();
    }

    public static void clearWatchers() {
        watchers.clear();
    }

    /** Somebody tried something a punishment stopped. Cheap; any thread. */
    public void record(UUID player, String name, Kind kind, Punishment because, String detail, String address) {
        record(player, name, kind, because == null ? null : because.kind(), because == null ? "" : because.reason(),
                detail, address);
    }

    /** The same, for a refusal that is not one of Core's punishments — the server's own ban list. */
    public void record(UUID player, String name, Kind kind, PunishmentKind punishment, String reason, String detail,
                       String address) {
        if (player == null || kind == null) {
            return;
        }
        long now = clock.getAsLong();
        if (kind == Kind.BUILD) {
            Long last = lastBuild.get(player);
            if (last != null && now - last < BUILD_QUIET.toMillis()) {
                return;
            }
            lastBuild.put(player, now);
        }
        String shown = name == null || name.isBlank() ? player.toString() : name;
        Attempt attempt = new Attempt(Instant.ofEpochMilli(now), player, shown, kind, punishment,
                reason == null ? "" : reason, detail == null ? "" : detail, address);
        AuditEntry.Builder entry = AuditEntry.of(FEATURE, kind.action()).by(player, shown).to(player, shown)
                .saying(attempt.detail());
        if (punishment != null) {
            entry.with("punishment", punishment.name());
        }
        if (!attempt.reason().isEmpty()) {
            entry.with("reason", attempt.reason());
        }
        if (address != null) {
            entry.with("address", address);
        }
        audit.record(entry.at(attempt.at()));
        for (Consumer<Attempt> watcher : watchers) {
            try {
                watcher.accept(attempt);
            } catch (RuntimeException broken) {
                log.error(broken, "Something watching blocked attempts failed.");
            }
        }
    }

    /**
     * What was tried, newest first. Off the server's threads.
     *
     * @param players whose; empty for everybody
     * @param from    inclusive, null for no limit
     * @param to      exclusive, null for no limit
     * @param kinds   which; empty for all
     */
    public List<Attempt> search(Collection<UUID> players, Instant from, Instant to, Collection<Kind> kinds, int limit) {
        audit.flush();
        Set<Kind> wanted = kinds == null || kinds.isEmpty() ? EnumSet.allOf(Kind.class) : EnumSet.copyOf(kinds);
        List<UUID> whose = players == null || players.isEmpty() ? java.util.Collections.singletonList(null)
                : List.copyOf(players);
        int most = Math.max(1, Math.min(limit, AuditSearch.MAX_LIMIT));
        List<Attempt> found = new ArrayList<>();
        for (UUID player : whose) {
            for (Kind kind : wanted) {
                AuditSearch search = AuditSearch.in(FEATURE).withAction(kind.action()).withSubject(player).limit(most);
                if (from != null) {
                    search = search.since(from);
                }
                if (to != null) {
                    search = search.until(to.minusMillis(1));
                }
                for (AuditEntry entry : audit.search(search)) {
                    found.add(attemptOf(entry, kind));
                }
            }
        }
        found.sort(Comparator.comparing(Attempt::at).reversed());
        return found.size() > most ? List.copyOf(found.subList(0, most)) : found;
    }

    /** How many attempts of theirs are kept, at most {@link AuditSearch#MAX_LIMIT}. Off the server's threads. */
    public int count(Collection<UUID> players) {
        return search(players, null, null, Set.of(), AuditSearch.MAX_LIMIT).size();
    }

    private static Attempt attemptOf(AuditEntry entry, Kind kind) {
        PunishmentKind punishment = null;
        String typed = entry.fields().get("punishment");
        if (typed != null) {
            try {
                punishment = PunishmentKind.valueOf(typed);
            } catch (IllegalArgumentException unknown) {
                // Written by a newer version; shown without it.
            }
        }
        return new Attempt(entry.at(), entry.subject(), entry.subjectName(), kind, punishment,
                entry.fields().getOrDefault("reason", ""), entry.detail() == null ? "" : entry.detail(),
                entry.fields().get("address"));
    }

    /** A keyed hash of an address: the same key and address always match, and the address cannot be read back. */
    public static String hashAddress(byte[] key, String address) {
        if (address == null || address.isBlank() || key == null || key.length == 0) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(address.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (GeneralSecurityException impossible) {
            return null;
        }
    }
}
