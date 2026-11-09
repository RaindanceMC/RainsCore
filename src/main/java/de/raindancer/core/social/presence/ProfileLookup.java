package de.raindancer.core.social.presence;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Turns a typed name into a player id even when nobody on this server has seen them: Core's {@link KnownNames}
 * first, then Mojang's profile lookup — off the caller's thread, so a command can say who somebody is that has
 * never joined (a rank for a friend before their first login).
 *
 * <p>What Mojang answers is remembered in {@link KnownNames}, so the name stays attached to the uuid from then
 * on: messages show it, and the next lookup is not a network call.
 */
public final class ProfileLookup {

    /** @param name Mojang's spelling; null when only an id was typed and Core knows no name for it */
    public record Found(UUID id, String name) {
    }

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private final KnownNames names;
    private final Function<String, Optional<Found>> mojang;
    private final Executor async;

    /** @param mojang blocking lookup of a name; only ever called on {@code async} */
    public ProfileLookup(KnownNames names, Function<String, Optional<Found>> mojang, Executor async) {
        this.names = names;
        this.mojang = mojang;
        this.async = async;
    }

    /** Who this name is; empty when it is not a valid name, does not exist, or Mojang could not be asked. */
    public CompletableFuture<Optional<Found>> byName(String typed) {
        String name = typed == null ? "" : typed.trim();
        if (!NAME.matcher(name).matches()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        Optional<UUID> known = names.idOf(name);
        if (known.isPresent()) {
            return CompletableFuture.completedFuture(
                    Optional.of(new Found(known.get(), names.nameOf(known.get()).orElse(name))));
        }
        CompletableFuture<Optional<Found>> result = new CompletableFuture<>();
        async.execute(() -> {
            try {
                Optional<Found> found = mojang.apply(name);
                found.ifPresent(who -> names.seen(who.id(), who.name()));
                result.complete(found);
            } catch (RuntimeException failed) {
                result.complete(Optional.empty());
            }
        });
        return result;
    }

    /** As {@link #byName}, and a typed uuid is accepted as it is. */
    public CompletableFuture<Optional<Found>> byNameOrId(String typed) {
        if (typed != null && typed.trim().length() == 36) {
            try {
                UUID id = UUID.fromString(typed.trim());
                return CompletableFuture.completedFuture(Optional.of(new Found(id, names.nameOf(id).orElse(null))));
            } catch (IllegalArgumentException notAnId) {
                // A name, then.
            }
        }
        return byName(typed);
    }
}
