package com.nishiyu.enderframelib.core.paint.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.core.paint.RetainPlan;
import com.nishiyu.enderframelib.core.raster.Matrix2D;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** S6: {@link GpuSink} を 図形の口 / 層の口 / 再生の口 に分けた構造の固定と、既定の「受け取らない」の確認。 */
class GpuSinkSplitTest {
    private static Set<String> declared(Class<?> c) {
        Set<String> s = new TreeSet<>();
        for (Method m : c.getDeclaredMethods()) if (!m.isSynthetic()) s.add(m.getName());
        return s;
    }

    @Test
    void sinkIsComposedOfTheLayerAndReplayPorts() {
        assertTrue(GpuLayerSink.class.isAssignableFrom(GpuSink.class));
        assertTrue(GpuReplaySink.class.isAssignableFrom(GpuSink.class));
        assertEquals(2, GpuSink.class.getInterfaces().length);
    }

    @Test
    void eachPortDeclaresOnlyItsOwnConcern() {
        Set<String> sink = declared(GpuSink.class), layer = declared(GpuLayerSink.class), replay = declared(GpuReplaySink.class);
        for (String n : Set.of("beginGroup", "endGroup", "beginMask", "endMask", "snapshotTarget", "overwriteTarget",
                "planScrollLayer", "drawScrollLayer", "beginScrollLayer", "endScrollLayer")) {
            assertTrue(layer.contains(n), "層の口に無い: " + n);
            assertFalse(sink.contains(n) || replay.contains(n), "層の口の外にある: " + n);
        }
        for (String n : Set.of("spanReplay", "spanBegin", "spanEnd", "beginDiffPatch", "canPatchSpan", "patchSpanBegin",
                "patchSpanEnd", "abortDiffPatch", "endDiffPatch", "snapsScrollOffsets")) {
            assertTrue(replay.contains(n), "再生の口に無い: " + n);
            assertFalse(sink.contains(n) || layer.contains(n), "再生の口の外にある: " + n);
        }
        for (String n : Set.of("fillRoundRect", "strokeRoundBorder", "drawGlyph", "drawImage", "drawTexture", "drawScene3D")) {
            assertTrue(sink.contains(n), "図形の口に無い: " + n);
        }
        assertTrue(sink.size() <= 20, "GpuSink が再び肥大した: " + sink);
    }

    @Test
    void portsDefaultToDecliningEverything() {
        GpuSink s = new GpuSink() {
            public boolean fillRoundRect(Matrix2D p, float a, float b, float c, float d, float[] rx, float[] ry, int argb, GpuClip[] g, float o) { return false; }
            public boolean strokeRoundBorder(Matrix2D p, float a, float b, float c, float d, float[] rx, float[] ry,
                    float e, float f, float g, float h, float[] irx, float[] iry, boolean ie, int[] cs, float[] w, GpuClip[] gc, float o) { return false; }
        };
        assertFalse(s.beginGroup(0.5f));
        assertNull(s.snapshotTarget());
        assertEquals(RetainPlan.NONE, s.planScrollLayer(null));
        assertTrue(s.endScrollLayer(null));
        assertFalse(s.spanReplay(0, false));
        assertFalse(s.spanBegin(0));
        assertFalse(s.beginDiffPatch());
        assertFalse(s.snapsScrollOffsets());
        assertEquals(4, GpuSink.MAX_CLIPS);
    }
}
