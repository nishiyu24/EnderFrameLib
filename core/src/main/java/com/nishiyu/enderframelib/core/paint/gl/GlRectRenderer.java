package com.nishiyu.enderframelib.core.paint.gl;

import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuBuffer;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuDevice;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuPass;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuPipeline;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuTexture;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;

/**
 * {@link GlTreeStore} のプリミティブを 1 回のインスタンス描画で描く。CPU は頂点も DisplayList も作らず、
 * {@code rects} パイプライン（RHI。{@code ShaderPrograms.RECTS}）を結んで {@code drawInstanced} を 1 回呼ぶだけ。
 * 描画先（FBO）・ビューポート・クリアは呼び出し側。合成は {@code (ONE, ONE_MINUS_SRC_ALPHA)}（プリマルチプライド）。
 *
 * <p>GL の呼び出しは {@link GlDevice} が持つ（G1 段 3）。ここに残るのは、ストアの 7 本のバッファを記録列として渡す形と、
 * 呼び出し側の描画先へ描くための橋（{@link GlDevice#passOnCurrentFramebuffer}）だけ。</p>
 */
public final class GlRectRenderer implements AutoCloseable {
    private final GlDevice device = new GlDevice();
    private final GpuPipeline pipeline;
    /** ビュー（0 番目から 7 本）の並びは {@code ShaderPrograms.RECTS} の記録列の宣言順。 */
    private final GpuBuffer[] records = new GpuBuffer[7];
    /** 字形アトラスと画像（ユニット 1・2）。呼び出し側のバックエンドが GL のテクスチャを結んでいるので、結び直さずに指す。 */
    private final GpuTexture[] textures;
    private final ByteBuffer uniforms = ByteBuffer.allocateDirect(20).order(ByteOrder.nativeOrder());

    public GlRectRenderer() {
        // 新経路のバッファテクスチャは 16 番以降のユニットに結ぶ（従来のバックエンドが 0〜6 を使う。型の違うサンプラーを同じユニットに指すとエラー）
        if (GL11.glGetInteger(org.lwjgl.opengl.GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS) < 28) {
            throw new IllegalStateException("テクスチャユニットが足りない");
        }
        this.pipeline = device.createPipeline(new GpuDevice.PipelineDesc("rects", true, false));
        // アトラス・画像は呼び出し側が glBindTexture した状態を前提にする（ユニット 1・2 を現在のバインドのまま使う）
        this.textures = new GpuTexture[]{device.wrapCurrentUnit(1), device.wrapCurrentUnit(2)};
    }

    /** 設定したサンプラーの数（テスト用: 描画を重ねても増えない）。 */
    int samplerSetCount() { return device.samplerUniformSets(pipeline); }

    /**
     * 先頭 {@code count} 個（描画順）のプリミティブを描く。{@code frameMs} がアニメ・スクロール評価の現在時刻。
     * 呼び出し側が VAO を結んでおくこと（コア・プロファイルは VAO 無しで描けない）。
     */
    public void draw(GlTreeStore store, int count, float frameMs, int viewW, int viewH) {
        draw(store, 0, count, frameMs, viewW, viewH, false);
    }

    /**
     * 描画順の {@code [first, first+count)} を描く。{@code flipY} なら画面の上が y=0（バックエンドの FBO の向き）、
     * そうでなければ GL の下が y=0。
     */
    public void draw(GlTreeStore store, int first, int count, float frameMs, int viewW, int viewH, boolean flipY) {
        if (count <= 0) return;
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int srcA = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), dstA = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        drawCore(store, first, count, frameMs, viewW, viewH, flipY);
        GL14.glBlendFuncSeparate(srcRgb, dstRgb, srcA, dstA);
        if (!blend) GL11.glDisable(GL11.GL_BLEND);
    }

    /** 呼び出し側が既に {@code GL_BLEND} 有効・事前乗算 {@code (ONE, ONE_MINUS_SRC_ALPHA)} に揃えているとき用（状態問い合わせを省く）。 */
    void drawPremulBlend(GlTreeStore store, int first, int count, float frameMs, int viewW, int viewH, boolean flipY) {
        if (count <= 0) return;
        drawCore(store, first, count, frameMs, viewW, viewH, flipY);
    }

    private void drawCore(GlTreeStore store, int first, int count, float frameMs, int viewW, int viewH, boolean flipY) {
        records[0] = store.primitives;
        records[1] = store.order;
        records[2] = store.transforms;
        records[3] = store.scrolls;
        records[4] = store.curves;
        records[5] = store.effects;
        records[6] = store.clips;
        uniforms.clear();
        uniforms.putFloat(viewW).putFloat(viewH).putFloat(frameMs).putInt(first).putFloat(flipY ? -1f : 1f);
        uniforms.flip();
        GpuPass pass = device.passOnCurrentFramebuffer();
        pass.bind(pipeline, new GpuPass.Bindings(records, textures, uniforms));
        pass.drawInstanced(4, count, 0);
        pass.end();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    @Override
    public void close() {
        pipeline.close();
        device.close();
    }
}
