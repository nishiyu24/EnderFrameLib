package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.SessionContext;
import com.nishiyu.enderframelib.core.paint.CssAnimator;
import com.nishiyu.enderframelib.core.css.StyleSheet.CascadeOrigin;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.css.model.EasingFunction;
import com.nishiyu.enderframelib.core.css.model.ElementState;
import com.nishiyu.enderframelib.core.css.StyleSheet.Specificity;
import com.nishiyu.enderframelib.core.css.CssParser;
import com.nishiyu.enderframelib.core.css.StyleResolver;
import com.nishiyu.enderframelib.core.css.StyleSheet;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.paint.tree.PaintBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.paint.model.*;
import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;
import com.nishiyu.enderframelib.core.layout.model.Viewport;
import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;
import com.nishiyu.enderframelib.core.css.model.Overflow;
import com.nishiyu.enderframelib.core.css.model.TextAlign;
import com.nishiyu.enderframelib.core.css.model.BorderStyle;

/**
 * F3（状態をパイプラインの入力にする）のテスト群。
 *
 * <ul>
 *   <li>F3-1: {@link ElementState}、詳細度1パスカスケード、{@code :focus-visible}/{@code :focus-within}、
 *       {@code :checked}/{@code :disabled}/{@code :indeterminate}/{@code :open}、UA {@code !important} 優先</li>
 *   <li>F3-2: {@link InteractionState} による部分木再スタイル（{@code forceDirty}）、
 *       ペイント変更とレイアウト変更の判別、{@code :hover { padding }} による再レイアウト</li>
 *   <li>F3-3: {@link CssAnimator} の前値→次値汎用トランジション、{@code transition-property}、
 *       {@link EasingFunction}（{@code cubic-bezier}・既定 {@code ease}）、
 *       {@code @keyframes} の色・再生方向・fill-mode・反復回数・他チャネル非破壊</li>
 * </ul>
 */
public class StateStyleTest {

    // =========================================================================
    // F3-1: 状態の型表現とセレクタ照合・カスケード
    // =========================================================================

    @Test
    public void testHoverDeclarationsResolveThroughInteractionState() {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument("""
                <style>
                  button { background-color: #111111; border: 1px solid #000000; }
                  button:hover { background-color: #222222; border-color: #333333; }
                </style>
                <button id="b">Hi</button>
                """, "", 320, 200);
        InteractionState state = new InteractionState();

        PaintBox root = relayout(doc, state);
        PaintBox button = findById(root, "b");
        assertNotNull(button);
        assertEquals("#111111", button.style().get(CssProperty.BACKGROUND_COLOR));
        assertTrue(button.interactiveRules(), "対話疑似クラスを持つ要素は hasInteractiveRules が true");

        state.setHoveredNode(doc.root.getElementById("b"));
        root = relayout(doc, state);
        button = findById(root, "b");
        assertEquals("#222222", button.style().get(CssProperty.BACKGROUND_COLOR));
        assertEquals("#333333", button.style().get("border-top-color"));
    }

    @Test
    public void testSinglePassCascadeSpecificityBeatsRuleType() {
        // #btn (ID=1) は .primary:hover (class+pseudo=2) より詳細度が高いため、
        // ホバー時でも #btn の指定が勝つことを確認する（旧4バケット putAll 方式では .primary:hover が不正に勝っていた）
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument("""
                <style>
                  #btn { background-color: #112233; }
                  .primary:hover { background-color: #998877; }
                </style>
                <button id="btn" class="primary">Click</button>
                """, "", 320, 200);
        InteractionState state = new InteractionState();
        state.setHoveredNode(doc.root.getElementById("btn"));

        PaintBox root = relayout(doc, state);
        PaintBox btn = findById(root, "btn");
        assertNotNull(btn);
        assertEquals("#112233", btn.style().get(CssProperty.BACKGROUND_COLOR),
                "ID セレクタ (1,0,0) は クラス+疑似クラス (0,2,0) より常に優先される");
    }

