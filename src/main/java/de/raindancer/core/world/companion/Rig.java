package de.raindancer.core.world.companion;

import java.util.List;

/**
 * A companion drawn as a model of item displays instead of its mob — for a mob the client will not shrink (the ender
 * dragon, Mojang's MC-268544) or one a server made up. Each part shows an item model (a resource pack's), hangs on its
 * parent at a pivot, may be turned, and may move: wings flap, a jaw snaps, a neck and a tail wave as chains of
 * segments the way the game's own models do. A part without a model is a joint only. Units are entity-model pixels
 * with y pointing down, as in the game's model code; the rig is turned over like the game's renderer does.
 *
 * @param height the rig's height in pixels at scale 1 — what the companion's height is measured against
 * @param offset blocks the whole rig is moved by before it is turned over (the game's renderer's own translate)
 */
public record Rig(float height, List<Part> parts, float[] offset) {

    public Rig {
        height = Math.max(1, height);
        parts = List.copyOf(parts);
        offset = offset == null ? new float[3] : offset.clone();
    }

    public Rig(float height, List<Part> parts) {
        this(height, parts, new float[3]);
    }

    /**
     * @param model      the item model key a display shows, like {@code rainspets:ender_dragon_body}; null for a joint
     * @param parent     the part it hangs on, or null for the rig's root
     * @param pivot      where it hangs, from its parent's pivot, in pixels (a chain's first segment: where it starts)
     * @param rotation   its resting turn, x, y, z in radians (applied z, y, x like the game's)
     * @param modelScale how many model units the item model has per pixel (an item model must fit −16..32)
     */
    public record Part(String name, String model, String parent, float[] pivot, float[] rotation, float modelScale,
                       Motion motion) {

        public Part {
            pivot = pivot == null ? new float[3] : pivot.clone();
            rotation = rotation == null ? new float[3] : rotation.clone();
            modelScale = modelScale <= 0 ? 1 : modelScale;
        }

        public boolean shown() {
            return model != null && !model.isBlank();
        }
    }

    /**
     * How a part moves.
     *
     * @param side  1 for the left, −1 for the right; for a leg the sign its bend follows
     * @param index a chain segment's place in its chain, from 0
     */
    public record Motion(Kind kind, int side, int index) {

        public Motion(Kind kind, int side) {
            this(kind, side, 0);
        }

        public enum Kind {
            /** The whole body's bob with the wing beat. */
            ROOT,
            /** Flaps, faster in flight. */
            WING,
            /** Follows the wing's beat, further out. */
            WING_TIP,
            /** Opens with the beat, wide when it bites. */
            JAW,
            /** Bends a little further with the bob. */
            LEG,
            /** A neck segment: waves, and the next one starts where it ends. */
            NECK,
            /** Where the neck ends. */
            HEAD,
            /** A tail segment: turned round, waving more towards the tip. */
            TAIL,
            /** Sways side to side. */
            SWAY
        }
    }

    public int indexOf(String name) {
        for (int i = 0; i < parts.size(); i++) {
            if (parts.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }
}
