package com.nishiyu.enderframelib.core.css;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** M1: 継承群の参照共有（{@code child.inherited == parent.inherited}）・DEFAULT 共有・値等価・カスタムプロパティ表の共有。 */
class StyleSharingTest {

    private static ComputedStyle cs(float parentFont, String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return ComputedStyle.of(m, parentFont, 16f);
    }

    @Test
    void childSharesParentInheritedGroupWhenNothingInheritedChanges() {
        Style parent = Style.of(cs(16f, "color", "red", "font-family", "serif"), null);
        // 子は継承するプロパティを親と同じ値で持ち、継承しないプロパティだけが違う
        Style child = Style.of(cs(16f, "color", "red", "font-family", "serif", "display", "block", "width", "10px"), parent);
        assertSame(parent.inherited, child.inherited);
        assertNotSame(parent.box, child.box);
        assertNotEquals(parent.box, child.box);
    }

    @Test
    void childDoesNotShareWhenAnInheritedValueChanges() {
        Style parent = Style.of(cs(16f, "color", "red"), null);
        Style child = Style.of(cs(16f, "color", "blue"), parent);
        assertNotSame(parent.inheritedPaint, child.inheritedPaint);
        assertNotEquals(parent.inheritedPaint, child.inheritedPaint);
        assertSame(parent.inherited, child.inherited, "描画だけの継承プロパティの違いはレイアウトの継承群を変えない（M2c）");
        assertEquals(0xFF0000FF, child.inheritedPaint.color);
        // フォントサイズの違いも継承群の違い
        assertNotEquals(Style.of(cs(16f), null).inherited, Style.of(cs(20f), null).inherited);
    }

    /** M2a: 解き直した結果の群が前回と同値なら前回のインスタンスへ寄せる（BoxTreeCompiler の継承の打ち切り）。 */
    @Test
    void shareEqualGroupsReusesPreviousInstances() {
        Style prev = Style.of(cs(16f, "color", "red", "font-size", "20px", "padding-top", "3px"), null);
        Style same = Style.of(cs(16f, "color", "red", "font-size", "20px", "padding-top", "3px"), null);
        assertNotSame(prev, same);
        assertSame(prev, same.shareEqualGroups(prev), "全体が同値なら前回そのもの");

        Style boxOnly = Style.of(cs(16f, "color", "red", "font-size", "20px", "padding-top", "9px"), null);
        Style shared = boxOnly.shareEqualGroups(prev);
        assertSame(prev.inherited, shared.inherited, "継承群は同値 → 前回の参照（子孫の作り直しが == で打ち切られる）");
        assertNotSame(prev.box, shared.box);
        assertEquals(boxOnly, shared);

        Style inherit = Style.of(cs(16f, "color", "blue", "font-size", "20px", "padding-top", "3px"), null);
        Style s2 = inherit.shareEqualGroups(prev);
        assertNotSame(prev.inheritedPaint, s2.inheritedPaint);
        assertSame(prev.inherited, s2.inherited);
        assertTrue(s2.sameLayout(prev), "色だけの違いはレイアウトに効く群が同じ（M2c）");
        assertFalse(boxOnly.shareEqualGroups(prev).sameLayout(prev));
        assertSame(prev.box, s2.box);
        assertSame(boxOnly, boxOnly.shareEqualGroups(null));
    }

    @Test
    void unchangedNonInheritedGroupsShareTheStaticDefault() {
        Style s = Style.of(cs(16f, "color", "red"), null);
        assertSame(BoxGeomStyle.DEFAULT, s.box);
        assertSame(VisualStyle.DEFAULT, s.visual);
        assertSame(FlexGridStyle.DEFAULT, s.flexGrid);
        assertSame(TableStyle.DEFAULT, s.table);
        assertSame(RareStyle.DEFAULT, s.rare);
        assertNull(s.custom);
        assertSame(InheritedStyle.DEFAULT, s.inherited);
        assertNotSame(InheritedPaintStyle.DEFAULT, s.inheritedPaint);
        assertSame(InheritedStyle.DEFAULT, Style.of(cs(16f), null).inherited);
        assertEquals(Style.DEFAULT, Style.of(cs(16f), null));
    }

    @Test
    void valueEqualityIgnoresInstanceIdentityAndHashesAgree() {
        Style a = Style.of(cs(16f, "display", "flex", "width", "calc(1px + 2px)", "color", "red"), null);
        Style b = Style.of(cs(16f, "display", "flex", "width", "calc(1px + 2px)", "color", "red"), null);
        assertNotSame(a, b);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals(a.box, b.box);
        assertEquals(a.box.hashCode(), b.box.hashCode());
        Style c = Style.of(cs(16f, "display", "flex", "width", "calc(1px + 3px)", "color", "red"), null);
        assertNotEquals(a, c);
        assertNotEquals(a.box, c.box);
    }

    @Test
    void customPropertiesExistOnlyWhereDeclaredAndAreSharedWithTheParent() {
        Style plain = Style.of(cs(16f, "color", "red"), null);
        assertNull(plain.custom);
        Style parent = Style.of(cs(16f, "--accent", "blue", "color", "red"), null);
        assertNotNull(parent.custom);
        assertEquals("blue", parent.custom.get("--accent"));
        Style child = Style.of(cs(16f, "--accent", "blue", "color", "red", "display", "block"), parent);
        assertSame(parent.custom, child.custom);
        Style other = Style.of(cs(16f, "--accent", "green", "color", "red"), parent);
        assertNotSame(parent.custom, other.custom);
        assertNotEquals(parent, other);
    }

    @Test
    void resolveUsesTheElementFontSizeAndRoot() {
        Style s = Style.of(cs(20f, "width", "2em", "margin-left", "10%", "padding-top", "calc(1em + 4px)"), null);
        assertEquals(40f, s.resolve(s.box.width, s.box.exprs, 100f, 800f, 600f), 0f);
        assertEquals(10f, s.resolve(s.box.marginLeft, s.box.exprs, 100f, 800f, 600f), 0f);
        assertEquals(24f, s.resolve(s.box.paddingTop, s.box.exprs, 100f, 800f, 600f), 0f);
    }

    @Test
    void typedMasksSafeFlagsAndDiffBehavePredictably() {
        ComputedStyle parent = cs(16f, "color", "red", "border-top-color", "blue", "background-color", "#123456",
                "justify-content", "safe center", "column-rule-color", "green");
        Style ps = parent.typed();
        assertFalse(ps.visual.currentColorMask == 31);
        assertEquals(0x1E, ps.visual.currentColorMask & 0x1F); // border-top declared; right/bottom/left/outline = currentcolor
        assertTrue(ps.visual.hasBackgroundColor);
        assertTrue(ps.flexGrid.justifyContentSafe);
        assertFalse(ps.visual.columnRuleColorCurrent);

        Style text = Style.forTextChild(ps);
        assertSame(ps.inherited, text.inherited);
        assertSame(ps.facts.fontStyle, text.facts.fontStyle);
        assertSame(BoxGeomStyle.DEFAULT, text.box);

        ComputedStyle a = cs(16f, "position", "relative", "left", "10px", "opacity", "0.5", "z-index", "1");
        ComputedStyle b = cs(16f, "position", "relative", "left", "20px", "opacity", "0.9", "z-index", "2");
        assertEquals(1, a.classifyPositionOnlyDiff(b)); // paint-only changes ignored in positionOnlyAxes
        ComputedStyle c = cs(16f, "position", "relative", "left", "20px", "margin-top", "4px");
        assertEquals(-1, a.classifyPositionOnlyDiff(c));
    }
}
