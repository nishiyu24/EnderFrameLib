package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.paint.tree.PaintView;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.paint.model.*;
import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;
import com.nishiyu.enderframelib.core.layout.model.Viewport;
import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;
import com.nishiyu.enderframelib.core.css.model.Overflow;
import com.nishiyu.enderframelib.core.css.model.TextAlign;
import com.nishiyu.enderframelib.core.css.model.BorderStyle;

/**
 * 軸 3「幾何は float、丸めは描画の 1 箇所だけ」（W2）の検証。
 *
 * <ul>
 *   <li>レイアウトの段では端数を落とさない（以前は length() と各エンジンが int に切り捨てていた）</li>
 *   <li>描画要素にするときは辺を丸めるので、端数の幅が並んでも隙間も重なりも出ない</li>
 * </ul>
 */
public class SubpixelGeometryTest {

    private static final int VIEWPORT_W = 320;
    private static final int VIEWPORT_H = 200;

    private static final String THIRDS = """
            <style>
              body { margin: 0; }
              #row { display: flex; width: 100px; height: 10px; }
              #row div { flex: 1; background: #ff0000; }
            </style>
            <div id="row"><div id="a"></div><div id="b"></div><div id="c"></div></div>
            """;

    @Test
    public void testLayoutKeepsFractionalWidths() {
        PaintView root = layout(THIRDS);
        float third = 100f / 3f, unit = 1f / 64f; // 位置と寸法は LayoutUnit（1/64 px）の格子に丸める（Chrome の LayoutNG と同じ）
        assertEquals(third, findById(root, "a").w(), unit);
        assertEquals(third, findById(root, "b").w(), unit);
        assertEquals(third, findById(root, "c").w(), unit);
        assertEquals(2 * third, findById(root, "c").x() - findById(root, "row").x(), 2 * unit);
    }

    @Test
    public void testPercentLengthsAreNotTruncated() {
        PaintView root = layout("""
                <style>body { margin: 0; }</style>
                <div id="outer" style="width: 101px;"><div id="half" style="width: 50%; height: 1px;"></div></div>
                """);
        // 以前は 50 に切り捨てていた
        assertEquals(50.5f, findById(root, "half").w(), 0.001f);
    }

    @Test
    public void testRoundedElementsTileWithoutGaps() {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument(THIRDS, "", VIEWPORT_W, VIEWPORT_H);
        PaintView root = parser.layoutDocument(doc, VIEWPORT_W, VIEWPORT_H, Map.of(),
                new ScrollState(), FontResolver.FALLBACK);
        List<DisplayItem> elements = com.nishiyu.enderframelib.common.FrameRender.document(doc, VIEWPORT_W, VIEWPORT_H, java.util.Map.of(), new ScrollState(), FontResolver.FALLBACK, new com.nishiyu.enderframelib.core.dom.InteractionState()).items();

        DisplayItem a = DisplayItem.resolveAbsolute(elements, findElement(elements, "a_bg"));
        DisplayItem b = DisplayItem.resolveAbsolute(elements, findElement(elements, "b_bg"));
        DisplayItem c = DisplayItem.resolveAbsolute(elements, findElement(elements, "c_bg"));
        // 幅をそれぞれ丸めると 33 + 33 + 33 で 1px 足りなくなる。辺を丸めれば隣と接し、合計は親の幅に一致する
        assertEquals(a.x() + a.width(), b.x(), "a と b の間に隙間か重なりがある");
        assertEquals(b.x() + b.width(), c.x(), "b と c の間に隙間か重なりがある");
        assertEquals(100, c.x() + c.width() - a.x(), "並べた幅の合計が親の幅と一致しない");
    }

    private static PaintView layout(String html) {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument(html, "", VIEWPORT_W, VIEWPORT_H);
        return parser.layoutDocument(doc, VIEWPORT_W, VIEWPORT_H, Map.of(), new ScrollState(), FontResolver.FALLBACK);
    }

    private static PaintView findById(PaintView box, String id) {
        if (box.node != null && id.equals(box.node.getId())) return box;
        for (PaintView child : box.paintChildren()) {
            PaintView hit = findById(child, id);
            if (hit != null) return hit;
        }
        if (box.paintParent() == null) throw new AssertionError(id + " が見つからない");
        return null;
    }

    private static DisplayItem findElement(List<DisplayItem> elements, String id) {
        for (DisplayItem el : elements) {
            if (id.equals(el.id())) return el;
        }
        assertNotNull(null, id + " の描画要素が無い");
        return null;
    }
}