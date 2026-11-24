package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.HtmlText;
import com.nishiyu.enderframelib.core.dom.parser.HtmlParser;
import com.nishiyu.enderframelib.core.kernel.Shortcut;
import com.nishiyu.enderframelib.core.layout.model.ConstraintSpace;
import com.nishiyu.enderframelib.core.layout.model.FormattingContext;
import com.nishiyu.enderframelib.core.layout.model.IntrinsicSizeResolver;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.layout.model.Viewport;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.BitmapFontFace;
import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C3（読み取り集合駆動の測定キー・一般化された子読みカットオフ・旧ヒューリスティックの撤去）の契約テスト。
 */
class MeasureReadSetTest {

    private static LayoutContext ctx(Viewport vp, FontResolver fonts, IntrinsicSizeResolver intrinsics, long run) {
        return new LayoutContext(vp, intrinsics, new ScrollState(), fonts, true, run, true, null);
    }

    private static LayoutBox boxTree(String html) {
        HtmlNode doc = HtmlParser.parse(html);
        return buildBoxes(doc, null);
    }

    private static LayoutBox buildBoxes(HtmlNode node, ComputedStyle parentStyle) {
        ComputedStyle style = node.nodeType == com.nishiyu.enderframelib.core.dom.node.NodeType.TEXT
                ? (parentStyle != null ? parentStyle : new ComputedStyle(Map.of()))
                : styleForTag(node);
        LayoutBox box = new LayoutBox(node, style);
        for (HtmlNode child : node.children) {
            box.children.add(buildBoxes(child, style));
        }
        box.linkParents();
        return box;
    }

    private static ComputedStyle styleForTag(HtmlNode node) {
        String s = node.attrs.get("style");
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        if (s != null && !s.isBlank()) {
            for (String decl : s.split(";")) {
                int colon = decl.indexOf(':');
                if (colon > 0) map.put(decl.substring(0, colon).trim().toLowerCase(), decl.substring(colon + 1).trim());
            }
        }
        if ("span".equals(node.tag) && !map.containsKey("display")) map.put("display", "inline");
        else if (!map.containsKey("display") && node.tag != null && !"img".equals(node.tag)) map.put("display", "block");
        float fontSize = ComputedStyle.DEFAULT_FONT_SIZE;
        String fs = map.get("font-size");
        if (fs != null && fs.endsWith("px")) {
            fontSize = Float.parseFloat(fs.substring(0, fs.length() - 2).trim());
        }
        return new ComputedStyle(map, fontSize, ComputedStyle.DEFAULT_FONT_SIZE);
    }

    private static LayoutBox findById(LayoutBox b, String id) {
        if (b.node != null && id.equals(b.node.attrs.get("id"))) return b;
        for (LayoutBox c : b.children) {
            LayoutBox hit = findById(c, id);
            if (hit != null) return hit;
        }
        return null;
    }

    private static List<String> signatures(StandaloneView view) {
        view.ui().flushInvalidation();
        List<String> out = new ArrayList<>();
        for (DisplayItem i : view.ui().renderCache()) {
            out.add(i.id() + "|" + i.type() + "|" + i.x() + "," + i.y() + "," + i.width() + "," + i.height()
                    + "|" + i.text() + "|" + i.color() + "|" + i.bgColor());
        }
        return out;
    }

    private static HtmlText firstText(StandaloneView v, String id) {
        HtmlNode el = v.root().getNodeById(id);
        for (HtmlNode c : el.children) if (c instanceof HtmlText t) return t;
        throw new IllegalStateException("no text node under " + id);
    }

