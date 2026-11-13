package de.raindancer.core.platform.client;

import de.raindancer.client.protocol.ClientCodec;
import de.raindancer.client.protocol.ClientFeature;
import de.raindancer.client.protocol.ClientboundPacket;
import de.raindancer.client.protocol.FeatureRange;
import de.raindancer.client.protocol.FeatureVersion;
import de.raindancer.client.protocol.Hello;
import de.raindancer.client.protocol.MalformedPacketException;
import de.raindancer.client.protocol.ServerboundPacket;
import de.raindancer.client.protocol.Theme;
import de.raindancer.client.protocol.Welcome;
import de.raindancer.client.protocol.Wire;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Who is playing with Rain's client mod, and the way to talk to it.
 *
 * <p>The client speaks first: a server that announced {@link Wire#CHANNEL} gets a {@link Hello} from a mod and
 * nothing from anybody else, so a player without the mod costs nothing and is never waited for. Until that
 * hello a player is vanilla — which is every player for their first moments here. Whatever draws for the mod
 * therefore listens to {@link #onReady} and moves that one player over when it comes.
 *
 * <p>A plugin does not draw through this. It keeps calling the service that owns the thing (scoreboards,
 * action bars, name tags…), and that service asks {@link #supports} and picks. This is for those services.
 */
public final class ClientMods {

    /** What reaches people rather than code. */
    public interface Reports {

        /** A client has sent more than its budget for three seconds running. */
        void flooding(UUID player);

        /** A mod too old to be spoken to at all; its player should hear that once. */
        void outdated(UUID player, String modVersion);

        /** A listener threw. */
        void failed(String what, Throwable why);
    }

    static final int FRAMES_PER_SECOND = 50;
    static final int BYTES_PER_SECOND = 64 * 1024;

    private final ClientWire wire;
    private final LongSupplier clock;
    private final Supplier<String> serverName;
    private final Supplier<Theme> theme;
    private final Reports reports;
    private final List<FeatureRange> offered;
    private final FrameBudget budget = new FrameBudget(FRAMES_PER_SECOND, BYTES_PER_SECOND, 3);

    private final Map<UUID, ClientSession> sessions = new ConcurrentHashMap<>();
    private final List<Consumer<ClientSession>> ready = new CopyOnWriteArrayList<>();
    private final List<Consumer<UUID>> gone = new CopyOnWriteArrayList<>();

    public ClientMods(ClientWire wire, LongSupplier clock, Supplier<String> serverName, Supplier<Theme> theme,
                      Reports reports) {
        this(wire, clock, serverName, theme, reports, Arrays.stream(ClientFeature.values()).map(FeatureRange::of).toList());
    }

    ClientMods(ClientWire wire, LongSupplier clock, Supplier<String> serverName, Supplier<Theme> theme, Reports reports,
               List<FeatureRange> offered) {
        this.wire = wire;
        this.clock = clock;
        this.serverName = serverName;
        this.theme = theme;
        this.reports = reports;
        this.offered = List.copyOf(offered);
    }

    public Optional<ClientSession> of(UUID player) {
        return Optional.ofNullable(sessions.get(player));
    }

    public Collection<ClientSession> sessions() {
        return List.copyOf(sessions.values());
    }

    public boolean supports(UUID player, ClientFeature feature) {
        return version(player, feature) > 0;
    }

    /** The version of this feature to speak with this player, 0 when there is none. */
    public int version(UUID player, ClientFeature feature) {
        ClientSession session = sessions.get(player);
        return session == null ? 0 : session.features().getOrDefault(feature, 0);
    }

    /**
     * Sends, if this player's mod agreed the packet's feature.
     *
     * @return false when it did not — the caller then draws the vanilla way
     */
    public boolean send(UUID player, ClientboundPacket packet) {
        if (!supports(player, packet.feature())) {
            return false;
        }
        wire.send(player, Wire.CHANNEL, ClientCodec.encode(packet));
        return true;
    }

    /** Told once the welcome is on its way — and again if the same client says hello again. */
    public void onReady(Consumer<ClientSession> listener) {
        ready.add(listener);
    }

    /** Told when a player who had a session leaves. */
    public void onGone(Consumer<UUID> listener) {
        gone.add(listener);
    }

    /** Drops what a plugin that is being disabled registered. */
    public int forgetFrom(ClassLoader loader) {
        int before = ready.size() + gone.size();
        ready.removeIf(listener -> listener.getClass().getClassLoader() == loader);
        gone.removeIf(listener -> listener.getClass().getClassLoader() == loader);
        return before - ready.size() - gone.size();
    }

    /** One plugin message on the channel, from whoever sent it. */
    public void received(UUID player, byte[] frame) {
        FrameBudget.Verdict verdict = budget.take(player, frame.length, clock.getAsLong());
        if (verdict != FrameBudget.Verdict.PASS) {
            if (verdict == FrameBudget.Verdict.DROP_AND_REPORT) {
                reports.flooding(player);
            }
            return;
        }
        Optional<ServerboundPacket> packet;
        try {
            packet = ClientCodec.decodeServerbound(frame);
        } catch (MalformedPacketException notAPacket) {
            return;
        }
        if (packet.isPresent() && packet.get() instanceof Hello hello) {
            greet(player, hello);
        }
    }

    public void left(UUID player) {
        budget.forget(player);
        if (sessions.remove(player) != null) {
            gone.forEach(listener -> tell("a client-gone listener", () -> listener.accept(player)));
        }
    }

    private void greet(UUID player, Hello hello) {
        String modVersion = plain(hello.modVersion());
        if (hello.protocol() < 1) {
            reports.outdated(player, modVersion);
            return;
        }
        Negotiation.Outcome outcome = Negotiation.between(offered, hello.features());
        int session = ClientFeature.SESSION.id();
        if (!outcome.agreed().containsKey(session)) {
            if (outcome.outdated().contains(session)) {
                reports.outdated(player, modVersion);
            }
            return;
        }
        Map<ClientFeature, Integer> features = new EnumMap<>(ClientFeature.class);
        outcome.agreed().forEach((feature, version) ->
                ClientFeature.byId(feature).ifPresent(known -> features.put(known, version)));
        ClientSession joined = new ClientSession(player, modVersion, plain(hello.minecraft()), features,
                Math.clamp(hello.guiScale(), 0, 16), Locale.forLanguageTag(hello.locale().replace('_', '-')));
        sessions.put(player, joined);

        wire.send(player, Wire.CHANNEL, ClientCodec.encode(new Welcome(Math.min(hello.protocol(), Wire.PROTOCOL),
                features.entrySet().stream().map(each -> new FeatureVersion(each.getKey().id(), each.getValue())).toList(),
                serverName.get(), theme.get())));
        ready.forEach(listener -> tell("a client-ready listener", () -> listener.accept(joined)));
    }

    private void tell(String what, Runnable listener) {
        try {
            listener.run();
        } catch (RuntimeException | LinkageError failed) {
            reports.failed(what, failed);
        }
    }

    /** A version string as a client claims it, made safe to print in chat, logs and MiniMessage. */
    private static String plain(String claimed) {
        StringBuilder safe = new StringBuilder(claimed.length());
        for (char each : claimed.toCharArray()) {
            boolean fine = each >= 'a' && each <= 'z' || each >= 'A' && each <= 'Z' || each >= '0' && each <= '9'
                    || each == '.' || each == '+' || each == '-' || each == '_';
            safe.append(fine ? each : '?');
        }
        return safe.toString();
    }
}
