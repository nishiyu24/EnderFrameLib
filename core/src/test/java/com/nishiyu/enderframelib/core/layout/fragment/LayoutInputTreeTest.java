package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.box.StyleSource;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2 つの木（M2c ①。計画書 §3.4.2-3）: 描画だけの変化ではレイアウトの入力の木（{@link BoxNode#layout}）が前回の参照のままで、
 * レイアウトは何も整形しない。最新の木は最新の全ての群を持ち、ゼロから作った木と同じ算出スタイルになる。2 つの木は同じ形。
 */
class LayoutInputTreeTest {

    @Test
    void paintOnlyChangeKeepsTheLayoutTreeAndFragments() {
        SpikeView v = new SpikeView(SpikeView.largeList(60), 640, 480);
        LayoutFragment f0 = v.frame();
        BoxNode p0 = v.boxRoot, l0 = p0.layout;
        assertSame(p0, l0, "変化の無い木は 2 つの木が全ての節を共有する");

        ((HtmlElement) v.byId("item-7")).setAttribute("style", "color:#123456;background:#abcdef;opacity:.5;z-index:3");
        LayoutFragment f1 = v.frame();
        assertNotSame(p0, v.boxRoot, "最新の木は描画だけの変化でも新しい根");
        assertSame(l0, v.boxRoot.layout, "レイアウトの入力の木は前回の参照のまま");
        assertSame(f0, f1, "整形しない（根の断片がそのまま返る）");
        assertEquals(0, v.cacheStats().layoutComputes(), v.cacheStats().toString());

        BoxNode latest = find(v.boxRoot, v.byId("item-7")), input = find(v.boxRoot.layout, v.byId("item-7"));
        assertNotNull(latest);
        assertEquals(0xFF123456, latest.computed.inheritedPaint.color, "最新の木は新しい色");
        assertTrue(latest.computed.visual.hasBackgroundColor);
        assertSame(input, latest.layout, "最新の箱のレイアウトの入力は前回の箱");
        assertTrue(input.computed.inheritedPaint.color != 0xFF123456, "レイアウトの入力の箱の描画だけの群は古いまま（レイアウトは読まない）");
        assertAligned(v);

        // 続けて別の項目の寸法を変える: 整形するが、色だけ変えた項目のレイアウトの入力の箱は前回のまま
        ((HtmlElement) v.byId("item-20")).setAttribute("style", "padding:9px");
        v.frame();
        assertNotSame(l0, v.boxRoot.layout);
        assertSame(input, find(v.boxRoot.layout, v.byId("item-7")), "寸法の変化は色だけの項目のレイアウトの入力を作り直さない");
        assertEquals(SpikeView.dump(v.scratch()), SpikeView.dump(v.layout.root()));
        assertAligned(v);
    }

    /** 計画書 §3.4.2-3 の実測の場面: {@code varied-list} の hover（{@code li:hover} の色だけ）はレイアウトを 1 つも整形しない。 */
    @Test
    void hoverColorDoesNoLayoutWork() {
        SpikeView v = new SpikeView(SpikeView.variedList(120), 640, 480);
        LayoutFragment f0 = v.frame();
        for (String id : new String[]{"item-7", "item-8", "item-60"}) {
            BoxNode l0 = v.boxRoot.layout;
            v.state.setHoveredNode(v.byId(id));
            LayoutFragment f1 = v.frame();
            assertSame(l0, v.boxRoot.layout, id);
            assertSame(f0, f1, id);
            assertEquals(0, v.cacheStats().layoutComputes(), id + " " + v.cacheStats());
            assertEquals(0xFFCC0000, find(v.boxRoot, v.byId(id)).computed.inheritedPaint.color, id);
        }
        assertAligned(v);
    }

    @Test
    void inheritedPaintOnTheRootRestylesEveryLatestBoxButNoLayout() {
        SpikeView v = new SpikeView(SpikeView.inlineText(12), 640, 480);
        LayoutFragment f0 = v.frame();
        BoxNode l0 = v.boxRoot.layout;
        ((HtmlElement) byTag(v.doc.root, "body")).setAttribute("style", "color:#00ff00;text-shadow:1px 1px red");
        LayoutFragment f1 = v.frame();
        assertSame(l0, v.boxRoot.layout);
        assertSame(f0, f1);
        // 継承した色は文字の節まで届く（最新の木の全ての文字の箱）
        List<BoxNode> texts = new ArrayList<>();
        collectText(v.boxRoot, texts);
        assertTrue(texts.size() > 10);
        for (BoxNode t : texts) assertEquals(0xFF00FF00, t.computed.inheritedPaint.color, t.toString());
        assertAligned(v);
    }

    @Test
    void layoutChangeThenPaintOnlyBackKeepsAligned() {
        SpikeView v = new SpikeView(SpikeView.inlineText(10).replace("</style>",
                " .pb::before{content:'[x]';color:#900} li{list-style:decimal}</style>").replace("</body>",
                "<ol id=ol><li id=l1>a</li><li id=l2>b</li></ol></body>"), 640, 480);
        v.frame();
        HtmlNode p = v.byId("p1");
        ((HtmlElement) p).setAttribute("class", "pb");
        v.frame();
        BoxNode l1 = v.boxRoot.layout;
        // 疑似要素の持ち主の色だけを変える: 疑似要素の箱も最新の木では新しく、レイアウトの入力は前回のまま
        ((HtmlElement) p).setAttribute("style", "color:blue");
        v.frame();
        assertSame(l1, v.boxRoot.layout);
        // マーカーの色
        ((HtmlElement) v.byId("l2")).setAttribute("style", "color:red");
        v.frame();
        assertSame(l1, v.boxRoot.layout);
        assertEquals(0xFFFF0000, find(v.boxRoot, v.byId("l2")).marker.computed.inheritedPaint.color);
        assertAligned(v);
    }

    /** 描画だけの変化と寸法の変化を混ぜた乱択: 断片は全再計算と一致し、2 つの木は揃い、最新の木はゼロから作った木と同じスタイル。 */
    @Test
    void randomPaintAndLayoutMutationsStayConsistent() {
        SpikeView v = new SpikeView(SpikeView.nestedFlex(4, 3, true).replace("</style>",
                " .pb::before{content:'*';color:#900} .x{display:table-cell} .g:hover{color:red}</style>"), 640, 480);
        v.frame();
        Random rnd = new Random(20261125);
        for (int step = 0; step < 120; step++) {
            List<HtmlNode> elems = new ArrayList<>();
            collectElements(v.doc.root, elems);
            HtmlElement target = (HtmlElement) elems.get(rnd.nextInt(elems.size()));
            BoxNode beforeL = v.boxRoot.layout;
            boolean paintOnly = rnd.nextInt(3) > 0;
            String op;
            if (paintOnly) {
                op = PAINT[rnd.nextInt(PAINT.length)];
                // 前の手順の寸法の宣言は残し、描画だけの宣言を後ろに足す（置き換えると寸法の宣言が消えてレイアウトが変わる）
                String old = target.getAttribute("style");
                target.setAttribute("style", (old == null || old.isEmpty() ? "" : old + ";") + op);
            } else {
                op = IncrementalOracleTest.mutate(v, rnd, step);
            }
            LayoutFragment inc = v.frame();
            if (paintOnly) assertSame(beforeL, v.boxRoot.layout, "step " + step + " " + op + " は描画だけ");
            assertEquals(SpikeView.dump(v.scratch()), SpikeView.dump(inc), "step " + step + " " + op);
            assertAligned(v);
        }
    }

    private static final String[] PAINT = {"color:#a00", "background:#0a0", "opacity:.4", "outline:2px solid red",
            "box-shadow:2px 2px 3px #000", "transform:rotate(3deg)", "text-decoration:underline", "cursor:pointer", "z-index:2",
            "border-color:blue", "border-radius:4px", "visibility:visible", ""};

    /** 最新の木がゼロから作った木と同じ形・同じ算出スタイルで、レイアウトの入力の木とも同じ形か。 */
    private static void assertAligned(SpikeView v) {
        FragmentPipeline fresh = new FragmentPipeline(new StyleSource(v.doc.sheet, v.state, null, v.vw, v.vh),
                new LayoutSession(FontResolver.FALLBACK));
        fresh.frame(v.doc.root, null, v.vw, v.vh);
        aligned(v.boxRoot, v.boxRoot.layout, fresh.boxRoot(), "root");
    }

    private static void aligned(BoxNode p, BoxNode l, BoxNode z, String path) {
        assertSame(l, l.layout, path + ": レイアウトの入力の箱は自分自身を指す");
        assertSame(p.node, z.node, path);
        assertSame(p.node, l.node, path);
        assertEquals(z.kind, p.kind, path);
        assertEquals(z.kind, l.kind, path);
        assertEquals(z.pseudo, p.pseudo, path);
        assertEquals(z.text, p.text, path);
        assertEquals(z.computed, p.computed, path + ": 最新の木の算出スタイルはゼロから作った木と同じ");
        assertTrue(l.computed.sameLayout(p.computed), path + ": レイアウトに効く群は同じ");
        assertEquals(z.flow.size(), p.flow.size(), path);
        assertEquals(p.flow.size(), l.flow.size(), path);
        for (int i = 0; i < p.flow.size(); i++) {
            BoxNode pc = p.flow.get(i), lc = l.flow.get(i);
            if (pc.pseudo != BoxNode.Pseudo.ANONYMOUS && pc.pseudo != BoxNode.Pseudo.ORTHOGONAL) {
                assertSame(pc.layout, lc, path + "/" + i + ": 最新の箱のレイアウトの入力はレイアウトの入力の木の同じ位置の箱");
            }
            aligned(pc, lc, z.flow.get(i), path + "/" + i);
        }
        if (z.outsideMarker() != null) aligned(p.marker, l.marker, z.marker, path + "/marker");
    }

    private static HtmlNode byTag(HtmlNode n, String tag) {
        if (tag.equals(n.tag)) return n;
        for (HtmlNode c : n.children) {
            HtmlNode r = byTag(c, tag);
            if (r != null) return r;
        }
        return null;
    }

    private static BoxNode find(BoxNode b, HtmlNode n) {
        if (b.node == n && b.pseudo == BoxNode.Pseudo.NONE) return b;
        for (int i = 0; i < b.flow.size(); i++) {
            BoxNode r = find(b.flow.get(i), n);
            if (r != null) return r;
        }
        return null;
    }

    private static void collectText(BoxNode b, List<BoxNode> out) {
        if (b.text != null && b.pseudo == BoxNode.Pseudo.NONE) out.add(b);
        for (int i = 0; i < b.flow.size(); i++) collectText(b.flow.get(i), out);
    }

    private static void collectElements(HtmlNode n, List<HtmlNode> out) {
        if (n instanceof HtmlElement && n.parent != null && !"html".equals(n.tag) && !"head".equals(n.tag)) out.add(n);
        for (HtmlNode c : n.children) {
            if (!"head".equals(c.tag) && !"style".equals(c.tag)) collectElements(c, out);
        }
    }
}
