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

    /** 層の合成（色行列・ぼかし・影・マスク・mix-blend-mode・backdrop-filter・clip-path）。定数は std140 整列順。 */
    public static final ShaderProgram FX = new ShaderProgram("fx", ShaderSources.fxVertex(), ShaderSources.FX_FRAG,
            List.of(new Uniform("uDev", UniformType.VEC4),
                    new Uniform("uView", UniformType.VEC2), new Uniform("uDir", UniformType.VEC2),
                    new Uniform("uRow0", UniformType.VEC4), new Uniform("uRow1", UniformType.VEC4),
                    new Uniform("uRow2", UniformType.VEC4), new Uniform("uShadowColor", UniformType.VEC4),
                    new Uniform("uInvM", UniformType.VEC4),
                    new Uniform("uBD", UniformType.VEC4_ARRAY, 3),
                    new Uniform("uClipD", UniformType.VEC4_ARRAY, 96),
                    new Uniform("uTexel", UniformType.VEC2), new Uniform("uUvScale", UniformType.VEC2),
                    new Uniform("uUvMax", UniformType.VEC2), new Uniform("uShadowOffset", UniformType.VEC2),
                    new Uniform("uInvT", UniformType.VEC2),
                    new Uniform("uMode", UniformType.INT), new Uniform("uOpacity", UniformType.FLOAT),
                    new Uniform("uHasMatrix", UniformType.INT), new Uniform("uSigma", UniformType.FLOAT),
                    new Uniform("uTaps", UniformType.INT), new Uniform("uStep", UniformType.FLOAT),
                    new Uniform("uK", UniformType.INT), new Uniform("uMaskMode", UniformType.INT),
                    new Uniform("uBlend", UniformType.INT), new Uniform("uShapeScale", UniformType.FLOAT),
                    new Uniform("uClipKind", UniformType.INT), new Uniform("uEvenOdd", UniformType.INT),
                    new Uniform("uClipN", UniformType.INT), new Uniform("uHasB", UniformType.INT)),
            List.of("uSrc", "uAux", "uBackdrop"));

    /** 3D インスタンス描画（{@code Scene3DRenderer} 用）。vec3 は std140 の 16B 整列を明示するため vec4 で受け渡しする。 */
    public static final ShaderProgram SCENE3D = new ShaderProgram("scene3d",
            ShaderSources.SCENE3D_VERTEX, ShaderSources.SCENE3D_FRAGMENT,
            List.of(new Uniform("uViewProj", UniformType.MAT4),
                    new Uniform("uCameraPos", UniformType.VEC4), new Uniform("uLightDir", UniformType.VEC4),
                    new Uniform("uLightColor", UniformType.VEC4), new Uniform("uAmbientColor", UniformType.VEC4),
                    new Uniform("uBaseInstance", UniformType.INT), new Uniform("uExtraCount", UniformType.INT),
                    new Uniform("uExtra", UniformType.VEC4_ARRAY, 24)),
            List.of("uBaseTex"), 1,
            List.of(new RecordView("uInstanceTboF", 0, ViewFormat.RGBA_FLOAT32, 8),
                    new RecordView("uInstanceTboI", 0, ViewFormat.RGBA_INT32, 9)));

    /** {@link #SCENE3D} の HDR 版（8 bit への丸めをしない）。Bloom 有効のワールドを RGBA16F へ描く。 */
    public static final ShaderProgram SCENE3D_HDR = new ShaderProgram("scene3d_hdr",
            SCENE3D.vertexBody(), ShaderSources.scene3dFragmentHdr(),
            SCENE3D.uniforms(), SCENE3D.samplers(), SCENE3D.firstTextureUnit(), SCENE3D.records());

    /** 3D の Bloom（取り出し付き縮小・縮小・拡大して足し戻し・ACES 合成）。{@code ShaderSources.BLOOM_FRAGMENT} を参照。 */
    public static final ShaderProgram BLOOM = new ShaderProgram("bloom", ShaderSources.bloomVertex(), ShaderSources.BLOOM_FRAGMENT,
            List.of(new Uniform("uDev", UniformType.VEC4), new Uniform("uView", UniformType.VEC2),
                    new Uniform("uOffset", UniformType.FLOAT), new Uniform("uThreshold", UniformType.FLOAT),
                    new Uniform("uKnee", UniformType.FLOAT), new Uniform("uIntensity", UniformType.FLOAT),
                    new Uniform("uMode", UniformType.INT)),
            // サンプラーは binding / ユニット 1 から（0 は定数ブロック。Vulkan では重なると配置が不正になる）
            List.of("uSrc", "uAux"), 1, List.of());

    private static final List<ShaderProgram> ALL = List.of(TEX, RECTS, FX, SCENE3D, SCENE3D_HDR, BLOOM);

    /** 全シェーダープログラム（コンパイル・検証・一覧用）。 */
    public static List<ShaderProgram> all() {
        return ALL;
    }

    /** 名前で引く。無ければ {@link IllegalArgumentException}。 */
    public static ShaderProgram byId(String id) {
        for (ShaderProgram p : ALL) if (p.id().equals(id)) return p;
        throw new IllegalArgumentException("未知のシェーダー: " + id);
    }
}
