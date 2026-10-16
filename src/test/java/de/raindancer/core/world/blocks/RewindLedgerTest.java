package de.raindancer.core.world.blocks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("what a rewind puts back")
class RewindLedgerTest {

    @Test
    @DisplayName("only the first state of a block is kept — what it was before anything happened to it")
    void firstOnly() {
        Rewind.Ledger ledger = new Rewind.Ledger();
        ledger.remember("ctf", 1, 64, 1, "minecraft:stone");
        ledger.remember("ctf", 1, 64, 1, "minecraft:air");
        ledger.remember("ctf", 2, 64, 1, "minecraft:air");

        List<Rewind.Original> originals = ledger.drain();
        assertThat(originals).containsExactlyInAnyOrder(
                new Rewind.Original("ctf", 1, 64, 1, "minecraft:stone"),
                new Rewind.Original("ctf", 2, 64, 1, "minecraft:air"));
        assertThat(ledger.size()).as("drained").isZero();
    }

    @Test
    @DisplayName("the same spot in two worlds is two blocks")
    void worlds() {
        Rewind.Ledger ledger = new Rewind.Ledger();
        ledger.remember("a", 0, 0, 0, "minecraft:stone");
        ledger.remember("b", 0, 0, 0, "minecraft:dirt");
        assertThat(ledger.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("a huge round is still bounded: past the limit nothing more is remembered")
    void bounded() {
        Rewind.Ledger ledger = new Rewind.Ledger(3);
        for (int x = 0; x < 10; x++) {
            ledger.remember("ctf", x, 0, 0, "minecraft:air");
        }
        assertThat(ledger.size()).isEqualTo(3);
        assertThat(ledger.isFull()).isTrue();
    }

    @Test
    @DisplayName("after a drain the next change of a block counts as its first again")
    void afterDrain() {
        Rewind.Ledger ledger = new Rewind.Ledger();
        ledger.remember("ctf", 0, 0, 0, "minecraft:stone");
        ledger.drain();
        ledger.remember("ctf", 0, 0, 0, "minecraft:dirt");
        assertThat(ledger.drain()).containsExactly(new Rewind.Original("ctf", 0, 0, 0, "minecraft:dirt"));
    }
}
