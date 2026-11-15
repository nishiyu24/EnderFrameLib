package com.nishiyu.enderframelib.core.kernel.gpu.rhi;

import java.nio.ByteBuffer;

/** GPU が読むバッファ。CPU は {@link #map()} が返すバッファへ書く（読んではならない。書いた内容はフレームの終わりまでに GPU へ届く）。 */
public interface GpuBuffer extends AutoCloseable {
    long size();

    GpuDevice.BufferUsage usage();

    /** 書き込み先。呼び出しの間ではなく、閉じるまで同じバッファを返す。 */
    ByteBuffer map();

    /**
     * {@link #map()} へ書いた {@code [offset, offset + length)} を変更済みとして印を付ける
     * （永続リング・段階転送で、実際に変わった範囲だけを GPU へ反映する。既定は何もしない）。
     */
    default void markDirty(long offset, long length) {}

    @Override
    void close();
}