    @Test
    public void testImportantOriginReversal() {
        // 通常宣言では AUTHOR > USER_AGENT だが、!important 同士では USER_AGENT > AUTHOR に逆転する
        Specificity uaNormal = new Specificity(CascadeOrigin.USER_AGENT, false, 0, 0, 1);
        Specificity authorNormal = new Specificity(CascadeOrigin.AUTHOR, false, 0, 0, 1);
        assertTrue(uaNormal.compareTo(authorNormal) < 0, "通常宣言では Author が UA に勝つ");

        Specificity uaImportant = new Specificity(CascadeOrigin.USER_AGENT, true, 0, 0, 1);
        Specificity authorImportant = new Specificity(CascadeOrigin.AUTHOR, true, 1, 0, 0);
        assertTrue(uaImportant.compareTo(authorImportant) > 0, "!important 同士では UA が Author に勝つ");
    }

    @Test
    public void testFocusVisibleAndFocusWithinSeparation() {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument("""
                <style>
                  #wrap { background-color: #101010; }
                  #wrap:focus-within { background-color: #204060; }
                  #inp { border-color: #444444; }
                  #inp:focus { border-color: #888888; }
                  #inp:focus-visible { border-color: #ffaa00; }
                </style>
                <div id="wrap"><input id="inp" type="text" /></div>
                """, "", 320, 200);
        InteractionState state = new InteractionState();

        // 1. マウスクリック由来のフォーカス（focusVisible = false）
        state.setFocusedNode(doc.root.getElementById("inp"), false);
        PaintBox root = relayout(doc, state);
        PaintBox wrap = findById(root, "wrap");
        PaintBox inp = findById(root, "inp");
        assertEquals("#204060", wrap.style().get(CssProperty.BACKGROUND_COLOR), "子孫がフォーカスを持てば親の :focus-within が当たる");
        assertEquals("#888888", inp.style().get("border-top-color"), "マウスフォーカスでは :focus のみが当たり :focus-visible は当たらない");

        // 2. キーボード Tab 由来のフォーカス（focusVisible = true）
        state.setFocusedNode(doc.root.getElementById("inp"), true);
        root = relayout(doc, state);
        wrap = findById(root, "wrap");
        inp = findById(root, "inp");
        assertEquals("#ffaa00", inp.style().get("border-top-color"), "キーボードフォーカスでは :focus-visible が当たる");
    }

    @Test
    public void testFormAndDisclosureRuntimeStates() {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument("""
                <style>
                  #cb { opacity: 0.4; }
                  #cb:checked { opacity: 1.0; }
                  #cb:indeterminate { opacity: 0.7; }
                  #btn:disabled { opacity: 0.25; }
                  #det { background-color: #111111; }
                  #det:open { background-color: #333333; }
                </style>
                <div>
                  <input id="cb" type="checkbox" />
                  <button id="btn">Run</button>
                  <details id="det"><summary>More</summary></details>
                </div>
                """, "", 320, 200);
        InteractionState state = new InteractionState();

        PaintBox root = relayout(doc, state);
        PaintBox cb = findById(root, "cb");
        PaintBox btn = findById(root, "btn");
        PaintBox det = findById(root, "det");

        assertEquals(0.4f, cb.style().typed().visual.opacity, 1e-4f);

        state.setChecked(doc.root.getElementById("cb"), true);
        state.setDisabled(doc.root.getElementById("btn"), true);
        state.setOpen(doc.root.getElementById("det"), true);
        root = relayout(doc, state);
        cb = findById(root, "cb");
        btn = findById(root, "btn");
        det = findById(root, "det");

        assertEquals(1.0f, cb.style().typed().visual.opacity, 1e-4f);
        assertEquals(0.25f, btn.style().typed().visual.opacity, 1e-4f);
        assertEquals("#333333", det.style().get(CssProperty.BACKGROUND_COLOR));

        state.setIndeterminate(doc.root.getElementById("cb"), true);
        root = relayout(doc, state);
        cb = findById(root, "cb");
        btn = findById(root, "btn");
        det = findById(root, "det");
        assertEquals(0.7f, cb.style().typed().visual.opacity, 1e-4f);
    }

    // =========================================================================
    // F3-2: 状態変化による部分木再スタイルとレイアウト反映
    // =========================================================================

