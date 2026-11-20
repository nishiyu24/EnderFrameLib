package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nishiyu.enderframelib.core.paint.PaintSceneDriver.Group;
import com.nishiyu.enderframelib.core.paint.PaintSceneDriver.Resolved;
import com.nishiyu.enderframelib.core.raster.Matrix2D;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 姿勢の使い回しの検証（C0 ②）の感度: 姿勢・不透明度・グループが違えば赤、リスト位置（anchor）だけの違いは許す。 */
class PoseVerifierTest {
    private static final Matrix2D M = new Matrix2D(1, 0, 0, 1, 10, 20);

    private static Resolved r(Matrix2D pose, float opacity, List<Group> groups) {
        return new Resolved(pose, opacity, 1f, groups, null);
    }

    private static Group g(int anchor, float opacity) {
        return new Group("g", opacity, anchor, null, new double[]{1, 0, 0, 1}, null, M);
    }

    @Test
    void sameStateIsAcceptedEvenIfTheGroupAnchorMoved() {
        assertDoesNotThrow(() -> PoseVerifier.check("e", r(M, 0.5f, List.of(g(3, 0.5f))), r(M, 0.5f, List.of(g(7, 0.5f)))));
    }

    @Test
    void differentPoseOpacityOrGroupsAreCaught() {
        Resolved base = r(M, 0.5f, List.of(g(3, 0.5f)));
        assertThrows(AssertionError.class, () -> PoseVerifier.check("e", base, r(new Matrix2D(1, 0, 0, 1, 11, 20), 0.5f, List.of(g(3, 0.5f)))));
        assertThrows(AssertionError.class, () -> PoseVerifier.check("e", base, r(M, 0.6f, List.of(g(3, 0.5f)))));
        assertThrows(AssertionError.class, () -> PoseVerifier.check("e", base, r(M, 0.5f, List.of())));
        assertThrows(AssertionError.class, () -> PoseVerifier.check("e", base, r(M, 0.5f, List.of(g(3, 0.4f)))));
    }
}
