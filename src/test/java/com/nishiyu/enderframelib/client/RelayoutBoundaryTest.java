package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.client.renderer.DisplayListBuilder;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.dom.node.HtmlDocument;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 再レイアウト境界（size containment や確定サイズのスクロールコンテナ）の中だけが変わったとき、
 * 境界の外を測り直さず、置き直しもしないことを数で確かめる。結果が全部作り直したときと同じことも毎回確かめる。
 */
public class RelayoutBoundaryTest {

    private static final UiDocumentCompiler COMPILER = new UiDocumentCompiler();
    private static final int W = 800;
    private static final int H = 600;
    private static final int ROWS = 200;

    private static String page(String panelStyle) {
        StringBuilder sb = new StringBuilder("<html><head><style>body{margin:8px;font-size:14px}</style></head><body>");
        sb.append("<div id=\"before\">Before the panel</div>");
        sb.append("<div id=\"panel\" style=\"").append(panelStyle).append("\">");
        for (int i = 0; i < ROWS; i++) {
            sb.append("<div id=\"row").append(i).append("\">Row ").append(i).append(" <b>bold</b></div>");
        }
        sb.append("</div>");
        sb.append("<div id=\"after\">After the panel</div>");
        for (int i = 0; i < ROWS; i++) {
            sb.append("<p id=\"out").append(i).append("\">Outside paragraph ").append(i).append("</p>");
        }
        sb.append("</body></html>");
        return sb.toString();
    }

    private static final class Session {
        final Document doc;
        final InteractionState state = new InteractionState();
        final ScrollState scroll = new ScrollState();
        LayoutBox root;

        Session(String html) {
            doc = COMPILER.parseDocument(html, "", W, H);
            ((HtmlDocument) doc.root).setInteractionState(state);
            root = COMPILER.layoutDocument(doc, W, H, Map.of(), scroll, FontResolver.FALLBACK, state);
        }

        void relayout() {
            root = COMPILER.layoutDocument(doc, W, H, Map.of(), scroll, FontResolver.FALLBACK, state, root);
        }

        List<DisplayItem> render() {
            return new DisplayListBuilder(W, H, doc.sheet.keyframes, scroll).render(root);
        }
    }

    /** 同じ変異を当てた文書を、新しく全部作り直したディスプレイリスト。 */
    private static List<DisplayItem> rebuild(String html, java.util.function.Consumer<Document> mutation) {
        Document doc = COMPILER.parseDocument(html, "", W, H);
        InteractionState state = new InteractionState();
        ((HtmlDocument) doc.root).setInteractionState(state);
        ScrollState scroll = new ScrollState();
        mutation.accept(doc);
        LayoutBox root = COMPILER.layoutDocument(doc, W, H, Map.of(), scroll, FontResolver.FALLBACK, state);
        return new DisplayListBuilder(W, H, doc.sheet.keyframes, scroll).render(root);
    }

    private static void editRow(Document doc) {
        doc.root.getElementById("row100").children.get(0).setTextContent("Edited row with a much longer text that wraps");
    }

    private void assertBoundaryKeepsOutsideUntouched(String panelStyle) {
        String html = page(panelStyle);
        Session s = new Session(html);
        s.relayout();

        long measuredBefore = LayoutBox.measurementsComputed();
        long skippedBefore = LayoutBox.layoutsSkipped();
        editRow(s.doc);
        s.relayout();
        long measured = LayoutBox.measurementsComputed() - measuredBefore;
        long skipped = LayoutBox.layoutsSkipped() - skippedBefore;

        // 境界の外の 200 段落（と各々の中身）は測り直さない。測るのは境界の中の変わった行とその祖先だけ
        assertTrue(measured < 60, "境界の外まで測り直している: " + measured + " 回");
        assertTrue(skipped > 0, "境界の外の置き直しを飛ばしていない");
        assertEquals(rebuild(html, RelayoutBoundaryTest::editRow), s.render(), "全部作り直した結果と違う");
    }