    @Test
    public void testHoverPaddingTriggersLayoutChange() {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument("""
                <style>
                  #box { width: 100px; padding: 4px; }
                  #box:hover { padding: 20px; }
                </style>
                <div id="box">Content</div>
                """, "", 320, 200);
        InteractionState state = new InteractionState();

        PaintBox root = parser.layoutDocument(doc, 320, 200, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);
        PaintBox box = findById(root, "box");
        assertNotNull(box);
        assertEquals(4, box.pL());
        float initialHeight = box.h();

        state.setHoveredNode(doc.root.getElementById("box"));
        root = relayout(doc, state);
        box = findById(root, "box");

        PaintBox relaidRoot = parser.layoutDocument(doc, 320, 200, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);
        PaintBox relaidBox = findById(relaidRoot, "box");
        assertEquals(20, relaidBox.pL());
        assertTrue(relaidBox.h() > initialHeight, ":hover の padding 増加によりボックスの高さが増える");
    }

    @Test
    public void testParentHoverColorPropagatesToChildInheritedStyle() {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument("""
                <style>
                  #parent { color: #111111; }
                  #parent:hover { color: #ff0000; }
                </style>
                <div id="parent"><span id="child">Text</span></div>
                """, "", 320, 200);
        InteractionState state = new InteractionState();

        PaintBox root = relayout(doc, state);
        PaintBox parent = findById(root, "parent");
        PaintBox child = findById(root, "child");
        assertNotNull(parent);
        assertNotNull(child);
        assertEquals("#111111", parent.style().get(CssProperty.COLOR));
        assertEquals("#111111", child.style().get(CssProperty.COLOR), "子は親の color を初期継承する");

        state.setHoveredNode(doc.root.getElementById("parent"));
        root = relayout(doc, state);
        parent = findById(root, "parent");
        child = findById(root, "child");

        assertEquals("#ff0000", parent.style().get(CssProperty.COLOR));
        assertEquals("#ff0000", child.style().get(CssProperty.COLOR), "親の :hover による color 変化が子の継承値に伝搬する");
    }

    @Test
    public void testAuthorNormalDeclarationBeatsUserAgentHoverDeclaration() {
        // CSS のカスケード仕様に従い、Author の通常宣言は UA の疑似クラス付き通常宣言（button:hover 等）に優先する
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument("""
                <style>
                  #custom-btn { background-color: #123456; }
                  #styled-hover:hover { background-color: #abcdef; }
                </style>
                <div>
                  <button id="default-btn">Def</button>
                  <button id="custom-btn">Custom</button>
                  <button id="styled-hover">Styled</button>
                </div>
                """, "", 320, 200);
        InteractionState state = new InteractionState();

        PaintBox root = relayout(doc, state);
        PaintBox defBtn = findById(root, "default-btn");
        PaintBox customBtn = findById(root, "custom-btn");
        PaintBox styledHover = findById(root, "styled-hover");

        assertEquals("#efefef", defBtn.style().get(CssProperty.BACKGROUND_COLOR), "UA の button 初期背景色");
        assertEquals("#123456", customBtn.style().get(CssProperty.BACKGROUND_COLOR), "Author の通常宣言による背景色");

        // 1. デフォルトボタンをホバー: UA の button:hover (#e5e5e5) が適用される
        state.setHoveredNode(doc.root.getElementById("default-btn"));
        root = relayout(doc, state);
        defBtn = findById(root, "default-btn");
        customBtn = findById(root, "custom-btn");
        styledHover = findById(root, "styled-hover");
        assertEquals("#e5e5e5", defBtn.style().get(CssProperty.BACKGROUND_COLOR), "UA の button:hover が効く");

        // 2. 作者が背景色を指定したボタンをホバー: UA の button:hover は Author の通常宣言に勝てないため #123456 のまま
        state.setHoveredNode(doc.root.getElementById("custom-btn"));
        root = relayout(doc, state);
        defBtn = findById(root, "default-btn");
        customBtn = findById(root, "custom-btn");
        styledHover = findById(root, "styled-hover");
        assertEquals("#123456", customBtn.style().get(CssProperty.BACKGROUND_COLOR), "Author の通常宣言は UA の :hover に勝つ");

        // 3. 作者が :hover を指定したボタンをホバー: Author の :hover (#abcdef) が適用される
        state.setHoveredNode(doc.root.getElementById("styled-hover"));
        root = relayout(doc, state);
        defBtn = findById(root, "default-btn");
        customBtn = findById(root, "custom-btn");
        styledHover = findById(root, "styled-hover");
        assertEquals("#abcdef", styledHover.style().get(CssProperty.BACKGROUND_COLOR), "Author の :hover が勝つ");
    }


