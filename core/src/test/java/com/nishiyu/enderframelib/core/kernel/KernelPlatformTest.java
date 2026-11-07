package com.nishiyu.enderframelib.core.kernel;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.management.ManagementFactory;
import java.nio.ByteOrder;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorSpecies;
import org.junit.jupiter.api.Test;
import java.nio.IntBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.harfbuzz.HarfBuzz;

/**
 * エンジン再設計 Milestone 0 の検証: Vector API / Panama FFM / HarfBuzz が
 * ビルド（コンパイル・テスト実行）で使えること。
 */
class KernelPlatformTest {

    @Test
    void jvmFlagsAreApplied() {
        var args = ManagementFactory.getRuntimeMXBean().getInputArguments();
        assertTrue(args.contains("--enable-native-access=ALL-UNNAMED"), args.toString());
        assertTrue(args.contains("--enable-preview"), args.toString());
        assertTrue(args.stream().anyMatch(a -> a.contains("jdk.incubator.vector")), args.toString());
        assertTrue(ModuleLayer.boot().findModule("jdk.incubator.vector").isPresent());
    }

    @Test
    void vectorApiComputesHitTestLikeSimdLoop() {
        VectorSpecies<Float> sp = FloatVector.SPECIES_256;
        int n = 21; // 8 の倍数でない端数も含める
        float[] x = new float[n], y = new float[n], w = new float[n], h = new float[n];
        for (int i = 0; i < n; i++) { x[i] = i * 10f; y[i] = 0; w[i] = 10f; h[i] = 10f; }
        float px = 95f, py = 5f; // 要素 9（x=90..100）だけに含まれる
        int hit = -1, hits = 0;
        int i = 0;
        for (; i < sp.loopBound(n); i += sp.length()) {
            FloatVector vx = FloatVector.fromArray(sp, x, i), vw = FloatVector.fromArray(sp, w, i);
            FloatVector vy = FloatVector.fromArray(sp, y, i), vh = FloatVector.fromArray(sp, h, i);
            VectorMask<Float> m = vx.compare(jdk.incubator.vector.VectorOperators.LE, px)
                    .and(vx.add(vw).compare(jdk.incubator.vector.VectorOperators.GT, px))
                    .and(vy.compare(jdk.incubator.vector.VectorOperators.LE, py))
                    .and(vy.add(vh).compare(jdk.incubator.vector.VectorOperators.GT, py));
            if (m.anyTrue()) { hit = i + m.firstTrue(); hits += m.trueCount(); }
        }
        for (; i < n; i++) {
            if (x[i] <= px && x[i] + w[i] > px && y[i] <= py && y[i] + h[i] > py) { hit = i; hits++; }
        }
        assertEquals(9, hit);
        assertEquals(1, hits);
    }

    @Test
    void panamaAlignedOffHeapSegmentWorksWithVectors() {
        try (Arena arena = Arena.ofShared()) {
            MemorySegment seg = arena.allocate(64L * 4, 64);
            assertEquals(0, seg.address() % 64, "64 バイト境界に整列していること");
            for (int i = 0; i < 16; i++) seg.setAtIndex(ValueLayout.JAVA_FLOAT, i, i + 1f);
            VectorSpecies<Float> sp = FloatVector.SPECIES_256;
            FloatVector v = FloatVector.fromMemorySegment(sp, seg, 0, ByteOrder.nativeOrder());
            v.mul(2f).intoMemorySegment(seg, 0, ByteOrder.nativeOrder());
            assertEquals(2f, seg.getAtIndex(ValueLayout.JAVA_FLOAT, 0));
            assertEquals(2f * sp.length(), seg.getAtIndex(ValueLayout.JAVA_FLOAT, sp.length() - 1));
            assertEquals(sp.length() + 1f, seg.getAtIndex(ValueLayout.JAVA_FLOAT, sp.length())); // 範囲外は不変
        }
    }

    @Test
    void arenaCloseInvalidatesSegment() {
        Arena arena = Arena.ofShared();
        MemorySegment seg = arena.allocate(64, 64);
        arena.close();
        assertThrows(IllegalStateException.class, () -> seg.get(ValueLayout.JAVA_BYTE, 0));
    }

    @Test
    void harfBuzzNativeIsLoadableAndReportsVersion() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer major = stack.mallocInt(1), minor = stack.mallocInt(1), micro = stack.mallocInt(1);
            HarfBuzz.hb_version(major, minor, micro);
            assertTrue(major.get(0) >= 1, "HarfBuzz version " + major.get(0) + "." + minor.get(0) + "." + micro.get(0));
        }
        assertNotNull(HarfBuzz.hb_version_string());
    }
}
