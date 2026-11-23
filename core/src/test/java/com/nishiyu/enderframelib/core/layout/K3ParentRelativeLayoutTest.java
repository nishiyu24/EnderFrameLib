package com.nishiyu.enderframelib.core.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.common.FrameOracle;
import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.EngineContext;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.kernel.Shortcut;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * K3（親相対の配置と位置の解決の分離）の契約と回帰の検証。
 */
class K3ParentRelativeLayoutTest {

    private static LayoutBox findBox(LayoutBox box, String id) {
        if (box == null) return null;
        if (box.node != null && id.equals(box.node.key())) return box;
        for (LayoutBox c : box.children) {
            LayoutBox found = findBox(c, id);
            if (found != null) return found;
        }
        return null;
    }

    @Test
    void shiftSubtreeAndLegacySwitchesAreRemoved() {
        for (Shortcut s : Shortcut.values()) {
            assertTrue(!"SHIFT".equals(s.name()) && !"KEEP_PLACEMENTS".equals(s.name()),
                    "Legacy shortcut must be removed: " + s.name());
        }
        for (Field f : PlacementMemo.class.getDeclaredFields()) {
            String n = f.getName();
            assertTrue(!"lastLayoutX".equals(n) && !"lastLayoutY".equals(n) && !"LAYOUTS_SHIFTED".equals(n),
                    "Legacy PlacementMemo field must be removed: " + n);
        }
        for (Method m : PlacementMemo.class.getDeclaredMethods()) {
            assertTrue(!"shiftSubtree".equals(m.getName()), "shiftSubtree must be removed");
        }
        for (Method m : LayoutBox.class.getDeclaredMethods()) {
            assertTrue(!"layoutsShifted".equals(m.getName()), "layoutsShifted must be removed");
        }
    }

    @Test
    void descendantsKeepParentRelativeCoordsAndReuseOnSiblingResize() {
        String html = """
                <style>
                  body { margin: 0; }
                  #grow { height: 20px; }
                  #card { width: 220px; padding: 10px; border: 2px solid #333; margin-top: 6px; }
                  #inner { padding: 4px; margin-top: 5px; }
                  #badge { display: inline-block; width: 24px; height: 12px; }
                </style>
                <div id="grow"></div>
                <div id="card">
                  <div id="inner">
                    <span id="sp">Hello <span id="ibWrap"><span id="badge"></span></span> world</span>
                  </div>
                </div>
                """;
        StandaloneView v = new StandaloneView(html, 400, 400, FontResolver.FALLBACK);
        v.ui().flushInvalidation();

        LayoutBox card = findBox(v.ui().layoutRoot(), "card");
        LayoutBox inner = findBox(v.ui().layoutRoot(), "inner");
        LayoutBox badge = findBox(v.ui().layoutRoot(), "badge");
        float relCardX = card.x;
        float relInnerX = inner.x;
        float relInnerY = inner.y;
        float relBadgeX = badge.x;
        float relBadgeY = badge.y;
        float absCardYBefore = card.y();
        float absBadgeYBefore = badge.y();

        long skippedBefore = LayoutBox.layoutsSkipped();
        HtmlElement grow = (HtmlElement) v.root().getNodeById("grow");
        grow.setAttribute("style", "height: 75px;");
        v.ui().flushInvalidation();

        assertNull(FrameOracle.firstDifference(v), "Sibling growth must match fresh oracle");
        assertTrue(LayoutBox.layoutsSkipped() > skippedBefore, "#card subtree must reuse layout");
        assertEquals(relCardX, card.x, 0.001f);
        assertEquals(relInnerX, inner.x, 0.001f, "#inner relative x unchanged");
        assertEquals(relInnerY, inner.y, 0.001f, "#inner relative y unchanged");
        assertEquals(relBadgeX, badge.x, 0.001f, "#badge relative x unchanged");
        assertEquals(relBadgeY, badge.y, 0.001f, "#badge relative y unchanged");
        assertEquals(absCardYBefore + 55f, card.y(), 0.01f, "resolved card.y() moves by +55");
        assertEquals(absBadgeYBefore + 55f, badge.y(), 0.01f, "resolved badge.y() moves by +55");
        v.close();
    }