    // =========================================================================
    // F3-3: EasingFunction・汎用トランジション・@keyframes チャネル分離
    // =========================================================================

    @Test
    public void testCubicBezierAndEaseDefault() {
        assertEquals(0.0f, EasingFunction.apply(0.0f, "ease"), 1e-5f);
        assertEquals(1.0f, EasingFunction.apply(1.0f, "ease"), 1e-5f);
        assertEquals(0.5f, EasingFunction.apply(0.5f, "linear"), 1e-5f);

        // CSS 既定 ease = cubic-bezier(0.25, 0.1, 0.25, 1.0) は t=0.5 で約 0.8024 になる
        float easeMid = EasingFunction.apply(0.5f, null);
        float explicitBezierMid = EasingFunction.apply(0.5f, "cubic-bezier(0.25, 0.1, 0.25, 1.0)");
        assertEquals(explicitBezierMid, easeMid, 1e-5f);
        assertTrue(easeMid > 0.75f && easeMid < 0.85f, "CSS ease の t=0.5 評価値: " + easeMid);
    }

    @Test
    public void testGeneralizedTransitionAndTransitionPropertyFiltering() {
        String sessionId = "test_trans_session";
        ClientWebManager.clearSession(sessionId);

        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument("""
                <style>
                  #card {
                    width: 80px;
                    height: 40px;
                    opacity: 1.0;
                    transform: translateX(0px);
                    transition-property: opacity;
                    transition-duration: 200ms;
                    transition-timing-function: linear;
                  }
                  #card:hover {
                    opacity: 0.2;
                    transform: translateX(40px);
                  }
                </style>
                <div id="card"></div>
                """, "", 320, 200);
        InteractionState state = new InteractionState();

        PaintBox root1 = parser.layoutDocument(doc, 320, 200, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);
        List<DisplayItem> els1 = com.nishiyu.enderframelib.common.FrameRender.document(doc, 320, 200, java.util.Map.of(), new ScrollState(), FontResolver.FALLBACK, state).items();
        DisplayItem node1 = findElementById(els1, "card");
        assertNotNull(node1);

        long t0 = 1_000_000L;
        AnimatedValues v0 = CssAnimator.getValuesAt(sessionId, node1, t0);
        assertEquals(1.0f, v0.opacity(), 1e-4f);
        assertEquals(0.0f, v0.tx(), 1e-4f);

        // ホバー状態へ遷移
        state.setHoveredNode(doc.root.getElementById("card"));
        PaintBox root2 = parser.layoutDocument(doc, 320, 200, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);
        List<DisplayItem> els2 = com.nishiyu.enderframelib.common.FrameRender.document(doc, 320, 200, java.util.Map.of(), new ScrollState(), FontResolver.FALLBACK, state).items();
        DisplayItem node2 = findElementById(els2, "card");
        assertNotNull(node2);

        // t0 + 100ms（200ms の半分）で評価:
        // transition-property: opacity なので opacity は 0.6 に補間され、transform (tx) は即座に 40px にスナップする
        CssAnimator.getValuesAt(sessionId, node2, t0);
        AnimatedValues vMid = CssAnimator.getValuesAt(sessionId, node2, t0 + 100L);
        assertEquals(0.6f, vMid.opacity(), 1e-3f, "transition-property に含まれる opacity は中間値に補間される");
        assertEquals(40.0f, vMid.tx(), 1e-3f, "transition-property に含まれない transform は即座に目標値へスナップする");

        ClientWebManager.clearSession(sessionId);
    }