    @Test
    void externalEpochsInvalidateOnMeasurementFontImageAndShapeChanges() {
        // 1. Same IntrinsicSizeResolver instance whose resolveGeneration() advances invalidates without invalidateAllMeasurements()
        AtomicLong intrGen = new AtomicLong(1);
        AtomicInteger imgW = new AtomicInteger(40);
        IntrinsicSizeResolver intrinsics = new IntrinsicSizeResolver() {
            @Override public int[] getSize(String src) { return new int[]{imgW.get(), 20}; }
            @Override public long resolveGeneration() { return intrGen.get(); }
        };

        // 2. Same FontResolver instance whose resolveGeneration() advances invalidates without invalidateAllMeasurements()
        AtomicLong fontGen = new AtomicLong(1);
        AtomicInteger charAdvance = new AtomicInteger(6);
        FontResolver fonts = new FontResolver() {
            @Override public FontFace resolve(String fontFamily) {
                int adv = charAdvance.get();
                return new BitmapFontFace() {
                    @Override protected float naturalAdvance(String text, boolean bold) {
                        return text == null ? 0 : text.length() * adv;
                    }
                };
            }
            @Override public long resolveGeneration() { return fontGen.get(); }
        };

        LayoutBox root = boxTree(
                "<div id='r' style='width:300px'>"
                        + "<img id='im' src='https://example.com/a.png' style='display:inline-block'>"
                        + "<div id='txBox' style='display:inline-block;font-size:9px'>abcd</div>"
                        + "<div id='flBox' style='width:200px;overflow-x:hidden;overflow-y:hidden'><div style='float:left;width:30px;height:10px'></div>hi</div>"
                        + "<div id='cleanBox' style='width:120px;height:40px'>fixed</div>"
                        + "</div>");
        LayoutBox im = findById(root, "im");
        LayoutBox txBox = findById(root, "txBox");
        LayoutBox flBox = findById(root, "flBox");
        LayoutBox cleanBox = findById(root, "cleanBox");

        Viewport vp = new Viewport(400, 400);
        ConstraintSpace rootSpace = ConstraintSpace.block(400, 400);

        LayoutBox.markLayoutChanges(root);
        root.measure(rootSpace, ctx(vp, fonts, intrinsics, 1));
        LayoutBox.clearAllSubtreeMarks(root);
        assertEquals(40f, im.w(), 0.01f);
        assertEquals(24f, txBox.w(), 0.01f);

        // Unchanged generations hit memo
        long beforeHit = LayoutBox.measurementsComputed();
        root.measure(rootSpace, ctx(vp, fonts, intrinsics, 2));
        assertEquals(beforeHit, LayoutBox.measurementsComputed());

        // Advancing IntrinsicSizeResolver generation invalidates im
        intrGen.incrementAndGet();
        imgW.set(72);
        long beforeIntr = LayoutBox.measurementsComputed();
        root.measure(rootSpace, ctx(vp, fonts, intrinsics, 3));
        assertTrue(LayoutBox.measurementsComputed() > beforeIntr);
        assertEquals(72f, im.w(), 0.01f);

        // Advancing FontResolver generation invalidates text measurement
        fontGen.incrementAndGet();
        charAdvance.set(10);
        root.measure(rootSpace, ctx(vp, fonts, intrinsics, 4));
        assertEquals(40f, txBox.w(), 0.01f);

        // bumpShapeEpoch() invalidates float-bearing box but keeps float-free sibling valid
        assertTrue((flBox.measureMemo.readMask() & MeasureReads.READ_SHAPE_EPOCH) != 0);
        assertEquals(0, cleanBox.measureMemo.readMask() & MeasureReads.READ_SHAPE_EPOCH);
        LayoutBox.bumpShapeEpoch();
        long cFl = LayoutBox.measurementsComputed();
        flBox.measure(flBox.measureMemo.measuredSpace(), ctx(vp, fonts, intrinsics, 5));
        assertTrue(LayoutBox.measurementsComputed() > cFl);

        long cClean = LayoutBox.measurementsComputed();
        cleanBox.measure(cleanBox.measureMemo.measuredSpace(), ctx(vp, fonts, intrinsics, 6));
        assertEquals(cClean, LayoutBox.measurementsComputed(), "Float-free box must not re-measure on bumpShapeEpoch");

        // Global invalidation forces re-measure even on clean fixed box
        LayoutBox.invalidateAllMeasurements();
        cleanBox.measure(cleanBox.measureMemo.measuredSpace(), ctx(vp, fonts, intrinsics, 7));
        assertTrue(LayoutBox.measurementsComputed() > cClean);
    }

