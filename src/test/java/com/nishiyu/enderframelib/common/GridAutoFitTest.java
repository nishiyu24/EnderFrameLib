package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code repeat(auto-fit)} の空のトラックの畳み（R9）。{@code auto-fill} は空のトラックを残し、
 * {@code auto-fit} は潰して残りのトラックが幅を分け合う。
 */
public class GridAutoFitTest {

    private static final int VIEWPORT_W = 400;
    private static final int VIEWPORT_H = 200;

    @Test
    public void testAutoFillKeepsEmptyTracks() {
        LayoutBox root = layout(grid("auto-fill", "0"));
        assertEquals(50, find(root, "a").w, 0.01f);
        assertEquals(50, find(root, "b").w, 0.01f);
        assertEquals(50, find(root, "b").x - find(root, "a").x, 0.01f);
    }

    @Test
    public void testAutoFitCollapsesEmptyTracksSoItemsStretch() {
        LayoutBox root = layout(grid("auto-fit", "0"));
        assertEquals(150, find(root, "a").w, 0.01f);
        assertEquals(150, find(root, "b").w, 0.01f);
        assertEquals(150, find(root, "b").x - find(root, "a").x, 0.01f);
    }

    @Test
    public void testAutoFitGapsCollapseWithTheTracks() {
        // 幅 300・gap 10 → 5 本入る（5*50 + 4*10 = 290）。2 つ入ると残りは潰れ、gap は 1 本だけ
        LayoutBox root = layout(grid("auto-fit", "10px"));
        assertEquals(145, find(root, "a").w, 0.01f);
        assertEquals(145, find(root, "b").w, 0.01f);
        assertEquals(155, find(root, "b").x - find(root, "a").x, 0.01f);
    }

    @Test
    public void testAutoFitKeepsTracksThatItemsSpan() {
        LayoutBox root = layout("""
                <div style="display: grid; width: 300px; grid-template-columns: repeat(auto-fit, minmax(50px, 1fr));">
                  <div id="a" style="grid-column: 1 / span 2; height: 10px;"></div>
                  <div id="b" style="grid-column: 4; height: 10px;"></div>
                </div>
                """);
        // 6 本のうち 1〜2 と 4 が使われ、3・5・6 が潰れて 3 本になる
        assertEquals(200, find(root, "a").w, 0.01f);
        assertEquals(100, find(root, "b").w, 0.01f);
        assertEquals(200, find(root, "b").x - find(root, "a").x, 0.01f);
    }

    private static String grid(String mode, String gap) {
        return """
                <div style="display: grid; width: 300px; gap: %s; grid-template-columns: repeat(%s, minmax(50px, 1fr));">
                  <div id="a" style="height: 10px;"></div>
                  <div id="b" style="height: 10px;"></div>
                </div>
                """.formatted(gap, mode);
    }

    private static LayoutBox layout(String html) {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument("<style>body { margin: 0; }</style>" + html, "", VIEWPORT_W, VIEWPORT_H);
        return parser.layoutDocument(doc, VIEWPORT_W, VIEWPORT_H, Map.of(), new ScrollState(), FontResolver.FALLBACK);
    }

    private static LayoutBox find(LayoutBox box, String id) {
        if (id.equals(box.node.attrs.get("id"))) return box;
        for (LayoutBox child : box.elementChildren) {
            LayoutBox hit = find(child, id);
            if (hit != null) return hit;
        }
        return null;
    }
}