    @Test
    public void testKeyframesColorDirectionFillModeAndNonOverwritingTransition() {
        String sessionId = "test_kf_session";
        ClientWebManager.clearSession(sessionId);

        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument("""
                <style>
                  @keyframes slideX {
                    0% { transform: translateX(0px); }
                    100% { transform: translateX(100px); }
                  }
                  #actor {
                    width: 50px;
                    height: 20px;
                    opacity: 1.0;
                    animation: slideX 200ms linear 1 forwards;
                    transition: opacity 200ms linear;
                  }
                  #actor:hover {
                    opacity: 0.0;
                  }
                </style>
                <div id="actor"></div>
                """, "", 320, 200);
        InteractionState state = new InteractionState();

        PaintBox root1 = parser.layoutDocument(doc, 320, 200, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);
        List<DisplayItem> els1 = com.nishiyu.enderframelib.common.FrameRender.document(doc, 320, 200, java.util.Map.of(), new ScrollState(), FontResolver.FALLBACK, state).items();
        DisplayItem actor1 = findElementById(els1, "actor");
        assertNotNull(actor1);

        long t0 = 2_000_000L;
        CssAnimator.getValuesAt(sessionId, actor1, t0);

        // ホバーを発生させ、opacity のトランジションと @keyframes slideX が同時に走る状態にする
        state.setHoveredNode(doc.root.getElementById("actor"));
        PaintBox root2 = parser.layoutDocument(doc, 320, 200, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);
        List<DisplayItem> els2 = com.nishiyu.enderframelib.common.FrameRender.document(doc, 320, 200, java.util.Map.of(), new ScrollState(), FontResolver.FALLBACK, state).items();
        DisplayItem actor2 = findElementById(els2, "actor");
        assertNotNull(actor2);

        CssAnimator.getValuesAt(sessionId, actor2, t0);
        AnimatedValues vMid = CssAnimator.getValuesAt(sessionId, actor2, t0 + 100L);
        assertEquals(50.0f, vMid.tx(), 1e-2f, "@keyframes の translateX が 50% 進行している");
        assertEquals(0.5f, vMid.opacity(), 1e-2f, "@keyframes が触っていない opacity のトランジションが潰されない");

        // 200ms 経過後も forwards により tx=100px が保持される
        AnimatedValues vEnd = CssAnimator.getValuesAt(sessionId, actor2, t0 + 300L);
        assertEquals(100.0f, vEnd.tx(), 1e-2f, "animation-fill-mode: forwards により終了後も最終フレームの値が維持される");
        assertEquals(0.0f, vEnd.opacity(), 1e-2f);

        ClientWebManager.clearSession(sessionId);
    }

    @Test
    public void testKeyframesBackgroundColorOnBlockElement() {
        String sessionId = "test_kf_bg_session";
        ClientWebManager.clearSession(sessionId);

        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument("""
                <style>
                  @keyframes pulseBg {
                    from { background-color: #000000; }
                    to { background-color: #ffffff; }
                  }
                  #panel {
                    width: 60px;
                    height: 30px;
                    animation-name: pulseBg;
                    animation-duration: 100ms;
                    animation-timing-function: linear;
                    animation-iteration-count: 2;
                    animation-direction: alternate;
                  }
                </style>
                <div id="panel"></div>
                """, "", 320, 200);
        InteractionState state = new InteractionState();

        PaintBox root = parser.layoutDocument(doc, 320, 200, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);
        List<DisplayItem> els = com.nishiyu.enderframelib.common.FrameRender.document(doc, 320, 200, java.util.Map.of(), new ScrollState(), FontResolver.FALLBACK, state).items();
        DisplayItem bgEl = findElementById(els, "panel_bg");
        assertNotNull(bgEl, "背景アニメーションがある場合は背景要素が出力される");

        long t0 = 3_000_000L;
        CssAnimator.getValuesAt(sessionId, bgEl, t0);
        AnimatedValues vHalf = CssAnimator.getValuesAt(sessionId, bgEl, t0 + 50L);
        int rHalf = (vHalf.bgColor() >>> 16) & 0xFF;
        assertTrue(rHalf >= 120 && rHalf <= 135, "50ms 時点で背景色が中間グレー付近に補間される: " + rHalf);

        // alternate の 2 周目 (t0 + 175ms = 2周目の75% → 逆再生で 25%)
        AnimatedValues vAlt = CssAnimator.getValuesAt(sessionId, bgEl, t0 + 175L);
        int rAlt = (vAlt.bgColor() >>> 16) & 0xFF;
        assertTrue(rAlt >= 55 && rAlt <= 75, "alternate の 2 周目後半は逆方向に補間される: " + rAlt);

        ClientWebManager.clearSession(sessionId);
    }

