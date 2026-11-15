package com.nishiyu.enderframelib.core.kernel.gpu.rhi;

import java.nio.ByteBuffer;

/** パイプラインと、それが読む資源。{@code records} はレコード列、{@code uniforms} はパスごとの定数ブロック（無ければ null）。 */
public record GpuBindings(GpuBuffer[] records, GpuTexture[] textures, ByteBuffer uniforms) {
    public static final GpuBindings NONE = new GpuBindings(new GpuBuffer[0], new GpuTexture[0], null);
}
