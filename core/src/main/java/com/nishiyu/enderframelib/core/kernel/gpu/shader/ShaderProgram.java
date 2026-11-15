package com.nishiyu.enderframelib.core.kernel.gpu.shader;

import java.util.List;

/**
 * 頂点・フラグメントの対と、それが読む定数（uniform）とサンプラーの宣言（G1 段 2 / G2）。API 中立: ここにあるのは名前と型と並びだけで、
 * GL はこれを {@code glUniform*} へ、Vulkan は 1 つの uniform ブロックへ写す。
 *
 * <p>定数は宣言の順に隙間なく並べた {@code ByteBuffer}（ネイティブ順）で渡す。型の大きさは {@link UniformType#bytes}。
 * サンプラーは宣言の順に、{@code GpuBindings#textures} の同じ番号のテクスチャを読む。</p>
 */
public record ShaderProgram(String id, String vertexBody, String fragmentBody, List<Uniform> uniforms, List<String> samplers,
                            int firstTextureUnit, List<RecordView> records) {
    /** サンプラーだけで定数も記録列も無いプログラム用（テクスチャは 0 番のユニットから）。 */
    public ShaderProgram(String id, String vertexBody, String fragmentBody, List<Uniform> uniforms, List<String> samplers) {
        this(id, vertexBody, fragmentBody, uniforms, samplers, 0, List.of());
    }

    /** レコード列のバッファを、シェーダーの{@code samplerBuffer} が引く形（同じバッファの別の読み方を別のサンプラーに）。 */
    public enum ViewFormat { FLOAT32, INT32, HALF16, RGBA_FLOAT32, RGBA_INT32 }

    /**
     * 記録列の読み口。{@code sampler} は GLSL の変数名、{@code bufferIndex} は {@code GpuBindings#records} の何番目のバッファか、
     * {@code unit} はそのサンプラーのテクスチャユニット（GL）。
     */
    public record RecordView(String sampler, int bufferIndex, ViewFormat format, int unit) {
    }

    /** 記録列のバッファの数（{@code GpuBindings#records} に要る本数）。 */
    public int recordBuffers() {
        int n = 0;
        for (RecordView v : records) n = Math.max(n, v.bufferIndex() + 1);
        return n;
    }

    public enum UniformType {
        FLOAT(4), VEC2(8), VEC3(12), VEC4(16), INT(4), MAT4(64), VEC4_ARRAY(16);

        public final int bytes;

        UniformType(int bytes) {
            this.bytes = bytes;
        }
    }

    public record Uniform(String name, UniformType type, int count) {
        public Uniform {
            if (count < 1) throw new IllegalArgumentException("count: " + count);
        }

        public Uniform(String name, UniformType type) {
            this(name, type, 1);
        }

        public int bytes() {
            return type.bytes * count;
        }
    }

    /** 定数の並びの合計バイト数。 */
    public int uniformBytes() {
        int n = 0;
        for (Uniform u : uniforms) n += u.bytes();
        return n;
    }
}
