package de.raindancer.core.platform.link;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Who is online on the other server of the link — so a name can be tab-completed, or a message addressed, about
 * somebody over there. Each exchange carries this side's names and is answered with the other's, so one side calling
 * keeps both up to date. A roster not heard from for {@link #FORGOTTEN_AFTER} is empty: the other side may be down.
 */
public final class LinkRoster {

    public static final String TOPIC = "core.roster";
    public static final Duration FORGOTTEN_AFTER = Duration.ofSeconds(20);

    private final ServerLink link;
    private final String name;
    private final Supplier<? extends Collection<String>> here;
    private final LongSupplier clock;
    private volatile Set<String> there = Set.of();
    private volatile long heard = Long.MIN_VALUE;
    private volatile String thereName = "";

    public LinkRoster(ServerLink link, Supplier<? extends Collection<String>> here, LongSupplier clock) {
        this(link, "", here, clock);
    }

    /** @param name what this server is called (server-name in link.yml), for the other side to say where people are */
    public LinkRoster(ServerLink link, String name, Supplier<? extends Collection<String>> here, LongSupplier clock) {
        this.link = link;
        this.name = name == null ? "" : name;
        this.here = here;
        this.clock = clock;
        link.on(TOPIC, data -> {
            heardFrom(data);
            return Map.of("names", join(here.get()), "server", this.name);
        });
    }

    /** What the other side calls itself; empty until it has said so. */
    public String serverName() {
        return thereName;
    }

    /** Sends this side's names and takes the other's from the answer. */
    public CompletableFuture<Void> exchange() {
        return link.request(TOPIC, Map.of("names", join(here.get()), "server", name), Duration.ofSeconds(3))
                .thenAccept(this::heardFrom);
    }

    /** The names online over there, as last heard; empty when nothing was heard for a while. */
    public Set<String> elsewhere() {
        return clock.getAsLong() - heard > FORGOTTEN_AFTER.toMillis() ? Set.of() : there;
    }

    public void stop() {
        link.off(TOPIC);
    }

    private void heardFrom(Map<String, String> data) {
        if (data != null && data.containsKey("names")) {
            there = parse(data.get("names"));
            heard = clock.getAsLong();
            String said = data.getOrDefault("server", "");
            if (!said.isBlank()) {
                thereName = said;
            }
        }
    }

    static Set<String> parse(String names) {
        return names == null ? Set.of() : Arrays.stream(names.split(",")).map(String::trim)
                .filter(name -> !name.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    static String join(Collection<String> names) {
        return names.stream().filter(name -> name != null && !name.contains(",")).collect(Collectors.joining(","));
    }
}
