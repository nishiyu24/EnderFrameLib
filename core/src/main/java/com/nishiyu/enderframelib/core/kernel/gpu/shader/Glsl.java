package com.nishiyu.enderframelib.core.kernel.gpu.shader;

/**
 * GLSL の前置き（プリアンブル）。API ごとの違い（版・組み込み変数の名前・座標原点・レコード列の引き方・束縛の layout 修飾）は
 * ここで吸収し、{@link ShaderSources} の本体は GL 3.2（GLSL 150）と Vulkan（GLSL 450 → SPIR-V）で単一ソースにする。
 */
public final class Glsl {
    private Glsl() {}

    private static final String GL150_PREAMBLE = """
            #version 150
            #define EF_VERTEX_ID gl_VertexID
            #define EF_INSTANCE_ID gl_InstanceID
            #define EF_FRAG_Y(efViewH) ((efViewH) - gl_FragCoord.y)
            #define EF_CLIP_Y(efClipVal) (efClipVal)
            // RHI の GL は行を上下反転して持つ（上げた先頭の行が画面の上になる向き）。画像の v = 0（先頭の行）を引くには反転が要る
            #define EF_IMG_V(efV) (1.0 - (efV))
            #define EF_ATLAS_Y(efAtlasTex, efRow) (textureSize(efAtlasTex, 0).y - 1 - (efRow))
            #define EF_UBO(efBind, efName) layout(std140) uniform efName
            #define EF_TEX2D(efBind, efName) uniform sampler2D efName
            #define EF_TBO_F(efBind, efName) uniform samplerBuffer efName
            #define EF_TBO_I(efBind, efName) uniform isamplerBuffer efName
            #define EF_REC_F(efBuf, efIdx) texelFetch(efBuf, efIdx).r
            #define EF_REC_I(efBuf, efIdx) texelFetch(efBuf, efIdx).r
            #define EF_REC_V4(efBuf, efIdx) texelFetch(efBuf, efIdx)
            #define EF_REC_I4(efBuf, efIdx) texelFetch(efBuf, efIdx)
            #define EF_LOC(efLoc)
            """;

    private static final String VK450_PREAMBLE = """
            #version 450
            #define EF_VERTEX_ID gl_VertexIndex
            #define EF_INSTANCE_ID gl_InstanceIndex
            #define EF_FRAG_Y(efViewH) (gl_FragCoord.y)
            #define EF_CLIP_Y(efClipVal) (-(efClipVal))
            #define EF_IMG_V(efV) (efV)
            #define EF_ATLAS_Y(efAtlasTex, efRow) (efRow)
            #define EF_UBO(efBind, efName) layout(set = 0, binding = efBind, std140) uniform efName
            #define EF_TEX2D(efBind, efName) layout(set = 0, binding = efBind) uniform sampler2D efName
            #define EF_TBO_F(efBind, efName) layout(set = 0, binding = efBind) uniform samplerBuffer efName
            #define EF_TBO_I(efBind, efName) layout(set = 0, binding = efBind) uniform isamplerBuffer efName
            #define EF_REC_F(efBuf, efIdx) texelFetch(efBuf, efIdx).r
            #define EF_REC_I(efBuf, efIdx) texelFetch(efBuf, efIdx).r
            #define EF_REC_V4(efBuf, efIdx) texelFetch(efBuf, efIdx)
            #define EF_REC_I4(efBuf, efIdx) texelFetch(efBuf, efIdx)
            #define EF_LOC(efLoc) layout(location = efLoc)
            """;

    /** OpenGL 3.2 core 向け（GLSL 1.50 ＋ API 吸収マクロ）。 */
    public static String gl150(String body) {
        return GL150_PREAMBLE + body;
    }

    /** Vulkan 1.1（shaderc / SPIR-V）向け（GLSL 4.50 ＋ API 吸収マクロ）。 */
    public static String vk450(String body) {
        return VK450_PREAMBLE + body;
    }
}
