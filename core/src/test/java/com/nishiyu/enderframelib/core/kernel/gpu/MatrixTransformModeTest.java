package com.nishiyu.enderframelib.core.kernel.gpu;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * 姿勢を TRS へ分解し直さず 2x3 行列のまま書く経路（{@link PropertyTrees#setTransformMatrix}）。
 * シェーダーはフラグを見て行列をそのまま読む（三角関数なし）。CPU の参照実装と一致し、TRS のスロットと混在できる。
 */
class MatrixTransformModeTest {

    private static PropertyTrees trees() {
        return new PropertyTrees(32, 4, 4, 32, new Milestone4KernelTest.FakeDevice(false));
    }

    @Test
    void matrixSlotEvaluatesToItsMatrix() {
        PropertyTrees t = trees();
        int s = t.addTransform(PropertyTrees.NONE, 0, 0, 1, 1, 0);
        t.setTransformMatrix(s, 0.5f, 1.25f, -2f, 0.75f, 7f, -9f);
        assertTrue(t.isMatrixTransform(s));
        float[] m = new float[6], scratch = new float[17];
        GpuAnimationReference.worldMatrix(t, s, 0f, m, scratch);
        assertArrayEquals(new float[]{0.5f, 1.25f, -2f, 0.75f, 7f, -9f}, m, 1e-6f);
    }

    @Test
    void matrixAndTrsSlotsComposeThroughTheParentChain() {
        PropertyTrees t = trees();
        int root = t.addTransform(PropertyTrees.NONE, 10, 20, 2, 3, 0.3f);
        int child = t.addTransform(root, 0, 0, 1, 1, 0);
        t.setTransformMatrix(child, 1f, 0.5f, -0.5f, 1f, 4f, 5f);
        float[] expectedRoot = new float[6];
        GpuAnimationReference.trsToMatrix(10, 20, 2, 3, 0.3f, expectedRoot);
        float[] local = {1f, 0.5f, -0.5f, 1f, 4f, 5f};
        float[] expected = new float[6];
        GpuAnimationReference.multiply(expectedRoot, local, expected);
        float[] m = new float[6], scratch = new float[17];
        GpuAnimationReference.worldMatrix(t, child, 0f, m, scratch);
        assertArrayEquals(expected, m, 1e-4f);
    }

    @Test
    void settingTrsAgainLeavesMatrixMode() {
        PropertyTrees t = trees();
        int s = t.addTransform(PropertyTrees.NONE, 0, 0, 1, 1, 0);
        t.setTransformMatrix(s, 2f, 0f, 0f, 2f, 1f, 1f);
        t.setTransformTarget(s, 3, 4, 1, 1, 0);
        assertFalse(t.isMatrixTransform(s));
        float[] m = new float[6], scratch = new float[17];
        GpuAnimationReference.worldMatrix(t, s, 0f, m, scratch);
        assertArrayEquals(new float[]{1f, 0f, 0f, 1f, 3f, 4f}, m, 1e-6f);
    }

    @Test
    void matrixSlotCannotBeAnimated() {
        PropertyTrees t = trees();
        int s = t.addTransform(PropertyTrees.NONE, 0, 0, 1, 1, 0);
        t.setTransformMatrix(s, 1f, 0f, 0f, 1f, 0f, 0f);
        assertThrows(IllegalStateException.class,
                () -> t.startTransformAnimation(s, 0, 0, 1, 1, 0, 0f, 100f, PropertyTrees.EASE_LINEAR));
    }

    @Test
    void shaderReadsTheMatrixDirectly() {
        assertTrue(GpuAnimation.GLSL.contains("trI(slot, 5) == 1"), "行列モードの分岐");
        assertTrue(GpuAnimation.GLSL.contains("mat3(trF(slot, 6), trF(slot, 7), 0.0"), "行列を直接読む");
    }
}
