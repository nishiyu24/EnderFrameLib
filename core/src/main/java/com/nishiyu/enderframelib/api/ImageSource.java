package com.nishiyu.enderframelib.api;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * 画像（{@code <img src>}・{@code background-image}）の出所をホストが差し込む窓口。引けなければ {@code null} を返すと、
 * エンジンの既定の解決（{@code data:}・ローカルファイル・クラスパス、{@link EnderEngine.Builder#webImages} なら HTTP）へ回る。
 * 描画のたびに呼ばれうるので、結果は呼び出し側で覚えておくこと。
 */
@FunctionalInterface
@ApiStatus.AvailableSince("1.0")
public interface ImageSource {

    /** {@code src} 属性（または CSS の {@code url()}）の文字列から画像を引く。 */
    @Nullable
    SurfaceImage load(String src);
}
