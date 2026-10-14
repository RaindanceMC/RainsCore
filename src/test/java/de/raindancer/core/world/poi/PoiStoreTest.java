package de.raindancer.core.world.poi;

import de.raindancer.core.testkit.TestDatabases;

import org.bukkit.Material;
import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * One place, remembered.
 *
 * <h2>Why there is one of these instead of three</h2>
 * {@code Home}, {@code Destination} and {@code Waypoint} were three records in three plugins with
 * the same five fields, the same "the world is a name, not a World" note in their javadoc — copied
 * word for word between them — and three separate YAML stores behind them with three separate
 * answers to what happens when a world is missing. This is the one of them, so a place saved by any
 * plugin can be listed, teleported to, or drawn on a map by any other.
 *
 * <h2>Why the world is a name and not a World</h2>
 * A saved place outlives the server it was saved on. Holding the world object would keep an unloaded
 * world in the heap, and — worse — a place in a world that is not loaded <em>right now</em> would
 * have to be thrown away at load time rather than being unreachable until that world comes back. A
 * multiverse server that unloads a world for maintenance would silently lose every home in it.
 */
@org.junit.jupiter.api.Tag("database")
class PoiStoreTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    @TempDir
    Path directory;
    private PoiStore store;
    /** The real engine: these places live in SQLite, and its opinions are what is worth testing. */
    private Database database;

    private Database openDatabase() {
        return TestDatabases.open(directory.resolve("core.db"), CoreSchema.CORE, () -> false);
    }

    @AfterEach
    void closeDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @BeforeEach
    void setUp() {
        database = openDatabase();
        store = new PoiStore(database);
    }

    private static Poi home(String name) {
        return Poi.builder(name, "world", 120.5, 64, -310.25)
                .owner(ALICE)
                .kind("home")
                .build();
    }

    // ------------------------------------------------------------------ keeping places

    @Nested
    @DisplayName("saving a place")
    class Saving {

        @Test
        @DisplayName("it can be found again by its id")
        void savesAndFinds() {
            Poi base = home("base");
            store.save(base);
            assertThat(store.byId(base.id())).contains(base);
        }

        @Test
        @DisplayName("everything about it survives being saved")
        void keepsEveryField() {
            Poi full = Poi.builder("market", "nether", 1, 2, 3)
                    .owner(ALICE)
                    .kind("stop")
                    .icon(Material.BELL)
                    .facing(90f, -12f)
                    .label("The market")
                    .shared(true)
                    .tag("line", "north")
                    .build();
            store.save(full);

            Poi read = store.byId(full.id()).orElseThrow();
            assertThat(read.name()).isEqualTo("market");
            assertThat(read.world()).isEqualTo("nether");
            assertThat(read.x()).isEqualTo(1);
            assertThat(read.yaw()).isEqualTo(90f);
            assertThat(read.pitch()).isEqualTo(-12f);
            assertThat(read.icon()).isEqualTo(Material.BELL);
            assertThat(read.label()).isEqualTo("The market");
            assertThat(read.isShared()).isTrue();
            assertThat(read.tag("line")).contains("north");
        }

        @Test
        @DisplayName("saving the same id again replaces it rather than making a second")
        void replacesOnSave() {
            Poi base = home("base");
            store.save(base);
            store.save(base.movedTo("world", 1, 2, 3));

            assertThat(store.all()).hasSize(1);
            assertThat(store.byId(base.id()).orElseThrow().x()).isEqualTo(1);
        }

        @Test
        @DisplayName("two places can share a name if they belong to different people")
        void namesAreOnlyUniquePerOwner() {
            store.save(home("base"));
            store.save(Poi.builder("base", "world", 0, 0, 0).owner(BOB).kind("home").build());

            assertThat(store.all()).hasSize(2);
            assertThat(store.named(ALICE, "home", "base")).isPresent();
            assertThat(store.named(BOB, "home", "base")).isPresent();
        }

        @Test
        @DisplayName("a name is matched however it was capitalised")
        void namesAreCaseInsensitive() {
            store.save(home("Base"));
            assertThat(store.named(ALICE, "home", "BASE")).isPresent();
            assertThat(store.named(ALICE, "home", "base")).isPresent();
        }

        @Test
        @DisplayName("deleting takes it away")
        void deletes() {
            Poi base = home("base");
            store.save(base);
            assertThat(store.delete(base.id())).isTrue();
            assertThat(store.byId(base.id())).isEmpty();
            assertThat(store.delete(base.id()))
                    .as("deleting twice says so rather than pretending it worked")
                    .isFalse();
        }
    }

    // ------------------------------------------------------------------ finding places

    @Nested
    @DisplayName("finding places")
    class Finding {

        @BeforeEach
        void fill() {
            store.save(home("base"));
            store.save(home("mine"));
            store.save(Poi.builder("shop", "world", 0, 0, 0).owner(BOB).kind("home").build());
            store.save(Poi.builder("north", "world", 0, 0, 0).owner(ALICE).kind("stop")
                    .shared(true).build());
        }

        @Test
        @DisplayName("everything one person owns, of one kind")
        void findsByOwnerAndKind() {
            assertThat(store.owned(ALICE, "home")).extracting(Poi::name)
                    .containsExactlyInAnyOrder("base", "mine");
        }

        @Test
        @DisplayName("everything one person owns, whatever kind")
        void findsEverythingOwned() {
            assertThat(store.owned(ALICE)).hasSize(3);
        }

        @Test
        @DisplayName("everything of one kind, whoever owns it")
        void findsByKind() {
            assertThat(store.ofKind("home")).hasSize(3);
        }

        @Test
        @DisplayName("only the ones their owner has shared")
        void findsSharedOnes() {
            assertThat(store.shared()).extracting(Poi::name).containsExactly("north");
        }

        @Test
        @DisplayName("everything in a world, so a world being removed can be dealt with")
        void findsByWorld() {
            assertThat(store.inWorld("world")).hasSize(4);
            assertThat(store.inWorld("nether")).isEmpty();
        }

        @Test
        @DisplayName("how many one person has, for a limit")
        void counts() {
            assertThat(store.count(ALICE, "home")).isEqualTo(2);
            assertThat(store.count(BOB, "home")).isEqualTo(1);
        }

        @Test
        @DisplayName("asking for something that is not there is empty, not an exception")
        void missingIsEmpty() {
            assertThat(store.byId("nope")).isEmpty();
            assertThat(store.named(ALICE, "home", "nowhere")).isEmpty();
            assertThat(store.owned(null)).isEmpty();
            assertThat(store.ofKind(null)).isEmpty();
        }
    }

    // ------------------------------------------------------------------ persistence

    @Nested
    @DisplayName("across a restart")
    class Persistence {

        @Test
        @DisplayName("everything saved is still there")
        void roundTrips() {
            Poi base = home("base");
            Poi market = Poi.builder("market", "nether", 1, 2, 3).owner(BOB).kind("stop")
                    .icon(Material.BELL).label("The market").shared(true).tag("line", "north")
                    .build();
            store.save(base);
            store.save(market);
            store.flush();

            // Closed and reopened over the same file, because that is what a restart is.
            database.close();
            database = openDatabase();
            PoiStore reopened = new PoiStore(database);
            reopened.load();

            assertThat(reopened.all()).hasSize(2);
            assertThat(reopened.byId(base.id())).contains(base);
            assertThat(reopened.byId(market.id())).contains(market);
        }

        @Test
        @DisplayName("a file that was never written loads as empty rather than failing")
        void survivesAMissingFile() {
            Database empty = TestDatabases.open(directory.resolve("never-used.db"), CoreSchema.CORE,
                    () -> false);
            PoiStore fresh = new PoiStore(empty);
            assertThatCode(fresh::load).doesNotThrowAnyException();
            assertThat(fresh.all()).isEmpty();
        }

        @Test
        @DisplayName("one unreadable row is skipped and the rest still load")
        void skipsBadEntries() {
            store.save(home("base"));
            store.flush();
            // A row the loader cannot make sense of, put there directly.
            //
            // Note what it took to write one: `name` and `world` are NOT NULL, so the two things the
            // old YAML version of this test made bad are now refused by the database itself — which
            // is the point of having one. What is left is a value only the loader can judge: an owner
            // that is not a UUID, as a hand-edited database or an older version might leave behind.
            boolean inserted = database.write(connection -> {
                try (var statement = connection.prepareStatement(
                        "INSERT INTO place (id, name, kind, owner, world, x, y, z) "
                                + "VALUES ('broken', 'nowhere', 'home', 'not-a-uuid', "
                                + "'world', 0, 0, 0)")) {
                    statement.executeUpdate();
                }
            });
            assertThat(inserted).isTrue();

            database.close();
            database = openDatabase();
            PoiStore reopened = new PoiStore(database);
            reopened.load();

            assertThat(reopened.all()).extracting(Poi::name).containsExactly("base");
            assertThat(reopened.problems())
                    .as("a skipped row must be reported, not silently dropped")
                    .hasSize(1);
        }

        @Test
        @DisplayName("a place whose world has gone is kept, not thrown away")
        void keepsPlacesInMissingWorlds() throws Exception {
            store.save(Poi.builder("old", "a-world-that-was-deleted", 1, 2, 3)
                    .owner(ALICE).kind("home").build());
            store.flush();

            // Closed and reopened over the same file, because that is what a restart is.
            database.close();
            database = openDatabase();
            PoiStore reopened = new PoiStore(database);
            reopened.load();

            assertThat(reopened.all())
                    .as("a world unloaded for maintenance must not cost every home in it")
                    .hasSize(1);
        }

        @Test
        @DisplayName("a change made while a write is running is written by the next one")
        void changesDuringAWriteSurvive() throws InterruptedException {
            Poi place = home("base");
            store.save(place);
            store.flush();

            // Genuinely concurrent, because that is the only way to land a change inside a write. One
            // thread renames the place over and over; another flushes over and over. Whatever the
            // interleaving, the last rename must reach the database — the old code could unmark it.
            java.util.concurrent.atomic.AtomicReference<String> lastName =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Thread renaming = new Thread(() -> {
                for (int at = 0; at < 400; at++) {
                    String name = "rename " + at;
                    store.save(store.byId(place.id()).orElseThrow().renamedTo(name));
                    lastName.set(name);
                }
            });
            Thread saving = new Thread(() -> {
                for (int at = 0; at < 400; at++) {
                    store.flush();
                }
            });
            renaming.start();
            saving.start();
            renaming.join();
            saving.join();

            // One last flush, which is what the save timer would do a moment later.
            store.flush();
            database.close();
            database = openDatabase();
            PoiStore reopened = new PoiStore(database);
            reopened.load();

            assertThat(reopened.byId(place.id()).orElseThrow().name())
                    .as("the last rename has to be on disk. Snapshot-then-clear could take the mark "
                            + "off a change that arrived during the write, and then nothing would "
                            + "ever write it")
                    .isEqualTo(lastName.get());
        }

        @Test
        @DisplayName("a change made between two flushes is written")
        void changesBetweenWritesSurvive() {
            Poi first = home("base");
            store.save(first);
            store.flush();

            // The race, made deterministic. A place is marked, the flush takes its snapshot and
            // writes it, and the owner renames it *while that write is in flight*. The old code
            // cleared the marks with removeAll(snapshot) afterwards — which removed the mark the
            // rename had just put back, because the key was already in the snapshot. The rename was
            // then never written, and nothing would write it again until somebody touched that place
            // once more.
            Poi renamed = first.renamedTo("renamed while saving");
            store.save(renamed);
            store.flush();

            database.close();
            database = openDatabase();
            PoiStore reopened = new PoiStore(database);
            reopened.load();

            assertThat(reopened.byId(first.id()).orElseThrow().name())
                    .as("a change that arrives during a write must be written by the next flush, "
                            + "not silently unmarked")
                    .isEqualTo("renamed while saving");
        }

        @Test
        @DisplayName("nothing stays marked after a write that worked")
        void marksAreClearedOnSuccess() {
            store.save(home("base"));

            assertThat(store.isDirty()).isTrue();
            store.flush();
            assertThat(store.isDirty())
                    .as("a mark left behind means every flush rewrites every row for ever")
                    .isFalse();
        }

        @Test
        @DisplayName("a write that failed leaves the change marked, so the next flush tries again")
        void marksSurviveAFailedWrite() {
            store.save(home("base"));
            database.close();

            store.flush();

            assertThat(store.isDirty())
                    .as("dropping the mark when the write failed is losing the place: nothing would "
                            + "ever write it again")
                    .isTrue();
        }

        @Test
        @DisplayName("a store with nothing to say writes nothing")
        void doesNotWriteWithoutChanges() {
            store.save(home("base"));
            store.flush();

            assertThat(store.isDirty()).isFalse();

            // Nothing marked, so nothing to write. Measured by what is waiting rather than by the
            // file's timestamp, which is what this used to look at and which SQLite touches for its
            // own reasons.
            store.flush();

            assertThat(store.isDirty()).isFalse();
            assertThat(store.all()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("saying whether it wrote")
    class Reporting {

        @Test
        @DisplayName("a flush that wrote says so")
        void aGoodFlushSaysSo() {
            // What a migration has to know. A caller that moves the old file aside on the strength of
            // an import needs to be sure the import actually landed — otherwise the source is gone and
            // the copy was never written.
            store.save(home("base"));

            assertThat(store.flush()).isTrue();
        }

        @Test
        @DisplayName("a flush with nothing to write also says so")
        void nothingToWriteIsStillFine() {
            // "Nothing changed" is not a failure. A caller that treated it as one would refuse to
            // finish a migration that had already finished.
            assertThat(store.flush()).isTrue();
        }

        @Test
        @DisplayName("a flush that could not write says that instead")
        void aFailedFlushSaysSo() {
            store.save(home("base"));
            closeDatabase();

            assertThat(store.flush())
                    .as("the database is gone, so nothing was written — and a caller about to delete "
                            + "its source data has to be told")
                    .isFalse();
        }

        @Test
        @DisplayName("what could not be written is still there to try again")
        void aFailedFlushKeepsTheWork() {
            store.save(home("base"));
            closeDatabase();
            store.flush();

            assertThat(store.isDirty())
                    .as("dropping the marks on a failed write would lose the places outright: "
                            + "nothing else would ever write them")
                    .isTrue();
        }
    }

    // ------------------------------------------------------------------ the value itself

    @Nested
    @DisplayName("a place")
    class TheValue {

        @Test
        @DisplayName("reads its coordinates the way a person would")
        void readsItsCoordinates() {
            assertThat(home("base").coordinates()).isEqualTo("121, 64, -310");
        }

        @Test
        @DisplayName("can be renamed and moved without losing its identity")
        void keepsItsIdThroughChanges() {
            Poi base = home("base");
            assertThat(base.renamedTo("home").id()).isEqualTo(base.id());
            assertThat(base.movedTo("nether", 1, 2, 3).id()).isEqualTo(base.id());
            assertThat(base.renamedTo("home").name()).isEqualTo("home");
        }

        @Test
        @DisplayName("two places are never given the same id")
        void idsAreUnique() {
            assertThat(home("base").id()).isNotEqualTo(home("base").id());
        }

        @Test
        @DisplayName("needs a name, a world and an owner")
        void refusesNonsense() {
            assertThatCode(() -> Poi.builder(null, "world", 0, 0, 0).build())
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatCode(() -> Poi.builder("x", null, 0, 0, 0).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a label falls back to the name, so something is always shown")
        void labelFallsBackToName() {
            assertThat(home("base").label()).isEqualTo("base");
        }

        @Test
        @DisplayName("a label can be given and taken away again")
        void theLabelCanBeChanged() {
            Poi base = home("base");

            assertThat(base.withLabel("The Old Quarry").label()).isEqualTo("The Old Quarry");
            assertThat(base.withLabel("The Old Quarry").name())
                    .as("the name is what a command takes and what a permission was written "
                            + "against, so labelling must not touch it")
                    .isEqualTo("base");
            assertThat(base.withLabel("The Old Quarry").withLabel(null).label())
                    .as("cleared, it goes back to being called by its name")
                    .isEqualTo("base");
        }

        @Test
        @DisplayName("which way you face can be changed without losing anything else")
        void theFacingCanBeChanged() {
            Poi base = home("base").withTag("permission", "staff.only").withLabel("Quarry");

            Poi turned = base.withFacing(90f, -12f);

            assertThat(turned.yaw()).isEqualTo(90f);
            assertThat(turned.pitch()).isEqualTo(-12f);
            assertThat(turned.id()).isEqualTo(base.id());
            assertThat(turned.tag("permission"))
                    .as("turning a place round must not open a staff-only one to everybody")
                    .contains("staff.only");
            assertThat(turned.label()).isEqualTo("Quarry");
        }

        @Test
        @DisplayName("moving a place keeps its tags, its icon and its label")
        void movingKeepsEverythingElse() {
            // The reason move() exists rather than "just create it again": creating replaces, and
            // replacing loses the permission — so redoing a badly placed staff warp would open it to
            // the whole server without a word.
            Poi base = home("base").withTag("permission", "staff.only").withLabel("Quarry");

            Poi moved = base.movedTo("nether", 10, 20, 30);

            assertThat(moved.tag("permission")).contains("staff.only");
            assertThat(moved.label()).isEqualTo("Quarry");
            assertThat(moved.id()).isEqualTo(base.id());
        }
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    @DisplayName("saving from many threads at once loses nothing")
    void isSafeFromEveryThread() throws Exception {
        int threads = 8;
        int each = 100;
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int thread = 0; thread < threads; thread++) {
                int id = thread;
                pool.submit(() -> {
                    go.await();
                    for (int index = 0; index < each; index++) {
                        store.save(Poi.builder("place-" + id + "-" + index, "world", 0, 0, 0)
                                .owner(ALICE).kind("home").build());
                    }
                    return null;
                });
            }
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(store.all()).hasSize(threads * each);
        assertThat(store.count(ALICE, "home")).isEqualTo(threads * each);
    }

    @Test
    @DisplayName("the list handed back cannot be used to change the store behind its back")
    void handsBackCopies() {
        store.save(home("base"));
        List<Poi> all = store.all();
        assertThatCode(() -> all.add(home("sneaky"))).isInstanceOf(UnsupportedOperationException.class);
        assertThat(store.all()).hasSize(1);
    }

    @Test
    @DisplayName("a place can be looked up without the server being asked about worlds")
    void doesNotNeedAServer() {
        // Every assertion in this class has run without a Bukkit server, which is the point: the
        // world is a name until somebody actually asks for a Location.
        Optional<Poi> found = store.byId("nothing");
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("a flush does not answer 'written' while an earlier one still has the change in hand")
    void aFlushWaitsForTheOneAlreadyWriting() throws Exception {
        store.save(Poi.builder("base", "world", 0, 0, 0).owner(BOB).kind("home").build());

        AutoCloseable held = TestDatabases.holdWrites(directory.resolve("core.db"), "place");
        {
            java.util.concurrent.CompletableFuture<Boolean> first =
                    java.util.concurrent.CompletableFuture.supplyAsync(store::flush);
            long until = System.currentTimeMillis() + 5_000;
            while (store.isDirty() && System.currentTimeMillis() < until) {
                Thread.sleep(5);
            }
            assertThat(store.isDirty()).as("the first flush took the change").isFalse();

            // What the shutdown flush is, while a timer flush cancelled a moment ago is still running.
            java.util.concurrent.CompletableFuture<Boolean> second =
                    java.util.concurrent.CompletableFuture.supplyAsync(store::flush);
            Thread.sleep(300);
            assertThat(second)
                    .as("answering now lets the databases close under the write still running")
                    .isNotDone();

            held.close();
            assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("a place saved or deleted asks to be written at once, not at the next saving timer")
    void writtenSoon() {
        java.util.List<Runnable> asked = new java.util.ArrayList<>();
        store.writeSoon(asked::add);

        store.save(home("base"));
        assertThat(asked).isNotEmpty();
        asked.forEach(Runnable::run);
        assertThat(store.isDirty()).isFalse();

        asked.clear();
        store.delete(store.all().getFirst().id());
        assertThat(asked).isNotEmpty();
    }
}
