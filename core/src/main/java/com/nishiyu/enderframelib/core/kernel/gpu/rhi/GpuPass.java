package com.nishiyu.enderframelib.core.kernel.gpu.rhi;

import java.nio.ByteBuffer;

/**
 * 1 つの描画先への一連の描画。粒度は「パス」と「バッチ」で、図形ごとに呼ぶ口は無い
 * （図形はレコード列に書かれ、{@link #drawInstanced} 1 回で描く）。
 */
public interface GpuPass {
    /** パイプラインと、それが読む資源。{@code records} はレコード列、{@code uniforms} はパスごとの定数ブロック（無ければ null）。 */
    record Bindings(GpuBuffer[] records, GpuTexture[] textures, ByteBuffer uniforms) {
        public static final Bindings NONE = new Bindings(new GpuBuffer[0], new GpuTexture[0], null);
    }

    void bind(GpuPipeline pipeline, Bindings bindings);

    void drawInstanced(int vertexCount, int instanceCount, int baseInstance);

    void end();
}
