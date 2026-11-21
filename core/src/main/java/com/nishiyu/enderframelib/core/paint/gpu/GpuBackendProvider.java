package com.nishiyu.enderframelib.core.paint.gpu;

import com.nishiyu.enderframelib.core.text.model.FontResolver;
import com.nishiyu.enderframelib.core.paint.RenderBackend;
import java.util.function.LongFunction;

/**
 * GPU 描画の実装（OpenGL・Vulkan など）を {@link java.util.ServiceLoader} で見つけるための SPI。
 * {@code :core} は実装のクラスを持たず、実装モジュール（{@code core-gl}・{@code core-vulkan}）が自分を登録する。
 */
public interface GpuBackendProvider {

    /** 実装の識別名（{@code "gl"}・{@code "vulkan"}）。 */
    String id();

    /** {@link GpuApiKind} のうち、この実装がどれか。 */
    GpuApiKind api();

    /** 小さいほど先に試す（{@code AUTO} の順）。 */
    int priority();

    /** 副作用なしの安い確認（必要なクラスがクラスパスにあるか）。作れるかどうかは {@link #create} が決める。 */
    boolean available();

    /** 描画先を作る。作れなければ例外（{@code AUTO} は次の実装へ進む）。 */
    RenderBackend create(Request request);

    /** 結果の色テクスチャのネイティブハンドル（{@link #create} で作ったバックエンドのもの）。 */
    long colorTexture(RenderBackend backend);

    /**
     * 作成の指定。
     *
     * @param host ホストが持つ GPU デバイス。{@code null} なら自前で作る
     */
    record Request(int widthCss, int heightCss, float devicePixelRatio, FontResolver fonts,
                   com.nishiyu.enderframelib.core.paint.resource.ImageProvider images,
                   LongFunction<com.nishiyu.enderframelib.core.scene3d.Scene3DWorld> scene3dWorlds, HostDevice host) {

        /** ホストデバイスなし。 */
        public Request(int widthCss, int heightCss, float devicePixelRatio, FontResolver fonts,
                       com.nishiyu.enderframelib.core.paint.resource.ImageProvider images,
                       LongFunction<com.nishiyu.enderframelib.core.scene3d.Scene3DWorld> scene3dWorlds) {
            this(widthCss, heightCss, devicePixelRatio, fonts, images, scene3dWorlds, null);
        }
    }
}
