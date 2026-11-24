package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import java.util.ArrayList;
import java.util.List;

/**
 * 箱の木を作り直さずに測り直す通常の経路（再スタイルが見つけた変わった箱と、文字の変わった箱を同じ入口で測り直す）。
 * K5 で旧 {@code LocalPatches} の子の増減のパッチ・文字パッチを撤去し、これだけが残った。
 */
final class InPlaceRelayout {
    private final UiView view;

    InPlaceRelayout(UiView view) {
        this.view = view;
    }

    /**
     * 再スタイル済みの箱の木を、作り直さずに測り直して配置し直す（寸法の入力だけが変わったとき）。成功したら true。
     * 条件に合わないとき（false）は何も変えないので、呼び出し側が全体の再レイアウトに退く。
     * 箱の木の形を変える変化（表示の種類・生成内容など）は、呼び出し側が {@link RenderPipeline.RestyleResult#structural()} で除いてある。
     */
    boolean tryRelayoutInPlace(List<LayoutBox> textBoxes) {
        Document doc = view.document;
        if (!com.nishiyu.enderframelib.core.EngineContext.current().flags().inPlaceRestyleRelayout() || view.layoutRoot == null || doc == null || doc.root == null
                || doc.sheet == null) {
            return false;
        }
        if (doc.sheet.declaresContainers() || doc.sheet.usesContainerQueries()) return false;
        long startedAt = view.clock.nanos();
        view.applyOverrides();
        List<LayoutBox> changedBoxes = new ArrayList<>(RenderPipeline.layoutChangedBoxes());
        RenderPipeline.clearChangedBoxes();
        for (LayoutBox t : textBoxes) if (!changedBoxes.contains(t)) changedBoxes.add(t);
        RenderPipeline.relayoutInPlace(view.layoutRoot, view.rootW, view.rootH, view.layoutRoot.devicePixelRatio(), view.scrollState, view.fontResolver(),
                changedBoxes);
        // 箱の木は作り替えていないので、節の箱の索引は捨てない（文字の更新ごとに全箱を索引し直さない）。文字の更新だけなら旧文字パッチと同じく残す
        if (textBoxes.isEmpty() && view.layoutRoot.subtreeHasContentAuto()) view.nodeBoxIndex = null;
        view.publishLayoutToDom();
        long layoutDone = view.clock.nanos();
        // 再スタイルは変わった箱に updateStyle で印を付け、レイアウトは触った箱に印を付ける（検証モードが付け漏れを検出する）
        List<DisplayItem> elements = view.paintBoxes(view.layoutRoot, view.rootW, view.rootH, false);
        if (elements == null) elements = new ArrayList<>();
        long paintDone = view.clock.nanos();
        view.publishBuilt(elements);
        long publishDone = view.clock.nanos();
        view.lastBreakdown = () -> "inplace-relayout layout=" + UiView.ms(startedAt, layoutDone)
                + "ms paint=" + UiView.ms(layoutDone, paintDone) + "ms publish=" + UiView.ms(paintDone, publishDone) + "ms";
        view.applyOverrides();
        view.lastLayoutFinishedNanos = view.clock.nanos();
        view.lastLayoutCostNanos = view.lastLayoutFinishedNanos - startedAt;
        UiView.INPLACE_RELAYOUT_HITS.incrementAndGet();
        return true;
    }
}
