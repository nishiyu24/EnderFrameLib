package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.common.TestBoxes;

import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.paint.tree.PaintBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** {@code @scope (<根>) [to (<下限>)]}：根の子孫だけに当たり、下限の中は外れる。 */
public class ScopeRuleTest {

    private static final String PAGE = """
            <div class="card" id="c"><p id="p1">a</p>
              <div class="inner" id="i"><p id="p2">b</p></div></div>
            <p id="out">z</p>
            """;

    private static String color(PaintBox root, String id) {
        return style(root, id).get(CssProperty.COLOR);
    }

    @Test
    public void rulesApplyOnlyInsideTheScopeRoot() {
        PaintBox root = layout("<style>@scope (.card) { p { color: #111111; } }</style>" + PAGE);
        assertEquals("#111111", color(root, "p1"));
        assertEquals("#111111", color(root, "p2"), "入れ子の子孫にも当たる");
        assertNotEqualsColor("#111111", color(root, "out"));
        assertNotEqualsColor("#111111", color(root, "c"));
    }

    @Test
    public void scopeLimitExcludesTheLimitAndItsDescendants() {
        PaintBox root = layout("<style>@scope (.card) to (.inner) { p { color: #222222; } }</style>" + PAGE);
        assertEquals("#222222", color(root, "p1"));
        assertNotEqualsColor("#222222", color(root, "p2"));
    }

    @Test
    public void scopeKeywordAndBareDeclarationsTargetTheRoot() {
        PaintBox root = layout("<style>@scope (.card) { :scope { color: #333333; } p { background-color: #444444; } }</style>" + PAGE);
        assertEquals("#333333", color(root, "c"), ":scope は根");
        assertEquals("#444444", style(root, "p2").get(CssProperty.BACKGROUND_COLOR));

        PaintBox bare = layout("<style>@scope (.card) { color: #555555; }</style>" + PAGE);
        assertEquals("#555555", color(bare, "c"), "規則の外の宣言は根に当たる");
    }

    @Test
    public void scopedRulesHaveZeroSpecificityForTheScopeRoot() {
        // 根の詳細度（#c = id）は足されない：同じ要素の .t（クラス 1 つ）が勝つ
        PaintBox root = layout("""
                <style>
                  .t { color: #666666; }
                  @scope (#c) { p { color: #777777; } }
                </style>
                <div id="c"><p id="p" class="t">x</p></div>
                """);
        // 詳細度は（0,0,1）対（0,1,0）なので .t が勝つ
        assertEquals("#666666", color(root, "p"));
    }

    @Test
    public void nestingAndMediaWorkInsideScope() {
        PaintBox root = layout("""
                <style>
                  @scope (.card) {
                    .inner { color: #888888; & p { background-color: #999999; } }
                    @media (min-width: 100px) { p { border-top-color: #aaaaaa; } }
                  }
                </style>
                """ + PAGE);
        assertEquals("#888888", color(root, "i"));
        assertEquals("#999999", style(root, "p2").get(CssProperty.BACKGROUND_COLOR));
        assertEquals("#aaaaaa", style(root, "p1").get(CssProperty.BORDER_TOP_COLOR));
    }

    @Test
    public void invalidPreludeIsIgnored() {
        PaintBox root = layout("<style>@scope { p { color: #bbbbbb; } } @scope (.card) to { p { color: #cccccc; } }</style>" + PAGE);
        assertNotEqualsColor("#bbbbbb", color(root, "p1"));
        assertNotEqualsColor("#cccccc", color(root, "p1"));
    }

    @Test
    public void selectorListRootsAndLimits() {
        PaintBox root = layout("""
                <style>@scope (.card, .box) to (.inner, .skip) { p { color: #dddddd; } }</style>
                <div class="box"><p id="a">1</p><div class="skip"><p id="b">2</p></div></div>
                """ + PAGE);
        assertEquals("#dddddd", color(root, "a"));
        assertNotEqualsColor("#dddddd", color(root, "b"));
        assertEquals("#dddddd", color(root, "p1"));
        assertNotEqualsColor("#dddddd", color(root, "p2"));
    }

    private static void assertNotEqualsColor(String unexpected, String actual) {
        assertNotNull(actual == null ? "" : actual);
        if (unexpected.equals(actual)) throw new AssertionError("色が " + unexpected + " になってはいけない");
    }

    private static PaintBox layout(String html) {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument(html, "", 400, 300);
        return parser.layoutDocument(doc, 400, 300, Map.of(), new ScrollState(), FontResolver.FALLBACK);
    }

    private static ComputedStyle style(PaintBox root, String id) {
        PaintBox box = find(root, id);
        assertNotNull(box, "id=" + id + " のボックスが無い");
        return box.style();
    }

    private static PaintBox find(PaintBox box, String id) {
        if (box.node != null && id.equals(box.node.getId())) return box;
        for (PaintBox child : TestBoxes.elements(box)) {
            PaintBox hit = find(child, id);
            if (hit != null) return hit;
        }
        return null;
    }
}