    @Test
    public void testAnimationDelayAndBackwardsFillMode() {
        String sessionId = "test_anim_delay_session";
        ClientWebManager.clearSession(sessionId);

        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument("""
                <style>
                  @keyframes shiftX {
                    0% { transform: translateX(50px); }
                    100% { transform: translateX(150px); }
                  }
                  #delayedBackwards {
                    width: 40px; height: 20px;
                    transform: translateX(0px);
                    animation: shiftX 100ms linear 100ms backwards;
                  }
                  #delayedNone {
                    width: 40px; height: 20px;
                    transform: translateX(0px);
                    animation: shiftX 100ms linear 100ms none;
                  }
                </style>
                <div>
                  <div id="delayedBackwards"></div>
                  <div id="delayedNone"></div>
                </div>
                """, "", 320, 200);
        InteractionState state = new InteractionState();

        PaintBox root = parser.layoutDocument(doc, 320, 200, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);
        List<DisplayItem> els = com.nishiyu.enderframelib.common.FrameRender.document(doc, 320, 200, java.util.Map.of(), new ScrollState(), FontResolver.FALLBACK, state).items();
        DisplayItem elBackwards = findElementById(els, "delayedBackwards");
        DisplayItem elNone = findElementById(els, "delayedNone");
        assertNotNull(elBackwards);
        assertNotNull(elNone);

        long t0 = 4_000_000L;
        // t0 でアニメーション開始時刻を確定
        CssAnimator.getValuesAt(sessionId, elBackwards, t0);
        CssAnimator.getValuesAt(sessionId, elNone, t0);

        // 遅延中 (t0 + 40ms) の評価
        AnimatedValues vBackwardsDelay = CssAnimator.getValuesAt(sessionId, elBackwards, t0 + 40L);
        AnimatedValues vNoneDelay = CssAnimator.getValuesAt(sessionId, elNone, t0 + 40L);

        assertEquals(50.0f, vBackwardsDelay.tx(), 1e-2f, "backwards は delay 中に 0% キーフレーム (50px) を適用する");
        assertEquals(0.0f, vNoneDelay.tx(), 1e-2f, "none は delay 中に基底スタイル (0px) を維持する");

        // アニメーション中 (t0 + 150ms: 遅延 100ms + 50ms 経過 = 50%) の評価
        AnimatedValues vMid = CssAnimator.getValuesAt(sessionId, elBackwards, t0 + 150L);

        assertEquals(100.0f, vMid.tx(), 1e-2f, "進行度 50% で 50px と 150px の中間値 100px になる");

        // アニメーション終了後 (t0 + 250ms)
        // backwards 単独では終了後に forwards が無いため、基底スタイル (0px) に戻る
        AnimatedValues vAfter = CssAnimator.getValuesAt(sessionId, elBackwards, t0 + 250L);
        assertEquals(0.0f, vAfter.tx(), 1e-2f, "forwards 指定が無いため終了後は基底スタイル (0px) に戻る");

        ClientWebManager.clearSession(sessionId);
    }


    // =========================================================================
    // W9: 結合子を跨ぐ再スタイルと擬似要素の動的な再スタイル
    // =========================================================================

