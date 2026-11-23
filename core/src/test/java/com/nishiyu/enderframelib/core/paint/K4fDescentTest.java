package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.common.UiView;
import com.nishiyu.enderframelib.core.dom.node.AttributeMap;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.kernel.CompactList;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.LayoutOrigin;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * K4f（木を下りる状態の持ち回しの型 {@link PaintDescent}・子の並びの照合 {@link CompactList#sameAs}）。
 * ①<b>正しさ</b>: 受け渡した根・原点が、箱ごとに根まで辿った値・解いた値と常に同じ（実文書の全箱、非スタイル属性の変異の前後）
 * ②<b>境界</b>: 並びの照合は 0・1・2 個・64 個超・長さ違い・入れ替え・容量の余りで素朴な比較と一致する
 * ③<b>全体</b>: 変異の列で、記憶つきの表示リスト＝最初から作った表示リスト。
 */
class K4fDescentTest {
    private static final int W = 800, H = 600;

    private static String doc() {
        StringBuilder sb = new StringBuilder("<html><head><style>body{margin:8px} ul{list-style:none;padding:0;margin:0}"
                + " li{padding:4px;background:#eef} .rel{position:relative;left:3px;top:2px}"
                + "</style></head><body><div id=\"wrap\" style=\"margin-left:7px\"><ul id=\"list\">");
        for (int i = 0; i < 80; i++) {
            sb.append("<li id=\"r").append(i).append("\"").append(i % 5 == 0 ? " class=\"rel\"" : "").append("><div><span>Row </span><b>#")
                    .append(i).append("</b></div></li>");
        }
        return sb.append("</ul></div></body></html>").toString();
    }

    private static void walk(LayoutBox box, PaintDescent d, int[] visited) {
        float ox = d.originX(box), oy = d.originY(box);
        if (d.depth() > 0 && d.at(d.depth() - 1) == box.parent) {
            // 親から受け継いだ原点（親の下降で渡された値）が、箱から解いた値と同じ
            assertEquals(LayoutOrigin.originX(box), ox, 1e-3f, "originX " + box.node.tag);
            assertEquals(LayoutOrigin.originY(box), oy, 1e-3f, "originY " + box.node.tag);
        }
        d.push(box, box.x(), box.y(), ox, oy);
        try {
            visited[0]++;
            assertEquals(AttributeMap.treeNonStyleAttrVersion(box.node), d.treeAttrVer(box), "treeAttrVer " + box.node.tag);
            assertEquals(box.x(), d.ax);
            for (int i = 0, n = box.children.size(); i < n; i++) walk(box.children.get(i), d, visited);
        } finally {
            d.pop();
        }
    }

    @Test
    void descentMatchesPerBoxRecomputationOnRealDocument() {
        try (StandaloneView v = new StandaloneView(doc(), W, H, FontResolver.FALLBACK)) {
            v.ui().flushInvalidation();
            for (int round = 0; round < 3; round++) {
                PaintDescent d = new PaintDescent();
                int[] n = {0};
                walk(v.ui().layoutRoot(), d, n);
                assertTrue(n[0] > 200, "箱が少なすぎる " + n[0]);
                assertEquals(0, d.depth());
                // 非スタイル属性を変えると、全ての箱が根の版の変化を見る（受け渡した根から読んでも古い値にならない）
                ((HtmlElement) v.root().getNodeById("r" + (7 + round))).setAttribute("title", "t" + round);
                v.ui().flushInvalidation();
            }
        }
    }

    @Test
    void unlistedNodeIsItsOwnTop() {
        HtmlElement top = new HtmlElement("div"), mid = new HtmlElement("section"), leaf = new HtmlElement("span");
        top.appendChild(mid);
        mid.appendChild(leaf);
        assertSame(top, AttributeMap.treeTopBelow(mid, top, top));
        assertSame(top, AttributeMap.treeTopBelow(leaf, mid, top));
        // 親が違う（受け渡しの親と実際の親が別）ときは受け渡しを信じず根まで辿る
        HtmlElement other = new HtmlElement("p");
        assertSame(top, AttributeMap.treeTopBelow(leaf, other, other));
        // 子の並びから外した節は自分が根
        top.removeChild(mid);
        assertSame(mid, AttributeMap.treeTopBelow(mid, null, null));
        assertSame(mid, AttributeMap.treeTopBelow(leaf, mid, mid));
        assertNotSame(top, AttributeMap.treeTopBelow(mid, top, top));
    }

    @Test
    void sameAsMatchesNaiveComparison() {
        Random rnd = new Random(41);
        List<LayoutBox> pool = new ArrayList<>();
        try (StandaloneView v = new StandaloneView(doc(), W, H, FontResolver.FALLBACK)) {
            v.ui().flushInvalidation();
            collect(v.ui().layoutRoot(), pool);
        }
        assertTrue(pool.size() >= 200, "箱が少なすぎる " + pool.size());
        for (int iter = 0; iter < 400; iter++) {
            int n = iter < 10 ? iter : rnd.nextInt(140);
            CompactList<LayoutBox> list = new CompactList<>();
            LayoutBox[] mem = new LayoutBox[n + rnd.nextInt(5)];
            for (int i = 0; i < n; i++) {
                LayoutBox b = pool.get(rnd.nextInt(pool.size()));
                list.add(b);
                mem[i] = b;
            }
            assertTrue(list.sameAs(mem, n), "同じ並び n=" + n);
            assertTrue(CompactList.sameElements(list, mem, n));
            assertFalse(list.sameAs(mem, n + 1), "長さ違い（記憶が長い）");
            if (n > 0) {
                assertFalse(list.sameAs(mem, n - 1), "長さ違い（記憶が短い）");
                int k = rnd.nextInt(n);
                LayoutBox keep = mem[k];
                mem[k] = pool.get((pool.indexOf(keep) + 1) % pool.size());
                assertEquals(naive(list, mem, n), list.sameAs(mem, n), "1 箇所の置き換え n=" + n + " k=" + k);
                mem[k] = keep;
            }
            // 容量の余り（記憶の末尾の古い参照）は見ない
            if (mem.length > n) mem[n] = pool.get(0);
            assertTrue(list.sameAs(mem, n), "容量の余りは無視 n=" + n);
        }
        // 一般の List でも同じ答え
        List<LayoutBox> plain = new ArrayList<>(pool.subList(0, 5));
        assertTrue(CompactList.sameElements(plain, plain.toArray(new LayoutBox[0]), 5));
        assertFalse(CompactList.sameElements(plain, plain.toArray(new LayoutBox[0]), 4));
    }

    private static void collect(LayoutBox b, List<LayoutBox> out) {
        out.add(b);
        for (int i = 0, n = b.children.size(); i < n; i++) collect(b.children.get(i), out);
    }

    private static boolean naive(List<LayoutBox> list, LayoutBox[] mem, int n) {
        if (list.size() != n) return false;
        for (int i = 0; i < n; i++) if (list.get(i) != mem[i]) return false;
        return true;
    }

    private static String sig(DisplayItem i) {
        return i.id() + "|" + i.baseId() + "|" + i.type() + "|" + i.x() + "," + i.y() + "," + i.width() + "," + i.height() + "|" + i.text() + "|" + i.color() + "|" + i.bgColor();
    }

    private static void assertMatchesFresh(StandaloneView view, String step) {
        UiView ui = view.ui();
        ui.flushInvalidation();
        List<DisplayItem> actual = ui.renderCache();
        List<DisplayItem> expected = new DisplayListBuilder(W, H, ui.document().sheet.keyframes, ui.scrollState()).render(ui.layoutRoot(), false);
        assertEquals(expected.size(), actual.size(), step + ": 項目数");
        for (int i = 0; i < expected.size(); i++) assertEquals(sig(expected.get(i)), sig(actual.get(i)), step + ": 項目 " + i);
    }

    @Test
    void mutationSequenceMatchesFresh() {
        for (int seed = 1; seed <= 6; seed++) {
            Random rnd = new Random(seed);
            try (StandaloneView v = new StandaloneView(doc(), W, H, FontResolver.FALLBACK)) {
                assertMatchesFresh(v, "seed " + seed + " 初期");
                for (int step = 0; step < 25; step++) {
                    HtmlElement e = (HtmlElement) v.root().getNodeById("r" + rnd.nextInt(80));
                    switch (rnd.nextInt(4)) {
                        case 0 -> e.setAttribute("title", "t" + step);          // 非スタイル属性（根の版）
                        case 1 -> e.setAttribute("class", rnd.nextBoolean() ? "rel" : "");
                        case 2 -> e.setAttribute("style", "padding-left:" + rnd.nextInt(30) + "px");
                        default -> e.setAttribute("data-x", String.valueOf(step));
                    }
                    assertMatchesFresh(v, "seed " + seed + " step " + step);
                }
            }
        }
    }
}
