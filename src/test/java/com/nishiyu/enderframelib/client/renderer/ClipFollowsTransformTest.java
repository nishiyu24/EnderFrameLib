package com.nishiyu.enderframelib.client.renderer;

import org.joml.Matrix4f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 動く要素（transform / animation）の箱で切るクリップの座標変換。
 * <p>
 * animation_test.html の Easing Curves Parity では、ランナー（円）が動かずに元の位置で切り取られるだけになっていた。
 * ランナーのグラデーション層が、動かない祖先のクリップと同じく「画面に留める」変換を受けていたため、
 * 動いた層が元の位置の円で切られていた。
 */
public class ClipFollowsTransformTest {

    private static final float[] CIRCLE = {29f, 536f, 47f, 554f, 9f, 9f, 9f, 9f};

    private static float[] fix(Matrix4f base, Matrix4f eff) {
        return DisplayItemDrawer.clipIntoLocalSpace(base, eff, CIRCLE[0], CIRCLE[1], CIRCLE[2], CIRCLE[3],
                CIRCLE[4], CIRCLE[5], CIRCLE[6], CIRCLE[7]);
    }

    @Test
    @DisplayName("動かない祖先のクリップは、要素が動いた分だけ逆にずらして画面に留まる")
    void staticClipStaysOnScreen() {
        Matrix4f base = new Matrix4f();
        Matrix4f eff = new Matrix4f().translate(60f, 0f, 0f);
        float[] local = fix(base, eff);
        assertNotNull(local);
        assertEquals(CIRCLE[0] - 60f, local[0], 1e-3f);
        assertEquals(CIRCLE[2] - 60f, local[2], 1e-3f);
    }

    @Test
    @DisplayName("持ち主（動く要素）の姿勢を基準にすれば、クリップは要素と一緒に動く（ずらさない）")
    void ownerClipFollowsOwner() {
        Matrix4f ownerPose = new Matrix4f().translate(60f, 0f, 0f);
        // 子の姿勢 = 持ち主の姿勢（子自身は動かない）
        assertNull(fix(ownerPose, new Matrix4f(ownerPose)), "ずらさず、クリップはそのまま（要素と一緒に動く）");
    }

    @Test
    @DisplayName("持ち主が動いた後に子が更に動くときは、子の分だけを戻す")
    void ownerClipOnlyUndoesChildOwnTransform() {
        Matrix4f ownerPose = new Matrix4f().translate(60f, 0f, 0f);
        Matrix4f childPose = new Matrix4f(ownerPose).translate(5f, 0f, 0f);
        float[] local = fix(ownerPose, childPose);
        assertNotNull(local);
        assertEquals(CIRCLE[0] - 5f, local[0], 1e-3f);
    }
}
