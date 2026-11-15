package com.nishiyu.enderframelib.core.kernel.gpu.rhi;

import java.nio.ByteBuffer;

/**
 * 1 フレームの命令の入れ物。領域 {@code region} は {@code {x, y, 幅, 高さ}}（左上原点・画素）。
 * 内容を読み書きする命令（{@link #upload}・{@link #copy}・{@link #readback}）は RGBA8 と R8 のテクスチャが対象。
 */
public interface GpuFrame {
    /** {@code scissor} は {@code {x, y, 幅, 高さ}}（無ければ null = 全面）。{@code clearArgb} は事前乗算の ARGB。 */
    GpuPass beginPass(GpuTexture target, GpuDevice.LoadOp load, int clearArgb, int[] scissor);

    /** {@code pixels} は先頭の行（上）から詰めて並べる（行間の余白なし）。位置は {@code buffer.position()} から。 */
    void upload(GpuTexture dst, int x, int y, int w, int h, ByteBuffer pixels);

    /** 同じ位置・同じ大きさの領域を {@code src} から {@code dst} へ写す。 */
    void copy(GpuTexture src, GpuTexture dst, int[] region);

    /** 領域を {@code dst} へ読み出す（先頭の行から詰める）。GPU の完了を待つので、フレーム中は多用しない。 */
    void readback(GpuTexture src, int[] region, ByteBuffer dst);

    /** 提出する。フェンスを置いて次の面へ進む。 */
    void end();
}
