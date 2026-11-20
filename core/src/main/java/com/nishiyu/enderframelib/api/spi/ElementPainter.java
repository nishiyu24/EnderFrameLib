package com.nishiyu.enderframelib.api.spi;

import java.util.Map;

/**
 * ホストが持ち込む置換要素（例: {@code <my-avatar>}）の採寸と描画入力の差し込み。
 * 署名は JDK と {@code api.*} の型だけなので、エンジン内部の整理で互換性が壊れない。
 */
public interface ElementPainter {

    /** 置換要素の性質（採寸・描画の経路を決める）。 */
    ReplacedKind kind();

    /**
     * 画像として描くときの参照先を決める。{@code src} は {@code src} スタイル／属性から求めた既定値。
     * 返す文字列がそのまま画像の解決（{@code EnderEngine.Builder#images}）へ渡る。
     */
    default String resolveSrc(Map<String, String> attrs, String src) {
        return src;
    }

    /** {@link ReplacedKind#SCENE} の最小幅（CSS px）。 */
    default int sceneMinWidth() {
        return 64;
    }

    /** {@link ReplacedKind#SCENE} が幅 auto のとき親の幅いっぱいに広がるか。偽なら {@link #sceneMinWidth()} を固有の幅にする。 */
    default boolean sceneFillsWidth() {
        return true;
    }

    /** {@link ReplacedKind#SCENE} の既定の高さ（CSS px）。 */
    default int sceneHeight() {
        return 120;
    }
}
