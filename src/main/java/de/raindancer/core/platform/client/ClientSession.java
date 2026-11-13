package de.raindancer.core.platform.client;

import de.raindancer.client.protocol.ClientFeature;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * One player's mod, for as long as they are on this server. Everything in here is what the client said about
 * itself: fit to show and to choose a layout by, never to trust.
 *
 * @param features what was agreed, each at the one version to speak
 * @param guiScale the client's GUI scale, 0 for automatic
 */
public record ClientSession(UUID player, String modVersion, String minecraft, Map<ClientFeature, Integer> features,
                            int guiScale, Locale locale) {

    public ClientSession {
        features = Map.copyOf(features);
    }
}
