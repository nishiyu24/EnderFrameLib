package com.nishiyu.enderframelib.core.paint.gl;

import com.nishiyu.enderframelib.core.kernel.gpu.GpuRectShader;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL31;

/**
 * {@link GlTreeStore} のプリミティブを 1 回のインスタンス描画で描く。CPU は頂点も DisplayList も作らず、
 * テクスチャユニットを結んで {@code glDrawArraysInstanced} を 1 回呼ぶだけ。
 * 描画先（FBO）・ビューポート・クリアは呼び出し側。合成は {@code (ONE, ONE_MINUS_SRC_ALPHA)}（プリマルチプライド）。
 */
public final class GlRectRenderer implements AutoCloseable {
    private int program;
    private int locView, locTime, locBase, locSign;

    public GlRectRenderer() {
        int vs = compile(GL20.GL_VERTEX_SHADER, GpuRectShader.VERTEX);
        int fs = compile(GL20.GL_FRAGMENT_SHADER, GpuRectShader.FRAGMENT);
        program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vs);
        GL20.glAttachShader(program, fs);
        GL20.glLinkProgram(program);
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
            String log = GL20.glGetProgramInfoLog(program);
            GL20.glDeleteProgram(program);
            program = 0;
            throw new IllegalStateException("矩形シェーダーのリンクに失敗: " + log);
        }
        // 新経路のバッファテクスチャは 16 番以降のユニットに結ぶ（従来のバックエンドが 0〜6 を使う。型の違うサンプラーを同じユニットに指すとエラー）
        if (GL11.glGetInteger(GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS) < 28) {
            GL20.glDeleteProgram(program);
            program = 0;
            throw new IllegalStateException("テクスチャユニットが足りない");
        }
        locView = GL20.glGetUniformLocation(program, "uView");
        locTime = GL20.glGetUniformLocation(program, "uFrameTimeMs");
        locBase = GL20.glGetUniformLocation(program, "uOrderBase");
        locSign = GL20.glGetUniformLocation(program, "uYSign");
    }

    private static int compile(int type, String src) {
        int s = GL20.glCreateShader(type);
        GL20.glShaderSource(s, src);
        GL20.glCompileShader(s);
        if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == 0) {
            String log = GL20.glGetShaderInfoLog(s);
            GL20.glDeleteShader(s);
            throw new IllegalStateException("矩形シェーダーのコンパイルに失敗: " + log);
        }
        return s;
    }

    /** サンプラー名とユニット（プログラムに固定。値は最初の描画で 1 回だけ設定する。プログラムの uniform は結び直しても保たれる）。 */
    private static final String[] SAMPLER_NAMES = {"uAtlas", "uImgTex", "uPrF", "uPrI", "uPrH", "uOrder", "uTrF", "uTrI", "uScroll", "uCurves", "uEfF", "uEfI", "uClF", "uClH"};
    private static final int[] SAMPLER_UNITS = {1, 2, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27};
    private final int[] samplerLocs = new int[SAMPLER_NAMES.length];
    private boolean samplersSet;
    /** 設定したサンプラーの数（テスト用: 描画を重ねても増えない）。 */
    private int samplerSetCount;

    int samplerSetCount() { return samplerSetCount; }

    private void setSamplersOnce() {
        if (samplersSet) return;
        for (int i = 0; i < samplerLocs.length; i++) {
            samplerLocs[i] = GL20.glGetUniformLocation(program, SAMPLER_NAMES[i]);
            GL20.glUniform1i(samplerLocs[i], SAMPLER_UNITS[i]);
            samplerSetCount++;
        }
        samplersSet = true;
    }

    /**
     * 先頭 {@code count} 個（描画順）のプリミティブを描く。{@code frameMs} がアニメ・スクロール評価の現在時刻。
     * 呼び出し側が VAO を結んでおくこと（コア・プロファイルは VAO 無しで描けない）。
     */
    public void draw(GlTreeStore store, int count, float frameMs, int viewW, int viewH) {
        draw(store, 0, count, frameMs, viewW, viewH, false);
    }

    /**
     * 描画順の {@code [first, first+count)} を描く。{@code flipY} なら画面の上が y=0（バックエンドの FBO の向き）、
     * そうでなければ GL の下が y=0。
     */
    public void draw(GlTreeStore store, int first, int count, float frameMs, int viewW, int viewH, boolean flipY) {
        if (count <= 0) return;
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int srcA = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), dstA = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        GL11.glEnable(GL11.GL_BLEND);
        GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        drawCore(store, first, count, frameMs, viewW, viewH, flipY);
        GL14.glBlendFuncSeparate(srcRgb, dstRgb, srcA, dstA);
        if (!blend) GL11.glDisable(GL11.GL_BLEND);
    }

    /** 呼び出し側が既に {@code GL_BLEND} 有効・事前乗算 {@code (ONE, ONE_MINUS_SRC_ALPHA)} に揃えているとき用（状態問い合わせを省く）。 */
    void drawPremulBlend(GlTreeStore store, int first, int count, float frameMs, int viewW, int viewH, boolean flipY) {
        if (count <= 0) return;
        drawCore(store, first, count, frameMs, viewW, viewH, flipY);
    }

    private void drawCore(GlTreeStore store, int first, int count, float frameMs, int viewW, int viewH, boolean flipY) {
        GL20.glUseProgram(program);
        setSamplersOnce(); // 字形アトラス = ユニット 1・画像 = 2（従来のバックエンドが結ぶ）。バッファテクスチャは 16 番以降
        store.primitives.bindFloatView(16);
        store.primitives.bindIntView(17);
        store.primitives.bindHalfView(18);
        store.order.bindIntView(19);
        store.transforms.bindFloatView(20);
        store.transforms.bindIntView(21);
        store.scrolls.bindFloatView(22);
        store.curves.bindFloatView(23);
        store.effects.bindFloatView(24);
        store.effects.bindIntView(25);
        store.clips.bindFloatView(26);
        store.clips.bindHalfView(27);
        GL20.glUniform2f(locView, viewW, viewH);
        GL20.glUniform1f(locTime, frameMs);
        GL20.glUniform1i(locBase, first);
        GL20.glUniform1f(locSign, flipY ? -1f : 1f);
        GL31.glDrawArraysInstanced(GL31.GL_TRIANGLE_STRIP, 0, 4, count);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    @Override
    public void close() {
        if (program != 0) { GL20.glDeleteProgram(program); program = 0; }
    }
}
