package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.parser.HtmlParser;
import com.nishiyu.enderframelib.core.layout.model.ConstraintSpace;
import com.nishiyu.enderframelib.core.layout.model.ExternalEpochs;
import com.nishiyu.enderframelib.core.layout.model.FormattingContext;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.layout.model.Viewport;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A7（LayoutBox から測定・配置の記憶を MeasureMemo / PlacementMemo へ分離）の契約テスト。
 */
class MeasureMemoTest {

    private static LayoutContext ctx(boolean reuse, long run) {
        return new LayoutContext(new Viewport(400, 400), null, new ScrollState(),
                FontResolver.FALLBACK, reuse, run, true, null);
    }

    private static Path layoutDir() {
        Path direct = Path.of("src/main/java/com/nishiyu/enderframelib/core/layout");
        return Files.exists(direct.resolve("LayoutBox.java"))
                ? direct
                : Path.of("core/src/main/java/com/nishiyu/enderframelib/core/layout");
    }

    @Test
    void measureOutputsAndTentativeAreImmutableByType() {
        for (Class<?> cls : List.of(MeasureOutputs.class, MeasureKey.class, Tentative.class, ExternalEpochs.class)) {
            assertTrue(cls.isRecord(), cls.getSimpleName() + " must be an immutable record");
            for (Field f : cls.getDeclaredFields()) {
                assertTrue(Modifier.isFinal(f.getModifiers()),
                        cls.getSimpleName() + "." + f.getName() + " must be final");
            }
        }
    }

    @Test
    void onlyMeasureMemoAndPlacementMemoOwnTheirCacheFields() throws Exception {
        for (Class<?> owner : List.of(MeasureMemo.class, PlacementMemo.class)) {
            for (Field f : owner.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                assertTrue(Modifier.isPrivate(f.getModifiers()),
                        owner.getSimpleName() + "." + f.getName() + " must be private");
            }
        }

        Field mmField = LayoutBox.class.getDeclaredField("measureMemo");
        Field pmField = LayoutBox.class.getDeclaredField("placementMemo");
        assertFalse(Modifier.isPublic(mmField.getModifiers()), "measureMemo must be package-private");
        assertTrue(Modifier.isFinal(mmField.getModifiers()), "measureMemo must be final");
        assertFalse(Modifier.isPublic(pmField.getModifiers()), "placementMemo must be package-private");

        Set<String> removedMemoFields = Set.of(
                "measurementValid", "measuredSpace", "measuredStyle", "measuredPlaceholderStyle",
                "measuredText", "measuredContentHash", "measuredChildren", "measuredViewport",
                "measuredFonts", "measuredIntrinsics", "measuredEpoch", "measuredRun",
                "layoutChanged", "subtreeChanged", "placementValid", "placementPure", "keptPure",
                "lastLayoutX", "lastLayoutY", "lastLayoutOuterW", "lastLayoutOuterH",
                "lastScrollX", "lastScrollY", "lastLayoutML", "lastLayoutMR", "lastLayoutMT", "lastLayoutMB",
                "tentatives", "tentativeStash", "tentativeStashed", "tentativeStashedSpace", "tentativeStashedCtx",
                "outW", "outH", "everLaidOutAsTable", "hasFloatChild", "renderedLegend",
                "MEASUREMENT_EPOCH", "MEASUREMENTS_COMPUTED", "TENTATIVE_SKIPPED", "FUEL_REUSED",
                "LAYOUTS_SKIPPED", "LAYOUTS_SHIFTED"
        );
        for (Field f : LayoutBox.class.getDeclaredFields()) {
            assertFalse(removedMemoFields.contains(f.getName()),
                    "Legacy memo field still declared on LayoutBox: " + f.getName());
        }
    }

    @Test
    void layoutBoxGeometryFieldsAreClosedAndSizeUnder2000Lines() throws Exception {
        List<String> closedFields = List.of(
                "x", "y", "w", "h", "mT", "mR", "mB", "mL", "pT", "pR", "pB", "pL", "bT", "bR", "bB", "bL",
                "rowGap", "columnGap", "scrollWidth", "scrollHeight",
                "isAbsolute", "isFixed", "isRelative", "isSticky", "zIndex",
                "display", "displayText", "lineHeightPx", "shapedLines",
                "collapsedBorders", "columnRules", "firstLineBgRect",
                "stickyDy", "stickyUnstuckY", "stickyPlacedY",
                "isVerticalRoot", "isHorizontalReturnRoot", "isVerticalWrapper",
                "isOutsideMarker", "establishesBfc", "paintStamp", "fragmentsStale",
                "contentSpaceCache", "childSpaceCache", "subtreeHasContentAuto", "devicePixelRatio"
        );
        for (String name : closedFields) {
            Field f = LayoutBox.class.getDeclaredField(name);
            assertFalse(Modifier.isPublic(f.getModifiers()),
                    "LayoutBox." + name + " must not be public");
        }

        Field frags = LayoutBox.class.getDeclaredField("inlineFragments");
        assertTrue(Modifier.isPublic(frags.getModifiers()) && Modifier.isFinal(frags.getModifiers()),
                "inlineFragments must be public final");

        int pubMutCount = 0;
        for (Class<?> c : List.of(LayoutBox.class, LayoutBox.InlineFragment.class)) {
            for (Field f : c.getDeclaredFields()) {
                int m = f.getModifiers();
                if (Modifier.isPublic(m) && !Modifier.isStatic(m) && !Modifier.isFinal(m)) {
                    pubMutCount++;
                }
            }
        }
        assertTrue(pubMutCount <= 53, "pubMut expected <= 53, got " + pubMutCount);

        Path dir = layoutDir();
        long layoutBoxLines = Files.readAllLines(dir.resolve("LayoutBox.java")).size();
        long measureMemoLines = Files.readAllLines(dir.resolve("MeasureMemo.java")).size();
        long placementMemoLines = Files.readAllLines(dir.resolve("PlacementMemo.java")).size();
        assertTrue(layoutBoxLines <= 2000, "LayoutBox.java lines " + layoutBoxLines + " > 2000");
        assertTrue(measureMemoLines <= 800, "MeasureMemo.java lines " + measureMemoLines + " > 800");
        assertTrue(placementMemoLines <= 800, "PlacementMemo.java lines " + placementMemoLines + " > 800");
    }