    @Test
    void inputReadSetPerturbationMatchesEveryActualDependency() {
        Viewport baseVp = new Viewport(400, 300);
        LayoutContext baseCtx = ctx(baseVp, FontResolver.FALLBACK, null, 1);

        LayoutBox root = boxTree(
                "<div id='r' style='width:400px;height:300px'>"
                        + "<div id='fixed' style='display:block;width:120px;height:40px'></div>"
                        + "<div id='pctW' style='display:block;width:50%;height:40px'></div>"
                        + "<div id='pctH' style='display:block;width:120px;height:50%'></div>"
                        + "<div id='vwBox' style='display:block;width:20vw;height:40px'></div>"
                        + "<div id='vhBox' style='display:block;width:120px;height:10vh'></div>"
                        + "<div id='colAuto' style='display:block'></div>"
                        + "<div id='fixedPos' style='position:fixed;width:50%;height:20px'></div>"
                        + "</div>");
        root.measure(ConstraintSpace.block(400, 300), baseCtx);

        LayoutBox fixed = findById(root, "fixed");
        LayoutBox pctW = findById(root, "pctW");
        LayoutBox pctH = findById(root, "pctH");
        LayoutBox vwBox = findById(root, "vwBox");
        LayoutBox vhBox = findById(root, "vhBox");
        LayoutBox colAuto = findById(root, "colAuto");
        LayoutBox fixedPos = findById(root, "fixedPos");

        // Fixed 120x40 block reads zero optional external axes and reuses under all perturbations
        assertEquals(0, fixed.measureMemo.readMask() & (MeasureReads.READ_AVAIL_INLINE | MeasureReads.READ_PERCENT_BLOCK
                | MeasureReads.READ_SHRINK | MeasureReads.READ_STRETCH
                | MeasureReads.READ_VIEWPORT_W | MeasureReads.READ_VIEWPORT_H));
        long c = LayoutBox.measurementsComputed();
        fixed.measure(new ConstraintSpace(250, 150, FormattingContext.of(FormattingContext.Display.BLOCK, true, true)),
                ctx(new Viewport(640, 480), FontResolver.FALLBACK, null, 2));
        assertEquals(c, LayoutBox.measurementsComputed(), "Fixed 120x40 box must reuse across unread input changes");

        // width: 50% records READ_AVAIL_INLINE and invalidates when avail changes
        assertTrue((pctW.measureMemo.readMask() & MeasureReads.READ_AVAIL_INLINE) != 0);
        c = LayoutBox.measurementsComputed();
        pctW.measure(ConstraintSpace.block(200, 300), ctx(baseVp, FontResolver.FALLBACK, null, 3));
        assertEquals(c + 1, LayoutBox.measurementsComputed());
        assertEquals(100f, pctW.w(), 0.01f);

        // width: 50% also records READ_SHRINK and invalidates when isShrink flips
        pctW.measure(new ConstraintSpace(200, 300, FormattingContext.of(FormattingContext.Display.BLOCK, true, true)),
                ctx(baseVp, FontResolver.FALLBACK, null, 4));
        assertEquals(c + 2, LayoutBox.measurementsComputed());
        assertEquals(0f, pctW.w(), 0.01f);

        // height: 50% records READ_PERCENT_BLOCK and invalidates when percentageBlockSize changes
        assertTrue((pctH.measureMemo.readMask() & MeasureReads.READ_PERCENT_BLOCK) != 0);
        c = LayoutBox.measurementsComputed();
        pctH.measure(ConstraintSpace.block(400, 200), ctx(baseVp, FontResolver.FALLBACK, null, 5));
        assertEquals(c + 1, LayoutBox.measurementsComputed());
        assertEquals(100f, pctH.h(), 0.01f);

        // viewport units record READ_VIEWPORT_W | READ_VIEWPORT_H
        assertTrue((vwBox.measureMemo.readMask() & MeasureReads.READ_VIEWPORT_W) != 0);
        assertTrue((vhBox.measureMemo.readMask() & MeasureReads.READ_VIEWPORT_H) != 0);
        assertTrue((fixedPos.measureMemo.readMask() & MeasureReads.READ_VIEWPORT_W) != 0);
        c = LayoutBox.measurementsComputed();
        vwBox.measure(vwBox.measureMemo.measuredSpace(), ctx(new Viewport(500, 300), FontResolver.FALLBACK, null, 6));
        assertEquals(c + 1, LayoutBox.measurementsComputed());
        assertEquals(100f, vwBox.w(), 0.01f);
        vhBox.measure(vhBox.measureMemo.measuredSpace(), ctx(new Viewport(400, 500), FontResolver.FALLBACK, null, 7));
        assertEquals(c + 2, LayoutBox.measurementsComputed());
        assertEquals(50f, vhBox.h(), 0.01f);

        // flex-direction: column child records READ_STRETCH and invalidates on stretch flip
        FormattingContext colStretch = FormattingContext.of(FormattingContext.Display.COLUMN, true, false);
        FormattingContext colNoStretch = FormattingContext.of(FormattingContext.Display.COLUMN, false, false);
        colAuto.measure(new ConstraintSpace(200, 300, colStretch), ctx(baseVp, FontResolver.FALLBACK, null, 8));
        assertTrue((colAuto.measureMemo.readMask() & MeasureReads.READ_STRETCH) != 0);
        c = LayoutBox.measurementsComputed();
        colAuto.measure(new ConstraintSpace(200, 300, colNoStretch), ctx(baseVp, FontResolver.FALLBACK, null, 9));
        assertEquals(c + 1, LayoutBox.measurementsComputed());
        assertEquals(LayoutBox.SizingMode.SHRINK_TO_FIT, colAuto.sizingMode);
    }

