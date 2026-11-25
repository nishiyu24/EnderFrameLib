package com.nishiyu.enderframelib.common;

/**
 * ビューが使うレイアウトと描画の経路（M2c ④。計画書 §3.4.2-4）。ビューの構築時の引数として持ち、プロセス共有のスイッチ
 * （{@code Shortcut}・{@code EngineFlags}）にはしない。旧経路を消す M2b-6 で、この型ごと消す。
 */
public enum EnginePath {
    /** 旧経路: {@code LayoutBox} の木 → {@code DisplayListBuilder}。 */
    LEGACY,
    /** 新経路（Engine v3）: 箱の木 → 断片木 → 描画用の木 → {@code TreePainter}。入口は変更台帳だけ。 */
    V3
}
