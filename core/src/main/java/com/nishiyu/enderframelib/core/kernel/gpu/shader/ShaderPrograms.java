package com.nishiyu.enderframelib.core.kernel.gpu.shader;

import com.nishiyu.enderframelib.core.kernel.gpu.shader.ShaderProgram.Uniform;
import com.nishiyu.enderframelib.core.kernel.gpu.shader.ShaderProgram.UniformType;
import java.util.List;

/** 名前（{@code PipelineDesc#shaderId}）で引けるシェーダープログラムの一覧。段ごとに増やす。 */
public final class ShaderPrograms {
    private ShaderPrograms() {}

    /**
     * 画面と同じ位置の画素を、{@code uDev}（四隅 x0, y0, x1, y1。左上原点の画素）の範囲だけ {@code uTex} から写す。事前乗算の画素を 1 対 1 で、
     * {@code uOpacity} を掛けて重ねる。{@code uFlip} は 0 でテクスチャの行を描画先と同じ向きで読み（RHI のテクスチャ同士）、
     * 1 で上下反転して読む（左上原点の CPU の画素を、そのまま上げたとき）。
     */
    public static final ShaderProgram TEX = new ShaderProgram("tex", ShaderSources.TEX_VERT, ShaderSources.TEX_FRAG,
            List.of(new Uniform("uDev", UniformType.VEC4), new Uniform("uView", UniformType.VEC2),
                    new Uniform("uOpacity", UniformType.FLOAT), new Uniform("uFlip", UniformType.INT)),
            List.of("uTex"));

    private static final List<ShaderProgram> ALL = List.of(TEX);

    /** 名前で引く。無ければ {@link IllegalArgumentException}。 */
    public static ShaderProgram byId(String id) {
        for (ShaderProgram p : ALL) if (p.id().equals(id)) return p;
        throw new IllegalArgumentException("未知のシェーダー: " + id);
    }
}
