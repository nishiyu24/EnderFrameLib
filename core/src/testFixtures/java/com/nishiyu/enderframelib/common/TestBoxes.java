package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.tree.PaintBox;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.tree.PaintView;
import com.nishiyu.enderframelib.core.text.model.FontResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * テストの道具（M2c-3）: HTML を 1 回だけ組んだ描画用の木（{@link PaintView}。位置は絶対）と表示リストを作り、箱を id・節で引く。
 * 旧経路の {@code LayoutBox} の木を読んでいたテストは、これで同じ問いを新経路の結果に向ける。
 */
public final class TestBoxes {
    private TestBoxes() { }

    /** {@code html} を {@code w}×{@code h} で組んだ根（内蔵の簡易書体）。 */
    public static PaintView layout(String html, int w, int h) {
        return layout(html, w, h, FontResolver.FALLBACK);
    }

    public static PaintView layout(String html, int w, int h, FontResolver fonts) {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument(html, "", w, h);
        return FrameRender.document(doc, w, h, Map.of(), new ScrollState(), fonts, new InteractionState()).root();
    }

    /** {@code html} を {@code w}×{@code h} で組んで描いた表示リスト（内蔵の簡易書体）。 */
    public static List<DisplayItem> render(String html, int w, int h) {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument(html, "", w, h);
        return FrameRender.document(doc, w, h, FontResolver.FALLBACK).items();
    }

    /** {@code id} 属性が {@code id} の要素の箱（匿名でない最初のもの。無ければ null）。 */
    public static PaintView find(PaintBox root, String id) {
        if (root == null) return null;
        if (!root.isAnonymous && root.node != null && !root.node.isPseudoNode && id.equals(root.node.attrs.get("id"))) return (PaintView) root;
        for (PaintBox c : root.paintChildren()) {
            PaintView hit = find(c, id);
            if (hit != null) return hit;
        }
        return null;
    }

    /** 節 {@code node} の箱（匿名・疑似要素でない最初のもの。無ければ null）。 */
    public static PaintView byNode(PaintBox root, HtmlNode node) {
        if (root == null) return null;
        if (!root.isAnonymous && root.node == node && (node == null || !node.isPseudoNode)) return (PaintView) root;
        for (PaintBox c : root.paintChildren()) {
            PaintView hit = byNode(c, node);
            if (hit != null) return hit;
        }
        return null;
    }

    /**
     * DOM と 1 対 1 の子の箱（旧 {@code LayoutBox.elementChildren} に当たるもの。要素・文字・疑似要素）: 匿名の箱（匿名ブロック・
     * 匿名表）は中へ降り、{@code ::marker} は含めない。{@code display: contents} の要素は箱を持たないので現れない。
     */
    public static List<PaintView> elements(PaintBox box) {
        List<PaintView> out = new ArrayList<>();
        collectElements(box, out);
        return out;
    }

    private static void collectElements(PaintBox box, List<PaintView> out) {
        for (PaintBox c : box.paintChildren()) {
            if (c.isAnonymous) {
                if (c.node == null || c.node.getId() == null || !c.node.getId().endsWith("_marker")) collectElements(c, out);
            } else if (c.node != null) {
                out.add((PaintView) c);
            }
        }
    }

    /** {@link #elements} のうち要素の箱だけ（文字の箱を除く。疑似要素は含む）。 */
    public static List<PaintView> elementBoxes(PaintBox box) {
        List<PaintView> out = new ArrayList<>(elements(box));
        out.removeIf(b -> b.node.nodeType != NodeType.ELEMENT);
        return out;
    }
}
