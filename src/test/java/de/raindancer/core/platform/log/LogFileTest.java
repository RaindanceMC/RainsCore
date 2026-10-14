package de.raindancer.core.platform.log;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The logfile is the half of the logger that has to work when everything else does not, so it is
 * tested against a real directory rather than a mock.
 */
class LogFileTest {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static Path todaysFile(Path directory) {
        return directory.resolve("rainscore-" + DAY.format(LocalDate.now()) + ".log");
    }

    @Test
    @DisplayName("a written line reaches the file, and close() waits for it")
    void writesAndFlushes(@TempDir Path directory) throws IOException {
        try (LogFile file = new LogFile(directory, 7, ZoneId.systemDefault())) {
            file.write("hello");
            file.write("world");
        }
        assertThat(Files.readAllLines(todaysFile(directory))).containsExactly("hello", "world");
    }

    @Test
    @DisplayName("the directory is created rather than the logger failing")
    void createsItsOwnDirectory(@TempDir Path parent) throws IOException {
        Path nested = parent.resolve("deeper").resolve("logs");
        try (LogFile file = new LogFile(nested, 7, ZoneId.systemDefault())) {
            file.write("made it");
        }
        assertThat(Files.readAllLines(todaysFile(nested))).containsExactly("made it");
    }

    @Test
    @DisplayName("an existing file is appended to, not truncated — a restart does not lose the morning")
    void appendsAcrossRestarts(@TempDir Path directory) throws IOException {
        try (LogFile first = new LogFile(directory, 7, ZoneId.systemDefault())) {
            first.write("before the restart");
        }
        try (LogFile second = new LogFile(directory, 7, ZoneId.systemDefault())) {
            second.write("after the restart");
        }
        assertThat(Files.readAllLines(todaysFile(directory)))
                .containsExactly("before the restart", "after the restart");
    }

    @Test
    @DisplayName("logs older than the retention window are deleted, newer ones are kept")
    void prunesByFileName(@TempDir Path directory) throws IOException {
        Files.createDirectories(directory);
        Path ancient = directory.resolve("rainscore-2020-01-01.log");
        Path yesterday = directory.resolve(
                "rainscore-" + DAY.format(LocalDate.now().minusDays(1)) + ".log");
        Path notOurs = directory.resolve("something-else.log");
        Files.writeString(ancient, "old\n");
        Files.writeString(yesterday, "recent\n");
        Files.writeString(notOurs, "not the logger's business\n");

        try (LogFile file = new LogFile(directory, 7, ZoneId.systemDefault())) {
            file.write("today");
        }

        assertThat(ancient).doesNotExist();
        assertThat(yesterday).exists();
        assertThat(notOurs).exists();
    }

