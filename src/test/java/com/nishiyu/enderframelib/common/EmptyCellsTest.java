package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.client.renderer.DisplayListBuilder;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R9: {@code empty-cells: hide}（CSS 2.2 §17.6.1.1）。空の分離枠線セルは背景も枠線も描かない。 */
public class EmptyCellsTest {

    private static final String CELLS = "<tr><td id=e style='height:20px;width:30px;background:red;border:2px solid blue'> </td>"
            + "<td id=f style='height:20px;width:30px;background:red;border:2px solid blue'>x</td></tr></table>";

    @Test
    public void emptyCellIsNotPaintedWhenHidden() {
        List<DisplayItem> items = render("<table style='empty-cells:hide'>" + CELLS);
        assertFalse(hasPart(items, "e"), "空のセルが描かれている");
        assertTrue(hasPart(items, "f"), "中身のあるセルが描かれていない");
    }

    @Test
    public void emptyCellIsPaintedByDefault() {
        assertTrue(hasPart(render("<table>" + CELLS), "e"));
    }

    @Test
    public void collapsedTableIgnoresEmptyCells() {
        assertTrue(hasPart(render("<table style='empty-cells:hide;border-collapse:collapse'>" + CELLS), "e"));
    }

    private static boolean hasPart(List<DisplayItem> items, String id) {
        for (DisplayItem i : items) if (i.id().equals(id + "_bg")) return true;
        return false;
    }

    private static List<DisplayItem> render(String html) {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument("<style>body{margin:0}</style>" + html, "", 400, 300);
        LayoutBox root = parser.layoutDocument(doc, 400, 300, Map.of(), new ScrollState(), FontResolver.FALLBACK);
        return new DisplayListBuilder(400, 300, doc.sheet.keyframes, new ScrollState()).render(root);
    }
}