    @Test
    public void testCheckedRestylesAdjacentSibling() {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument("""
                <style>
                  label { color: #111111; }
                  input:checked + label { color: #ff0000; }
                </style>
                <div><input id="cb" type="checkbox"><label id="near">a</label><label id="far">b</label></div>
                """, "", 320, 200);
        InteractionState state = new InteractionState();
        PaintBox root = parser.layoutDocument(doc, 320, 200, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);
        assertEquals("#111111", findById(root, "near").style().get(CssProperty.COLOR));

        // 汚れるのは input だけ。以前は兄弟の label に再スタイルが届かなかった
        state.setChecked(doc.root.getElementById("cb"), true);
        root = relayout(doc, state);
        assertEquals("#ff0000", findById(root, "near").style().get(CssProperty.COLOR));
        assertEquals("#111111", findById(root, "far").style().get(CssProperty.COLOR), "+ は直後の兄弟だけ");

        state.setChecked(doc.root.getElementById("cb"), false);
        root = relayout(doc, state);
        assertEquals("#111111", findById(root, "near").style().get(CssProperty.COLOR));
    }

    @Test
    public void testStateRestylesGeneralSiblingsAndTheirDescendants() {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument("""
                <style>
                  .a:hover ~ .b { background-color: #00ff00; }
                  input:disabled ~ .b span { color: #0000ff; }
                </style>
                <div><div class="a" id="a"></div><input id="in"><p id="mid"></p><div class="b" id="b"><span id="s">x</span></div></div>
                """, "", 320, 200);
        InteractionState state = new InteractionState();
        PaintBox root = parser.layoutDocument(doc, 320, 200, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);

        state.setHoveredNode(doc.root.getElementById("a"));
        root = relayout(doc, state);
        assertEquals("#00ff00", findById(root, "b").style().get(CssProperty.BACKGROUND_COLOR));

        state.setDisabled(doc.root.getElementById("in"), true);
        root = relayout(doc, state);
        assertEquals("#0000ff", findById(root, "s").style().get(CssProperty.COLOR), "~ の後続の兄弟の子孫まで届く");
    }

    @Test
    public void testHoverBeforeAppearsAndDisappears() {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument("""
                <style>.btn:hover::before { content: "→"; color: #123456; }</style>
                <div class="btn" id="b">go</div>
                """, "", 320, 200);
        InteractionState state = new InteractionState();
        PaintBox root = parser.layoutDocument(doc, 320, 200, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);
        assertNull(findById(root, "b_before"));

        state.setHoveredNode(doc.root.getElementById("b"));
        root = relayout(doc, state);
        PaintBox b = findById(root, "b");
        assertEquals("b_before", TestBoxes.elements(b).getFirst().node.getId(), "::before は先頭の子");

        // 呼び出し側（ClientWebManager）と同じく、再レイアウトは木を組み直す
        root = parser.layoutDocument(doc, 320, 200, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);
        PaintBox before = findById(root, "b_before");
        assertNotNull(before);
        assertEquals("#123456", before.style().get(CssProperty.COLOR));
        assertEquals("→", contentText(before));

        state.setHoveredNode(null);
        root = relayout(doc, state);
        b = findById(root, "b");
        before = findById(root, "b_before");
        assertNull(findById(root, "b_before"));
    }

    @Test
    public void testCheckedAfterOnSiblingRestylesPaintOnly() {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument("""
                <style>
                  label::after { content: "!"; color: #111111; }
                  input:checked + label::after { color: #222222; }
                </style>
                <div><input id="cb" type="checkbox"><label id="l">a</label></div>
                """, "", 320, 200);
        InteractionState state = new InteractionState();
        PaintBox root = parser.layoutDocument(doc, 320, 200, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);
        PaintBox after = findById(root, "l_after");
        assertEquals("#111111", after.style().get(CssProperty.COLOR));

        state.setChecked(doc.root.getElementById("cb"), true);
        root = relayout(doc, state);
        after = findById(root, "l_after");
        assertSame(after, findById(root, "l_after"), "同じ箱のスタイルを書き換える");
        assertEquals("#222222", after.style().get(CssProperty.COLOR));
        assertEquals("#222222", TestBoxes.elements(after).getFirst().style().get(CssProperty.COLOR), "中の文字も追従する");
    }

