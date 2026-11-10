package com.nishiyu.enderframelib.core.dom;

import java.util.Map;

/**
 * ホストが持ち込む置換要素（{@code <mc-fluid>} など）の採寸と描画入力を、エンジン本体のタグ名の直書きなしに
 * 差し込む窓口。{@link ReplacedElements#register(String, CustomElementPainter)} で登録する。
 *
 * <p>描画の分岐（画像として描くか、シーンとして描くか）は {@link #kind()} で決まり、ホスト固有の部分は
 * 画像の参照先の解決（{@link #resolveSrc}）とシーンの既定寸法だけに限る。
 */
public interface CustomElementPainter {

    /** 置換要素の性質（採寸・描画の経路を決める）。 */
    ReplacedKind kind();

    /**
     * 画像として描くときの参照先を決める。{@code src} は {@code src} スタイル／属性から求めた既定値。
     * 返す文字列がそのままホストの画像解決器へ渡る。
     */
    default String resolveSrc(Map<String, String> attrs, String src) {
        return src;
    }

    /** {@link ReplacedKind#SCENE} の最小幅（CSS px）。 */
    default int sceneMinWidth() {
        return 64;
    }

    /** {@link ReplacedKind#SCENE} の既定の高さ（CSS px、内容高さの分は含まない）。 */
    default int sceneHeight() {
        return 120;
    }
}
