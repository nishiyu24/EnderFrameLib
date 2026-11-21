package com.nishiyu.enderframelib.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * L5 の描画パイプラインの依存の向きのラチェット（ENGINE_REARCHITECTURE_PLAN S6）。
 * 共有の型（{@code paint.model}・{@code paint.resource}・{@code paint.port}・{@code paint.emit}）は描画実装（{@code paint.gpu}・{@code paint.software}）を知らず、
 * GPU 実装と CPU 実装は互いのパッケージを参照しない（つなぎは {@code port} の口と {@code resource} の供給元だけ）。
 */
class PaintPipelineDirectionRatchetTest {
    private static final String GPU = "core.paint.gpu";
    private static final String SOFTWARE = "core.paint.software";
    private static final Set<String> SHARED = Set.of("core.paint.model", "core.paint.port", "core.paint.resource", "core.paint.emit");

    private static Set<String> violations(Set<String> from, Set<String> forbidden) throws IOException {
        Map<String, Set<String>> g = PackageCycleRatchetTest.graph();
        Set<String> bad = new TreeSet<>();
        for (var e : g.entrySet()) {
            if (!from.contains(e.getKey())) continue;
            for (String ref : e.getValue()) if (forbidden.contains(ref)) bad.add(e.getKey() + " -> " + ref);
        }
        return bad;
    }

    @Test
    void sharedPackagesDoNotKnowTheBackends() throws IOException {
        assertEquals(Set.of(), violations(SHARED, Set.of(GPU, SOFTWARE)),
                "共有の型の置き場が GPU/CPU の描画実装を参照している（共有するものは paint.model / port / resource に置く）");
    }

    @Test
    void gpuAndSoftwareDoNotReferenceEachOther() throws IOException {
        assertEquals(Set.of(), violations(Set.of(GPU), Set.of(SOFTWARE)), "paint.gpu が paint.software を参照している");
        assertEquals(Set.of(), violations(Set.of(SOFTWARE), Set.of(GPU)), "paint.software が paint.gpu を参照している");
    }

    @Test
    void thePackagesExistAndAreScanned() throws IOException {
        Map<String, Set<String>> g = PackageCycleRatchetTest.graph();
        for (String p : Set.of(GPU, SOFTWARE, "core.paint.port", "core.paint.resource", "core.paint.emit")) {
            assertTrue(g.containsKey(p), p + " がグラフに無い（パッケージ名が変わったらこのテストも直す。空振りの合格を防ぐ）");
        }
    }
}
