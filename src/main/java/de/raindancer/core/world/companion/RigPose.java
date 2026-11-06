package de.raindancer.core.world.companion;

import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Where every part of a {@link Rig} is right now, as the matrix its display shows: the rig's facing, its scale, the
 * game's turn-over (−1, −1, 1), the chain of parents and each part's motion — for the ender dragon exactly the game's
 * own {@code EnderDragonModel.setupAnim} in steady flight — and the item model's own scale undone. Pure arithmetic;
 * the displays are Core's to move.
 */
public final class RigPose {

    /**
     * @param ticks  a running clock, for the beats
     * @param flying flaps hard; otherwise slow
     * @param biting the jaw wide open
     */
    public record State(long ticks, boolean flying, boolean biting) {
    }

    private RigPose() {
    }

    public static List<Matrix4f> pose(Rig rig, float scale, float yawDegrees, State state, boolean mirrored) {
        return pose(rig, scale, yawDegrees, state, mirrored, 0);
    }

    /** The game's flap: a turn of the wing beat, in radians. */
    static double flap(State state) {
        return state.ticks() * (state.flying() ? 0.05 : 0.025) * Math.PI * 2;
    }

    /** The game's bounce: how far the body bobs with the beat, 0 to about 0.4. */
    static float bounce(double flap) {
        float bounce = (float) Math.sin(flap - 1) + 1;
        return (bounce * bounce + bounce * 2) * 0.05f;
    }

    /** @param lift blocks the whole rig is raised by */
    public static List<Matrix4f> pose(Rig rig, float scale, float yawDegrees, State state, boolean mirrored,
                                      float lift) {
        Matrix4f root = new Matrix4f()
                .translate(0, lift, 0)
                .rotateY((float) Math.toRadians(-yawDegrees))
                .scale(scale)
                .translate(rig.offset()[0], 0, rig.offset()[2])
                .scale(-1, -1, 1)
                .translate(0, rig.offset()[1], 0);
        double flap = flap(state);
        float bounce = bounce(flap);
        List<Matrix4f> joints = new ArrayList<>(rig.parts().size());
        List<Matrix4f> shown = new ArrayList<>(rig.parts().size());
        float[] neck = null;
        float[] tail = null;
        float tailWave = 0;
        for (Rig.Part part : rig.parts()) {
            int parent = part.parent() == null ? -1 : rig.indexOf(part.parent());
            Matrix4f joint = new Matrix4f(parent >= 0 && parent < joints.size() ? joints.get(parent) : root);
            float[] at = part.pivot().clone();
            float[] turn = part.rotation().clone();
            Rig.Motion motion = part.motion();
            if (motion != null) {
                int side = motion.side() == 0 ? 1 : motion.side();
                switch (motion.kind()) {
                    case ROOT -> {
                        at[1] += (bounce - 2) * 16;
                        turn[0] += (float) Math.toRadians(bounce * 2);
                    }
                    case WING -> {
                        turn[0] += (float) (0.125 - Math.cos(flap) * 0.2);
                        turn[1] += -0.25f * side;
                        turn[2] += (float) (-(Math.sin(flap) + 0.125) * 0.8 * side);
                    }
                    case WING_TIP -> turn[2] += (float) ((Math.sin(flap + 2) + 0.5) * 0.75 * side);
                    case JAW -> turn[0] += state.biting() ? 0.75f : (float) ((Math.sin(flap) + 1) * 0.2);
                    case LEG -> turn[0] += bounce * 0.1f * side;
                    case NECK -> {
                        if (neck == null) {
                            neck = at.clone();
                        }
                        turn[0] += (float) (Math.cos(motion.index() * 0.45 + flap) * 0.15);
                        at = neck.clone();
                        neck[1] += (float) (Math.sin(turn[0]) * 10);
                        neck[2] -= (float) (Math.cos(turn[1]) * Math.cos(turn[0]) * 10);
                        neck[0] -= (float) (Math.sin(turn[1]) * Math.cos(turn[0]) * 10);
                    }
                    case HEAD -> {
                        if (neck != null) {
                            at = neck.clone();
                        }
                    }
                    case TAIL -> {
                        if (tail == null) {
                            tail = at.clone();
                        }
                        tailWave += (float) (Math.sin(motion.index() * 0.45 + flap) * 0.05);
                        turn[0] += tailWave;
                        turn[1] += (float) Math.PI;
                        at = tail.clone();
                        tail[1] += (float) (Math.sin(turn[0]) * 10);
                        tail[2] -= (float) (Math.cos(turn[1]) * Math.cos(turn[0]) * 10);
                        tail[0] -= (float) (Math.sin(turn[1]) * Math.cos(turn[0]) * 10);
                    }
                    case SWAY -> turn[1] += (float) (Math.sin(flap * 0.5) * 0.12);
                }
            }
            joint.translate(at[0] / 16f, at[1] / 16f, at[2] / 16f).rotateZYX(turn[2], turn[1], turn[0]);
            joints.add(joint);
            shown.add(new Matrix4f(joint).scale(1f / part.modelScale()));
        }
        return shown;
    }
}
