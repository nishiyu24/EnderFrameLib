package com.nishiyu.enderframelib.core.paint.software;

import com.nishiyu.enderframelib.common.StandaloneView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ゲーム内の再描画スキップ（CoreGpuSurface）が頼る「動いているアニメがあるか」の判定。 */
class ActiveAnimationTest {

    private static final String STATIC = "<html><body style='margin:0'>"
            + "<div style='width:40px;height:40px;background:#f00'></div></body></html>";
    private static final String LOOP = "<html><head><style>@keyframes k{from{opacity:0}to{opacity:1}}"
            + "#b{width:40px;height:40px;background:#f00;animation:k 500ms infinite}</style></head>"
            + "<body style='margin:0'><div id='b'></div></body></html>";
    private static final String HOVER = "<html><head><style>"
            + "#b{width:40px;height:40px;background:#000;transition:background-color 200ms linear}"
            + "#b:hover{background:#fff}</style></head><body style='margin:0'><div id='b'></div></body></html>";

    @Test
    void staticDocumentIsSettled() {
        StandaloneView v = new StandaloneView(STATIC, 60, 60);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(60, 60, 1f)) {
            v.frame(1000, cpu, true);
            assertFalse(cpu.hasActiveAnimation(v.items(), 1000));
            assertFalse(cpu.hasActiveAnimation(v.items(), 60_000));
        }
    }

    @Test
    void infiniteAnimationNeverSettles() {
        StandaloneView v = new StandaloneView(LOOP, 60, 60);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(60, 60, 1f)) {
            v.frame(1000, cpu, true);
            assertTrue(cpu.hasActiveAnimation(v.items(), 1000));
            assertTrue(cpu.hasActiveAnimation(v.items(), 600_000));
        }
    }

    @Test
    void transitionIsActiveUntilItEnds() {
        StandaloneView v = new StandaloneView(HOVER, 60, 60);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(60, 60, 1f)) {
            v.frame(1000, cpu, true);
            v.mouseMove(10, 10, cpu.sessionId());
            v.frame(1016, cpu, false);
            assertTrue(cpu.hasActiveAnimation(v.items(), 1016));
            assertFalse(cpu.hasActiveAnimation(v.items(), 2000));
        }
    }
}
