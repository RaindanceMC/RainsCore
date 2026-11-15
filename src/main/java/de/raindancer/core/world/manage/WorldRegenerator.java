package de.raindancer.core.world.manage;

import de.raindancer.core.RainsCore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Creating, deleting and regenerating a world, generically — the one place any of the three has to be
 * gotten right, so a module needing "make sure this world exists" or "wipe this one for a fresh
 * attempt" reaches for this instead of writing its own copy of unload-then-delete-then-recreate.
 *
 * <h2>Seeds</h2>
 * By default none is set: a plain regeneration is a wipe, not a "restore this exact map" tool, and a
 * fixed seed would make every regeneration of a world identical to the last — only occasionally what
 * somebody wants, and surprising the rest of the time. The overloads taking a {@link WorldSeed} are
 * for when it <em>is</em> wanted: the map it had, or a seed somebody chose.
 *
 * <p>Handed a {@link SeedHistory}, every seed passing through here is written down — the outgoing one
 * <em>before</em> its folder is deleted, since after that it exists nowhere, and the new one once the
 * world is back. Without one nothing is recorded, which is what a test or a caller outside a running
 * Core wants.
 *
 * <h2>Order of operations, and why it is fixed</h2>
 * Evacuate whoever is standing in it — back to wherever {@link WorldEntryPoints} last saw them before
 * they arrived, not a generic spawn — wait for every one of those moves to actually land, unload
 * without saving, delete the folder deepest-first, then create again. Each step exists for a reason:
 * teleporting after the unload would fail (the world is gone), saving before deleting would spend time
 * writing chunks that are about to be thrown away, deleting before the world is unloaded fights the OS
 * over files the server still has open, and unloading before every occupant has actually left is
 * either refused by Bukkit outright or strands whoever's teleport had not yet landed.
 *
 * <h2>Why {@link #delete} and {@link #regenerate} take a callback</h2>
 * Waiting for occupants to actually arrive somewhere is asynchronous — {@link Player#teleportAsync}
 * is the only safe way to move someone across worlds at all, let alone on Folia — so there is no
 * synchronous answer to give back. A caller with nobody to evacuate still gets its answer on the same
 * tick; one with occupants gets it once their teleports resolve.
 *
 * <h2>Threading</h2>
 * Every callback runs on the main thread (a {@link Player#teleportAsync} future completes there), so
 * touching Bukkit API from inside one is safe. Everything else here — {@link #create}, and {@link #delete}
 * for an already-empty world — still requires being called from the main thread in the first place;
 * creating, unloading and deleting a world are main-thread-only operations in Paper regardless.
 */
public final class WorldRegenerator {

    private static final LogChannel log = Log.of("world");

    private final SeedHistory history;
    private final Consumer<Runnable> onGlobal;

    /** A regenerator that writes no seed down anywhere. */
    public WorldRegenerator() {
        this(null);
    }

    /** @param history where every seed created or thrown away is recorded; null records nothing */
    public WorldRegenerator(SeedHistory history) {
        this(history, Runnable::run);
    }

    /**
     * @param history  where every seed created or thrown away is recorded; null records nothing
     * @param onGlobal how to get onto the thread that may unload, delete and create worlds — Core passes
     *                 the global region scheduler. Teleports and database writes finish on whatever thread
     *                 they finish on, and every step after one of them hops through this first. Running
     *                 in place is right only for a caller already on that thread, such as a test.
     */
    public WorldRegenerator(SeedHistory history, Consumer<Runnable> onGlobal) {
        this.history = history;
        this.onGlobal = onGlobal == null ? Runnable::run : onGlobal;
    }

    /**
     * Runs {@code then} once every seed recorded so far is on disk, back on the world thread. A seed
     * that cannot be written stops the destructive step: afterwards it would exist nowhere.
     */
    private void onceSeedsAreWritten(Runnable then, Consumer<Boolean> whenDone) {
        if (history == null) {
            then.run();
            return;
        }
        CompletableFuture<Boolean> written = history.flushNow();
        if (written == null) {
            then.run();
            return;
        }
        written.whenComplete((ok, failure) -> onGlobal.accept(() -> {
            if (failure == null && Boolean.TRUE.equals(ok)) {
                then.run();
                return;
            }
            log.error("The seed history could not be written, so nothing was deleted: the seed of the "
                    + "world that would have gone exists nowhere else.");
            whenDone.accept(false);
        }));
    }

    /**
     * Deletes {@code world}'s folder and recreates it, empty, under the same name — {@link #delete}
     * followed by {@link #create}, so the two share exactly one copy of each step's own rules (the
     * primary world refused, occupants evacuated first, Bukkit's own refusal handled).
     *
     * @param world    the world to throw away; must still be loaded, so its real folder can be read
     *                 from it before anything happens to it — resolving the folder from the world
     *                 container by name alone is a bug, not a shortcut, since Paper 26 nests a
     *                 non-primary world's folder under {@code <level-name>/dimensions/<namespace>/<name>}
     * @param whenDone told once, with whether the world came back — the server log has the reason
     *                 either way when it did not
     */
    public void regenerate(World world, Consumer<Boolean> whenDone) {
        regenerate(world, WorldSeed.random(), whenDone);
    }

    /**
     * The same, with the seed chosen — {@link WorldSeed#same()} puts back the map it had,
     * {@link WorldSeed#fixed} makes a chosen one, and {@link WorldSeed#random()} is the plain wipe.
     */
    public void regenerate(World world, WorldSeed seed, Consumer<Boolean> whenDone) {
        if (world == null || refused(world)) {
            whenDone.accept(false);
            return;
        }
        String name = world.getName();
        // Everything below is read while the world is still there. Once it is deleted nothing left says
        // whether it was a nether, a flat world or a generator plugin's, what its game rules were, or
        // which seed it had — and recreating it wrong is a swap nobody can undo.
        World.Environment environment = world.getEnvironment();
        long outgoing = world.getSeed();
        record(name, outgoing, SeedHistory.Cause.REPLACED);
        long chosen = seedFor(seed == null ? WorldSeed.random() : seed, outgoing, OptionalLong.empty());
        WorldCreator creator = new WorldCreator(name).copy(world).environment(environment);
        WorldSnapshot carried = WorldSnapshot.of(world);
        onceSeedsAreWritten(() -> deleteWithoutRecording(world, deleted -> {
            if (!deleted) {
                whenDone.accept(false);
                return;
            }
            whenDone.accept(make(name, creator, OptionalLong.of(chosen), SeedHistory.Cause.REGENERATED,
                    carried, chosen == outgoing));
        }), whenDone);
    }

    /**
     * The seed a regenerated world is made with. Always an explicit one: the creator is a copy of the
     * old world's, seed included, so "random" has to be drawn here or it would quietly be the old map.
     */
    private static long seedFor(WorldSeed seed, long outgoing, OptionalLong shared) {
        if (seed.kind() == WorldSeed.Kind.RANDOM) {
            return shared.isPresent() ? shared.getAsLong()
                    : ThreadLocalRandom.current().nextLong();
        }
        return seed.resolve(OptionalLong.of(outgoing)).orElse(outgoing);
    }

    /**
     * Whether this world may not be deleted or regenerated at all — said in the log, since the caller only
     * hears "no".
     */
    private static boolean refused(World world) {
        if (isPrimaryWorld(world)) {
            log.error("Cannot delete or regenerate '{}': it is this server's primary world, which "
                    + "Bukkit never allows to be unloaded.", world.getName());
            return true;
        }
        if (isServerDimension(world)) {
            log.error("Cannot delete or regenerate '{}': it is the primary level's own dimension. Stop "
                    + "the server and delete its folder instead. At runtime it cannot be made again "
                    + "as the same dimension.", world.getName());
            return true;
        }
        return false;
    }

    /**
     * Whether {@code world} is one of the primary level's own three dimensions ({@code minecraft:overworld},
     * {@code minecraft:the_nether}, {@code minecraft:the_end}). A world created at runtime under the name
     * {@code world_nether} is a different level with its own key, and is not one of these.
     */
    public static boolean isServerDimension(World world) {
        NamespacedKey key = world == null ? null : world.getKey();
        return key != null && key.getNamespace().equals(NamespacedKey.MINECRAFT)
                && (key.getKey().equals("overworld") || key.getKey().equals("the_nether")
                || key.getKey().equals("the_end"));
    }

    /**
     * Unloads {@code world} and deletes its folder — no recreation. What {@link #regenerate} calls
     * first; also useful on its own for a caller that wants a name gone for good, not remade empty.
     *
     * <p>Whoever is standing in it is sent back to wherever they were right before they entered it —
     * see {@link WorldEntryPoints} — falling back to the first loaded world's spawn only when nothing
     * was ever recorded for them. Unlike {@link #evacuate}, this <em>waits</em> for every teleport to
     * actually land before unloading anything: a caller of this method gets one answer, not "try again
     * once they have left" for {@code evacuate}'s own callers to poll.
     *
     * @param whenDone told once, with whether it is gone — false leaves the world exactly as it was,
     *                 whatever the reason
     */
    public void delete(World world, Consumer<Boolean> whenDone) {
        if (world != null && !isPrimaryWorld(world) && !isServerDimension(world)) {
            // Before anything else, for the reason SeedHistory exists at all: once the folder is gone
            // this seed is written nowhere.
            record(world.getName(), world.getSeed(), SeedHistory.Cause.DELETED);
            World doomed = world;
            onceSeedsAreWritten(() -> deleteWithoutRecording(doomed, whenDone), whenDone);
            return;
        }
        deleteWithoutRecording(world, whenDone);
    }

    private void deleteWithoutRecording(World world, Consumer<Boolean> whenDone) {
        if (world == null) {
            whenDone.accept(false);
            return;
        }
        String name = world.getName();
        // Not a transient failure worth retrying — Bukkit refuses the primary world unconditionally,
        // for ever. Checked before evacuating anybody, so a misconfigured caller finds out without
        // moving players for nothing.
        if (refused(world)) {
            whenDone.accept(false);
            return;
        }
        Path folder = world.getWorldFolder().toPath();
        evacuateThen(List.of(world), name, evacuated -> {
            if (!evacuated) {
                whenDone.accept(false);
                return;
            }
            whenDone.accept(unloadAndDelete(world, folder, name, ""));
        });
    }

    /**
     * Regenerates worlds that belong together — a run's overworld, nether and end — as one operation.
     *
     * <h2>Why one at a time is wrong</h2>
     * Whoever stands in the second world entered it from the first, so "send them back where they came
     * from" sends them into a world that was deleted a moment ago. That is the speedrun reset that left
     * its nether and end untouched whenever somebody was still in them. Here every occupant of every
     * world is moved somewhere <em>outside</em> the group, all of those moves are waited for, and only
     * then is anything unloaded.
     *
     * <h2>Seeds</h2>
     * A fixed seed is used for every world, and {@link WorldSeed#same()} gives each world back its own.
     * A random seed is drawn <em>once</em> and shared, since a nether and an end generated from a
     * different seed than their overworld are not that overworld's dimensions any more.
     *
     * <h2>When some of it fails</h2>
     * A world that will not unload is left exactly as it was, and the rest still go ahead: the admin
     * gets a mostly fresh group and a log line naming the one that is not, rather than a group left
     * half-evacuated and entirely old. The answer is {@code true} only when every world came back.
     *
     * @param worlds   loaded, in the order they should be made again — overworld first, so a portal
     *                 linking lookup during creation finds it
     * @param whenDone told once, on the main thread
     */
    public void regenerateAll(List<World> worlds, WorldSeed seed, Consumer<Boolean> whenDone) {
        List<World> group = groupOf(worlds);
        if (group.isEmpty()) {
            whenDone.accept(false);
            return;
        }
        WorldSeed chosen = seed == null ? WorldSeed.random() : seed;
        OptionalLong shared = chosen.kind() == WorldSeed.Kind.RANDOM
                ? OptionalLong.of(ThreadLocalRandom.current().nextLong())
                : OptionalLong.empty();

        // Everything that must be read while the worlds still exist.
        record Doomed(World world, String name, Path folder, long seed, WorldCreator creator,
                      WorldSnapshot carried) {
        }
        List<Doomed> doomed = new ArrayList<>();
        for (World world : group) {
            doomed.add(new Doomed(world, world.getName(), world.getWorldFolder().toPath(), world.getSeed(),
                    new WorldCreator(world.getName()).copy(world).environment(world.getEnvironment()),
                    WorldSnapshot.of(world)));
            record(world.getName(), world.getSeed(), SeedHistory.Cause.REPLACED);
        }
        String names = String.join(", ", doomed.stream().map(Doomed::name).toList());

        onceSeedsAreWritten(() -> evacuateThen(group, names, evacuated -> {
            if (!evacuated) {
                whenDone.accept(false);
                return;
            }
            boolean everyOne = true;
            List<Doomed> gone = new ArrayList<>();
            for (Doomed each : doomed) {
                if (unloadAndDelete(each.world(), each.folder(), each.name(), ", to be made again")) {
                    gone.add(each);
                } else {
                    everyOne = false;
                }
            }
            for (Doomed each : gone) {
                long seedFor = seedFor(chosen, each.seed(), shared);
                everyOne &= make(each.name(), each.creator(), OptionalLong.of(seedFor),
                        SeedHistory.Cause.REGENERATED, each.carried(), seedFor == each.seed());
            }
            whenDone.accept(everyOne);
        }), whenDone);
    }

    /**
     * Deletes worlds that belong together, for good — the same one-operation shape as
     * {@link #regenerateAll}, for the same reason: deleting a run's overworld first and its nether second
     * sends whoever is in the nether back into an overworld that is already gone.
     *
     * @param whenDone told once, with whether every one of them is gone; one that would not unload is
     *                 left exactly as it was, and named in the log
     */
    public void deleteAll(List<World> worlds, Consumer<Boolean> whenDone) {
        List<World> group = groupOf(worlds);
        if (group.isEmpty()) {
            whenDone.accept(false);
            return;
        }
        record Doomed(World world, String name, Path folder) {
        }
        List<Doomed> doomed = new ArrayList<>();
        for (World world : group) {
            doomed.add(new Doomed(world, world.getName(), world.getWorldFolder().toPath()));
            record(world.getName(), world.getSeed(), SeedHistory.Cause.DELETED);
        }
        String names = String.join(", ", doomed.stream().map(Doomed::name).toList());
        onceSeedsAreWritten(() -> evacuateThen(group, names, evacuated -> {
            if (!evacuated) {
                whenDone.accept(false);
                return;
            }
            boolean everyOne = true;
            for (Doomed each : doomed) {
                everyOne &= unloadAndDelete(each.world(), each.folder(), each.name(), "");
            }
            whenDone.accept(everyOne);
        }), whenDone);
    }

    /**
     * Moves everybody standing in any of {@code leaving} somewhere outside all of them, waits until every
     * one of those moves has landed, then answers — the part {@link #delete} and {@link #regenerateAll}
     * share. Nobody to move is an answer on the same tick.
     */
    private void evacuateThen(List<World> leaving, String what, Consumer<Boolean> next) {
        List<CompletableFuture<Boolean>> moves = new ArrayList<>();
        for (World world : leaving) {
            for (Player player : List.copyOf(world.getPlayers())) {
                Location destination = destinationFor(player, leaving);
                if (destination == null) {
                    log.error("Cannot clear '{}': there is nowhere to move {} to.", what, player.getName());
                    next.accept(false);
                    return;
                }
                moves.add(player.teleportAsync(destination));
            }
        }
        if (moves.isEmpty()) {
            next.accept(true);
            return;
        }
        CompletableFuture.allOf(moves.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, failure) -> onGlobal.accept(() -> {
                    // "Every future finished" is not "everybody left": Paper answers false for a
                    // cross-world teleport it would not do — somebody carrying a passenger, for one — and
                    // unloading then deletes the world around them.
                    boolean everybodyLeft = failure == null
                            && moves.stream().allMatch(move -> Boolean.TRUE.equals(move.getNow(false)));
                    if (!everybodyLeft) {
                        log.error(failure, "Not everybody could be moved out of '{}', so nothing was "
                                + "deleted.", what);
                    }
                    next.accept(everybodyLeft);
                }));
    }

    /**
     * The non-null, distinct worlds of a group — empty, which every caller answers "no" to, when there
     * are none or when any one of them may not be deleted at all.
     */
    private static List<World> groupOf(List<World> worlds) {
        List<World> group = worlds == null ? List.of()
                : worlds.stream().filter(Objects::nonNull).distinct().toList();
        return group.stream().anyMatch(WorldRegenerator::refused) ? List.of() : group;
    }

    /**
     * Unloads without saving, then deletes the folder. A world that will not unload is left exactly as
     * it was; one only partly deleted is said loudly, since somebody has to finish it by hand.
     */
    private static boolean unloadAndDelete(World world, Path folder, String name, String purpose) {
        if (!Bukkit.unloadWorld(world, false)) {
            log.error("Could not unload '{}', so it is left as it was.", name);
            return false;
        }
        if (!deleteFolder(folder, name)) {
            log.fatal("'{}' was only partly deleted. Its folder is at {}. Remove it by hand.",
                    name, folder);
            return false;
        }
        log.info("World '{}' has been deleted{}.", name, purpose);
        return true;
    }

    /**
     * Where to send {@code player} on their way out of {@code leaving} — wherever
     * {@link WorldEntryPoints} last saw them before they entered it, if that is still somewhere real
     * and not {@code leaving} itself, or the first loaded world's spawn otherwise.
     */
    private static Location destinationFor(Player player, List<World> leaving) {
        Location remembered = RainsCore.get().worldEntryPoints().before(player.getUniqueId())
                // isWorldLoaded first: a Location in a world that has since been unloaded throws from
                // getWorld() rather than answering null. That is exactly the case of somebody in a
                // speedrun's nether whose overworld was regenerated a moment earlier.
                .filter(at -> at.isWorldLoaded() && at.getWorld() != null
                        && !leaving.contains(at.getWorld()))
                .orElse(null);
        return remembered != null ? remembered : safeSpawn();
    }

    /**
     * Makes a brand new world called {@code name} from scratch, with whatever seed Bukkit picks —
     * see the class javadoc for why nothing here ever sets one. What {@link #regenerate} calls second;
     * also useful on its own for a caller that just wants a name to exist, nothing having been there
     * before.
     *
     * @return whether it now exists and is loaded
     */
    public boolean create(String name) {
        return create(name, World.Environment.NORMAL);
    }

    /**
     * The same, in a chosen dimension — a nether or an end rather than an overworld. What
     * {@link #regenerate} uses to put a world back as whatever it already was, and what a caller
     * building a linked set of worlds ({@code x}, {@code x_nether}, {@code x_the_end}) needs to make
     * the other two at all.
     *
     * @return whether it now exists and is loaded
     */
    public boolean create(String name, World.Environment environment) {
        return create(name, environment, WorldSeed.random());
    }

    /**
     * The same, with the seed chosen. {@link WorldSeed#same()} means nothing for a world that does not
     * exist yet, so it is a random seed here.
     *
     * @return whether it now exists and is loaded
     */
    public boolean create(String name, World.Environment environment, WorldSeed seed) {
        if (name == null || name.isBlank()) {
            return false;
        }
        if (Bukkit.getWorld(name) != null) {
            log.warn("'{}' is already loaded; not creating it again.", name);
            return false;
        }
        WorldCreator creator = new WorldCreator(name)
                .environment(environment == null ? World.Environment.NORMAL : environment);
        return make(name, creator, (seed == null ? WorldSeed.random() : seed).resolve(OptionalLong.empty()),
                SeedHistory.Cause.CREATED, null, false);
    }

    /**
     * Loads a world whose folder is already there — at startup, say, since Paper only loads the primary
     * level's worlds by itself and every world made at runtime is simply absent after a restart. Nothing
     * is written into the seed history: a world coming back from disk was not created.
     *
     * @return whether it is loaded now
     */
    public boolean load(String name, World.Environment environment) {
        if (name == null || name.isBlank()) {
            return false;
        }
        if (Bukkit.getWorld(name) != null) {
            return true;
        }
        WorldCreator creator = new WorldCreator(name)
                .environment(environment == null ? World.Environment.NORMAL : environment);
        return make(name, creator, OptionalLong.empty(), null, null, false);
    }

    /**
     * Makes the world, writes down the seed it actually got, and puts back whatever {@code carried} holds.
     *
     * @param seed    empty lets the server pick
     * @param cause   null records nothing — a world loaded from disk was not created
     * @param sameMap whether the new world is the same map as the one {@code carried} was read from
     */
    private boolean make(String name, WorldCreator creator, OptionalLong seed, SeedHistory.Cause cause,
                         WorldSnapshot carried, boolean sameMap) {
        if (Bukkit.getWorld(name) != null) {
            log.warn("'{}' is already loaded; not creating it again.", name);
            return false;
        }
        WorldCreator configured = seed.isPresent() ? creator.seed(seed.getAsLong()) : creator;
        World created = configured.createWorld();
        if (created == null) {
            log.error("The server would not create the world '{}'.", name);
            return false;
        }
        // The seed the world actually got, not the one asked for: with none asked for, this is the
        // only moment the server's own pick can be written down.
        if (cause != null) {
            record(name, created.getSeed(), cause);
        }
        if (carried != null) {
            try {
                carried.applyTo(created, sameMap);
            } catch (RuntimeException failure) {
                // The world exists and is usable; losing a game rule is not worth reporting it as gone.
                log.warn(failure, "'{}' is back, but not every setting it had could be restored.", name);
            }
        }
        log.info("World '{}' has been created.", name);
        return true;
    }

    private void record(String world, long seed, SeedHistory.Cause cause) {
        if (history != null) {
            history.record(world, seed, cause);
        }
    }

    /**
     * Whether {@code world} is the one at index 0 of {@link Bukkit#getWorlds()} — Paper's own
     * level-name world, which {@link Bukkit#unloadWorld} refuses unconditionally. Public so a caller
     * can warn about a configuration that names the primary world before ever attempting
     * {@link #delete}/{@link #regenerate}, rather than only finding out when one fails.
     */
    public static boolean isPrimaryWorld(World world) {
        List<World> worlds = Bukkit.getWorlds();
        return !worlds.isEmpty() && worlds.getFirst().equals(world);
    }

    /**
     * Moves everybody currently in {@code world} out of it, before it is unloaded from under them.
     *
     * <p>Shared with {@code FarmWorlds}, which needs the identical move — evacuate, then leave
     * unloading and deleting to the caller — for each world in a linked set, with its own message and
     * its own retry bookkeeping on top. This is the one place the actual teleport loop lives.
     *
     * @return whether anybody had to be moved at all — a caller must not unload or delete while this
     *         is true, since their teleport has only just started
     */
    public static boolean evacuate(World world, Location safety) {
        return evacuate(world, safety, player -> { });
    }

    /** The same, and also given each player before their teleport starts — for a caller with its own message. */
    public static boolean evacuate(World world, Location safety, Consumer<Player> notify) {
        List<Player> inside = List.copyOf(world.getPlayers());
        for (Player player : inside) {
            try {
                // teleportAsync, not teleport: on Folia a synchronous teleport across regions throws,
                // and the world would then be unloaded with somebody still in it.
                player.teleportAsync(safety);
                notify.accept(player);
            } catch (RuntimeException failure) {
                log.warn(failure, "Could not move {} out of '{}'.", player.getName(), world.getName());
            }
        }
        return !inside.isEmpty();
    }

    /** The first loaded world's spawn — the safe fallback when there is nowhere more specific to send somebody. */
    private static Location safeSpawn() {
        List<World> worlds = Bukkit.getWorlds();
        return worlds.isEmpty() ? null : worlds.getFirst().getSpawnLocation();
    }

    /**
     * Deletes a folder deepest-first, because a directory cannot be removed until it is empty.
     *
     * <p>Shared with {@code FarmWorlds}, which gates every call behind its own
     * {@code FarmWorldState#mayDelete} first — this does no ownership check of its own, and a caller
     * that skipped one would delete whatever path it was handed.
     */
    public static boolean deleteFolder(Path folder, String name) {
        if (folder == null || !Files.exists(folder)) {
            return true;
        }
        try (Stream<Path> contents = Files.walk(folder)) {
            List<Path> deepestFirst = contents.sorted(Comparator.reverseOrder()).toList();
            for (Path each : deepestFirst) {
                Files.deleteIfExists(each);
            }
            return true;
        } catch (IOException failure) {
            log.error(failure, "Could not delete '{}'.", name);
            return false;
        }
    }
}
