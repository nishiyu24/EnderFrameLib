package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.paint.tree.PaintBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 段組（multicol。R9）。期待値は Edge 156 ヘッドレスの {@code getBoundingClientRect} と {@code Range} の実測。
 * 文字の幅に依存しないよう、行は {@code line-height: 20px} と {@code <br>} で作り、位置は列の x と行の順で見る。
 * ブラウザは行を持たない空のブロックも途中で分けるが、このエンジンは分けないので、そこが割り切れる高さで比べる。
 */
@Disabled("G3 multicol は M3c で戻す（計画書 §3.5）。計画書 §3.2「新経路の穴（M2c-3 の移植で見えた）」")
public class MulticolTest {

    private static final int VIEWPORT_W = 400;
    private static final int VIEWPORT_H = 300;
    private static final String CSS = "<style>body{margin:0;font:16px/20px monospace} .b{height:20px} p{margin:0}</style>";

    @Test
    public void testBalancedColumnsFillTopToBottom() {
        PaintBox root = layout("<div id=m style='columns:3;column-gap:0;width:300px'>" + blocks("a", "b", "c", "d", "e", "f") + "</div>");
        assertRect(root, "a", 0, 0, 100, 20);
        assertRect(root, "b", 0, 20, 100, 20);
        assertRect(root, "c", 100, 0, 100, 20);
        assertRect(root, "d", 100, 20, 100, 20);
        assertRect(root, "e", 200, 0, 100, 20);
        assertRect(root, "f", 200, 20, 100, 20);
        assertEquals(40, find(root, "m").h(), 0.01f);
    }

    @Test
    public void testDefaultGapIsOneEm() {
        PaintBox root = layout("<div id=m style='column-count:3;width:300px'>" + blocks("a", "b", "c") + "</div>");
        assertRect(root, "a", 0, 0, 89.33f, 20);
        assertRect(root, "b", 105.33f, 0, 89.33f, 20);
        assertRect(root, "c", 210.67f, 0, 89.33f, 20);
    }

    @Test
    public void testColumnWidthDecidesTheCount() {
        PaintBox root = layout("<div id=m style='column-width:100px;column-gap:0;width:350px'>" + blocks("a", "b", "c") + "</div>");
        assertRect(root, "a", 0, 0, 116.67f, 20);
        assertRect(root, "b", 116.67f, 0, 116.67f, 20);
        assertRect(root, "c", 233.33f, 0, 116.67f, 20);
    }

    @Test
    public void testColumnCountIsCappedByColumnWidth() {
        // 幅 250 に 100px の列は 2 本しか入らない（count: 5 でも 2 列）
        PaintBox root = layout("<div id=m style='columns:5 100px;column-gap:0;width:250px'>" + blocks("a", "b") + "</div>");
        assertRect(root, "a", 0, 0, 125, 20);
        assertRect(root, "b", 125, 0, 125, 20);
    }

    @Test
    public void testColumnFillAutoFillsToTheHeight() {
        PaintBox root = layout("<div id=m style='column-count:2;column-gap:0;column-fill:auto;height:60px;width:200px'>"
                + blocks("a", "b", "c", "d", "e") + "</div>");
        assertRect(root, "c", 0, 40, 100, 20);
        assertRect(root, "d", 100, 0, 100, 20);
        assertRect(root, "e", 100, 20, 100, 20);
        assertEquals(60, find(root, "m").h(), 0.01f);
    }

    @Test
    public void testColumnSpanAllBreaksTheColumns() {
        PaintBox root = layout("<div id=m style='column-count:2;column-gap:0;width:200px'>" + blocks("a", "b")
                + "<div id=s style='column-span:all;height:30px'></div>" + blocks("c", "d") + "</div>");
        assertRect(root, "a", 0, 0, 100, 20);
        assertRect(root, "b", 100, 0, 100, 20);
        assertRect(root, "s", 0, 20, 200, 30);
        assertRect(root, "c", 0, 50, 100, 20);
        assertRect(root, "d", 100, 50, 100, 20);
        assertEquals(70, find(root, "m").h(), 0.01f);
    }

    @Test
    public void testOwnInlineContentBreaksBetweenLines() {
        PaintBox root = layout("<div id=m style='column-count:2;column-gap:0;width:200px'><span id=t>l1<br>l2<br>l3<br>l4<br>l5<br>l6<br>l7</span></div>");
        List<float[]> lines = lineOrigins(find(root, "t"));
        assertEquals(7, lines.size());
        // 4 行 + 3 行。2 列目は 1 列目の先頭と同じ高さから始まる
        for (int i = 0; i < 4; i++) assertEquals(0, lines.get(i)[0], 0.01f, "line " + i);
        for (int i = 4; i < 7; i++) assertEquals(100, lines.get(i)[0], 0.01f, "line " + i);
        assertEquals(lines.get(0)[1], lines.get(4)[1], 0.01f);
        assertEquals(lines.get(0)[1] + 60, lines.get(3)[1], 0.01f);
        assertEquals(80, find(root, "m").h(), 0.01f);
    }

    @Test
    public void testBlockChildrenGoToSeparateColumns() {
        PaintBox root = layout("<div id=m style='column-count:2;column-gap:0;width:200px'>"
                + "<p id=p1>a1<br>a2<br>a3<br>a4</p><p id=p2>b1<br>b2<br>b3<br>b4</p></div>");
        assertEquals(80, find(root, "m").h(), 0.01f);
        for (float[] o : lineOrigins(find(root, "p2"))) assertEquals(100, o[0], 0.01f);
        for (float[] o : lineOrigins(find(root, "p1"))) assertEquals(0, o[0], 0.01f);
    }

