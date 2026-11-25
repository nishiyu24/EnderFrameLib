package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.box.StyleSource;
import com.nishiyu.enderframelib.core.layout.fragment.FragmentPipeline;
import com.nishiyu.enderframelib.core.layout.fragment.LayoutFragment;
import com.nishiyu.enderframelib.core.layout.fragment.LayoutSession;
import com.nishiyu.enderframelib.core.paint.TreePainter;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.tree.PaintNode;
import com.nishiyu.enderframelib.core.paint.tree.PaintTreeBuilder;
import com.nishiyu.enderframelib.core.text.model.FontResolver;

import java.util.List;

/**
 * 記憶の無い新経路で、ビューの今の文書・対話の状態・スクロールの状態から表示リストをゼロから作る（増分の結果と比べる独立の基準）。
 * 箱の木・断片・描画用の木・描画器のどれも前回を持たないので、レイアウトの記憶の古さも描画の記憶の古さも同じ形で見つかる。
 */
final class V3Fresh {
    private V3Fresh() { }

    static List<DisplayItem> displayList(UiView ui) {
        int w = ui.rootW(), h = ui.rootH();
        Document doc = ui.document();
        LayoutSession session = new LayoutSession(FontResolver.FALLBACK);
        FragmentPipeline p = new FragmentPipeline(new StyleSource(doc.sheet, ui.interactionState(), ui.env(), w, h), session);
        LayoutFragment f = p.frame(doc.root, null, w, h);
        PaintNode tree = new PaintTreeBuilder(session, doc.sheet).build(f, p.boxRoot());
        return new TreePainter(w, h, doc.sheet.keyframes, ui.scrollState()).paint(tree);
    }
}
