package com.nishiyu.enderframelib.core.layout.fragment;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2a の Go/No-Go の数値（計画書 §3.3 B）をゲートとして固定する。詳しい内訳と旧経路との交互 A/B は {@code ./gradlew :core:m2aSpike}。
 */
class M2aSpikeGateTest {

    /** 判定基準 4: レイアウト層増分（BoxNode 木 + 断片木）が large-list-2000 ≤ 1.5 MB・varied-list-2000 ≤ 4.0 MB。 */
    @Test
    @Tag("slow")
    void layoutLayerHeapIsWithinBudget() {
        double[] large = M2aSpikeMain.heapOf(SpikeView.largeList(2000));
        double[] varied = M2aSpikeMain.heapOf(SpikeView.variedList(2000));
        String msg = String.format(Locale.ROOT, "large: BoxNode木 %.2f + 断片木 %.2f / varied: BoxNode木 %.2f + 断片木 %.2f",
                large[3], large[4], varied[3], varied[4]);
        System.out.println("M2a heap " + msg);
        assertTrue(large[3] + large[4] <= 1.5, msg);
        assertTrue(varied[3] + varied[4] <= 4.0, msg);
    }

    /** 判定基準 1: large-list-8000 の 1 件挿入・削除 ≤ 1.0 ms、500 行 ≤ 0.15 ms（v3 の DOM 変更 + コンパイル + 整形の中央値）。 */
    @Test
    @Tag("timing")
    void singleInsertDeleteIsWithinBudget() {
        for (String anchor : new String[]{"item-1", "item-4000", null}) {
            double ms = M2aSpikeMain.v3Child(SpikeView.largeList(8000), anchor, 60, 200)[0];
            assertTrue(ms <= 1.0, "large-list-8000 " + anchor + ": " + ms + " ms");
        }
        for (String anchor : new String[]{"item-1", "item-250", null}) {
            double ms = M2aSpikeMain.v3Child(SpikeView.largeList(500), anchor, 60, 200)[0];
            assertTrue(ms <= 0.15, "large-list-500 " + anchor + ": " + ms + " ms");
        }
    }
}
