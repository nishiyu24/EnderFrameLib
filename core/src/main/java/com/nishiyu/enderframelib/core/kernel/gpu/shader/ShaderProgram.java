package com.nishiyu.enderframelib.core.kernel.gpu.shader;

import java.util.List;

/**
 * 頂点・フラグメントの対と、それが読む定数（uniform）とサンプラーの宣言（G1 段 2 / G2）。API 中立: ここにあるのは名前と型と並びだけで、
 * GL はこれを {@code glUniform*} へ、Vulkan は 1 つの uniform ブロックへ写す。
 *
 * <p>定数は宣言の順に隙間なく並べた {@code ByteBuffer}（ネイティブ順）で渡す。型の大きさは {@link UniformType#bytes}。
 * サンプラーは宣言の順に、{@code GpuPass.Bindings#textures} の同じ番号のテクスチャを読む。</p>
 */
public record ShaderProgram(String id, String vertexBody, String fragmentBody, List<Uniform> uniforms, List<String> samplers) {
    public enum UniformType {
        FLOAT(4), VEC2(8), VEC4(16), INT(4);

        public final int bytes;

        UniformType(int bytes) {
            this.bytes = bytes;
        }
    }

    public record Uniform(String name, UniformType type) {
    }

    /** 定数の並びの合計バイト数。 */
    public int uniformBytes() {
        int n = 0;
        for (Uniform u : uniforms) n += u.type().bytes;
        return n;
    }
}
