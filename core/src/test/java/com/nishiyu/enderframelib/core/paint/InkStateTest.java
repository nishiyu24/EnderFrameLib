package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

/** K4g: 窓の外の代理の状態機械（{@link InkState}）。全ての遷移と、遷移してはいけない場合を網羅する。 */
class InkStateTest {
    private static final float W = 800, H = 600, M = 640;

    @Test
    void onStoreChoosesTheStateFromTheThreeInputs() {
        for (boolean empty : new boolean[] {false, true}) {
            for (boolean cull : new boolean[] {false, true}) {
                for (int owners : new int[] {0, 3}) {
                    InkState s = InkState.onStore(empty, cull, owners);
                    InkState want = !empty || !cull ? InkState.NOT_EMPTY : owners == 0 ? InkState.FREE : InkState.PENDING;
                    assertSame(want, s, "empty=" + empty + " cull=" + cull + " owners=" + owners);
                }
            }
        }
    }

    @Test
    void onlyEmptyCulledStatesCanTranslate() {
        assertFalse(InkState.NOT_EMPTY.canTranslate());
        assertFalse(InkState.UNBOUNDED.canTranslate());
        assertTrue(InkState.FREE.canTranslate());
        assertTrue(InkState.PENDING.canTranslate());
    }

    @Test
    void statesThatCannotProveAnythingNeverSayOutside() {
        for (InkState s : new InkState[] {InkState.NOT_EMPTY, InkState.PENDING, InkState.UNBOUNDED}) {
            assertFalse(s.outside(0f, H + M + 9999f, W, H, M), s + " は保守的に偽");
        }
        assertTrue(InkState.FREE.outside(0f, 0f, W, H, M), "FREE は位置に依らず真");
    }

    @Test
    void resolvingOnlyChangesPendingAndIsIdempotent() {
        String html = "<html><body style='margin:0'><div id='a' style='height:30px'>x</div><div id='b' style='position:absolute;top:5px'>y</div></body></html>";
        try (StandaloneView v = new StandaloneView(html, 400, 300, FontResolver.FALLBACK)) {
            v.ui().flushInvalidation();
            LayoutBox a = find(v.ui().layoutRoot(), "a"), b = find(v.ui().layoutRoot(), "b");
            assertNotNull(a);
            assertNotNull(b);
            for (InkState s : new InkState[] {InkState.NOT_EMPTY, InkState.FREE, InkState.UNBOUNDED}) assertSame(s, s.resolved(a), s + " は変わらない");
            InkState bounded = InkState.PENDING.resolved(a);
            assertEquals(InkState.Kind.BOUNDED, bounded.kind, "通常の箱は境界を作れる");
            assertSame(bounded, bounded.resolved(a), "BOUNDED は変わらない");
            assertSame(InkState.UNBOUNDED, InkState.PENDING.resolved(b), "位置指定の箱は境界を作れない");
            assertTrue(bounded.canTranslate());
            // 境界は箱の左上からの相対: 箱を窓の下へ動かすと外、窓の中なら内
            assertTrue(bounded.outside(0f, H + M + 1f, W, H, M));
            assertFalse(bounded.outside(0f, 10f, W, H, M));
        }
    }

    @Test
    void quietMeansNeitherTheBoxNorItsChildrenChangedSinceTheEpoch() {
        try (StandaloneView v = new StandaloneView("<html><body><div id='a'><span>x</span></div></body></html>", 300, 200, FontResolver.FALLBACK)) {
            v.ui().flushInvalidation();
            LayoutBox a = find(v.ui().layoutRoot(), "a");
            assertTrue(InkState.quiet(a, LayoutBox.paintEpoch()), "いまの通し番号以下なら静か");
            assertFalse(InkState.quiet(a, 0L), "古い通し番号より新しい印があれば静かでない");
        }
    }

    private static LayoutBox find(LayoutBox b, String id) {
        if (b.node != null && !b.isAnonymous && id.equals(b.node.getId())) return b;
        for (LayoutBox c : b.children) {
            LayoutBox f = find(c, id);
            if (f != null) return f;
        }
        return null;
    }
}
