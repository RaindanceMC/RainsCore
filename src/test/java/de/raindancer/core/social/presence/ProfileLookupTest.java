package de.raindancer.core.social.presence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** A name nobody on this server has seen is asked of Mojang once, off the caller's thread, and then remembered. */
class ProfileLookupTest {

    @TempDir
    Path folder;

    private final UUID yeu = UUID.fromString("bac99a46-98f7-450e-87cd-9d2d30979972");
    private final AtomicInteger asked = new AtomicInteger();

    private ProfileLookup lookup(KnownNames names) {
        return new ProfileLookup(names, name -> {
            asked.incrementAndGet();
            return name.equalsIgnoreCase("yeukoula") ? Optional.of(new ProfileLookup.Found(yeu, "Yeukoula"))
                    : Optional.empty();
        }, id -> {
            asked.incrementAndGet();
            return id.equals(yeu) ? Optional.of("Yeukoula") : Optional.empty();
        }, Runnable::run);
    }

    @Test
    @DisplayName("a name Mojang knows resolves, in Mojang's spelling, and is remembered as the player's name")
    void resolvesAndRemembers() {
        KnownNames names = new KnownNames(folder.resolve("known-names.yml"));
        ProfileLookup lookup = lookup(names);

        assertThat(lookup.byName("yeukoula").join()).contains(new ProfileLookup.Found(yeu, "Yeukoula"));
        assertThat(names.nameOf(yeu)).contains("Yeukoula");
        assertThat(names.idOf("YEUKOULA")).contains(yeu);
    }

    @Test
    @DisplayName("a remembered name is not asked of Mojang again")
    void knownIsNotAsked() {
        KnownNames names = new KnownNames(folder.resolve("known-names.yml"));
        names.seen(yeu, "Yeukoula");

        assertThat(lookup(names).byName("yeukoula").join()).contains(new ProfileLookup.Found(yeu, "Yeukoula"));
        assertThat(asked.get()).isZero();
    }

    @Test
    @DisplayName("a name that does not exist, or cannot be a name, resolves to nobody — and the impossible ones are never asked")
    void unknownIsEmpty() {
        KnownNames names = new KnownNames(folder.resolve("known-names.yml"));
        ProfileLookup lookup = lookup(names);

        assertThat(lookup.byName("nobodyatall").join()).isEmpty();
        assertThat(asked.get()).isEqualTo(1);
        assertThat(lookup.byName("not a name!").join()).isEmpty();
        assertThat(lookup.byName("waytoolongtobeaminecraftname").join()).isEmpty();
        assertThat(lookup.byName("").join()).isEmpty();
        assertThat(asked.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a lookup that fails is nobody, not an exception for the caller")
    void failureIsEmpty() {
        ProfileLookup lookup = new ProfileLookup(new KnownNames(folder.resolve("known-names.yml")),
                name -> {
                    throw new IllegalStateException("Mojang is down");
                }, id -> Optional.empty(), Runnable::run);

        assertThat(lookup.byName("yeukoula").join()).isEmpty();
    }

    @Test
    @DisplayName("a uuid typed instead of a name resolves to itself, with the name Core knows for it")
    void uuidTyped() {
        KnownNames names = new KnownNames(folder.resolve("known-names.yml"));
        ProfileLookup lookup = lookup(names);

        assertThat(lookup.byNameOrId(yeu.toString()).join()).contains(new ProfileLookup.Found(yeu, null));
        names.seen(yeu, "Yeukoula");
        assertThat(lookup.byNameOrId(yeu.toString()).join()).contains(new ProfileLookup.Found(yeu, "Yeukoula"));
        assertThat(asked.get()).isZero();
    }

    @Test
    @DisplayName("the name of a uuid nobody here knows is asked of Mojang once and remembered; a known one is not asked")
    void nameOfId() {
        KnownNames names = new KnownNames(folder.resolve("known-names.yml"));
        ProfileLookup lookup = lookup(names);

        assertThat(lookup.nameOf(yeu)).contains("Yeukoula");
        assertThat(names.nameOf(yeu)).contains("Yeukoula");
        assertThat(lookup.nameOf(yeu)).contains("Yeukoula");
        assertThat(lookup.nameOf(UUID.randomUUID())).isEmpty();
        assertThat(asked.get()).isEqualTo(2);
    }
}
