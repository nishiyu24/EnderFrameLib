package com.nishiyu.enderframelib.core.paint.gl;

import com.nishiyu.enderframelib.core.kernel.gpu.shader.Glsl;
import com.nishiyu.enderframelib.core.kernel.gpu.shader.ShaderSources;

/**
 * OpenGL 3.2 Core / GLSL 150 向けの 3D インスタンスシェーダ文字列定義。
 *
 * <p>GLSL 330 の {@code layout(location=...)} は使わず、プログラムリンク前に
 * {@code glBindAttribLocation} で頂点属性を割り当てる。また {@code org.lwjgl} への依存を持たない。</p>
 */
public final class Scene3DShaders {
    public static final int ATTR_POS = 0;
    public static final int ATTR_NORM = 1;
    public static final int ATTR_UV = 2;
    public static final int ATTR_TANGENT = 3;

    public static final int UNIT_INSTANCE_TBO_F = 8;
    public static final int UNIT_INSTANCE_TBO_I = 9;

    private Scene3DShaders() {}

    public static final String VERTEX_GLSL_150 = Glsl.gl150(ShaderSources.SCENE3D_VERTEX);

    public static final String FRAGMENT_GLSL_150 = Glsl.gl150(ShaderSources.SCENE3D_FRAGMENT);
}
