package de.raindancer.core.world.companion;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("posing a rig: parts on their parents, turned the right way up, scaled and facing")
class RigPoseTest {

    private static final Rig RIG = new Rig(16, List.of(
            new Rig.Part("body", "x:body", null, new float[]{0, 0, 0}, new float[]{0, 0, 0}, 1, null),
            new Rig.Part("wing", "x:wing", "body", new float[]{16, 0, 0}, new float[]{0, 0, 0}, 0.5f,
                    new Rig.Motion(Rig.Motion.Kind.WING, 1)),
            new Rig.Part("tip", "x:tip", "wing", new float[]{16, 0, 0}, new float[]{0, 0, 0}, 0.5f, null)));

    private static final Rig STILL = new Rig(16, List.of(
            new Rig.Part("body", "x:body", null, new float[]{0, 0, 0}, new float[]{0, 0, 0}, 1, null),
            new Rig.Part("wing", "x:wing", "body", new float[]{16, 0, 0}, new float[]{0, 0, 0}, 0.5f, null),
            new Rig.Part("tip", "x:tip", "wing", new float[]{16, 0, 0}, new float[]{0, 0, 0}, 0.5f, null)));

    private static Vector3f origin(Matrix4f matrix) {
        return matrix.transformPosition(new Vector3f());
    }

    @Test
    @DisplayName("a child sits at its pivot on its parent, in blocks, turned over like the game's renderer")
    void chain() {
        List<Matrix4f> pose = RigPose.pose(STILL, 1, 0, new RigPose.State(0, false, false), false);
        // x is mirrored by the turn-over: 16 pixels to the model's left is a block to the world's -x
        assertThat(origin(pose.get(1)).x).isEqualTo(-1f, within(1e-5f));
        assertThat(origin(pose.get(2)).x).isEqualTo(-2f, within(1e-5f));
    }

    @Test
    @DisplayName("scale shrinks the whole rig; the offset lifts it")
    void scaledAndLifted() {
        List<Matrix4f> pose = RigPose.pose(STILL, 0.25f, 0, new RigPose.State(0, false, false), false, 0.5f);
        assertThat(origin(pose.get(2)).x).isEqualTo(-0.5f, within(1e-5f));
        assertThat(origin(pose.get(0)).y).isEqualTo(0.5f, within(1e-5f));
    }

    @Test
    @DisplayName("facing south (yaw 0) keeps +z; facing west (yaw 90) turns the rig's +z to -x")
    void facing() {
        Rig forward = new Rig(16, List.of(new Rig.Part("nose", "x:n", null, new float[]{0, 0, 16},
                new float[]{0, 0, 0}, 1, null)));
        assertThat(origin(RigPose.pose(forward, 1, 0, new RigPose.State(0, false, false), false).get(0)).z)
                .isEqualTo(1f, within(1e-5f));
        assertThat(origin(RigPose.pose(forward, 1, 90, new RigPose.State(0, false, false), false).get(0)).x)
                .isEqualTo(-1f, within(1e-5f));
    }

    @Test
    @DisplayName("wings flap over time; the model scale is undone so each part shows at the rig's size")
    void flapping() {
        Matrix4f a = RigPose.pose(RIG, 1, 0, new RigPose.State(0, true, false), false).get(2);
        Matrix4f b = RigPose.pose(RIG, 1, 0, new RigPose.State(7, true, false), false).get(2);
        assertThat(origin(a).distance(origin(b))).isGreaterThan(0.05f);
        Vector3f unit = RigPose.pose(RIG, 1, 0, new RigPose.State(0, false, false), false).get(1)
                .transformDirection(new Vector3f(1, 0, 0));
        assertThat(unit.length()).isEqualTo(2f, within(1e-4f));
    }

    @Test
    @DisplayName("a neck is a chain: each segment starts ten pixels on from where the last one ended, the head at its end")
    void chains() {
        Rig neck = new Rig(16, List.of(
                new Rig.Part("n0", "x:n", null, new float[]{0, 0, 0}, new float[]{0, 0, 0}, 1,
                        new Rig.Motion(Rig.Motion.Kind.NECK, 1, 0)),
                new Rig.Part("n1", "x:n", null, new float[]{0, 0, 0}, new float[]{0, 0, 0}, 1,
                        new Rig.Motion(Rig.Motion.Kind.NECK, 1, 1)),
                new Rig.Part("head", "x:h", null, new float[]{0, 0, 0}, new float[]{0, 0, 0}, 1,
                        new Rig.Motion(Rig.Motion.Kind.HEAD, 1, 0))));
        List<Matrix4f> pose = RigPose.pose(neck, 1, 0, new RigPose.State(0, false, false), false);
        assertThat(origin(pose.get(0)).distance(origin(pose.get(1)))).isEqualTo(10 / 16f, within(1e-4f));
        assertThat(origin(pose.get(1)).distance(origin(pose.get(2)))).isEqualTo(10 / 16f, within(1e-4f));
        // the neck runs towards -z, the head ends up in front
        assertThat(origin(pose.get(2)).z).isLessThan(origin(pose.get(0)).z);
    }

    @Test
    @DisplayName("a part without a model is a joint: its children hang on it")
    void joints() {
        Rig rig = new Rig(16, List.of(
                new Rig.Part("root", null, null, new float[]{0, 0, -16}, new float[]{0, 0, 0}, 1, null),
                new Rig.Part("body", "x:b", "root", new float[]{0, 0, 0}, new float[]{0, 0, 0}, 1, null)));
        assertThat(rig.parts().get(0).shown()).isFalse();
        assertThat(origin(RigPose.pose(rig, 1, 0, new RigPose.State(0, false, false), false).get(1)).z)
                .isEqualTo(-1f, within(1e-5f));
    }
}
