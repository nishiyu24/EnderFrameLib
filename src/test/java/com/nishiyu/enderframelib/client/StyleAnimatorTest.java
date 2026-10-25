package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.client.renderer.DisplayListBuilder;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.css.AnimationParser;
import com.nishiyu.enderframelib.core.paint.model.AnimationTiming;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.ValueInterpolator;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.dom.node.HtmlDocument;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code opacity} / {@code transform} / 色以外のプロパティのトランジションと {@code @keyframes}
 * （{@link com.nishiyu.enderframelib.core.css.StyleAnimator}）。
 *
 * <p>どの時刻の値も、同じ値を静的に書いた文書と同じ描画になることで確かめる（時刻は固定なので決定的）。</p>
 */
public class StyleAnimatorTest {

    private static final UiDocumentCompiler COMPILER = new UiDocumentCompiler();
    private static final int W = 400;
    private static final int H = 300;

    // ------------------------------------------------------------------
    // 値の補間
    // ------------------------------------------------------------------

    @Test
    public void interpolatesLengthsNumbersAndColors() {
        assertEquals("150px", ValueInterpolator.interpolate("width", "100px", "200px", 0.5));
        assertEquals("12.5%", ValueInterpolator.interpolate("width", "0", "25%", 0.5));
        assertEquals("5px", ValueInterpolator.interpolate("margin-left", "0", "10px", 0.5));
        assertEquals("2px", ValueInterpolator.interpolate("border-top-width", "thin", "3px", 0.5));
        assertEquals("calc((10px) * 0.5 + (50%) * 0.5)", ValueInterpolator.interpolate("width", "10px", "50%", 0.5));
        assertEquals("0.25", ValueInterpolator.interpolate("flex-grow", "0", "1", 0.25));
        assertEquals("#808080", ValueInterpolator.interpolate("border-top-color", "#000000", "#ffffff", 0.5));
        assertEquals("#ff0000", ValueInterpolator.interpolate("border-top-color", "#000", "#ff0000", 1.0));
    }

    @Test
    public void nonInterpolableValuesAreDiscrete() {
        assertNull(ValueInterpolator.interpolate("width", "auto", "100px", 0.5));
        assertNull(ValueInterpolator.interpolate("display", "block", "none", 0.5));
        assertNull(ValueInterpolator.interpolate("border-top-color", "currentcolor", "#fff", 0.5));
        // opacity など描画項目の補間が担うものも、ここでは対象外（宣言が animatable でも呼び出し側が除く）
        assertEquals("0.5", ValueInterpolator.interpolate("opacity", "0", "1", 0.5));
    }

    // ------------------------------------------------------------------
    // 指定の読み取り
    // ------------------------------------------------------------------

    private static ComputedStyle styleOf(String declarations) {
        Document doc = COMPILER.parseDocument("<html><body><div id=\"x\" style=\"" + declarations + "\"></div></body></html>", "", W, H);
        InteractionState state = new InteractionState();
        ((HtmlDocument) doc.root).setInteractionState(state);
        COMPILER.layoutDocument(doc, W, H, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);
        return state.computedStyleOf(doc.root.getElementById("x"));
    }

    @Test
    public void parsesTransitionShorthandListAndCyclesLonghands() {
        List<AnimationTiming.Transition> list = AnimationParser.parseTransitions(
                styleOf("transition: width 2s 0.5s linear, height 300ms ease-in"));
        assertEquals(2, list.size());
        assertEquals(new AnimationTiming.Transition("width", 2000, 500, "linear"), list.get(0));
        assertEquals(new AnimationTiming.Transition("height", 300, 0, "ease-in"), list.get(1));

        List<AnimationTiming.Transition> cycled = AnimationParser.parseTransitions(
                styleOf("transition-property: width, height, margin-left; transition-duration: 1s, 2s; transition-delay: 100ms"));
        assertEquals(List.of(1000, 2000, 1000), cycled.stream().map(AnimationTiming.Transition::durationMs).toList());
        assertEquals(List.of(100, 100, 100), cycled.stream().map(AnimationTiming.Transition::delayMs).toList());

        assertEquals(List.of("none"), AnimationParser.parseTransitions(styleOf("transition: none")).stream()
                .map(AnimationTiming.Transition::property).toList());
        assertEquals("height", AnimationTiming.transitionFor(list, "height").property());
        assertEquals("all", AnimationTiming.transitionFor(
                AnimationParser.parseTransitions(styleOf("transition: all 1s")), "padding-left").property());
    }

