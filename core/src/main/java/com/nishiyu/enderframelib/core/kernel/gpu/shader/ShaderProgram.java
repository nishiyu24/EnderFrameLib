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

    /** 記録列のバッファの数（{@code GpuBindings#records} に要る本数）。 */
    public int recordBuffers() {
        int n = 0;
        for (RecordView v : records) n = Math.max(n, v.bufferIndex() + 1);
        return n;
    }

    public enum UniformType {
        FLOAT(4, 4), VEC2(8, 8), VEC3(12, 16), VEC4(16, 16), INT(4, 4), MAT4(64, 16), VEC4_ARRAY(16, 16);

        public final int bytes;
        public final int std140Align;

        UniformType(int bytes, int std140Align) {
            this.bytes = bytes;
            this.std140Align = std140Align;
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

    /** 各定数の std140 ブロック内の先頭バイト位置。 */
    public int[] std140Offsets() {
        int[] offsets = new int[uniforms.size()];
        int pos = 0;
        for (int i = 0; i < uniforms.size(); i++) {
            Uniform u = uniforms.get(i);
            int align = u.type().std140Align;
            pos = (pos + align - 1) & ~(align - 1);
            offsets[i] = pos;
            pos += u.bytes();
        }
        return offsets;
    }

    /** 最後の有効定数の末尾までのバイト数（呼出側バッファの最小必要サイズ）。 */
    public int uniformBytes() {
        int pos = 0;
        for (Uniform u : uniforms) {
            int align = u.type().std140Align;
            pos = (pos + align - 1) & ~(align - 1);
            pos += u.bytes();
        }
        return pos;
    }

    /** GLSL {@code std140} の uniform ブロック全体サイズ（16 バイト境界へ切り上げ）。 */
    public int uniformBlockBytes() {
        int raw = uniformBytes();
        return raw == 0 ? 0 : ((raw + 15) & ~15);
    }
}
