package com.nishiyu.enderframelib.core.paint.gpu;

import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuBindings;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuDevice;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuPass;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuPipeline;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuTexture;
import com.nishiyu.enderframelib.core.kernel.gpu.shader.ShaderPrograms;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * {@link GpuTreeStore} のプリミティブ列を RHI パイプライン {@code "rects"}（{@link ShaderPrograms#RECTS}）で
 * 1 回のインスタンス描画として吐く API 非依存レンダラ。
 */
public final class RectRenderer implements AutoCloseable {
    private final GpuPipeline pipeline;
    private final GpuTexture[] textures = new GpuTexture[2];
    private final ByteBuffer uniforms = ByteBuffer.allocateDirect(ShaderPrograms.RECTS.uniformBytes())
            .order(ByteOrder.nativeOrder());

    public RectRenderer(GpuDevice device) {
        this.pipeline = device.createPipeline(new GpuDevice.PipelineDesc("rects", true, false));
    }

    /** テスト・診断用。 */
    public GpuPipeline pipeline() {
        return pipeline;
    }

    /** 先頭 {@code count} 個のプリミティブを描く。 */
    public void draw(GpuPass pass, GpuTreeStore store, GpuTexture atlasTex, GpuTexture imgTex,
                     int count, float frameMs, int viewW, int viewH) {
        draw(pass, store, atlasTex, imgTex, 0, count, frameMs, viewW, viewH, false);
    }

    /**
     * 描画順の {@code [first, first + count)} を現在開いている {@link GpuPass} に描く。
     * {@code flipY} が {@code true} なら上が {@code y = 0}、{@code false} なら下が {@code y = 0}。
     */
    public void draw(GpuPass pass, GpuTreeStore store, GpuTexture atlasTex, GpuTexture imgTex,
                     int first, int count, float frameMs, int viewW, int viewH, boolean flipY) {
        if (count <= 0) return;
        uniforms.clear();
        uniforms.putFloat(viewW).putFloat(viewH).putFloat(frameMs).putInt(first).putFloat(flipY ? -1f : 1f);
        uniforms.flip();
        textures[0] = atlasTex;
        textures[1] = imgTex;
        pass.bind(pipeline, new GpuBindings(store.recordBindings(), textures, uniforms));
        pass.drawInstanced(4, count, 0);
    }

    @Override
    public void close() {
        pipeline.close();
    }
}
