package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.css.StyleSheet;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.tree.PaintBox;
import com.nishiyu.enderframelib.core.text.model.FontResolver;

import java.util.List;
import java.util.Map;

/**
 * 記憶を持ち越さない 1 回だけのフレーム（M3b-2）。文書（または要素の部分木）から、箱の木 → 断片木 → {@code DisplayScene} → 表示リストを 1 度だけ作る。
 * 状態は呼び出しの中に閉じ、前回の木も記憶も持たない（対話のある画面は {@link UiView} / {@link StandaloneView} を使う）。
 */
public final class FrameRender {
    private FrameRender() { }

    /** 1 回のフレームの結果: 箱の見え方（位置は絶対）と表示リスト。 */
    public record Frame(PaintBox root, List<DisplayItem> items) { }

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
        List<DisplayItem> items = frame.frame(doc, null, w, h, dpr, env, st, scroll != null ? scroll : new ScrollState(), fonts);
        return new Frame(frame.root(), items);
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
        List<DisplayItem> items = frame.subtree(root, sheet, w, h, scroll != null ? scroll : new ScrollState(), fonts);
        return new Frame(frame.root(), items);
    }
}
