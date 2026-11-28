package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.css.StyleSheet;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.scene.DisplayScene;
import com.nishiyu.enderframelib.core.paint.scene.DynamicSceneState;
import com.nishiyu.enderframelib.core.text.model.FontResolver;

import java.util.List;
import java.util.Map;

/**
 * 記憶を持ち越さない 1 回だけのフレーム（M3b-2）。文書（または要素の部分木）から、箱の木 → 断片木 → {@code DisplayScene} → 表示リストを 1 度だけ作る。
 * 状態は呼び出しの中に閉じ、前回の木も記憶も持たない（対話のある画面は {@link UiView} / {@link StandaloneView} を使う）。
 */
public final class FrameRender {
    private FrameRender() { }

    /** 1 回のフレームの結果: 不変の描画シーン・動的状態（スクロール等）と、描画器へ渡すシーンの添え物（表示項目の列は持たない。M3b-4c-2b-3b-2b-3）。 */
    public record Frame(DisplayScene scene, DynamicSceneState dynamic, com.nishiyu.enderframelib.core.paint.SceneSource source) {
        /**
         * 描画器へ渡すフレームの値（シーンつき・時刻 0。列は描画器が組む。描くホストが {@link com.nishiyu.enderframelib.core.paint.RenderFrame#at}
         * で時刻を入れる）。シーンが無ければ何も描かないフレーム。
         */
        public com.nishiyu.enderframelib.core.paint.RenderFrame renderFrame() {
            return new com.nishiyu.enderframelib.core.paint.RenderFrame(List.of(), List.of(), null, source, null, 0L);
        }
    }

    /** {@code frame} の結果（1 回だけの描画とテストの道具）。 */
    private static Frame result(V3Frame frame, ScrollState scroll) {
        DisplayScene scene = frame.scene();
        if (scene == null) return new Frame(null, null, null);
        DynamicSceneState dyn = frame.dynamicState(scroll);
        return new Frame(scene, dyn,
                new com.nishiyu.enderframelib.core.paint.SceneSource(scene, dyn, 0, frame.keyframes(), frame.devicePixelRatio()));
    }

    /**
     * 文書 {@code doc} を大きさ {@code w}×{@code h} で組んで描く。{@code state} は対話の状態（:hover など。無ければ新しい空の状態）で、
     * 算出スタイルの読み口（{@link InteractionState#computedStyleOf}）をこのフレームの箱の木に結ぶ（ビューの状態は渡さない）。
     */
    public static Frame document(Document doc, int w, int h, Map<String, String> env, ScrollState scroll, FontResolver fonts,
                                 InteractionState state) {
        V3Frame frame = new V3Frame();
        InteractionState st = state != null ? state : new InteractionState();
        st.bindStyleLookup(frame::styleOf); // 算出スタイル（CSSOM の getComputedStyle と同じ口）はこのフレームの箱の木から読む
        float dpr = doc.sheet != null ? doc.sheet.mediaEnvironment().resolution() : 1f;
        ScrollState effectiveScroll = scroll != null ? scroll : new ScrollState();
        frame.frame(doc, null, w, h, dpr, env, st, effectiveScroll, fonts);
        return result(frame, effectiveScroll);
    }

    /** {@link #document} の、空の対話の状態・空のスクロールの状態の形。 */
    public static Frame document(Document doc, int w, int h, FontResolver fonts) {
        return document(doc, w, h, Map.of(), new ScrollState(), fonts, new InteractionState());
    }

    /**
     * 要素 {@code root} の部分木だけを、ビューポート {@code w}×{@code h} の根として組んで描く（HUD の追跡表示など、文書の一部を
     * 別の面に描く用）。根の継承は初期値から始まる。
     */
    public static Frame subtree(HtmlNode root, StyleSheet sheet, int w, int h, ScrollState scroll, FontResolver fonts) {
        V3Frame frame = new V3Frame();
        ScrollState effectiveScroll = scroll != null ? scroll : new ScrollState();
        frame.subtree(root, sheet, w, h, effectiveScroll, fonts);
        return result(frame, effectiveScroll);
    }
}
