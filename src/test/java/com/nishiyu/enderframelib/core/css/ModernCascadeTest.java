package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutContext.ScrollState;
import com.nishiyu.enderframelib.core.layout.TextShaper.FontFace.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * R4 の構文・カスケード・条件付き規則・{@code @property}・{@code :has()} を、
 * 文書全体（パーサ → カスケード → 算出値）を通して固定する。
 */
public class ModernCascadeTest {

    private static final int VIEWPORT_WIDTH = 400;
    private static final int VIEWPORT_HEIGHT = 300;

    // ---- @layer

    @Test
    public void testUnlayeredRulesBeatLayeredRulesRegardlessOfSpecificity() {
        LayoutBox root = layout("""
                <style>
                  @layer base { #a { color: #111111; } }
                  .a { color: #222222; }
                </style>
                <div id="a" class="a">x</div>
                """);
        assertEquals("#222222", style(root, "a").get(CssProperty.COLOR));
    }

    @Test
    public void testLaterLayerBeatsEarlierLayerAndOrderIsFixedByFirstMention() {
        LayoutBox root = layout("""
                <style>
                  @layer reset, theme;
                  @layer theme { div { color: #333333; } }
                  @layer reset { #a { color: #444444; } }
                </style>
                <div id="a">x</div>
                """);
        assertEquals("#333333", style(root, "a").get(CssProperty.COLOR));
    }

    @Test
    public void testImportantInLayersReversesTheLayerOrder() {
        LayoutBox root = layout("""
                <style>
                  @layer first, second;
                  @layer first { div { color: #aaaaaa !important; } }
                  @layer second { div { color: #bbbbbb !important; } }
                  div { color: #cccccc !important; }
                </style>
                <div id="a">x</div>
                """);
        // !important では先の層が勝ち、層に入っていない規則が最も弱い
        assertEquals("#aaaaaa", style(root, "a").get(CssProperty.COLOR));
    }

    @Test
    public void testNestedAndAnonymousLayers() {
        LayoutBox root = layout("""
                <style>
                  @layer outer { @layer inner { div { color: #010101; } } div { color: #020202; } }
                  @layer { span { color: #030303; } }
                  span { color: #040404; }
                </style>
                <div id="a">x</div><span id="b">y</span>
                """);
        // 親の層に直接書いた規則は入れ子の層より強い
        assertEquals("#020202", style(root, "a").get(CssProperty.COLOR));
        assertEquals("#040404", style(root, "b").get(CssProperty.COLOR));
    }

    @Test
    public void testRevertLayerFallsBackToThePreviousLayer() {
        LayoutBox root = layout("""
                <style>
                  @layer a, b;
                  @layer a { div { color: #123456; } }
                  @layer b { div { color: revert-layer; } }
                </style>
                <div id="a">x</div>
                """);
        assertEquals("#123456", style(root, "a").get(CssProperty.COLOR));
    }

    // ---- CSS ネスト

    @Test
    public void testNestingWithAmpersandAndImplicitDescendant() {
        LayoutBox root = layout("""
                <style>
                  .card { color: #101010;
                    & .title { color: #202020; }
                    span { color: #303030; }
                    &:hover { color: #404040; }
                    .dark & { color: #505050; }
                  }
                </style>
                <div class="card" id="c"><p class="title" id="t">t</p><span id="s">s</span></div>
                <section class="dark"><div class="card" id="d">d</div></section>
                """);
        assertEquals("#101010", style(root, "c").get(CssProperty.COLOR));
        assertEquals("#202020", style(root, "t").get(CssProperty.COLOR));
        assertEquals("#303030", style(root, "s").get(CssProperty.COLOR));
        assertEquals("#505050", style(root, "d").get(CssProperty.COLOR));
    }

    @Test
    public void testNestedAtRulesAndSelectorListSpecificity() {
        LayoutBox root = layout("""
                <style>
                  .a, #b { width: 10px;
                    @media (min-width: 300px) { width: 20px; }
                  }
                  .a { width: 30px; }
                </style>
                <div class="a" id="x">x</div>
                """);
        // 入れ子の @media は外側の規則の宣言を条件付きで足す。後ろの .a が同じ詳細度で勝つ
        assertEquals("30px", style(root, "x").get(CssProperty.WIDTH));
    }