    @Test
    void childReadSetCutoffHandlesBaselineMinContentContentExtentAndStyleChanges() {
        // 1. Baseline-aligned flex item (with nested block wrapper): same baseline cuts off; changed baseline invalidates; multi-frame baseline stays tracked
        String baselineHtml = "<style>body{margin:0;font-size:12px}"
                + ".row{display:flex;align-items:baseline;width:300px}"
                + ".it{width:90px;height:32px;overflow:hidden}"
                + ".in{height:24px;overflow:hidden}</style>"
                + "<div class=row><div class=it id=it><div class=in><span id=t style='font-size:%dpx'>%s</span></div></div>"
                + "<div style='width:60px;height:40px'>ref</div></div>";
        StandaloneView v = new StandaloneView(String.format(baselineHtml, 12, "hi"), 320, 200, FontResolver.FALLBACK);
        signatures(v);

        // Frame 2: same font-size -> same baseline & outer -> must cut off (parent row not re-measured)
        LayoutBox rowBox = findById(v.ui().layoutRoot(), "it").parent;
        long beforeCutoff = LayoutBox.measurementsComputed();
        firstText(v, "t").setData("ok");
        List<String> f2 = signatures(v);
        long measuredCutoff = LayoutBox.measurementsComputed() - beforeCutoff;
        assertEquals(f2, signatures(new StandaloneView(String.format(baselineHtml, 12, "ok"), 320, 200, FontResolver.FALLBACK)));
        assertTrue(measuredCutoff <= 3, "Baseline-unchanged edit must cut off without re-measuring parent row: " + measuredCutoff + " valid=" + rowBox.measureMemo.isValid());

        // Frame 3 (immediately AFTER a cutoff frame!): change font-size -> baseline shifts even though box is 90x32 overflow:hidden -> must invalidate parent!
        ((HtmlElement) v.root().getNodeById("t")).setAttribute("style", "font-size:26px");
        List<String> f3 = signatures(v);
        assertEquals(signatures(new StandaloneView(String.format(baselineHtml, 26, "ok"), 320, 200, FontResolver.FALLBACK)), f3);

        // 2. min-width: auto row flex item: growing min-content past flex-basis must break cutoff and widen item
        String minContentHtml = "<style>body{margin:0;font-size:12px}"
                + ".row{display:flex;width:140px}"
                + ".it{flex:0 1 60px;height:20px;overflow:hidden}"
                + ".in{height:20px;overflow:hidden}</style>"
                + "<div class=row><div class=it><div class=in><span id=w>%s</span></div></div>"
                + "<div style='flex:1 1 60px;height:20px'>sib</div></div>";
        StandaloneView mv = new StandaloneView(String.format(minContentHtml, "a b"), 200, 120, FontResolver.FALLBACK);
        signatures(mv);
        firstText(mv, "w").setData("supercalifragilisticexpialidocious");
        assertEquals(
                signatures(new StandaloneView(String.format(minContentHtml, "supercalifragilisticexpialidocious"), 200, 120, FontResolver.FALLBACK)),
                signatures(mv));

        // 3. Column flex item with flex-basis: content (reads contentBlockExtent)
        String colContentHtml = "<style>body{margin:0;font-size:12px}"
                + ".col{display:flex;flex-direction:column;height:140px}"
                + ".it{flex:0 0 content;width:70px;height:20px;overflow:hidden}"
                + "</style><div class=col><div class=it><div id=in>%s</div></div><div style='height:20px'>end</div></div>";
        StandaloneView cv = new StandaloneView(String.format(colContentHtml, "short"), 200, 200, FontResolver.FALLBACK);
        signatures(cv);
        firstText(cv, "in").setData("one two three four five six seven eight");
        assertEquals(
                signatures(new StandaloneView(String.format(colContentHtml, "one two three four five six seven eight"), 200, 200, FontResolver.FALLBACK)),
                signatures(cv));

        // 4. Child's own style change (order) without changing child w/h must NOT cut off parent
        String orderHtml = "<style>body{margin:0}.row{display:flex;width:200px}.a{width:40px;height:20px;order:%d}.b{width:40px;height:20px;order:1}</style>"
                + "<div class=row><div id=a class=a></div><div class=b></div></div>";
        StandaloneView ov = new StandaloneView(String.format(orderHtml, 0), 240, 100, FontResolver.FALLBACK);
        signatures(ov);
        ((HtmlElement) ov.root().getNodeById("a")).setAttribute("style", "order:3");
        assertEquals(
                signatures(new StandaloneView("<style>body{margin:0}.row{display:flex;width:200px}.a{width:40px;height:20px;order:0}.b{width:40px;height:20px;order:1}</style>"
                        + "<div class=row><div id=a class=a style='order:3'></div><div class=b></div></div>", 240, 100, FontResolver.FALLBACK)),
                signatures(ov));

        // 5. Cutoff child growing its natural fit width updates ancestor fitNeed conservatively before narrowing resize
        String fitHtml = "<style>body{margin:0;font-size:12px}.wrap{height:20px;overflow:hidden}.ib{display:inline-block}</style>"
                + "<div id=p><div class=wrap><span class=ib id=ft>%s</span></div></div>";
        StandaloneView fv = new StandaloneView(String.format(fitHtml, "hi"), 400, 120, FontResolver.FALLBACK);
        signatures(fv);
        firstText(fv, "ft").setData("longunbreakablewordwideword");
        signatures(fv);
        fv.resize(60, 120);
        com.nishiyu.enderframelib.common.ResizeSettle.flushUntilApplied(fv); // リサイズの反映は実時間で間引かれる（§6-178）
        assertEquals(
                signatures(new StandaloneView(String.format(fitHtml, "longunbreakablewordwideword"), 60, 120, FontResolver.FALLBACK)),
                signatures(fv));
    }

