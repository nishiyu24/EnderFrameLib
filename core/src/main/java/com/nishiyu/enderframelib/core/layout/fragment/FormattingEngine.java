package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxKind;
import com.nishiyu.enderframelib.core.box.BoxNode;

/**
 * 整形コンテキストの SPI（Engine v3 M2a。計画書 §3.4）。状態を持たない純粋な変換で、実装は不変の単一実例。
 *
 * <p>{@code prior} は同じ DOM の節の前回（または同じフレームの別パス）の断片で、子のキャッシュの候補を引くためだけに使う
 * （照合は子ごとに {@code box ==} と制約の値等価で行う）。無ければ null。</p>
 */
public interface FormattingEngine {

    /** {@code node} を {@code space} で整形する。 */
    LayoutFragment layout(BoxNode node, ConstraintSpace space, LayoutFragment prior, LayoutSession session);

    /** 内在サイズ（border-box の {@code min-content} / {@code max-content} 幅）。 */
    MinMaxSizes computeMinMaxSizes(BoxNode node, MinMaxContext ctx, LayoutSession session);

    /** 箱の種類に応じたエンジン。 */
    static FormattingEngine of(BoxNode node) {
        if (OrthogonalFlow.is(node)) return OrthogonalFlow.INSTANCE;
        if (ReplacedFlow.is(node)) return ReplacedFlow.INSTANCE;
        if (node.kind.flexContainer()) return FlexFlow.INSTANCE;
        if (node.kind.tableContainer()) return TableFlow.INSTANCE;
        return node.kind.gridContainer() ? GridFlow.INSTANCE : BlockFlow.INSTANCE;
    }
}
