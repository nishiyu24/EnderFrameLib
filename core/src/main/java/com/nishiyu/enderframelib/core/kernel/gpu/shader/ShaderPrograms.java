package com.nishiyu.enderframelib.core.kernel.gpu.shader;

import com.nishiyu.enderframelib.core.kernel.gpu.shader.ShaderProgram.RecordView;
import com.nishiyu.enderframelib.core.kernel.gpu.shader.ShaderProgram.Uniform;
import com.nishiyu.enderframelib.core.kernel.gpu.shader.ShaderProgram.UniformType;
import com.nishiyu.enderframelib.core.kernel.gpu.shader.ShaderProgram.ViewFormat;
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

    /**
     * 矩形（SDF 図形）。図形・変換・クリップ・効果・スクロール・曲線・描画順は Property Tree のレコード列（7 本）として渡し、
     * 添字で引いて {@code drawInstanced} 1 回で描く。2D のサンプラーは字形アトラスと画像（ユニット 1・2）。
     * レコード列は {@code Bindings#records} の 0=図形・1=描画順・2=変換・3=スクロール・4=曲線・5=効果・6=クリップ。
     */
    public static final ShaderProgram RECTS = new ShaderProgram("rects",
            ShaderSources.ANIMATION + ShaderSources.RECT_VERTEX, ShaderSources.RECT_FRAGMENT,
            List.of(new Uniform("uView", UniformType.VEC2), new Uniform("uFrameTimeMs", UniformType.FLOAT),
                    new Uniform("uOrderBase", UniformType.INT), new Uniform("uYSign", UniformType.FLOAT)),
            List.of("uAtlas", "uImgTex"), 1,
            List.of(new RecordView("uPrF", 0, ViewFormat.FLOAT32, 16), new RecordView("uPrI", 0, ViewFormat.INT32, 17),
                    new RecordView("uPrH", 0, ViewFormat.HALF16, 18), new RecordView("uOrder", 1, ViewFormat.INT32, 19),
                    new RecordView("uTrF", 2, ViewFormat.FLOAT32, 20), new RecordView("uTrI", 2, ViewFormat.INT32, 21),
                    new RecordView("uScroll", 3, ViewFormat.FLOAT32, 22), new RecordView("uCurves", 4, ViewFormat.FLOAT32, 23),
                    new RecordView("uEfF", 5, ViewFormat.FLOAT32, 24), new RecordView("uEfI", 5, ViewFormat.INT32, 25),
                    new RecordView("uClF", 6, ViewFormat.FLOAT32, 26), new RecordView("uClH", 6, ViewFormat.HALF16, 27)));

    private static final List<ShaderProgram> ALL = List.of(TEX, RECTS);

    /** 名前で引く。無ければ {@link IllegalArgumentException}。 */
    public static ShaderProgram byId(String id) {
        for (ShaderProgram p : ALL) if (p.id().equals(id)) return p;
        throw new IllegalArgumentException("未知のシェーダー: " + id);
    }
}
