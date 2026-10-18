package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.client.renderer.UIRenderer;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.LayoutContext.ScrollState;
import com.nishiyu.enderframelib.core.model.UIElement;
import com.nishiyu.enderframelib.core.text.FontFace.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
        LayoutBox root = layout(THIRDS);
        float third = 100f / 3f;
        assertEquals(third, findById(root, "a").w, 0.01f);
        assertEquals(third, findById(root, "b").w, 0.01f);
        assertEquals(third, findById(root, "c").w, 0.01f);
        assertEquals(2 * third, findById(root, "c").x - findById(root, "row").x, 0.01f);
    }

    @Test
    public void testPercentLengthsAreNotTruncated() {
        LayoutBox root = layout("""
                <style>body { margin: 0; }</style>
                <div id="outer" style="width: 101px;"><div id="half" style="width: 50%; height: 1px;"></div></div>
                """);
        // 以前は 50 に切り捨てていた
        assertEquals(50.5f, findById(root, "half").w, 0.001f);
    }

    @Test
    public void testRoundedElementsTileWithoutGaps() {
        UIParser parser = new UIParser();
        UIParser.Document doc = parser.parseDocument(THIRDS, "", VIEWPORT_W, VIEWPORT_H);
        LayoutBox root = parser.layoutDocument(doc, VIEWPORT_W, VIEWPORT_H, Map.of(),
                new ScrollState(), FontResolver.FALLBACK);
        List<UIElement> elements = new UIRenderer(VIEWPORT_W, VIEWPORT_H, doc.sheet.keyframes, new ScrollState())
                .render(root);

        UIElement a = findElement(elements, "a_bg");
        UIElement b = findElement(elements, "b_bg");
        UIElement c = findElement(elements, "c_bg");
        // 幅をそれぞれ丸めると 33 + 33 + 33 で 1px 足りなくなる。辺を丸めれば隣と接し、合計は親の幅に一致する
        assertEquals(a.x() + a.width(), b.x(), "a と b の間に隙間か重なりがある");
        assertEquals(b.x() + b.width(), c.x(), "b と c の間に隙間か重なりがある");
        assertEquals(100, c.x() + c.width() - a.x(), "並べた幅の合計が親の幅と一致しない");
    }

    private static LayoutBox layout(String html) {
        UIParser parser = new UIParser();
        UIParser.Document doc = parser.parseDocument(html, "", VIEWPORT_W, VIEWPORT_H);
        return parser.layoutDocument(doc, VIEWPORT_W, VIEWPORT_H, Map.of(), new ScrollState(), FontResolver.FALLBACK);
    }

    private static LayoutBox findById(LayoutBox box, String id) {
        if (box.node != null && id.equals(box.node.getId())) return box;
        for (LayoutBox child : box.children) {
            LayoutBox hit = findById(child, id);
            if (hit != null) return hit;
        }
        if (box.parent == null) throw new AssertionError(id + " が見つからない");
        return null;
    }

    private static UIElement findElement(List<UIElement> elements, String id) {
        for (UIElement el : elements) {
            if (id.equals(el.id())) return el;
        }
        assertNotNull(null, id + " の描画要素が無い");
        return null;
    }
}
