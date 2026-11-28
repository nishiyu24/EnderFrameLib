package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.raster.PngEncoder;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 1 つのフレームの値（{@link RenderFrame}）を画素にする描画バックエンドの共通口（M3b-4c-2a。計画書 §1.6 決定 V）。
 * Minecraft に依存しない実装として、CPU の {@code SoftwareRenderBackend} と
 * 純 LWJGL の {@code LwjglGlRenderBackend}（GPU）がある。呼び出し側は実装を差し替えるだけでよい。
 *
 * <p>同じインスタンスで {@link #render} を繰り返すと、補間（{@code transition} / {@code @keyframes}）の状態を保ったまま
 * 次のフレームを描く。スレッドセーフではない（GPU 版は作ったスレッドからだけ呼ぶ）。
 */
public interface RenderBackend extends AutoCloseable {

    /**
     * 描く前に、前に描いたフレームから描き直す範囲を求める（範囲を求めない描画器は全面）。描き直しが要るなら（{@link PreparedFrame#needsRepaint}）
     * 返した値をそのまま {@link #render(PreparedFrame)} に渡す。要らなければ描かずに前の画素を使ってよい（前の画素を持ち続ける描画先のホスト）。
     * 比べる相手はここで 1 度だけ進める。範囲はシーンの違い・塗りの色・利用者の平行移動と、シーンの外で変わるもの（補間・外の事情・重ね描き）から。
     */
    default PreparedFrame prepare(RenderFrame frame) {
        return PreparedFrame.full(frame);
    }

    /** 準備したフレームを描く（描画先を塗り直し、時刻 {@code frame.nowMs()} の見た目で描く。最後に準備した値でなければ全面）。 */
    void render(PreparedFrame frame);

    /** 準備して描く（範囲を求めて描く。描き直しが要らなくても描く）。 */
    default void render(RenderFrame frame) {
        render(prepare(frame));
    }

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