    @Test
    public void testParagraphIsSplitAcrossColumns() {
        PaintBox root = layout("<div id=m style='column-count:2;column-gap:10px;width:210px'>"
                + "<p id=p1>a1<br>a2<br>a3<br>a4<br>a5</p></div>");
        List<float[]> lines = lineOrigins(find(root, "p1"));
        assertEquals(5, lines.size());
        for (int i = 0; i < 3; i++) assertEquals(0, lines.get(i)[0], 0.01f);
        for (int i = 3; i < 5; i++) assertEquals(110, lines.get(i)[0], 0.01f);
        assertEquals(lines.get(0)[1], lines.get(3)[1], 0.01f);
        assertEquals(60, find(root, "m").h(), 0.01f);
    }

    @Test
    public void testContainerContentBoxIsTheOrigin() {
        // 段組の前後にブロックがあっても、列はコンテナの内容ボックスから
        PaintBox root = layout("<div style='height:7px'></div><div id=m style='columns:2;column-gap:0;width:200px;padding:5px'>"
                + blocks("a", "b") + "</div><div id=after style='height:10px'></div>");
        assertRect(root, "a", 0, 0, 100, 20);
        assertRect(root, "b", 100, 0, 100, 20);
        assertEquals(7 + 5 + 20 + 5, find(root, "after").y(), 0.01f);
    }

    @Test
    public void testColumnRuleSitsInTheMiddleOfTheGap() {
        PaintBox root = layout("<div id=m style='columns:2;column-gap:10px;column-rule:2px solid red;width:210px'>"
                + blocks("a", "b") + "</div>");
        PaintBox m = find(root, "m");
        assertNotNull(m.columnRules());
        assertEquals(1, m.columnRules().length);
        float[] rule = m.columnRules()[0];
        assertEquals(m.x() + 104, rule[0], 0.01f);
        assertEquals(2, rule[2], 0.01f);
        assertEquals(20, rule[3], 0.01f);
    }

    @Test
    public void testNoRuleWithoutStyle() {
        PaintBox root = layout("<div id=m style='columns:2;column-gap:10px;column-rule-width:2px;width:210px'>" + blocks("a", "b") + "</div>");
        assertNull(find(root, "m").columnRules());
    }

    @Test
    public void testPlainBlockHasNoColumns() {
        PaintBox root = layout("<div id=m style='width:300px'>" + blocks("a", "b") + "</div>");
        assertRect(root, "b", 0, 20, 300, 20);
    }

    @Test
    public void testDecoratedBlockIsNotSplit() {
        // 背景を持つブロックは 1 つの単位。2 つ目は次の列へ
        PaintBox root = layout("<div id=m style='column-count:2;column-gap:0;width:200px'>"
                + "<div id=x style='background:#ccc'><div class=b></div><div class=b></div></div>"
                + "<div id=y style='background:#ccc'><div class=b></div><div class=b></div></div></div>");
        assertRect(root, "x", 0, 0, 100, 40);
        assertRect(root, "y", 100, 0, 100, 40);
        assertTrue(find(root, "m").h() >= 40 - 0.01f);
    }

    // ==========================================

    private static String blocks(String... ids) {
        StringBuilder sb = new StringBuilder();
        for (String id : ids) sb.append("<div id=").append(id).append(" class=b></div>");
        return sb.toString();
    }

    /** {@code m}（段組コンテナ）の内容ボックスの左上を原点とした位置と大きさで比べる。 */
    private static void assertRect(PaintBox root, String id, float x, float y, float w, float h) {
        PaintBox b = find(root, id);
        assertNotNull(b, id);
        PaintBox m = find(root, "m");
        float ox = m.x() + m.bL() + m.pL(), oy = m.y() + m.bT() + m.pT();
        assertEquals(x, b.x() - ox, 0.02f, id + ".x");
        assertEquals(y, b.y() - oy, 0.02f, id + ".y");
        assertEquals(w, b.w(), 0.02f, id + ".w");
        assertEquals(h, b.h(), 0.02f, id + ".h");
    }

    /** 要素の下のテキスト断片（行ごと）の左上。{@code m} の内容ボックスが原点。文書順。 */
    private static List<float[]> lineOrigins(PaintBox box) {
        List<float[]> out = new ArrayList<>();
        PaintBox m = box;
        while (m.paintParent() != null && !"m".equals(m.node.attrs.get("id"))) m = m.paintParent();
        collectFragments(box, m.x() + m.bL() + m.pL(), m.y() + m.bT() + m.pT(), out);
        return out;
    }

    private static void collectFragments(PaintBox box, float ox, float oy, List<float[]> out) {
        for (var f : box.runs()) {
            if (f.isText() && !f.text().isBlank()) out.add(new float[]{f.x() - ox, f.y() - oy});
        }
        for (PaintBox child : TestBoxes.elements(box)) collectFragments(child, ox, oy, out);
    }

    private static PaintBox layout(String html) {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument(CSS + html, "", VIEWPORT_W, VIEWPORT_H);
        return parser.layoutDocument(doc, VIEWPORT_W, VIEWPORT_H, Map.of(), new ScrollState(), FontResolver.FALLBACK);
    }

    private static PaintBox find(PaintBox box, String id) {
        if (id.equals(box.node.attrs.get("id"))) return box;
        for (PaintBox child : TestBoxes.elements(box)) {
            PaintBox hit = find(child, id);
            if (hit != null) return hit;
        }
        return null;
    }
}
