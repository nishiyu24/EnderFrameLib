package com.nishiyu.enderframelib.core.kernel.gpu.rhi;

import java.nio.ByteBuffer;

/** GPU が読むバッファ。CPU は {@link #map()} が返すバッファへ書く（読んではならない。書いた内容はフレームの終わりまでに GPU へ届く）。 */
public interface GpuBuffer extends AutoCloseable {
    long size();

    GpuDevice.BufferUsage usage();

    /** 書き込み先。呼び出しの間ではなく、閉じるまで同じバッファを返す。 */
    ByteBuffer map();

    @Override
    void close();
}
