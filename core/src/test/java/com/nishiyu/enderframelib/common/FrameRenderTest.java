package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.tree.PaintBox;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1 回だけのフレーム（{@link FrameRender}。M2c-3 で旧 {@code RenderPipeline.layoutDocument} / {@code renderDocument} /
 * {@code layoutSubtree} を置き換えた）: ビューと同じ組み立て（{@code V3Frame}）で、ビューが描く結果と同じになること。
 */
class FrameRenderTest {
    private static final String HTML = "<style>body{margin:0} .card{width:120px;padding:4px;background:#123;color:#fff}"
            + " .card:hover{background:#456}</style><div class=card id=c>hello <b id=b>world</b></div><p id=p>tail</p>";

    @Test
    void documentFrameMatchesTheView() {
        Document doc = new UiDocumentCompiler().parseDocument(HTML, "", 320, 200);
        FrameRender.Frame f = FrameRender.document(doc, 320, 200, FontResolver.FALLBACK);
        try (StandaloneView view = new StandaloneView(HTML, 320, 200, FontResolver.FALLBACK)) {
            for (String id : new String[]{"c", "b", "p"}) {
                PaintBox a = TestBoxes.find(f.root(), id), v = TestBoxes.find(view.ui().boxRoot(), id);
                assertNotNull(a, id);
                assertEquals(v.x(), a.x(), 0f, id + " x");
                assertEquals(v.y(), a.y(), 0f, id + " y");
                assertEquals(v.w(), a.w(), 0f, id + " w");
                assertEquals(v.h(), a.h(), 0f, id + " h");
            }
            assertEquals(view.ui().renderCache(), f.items(), "表示リストもビューの最初のフレームと同じ");
        }
    }

    @Test
    void documentFrameReadsTheGivenStateAndBindsItsComputedStyles() {
        Document doc = new UiDocumentCompiler().parseDocument(HTML, "", 320, 200);
        InteractionState state = new InteractionState();
        state.setHoveredNode(doc.root.getElementById("c"));
        FrameRender.Frame f = FrameRender.document(doc, 320, 200, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);
        assertEquals("#445566", TestBoxes.find(f.root(), "c").style().get(CssProperty.BACKGROUND_COLOR), ":hover は渡した状態から読む");
        assertEquals("#445566", state.computedStyleOf(doc.root.getElementById("c")).get(CssProperty.BACKGROUND_COLOR),
                "算出スタイルの読み口はこのフレームの箱の木に結ばれる");
    }

    @Test
    void subtreeLaysOutOneElementAsTheRoot() {
        Document doc = new UiDocumentCompiler().parseDocument(HTML, "", 320, 200);
        HtmlNode card = doc.root.getElementById("c");
        FrameRender.Frame f = FrameRender.subtree(card, doc.sheet, 1000, 1000, null, FontResolver.FALLBACK);
        assertEquals(card, f.root().node, "根は渡した要素");
        assertEquals(128f, f.root().w(), 0.01f, "幅は要素の指定（120px + padding 4px × 2）");
        assertTrue(f.root().h() > 0);
        List<DisplayItem> items = f.items();
        assertTrue(items.stream().anyMatch(i -> i.type() == ElementType.TEXT && "world".equals(i.text())), "子孫の文字も描く");
        assertTrue(items.stream().anyMatch(i -> (i.bgColor() & 0xFFFFFF) == 0x112233), "要素の背景を描く");
        // 幅が auto の要素は使える幅いっぱい（旧 layoutSubtree と同じ。文書の根と違い、指定のある幅はビューポートに固定しない）
        FrameRender.Frame p = FrameRender.subtree(doc.root.getElementById("p"), doc.sheet, 1000, 1000, null, FontResolver.FALLBACK);
        assertEquals(1000f, p.root().w(), 0.01f);
    }
}