    @Test
    public void progressFollowsDelayIterationDirectionAndFill() {
        var normal = com.nishiyu.enderframelib.core.paint.model.AnimationSpec.Direction.NORMAL;
        var alternate = com.nishiyu.enderframelib.core.paint.model.AnimationSpec.Direction.ALTERNATE;
        var none = com.nishiyu.enderframelib.core.paint.model.AnimationSpec.FillMode.NONE;
        var both = com.nishiyu.enderframelib.core.paint.model.AnimationSpec.FillMode.BOTH;
        assertTrue(Float.isNaN(AnimationTiming.progressPct(1000, 500, 1f, normal, none, 100)));
        assertEquals(0f, AnimationTiming.progressPct(1000, 500, 1f, normal, both, 100));
        assertEquals(50f, AnimationTiming.progressPct(1000, 500, 1f, normal, none, 1000));
        assertTrue(Float.isNaN(AnimationTiming.progressPct(1000, 0, 1f, normal, none, 1000)));
        assertEquals(100f, AnimationTiming.progressPct(1000, 0, 1f, normal, both, 5000));
        // alternate は 2 周目が逆再生
        assertEquals(75f, AnimationTiming.progressPct(1000, 0, 2f, alternate, none, 1250));
        assertEquals(25f, AnimationTiming.progressPct(1000, 0, Float.POSITIVE_INFINITY, normal, none, 7250));
    }

    // ------------------------------------------------------------------
    // 文書を通した動き
    // ------------------------------------------------------------------

    private static final String BODY = "<div id=\"box\" class=\"box\"><span id=\"label\">content</span></div><div id=\"after\">after</div>";

    private static String page(String css) {
        return "<html><head><style>body{margin:8px;font-size:14px} .box{background-color:#223344;border:2px solid #aa0000;height:40px}"
                + css + "</style></head><body>" + BODY + "</body></html>";
    }

    /** 見た目に出る値だけ（アニメーション用の補助情報は除く）。 */
    private static List<String> project(List<DisplayItem> items) {
        List<String> out = new ArrayList<>();
        for (DisplayItem d : items) {
            out.add(d.id() + " " + d.x() + "," + d.y() + " " + d.width() + "x" + d.height() + " bg=" + Integer.toHexString(d.bgColor())
                    + " fg=" + Integer.toHexString(d.color()) + " text=" + d.text());
        }
        return out;
    }

    private static final class Live {
        final Document doc;
        final InteractionState state = new InteractionState();
        final ScrollState scroll = new ScrollState();
        LayoutBox root;

        Live(String html, long now) {
            doc = COMPILER.parseDocument(html, "", W, H);
            ((HtmlDocument) doc.root).setInteractionState(state);
            state.setAnimationTime(now);
            root = COMPILER.layoutDocument(doc, W, H, Map.of(), scroll, FontResolver.FALLBACK, state);
        }

        /** 時刻を進めてスタイルを解き直し、そのフレームの描画を返す。 */
        List<String> frame(long now) {
            state.advanceAnimations(now);
            state.setAnimationTime(now);
            root = COMPILER.layoutDocument(doc, W, H, Map.of(), scroll, FontResolver.FALLBACK, state, root);
            return project(new DisplayListBuilder(W, H, doc.sheet.keyframes, scroll).render(root));
        }

        /** 時刻を進めて再スタイルだけを走らせる（描画の組み立てはしない）。 */
        com.nishiyu.enderframelib.common.RenderPipeline.RestyleResult restyle(long now) {
            state.advanceAnimations(now);
            state.setAnimationTime(now);
            return COMPILER.restyleLayoutTree(root, doc, W, H, Map.of(), state);
        }

        List<String> render() {
            return project(new DisplayListBuilder(W, H, doc.sheet.keyframes, scroll).render(root));
        }

        void setClass(String id, String value, long now) {
            state.setAnimationTime(now);
            doc.root.getElementById(id).setAttribute("class", value);
        }
    }

