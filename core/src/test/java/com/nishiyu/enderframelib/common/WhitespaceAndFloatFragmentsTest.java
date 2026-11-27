package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** WPT の参照比較（A11）で見つけた、空白と float の扱いの不具合の回帰テスト。 */
class WhitespaceAndFloatFragmentsTest {
    private static final int W = 400, H = 300;

    private static TestBoxes.Box layout(String html) {
        UiDocumentCompiler c = new UiDocumentCompiler();
        Document doc = c.parseDocument("<style>body{margin:0}</style>" + html, "", W, H);
        return TestBoxes.root(c.layoutDocument(doc, W, H, Map.of(), new ScrollState(), FontResolver.FALLBACK));
    }

    private static TestBoxes.Box find(TestBoxes.Box box, String id) {
        if (id.equals(box.node.attrs.get("id"))) return box;
        for (TestBoxes.Box c : box.paintChildren()) {
            TestBoxes.Box h = find(c, id);
            if (h != null) return h;
        }
        return null;
    }

    /** 行が始まった後（空白の後）に置いた float の中身の断片が、行の配置で消されない。 */
    @Test
    void floatsAfterInlineWhitespaceKeepTheirText() {
        TestBoxes.Box root = layout("<div><span id=a style='float:left;width:60px'>first</span> <span id=b style='float:left;width:60px'>second</span>"
                + " <span id=c style='float:left;width:60px'>third</span></div>");
        for (String id : new String[]{"a", "b", "c"}) {
            TestBoxes.Box text = find(root, id).paintChildren().get(0);
            assertFalse(text.runs().isEmpty(), id + " の文字の断片が無い");
        }
    }

    /** flex 容器の子の空白だけのテキストは項目を作らない（column の並びに高さを足さない）。 */
    @Test
    void whitespaceBetweenFlexItemsIsNotAnItem() {
        TestBoxes.Box root = layout("<div style='display:flex;flex-direction:column'><div id=a style='height:10px'></div> <div id=b style='height:10px'></div></div>");
        assertEquals(find(root, "a").y() + 10, find(root, "b").y(), 0.01f);
    }

    /** 直前が空白で終わっていれば、次の run の先頭の空白は畳まれる（"a " + " b" は 1 つの空白）。 */
    @Test
    void spaceAfterSpaceAcrossElementsCollapses() {
        TestBoxes.Box two = layout("<div><b>a </b><span id=s> b</span></div>");
        TestBoxes.Box one = layout("<div><b>a </b><span id=s>b</span></div>");
        float x2 = find(two, "s").paintChildren().get(0).runs().get(0).x();
        float x1 = find(one, "s").paintChildren().get(0).runs().get(0).x();
        assertEquals(x1, x2, 0.01f);
    }
}
