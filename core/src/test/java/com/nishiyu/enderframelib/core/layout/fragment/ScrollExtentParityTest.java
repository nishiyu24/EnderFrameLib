package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.layout.FragmentProjection;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * M2b-1: 到達範囲（{@link ScrollableOverflow}）を旧経路の {@code scrollWidth} / {@code scrollHeight} と比べる。幾何の一致の掃引
 * （{@link GeometrySweepTest}）で全ての箱が旧経路と一致した文書のブロックの要素について、新経路の到達範囲が旧経路を超えないこと。
 * 旧経路は行末の畳まれる空白（後ろに空白を含む語の断片・空白だけの文字の断片の ascent + descent）も到達範囲に数える逸脱を持ち
 * （CSS Text 3 §4.1.3 は行末の畳まれる空白を取り除く）、差はその分だけ旧経路が大きい。縦書きの文書（M2b-5）は比べない。
 * 値そのものは {@link ScrollableOverflowTest} が手で求めた数で固定する。
 */
class ScrollExtentParityTest {
    private static final float TOL = 1f / 64f + 1e-4f;
    private static final int W = 800, H = 600;

    @Test
    void scrollExtentsNeverExceedLegacyWhereTheGeometryMatches() throws IOException {
        Map<String, String> docs = GeometrySweepTest.documents();
        java.util.Set<String> matching = GeometrySweepTest.matchingList();
        List<String> bad = new ArrayList<>();
        int compared = 0;
        for (var e : docs.entrySet()) {
            if (!matching.contains(e.getKey()) || e.getKey().contains("vertical")) continue;
            Map<String, float[]> v3 = new LinkedHashMap<>(), old = new LinkedHashMap<>();
            SpikeView sv = new SpikeView(e.getValue(), W, H);
            collect(FragmentProjection.project(sv.frame(), sv.layout.session()), v3);
            try (StandaloneView lv = new StandaloneView(e.getValue(), W, H, FontResolver.FALLBACK, com.nishiyu.enderframelib.common.EnginePath.LEGACY)) {
                lv.frame(1000, new SpikeView.NullBackend(), true);
                LayoutBox root = lv.ui().layoutRoot();
                if (root != null) collect(root, old);
            }
            for (var b : v3.entrySet()) {
                float[] o = old.get(b.getKey());
                if (o == null) continue;
                compared++;
                float[] n = b.getValue();
                if (n[0] > o[0] + TOL || n[1] > o[1] + TOL) {
                    bad.add(String.format("%s %s legacy=%.2fx%.2f v3=%.2fx%.2f", e.getKey(), b.getKey(), o[0], o[1], n[0], n[1]));
                }
            }
        }
        System.out.println("SCROLL compared=" + compared + " mismatched=" + bad.size());
        bad.stream().limit(40).forEach(l -> System.out.println("  " + l));
        assertEquals(List.of(), bad);
    }

    /** ブロック水準の要素の箱の {scrollWidth, scrollHeight}（インラインの箱・文字の箱は除く）。 */
    private static void collect(LayoutBox b, Map<String, float[]> out) {
        if (b.node != null && b.node.nodeType == NodeType.ELEMENT && !b.node.isPseudoNode && b.inlineFragments.isEmpty()) {
            String k = GeometrySweepTest.path(b.node);
            if (k != null) out.putIfAbsent(k, new float[]{b.scrollWidth(), b.scrollHeight()});
        }
        for (LayoutBox c : b.children) collect(c, out);
    }
}