    @Test
    void definiteLeafReuseCannotAliasOrCorruptCachedOutputs() {
        HtmlNode doc = HtmlParser.parse("<div id=p><div id=c></div></div>");
        LayoutBox flexParent = new LayoutBox(doc.getElementById("p"),
                new ComputedStyle(Map.of("display", "flex", "width", "400px")));
        LayoutBox leaf = new LayoutBox(doc.getElementById("c"),
                new ComputedStyle(Map.of("display", "block", "height", "24px")));
        flexParent.children.add(leaf);
        flexParent.elementChildren.add(leaf);
        flexParent.linkParents();

        FormattingContext flexCtx = FormattingContext.of(FormattingContext.Display.ROW, false, false);
        ConstraintSpace space = new ConstraintSpace(400f, Float.NaN, flexCtx);

        LayoutBox.markLayoutChanges(flexParent);
        leaf.measure(space.withFixed(140f, Float.NaN), ctx(false, 1));
        LayoutBox.clearAllSubtreeMarks(flexParent);
        assertEquals(140f, leaf.w(), 0.01f);
        assertEquals(24f, leaf.h(), 0.01f);

        // 別幅での測定と box フィールドの直接書き換えが、記憶済み 140px DEFINITE スロットを汚染しない
        leaf.measure(space.withFixed(220f, Float.NaN), ctx(false, 2));
        LayoutBox.clearAllSubtreeMarks(flexParent);
        assertEquals(220f, leaf.w(), 0.01f);
        leaf.w = 999f;
        leaf.h = 888f;

        long before = LayoutBox.measurementsComputed();
        leaf.measure(space.withFixed(140f, Float.NaN), ctx(true, 3));
        assertEquals(before, LayoutBox.measurementsComputed(), "DEFINITE slot must hit without re-measuring");
        assertEquals(140f, leaf.w(), 0.01f, "DEFINITE slot width must not be aliased");
        assertEquals(24f, leaf.h(), 0.01f, "DEFINITE slot height must not be aliased");
    }

    @Test
    void tentativeStashRestoreAndSettleLifecycle() {
        HtmlNode doc = HtmlParser.parse("<div id=p><div id=c></div></div>");
        LayoutBox flexParent = new LayoutBox(doc.getElementById("p"),
                new ComputedStyle(Map.of("display", "flex", "width", "400px")));
        LayoutBox child = new LayoutBox(doc.getElementById("c"),
                new ComputedStyle(Map.of("display", "block", "width", "50%", "height", "20px")));
        flexParent.children.add(child);
        flexParent.elementChildren.add(child);
        flexParent.linkParents();

        FormattingContext flexCtx = FormattingContext.of(FormattingContext.Display.ROW, false, false);
        ConstraintSpace space120 = new ConstraintSpace(120f, Float.NaN, flexCtx);
        ConstraintSpace space260 = new ConstraintSpace(260f, Float.NaN, flexCtx);

        LayoutBox.markLayoutChanges(flexParent);
        child.measure(space120, ctx(false, 1));
        LayoutBox.clearAllSubtreeMarks(flexParent);
        child.measure(space260, ctx(false, 2));
        LayoutBox.clearAllSubtreeMarks(flexParent);
        assertEquals(130f, child.w(), 0.01f);
        assertFalse(child.isTentativeStashed());

        assertTrue(child.measureTentatively(space120, ctx(true, 3)));
        assertTrue(child.isTentativeStashed());
        assertEquals(60f, child.w(), 0.01f);

        // 本測定を同幅で呼び直すと、stash が復元されたうえで主スロットと一致し、stashed は解除される
        child.measure(space260, ctx(true, 4));
        assertFalse(child.isTentativeStashed());
        assertEquals(130f, child.w(), 0.01f);

        // 再び仮適用したあと settleTentative() すると、stashed が解除されて仮測定幅で確定する
        assertTrue(child.measureTentatively(space120, ctx(true, 5)));
        assertTrue(child.isTentativeStashed());
        child.settleTentative();
        assertFalse(child.isTentativeStashed());
        assertEquals(60f, child.w(), 0.01f);
    }