    @Test
    public void sizeContainmentIsABoundary() {
        assertBoundaryKeepsOutsideUntouched("contain: size; width: 300px; height: 120px; overflow: hidden");
    }

    @Test
    public void strictContainmentIsABoundary() {
        assertBoundaryKeepsOutsideUntouched("contain: strict; width: 300px; height: 120px");
    }

    @Test
    public void definiteSizedScrollContainerIsABoundary() {
        assertBoundaryKeepsOutsideUntouched("overflow: auto; width: 300px; height: 120px");
    }

    @Test
    public void autoSizedContainerIsNotABoundary() {
        String html = page("overflow: auto");
        Session s = new Session(html);
        s.relayout();
        long before = LayoutBox.measurementsComputed();
        editRow(s.doc);
        s.relayout();
        long measured = LayoutBox.measurementsComputed() - before;
        assertTrue(measured >= 5, "高さが内容で決まる箱は、内容の変化で祖先を測り直す: " + measured + " 回");
        assertEquals(rebuild(html, RelayoutBoundaryTest::editRow), s.render());
    }

    @Test
    public void insertingRowsIntoABoundaryMatchesRebuild() {
        String html = page("contain: strict; width: 300px; height: 120px");
        Session s = new Session(html);
        s.relayout();
        java.util.function.Consumer<Document> insert = doc -> {
            HtmlNode panel = doc.root.getElementById("panel");
            for (int i = 0; i < 3; i++) {
                HtmlNode row = doc.root.getElementById("row" + (10 + i)).cloneNode(true);
                row.setAttribute("id", "added" + i);
                panel.insertBefore(row, panel.children.get(5));
            }
        };
        insert.accept(s.doc);
        s.relayout();
        assertEquals(rebuild(html, insert), s.render());
    }

    @Test
    public void insertingARowSkipsLayoutOfTheRowsBeforeIt() {
        StringBuilder sb = new StringBuilder("<html><body><ul id=\"list\">");
        for (int i = 0; i < 300; i++) sb.append("<li id=\"r").append(i).append("\">Row ").append(i).append("</li>");
        sb.append("</ul></body></html>");
        String html = sb.toString();
        java.util.function.Consumer<Document> insert = doc -> {
            HtmlNode list = doc.root.getElementById("list");
            HtmlNode row = doc.root.getElementById("r0").cloneNode(true);
            row.setAttribute("id", "added");
            list.insertBefore(row, list.children.get(250));
        };
        Session s = new Session(html);
        s.relayout();
        long skippedBefore = LayoutBox.layoutsSkipped();
        insert.accept(s.doc);
        s.relayout();
        long skipped = LayoutBox.layoutsSkipped() - skippedBefore;
        // 挿入位置より前の 250 行は原点も寸法も変わらないので、置き直さない
        assertTrue(skipped >= 250, "挿入位置より前の行の layout を飛ばしていない: " + skipped);
        assertEquals(rebuild(html, insert), s.render());
    }

    @Test
    public void paintOnlyChangeDoesNotMeasureAnything() {
        String html = page("contain: strict; width: 300px; height: 120px");
        Session s = new Session(html);
        s.relayout();
        HtmlNode after = s.doc.root.getElementById("after");

        long before = LayoutBox.measurementsComputed();
        after.setAttribute("style", "opacity: 0.5; transform: translateX(10px); background-color: #123456");
        s.relayout();
        long measured = LayoutBox.measurementsComputed() - before;

        assertTrue(measured < 10, "opacity / transform / 色だけの変化で測り直している: " + measured + " 回");
        java.util.function.Consumer<Document> change = doc ->
                doc.root.getElementById("after").setAttribute("style", "opacity: 0.5; transform: translateX(10px); background-color: #123456");
        assertEquals(rebuild(html, change), s.render());
    }
}