    @Test
    public void testContentChangeOnStateRequiresLayout() {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument("""
                <style>
                  #t::after { content: "off"; }
                  #t:focus::after { content: "on"; }
                </style>
                <button id="t">x</button>
                """, "", 320, 200);
        InteractionState state = new InteractionState();
        PaintBox root = parser.layoutDocument(doc, 320, 200, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);
        assertEquals("off", contentText(findById(root, "t_after")));

        state.setFocusedNode(doc.root.getElementById("t"), false);
        root = relayout(doc, state);
        root = parser.layoutDocument(doc, 320, 200, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);
        assertEquals("on", contentText(findById(root, "t_after")));
    }

    @Test
    public void testHoveringGeneratedContentHoversItsOwner() {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument("<div id=\"p\"><div id=\"b\">x</div></div>", "", 320, 200);
        String sessionId = "hover-generated-content";
        SessionContext session = ClientWebManager.getOrCreateSession(sessionId);
        session.setDocument(doc);
        try {
            // 擬似要素とその文字は DOM に無いので、ヒットテストが返す id から持ち主へ戻して祖先を辿る
            session.setHoveredElement("b_before_txt");
            assertTrue(session.interactionState().stateOf(doc.root.getElementById("b")).hover());
            assertTrue(session.interactionState().stateOf(doc.root.getElementById("p")).hover());
        } finally {
            ClientWebManager.clearSession(sessionId);
        }
    }

    @Test
    public void testHasInteractiveRulesHonorsLeftCompounds() {
        Document doc = new UiDocumentCompiler().parseDocument("""
                <div class="menu"><div class="item" id="in"></div></div>
                <div class="item" id="out"></div>
                <div class="parent" id="p"><div class="child" id="c"></div></div>
                """, "", 320, 200);
        // UA の :focus-visible はすべての要素に当たるので、作者の規則だけで判定する
        StyleSheet sheet = CssParser.parse("""
                .menu .item:hover { color: #ff0000; }
                .parent:hover .child { color: #00ff00; }
                """, 320);
        assertTrue(StyleResolver.hasInteractiveRules(doc.root.getElementById("in"), sheet));
        assertFalse(StyleResolver.hasInteractiveRules(doc.root.getElementById("out"), sheet),
                ".menu の外の .item のホバーでは何も変わらない");
        assertTrue(StyleResolver.hasInteractiveRules(doc.root.getElementById("p"), sheet),
                "状態を持つ複合セレクタが先頭にあっても当たる");
        assertFalse(StyleResolver.hasInteractiveRules(doc.root.getElementById("c"), sheet));
    }

    private static PaintBox findById(PaintBox box, String id) {
        if (box == null) return null;
        if (box.node != null && id.equals(box.node.getId())) return box;
        // 要素の子の箱をたどる（匿名の箱は中へ降りる）
        for (PaintBox child : TestBoxes.elements(box)) {
            PaintBox hit = findById(child, id);
            if (hit != null) return hit;
        }
        return null;
    }

    private static DisplayItem findElementById(List<DisplayItem> elements, String id) {
        for (DisplayItem el : elements) {
            if (id.equals(el.id())) return el;
        }
        return null;
    }

    /**
     * 文書を {@code state} の今の状態で組み直した根（M2c-3: 旧経路の箱の木をその場で再スタイルする代わりに、同じ文書と状態から
     * 1 回だけのフレームを作り直す。読む箱は呼び出しのたびに引き直す）。
     */
    private static PaintBox relayout(Document doc, InteractionState state) {
        return new UiDocumentCompiler().layoutDocument(doc, 320, 200, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);
    }

    /** 文字の箱の文字（行に置いた語をつないだもの）。 */
    private static String runsText(com.nishiyu.enderframelib.core.paint.tree.PaintBox box) {
        StringBuilder sb = new StringBuilder();
        for (var run : box.runs()) if (run.text() != null) sb.append(run.text());
        return sb.toString();
    }

    /** 生成内容の箱の文字（中身の文字の箱か、自分の行の語）。 */
    private static String contentText(PaintBox pseudo) {
        return pseudo.paintChildren().isEmpty() ? runsText(pseudo) : runsText(pseudo.paintChildren().getFirst());
    }
}
