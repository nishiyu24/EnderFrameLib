package com.nishiyu.enderframelib.core.kernel.gpu.shader;

/**
 * GLSL の前置き（プリアンブル）。API ごとの違い（版・組み込み変数の名前・束縛）はここで吸収し、{@link ShaderSources} の本体は
 * どの API でも同じにする。いまは OpenGL 3.2 core（GLSL 1.50）だけ。
 */
public final class Glsl {
    private Glsl() {}

    /** OpenGL 3.2 core 向け: 版の行を前に付ける。 */
    public static String gl150(String body) {
        return "#version 150\n" + body;
    }
}
