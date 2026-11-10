package com.nishiyu.enderframelib.core.dom;

/** ホスト置換要素の性質（採寸・描画の経路を決める）。SPI（{@link CustomElementPainter}）が参照するので L3 に置く。 */
public enum ReplacedKind {
    /** 画像と同様に固有サイズを持ち、画像として採寸される（流体・エンティティなど）。 */
    IMAGE_LIKE,
    /** 属性だけで描かれ、固有サイズは別途解決される（3D シーンなど）。 */
    SCENE
}
