package com.nishiyu.enderframelib.core.kernel.gpu.rhi;

/**
 * 1 つの描画先への一連の描画。粒度は「パス」と「バッチ」で、図形ごとに呼ぶ口は無い
 * （図形はレコード列に書かれ、{@link #drawInstanced} 1 回で描く）。
 */
public interface GpuPass {
    void bind(GpuPipeline pipeline, GpuBindings bindings);

    /** インデックス付きメッシュ描画用の頂点バッファとインデックスバッファを結ぶ（3D 用）。 */
    default void bindMesh(GpuBuffer vertices, GpuBuffer indices) {}

    void drawInstanced(int vertexCount, int instanceCount, int baseInstance);

    /** インデックス付きインスタンス描画（3D 用）。 */
    default void drawIndexedInstanced(int indexCount, long indexOffsetBytes, int instanceCount, int baseVertex, int baseInstance) {
        drawInstanced(indexCount, instanceCount, baseInstance);
    }

    void end();
}
