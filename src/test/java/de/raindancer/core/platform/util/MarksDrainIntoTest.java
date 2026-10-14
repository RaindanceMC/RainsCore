package de.raindancer.core.platform.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MarksDrainIntoTest {

    @Test
    @DisplayName("everything taken is held, and nothing is held that was not taken or still marked")
    void takesIntoHolding() {
        Set<String> marks = ConcurrentHashMap.newKeySet();
        Set<String> holding = ConcurrentHashMap.newKeySet();
        marks.addAll(Set.of("a", "b", "c"));
        Set<String> taken = Marks.drainInto(marks, holding);
        assertThat(taken).containsExactlyInAnyOrder("a", "b", "c");
        assertThat(marks).isEmpty();
        assertThat(holding).containsExactlyInAnyOrder("a", "b", "c");
    }

    @Test
    @DisplayName("a watcher never sees a mark in neither set while it moves")
    void neverInNeither() throws Exception {
        for (int round = 0; round < 200; round++) {
            Set<String> marks = ConcurrentHashMap.newKeySet();
            Set<String> holding = ConcurrentHashMap.newKeySet();
            marks.add("ban");
            AtomicBoolean done = new AtomicBoolean();
            AtomicInteger gaps = new AtomicInteger();
            Thread watcher = new Thread(() -> {
                while (!done.get()) {
                    if (!marks.contains("ban") && !holding.contains("ban")) {
                        gaps.incrementAndGet();
                    }
                }
            });
            watcher.start();
            Marks.drainInto(marks, holding);
            done.set(true);
            watcher.join();
            assertThat(gaps).hasValue(0);
        }
    }

    @Test
    @DisplayName("null marks take nothing")
    void nullMarks() {
        assertThat(Marks.drainInto(null, ConcurrentHashMap.newKeySet())).isEmpty();
    }
}