    // ---- @container

    @Test
    public void testContainerQueryUsesTheNearestContainerSize() {
        LayoutBox root = layout("""
                <style>
                  .wrap { container-type: inline-size; width: 300px; }
                  .item { color: #111111; }
                  @container (min-width: 250px) { .item { color: #222222; } }
                  @container (min-width: 350px) { .item { color: #333333; } }
                </style>
                <div class="wrap"><div class="item" id="i">x</div></div>
                """);
        assertEquals("#222222", style(root, "i").get(CssProperty.COLOR));
    }

    @Test
    public void testNamedContainerAndContainerUnits() {
        LayoutBox root = layout("""
                <style>
                  .outer { container: side / inline-size; width: 200px; }
                  .inner { container-type: inline-size; width: 100px; }
                  @container side (min-width: 150px) { .item { color: #abcdef; } }
                  .item { width: 50cqw; }
                </style>
                <div class="outer"><div class="inner"><div class="item" id="i">x</div></div></div>
                """);
        assertEquals("#abcdef", style(root, "i").get(CssProperty.COLOR));
        // cqw は最も近い（inline-size の）コンテナ = .inner の 100px の 50%
        assertEquals("50px", style(root, "i").get(CssProperty.WIDTH));
    }

    // ---- @property

    @Test
    public void testRegisteredPropertyInitialValueAndInheritance() {
        LayoutBox root = layout("""
                <style>
                  @property --gap { syntax: '<length>'; inherits: false; initial-value: 8px; }
                  .box { margin-left: var(--gap); }
                  .parent { --gap: 20px; }
                </style>
                <div class="parent"><div class="box" id="b">x</div></div>
                """);
        // inherits: false なので親の 20px は届かず、登録した初期値になる
        assertEquals("8px", style(root, "b").get(CssProperty.MARGIN_LEFT));
    }

    @Test
    public void testRegisteredLengthPropertyIsComputedToAbsolute() {
        LayoutBox root = layout("""
                <style>
                  @property --size { syntax: '<length>'; inherits: true; initial-value: 0px; }
                  div { font-size: 10px; --size: 2em; width: var(--size); }
                </style>
                <div id="a">x</div>
                """);
        assertEquals("20px", style(root, "a").get(CssProperty.WIDTH));
    }

    // ---- :has()

    @Test
    public void testHasMatchesByDescendantChildAndSiblingRelativeSelectors() {
        LayoutBox root = layout("""
                <style>
                  div:has(> p.hit) { color: #111111; }
                  div:has(span) { background-color: #222222; }
                  h1:has(+ h2) { margin-left: 7px; }
                </style>
                <div id="a"><p class="hit">p</p></div>
                <div id="b"><section><span>s</span></section></div>
                <div id="c"><section><p class="hit">deep</p></section></div>
                <h1 id="h">h</h1><h2>h2</h2>
                """);
        assertEquals("#111111", style(root, "a").get(CssProperty.COLOR));
        assertEquals("#222222", style(root, "b").get(CssProperty.BACKGROUND_COLOR));
        assertEquals("7px", style(root, "h").get(CssProperty.MARGIN_LEFT));
        assertEquals(false, "#111111".equals(style(root, "c").get(CssProperty.COLOR)));
    }

    // ---- helpers

    private static LayoutBox layout(String html) {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        UiDocumentCompiler.Document doc = parser.parseDocument(html, "", VIEWPORT_WIDTH, VIEWPORT_HEIGHT);
        return parser.layoutDocument(doc, VIEWPORT_WIDTH, VIEWPORT_HEIGHT, Map.of(), new ScrollState(),
                FontResolver.FALLBACK);
    }

    private static ComputedStyle style(LayoutBox root, String id) {
        LayoutBox box = find(root, id);
        assertNotNull(box, "id=" + id + " のボックスが無い");
        return box.style;
    }

    private static LayoutBox find(LayoutBox box, String id) {
        if (box.node != null && id.equals(box.node.getId())) return box;
        for (LayoutBox child : box.elementChildren) {
            LayoutBox hit = find(child, id);
            if (hit != null) return hit;
        }
        return null;
    }
}