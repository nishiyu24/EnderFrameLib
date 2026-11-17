package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 増分で作った表示リストが、同じ面・同じ時刻で全面の再レイアウトを強いた結果と一致すること（ENGINE_REARCHITECTURE_PLAN A6b）。
 * 乱数の変更列（{@link MutationScenario}）の毎手で比べる。float や位置指定の子の増減を局所パッチで扱うと、兄弟の行の折り返し・描画順が
 * ずれる不具合（layouts 文書の種 2 / 6 / 7 / 8）の回帰テスト。
 */
class IncrementalVsFullTest {

    @AfterEach
    void clear() {
        MutationScenario.STEP_HOOK = null;
    }

    private static List<String> sigs(StandaloneView v) {
        List<String> out = new ArrayList<>();
        for (var it : v.items()) out.add(MutationScenario.sig(it));
        return out;
    }

    /** 毎手、増分の結果と全面の結果を比べて、食い違った手の説明を返す（無ければ空）。{@code strictOrder = false} は並び順を無視する。 */
    private static List<String> mismatches(String name, String html, String[] classes, String[] styles, int seeds, int steps, long seedMul, boolean strictOrder) {
        List<String> bad = new ArrayList<>();
        for (long seed = 1; seed <= seeds; seed++) {
            final long sd = seed;
            boolean[] failed = {false};
            MutationScenario.STEP_HOOK = (step, view) -> {
                if (failed[0]) return;
                List<String> live = sigs(view);
                view.ui().markInvalidation(UiView.InvalidationLevel.RELAYOUT);
                try (var be = new MutationScenario.NoDraw()) {
                    var f = StandaloneView.class.getDeclaredField("lastNow"); // 同じ時刻（時間が進むとトランジションが進んでノイズになる）
                    f.setAccessible(true);
                    view.frame(f.getLong(view), be, true);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
                List<String> full = sigs(view);
                List<String> a = new ArrayList<>(live), b = new ArrayList<>(full);
                if (!strictOrder) { Collections.sort(a); Collections.sort(b); }
                if (!a.equals(b)) {
                    failed[0] = true;
                    bad.add(name + "/" + sd + " 手 " + step);
                }
            };
            MutationScenario.run(html, classes, styles, seedMul * seed + (seedMul > 1 ? 1 : 0), steps, false);
        }
        return bad;
    }

    @Test
    void layoutsDocumentMatchesFullRelayoutEveryStep() {
        assertEquals(List.of(), mismatches("layouts", ShortcutMatrixMain.LAYOUTS, ShortcutMatrixMain.LAYOUT_CLASSES, ShortcutMatrixMain.LAYOUT_STYLES, 8, 45, 1, true));
    }

    @Test
    void cardsDocumentMatchesFullRelayoutEveryStep() {
        assertEquals(List.of(), mismatches("cards", ShortcutMatrixMain.CARDS, ShortcutMatrixMain.CARD_CLASSES, ShortcutMatrixMain.CARD_STYLES, 8, 45, 1, true));
    }

    /** nested（flex の入れ子）は、並び順だけがずれる既知の不具合（A6b）が残る。内容（項目の集合）は一致しなければならない。 */
    @Test
    void nestedDocumentHasTheSameItemsAsFullRelayout() {
        assertEquals(List.of(), mismatches("nested", ShortcutMatrixMain.NESTED, ShortcutMatrixMain.NESTED_CLASSES, ShortcutMatrixMain.NESTED_STYLES, 8, 60, 5, false));
    }

    /** 検査が効くこと: 厳密な並びで比べれば、nested には既知のずれが見える（直ったらこのテストを消して nested も厳密にする）。 */
    @Test
    void nestedStillHasTheKnownOrderOnlyMismatch() {
        List<String> bad = mismatches("nested", ShortcutMatrixMain.NESTED, ShortcutMatrixMain.NESTED_CLASSES, ShortcutMatrixMain.NESTED_STYLES, 8, 60, 5, true);
        assertEquals(true, !bad.isEmpty(), "並び順のずれが解消した: nestedDocumentHasTheSameItemsAsFullRelayout を strictOrder = true にして、このテストを消す");
    }
}
