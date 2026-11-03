package de.raindancer.core.world.activity;

import java.util.Locale;
import java.util.Objects;

/**
 * Something a player did, as {@link Activities} tells it.
 *
 * @param what   lower case: the entity killed ("zombie"), the ore group mined ("iron"), the crop ("wheat"); empty
 *               when the kind has no "what"
 * @param amount how many — levels spent enchanting, minutes played; 1 for most
 */
public record Activity(ActivityKind kind, String what, long amount) {

    public Activity {
        Objects.requireNonNull(kind, "kind");
        what = what == null ? "" : what.toLowerCase(Locale.ROOT);
        amount = Math.max(0, amount);
    }

    public static Activity of(ActivityKind kind, String what) {
        return new Activity(kind, what, 1);
    }

    public static Activity of(ActivityKind kind, long amount) {
        return new Activity(kind, "", amount);
    }
}