    private static List<String> staticFrame(String css) {
        Document doc = COMPILER.parseDocument(page(css), "", W, H);
        InteractionState state = new InteractionState();
        ((HtmlDocument) doc.root).setInteractionState(state);
        ScrollState scroll = new ScrollState();
        LayoutBox root = COMPILER.layoutDocument(doc, W, H, Map.of(), scroll, FontResolver.FALLBACK, state);
        return project(new DisplayListBuilder(W, H, doc.sheet.keyframes, scroll).render(root));
    }

    @Test
    public void widthTransitionMatchesStaticFramesAndRelayouts() {
        String css = ".box{width:100px;transition:width 1s linear} .box.wide{width:200px}";
        Live live = new Live(page(css), 10_000);
        assertEquals(staticFrame(".box{width:100px}"), live.frame(10_000));

        live.setClass("box", "box wide", 10_000);
        assertEquals(staticFrame(".box{width:100px}"), live.frame(10_000), "開始の瞬間は変化前の値");
        assertEquals(staticFrame(".box{width:125px}"), live.frame(10_250));
        assertEquals(staticFrame(".box{width:150px}"), live.frame(10_500));
        assertEquals(staticFrame(".box{width:200px}"), live.frame(11_000));
        assertEquals(staticFrame(".box{width:200px}"), live.frame(12_000), "終わった後は目標値のまま");
        assertFalse(live.state.advanceAnimations(13_000));
    }

    @Test
    public void paintOnlyTransitionNeedsNoLayout() {
        String css = ".box{width:100px;border-top-color:#aa0000;transition:border-top-color 1s linear} .box.hot{border-top-color:#0000aa}";
        Live live = new Live(page(css), 10_000);
        live.setClass("box", "box hot", 10_000);
        live.restyle(10_000);
        for (long t : new long[]{10_250, 10_500, 11_000}) {
            var result = live.restyle(t);
            assertTrue(result.styleChanged(), "t=" + t);
            assertFalse(result.layoutRequired(), "色だけの補間で再レイアウトを要求している t=" + t);
        }
        assertEquals(staticFrame(".box{width:100px;border-top-color:#0000aa}"), live.render());
    }

    @Test
    public void opacityTransformAndColorChangesNeedNoLayout() {
        String css = ".box.fx{opacity:0.4;transform:translateX(12px) scale(1.1);color:#ff0;background-color:#345;"
                + "border-top-color:#0f0;box-shadow:2px 2px 4px #000;border-radius:6px;outline:2px solid #f00}";
        Live live = new Live(page(css), 10_000);
        live.setClass("box", "box fx", 10_000);
        var result = live.restyle(10_000);
        assertTrue(result.styleChanged());
        assertFalse(result.layoutRequired(), "描画だけに効く変化で再レイアウトを要求している");
        assertFalse(result.repositionRequired());
    }

    @Test
    public void layoutAffectingTransitionRequestsLayoutEveryFrame() {
        String css = ".box{width:100px;transition:width 1s linear} .box.wide{width:200px}";
        Live live = new Live(page(css), 10_000);
        live.setClass("box", "box wide", 10_000);
        live.restyle(10_000);
        assertTrue(live.restyle(10_250).layoutRequired());
        assertTrue(live.restyle(10_500).layoutRequired());
        assertTrue(live.restyle(11_000).layoutRequired(), "目標値へ戻す最後のフレームも要る");
        assertFalse(live.restyle(11_100).styleChanged(), "終わった後は何も変わらない");
    }

    @Test
    public void channelPropertiesAreLeftToTheDisplayItemAnimator() {
        String css = ".box{opacity:1;color:#fff;transition:opacity 1s, color 1s, background-color 1s} .box.dim{opacity:0.2;color:#000;background-color:#000}";
        Live live = new Live(page(css), 10_000);
        live.setClass("box", "box dim", 10_000);
        live.frame(10_500);
        assertFalse(live.state.animator().isRunning(10_500), "チャネルのプロパティを二重に動かしている");
    }

