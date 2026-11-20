package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/** 差分ファズへの検証モードの併走（C0 ③）: 検証が赤にした手を「鍵の漏れ[記憶の種類]」として報告する。 */
class MutationFuzzKeyLeakTest {
    @Test
    void verifierMessagesAreClassifiedByTheMemoThatLeaked() {
        assertEquals("測定の記憶", MutationFuzz.leakKind("測定の記憶（reuse）が古い: 箱 a 記憶=…"));
        assertEquals("箱の項目の記憶", MutationFuzz.leakKind("箱の項目の記憶が古い: box=div#a item[0].id …"));
        assertEquals("項目の再生", MutationFuzz.leakKind("項目の再生が古い: 項目 3 図形の slot 7"));
        assertEquals("姿勢の使い回し", MutationFuzz.leakKind("姿勢の使い回しが古い: 項目 x …"));
        assertEquals("float メモ", MutationFuzz.leakKind("float メモが古い: 箱 a メモ=true"));
        assertNull(MutationFuzz.leakKind("seed 1 step 2: 項目の id が重複: a"), "検証以外の失敗は鍵の漏れにしない");
        assertNull(MutationFuzz.leakKind(null));
    }

    /** 全箱の測定の記憶の出力を壊す（入力の変化を取りこぼした記憶の状態）。{@code measureMemo.outH} を反射で書く。 */
    private static void corruptMeasureMemos(LayoutBox b) throws ReflectiveOperationException {
        Field memoField = LayoutBox.class.getDeclaredField("measureMemo");
        memoField.setAccessible(true);
        Object memo = memoField.get(b);
        Field outH = memo.getClass().getDeclaredField("outH");
        outH.setAccessible(true);
        outH.setFloat(memo, outH.getFloat(memo) + 50f);
        for (LayoutBox c : b.children) corruptMeasureMemos(c);
    }

    @Test
    void aStaleMemoIsReportedAsAKeyLeakNotAsAMismatch() {
        MutationFuzz.Doc doc = MutationFuzz.DOCS.get("layouts");
        boolean was = com.nishiyu.enderframelib.core.kernel.Shortcut.VERIFY_MEMO.on();
        com.nishiyu.enderframelib.core.kernel.Shortcut.VERIFY_MEMO.set(true);
        try {
            // 1 手目の全面との比較のあとで、記憶を古い状態にする。次の手の再利用が検証に当たる
            var saved = MutationScenario.STEP_HOOK;
            boolean[] done = {false};
            MutationFuzz.TAMPER = (step, view) -> {
                if (step == 0 && !done[0]) {
                    done[0] = true;
                    try { corruptMeasureMemos(view.ui().layoutRoot()); } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
                }
            };
            String r;
            try {
                r = MutationFuzz.firstMismatch(doc, 1L, 6);
            } finally {
                MutationFuzz.TAMPER = null;
                MutationScenario.STEP_HOOK = saved;
            }
            assertTrue(done[0], "壊す手が実行された");
            assertNotNull(r, "壊した記憶は報告される");
            assertTrue(r.contains("鍵の漏れ[測定の記憶]"), "検証の赤は鍵の漏れとして出る（増分と全面の食い違いではなく）: " + r);
        } finally {
            com.nishiyu.enderframelib.core.kernel.Shortcut.VERIFY_MEMO.set(was);
        }
    }

    @Test
    void reportLineCarriesTheMarkerThatDistinguishesItFromAMismatch() {
        MutationFuzz.Doc doc = MutationFuzz.DOCS.get("nested");
        String line = MutationFuzz.keyLeak(doc, 42L, 7, "測定の記憶", "測定の記憶（reuse）が古い: 箱 x");
        assertTrue(line.startsWith("nested/42 手 7 鍵の漏れ[測定の記憶]: "), line);
    }
}
