package de.raindancer.core.world.build;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Turning and mirroring a box of blocks, the way a structure block does it. */
class OrientationTest {

    @Test
    @DisplayName("unturned, a block stays where the schematic has it")
    void identity() {
        Orientation none = Orientation.NONE;
        assertThat(none.place(2, 1, 3, 5, 7)).containsExactly(2, 1, 3);
        assertThat(none.width(5, 7)).isEqualTo(5);
        assertThat(none.length(5, 7)).isEqualTo(7);
    }

    @Test
    @DisplayName("a quarter turn clockwise swaps width and length and sends north to east")
    void quarterTurn() {
        Orientation turned = Orientation.NONE.turned();
        // Width 5 along x, length 7 along z: turned, the footprint is 7 wide and 5 long.
        assertThat(turned.width(5, 7)).isEqualTo(7);
        assertThat(turned.length(5, 7)).isEqualTo(5);
        // The north-west corner (0,0) ends up north-east (6,0); the north-east (4,0) south-east (6,4).
        assertThat(turned.place(0, 0, 0, 5, 7)).containsExactly(6, 0, 0);
        assertThat(turned.place(4, 0, 0, 5, 7)).containsExactly(6, 0, 4);
        assertThat(turned.place(0, 2, 6, 5, 7)).containsExactly(0, 2, 0);
    }

    @Test
    @DisplayName("four quarter turns are no turn at all")
    void fullCircle() {
        Orientation turned = Orientation.NONE.turned().turned().turned().turned();
        assertThat(turned).isEqualTo(Orientation.NONE);
    }

    @Test
    @DisplayName("a half turn sends every corner to the opposite one")
    void halfTurn() {
        Orientation half = Orientation.NONE.turned().turned();
        assertThat(half.place(0, 0, 0, 5, 7)).containsExactly(4, 0, 6);
        assertThat(half.place(4, 3, 6, 5, 7)).containsExactly(0, 3, 0);
    }

    @Test
    @DisplayName("mirrored, east and west swap and nothing else moves")
    void mirror() {
        Orientation mirrored = Orientation.NONE.flipped();
        assertThat(mirrored.place(0, 1, 2, 5, 7)).containsExactly(4, 1, 2);
        assertThat(mirrored.width(5, 7)).isEqualTo(5);
        assertThat(mirrored.flipped()).isEqualTo(Orientation.NONE);
    }

    @Test
    @DisplayName("every position of the box lands inside the turned footprint, and no two on one spot")
    void staysInside() {
        int width = 4;
        int length = 3;
        for (int turns = 0; turns < 4; turns++) {
            for (boolean mirrored : new boolean[]{false, true}) {
                Orientation orientation = new Orientation(turns, mirrored);
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (int x = 0; x < width; x++) {
                    for (int z = 0; z < length; z++) {
                        int[] at = orientation.place(x, 0, z, width, length);
                        assertThat(at[0]).isBetween(0, orientation.width(width, length) - 1);
                        assertThat(at[2]).isBetween(0, orientation.length(width, length) - 1);
                        assertThat(seen.add(at[0] + "," + at[2])).as(orientation + " " + x + "," + z).isTrue();
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("turns outside 0..3 are folded back in")
    void normalised() {
        assertThat(new Orientation(5, false)).isEqualTo(new Orientation(1, false));
        assertThat(new Orientation(-1, true)).isEqualTo(new Orientation(3, true));
    }

    @Test
    @DisplayName("readable for a player and back from that text")
    void words() {
        Orientation orientation = new Orientation(3, true);
        assertThat(orientation.degrees()).isEqualTo(270);
        assertThat(Orientation.parse(orientation.toText())).contains(orientation);
        assertThat(Orientation.parse("nonsense")).isEmpty();
    }
}
