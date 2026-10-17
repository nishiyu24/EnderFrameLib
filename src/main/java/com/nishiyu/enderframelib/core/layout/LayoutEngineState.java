package com.nishiyu.enderframelib.core.layout;

/**
 * レイアウトエンジンがボックスに持たせる、エンジン固有の状態。
 *
 * 以前は {@code LayoutBox.engineState} が {@code Object} で、スクロール範囲を求めるために
 * {@code LayoutBox} が {@code instanceof GridLayoutEngine.GridState} でエンジン内部型を
 * 覗き込んでいた（core/layout の中で循環していた）。
 * 範囲への寄与をこのインターフェースの責務にすることでその参照を断つ。
 */
public interface LayoutEngineState {

    /**
     * 通常フローの子以外に、このエンジンが配置した要素の範囲を足し込む。
     * 何も追加しないエンジンは実装しなくてよい（既定は何もしない）。
     */
    default void contributeScrollExtent(ScrollExtent extent, int scrollX, int scrollY) {
    }
}