    @Test
    public void delayHoldsTheOldValueAndNewTargetRestartsFromCurrentValue() {
        String css = ".box{width:100px;transition:width 1s linear 500ms} .box.wide{width:200px} .box.narrow{width:100px}";
        Live live = new Live(page(css), 10_000);
        live.setClass("box", "box wide", 10_000);
        live.frame(10_000);
        assertEquals(staticFrame(".box{width:100px}"), live.frame(10_400), "遅延の間は変化前のまま");
        assertEquals(staticFrame(".box{width:150px}"), live.frame(11_000));

        // 途中で元へ戻す。いまの値（150px）から新しい遅延ぶん待って戻る
        live.setClass("box", "box narrow", 11_000);
        assertEquals(staticFrame(".box{width:150px}"), live.frame(11_000));
        assertEquals(staticFrame(".box{width:150px}"), live.frame(11_400));
        assertEquals(staticFrame(".box{width:125px}"), live.frame(12_000));
        assertEquals(staticFrame(".box{width:100px}"), live.frame(12_500));
    }

    @Test
    public void keyframesAnimateLayoutProperties() {
        String css = "@keyframes grow{from{width:100px}to{width:300px}} .box{animation:grow 2s linear}";
        Live live = new Live(page(css), 10_000);
        assertEquals(staticFrame(".box{width:100px}"), live.frame(10_000));
        assertEquals(staticFrame(".box{width:200px}"), live.frame(11_000));
        assertEquals(staticFrame(".box{width:250px}"), live.frame(11_500));
        // fill-mode が無いので、終わると元の値（auto = 表示領域いっぱい）へ戻る
        assertEquals(staticFrame(""), live.frame(13_000));
    }

    @Test
    public void keyframesHonorFillIterationAndDirection() {
        String css = "@keyframes grow{from{width:100px}to{width:300px}} .box{animation:grow 1s linear 2 alternate forwards}";
        Live live = new Live(page(css), 10_000);
        assertEquals(staticFrame(".box{width:150px}"), live.frame(10_250));
        assertEquals(staticFrame(".box{width:250px}"), live.frame(11_250), "2 周目は逆再生");
        assertEquals(staticFrame(".box{width:100px}"), live.frame(12_000), "forwards: 最後の周の終わりの値を保つ");
        assertEquals(staticFrame(".box{width:100px}"), live.frame(15_000));
        assertFalse(live.state.advanceAnimations(16_000));
    }

    @Test
    public void keyframesWithMiddleStopsAndImplicitEnds() {
        String css = "@keyframes pulse{50%{padding-left:40px}} .box{padding-left:10px;animation:pulse 2s linear}";
        Live live = new Live(page(css), 10_000);
        assertEquals(staticFrame(".box{padding-left:10px}"), live.frame(10_000));
        assertEquals(staticFrame(".box{padding-left:25px}"), live.frame(10_500), "0% は要素自身の値");
        assertEquals(staticFrame(".box{padding-left:40px}"), live.frame(11_000));
        assertEquals(staticFrame(".box{padding-left:25px}"), live.frame(11_500), "100% も要素自身の値");
    }

    @Test
    public void inheritedValueDrivenByTheParentDoesNotStartItsOwnTransition() {
        String css = "#box{font-size:10px;transition:font-size 1s linear} #box.big{font-size:30px} #label{width:5em;display:block}";
        Live live = new Live(page(css), 10_000);
        live.setClass("box", "box big", 10_000);
        live.frame(10_000);
        List<String> mid = live.frame(10_500);
        assertEquals(staticFrame("#box{font-size:20px} #label{width:5em;display:block}"), mid, "子の em が動いている親のサイズに追従する");
        assertEquals(staticFrame("#box{font-size:30px} #label{width:5em;display:block}"), live.frame(11_000));
    }

    @Test
    public void animationsAreSettledAfterFinishingAndLeaveNoState() {
        String css = ".box{width:100px;transition:width 200ms linear} .box.wide{width:140px}";
        Live live = new Live(page(css), 10_000);
        live.setClass("box", "box wide", 10_000);
        live.frame(10_000);
        assertTrue(live.state.animator().isRunning(10_100));
        live.frame(10_300);
        live.frame(10_400);
        assertFalse(live.state.animator().isRunning(10_400));
        HtmlNode box = live.doc.root.getElementById("box");
        assertTrue(live.state.animator().sample(box, 10_400).isEmpty());
    }
}