    @Test
    void externalContainingBlockFixedRelativeAndStickyStayCorrectUnderMoves() {
        String html = """
                <style>
                  body { margin: 0; }
                  #topSpacer { height: 10px; }
                  #app { position: relative; width: 320px; height: 260px; }
                  #midSpacer { height: 15px; }
                  #hold { width: 200px; padding: 8px; }
                  #pin { position: absolute; bottom: 4px; right: 6px; width: 30px; height: 20px; }
                  #rel { position: relative; top: 12px; left: 9px; width: 100px; height: 30px; }
                  #relChild { width: 40px; height: 10px; margin: 3px; }
                  #scroll { width: 200px; height: 100px; overflow-y: auto; }
                  #sticky { position: sticky; top: 8px; height: 20px; }
                  #filler { height: 240px; }
                  #trCb { transform: translateZ(0); width: 200px; height: 80px; }
                  #trFixed { position: fixed; bottom: 5px; right: 5px; width: 20px; height: 14px; }
                  #vpFixed { position: fixed; bottom: 10px; right: 12px; width: 20px; height: 14px; }
                </style>
                <div id="topSpacer"></div>
                <div id="app">
                  <div id="midSpacer"></div>
                  <div id="hold">
                    <div id="pin"></div>
                    <div id="rel"><div id="relChild"></div></div>
                  </div>
                </div>
                <div id="scroll"><div id="sticky"></div><div id="filler"></div></div>
                <div id="trCb"><div id="trFixed"></div></div>
                <div id="vpFixed"></div>
                """;
        StandaloneView v = new StandaloneView(html, 500, 500, FontResolver.FALLBACK);
        v.ui().flushInvalidation();
        assertNull(FrameOracle.firstDifference(v), "initial");

        // 1. #hold moves relative to its external containing block #app
        ((HtmlElement) v.root().getNodeById("midSpacer")).setAttribute("style", "height: 45px;");
        v.ui().flushInvalidation();
        assertNull(FrameOracle.firstDifference(v), "after #midSpacer grows inside #app");

        // 2. #app, #scroll, #trCb, #vpFixed all affected by outer #topSpacer growth
        ((HtmlElement) v.root().getNodeById("topSpacer")).setAttribute("style", "height: 60px;");
        v.ui().flushInvalidation();
        assertNull(FrameOracle.firstDifference(v), "after #topSpacer grows above #app/#scroll/#trCb");

        // 3. shrink back
        ((HtmlElement) v.root().getNodeById("topSpacer")).setAttribute("style", "height: 5px;");
        ((HtmlElement) v.root().getNodeById("midSpacer")).setAttribute("style", "height: 10px;");
        v.ui().flushInvalidation();
        assertNull(FrameOracle.firstDifference(v), "after shrink");
        v.close();
    }

    @Test
    void paintItemsMatchFullBuildWhenShiftedWithFirstLineMarkersAndMulticol() {
        String html = """
                <style>
                  body { margin: 0; }
                  #lead { height: 12px; }
                  #card { width: 260px; overflow: hidden; padding: 6px; }
                  #fl::first-line { background-color: #ffcc00; color: #112233; }
                  #mc { column-count: 2; column-gap: 12px; column-rule: 2px solid #444; }
                  ul { margin: 0; padding-left: 20px; }
                </style>
                <div id="lead"></div>
                <div id="card">
                  <p id="fl">First line styled text wrapping onto a second line for check</p>
                  <div id="mc"><p>Col A</p><p>Col B</p></div>
                  <ul><li id="li">Marker line</li></ul>
                </div>
                """;
        boolean prev = EngineContext.current().flags().parentRelativeDisplayItems();
        try {
            for (boolean parentRel : new boolean[]{true, false}) {
                EngineContext.current().updateFlags(f -> f.withParentRelativeDisplayItems(parentRel));
                try (StandaloneView v = new StandaloneView(html, 400, 400, FontResolver.FALLBACK)) {
                    v.ui().flushInvalidation();
                    ((HtmlElement) v.root().getNodeById("lead")).setAttribute("style", "height: 48px;");
                    v.ui().flushInvalidation();
                    assertNull(FrameOracle.firstDifference(v), "oracle parentRel=" + parentRel);

                    List<DisplayItem> actual = v.ui().elements();
                    try (StandaloneView fresh = new StandaloneView(html, 400, 400, FontResolver.FALLBACK)) {
                        ((HtmlElement) fresh.root().getNodeById("lead")).setAttribute("style", "height: 48px;");
                        fresh.ui().flushInvalidation();
                        List<DisplayItem> expected = fresh.ui().elements();
                        assertEquals(expected.size(), actual.size(), "item count parentRel=" + parentRel);
                        for (int i = 0; i < expected.size(); i++) {
                            DisplayItem e = expected.get(i), a = actual.get(i);
                            assertEquals(e.x(), a.x(), 0.01f, "item " + i + " x");
                            assertEquals(e.y(), a.y(), 0.01f, "item " + i + " y");
                            assertEquals(e.width(), a.width(), "item " + i + " width");
                            assertEquals(e.height(), a.height(), "item " + i + " height");
                        }
                    }
                }
            }
        } finally {
            EngineContext.current().updateFlags(f -> f.withParentRelativeDisplayItems(prev));
        }
    }
}