    @Test
    void tentativeAndDefiniteSlotsEvictIndependently() {
        HtmlNode doc = HtmlParser.parse("<div id=p><div id=c></div></div>");
        LayoutBox flexParent = new LayoutBox(doc.getElementById("p"),
                new ComputedStyle(Map.of("display", "flex", "width", "400px")));
        LayoutBox leaf = new LayoutBox(doc.getElementById("c"),
                new ComputedStyle(Map.of("display", "block", "width", "50%", "height", "20px")));
        flexParent.children.add(leaf);
        flexParent.elementChildren.add(leaf);
        flexParent.linkParents();

        FormattingContext flexCtx = FormattingContext.of(FormattingContext.Display.ROW, false, false);
        ConstraintSpace baseSpace = new ConstraintSpace(300f, Float.NaN, flexCtx);

        LayoutBox.markLayoutChanges(flexParent);
        leaf.measure(baseSpace.withFixed(150f, Float.NaN), ctx(false, 1));
        LayoutBox.clearAllSubtreeMarks(flexParent);
        assertEquals(150f, leaf.w(), 0.01f);

        // TENTATIVE を上限(4枠)超えの 6 種類測って追い出しを発生させても DEFINITE 枠は消えない
        for (int i = 0; i < 6; i++) {
            float avail = 80f + i * 20f;
            leaf.measure(new ConstraintSpace(avail, Float.NaN, flexCtx), ctx(false, 2 + i));
        }
        LayoutBox.clearAllSubtreeMarks(flexParent);

        long before = LayoutBox.measurementsComputed();
        leaf.measure(baseSpace.withFixed(150f, Float.NaN), ctx(true, 10));
        assertEquals(before, LayoutBox.measurementsComputed(),
                "DEFINITE slot must survive 6 TENTATIVE evictions without re-measuring");
        assertEquals(150f, leaf.w(), 0.01f);
    }

    @Test
    void placementMemoIsLazyAndHandlesSkipShiftAndInvalidation() {
        HtmlNode doc = HtmlParser.parse("<div id=p><div id=c>hello</div></div>");
        HtmlNode pNode = doc.getElementById("p");
        HtmlNode cNode = doc.getElementById("c");
        HtmlNode textNode = cNode.children.get(0);

        LayoutBox parent = new LayoutBox(pNode, new ComputedStyle(Map.of("display", "block", "width", "400px")));
        LayoutBox child = new LayoutBox(cNode, new ComputedStyle(Map.of("display", "block", "width", "100px", "height", "40px")));
        LayoutBox textBox = new LayoutBox(textNode, child.style);
        child.children.add(textBox);
        parent.children.add(child);
        parent.elementChildren.add(child);
        parent.linkParents();

        // 配置前は遅延生成されず null
        assertNull(parent.placementMemo);
        assertNull(child.placementMemo);
        assertNull(textBox.placementMemo);
        assertFalse(child.holdsPlacement());

        LayoutContext c1 = ctx(true, 1);
        LayoutBox.markLayoutChanges(parent);
        parent.measure(400f, 400f, FormattingContext.BLOCK, c1);
        LayoutBox.clearAllSubtreeMarks(parent);
        parent.layout(0f, 0f, 400f, 400f, c1);

        // ブロック箱は確保されるが、fitToFragments のみに頼るテキスト箱は null（0 B）のまま
        assertNotNull(parent.placementMemo);
        assertNotNull(child.placementMemo);
        assertNull(textBox.placementMemo, "Text box placed via inline fragments must not allocate PlacementMemo");
        assertTrue(child.holdsPlacement());

        // 同入力での再配置は skip される
        long skippedBefore = LayoutBox.layoutsSkipped();
        child.layout(0f, 0f, 400f, 400f, ctx(true, 2));
        assertEquals(skippedBefore + 1, LayoutBox.layoutsSkipped(), "Same inputs must skip layout");

        // 整数デルタの親座標変化は shift で再利用される
        long shiftedBefore = LayoutBox.layoutsShifted();
        child.layout(25f, 15f, 400f, 400f, ctx(true, 3));
        assertEquals(shiftedBefore + 1, LayoutBox.layoutsShifted(), "Integer origin delta must shift layout");
        assertEquals(25f, child.x(), 0.01f);
        assertEquals(15f, child.y(), 0.01f);

        // invalidate 後は skip されない
        child.invalidateMeasurement();
        assertFalse(child.placementMemo.isValid());
        long skippedAfterInv = LayoutBox.layoutsSkipped();
        child.layout(25f, 15f, 400f, 400f, ctx(true, 4));
        assertEquals(skippedAfterInv, LayoutBox.layoutsSkipped(), "Invalidated box must not skip layout");
    }
}
