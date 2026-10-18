package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.client.renderer.UIRenderer;
import com.nishiyu.enderframelib.client.renderer.WebAnimator;
import com.nishiyu.enderframelib.core.css.CascadeOrigin;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.css.EasingFunction;
import com.nishiyu.enderframelib.core.css.ElementState;
import com.nishiyu.enderframelib.core.css.Specificity;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.LayoutContext.ScrollState;
import com.nishiyu.enderframelib.core.model.UIElement;
import com.nishiyu.enderframelib.core.text.FontFace.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F3（状態をパイプラインの入力にする）のテスト群。
 *
 * <ul>
 *   <li>F3-1: {@link ElementState}、詳細度1パスカスケード、{@code :focus-visible}/{@code :focus-within}、
 *       {@code :checked}/{@code :disabled}/{@code :indeterminate}/{@code :open}、UA {@code !important} 優先</li>
 *   <li>F3-2: {@link InteractionState} による部分木再スタイル（{@code forceDirty}）、
 *       ペイント変更とレイアウト変更の判別、{@code :hover { padding }} による再レイアウト</li>
 *   <li>F3-3: {@link WebAnimator} の前値→次値汎用トランジション、{@code transition-property}、
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
        UIParser parser = new UIParser();
        UIParser.Document doc = parser.parseDocument("""
                <style>
                  button { background-color: #111111; border: 1px solid #000000; }
                  button:hover { background-color: #222222; border-color: #333333; }
                </style>
                <button id="b">Hi</button>
                """, "", 320, 200);
        InteractionState state = new InteractionState();

        LayoutBox root = parser.buildLayoutTree(doc.root, new HashMap<>(), doc.sheet, Map.of(), false, state);
        LayoutBox button = findById(root, "b");
        assertNotNull(button);
        assertEquals("#111111", button.style.get(CssProperty.BACKGROUND_COLOR));
        assertTrue(button.hasInteractiveRules, "対話疑似クラスを持つ要素は hasInteractiveRules が true");

        state.setHoveredNode(doc.root, "b");
        UIParser.RestyleResult res = parser.restyleLayoutTree(root, doc, 320, 200, Map.of(), state);
        assertTrue(res.styleChanged());
        assertFalse(res.layoutRequired(), "色のみの変化はレイアウト再計算を要求しない");
        assertEquals("#222222", button.style.get(CssProperty.BACKGROUND_COLOR));
        assertEquals("#333333", button.style.get("border-top-color"));
    }

    @Test
    public void testSinglePassCascadeSpecificityBeatsRuleType() {
        // #btn (ID=1) は .primary:hover (class+pseudo=2) より詳細度が高いため、
        // ホバー時でも #btn の指定が勝つことを確認する（旧4バケット putAll 方式では .primary:hover が不正に勝っていた）
        UIParser parser = new UIParser();
        UIParser.Document doc = parser.parseDocument("""
                <style>
                  #btn { background-color: #112233; }
                  .primary:hover { background-color: #998877; }
                </style>
                <button id="btn" class="primary">Click</button>
                """, "", 320, 200);
        InteractionState state = new InteractionState();
        state.setHoveredNode(doc.root, "btn");

        LayoutBox root = parser.buildLayoutTree(doc.root, new HashMap<>(), doc.sheet, Map.of(), false, state);
        LayoutBox btn = findById(root, "btn");
        assertNotNull(btn);
        assertEquals("#112233", btn.style.get(CssProperty.BACKGROUND_COLOR),
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
        UIParser parser = new UIParser();
        UIParser.Document doc = parser.parseDocument("""
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
        state.setFocusedNode(doc.root, "inp", false);
        LayoutBox root = parser.buildLayoutTree(doc.root, new HashMap<>(), doc.sheet, Map.of(), false, state);
        LayoutBox wrap = findById(root, "wrap");
        LayoutBox inp = findById(root, "inp");
        assertEquals("#204060", wrap.style.get(CssProperty.BACKGROUND_COLOR), "子孫がフォーカスを持てば親の :focus-within が当たる");
        assertEquals("#888888", inp.style.get("border-top-color"), "マウスフォーカスでは :focus のみが当たり :focus-visible は当たらない");

        // 2. キーボード Tab 由来のフォーカス（focusVisible = true）
        state.setFocusedNode(doc.root, "inp", true);
        parser.restyleLayoutTree(root, doc, 320, 200, Map.of(), state);
        assertEquals("#ffaa00", inp.style.get("border-top-color"), "キーボードフォーカスでは :focus-visible が当たる");
    }

    @Test
    public void testFormAndDisclosureRuntimeStates() {
        UIParser parser = new UIParser();
        UIParser.Document doc = parser.parseDocument("""
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

        LayoutBox root = parser.buildLayoutTree(doc.root, new HashMap<>(), doc.sheet, Map.of(), false, state);
        LayoutBox cb = findById(root, "cb");
        LayoutBox btn = findById(root, "btn");
        LayoutBox det = findById(root, "det");

        assertEquals(0.4f, cb.style.number(CssProperty.OPACITY, 1f), 1e-4f);

        state.setChecked("cb", true);
        state.setDisabled("btn", true);
        state.setOpen("det", true);
        parser.restyleLayoutTree(root, doc, 320, 200, Map.of(), state);

        assertEquals(1.0f, cb.style.number(CssProperty.OPACITY, 1f), 1e-4f);
        assertEquals(0.25f, btn.style.number(CssProperty.OPACITY, 1f), 1e-4f);
        assertEquals("#333333", det.style.get(CssProperty.BACKGROUND_COLOR));

        state.setIndeterminate("cb", true);
        parser.restyleLayoutTree(root, doc, 320, 200, Map.of(), state);
        assertEquals(0.7f, cb.style.number(CssProperty.OPACITY, 1f), 1e-4f);
    }

    // =========================================================================
    // F3-2: 状態変化による部分木再スタイルとレイアウト反映
    // =========================================================================

    @Test
    public void testHoverPaddingTriggersLayoutChange() {
        UIParser parser = new UIParser();
        UIParser.Document doc = parser.parseDocument("""
                <style>
                  #box { width: 100px; padding: 4px; }
                  #box:hover { padding: 20px; }
                </style>
                <div id="box">Content</div>
                """, "", 320, 200);
        InteractionState state = new InteractionState();

        LayoutBox root = parser.layoutDocument(doc, 320, 200, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);
        LayoutBox box = findById(root, "box");
        assertNotNull(box);
        assertEquals(4, box.pL);
        float initialHeight = box.h;

        state.setHoveredNode(doc.root, "box");
        UIParser.RestyleResult res = parser.restyleLayoutTree(root, doc, 320, 200, Map.of(), state);
        assertTrue(res.styleChanged());
        assertTrue(res.layoutRequired(), "padding の変化はレイアウト再計算を要求する");

        LayoutBox relaidRoot = parser.layoutDocument(doc, 320, 200, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);
        LayoutBox relaidBox = findById(relaidRoot, "box");
        assertEquals(20, relaidBox.pL);
        assertTrue(relaidBox.h > initialHeight, ":hover の padding 増加によりボックスの高さが増える");
    }

    @Test
    public void testParentHoverColorPropagatesToChildInheritedStyle() {
        UIParser parser = new UIParser();
        UIParser.Document doc = parser.parseDocument("""
                <style>
                  #parent { color: #111111; }
                  #parent:hover { color: #ff0000; }
                </style>
                <div id="parent"><span id="child">Text</span></div>
                """, "", 320, 200);
        InteractionState state = new InteractionState();

        LayoutBox root = parser.buildLayoutTree(doc.root, new HashMap<>(), doc.sheet, Map.of(), false, state);
        LayoutBox parent = findById(root, "parent");
        LayoutBox child = findById(root, "child");
        assertNotNull(parent);
        assertNotNull(child);
        assertEquals("#111111", parent.style.get(CssProperty.COLOR));
        assertEquals("#111111", child.style.get(CssProperty.COLOR), "子は親の color を初期継承する");

        state.setHoveredNode(doc.root, "parent");
        UIParser.RestyleResult res = parser.restyleLayoutTree(root, doc, 320, 200, Map.of(), state);
        assertTrue(res.styleChanged());
        assertFalse(res.layoutRequired(), "色のみの変更なので再レイアウトは不要");

        assertEquals("#ff0000", parent.style.get(CssProperty.COLOR));
        assertEquals("#ff0000", child.style.get(CssProperty.COLOR), "親の :hover による color 変化が子の継承値に伝搬する");
    }

    @Test
    public void testAuthorNormalDeclarationBeatsUserAgentHoverDeclaration() {
        // CSS のカスケード仕様に従い、Author の通常宣言は UA の疑似クラス付き通常宣言（button:hover 等）に優先する
        UIParser parser = new UIParser();
        UIParser.Document doc = parser.parseDocument("""
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

        LayoutBox root = parser.buildLayoutTree(doc.root, new HashMap<>(), doc.sheet, Map.of(), false, state);
        LayoutBox defBtn = findById(root, "default-btn");
        LayoutBox customBtn = findById(root, "custom-btn");
        LayoutBox styledHover = findById(root, "styled-hover");

        assertEquals("#f0f0f0", defBtn.style.get(CssProperty.BACKGROUND_COLOR), "UA の button 初期背景色");
        assertEquals("#123456", customBtn.style.get(CssProperty.BACKGROUND_COLOR), "Author の通常宣言による背景色");

        // 1. デフォルトボタンをホバー: UA の button:hover (#e5e5e5) が適用される
        state.setHoveredNode(doc.root, "default-btn");
        parser.restyleLayoutTree(root, doc, 320, 200, Map.of(), state);
        assertEquals("#e5e5e5", defBtn.style.get(CssProperty.BACKGROUND_COLOR), "UA の button:hover が効く");

        // 2. 作者が背景色を指定したボタンをホバー: UA の button:hover は Author の通常宣言に勝てないため #123456 のまま
        state.setHoveredNode(doc.root, "custom-btn");
        parser.restyleLayoutTree(root, doc, 320, 200, Map.of(), state);
        assertEquals("#123456", customBtn.style.get(CssProperty.BACKGROUND_COLOR), "Author の通常宣言は UA の :hover に勝つ");

        // 3. 作者が :hover を指定したボタンをホバー: Author の :hover (#abcdef) が適用される
        state.setHoveredNode(doc.root, "styled-hover");
        parser.restyleLayoutTree(root, doc, 320, 200, Map.of(), state);
        assertEquals("#abcdef", styledHover.style.get(CssProperty.BACKGROUND_COLOR), "Author の :hover が勝つ");
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
        WebAnimator.clearSession(sessionId);

        UIParser parser = new UIParser();
        UIParser.Document doc = parser.parseDocument("""
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

        LayoutBox root1 = parser.layoutDocument(doc, 320, 200, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);
        List<UIElement> els1 = new UIRenderer(320, 200, doc.sheet.keyframes, new ScrollState()).render(root1);
        UIElement node1 = findElementById(els1, "card");
        assertNotNull(node1);

        long t0 = 1_000_000L;
        WebAnimator.AnimatedValues v0 = WebAnimator.getValuesAt(sessionId, node1, t0);
        assertEquals(1.0f, v0.opacity(), 1e-4f);
        assertEquals(0.0f, v0.tx(), 1e-4f);

        // ホバー状態へ遷移
        state.setHoveredNode(doc.root, "card");
        LayoutBox root2 = parser.layoutDocument(doc, 320, 200, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);
        List<UIElement> els2 = new UIRenderer(320, 200, doc.sheet.keyframes, new ScrollState()).render(root2);
        UIElement node2 = findElementById(els2, "card");
        assertNotNull(node2);

        // t0 + 100ms（200ms の半分）で評価:
        // transition-property: opacity なので opacity は 0.6 に補間され、transform (tx) は即座に 40px にスナップする
        WebAnimator.getValuesAt(sessionId, node2, t0);
        WebAnimator.AnimatedValues vMid = WebAnimator.getValuesAt(sessionId, node2, t0 + 100L);
        assertEquals(0.6f, vMid.opacity(), 1e-3f, "transition-property に含まれる opacity は中間値に補間される");
        assertEquals(40.0f, vMid.tx(), 1e-3f, "transition-property に含まれない transform は即座に目標値へスナップする");

        WebAnimator.clearSession(sessionId);
    }

    @Test
    public void testKeyframesColorDirectionFillModeAndNonOverwritingTransition() {
        String sessionId = "test_kf_session";
        WebAnimator.clearSession(sessionId);

        UIParser parser = new UIParser();
        UIParser.Document doc = parser.parseDocument("""
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

        LayoutBox root1 = parser.layoutDocument(doc, 320, 200, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);
        List<UIElement> els1 = new UIRenderer(320, 200, doc.sheet.keyframes, new ScrollState()).render(root1);
        UIElement actor1 = findElementById(els1, "actor");
        assertNotNull(actor1);

        long t0 = 2_000_000L;
        WebAnimator.getValuesAt(sessionId, actor1, t0);

        // ホバーを発生させ、opacity のトランジションと @keyframes slideX が同時に走る状態にする
        state.setHoveredNode(doc.root, "actor");
        LayoutBox root2 = parser.layoutDocument(doc, 320, 200, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);
        List<UIElement> els2 = new UIRenderer(320, 200, doc.sheet.keyframes, new ScrollState()).render(root2);
        UIElement actor2 = findElementById(els2, "actor");
        assertNotNull(actor2);

        WebAnimator.getValuesAt(sessionId, actor2, t0);
        WebAnimator.AnimatedValues vMid = WebAnimator.getValuesAt(sessionId, actor2, t0 + 100L);
        assertEquals(50.0f, vMid.tx(), 1e-2f, "@keyframes の translateX が 50% 進行している");
        assertEquals(0.5f, vMid.opacity(), 1e-2f, "@keyframes が触っていない opacity のトランジションが潰されない");

        // 200ms 経過後も forwards により tx=100px が保持される
        WebAnimator.AnimatedValues vEnd = WebAnimator.getValuesAt(sessionId, actor2, t0 + 300L);
        assertEquals(100.0f, vEnd.tx(), 1e-2f, "animation-fill-mode: forwards により終了後も最終フレームの値が維持される");
        assertEquals(0.0f, vEnd.opacity(), 1e-2f);

        WebAnimator.clearSession(sessionId);
    }

    @Test
    public void testKeyframesBackgroundColorOnBlockElement() {
        String sessionId = "test_kf_bg_session";
        WebAnimator.clearSession(sessionId);

        UIParser parser = new UIParser();
        UIParser.Document doc = parser.parseDocument("""
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

        LayoutBox root = parser.layoutDocument(doc, 320, 200, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);
        List<UIElement> els = new UIRenderer(320, 200, doc.sheet.keyframes, new ScrollState()).render(root);
        UIElement bgEl = findElementById(els, "panel_bg");
        assertNotNull(bgEl, "背景アニメーションがある場合は背景要素が出力される");

        long t0 = 3_000_000L;
        WebAnimator.getValuesAt(sessionId, bgEl, t0);
        WebAnimator.AnimatedValues vHalf = WebAnimator.getValuesAt(sessionId, bgEl, t0 + 50L);
        int rHalf = (vHalf.bgColor() >>> 16) & 0xFF;
        assertTrue(rHalf >= 120 && rHalf <= 135, "50ms 時点で背景色が中間グレー付近に補間される: " + rHalf);

        // alternate の 2 周目 (t0 + 175ms = 2周目の75% → 逆再生で 25%)
        WebAnimator.AnimatedValues vAlt = WebAnimator.getValuesAt(sessionId, bgEl, t0 + 175L);
        int rAlt = (vAlt.bgColor() >>> 16) & 0xFF;
        assertTrue(rAlt >= 55 && rAlt <= 75, "alternate の 2 周目後半は逆方向に補間される: " + rAlt);

        WebAnimator.clearSession(sessionId);
    }

    @Test
    public void testAnimationDelayAndBackwardsFillMode() {
        String sessionId = "test_anim_delay_session";
        WebAnimator.clearSession(sessionId);

        UIParser parser = new UIParser();
        UIParser.Document doc = parser.parseDocument("""
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

        LayoutBox root = parser.layoutDocument(doc, 320, 200, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);
        List<UIElement> els = new UIRenderer(320, 200, doc.sheet.keyframes, new ScrollState()).render(root);
        UIElement elBackwards = findElementById(els, "delayedBackwards");
        UIElement elNone = findElementById(els, "delayedNone");
        assertNotNull(elBackwards);
        assertNotNull(elNone);

        long t0 = 4_000_000L;
        // t0 でアニメーション開始時刻を確定
        WebAnimator.getValuesAt(sessionId, elBackwards, t0);
        WebAnimator.getValuesAt(sessionId, elNone, t0);

        // 遅延中 (t0 + 40ms) の評価
        WebAnimator.AnimatedValues vBackwardsDelay = WebAnimator.getValuesAt(sessionId, elBackwards, t0 + 40L);
        WebAnimator.AnimatedValues vNoneDelay = WebAnimator.getValuesAt(sessionId, elNone, t0 + 40L);

        assertEquals(50.0f, vBackwardsDelay.tx(), 1e-2f, "backwards は delay 中に 0% キーフレーム (50px) を適用する");
        assertEquals(0.0f, vNoneDelay.tx(), 1e-2f, "none は delay 中に基底スタイル (0px) を維持する");

        // アニメーション中 (t0 + 150ms: 遅延 100ms + 50ms 経過 = 50%) の評価
        WebAnimator.AnimatedValues vMid = WebAnimator.getValuesAt(sessionId, elBackwards, t0 + 150L);

        assertEquals(100.0f, vMid.tx(), 1e-2f, "進行度 50% で 50px と 150px の中間値 100px になる");

        // アニメーション終了後 (t0 + 250ms)
        // backwards 単独では終了後に forwards が無いため、基底スタイル (0px) に戻る
        WebAnimator.AnimatedValues vAfter = WebAnimator.getValuesAt(sessionId, elBackwards, t0 + 250L);
        assertEquals(0.0f, vAfter.tx(), 1e-2f, "forwards 指定が無いため終了後は基底スタイル (0px) に戻る");

        WebAnimator.clearSession(sessionId);
    }


    private static LayoutBox findById(LayoutBox box, String id) {
        if (box == null) return null;
        if (box.node != null && id.equals(box.node.getId())) return box;
        for (LayoutBox child : box.children) {
            LayoutBox hit = findById(child, id);
            if (hit != null) return hit;
        }
        return null;
    }

    private static UIElement findElementById(List<UIElement> elements, String id) {
        for (UIElement el : elements) {
            if (id.equals(el.id())) return el;
        }
        return null;
    }
}
