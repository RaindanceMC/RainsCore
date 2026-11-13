package de.raindancer.core.platform.client;

import de.raindancer.client.protocol.FeatureRange;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Which version of which feature a server and one mod speak with each other. */
final class Negotiation {

    /**
     * @param agreed   feature to the newest version both sides speak
     * @param outdated features the mod is too old for — the ones worth telling its player about
     */
    record Outcome(Map<Integer, Integer> agreed, List<Integer> outdated) {
    }

    private Negotiation() {
    }

    static Outcome between(List<FeatureRange> server, List<FeatureRange> client) {
        Map<Integer, Integer> agreed = new LinkedHashMap<>();
        List<Integer> outdated = new ArrayList<>();
        for (FeatureRange ours : server) {
            FeatureRange theirs = client.stream().filter(offer -> offer.feature() == ours.feature()).findFirst().orElse(null);
            if (theirs == null || theirs.oldest() < 1 || theirs.oldest() > theirs.newest()) {
                continue;
            }
            int version = Math.min(ours.newest(), theirs.newest());
            if (version >= ours.oldest() && version >= theirs.oldest()) {
                agreed.put(ours.feature(), version);
            } else if (theirs.newest() < ours.oldest()) {
                outdated.add(ours.feature());
            }
        }
        return new Outcome(agreed, outdated);
    }
}