    @Test
    void tentativeSlotsSurviveDescentWhenAncestorRemeasures() {
        String html = "<style>*{box-sizing:border-box;margin:0;padding:0}"
                + ".sc{position:relative;width:400px;height:300px;display:flex;flex-direction:column;overflow:hidden}"
                + ".abs{position:absolute;top:2px;left:2px;font-size:10px}"
                + ".ws{flex:1;min-height:0;display:grid;grid-template-columns:1fr 1fr;grid-template-rows:1fr 1fr;gap:4px}"
                + ".card{display:flex;flex-direction:column;min-height:0;overflow:hidden}"
                + ".inner{display:flex;flex-direction:column;flex:1;min-height:0}</style>"
                + "<div class=sc>"
                + "<div id=m class=abs>%s</div>"
                + "<div class=ws>"
                + "<div class=card><div class=inner><div>a</div><div>b</div></div></div>"
                + "<div class=card><div class=inner><div>c</div><div>d</div></div></div>"
                + "<div class=card><div class=inner><div>e</div><div>f</div></div></div>"
                + "<div class=card><div class=inner><div>g</div><div>h</div></div></div>"
                + "</div></div>";
        StandaloneView v = new StandaloneView(String.format(html, "[0,0]"), 420, 320, FontResolver.FALLBACK);
        signatures(v);
        firstText(v, "m").setData("[0,1]");
        assertEquals(signatures(new StandaloneView(String.format(html, "[0,1]"), 420, 320, FontResolver.FALLBACK)), signatures(v));

        long before = LayoutBox.measurementsComputed();
        firstText(v, "m").setData("[0,2]");
        List<String> f2 = signatures(v);
        long delta = LayoutBox.measurementsComputed() - before;
        assertEquals(signatures(new StandaloneView(String.format(html, "[0,2]"), 420, 320, FontResolver.FALLBACK)), f2);
        assertTrue(delta <= 10, "Grid/flex siblings must retain tentative slots across descent: measured=" + delta);
    }

    @Test
    void legacyContentSizedAncestorAndCutoffSafeItemHeuristicsAreRemoved() {
        for (Method m : LayoutEngine.class.getDeclaredMethods()) {
            assertNotEquals("contentSizedAncestor", m.getName(), "Legacy LayoutEngine.contentSizedAncestor must be removed");
        }
        for (Method m : FlexLayoutEngine.class.getDeclaredMethods()) {
            assertNotEquals("cutoffSafeItem", m.getName(), "Legacy FlexLayoutEngine.cutoffSafeItem must be removed");
        }
    }
}