    @Test
    @DisplayName("writing from many threads at once interleaves lines but never corrupts one")
    void isSafeFromEveryThread(@TempDir Path directory) throws Exception {
        int threads = 8;
        int perThread = 200;
        CountDownLatch start = new CountDownLatch(1);
        try (LogFile file = new LogFile(directory, 7, ZoneId.systemDefault());
             ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int thread = 0; thread < threads; thread++) {
                int id = thread;
                pool.submit(() -> {
                    start.await();
                    for (int line = 0; line < perThread; line++) {
                        file.write("thread-" + id + "-line-" + line);
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        List<String> written = Files.readAllLines(todaysFile(directory));
        assertThat(written).hasSize(threads * perThread);
        // Every line is whole: no half of one line followed by half of another.
        assertThat(written).allMatch(line -> line.matches("thread-\\d-line-\\d+"));
        assertThat(written).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("writing after close is ignored rather than throwing")
    void survivesUseAfterClose(@TempDir Path directory) {
        LogFile file = new LogFile(directory, 7, ZoneId.systemDefault());
        file.write("before");
        file.close();
        file.write("after");
        file.close();
        assertThat(file.droppedLines()).isZero();
    }

    // ------------------------------------------------------------------ shutdown protocol

    /**
     * The bug this replaces: stopping used to be signalled by offering a poison pill onto the same
     * bounded queue. A full queue — the state a server in trouble is actually in — dropped the pill,
     * the drain thread waited on a queue nobody would add to, and every shutdown paid the full join
     * timeout before the thread was interrupted and its backlog thrown away.
     */
    @Test
    @DisplayName("close() finishes a full queue promptly instead of timing out")
    void closesPromptlyWithAFullQueue(@TempDir Path directory) throws IOException {
        int flood = 8192 + 500;
        LogFile file = new LogFile(directory, 7, ZoneId.systemDefault());
        for (int line = 0; line < flood; line++) {
            file.write("line-" + line);
        }

        long before = System.nanoTime();
        file.close();
        long tookMillis = (System.nanoTime() - before) / 1_000_000;

        // The old poison-pill protocol took the full five-second grace period here, every time.
        assertThat(tookMillis)
                .withFailMessage("close() took %dms — it is waiting for a signal that never arrives",
                        tookMillis)
                .isLessThan(4_000);
        assertThat(Files.readAllLines(todaysFile(directory))).isNotEmpty();
    }

    @Test
    @DisplayName("everything queued before close() is on disk after it")
    void losesNothingOnClose(@TempDir Path directory) throws IOException {
        int lines = 2_000;
        try (LogFile file = new LogFile(directory, 7, ZoneId.systemDefault())) {
            for (int line = 0; line < lines; line++) {
                file.write("line-" + line);
            }
        }
        assertThat(Files.readAllLines(todaysFile(directory))).hasSize(lines);
    }

    @Test
    @DisplayName("closeInBackground() returns at once and still finishes the file")
    void closesWithoutBlockingTheCaller(@TempDir Path directory) throws Exception {
        LogFile file = new LogFile(directory, 7, ZoneId.systemDefault());
        for (int line = 0; line < 2_000; line++) {
            file.write("line-" + line);
        }

        long before = System.nanoTime();
        file.closeInBackground();
        long tookMillis = (System.nanoTime() - before) / 1_000_000;
        assertThat(tookMillis).isLessThan(200);

        // The backlog is still written, just not on this thread.
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline
                // Not there at all yet is "not written yet" too: the background writer creates the file.
                && (!Files.exists(todaysFile(directory))
                    || Files.readAllLines(todaysFile(directory)).size() < 2_000)) {
            Thread.sleep(25);
        }
        assertThat(Files.readAllLines(todaysFile(directory))).hasSize(2_000);
    }

    @Test
    @DisplayName("close() twice is harmless")
    void closeIsIdempotent(@TempDir Path directory) {
        LogFile file = new LogFile(directory, 7, ZoneId.systemDefault());
        file.write("one");
        file.close();
        file.close();
        file.closeInBackground();
        assertThat(file.droppedLines()).isZero();
    }

    // --------------------------------------------------------------------- rotation

    /**
     * The bug this replaces: a fresh process reset the part number to 1 unconditionally, so a server
     * restarting after a day that had already filled three parts wrote its first startup line to the
     * end of the full part 1, its second to part 2, and so on — a startup sequence scattered one line
     * at a time across the tails of every part of the day.
     */
    /**
     * The part cap is injected rather than real.
     *
     * <p>This test used to allocate two sparse 32 MiB files to prove the same thing, which made it
     * depend on the disk having room and on sparse files behaving — and it failed once, in a full
     * build, for reasons nothing here could reproduce. A rotation test should exercise the
     * arithmetic, not the filesystem.
     */
    private static final long TINY_PART = 64L;

    @Test
    @DisplayName("a restart continues after a full part instead of writing into it")
    void resumesAfterAFullPart(@TempDir Path directory) throws IOException {
        Files.createDirectories(directory);
        Path part1 = todaysFile(directory);
        Path part2 = directory.resolve(part1.getFileName().toString().replace(".log", "-2.log"));
        Files.writeString(part1, "x".repeat((int) TINY_PART));
        Files.writeString(part2, "x".repeat((int) TINY_PART));

        try (LogFile file = new LogFile(directory, 7, ZoneId.systemDefault(), TINY_PART)) {
            file.write("the server started");
        }

        Path part3 = directory.resolve(part1.getFileName().toString().replace(".log", "-3.log"));
        assertThat(part3).exists();
        assertThat(Files.readString(part3)).contains("the server started");
        assertThat(Files.size(part1)).isEqualTo(TINY_PART);
        assertThat(Files.size(part2)).isEqualTo(TINY_PART);
    }

    @Test
    @DisplayName("a restart continues in a part that still has room")
    void resumesInAPartWithRoom(@TempDir Path directory) throws IOException {
        Files.createDirectories(directory);
        Path part1 = todaysFile(directory);
        Path part2 = directory.resolve(part1.getFileName().toString().replace(".log", "-2.log"));
        Files.writeString(part1, "x".repeat((int) TINY_PART));
        Files.writeString(part2, "room left here\n");

        try (LogFile file = new LogFile(directory, 7, ZoneId.systemDefault(), TINY_PART)) {
            file.write("the server started");
        }

        assertThat(Files.readAllLines(part2)).containsExactly("room left here", "the server started");
    }

    @Test
    @DisplayName("currentFile() names the file being written, from any thread")
    void publishesTheCurrentFile(@TempDir Path directory) throws Exception {
        Files.createDirectories(directory);
        Path part1 = todaysFile(directory);
        Files.writeString(part1, "x".repeat((int) TINY_PART));

        try (LogFile file = new LogFile(directory, 7, ZoneId.systemDefault(), TINY_PART)) {
            // Before anything is written the answer is where the first line would go.
            assertThat(file.currentFile()).isEqualTo(part1);
            file.write("rolled");
            Path expected = directory.resolve(
                    part1.getFileName().toString().replace(".log", "-2.log"));
            long deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline && !file.currentFile().equals(expected)) {
                Thread.sleep(20);
            }
            // Read from this thread, written by the writer thread: the value has to be published.
            assertThat(file.currentFile()).isEqualTo(expected);
        }
    }

}
