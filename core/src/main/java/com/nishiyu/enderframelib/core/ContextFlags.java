package com.nishiyu.enderframelib.core;

import com.nishiyu.enderframelib.core.kernel.EngineFlags;

/**
 * 文脈ごとの切り替え（ENGINE_REARCHITECTURE_PLAN S1 ②）。切り分け・診断用で、近道の切り替えは結果を変えてはならない。
 * 不変の値で、{@link EngineContext#updateFlags} が丸ごと差し替える。初期値は {@link EngineFlags}（起動時のシステムプロパティ）から取る。
 *
 * @param incrementalLayout          差分レイアウト。false なら前回の結果があっても全部を作り直す
 * @param pruneCleanSubtrees         再スタイルの走査で、汚れていない部分木を丸ごと飛ばす
 * @param trackAllocations           診断用: true の間、各段の割り当て量を診断文字列へ足す（既定 false で費用なし）
 * @param localRelayout              局所レイアウト。false なら常に木全体の印付けをする
 * @param inPlaceRestyleRelayout     局所再レイアウト（箱の木を作り直さない）。false なら常に木を作り直す
 * @param parentRelativeDisplayItems B3: 表示項目の座標を親 NODE の原点からの相対にする。false ならページの絶対座標（切り分け・パリティ検証用）
 */
public record ContextFlags(
        boolean incrementalLayout,
        boolean pruneCleanSubtrees,
        boolean trackAllocations,
        boolean localRelayout,
        boolean inPlaceRestyleRelayout,
        boolean parentRelativeDisplayItems) {

    /** 既定（近道は全部オン・診断はオフ。親相対は {@code -Denderframe.paint.noparentrelative} で切れる）。 */
    public static final ContextFlags DEFAULTS = new ContextFlags(true, true, false, true, true, EngineFlags.PARENT_RELATIVE);

    public ContextFlags withIncrementalLayout(boolean v) {
        return new ContextFlags(v, pruneCleanSubtrees, trackAllocations, localRelayout, inPlaceRestyleRelayout, parentRelativeDisplayItems);
    }

    public ContextFlags withPruneCleanSubtrees(boolean v) {
        return new ContextFlags(incrementalLayout, v, trackAllocations, localRelayout, inPlaceRestyleRelayout, parentRelativeDisplayItems);
    }

    public ContextFlags withTrackAllocations(boolean v) {
        return new ContextFlags(incrementalLayout, pruneCleanSubtrees, v, localRelayout, inPlaceRestyleRelayout, parentRelativeDisplayItems);
    }

    public ContextFlags withLocalRelayout(boolean v) {
        return new ContextFlags(incrementalLayout, pruneCleanSubtrees, trackAllocations, v, inPlaceRestyleRelayout, parentRelativeDisplayItems);
    }

    public ContextFlags withInPlaceRestyleRelayout(boolean v) {
        return new ContextFlags(incrementalLayout, pruneCleanSubtrees, trackAllocations, localRelayout, v, parentRelativeDisplayItems);
    }

    public ContextFlags withParentRelativeDisplayItems(boolean v) {
        return new ContextFlags(incrementalLayout, pruneCleanSubtrees, trackAllocations, localRelayout, inPlaceRestyleRelayout, v);
    }
}
