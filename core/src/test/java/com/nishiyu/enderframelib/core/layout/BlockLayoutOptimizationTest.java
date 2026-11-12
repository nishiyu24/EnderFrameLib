package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.layout.model.Viewport;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class BlockLayoutOptimizationTest {

    private static ComputedStyle defaultStyle() {
        return new ComputedStyle(Map.of("display", "block"));
    }

    @Test
    void isNotBlankFastMatchesStringIsBlank() {
        assertFalse(BlockLayoutEngine.isNotBlankFast(null));
        assertFalse(BlockLayoutEngine.isNotBlankFast(""));
        assertFalse(BlockLayoutEngine.isNotBlankFast("   "));
        assertFalse(BlockLayoutEngine.isNotBlankFast(" \t\r\n "));

        assertTrue(BlockLayoutEngine.isNotBlankFast("a"));
        assertTrue(BlockLayoutEngine.isNotBlankFast("  hello  "));
        assertTrue(BlockLayoutEngine.isNotBlankFast("x   "));
        assertTrue(BlockLayoutEngine.isNotBlankFast("   y"));
        assertTrue(BlockLayoutEngine.isNotBlankFast("12345"));

        // nbsp は Character.isWhitespace(0x00A0) == false なので isBlank() == false（非空白文字）
        assertTrue(BlockLayoutEngine.isNotBlankFast("\u00a0"));
        assertTrue(BlockLayoutEngine.isNotBlankFast(" \u00a0 "));

        // 全角空白（\u3000）
        assertFalse(BlockLayoutEngine.isNotBlankFast("\u3000"));
        assertTrue(BlockLayoutEngine.isNotBlankFast(" あ "));

        // 様々な文字列で !s.isBlank() と厳密に一致するか検証
        String[] samples = {
                "", " ", "  ", "\t", "\n", "\r\n", " a ", "a", "   b", "c   ",
                "hello world", " \u00a0 ", "　", "　abc　", "\n\t  x  \t\n"
        };
        for (String s : samples) {
            assertEquals(!s.isBlank(), BlockLayoutEngine.isNotBlankFast(s), "Mismatch for string: '" + s + "'");
        }
    }

    @Test
    void inlineFragmentReuseLifecycle() {
        HtmlNode node = new HtmlElement("div");
        ComputedStyle style = defaultStyle();
        LayoutBox box = new LayoutBox(node, style);

        // 1回目の配置パス: 断片を 3 つ追加
        box.startFragmentPass();
        LayoutBox.InlineFragment f1 = box.addFragment(10, 20, 30, 40, "hello", 15, 5, null, true, true);
        LayoutBox.InlineFragment f2 = box.addFragment(40, 20, 25, 40, "world", 15, 5, null, true, true);
        LayoutBox.InlineFragment f3 = box.addFragment(65, 20, 10, 40, "!", 15, 5, null, true, true);
        box.finishFragmentPass();

        assertEquals(3, box.inlineFragments.size());
        assertSame(f1, box.inlineFragments.get(0));
        assertSame(f2, box.inlineFragments.get(1));
        assertSame(f3, box.inlineFragments.get(2));
        assertEquals("hello", f1.text);
        assertEquals("world", f2.text);

        // 2回目の配置パス: 断片を 2 つだけ配置（3 つ目はトリムされること、1・2 つ目はインスタンスが再利用されること）
        box.startFragmentPass();
        LayoutBox.InlineFragment reused1 = box.addFragment(15, 25, 35, 45, "foo", 18, 6, null, true, false);
        LayoutBox.InlineFragment reused2 = box.addFragment(50, 25, 30, 45, "bar", 18, 6, null, false, true);
        box.finishFragmentPass();

        assertEquals(2, box.inlineFragments.size());
        assertSame(f1, reused1);
        assertSame(f2, reused2);
        assertEquals("foo", reused1.text);
        assertEquals(15f, reused1.x);
        assertEquals("bar", reused2.text);
        assertEquals(50f, reused2.x);
    }

    @Test
    void resetPlacementsKeepsFragmentObjectsForReuse() {
        LayoutBox box = new LayoutBox(new HtmlElement("div"), defaultStyle());
        box.startFragmentPass();
        LayoutBox.InlineFragment f1 = box.addFragment(1, 2, 3, 4, "a", 3, 1, null, true, true);
        LayoutBox.InlineFragment f2 = box.addFragment(5, 2, 3, 4, "b", 3, 1, null, true, true);
        box.finishFragmentPass();

        // 置き直し: 描画が古い断片を読まないよう空になるが、オブジェクトは控えに残る
        LayoutBox.resetPlacements(box, -1);
        assertTrue(box.inlineFragments.isEmpty());
        assertEquals(2, box.spareFragmentCount());

        // 次の配置パスは控えから取り、新しい値で上書きする
        box.startFragmentPass();
        LayoutBox.InlineFragment g1 = box.addFragment(10, 20, 30, 40, "x", 9, 2, null, false, true);
        LayoutBox.InlineFragment g2 = box.addFragment(50, 20, 30, 40, "y", 9, 2, null, true, false);
        LayoutBox.InlineFragment g3 = box.addFragment(90, 20, 30, 40, "z", 9, 2, null, true, true);
        box.finishFragmentPass();

        assertEquals(0, box.spareFragmentCount());
        assertEquals(3, box.inlineFragments.size());
        assertTrue((g1 == f1 || g1 == f2) && (g2 == f1 || g2 == f2) && g1 != g2);
        assertNotSame(f1, g3);
        assertNotSame(f2, g3);
        assertEquals("x", g1.text);
        assertEquals(10f, g1.x);
        assertFalse(g1.startEdge);
        assertEquals("y", g2.text);
        assertEquals(20f, g2.y);
        assertEquals("z", g3.text);
    }

    @Test
    void spareFragmentsAreBounded() {
        LayoutBox box = new LayoutBox(new HtmlElement("div"), defaultStyle());
        box.startFragmentPass();
        for (int i = 0; i < 100; i++) box.addFragment(i, 0, 1, 1, "t", 1, 0, null, true, true);
        box.finishFragmentPass();
        LayoutBox.resetPlacements(box, -1);
        assertTrue(box.spareFragmentCount() <= 16);
    }

    @Test
    void updateScrollExtentLeafNodeShortCircuit() {
        HtmlNode node = new HtmlElement("div");
        ComputedStyle style = defaultStyle();
        LayoutBox box = new LayoutBox(node, style);
        box.x = 10;
        box.y = 20;
        box.w = 100;
        box.h = 50;
        box.pL = 5;
        box.pR = 5;
        box.pT = 2;
        box.pB = 2;

        LayoutContext ctx = new LayoutContext(new Viewport(800, 600), null, new ScrollState(), FontResolver.FALLBACK);
        box.layout(10, 20, 100, 50, ctx);

        // 葉ノードでオーバーフローがない場合、箱の寸法がそのままスクロール範囲となる
        assertEquals(100f, box.scrollWidth);
        assertEquals(50f, box.scrollHeight);
    }

    @Test
    void childFlagsMaintenanceOnAddAndRemove() {
        HtmlNode rootNode = new HtmlElement("ul");
        ComputedStyle style = defaultStyle();
        LayoutBox root = new LayoutBox(rootNode, style);

        assertFalse(root.hasOutsideMarker);
        assertFalse(root.hasAbsoluteChild);

        // 通常の子
        HtmlNode childNode1 = new HtmlElement("li");
        LayoutBox child1 = new LayoutBox(childNode1, style);
        root.addChild(child1);
        assertFalse(root.hasOutsideMarker);
        assertFalse(root.hasAbsoluteChild);

        // 外側マーカーの子
        HtmlNode markerNode = new HtmlElement("span");
        LayoutBox marker = new LayoutBox(markerNode, style);
        marker.isOutsideMarker = true;
        root.addChild(marker);
        assertTrue(root.hasOutsideMarker);

        // 絶対配置の子
        HtmlNode absNode = new HtmlElement("div");
        LayoutBox abs = new LayoutBox(absNode, style);
        abs.isAbsolute = true;
        root.addChild(abs);
        assertTrue(root.hasAbsoluteChild);

        // clearChildren でフラグがリセットされること
        root.clearChildren();
        assertFalse(root.hasOutsideMarker);
        assertFalse(root.hasAbsoluteChild);
    }

    @Test
    void invalidateFloatsOnlyInvalidatesAncestors() {
        HtmlNode rootNode = new HtmlElement("div");
        ComputedStyle style = defaultStyle();
        LayoutBox root = new LayoutBox(rootNode, style);

        LayoutBox parent = new LayoutBox(new HtmlElement("div"), style);
        LayoutBox sibling = new LayoutBox(new HtmlElement("div"), style);
        root.addChild(parent);
        root.addChild(sibling);

        LayoutBox child = new LayoutBox(new HtmlElement("div"), style);
        parent.addChild(child);

        // 各ノードの floatMemo を初期化（擬似的に現在エポックを設定）
        int epoch = LayoutBox.shapeEpoch();
        root.floatsMemoEpoch = epoch;
        parent.floatsMemoEpoch = epoch;
        sibling.floatsMemoEpoch = epoch;
        child.floatsMemoEpoch = epoch;

        // parent に対して invalidateFloats を実行
        LayoutBox.invalidateFloats(parent);

        // parent とその祖先 root のメモは無効化される (-1)
        assertEquals(-1, parent.floatsMemoEpoch);
        assertEquals(-1, root.floatsMemoEpoch);

        // 兄弟ノード sibling や子ノード child のメモは保持される
        assertEquals(epoch, sibling.floatsMemoEpoch);
        assertEquals(epoch, child.floatsMemoEpoch);
    }
}
