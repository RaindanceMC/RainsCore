package de.raindancer.core.content.pack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a plugin actually talks to, and what a player ends up with.
 *
 * <p>The whole package exists for one fact: a player has one resource pack. Every test in here is
 * some version of "two plugins both wanted it and neither of them lost silently".
 */
@DisplayName("resource packs")
class ResourcePacksTest {

    @TempDir
    Path directory;

    /** Every request that would have gone to a player, instead of a server. */
    private final List<List<PackOffer>> sent = new ArrayList<>();
    private final List<UUID> cleared = new ArrayList<>();

    private ResourcePacks packs() {
        ResourcePacks packs = bare();
        // What the plugin wires up at startup. Without somewhere to download from there is nothing
        // to send, which is its own test below rather than the state every other test starts in.
        packs.urls(name -> "http://localhost:8080/packs/" + name);
        return packs;
    }

    private ResourcePacks bare() {
        return new ResourcePacks(directory.resolve("work"), new PackSink() {
            @Override
            public void send(UUID player, List<PackOffer> offers) {
                sent.add(offers);
            }

            @Override
            public void clear(UUID player) {
                cleared.add(player);
            }
        });
    }

    private Path zip(String name, Map<String, String> files) throws IOException {
        Path file = directory.resolve(name);
        try (OutputStream out = Files.newOutputStream(file);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("pack.mcmeta"));
            zip.write("{\"pack\":{\"description\":\"x\",\"pack_format\":46}}"
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (Map.Entry<String, String> entry : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file;
    }

    // ------------------------------------------------------------------ contributing

    @Nested
    @DisplayName("what plugins offer")
    class Contributing {

        @Test
        @DisplayName("a plugin contributes and the pack is rebuilt")
        void contributing() throws IOException {
            ResourcePacks packs = packs();
            assertThat(packs.contribute(
                    PackContribution.of("Claims", "icons", zip("a.zip", Map.of("assets/a", "a")))))
                    .isTrue();

            assertThat(packs.rebuild()).isPresent();
            assertThat(packs.current()).isPresent();
            assertThat(packs.current().orElseThrow().contributions()).isEqualTo(1);
        }

        @Test
        @DisplayName("nothing contributed means nothing to send, not an empty pack")
        void nothingContributed() {
            ResourcePacks packs = packs();
            assertThat(packs.rebuild()).isEmpty();
            assertThat(packs.current()).isEmpty();

            packs.sendTo(UUID.randomUUID());
            assertThat(sent)
                    .as("sending a pack that does not exist gives a player a download error for "
                            + "no reason at all")
                    .isEmpty();
        }

        @Test
        @DisplayName("a plugin going away takes its assets out of the pack")
        void withdrawing() throws IOException {
            ResourcePacks packs = packs();
            packs.contribute(PackContribution.of("Claims", "icons",
                    zip("a.zip", Map.of("assets/a", "a"))));
            packs.contribute(PackContribution.of("Records", "discs",
                    zip("b.zip", Map.of("assets/b", "b"))));
            packs.rebuild();
            String before = packs.current().orElseThrow().digest();

            assertThat(packs.withdrawAllFrom("Records")).isEqualTo(1);
            packs.rebuild();

            assertThat(packs.current().orElseThrow().digest())
                    .as("a disabled plugin's textures must not stay in the pack for ever")
                    .isNotEqualTo(before);
        }

        @Test
        @DisplayName("what is offered while a build runs is in the pack that build leaves")
        void offeredDuringABuild() throws IOException {
            ResourcePacks packs = packs();
            packs.contribute(PackContribution.of("Claims", "icons", zip("a.zip", Map.of("assets/a", "a"))));
            PackContribution late = PackContribution.of("RainsCore", "badges", zip("b.zip", Map.of("assets/b", "b")));
            java.util.concurrent.atomic.AtomicBoolean first = new java.util.concurrent.atomic.AtomicBoolean(true);
            PackBuilder real = new PackBuilder(directory.resolve("work2"));
            packs.buildWith((library, description) -> {
                Optional<PackBuild> built = real.build(library, description);
                if (first.getAndSet(false)) {
                    // Offered after this build read what there was: the badges at startup, on a busy server.
                    packs.contribute(late);
                }
                return built;
            });

            packs.rebuild();

            assertThat(packs.current().orElseThrow().contributions())
                    .as("a pack built without the badges shows boxes on every nametag until the next restart")
                    .isEqualTo(2);
        }
    }

    // ------------------------------------------------------------------ sending

    @Nested
    @DisplayName("sending it to a player")
    class Sending {

        @Test
        @DisplayName("the offer carries the URL and the hash the client verifies")
        void sendsUrlAndHash() throws IOException {
            ResourcePacks packs = packs();
            packs.contribute(PackContribution.of("Claims", "icons",
                    zip("a.zip", Map.of("assets/a", "a"))));
            packs.rebuild();
            packs.urls(name -> "https://example.com/packs/" + name);

            UUID player = UUID.randomUUID();
            packs.sendTo(player);

            assertThat(sent).hasSize(1);
            PackOffer offer = sent.get(0).get(0);
            assertThat(offer.url()).startsWith("https://example.com/packs/");
            assertThat(offer.sha1())
                    .as("without the hash the client cannot cache it and downloads it every join")
                    .isEqualTo(packs.current().orElseThrow().parts().get(0).sha1());
        }

        @Test
        @DisplayName("the same pack is not sent to the same player twice")
        void doesNotResendTheSamePack() throws IOException {
            ResourcePacks packs = packs();
            packs.contribute(PackContribution.of("Claims", "icons",
                    zip("a.zip", Map.of("assets/a", "a"))));
            packs.rebuild();

            UUID player = UUID.randomUUID();
            packs.sendTo(player);
            packs.sendTo(player);

            assertThat(sent)
                    .as("a second send of a pack a player already has is a download prompt for "
                            + "nothing")
                    .hasSize(1);
        }

        @Test
        @DisplayName("a rebuilt pack is sent again, because it is a different pack")
        void resendsAfterARebuild() throws IOException {
            ResourcePacks packs = packs();
            packs.contribute(PackContribution.of("Claims", "icons",
                    zip("a.zip", Map.of("assets/a", "a"))));
            packs.rebuild();
            UUID player = UUID.randomUUID();
            packs.sendTo(player);

            packs.contribute(PackContribution.of("Records", "discs",
                    zip("b.zip", Map.of("assets/b", "b"))));
            packs.rebuild();
            packs.sendTo(player);

            assertThat(sent).hasSize(2);
            assertThat(sent.get(1)).isNotEqualTo(sent.get(0));
        }

        @Test
        @DisplayName("several contributions go out as several packs, in one request")
        void sendsThemStacked() throws IOException {
            ResourcePacks packs = packs();
            packs.contribute(PackContribution.of("Claims", "icons",
                    zip("a.zip", Map.of("assets/a", "a"))).priority(1));
            packs.contribute(PackContribution.of("Records", "discs",
                    zip("b.zip", Map.of("assets/b", "b"))).priority(2));
            packs.rebuild();
            packs.sendTo(UUID.randomUUID());

            assertThat(sent).hasSize(1);
            assertThat(sent.get(0))
                    .as("the client has stacked packs since 1.20.3; one request keeps the order "
                            + "right and asks once rather than once per pack")
                    .hasSize(2);
            assertThat(sent.get(0)).extracting(PackOffer::sha1).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("combined, the same contributions go out as one pack")
        void sendsThemCombined() throws IOException {
            ResourcePacks packs = packs();
            packs.mode(PackMode.COMBINED);
            packs.contribute(PackContribution.of("Claims", "icons",
                    zip("a.zip", Map.of("assets/a", "a"))));
            packs.contribute(PackContribution.of("Records", "discs",
                    zip("b.zip", Map.of("assets/b", "b"))));
            packs.rebuild();
            packs.sendTo(UUID.randomUUID());

            assertThat(sent.get(0)).hasSize(1);
        }

        @Test
        @DisplayName("a pack with nowhere to download it from is not sent")
        void needsSomewhereToDownloadFrom() throws IOException {
            ResourcePacks packs = bare();
            packs.contribute(PackContribution.of("Claims", "icons",
                    zip("a.zip", Map.of("assets/a", "a"))));
            packs.rebuild();
            packs.sendTo(UUID.randomUUID());

            assertThat(sent)
                    .as("a request pointing at nothing is a download failure on every client, "
                            + "which reads as a broken pack rather than as unfinished setup")
                    .isEmpty();
        }

        @Test
        @DisplayName("whether it is required is carried through")
        void carriesRequired() throws IOException {
            ResourcePacks packs = packs();
            packs.required(true);
            packs.contribute(PackContribution.of("Claims", "icons",
                    zip("a.zip", Map.of("assets/a", "a"))));
            packs.rebuild();
            packs.sendTo(UUID.randomUUID());

            assertThat(sent.get(0).get(0).required()).isTrue();
        }

        @Test
        @DisplayName("everyone can be sent it at once, after a rebuild")
        void sendsToEveryone() throws IOException {
            ResourcePacks packs = packs();
            packs.contribute(PackContribution.of("Claims", "icons",
                    zip("a.zip", Map.of("assets/a", "a"))));
            packs.rebuild();

            List<UUID> online = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
            packs.sendToAll(online);

            assertThat(sent).hasSize(3);
        }

        @Test
        @DisplayName("a player who leaves is forgotten, so they get it again next time")
        void forgetsOnLeaving() throws IOException {
            ResourcePacks packs = packs();
            packs.contribute(PackContribution.of("Claims", "icons",
                    zip("a.zip", Map.of("assets/a", "a"))));
            packs.rebuild();

            UUID player = UUID.randomUUID();
            packs.sendTo(player);
            packs.forget(player);
            packs.sendTo(player);

            assertThat(sent)
                    .as("a client that declined and reconnected must be asked again, and a client "
                            + "that cleared its cache must be able to get it back")
                    .hasSize(2);
        }
    }

    // ------------------------------------------------------------------ what happened after

    @Nested
    @DisplayName("what the client did with it")
    class Outcomes {

        @Test
        @DisplayName("nothing is known about a player who was never sent one")
        void unknownByDefault() {
            assertThat(packs().statusOf(UUID.randomUUID())).isEqualTo(PackStatus.NOT_SENT);
        }

        @Test
        @DisplayName("a player who was sent one is waiting until they say otherwise")
        void waitingAfterSending() throws IOException {
            ResourcePacks packs = packs();
            packs.contribute(PackContribution.of("Claims", "icons",
                    zip("a.zip", Map.of("assets/a", "a"))));
            packs.rebuild();
            UUID player = UUID.randomUUID();
            packs.sendTo(player);

            assertThat(packs.statusOf(player)).isEqualTo(PackStatus.SENT);
        }

        @Test
        @DisplayName("the client's answer is remembered")
        void remembersTheAnswer() throws IOException {
            ResourcePacks packs = packs();
            packs.contribute(PackContribution.of("Claims", "icons",
                    zip("a.zip", Map.of("assets/a", "a"))));
            packs.rebuild();
            UUID player = UUID.randomUUID();
            packs.sendTo(player);

            packs.record(player, PackStatus.LOADED);
            assertThat(packs.statusOf(player)).isEqualTo(PackStatus.LOADED);
            assertThat(packs.wearing()).contains(player);
        }

        @Test
        @DisplayName("a download that failed is not a player wearing the pack")
        void failedIsNotWearing() throws IOException {
            ResourcePacks packs = packs();
            packs.contribute(PackContribution.of("Claims", "icons",
                    zip("a.zip", Map.of("assets/a", "a"))));
            packs.rebuild();
            UUID player = UUID.randomUUID();
            packs.sendTo(player);
            packs.record(player, PackStatus.FAILED);

            assertThat(packs.wearing()).doesNotContain(player);
            assertThat(packs.statusOf(player)).isEqualTo(PackStatus.FAILED);
        }

        @Test
        @DisplayName("a failed download can be tried again")
        void aFailureCanBeRetried() throws IOException {
            ResourcePacks packs = packs();
            packs.contribute(PackContribution.of("Claims", "icons",
                    zip("a.zip", Map.of("assets/a", "a"))));
            packs.rebuild();
            UUID player = UUID.randomUUID();
            packs.sendTo(player);
            packs.record(player, PackStatus.FAILED);

            packs.sendTo(player);
            assertThat(sent)
                    .as("a download that failed once — a flaky connection, a proxy — must not "
                            + "leave a player without the pack until they reconnect")
                    .hasSize(2);
        }
    }

    // ------------------------------------------------------------------ taking it away

    @Test
    @DisplayName("the pack can be taken off a player")
    void clears() {
        ResourcePacks packs = packs();
        UUID player = UUID.randomUUID();
        packs.clearFor(player);

        assertThat(cleared).containsExactly(player);
        assertThat(packs.statusOf(player)).isEqualTo(PackStatus.NOT_SENT);
    }
}
