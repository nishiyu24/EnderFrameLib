package com.nishiyu.enderframelib.api;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * {@link EnderEngine} の裏で実際に面を作る実装の SPI。api は実装パッケージに依存しないので、実装側
 * （エンジン本体）が {@link java.util.ServiceLoader} 経由でこれを提供する。利用者が直接触ることは無い。
 */
@ApiStatus.Internal
public interface EnginePlatform {

    /** 面の作り方の指定。 */
    record Spec(String html, int width, int height, float devicePixelRatio, int clearColor,
                EnderEngine.Target target, @Nullable PixelSink sink,
                java.util.List<SurfaceFont> fonts, boolean systemFonts,
                @Nullable ImageSource images, boolean webImages) {}

    WebViewSurface create(Spec spec);

    /** GLFW の窓に表示する（閉じるまで戻らない）。 */
    void openWindow(String html, int width, int height);
}
