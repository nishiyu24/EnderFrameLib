package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.css.model.AnimationSpec;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.ImageFitMode;
import com.nishiyu.enderframelib.core.paint.model.RenderMeta;
import com.nishiyu.enderframelib.core.raster.Matrix2D;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 補間の状態を節ごとに持つ表（{@link AnimationStates}。M3b-4b-2）。
 * <ul>
 *   <li>部品の鍵は項目ごとに一意でない（キャンバスの背景 {@code bg_root} と body の背景が同じ鍵）。節の中では項目の ID で引くので、
 *       2 つの項目が 1 つの状態を取り合わない（鍵で引いたときに {@code NoHtmlSiteTest} ほかが落ちた欠陥の番人）。</li>
 *   <li>後片付けは実時間でなく、列の構造が変わった印で行う。列から消えた節の状態はその場で捨て、節が残る限り消えた部品の状態は残す。</li>
 * </ul>
 */
class AnimationStatesTest {
    private static final int BODY = 7, OTHER = 9;

    private static DisplayItem item(String id, long key, ElementType type, int bg, float opacity, int transitionMs) {
        return new DisplayItem(id, id, type, 0, 0, 10, 10, "", 0xFF000000, bg, Map.of(), 0, opacity,
                0f, 0f, 1f, 1f, 0f, transitionMs, AnimationSpec.NONE, ImageFitMode.FILL, RenderMeta.EMPTY, key);
    }

    @Test
    void partsOfOneNodeShareTheNodeButNotTheState() {
        AnimationStates states = new AnimationStates();
        DisplayItem canvasBg = item("bg_root", DisplayItem.bgKeyOf(BODY), ElementType.RECT, 0xFF112233, 1f, 100);
        DisplayItem bodyBg = item("n7_bg", DisplayItem.bgKeyOf(BODY), ElementType.RECT, 0xFF445566, 1f, 100);
        DisplayItem anchor = item("n7", DisplayItem.nodeKeyOf(BODY), ElementType.NODE, 0, 1f, 100);
        assertEquals(canvasBg.key(), bodyBg.key(), "前提: 2 つの項目の鍵が同じ");

        CssAnimator.AnimState a = CssAnimator.stateFor(states, canvasBg, 0L);
        CssAnimator.AnimState b = CssAnimator.stateFor(states, bodyBg, 0L);
        CssAnimator.AnimState c = CssAnimator.stateFor(states, anchor, 0L);
        assertNotSame(a, b, "鍵の同じ 2 つの項目が 1 つの状態を取り合った");
        assertEquals(1, states.nodeCount(), "1 つの節の部品が別の節に分かれた");
        assertEquals(3, states.size());
        assertEquals(Map.of(BODY, List.of("bg_root", "n7_bg", "n7")), states.partsByNode());
        assertSame(c, states.peek(BODY, "n7"), "当たり判定が節の錨の状態を節の番号と ID で引けない");

        // 片方の色が変わっても、もう片方の補間は動かない
        CssAnimator.stateFor(states, item("bg_root", DisplayItem.bgKeyOf(BODY), ElementType.RECT, 0xFFFFFFFF, 1f, 100), 1000L);
        assertTrue(a.isActiveAt(1050L), "変えた部品の遷移が始まらない");
        assertFalse(b.isActiveAt(1050L), "変えていない部品の遷移が始まった");
        assertEquals(0xFF445566, CssAnimator.stateFor(states, bodyBg, 1050L).getInterpolatedValues(1050L).bgColor());
    }

    @Test
    void itemsWithoutANodeGoToTheLooseBucket() {
        AnimationStates states = new AnimationStates();
        DisplayItem loose = item("free", 0L, ElementType.RECT, 0, 1f, 0); // 鍵は ID の文字列から
        assertNotEquals(0L, loose.key() & DisplayItem.STRING_KEY_BIT, "前提: 文字列から作った鍵");
        CssAnimator.AnimState st = CssAnimator.stateFor(states, loose, 0L);
        assertSame(st, states.peek(loose));
        assertSame(st, states.peek(0, "free"));
        assertEquals(Map.of(0, List.of("free")), states.partsByNode());
    }

    @Test
    void sweepDropsNodesThatLeftTheListAndKeepsVanishedPartsOfLiveNodes() {
        AnimationStates states = new AnimationStates();
        DisplayItem anchor = item("n7", DisplayItem.nodeKeyOf(BODY), ElementType.NODE, 0, 1f, 200);
        DisplayItem bg = item("n7_bg", DisplayItem.bgKeyOf(BODY), ElementType.RECT, 0xFF000000, 1f, 200);
        DisplayItem other = item("n9", DisplayItem.nodeKeyOf(OTHER), ElementType.NODE, 0, 1f, 200);
        CssAnimator.stateFor(states, anchor, 0L);
        CssAnimator.AnimState bgState = CssAnimator.stateFor(states, bg, 0L);
        CssAnimator.stateFor(states, other, 0L);
        // 背景の遷移を始めてから、背景の部品だけが列から消える（節 7 は残る）・節 9 は列から消える
        CssAnimator.stateFor(states, item("n7_bg", DisplayItem.bgKeyOf(BODY), ElementType.RECT, 0xFFFFFFFF, 1f, 200), 100L);
        states.retainNodesOf(List.of(anchor));
        assertNull(states.peek(OTHER, "n9"), "列から消えた節の状態が残った");
        assertSame(bgState, states.peek(BODY, "n7_bg"), "節が残っているのに、消えた部品の状態を捨てた");
        assertTrue(bgState.isActiveAt(150L), "戻った部品の遷移が続かない");
        assertEquals(2, states.size());
        states.retainNodesOf(List.of());
        assertTrue(states.isEmpty());
    }

    /** 描く側（{@link PaintSceneDriver}）は列の構造が変わったその場で掃除する。以前は実時間で 1 秒に 1 回で、消えた節の状態が残った。 */
    @Test
    void driverSweepsOnStructureChangeWithoutWallClock() {
        String session = "anim-states-" + System.identityHashCode(this);
        try {
            PaintSceneDriver driver = new PaintSceneDriver(session);
            Painter none = new Painter() {
                @Override public void syncLayers(List<PaintSceneDriver.Group> want) { }
                @Override public void drawItem(DisplayItem el, int index, float flat) { }
            };
            DisplayItem a = item("n7", DisplayItem.nodeKeyOf(BODY), ElementType.NODE, 0, 1f, 0);
            DisplayItem aBg = item("n7_bg", DisplayItem.bgKeyOf(BODY), ElementType.RECT, 0xFF000000, 1f, 0);
            DisplayItem b = item("n9", DisplayItem.nodeKeyOf(OTHER), ElementType.NODE, 0, 1f, 0);
            DisplayItem bBg = item("n9_bg", DisplayItem.keyOf(OTHER, DisplayItem.PART_BG), ElementType.RECT, 0xFF000000, 1f, 0);
            driver.run(List.of(a, aBg, b, bBg), 0L, Matrix2D.IDENTITY, none);
            AnimationStates states = CssAnimator.states(session, false);
            assertNotNull(states);
            assertNotNull(states.peek(OTHER, "n9_bg"), "前提: 描いた項目に状態ができる");
            driver.run(List.of(a, aBg), 1L, Matrix2D.IDENTITY, none); // 1 ミリ秒後（実時間の 1 秒を待たない）
            assertNull(states.peek(OTHER, "n9_bg"), "列から消えた節の状態が残った");
            assertNull(states.peek(OTHER, "n9"));
            assertNotNull(states.peek(BODY, "n7_bg"));
        } finally {
            CssAnimator.clearSession(session);
        }
    }
}
