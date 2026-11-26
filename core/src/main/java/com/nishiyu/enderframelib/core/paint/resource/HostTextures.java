package com.nishiyu.enderframelib.core.paint.resource;

import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.TextureRef;

/**
 * ホストが自分の描画系で描いた中身を、項目の中身として渡す差し込み口（GPU のバックエンドだけが読む）。
 *
 * <p>コアが描けない中身（ゲームのアイテム・エンティティのモデル、ホストの 3D のシーン、ホストのビットマップ書体の文字）を、
 * ホストはフレームの前に自分のテクスチャへ描いておき、ここでその参照を返す。コアは項目の背景・枠線・クリップ・不透明度・
 * 重なりの順を自分で描き、中身の矩形にこのテクスチャを貼る。ホストの描画をコアの上に後から重ねる形と違い、
 * 前後関係（ポップアップがアイテムを隠す等）が崩れない。
 *
 * <p>聞かれるのは中身を持つ項目（画像・シーン・文字）だけ。null を返せばコアが普段どおり描く。
 * テクスチャの中身は、同じ参照を返す間は変えない（変えるなら別の参照にするか、項目を作り直す）。ただし画像とシーンの項目は
 * 外から駆動される種別なので、毎フレーム中身が変わってもよい。CPU のバックエンドは聞かない（画素の読み戻しをしない）。
 */
@FunctionalInterface
public interface HostTextures {

    /** 何も渡さない（既定）。 */
    HostTextures NONE = (item, deviceW, deviceH) -> null;

    /**
     * @param item    描く項目
     * @param deviceW 中身の矩形の幅（デバイス画素の目安）
     * @param deviceH 中身の矩形の高さ（デバイス画素の目安）
     * @return ホストのテクスチャ。コアに描かせるなら null
     */
    TextureRef texture(DisplayItem item, int deviceW, int deviceH);
}
