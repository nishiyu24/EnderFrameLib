package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.raster.PngEncoder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * ディスプレイリスト（{@link DisplayItem} の列）を画素にする描画バックエンドの共通口。
 * Minecraft に依存しない実装として、CPU の {@code SoftwareRenderBackend} と
 * 純 LWJGL の {@code LwjglGlRenderBackend}（GPU）がある。呼び出し側は実装を差し替えるだけでよい。
 *
 * <p>同じインスタンスで {@link #render} を繰り返すと、補間（{@code transition} / {@code @keyframes}）の状態を保ったまま
 * 次のフレームを描く。スレッドセーフではない（GPU 版は作ったスレッドからだけ呼ぶ）。
 */
public interface RenderBackend extends AutoCloseable {

    /** 描画先を塗り直し、{@code items} を時刻 {@code nowMs}（ミリ秒）の見た目で描く。 */
    void render(List<DisplayItem> items, long nowMs);

    /**
     * 直近のフレームの画素（事前乗算の ARGB、左上原点）。GPU 版は呼んだ時点で読み戻す。
     * 返した {@link Pixmap} は次の {@link #render} まで有効。
     */
    Pixmap pixmap();

    /** 1 CSS px が何画素か。 */
    float devicePixelRatio();

    /** PNG として書き出す（親のフォルダは作る。AWT を使わない {@link PngEncoder}）。 */
    default void writePng(Path file) throws IOException {
        PngEncoder.write(file, pixmap());
    }

    /** 補間状態（CssAnimator）の持ち主の ID。ヒットテストが変形中の要素を引くのに使う。持たない実装は空。 */
    default String sessionId() {
        return "";
    }

    /** 保持している資源を解放する。既定は何もしない。 */
    @Override
    default void close() {
    }
}
