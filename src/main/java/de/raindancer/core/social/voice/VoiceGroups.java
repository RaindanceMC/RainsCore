package de.raindancer.core.social.voice;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Voice groups a game can ask for without knowing which voice plugin there is: "put the red team in a group of
 * their own", "that group is done". A voice module ({@code voicebridge-module}) registers itself as the
 * {@link Provider}; with none registered every ask does nothing, so a game never has to check first.
 *
 * <p>Safe from any thread; a provider that throws is skipped and logged.
 */
public final class VoiceGroups {

    /** What a voice plugin does when asked. */
    public interface Provider {
        /**
         * Puts these players into one group named {@code name}, made for {@code key} if there is none yet; players
         * without a voice client are left out by the provider. Asked again with the same key, the group is the same.
         */
        void place(String key, String name, Collection<UUID> players);

        /** Everybody in the group for {@code key} is let out of it, and the group is gone. */
        void disband(String key);
    }

    private static final LogChannel log = Log.of("voice");
    private static final Map<String, Provider> providers = new LinkedHashMap<>();

    private VoiceGroups() {
    }

    public static synchronized void register(String owner, Provider provider) {
        providers.put(owner, provider);
    }

    public static synchronized boolean unregister(String owner) {
        return providers.remove(owner) != null;
    }

    public static synchronized boolean isAvailable() {
        return !providers.isEmpty();
    }

    public static void place(String key, String name, Collection<UUID> players) {
        for (Provider provider : snapshot()) {
            try {
                provider.place(key, name, List.copyOf(players));
            } catch (RuntimeException failure) {
                log.warn("A voice provider could not make the group {}: {}", key, failure.getMessage());
            }
        }
    }

    public static void disband(String key) {
        for (Provider provider : snapshot()) {
            try {
                provider.disband(key);
            } catch (RuntimeException failure) {
                log.warn("A voice provider could not end the group {}: {}", key, failure.getMessage());
            }
        }
    }

    public static synchronized int forgetFrom(ClassLoader loader) {
        int before = providers.size();
        providers.values().removeIf(provider -> provider.getClass().getClassLoader() == loader);
        return before - providers.size();
    }

    public static synchronized void clear() {
        providers.clear();
    }

    private static synchronized List<Provider> snapshot() {
        return List.copyOf(providers.values());
    }
}
