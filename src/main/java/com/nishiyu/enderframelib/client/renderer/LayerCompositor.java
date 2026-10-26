package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.core.paint.ClipShape;
import com.nishiyu.enderframelib.core.paint.FilterOp;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.nishiyu.enderframelib.core.paint.model.LayerSpec;
import com.nishiyu.enderframelib.core.paint.model.VramBudget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * スタッキングコンテキストの箱を別の描画先（FBO）に描いてから、不透明度・{@code filter}・{@code mix-blend-mode} を掛けて
 * 下へ合成する（{@link LayerSpec}）。グループの不透明度が、重なった子孫を 1 つずつ半透明にする描き方と違い、箱全体を 1 枚の
 * 絵として透かす。
 *
 * <p>使い方（{@code DisplayItemDrawer.renderElements}）: レイヤーの錨の項目の手前で {@link #begin}、錨から {@code span} 個の
 * 項目を描き終えたところで {@link #end}。間に描いたものは、画面と同じ行列・同じ大きさの FBO に描かれる
 * （画素の位置は同じで、合成は 1 対 1 の貼り付け）。FBO は {@link LayerPool} から借り、効果のある箱があるときだけ確保する。
 *
 * <p>描画先の中はプリマルチプライドで溜まる（バイト単位の色は、描くときのブレンドが {@code SRC_ALPHA / ONE_MINUS_SRC_ALPHA} と
 * アルファだけ {@code ONE / ONE_MINUS_SRC_ALPHA} のため）。合成のシェーダーはそれを読み、色の行列は
 * アルファで割った色に掛け、不透明度を掛けて、下の画素と混ぜる。
 *
 * <p>LIMITATION(制約): 画面の面（GUI・HUD）だけ。世界の中の面は、描画先を切り替えると世界の深度との前後が取れないので、
 * 従来どおり部品ごとに不透明度を掛ける。FBO を確保できない（入れ子が深い・GL が使えない）ときも同じ。README の "Not supported" に対応。
 */
public final class LayerCompositor {
    private LayerCompositor() {}

    // ==========================================
    // 状態
    // ==========================================

    /** 開いているレイヤー（内側が先頭）。 */
    private static final ArrayDeque<Layer> STACK = new ArrayDeque<>();

    private static final LayerPool<TextureTarget> POOL = new LayerPool<>(new LayerPool.Factory<>() {
        @Override
        public TextureTarget create(int width, int height) {
            TextureTarget target = new TextureTarget(width, height, true, Minecraft.ON_OSX);
            target.setClearColor(0f, 0f, 0f, 0f);
            target.setFilterMode(GL11.GL_LINEAR);
            return target;
        }

        @Override
        public void destroy(TextureTarget target) {
            target.destroyBuffers();
        }
    }, () -> VramBudget.maxLayers(Minecraft.getInstance().getWindow().getWidth(), Minecraft.getInstance().getWindow().getHeight(),
            OutlineTextRenderer.atlasBytes()));

    /** 効果の無いフレームがこれだけ続いたら、確保した FBO を手放す。 */
    private static final long IDLE_NANOS = 5_000_000_000L;
    private static long lastUse = 0L;
    private static boolean broken = false;

    private static final class Layer {
        TextureTarget target;
        int parentFbo;
        int[] parentViewport;
        LayerSpec spec;
        /** 錨の姿勢から出した、箱の座標が画面の画素（フラグメント座標）になる変換 {@code {ex.x, ex.y, ey.x, ey.y, tx, ty}}。 */
        float[] deviceMap;
        /** {@code mask-image} を描いた描画先（マスクの項目を描き始めたら入る）。 */
        TextureTarget mask;
    }

    /** いま、レイヤーの中に描いているか（ブレンドのアルファの扱いを変える）。 */
    static boolean isRecording() {
        return !STACK.isEmpty();
    }

    /** 画面（GUI・HUD）の面でレイヤーを使えるか。 */
    static boolean available() {
        if (broken || !RenderSystem.isOnRenderThread()) return false;
        if (!Shaders.ensure()) return false;
        return Minecraft.getInstance().getWindow().getWidth() > 0;
    }

    /** 効果の無いフレームが続いたら FBO を手放す（クライアントの tick から）。 */
    public static void trimIdle() {
        if (STACK.isEmpty() && lastUse != 0L) POOL.trim(System.nanoTime(), IDLE_NANOS);
    }

    /** 資源の再読み込み・GL の作り直し。 */
    public static void reload() {
        if (!RenderSystem.isOnRenderThread()) {
            RenderSystem.recordRenderCall(LayerCompositor::reload);
            return;
        }
        STACK.clear();
        POOL.releaseFree();
        Shaders.delete();
        broken = false;
    }

    // ==========================================
    // 開始と終了
    // ==========================================

    /**
     * レイヤーを開く。以降に描くものは、このレイヤーの描画先に溜まる。FBO を借りられなければ false
     * （呼び出し側は効果を諦めて直に描く。このとき {@link #end} は呼ばない）。
     *
     * @param anchorPose 錨の姿勢（箱の座標から、画面の面の座標まで）。{@code blur()} の大きさとドロップシャドウの向きに使う
     */
    static boolean begin(MultiBufferSource buffer, LayerSpec spec, Matrix4f anchorPose) {
        try {
            flush(buffer);
            int[] viewport = new int[4];
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            int parentFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            TextureTarget target = POOL.acquire(viewport[2], viewport[3]);
            if (target == null) {
                restore(parentFbo, viewport);
                return false;
            }
            Layer layer = new Layer();
            layer.target = target;
            layer.parentFbo = parentFbo;
            layer.parentViewport = viewport;
            layer.spec = spec;
            layer.deviceMap = deviceMap(anchorPose, viewport);
            target.bindWrite(true);
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);
            STACK.push(layer);
            lastUse = System.nanoTime();
            return true;
        } catch (Throwable t) {
            fail("begin", t);
            return false;
        }
    }

    /**
     * いちばん内側のレイヤーの、マスク画像を描く項目の手前で呼ぶ。以降に描くものは、レイヤーの中身ではなくマスク用の描画先に溜まる
     * （{@link #end} がそのアルファか輝度で中身を透かす）。借りられなければ false（マスクなしで合成する）。
     */
    static boolean beginMask(MultiBufferSource buffer) {
        Layer layer = STACK.peek();
        if (layer == null || layer.mask != null) return layer != null && layer.mask != null;
        try {
            flush(buffer);
            TextureTarget mask = POOL.acquire(layer.parentViewport[2], layer.parentViewport[3]);
            if (mask == null) {
                layer.target.bindWrite(true);
                return false;
            }
            layer.mask = mask;
            mask.bindWrite(true);
            mask.clear(Minecraft.ON_OSX);
            mask.bindWrite(true);
            return true;
        } catch (Throwable t) {
            fail("beginMask", t);
            return false;
        }
    }

    /**
     * いちばん内側のレイヤーを閉じて、{@code opacity}（アニメーション・トランジションの今の値）を掛けて下へ合成する。
     *
     * @param basePose 画面の面の基準の姿勢（錨の変形を含まない）。合成の四角はこの座標の {@code [0, maxX] x [0, maxY]}
     * @param z        合成する深さ。レイヤーの中でいちばん手前に描いた項目の深さ
     */
    static void end(MultiBufferSource buffer, float opacity, Matrix4f basePose, float maxX, float maxY, float z) {
        Layer layer = STACK.peek();
        if (layer == null) return;
        List<TextureTarget> temps = new ArrayList<>();
        try {
            flush(buffer);
            STACK.pop();
            composite(layer, opacity, basePose, maxX, maxY, z, temps);
        } catch (Throwable t) {
            STACK.remove(layer);
            fail("end", t);
        } finally {
            long now = System.nanoTime();
            for (TextureTarget temp : temps) POOL.release(temp, now);
            if (layer.mask != null) POOL.release(layer.mask, now);
            POOL.release(layer.target, now);
            try {
                restore(layer.parentFbo, layer.parentViewport);
            } catch (Throwable ignored) {
                // 後始末の失敗は握りつぶす（次のフレームで作り直す）
            }
            lastUse = now;
        }
    }

    /**
     * {@code backdrop-filter}: 箱の後ろ（今の描画先にこれまでに描いたもの）を写し取り、フィルターを掛けて、箱の形に貼る。
     * 箱自身の描画の前に呼ぶ。ぼかしは箱の縁の外の画素を混ぜない。FBO を借りられなければ何もしない。
     *
     * @param z 貼る深さ。直前に描いた項目と箱自身の最初の項目の間
     */
    static void backdrop(MultiBufferSource buffer, LayerSpec spec, Matrix4f anchorPose, Matrix4f basePose, float maxX, float maxY, float z) {
        List<TextureTarget> temps = new ArrayList<>();
        int[] viewport = new int[4];
        int parentFbo = 0;
        try {
            flush(buffer);
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            parentFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            int w = viewport[2], h = viewport[3];
            TextureTarget copy = POOL.acquire(w, h);
            if (copy == null) return;
            temps.add(copy);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, parentFbo);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, copy.frameBufferId);
            GL30.glBlitFramebuffer(viewport[0], viewport[1], viewport[0] + w, viewport[1] + h, 0, 0, w, h, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);

            float[] map = deviceMap(anchorPose, viewport);
            float[] box = spec.backdropShape().data();
            float[] clamp = deviceBounds(map, box[0], box[1], box[2], box[3], viewport);

            List<Stage> stages = stages(spec.backdrop());
            float[] finalMatrix = null;
            float finalOpacity = 1f;
            if (!stages.isEmpty() && stages.getLast().kind == StageKind.COLOR) {
                Stage last = stages.removeLast();
                finalMatrix = last.matrix;
                finalOpacity = last.opacity;
            }
            TextureTarget current = copy;
            for (Stage stage : stages) {
                TextureTarget next = runStage(stage, current, map, w, h, clamp, temps);
                if (next == null) break;
                current = next;
            }

            restore(parentFbo, viewport);
            Shaders.use();
            Shaders.common(viewport, w, h);
            Shaders.mode(Shaders.MODE_BACKDROP);
            Shaders.opacity(finalOpacity);
            Shaders.matrix(finalMatrix);
            GL20.glUniform1i(Shaders.uBlend, 0);
            GL20.glUniform1i(Shaders.uMaskMode, 0);
            Shaders.clip(spec.backdropShape(), map);
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.depthMask(true);
            RenderSystem.disableCull();
            bindTextures(current, null);
            Shaders.pose(basePose);
            Shaders.clipSpace(false);
            drawQuad(0f, 0f, maxX, maxY, z);
            unbindTextures();
            GL20.glUseProgram(0);
            RenderSystem.defaultBlendFunc();
            RenderSystem.enableBlend();
        } catch (Throwable t) {
            fail("backdrop", t);
        } finally {
            long now = System.nanoTime();
            for (TextureTarget temp : temps) POOL.release(temp, now);
            try {
                restore(parentFbo, viewport);
            } catch (Throwable ignored) {
                // 後始末の失敗は握りつぶす
            }
            lastUse = now;
        }
    }

    /** 箱の座標の矩形を画面の画素にして囲む矩形（uv）。 */
    private static float[] deviceBounds(float[] map, float x0, float y0, float x1, float y1, int[] viewport) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float[][] corners = {{x0, y0}, {x1, y0}, {x1, y1}, {x0, y1}};
        for (float[] c : corners) {
            float fx = map[0] * c[0] + map[2] * c[1] + map[4], fy = map[1] * c[0] + map[3] * c[1] + map[5];
            minX = Math.min(minX, fx);
            maxX = Math.max(maxX, fx);
            minY = Math.min(minY, fy);
            maxY = Math.max(maxY, fy);
        }
        float w = viewport[2], h = viewport[3];
        // 半画素内側に寄せて、縁の外の画素に触れないようにする
        return new float[]{
                Math.max(0f, (minX - viewport[0] + 0.5f) / w), Math.max(0f, (minY - viewport[1] + 0.5f) / h),
                Math.min(1f, (maxX - viewport[0] - 0.5f) / w), Math.min(1f, (maxY - viewport[1] - 0.5f) / h)};
    }

    private static void fail(String where, Throwable t) {
        broken = true;
        com.nishiyu.enderframelib.EnderFrameLib.LOGGER.error("レイヤー合成の {} で失敗したので、以降は部品ごとの不透明度で描く", where, t);
    }

    /** 描きかけの頂点（文字・SDF の Quad）を、今の描画先へ吐き出す。 */
    private static void flush(MultiBufferSource buffer) {
        if (buffer instanceof MultiBufferSource.BufferSource bs) bs.endBatch();
        WebSdfShader.endPass();
    }

    private static void restore(int fbo, int[] viewport) {
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GlStateManager._viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
    }

    // ==========================================
    // 合成
    // ==========================================

    private static void composite(Layer layer, float opacity, Matrix4f basePose, float maxX, float maxY, float z,
                                  List<TextureTarget> temps) {
        LayerSpec spec = layer.spec;
        int w = layer.parentViewport[2], h = layer.parentViewport[3];

        // フィルターの段（色と不透明度の連続は 1 つにまとめ、最後の段は最終の合成に畳む）
        List<Stage> stages = stages(spec.filters());
        float[] finalMatrix = null;
        float finalOpacity = opacity;
        if (!stages.isEmpty() && stages.getLast().kind == StageKind.COLOR) {
            Stage last = stages.removeLast();
            finalMatrix = last.matrix;
            finalOpacity *= last.opacity;
        }

        TextureTarget current = layer.target;
        for (Stage stage : stages) {
            TextureTarget next = runStage(stage, current, layer.deviceMap, w, h, null, temps);
            if (next == null) break; // FBO が足りない: 以降の段は諦める
            current = next;
        }

        // 最終の合成: 親の描画先へ
        int blend = spec.blend().ordinal();
        TextureTarget backdrop = null;
        if (blend != 0) {
            backdrop = POOL.acquire(w, h);
            if (backdrop == null) blend = 0;
            else {
                temps.add(backdrop);
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, layer.parentFbo);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, backdrop.frameBufferId);
                GL30.glBlitFramebuffer(layer.parentViewport[0], layer.parentViewport[1], layer.parentViewport[0] + w, layer.parentViewport[1] + h,
                        0, 0, w, h, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
            }
        }
        restore(layer.parentFbo, layer.parentViewport);
        // 親がウィンドウ本体（FBO 0 かメインの描画先）なら、下の画素は不透明として混ぜる
        boolean parentIsLayer = false;
        for (Layer outer : STACK) {
            if (outer.target.frameBufferId == layer.parentFbo) {
                parentIsLayer = true;
                break;
            }
        }
        Shaders.use();
        Shaders.common(layer.parentViewport, w, h);
        Shaders.mode(Shaders.MODE_FINAL);
        Shaders.opacity(finalOpacity);
        Shaders.matrix(finalMatrix);
        GL20.glUniform1i(Shaders.uBlend, blend);
        GL20.glUniform1f(Shaders.uBackdropOpaque, parentIsLayer ? 0f : 1f);
        Shaders.clip(spec.clip(), layer.deviceMap);
        GL20.glUniform1i(Shaders.uMaskMode, layer.mask == null ? 0 : (spec.maskLuminance() ? 2 : 1));
        if (blend != 0) {
            // 混合は下の画素を読んで自分で計算するので、GL のブレンドは使わず置き換える
            RenderSystem.disableBlend();
        } else {
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        }
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(true);
        RenderSystem.disableCull();
        bindTextures(current, backdrop);
        if (layer.mask != null) {
            RenderSystem.activeTexture(GL13.GL_TEXTURE2);
            layer.mask.bindRead();
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);
        }
        Shaders.pose(basePose);
        Shaders.clipSpace(false);
        drawQuad(0f, 0f, maxX, maxY, z);
        unbindTextures();
        GL20.glUseProgram(0);
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableBlend();
    }

    // ---- フィルターの段 ----

    private enum StageKind { COLOR, BLUR, DROP_SHADOW }

    /** 色の行列と不透明度の連続（{@code COLOR}）、ぼかし、ドロップシャドウ。 */
    private static final class Stage {
        StageKind kind;
        float[] matrix;
        float opacity = 1f;
        float sigma;
        float dx, dy;
        int color;
    }

    private static List<Stage> stages(List<FilterOp> ops) {
        List<Stage> out = new ArrayList<>();
        for (FilterOp op : ops) {
            switch (op.kind()) {
                case COLOR_MATRIX, OPACITY -> {
                    Stage s = out.isEmpty() || out.getLast().kind != StageKind.COLOR ? null : out.getLast();
                    if (s == null) {
                        s = new Stage();
                        s.kind = StageKind.COLOR;
                        out.add(s);
                    }
                    if (op.kind() == FilterOp.Kind.OPACITY) {
                        s.opacity *= op.amount();
                    } else {
                        s.matrix = s.matrix == null ? op.matrix() : multiply(op.matrix(), s.matrix);
                    }
                }
                case BLUR -> {
                    Stage s = new Stage();
                    s.kind = StageKind.BLUR;
                    s.sigma = op.amount();
                    out.add(s);
                }
                case DROP_SHADOW -> {
                    Stage s = new Stage();
                    s.kind = StageKind.DROP_SHADOW;
                    s.sigma = op.amount();
                    s.dx = op.dx();
                    s.dy = op.dy();
                    s.color = op.color();
                    out.add(s);
                }
            }
        }
        return out;
    }

    /** {@code second ∘ first}（3x4 の行列。{@code Filters.multiply} と同じ）。 */
    private static float[] multiply(float[] second, float[] first) {
        float[] out = new float[12];
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                out[row * 4 + col] = second[row * 4] * first[col] + second[row * 4 + 1] * first[4 + col] + second[row * 4 + 2] * first[8 + col];
            }
            out[row * 4 + 3] = second[row * 4] * first[3] + second[row * 4 + 1] * first[7] + second[row * 4 + 2] * first[11] + second[row * 4 + 3];
        }
        return out;
    }

    /** 段を 1 つ実行して、結果の描画先を返す（入力が一時のものなら返す）。借りられなければ null。 */
    private static TextureTarget runStage(Stage stage, TextureTarget in, float[] map, int w, int h, float[] clamp, List<TextureTarget> temps) {
        switch (stage.kind) {
            case COLOR -> {
                TextureTarget out = POOL.acquire(w, h);
                if (out == null) return null;
                temps.add(out);
                intermediate(out, in, null, Shaders.MODE_MATRIX, w, h, s -> {
                    Shaders.opacity(stage.opacity);
                    Shaders.matrix(stage.matrix);
                });
                return out;
            }
            case BLUR -> {
                return blur(stage.sigma, in, map, w, h, clamp, temps);
            }
            case DROP_SHADOW -> {
                TextureTarget blurred = blur(stage.sigma, in, map, w, h, clamp, temps);
                if (blurred == null) return null;
                TextureTarget out = POOL.acquire(w, h);
                if (out == null) return null;
                temps.add(out);
                float[] m = map;
                // 箱の座標のずれを、画面の画素のずれにする（回転・拡縮した箱でも向きが合う）
                float ox = m[0] * stage.dx + m[2] * stage.dy;
                float oy = m[1] * stage.dx + m[3] * stage.dy;
                intermediate(out, in, blurred, Shaders.MODE_DROP, w, h, s -> {
                    GL20.glUniform2f(Shaders.uShadowOffset, ox / w, oy / h);
                    GL20.glUniform4f(Shaders.uShadowColor, ((stage.color >>> 16) & 0xFF) / 255f, ((stage.color >>> 8) & 0xFF) / 255f,
                            (stage.color & 0xFF) / 255f, ((stage.color >>> 24) & 0xFF) / 255f);
                });
                return out;
            }
        }
        return null;
    }

    /** 2 回（横・縦）のガウスぼかし。標準偏差は箱の座標の px を画面の画素にしたもの。 */
    private static TextureTarget blur(float sigma, TextureTarget in, float[] m, int w, int h, float[] clamp, List<TextureTarget> temps) {
        float sigmaX = sigma * (float) Math.hypot(m[0], m[1]);
        float sigmaY = sigma * (float) Math.hypot(m[2], m[3]);
        if (sigmaX < 0.05f && sigmaY < 0.05f) return in;
        TextureTarget mid = POOL.acquire(w, h);
        if (mid == null) return null;
        temps.add(mid);
        TextureTarget out = POOL.acquire(w, h);
        if (out == null) return null;
        temps.add(out);
        intermediate(mid, in, null, Shaders.MODE_BLUR, w, h, s -> blurUniforms(1f, 0f, sigmaX, clamp));
        intermediate(out, mid, null, Shaders.MODE_BLUR, w, h, s -> blurUniforms(0f, 1f, sigmaY, clamp));
        return out;
    }

    private static void blurUniforms(float dirX, float dirY, float sigma, float[] clamp) {
        // 背景のフィルターは、箱の外の画素を混ぜない（箱の縁の画素を引き伸ばす）。clamp は uv の {@code {下限x, 下限y, 上限x, 上限y}}
        if (clamp != null) GL20.glUniform4f(Shaders.uClamp, clamp[0], clamp[1], clamp[2], clamp[3]);
        else GL20.glUniform4f(Shaders.uClamp, 0f, 0f, 1f, 1f);
        // 大きな標準偏差では、標本の間隔を広げて枚数を抑える（リニア補間が間を埋める）
        float step = Math.max(1f, sigma / 12f);
        int taps = (int) Math.min(40, Math.ceil(3f * sigma / step));
        GL20.glUniform2f(Shaders.uDir, dirX * step, dirY * step);
        GL20.glUniform1f(Shaders.uSigma, Math.max(sigma, 0.001f));
        GL20.glUniform1i(Shaders.uTaps, taps);
        GL20.glUniform1f(Shaders.uStep, step);
    }

    /** {@code out} へ全面を 1 枚描く（深度・ブレンドなし）。{@code in} は単位 0、{@code aux} は単位 1。 */
    private static void intermediate(TextureTarget out, TextureTarget in, TextureTarget aux, int mode, int w, int h,
                                     java.util.function.Consumer<Void> uniforms) {
        out.bindWrite(true);
        out.setClearColor(0f, 0f, 0f, 0f);
        GlStateManager._clearColor(0f, 0f, 0f, 0f);
        GlStateManager._clear(GL11.GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX);
        Shaders.use();
        Shaders.common(new int[]{0, 0, w, h}, w, h);
        Shaders.mode(mode);
        Shaders.opacity(1f);
        Shaders.matrix(null);
        GL20.glUniform1i(Shaders.uBlend, 0);
        uniforms.accept(null);
        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        bindTextures(in, aux);
        Shaders.clipSpace(true);
        drawQuad(-1f, -1f, 1f, 1f, 0f);
        unbindTextures();
        GL20.glUseProgram(0);
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
    }

    private static void bindTextures(TextureTarget unit0, TextureTarget unit1) {
        RenderSystem.activeTexture(GL13.GL_TEXTURE1);
        if (unit1 != null) unit1.bindRead();
        else GlStateManager._bindTexture(0);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0);
        unit0.bindRead();
    }

    private static void unbindTextures() {
        RenderSystem.activeTexture(GL13.GL_TEXTURE2);
        GlStateManager._bindTexture(0);
        RenderSystem.activeTexture(GL13.GL_TEXTURE1);
        GlStateManager._bindTexture(0);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0);
        GlStateManager._bindTexture(0);
    }

    // ==========================================
    // 幾何
    // ==========================================

    /**
     * 箱の座標が画面の画素（フラグメント座標）になる変換。{@code {ex.x, ex.y, ey.x, ey.y, tx, ty}} で、
     * {@code frag = (ex.x * x + ey.x * y + tx, ex.y * x + ey.y * y + ty)}。姿勢に射影を掛けて NDC にし、
     * ビューポートの半分を掛けて原点を足す（y は上向き、フラグメント座標と同じ）。
     */
    static float[] deviceMap(Matrix4f anchorPose, int[] viewport) {
        Matrix4f full = new Matrix4f(RenderSystem.getProjectionMatrix()).mul(RenderSystem.getModelViewMatrix()).mul(anchorPose);
        Vector4f o = full.transform(new Vector4f(0f, 0f, 0f, 1f));
        Vector4f ex = full.transform(new Vector4f(1f, 0f, 0f, 1f));
        Vector4f ey = full.transform(new Vector4f(0f, 1f, 0f, 1f));
        float hw = viewport[2] / 2f, hh = viewport[3] / 2f;
        float ow = o.w == 0f ? 1f : o.w, exw = ex.w == 0f ? 1f : ex.w, eyw = ey.w == 0f ? 1f : ey.w;
        return new float[]{
                (ex.x / exw - o.x / ow) * hw, (ex.y / exw - o.y / ow) * hh,
                (ey.x / eyw - o.x / ow) * hw, (ey.y / eyw - o.y / ow) * hh,
                (o.x / ow * 0.5f + 0.5f) * viewport[2] + viewport[0], (o.y / ow * 0.5f + 0.5f) * viewport[3] + viewport[1]};
    }

    private static ByteBufferBuilder quadBytes;
    private static VertexFormat quadFormat;

    private static void drawQuad(float x0, float y0, float x1, float y1, float z) {
        if (quadBytes == null) {
            quadBytes = new ByteBufferBuilder(256);
            quadFormat = VertexFormat.builder().add("Position", VertexFormatElement.POSITION).build();
        }
        float[][] v = {{x0, y1}, {x1, y1}, {x1, y0}, {x0, y0}};
        for (float[] p : v) {
            long at = quadBytes.reserve(12);
            MemoryUtil.memPutFloat(at, p[0]);
            MemoryUtil.memPutFloat(at + 4, p[1]);
            MemoryUtil.memPutFloat(at + 8, z);
        }
        ByteBufferBuilder.Result result = quadBytes.build();
        if (result == null) return;
        MeshData mesh = new MeshData(result, new MeshData.DrawState(quadFormat, 4, 6, VertexFormat.Mode.QUADS, VertexFormat.IndexType.least(6)));
        BufferUploader.draw(mesh);
    }

    // ==========================================
    // シェーダー
    // ==========================================

    private static final class Shaders {
        static final int MODE_FINAL = 0, MODE_MATRIX = 1, MODE_BLUR = 2, MODE_DROP = 3, MODE_BACKDROP = 4;

        private static int program = -1;
        private static boolean initialized = false;
        private static boolean ok = false;
        static int uModelView, uProj, uPose, uClipSpace, uSrc, uAux, uSize, uOrigin, uMode, uOpacity, uHasMatrix, uRow0, uRow1, uRow2,
                uBlend, uBackdropOpaque, uDir, uSigma, uTaps, uStep, uShadowOffset, uShadowColor,
                uClamp, uMask, uMaskMode, uClipKind, uClipA, uClipRx, uClipRy, uEdges, uEdgeCount, uEvenOdd, uInv, uInvT;

        static final String VERTEX = """
                #version 150
                in vec3 Position;
                uniform mat4 ModelViewMat;
                uniform mat4 ProjMat;
                uniform mat4 uPoseMat;
                uniform float uClipSpace;
                void main() {
                    if (uClipSpace > 0.5) gl_Position = vec4(Position.xy, 0.0, 1.0);
                    else gl_Position = ProjMat * ModelViewMat * uPoseMat * vec4(Position, 1.0);
                }
                """;

        static final String FRAGMENT = """
                #version 150
                uniform sampler2D uSrc;       // レイヤー（プリマルチプライド）
                uniform sampler2D uAux;       // ぼかした結果（ドロップシャドウ）か下の画素（混合）
                uniform sampler2D uMask;      // mask-image を描いた描画先（単位 2）
                uniform int uMaskMode;        // 0 なし, 1 アルファ, 2 輝度
                uniform vec4 uClamp;          // ぼかしの標本の uv の範囲 (下限x 下限y 上限x 上限y)
                uniform int uClipKind;        // 0 なし, 1 inset, 2 circle, 3 ellipse, 4 polygon
                uniform vec4 uClipA;          // inset: x0 y0 x1 y1 / circle: cx cy r / ellipse: cx cy rx ry
                uniform vec4 uClipRx;         // inset の横の半径 (左上 右上 右下 左下)
                uniform vec4 uClipRy;         // inset の縦の半径
                uniform vec4 uEdges[96];      // polygon の辺 (ax ay bx by)
                uniform int uEdgeCount;
                uniform int uEvenOdd;
                uniform vec4 uInv;            // 画素 → 箱の座標の逆変換の線形部分 (x = uInv.x fx + uInv.y fy, y = uInv.z fx + uInv.w fy)
                uniform vec2 uInvT;           // 箱の原点の画素
                uniform vec2 uSize;           // 描画先の大きさ（画素）
                uniform vec2 uOrigin;         // 描画先のビューポートの左下
                uniform int uMode;            // 0 最終の合成, 1 行列と不透明度, 2 ぼかし, 3 ドロップシャドウ
                uniform float uOpacity;
                uniform int uHasMatrix;
                uniform vec4 uRow0;           // 色の行列の 3 行（r g b 定数）
                uniform vec4 uRow1;
                uniform vec4 uRow2;
                uniform int uBlend;           // BlendMode の番号
                uniform float uBackdropOpaque; // 1: 下は不透明（ウィンドウ本体）。0: 下のアルファを読む
                uniform vec2 uDir;            // ぼかしの 1 標本ごとの進み（画素）
                uniform float uSigma;
                uniform int uTaps;
                uniform float uStep;
                uniform vec2 uShadowOffset;   // ドロップシャドウのずれ（uv）
                uniform vec4 uShadowColor;
                out vec4 fragColor;

                // 楕円の角を持つ角丸矩形までの距離（WebSdfShader の sdEllipseBox と同じ）
                float sdEllipseBox(vec2 p, vec2 b, vec4 rx, vec4 ry) {
                    vec2 sx = (p.x > 0.0) ? vec2(rx.y, rx.z) : vec2(rx.x, rx.w);
                    vec2 sy = (p.x > 0.0) ? vec2(ry.y, ry.z) : vec2(ry.x, ry.w);
                    vec2 rr = min(vec2((p.y > 0.0) ? sx.y : sx.x, (p.y > 0.0) ? sy.y : sy.x), b);
                    if (rr.x <= 0.0 || rr.y <= 0.0) {
                        vec2 e = abs(p) - b;
                        return length(max(e, 0.0)) + min(max(e.x, e.y), 0.0);
                    }
                    vec2 q = abs(p) - b + rr;
                    if (q.x > 0.0 && q.y > 0.0) {
                        float k0 = length(q / rr);
                        float k1 = length(q / (rr * rr));
                        return k0 * (k0 - 1.0) / max(k1, 1e-6);
                    }
                    return max(q.x - rr.x, q.y - rr.y);
                }

                float sdEllipse(vec2 p, vec2 r) {
                    float k0 = length(p / r);
                    float k1 = length(p / (r * r));
                    return k1 < 1e-9 ? -min(r.x, r.y) : k0 * (k0 - 1.0) / k1;
                }

                // clip-path の図形までの符号付き距離（箱の座標。内側が負）
                float clipDistance(vec2 p) {
                    if (uClipKind == 1) {
                        vec2 c = (uClipA.xy + uClipA.zw) * 0.5;
                        vec2 h = max(vec2(0.001), (uClipA.zw - uClipA.xy) * 0.5);
                        return sdEllipseBox(p - c, h, uClipRx, uClipRy);
                    }
                    if (uClipKind == 2) return length(p - uClipA.xy) - uClipA.z;
                    if (uClipKind == 3) return sdEllipse(p - uClipA.xy, max(uClipA.zw, vec2(0.001)));
                    float minD = 1e20;
                    int wind = 0;
                    int crossings = 0;
                    for (int i = 0; i < uEdgeCount; i++) {
                        vec4 e = uEdges[i];
                        vec2 a = e.xy;
                        vec2 d = e.zw - e.xy;
                        float len2 = dot(d, d);
                        float t = len2 <= 0.0 ? 0.0 : clamp(dot(p - a, d) / len2, 0.0, 1.0);
                        minD = min(minD, length(p - (a + t * d)));
                        if ((e.y <= p.y) != (e.w <= p.y)) {
                            float u = (p.y - e.y) / (e.w - e.y);
                            if (p.x < e.x + u * (e.z - e.x)) {
                                crossings++;
                                wind += e.w > e.y ? 1 : -1;
                            }
                        }
                    }
                    bool inside = uEvenOdd == 1 ? (crossings & 1) == 1 : wind != 0;
                    return inside ? -minD : minD;
                }

                // clip-path の図形の内側の割合（画素 1 つ分の線形のアンチエイリアス）
                float clipCoverage(vec2 frag) {
                    vec2 f = frag - uInvT;
                    vec2 item = vec2(uInv.x * f.x + uInv.y * f.y, uInv.z * f.x + uInv.w * f.y);
                    float itemPerPixel = sqrt(abs(uInv.x * uInv.w - uInv.y * uInv.z));
                    float d = clipDistance(item) / max(itemPerPixel, 1e-6);
                    return clamp(0.5 - d, 0.0, 1.0);
                }

                vec4 applyMatrix(vec4 pm) {
                    if (uHasMatrix == 0) return pm;
                    float a = pm.a;
                    if (a <= 0.0) return vec4(0.0);
                    vec3 c = pm.rgb / a;
                    c = clamp(vec3(dot(uRow0.rgb, c) + uRow0.a, dot(uRow1.rgb, c) + uRow1.a, dot(uRow2.rgb, c) + uRow2.a), 0.0, 1.0);
                    return vec4(c * a, a);
                }

                float lum(vec3 c) { return dot(c, vec3(0.3, 0.59, 0.11)); }
                vec3 clipColor(vec3 c) {
                    float l = lum(c);
                    float n = min(min(c.r, c.g), c.b);
                    float x = max(max(c.r, c.g), c.b);
                    if (n < 0.0) c = l + (c - l) * l / (l - n);
                    if (x > 1.0) c = l + (c - l) * (1.0 - l) / (x - l);
                    return c;
                }
                vec3 setLum(vec3 c, float l) { return clipColor(c + (l - lum(c))); }
                float sat(vec3 c) { return max(max(c.r, c.g), c.b) - min(min(c.r, c.g), c.b); }
                vec3 setSat(vec3 c, float s) {
                    float mx = max(max(c.r, c.g), c.b);
                    float mn = min(min(c.r, c.g), c.b);
                    if (mx <= mn) return vec3(0.0);
                    return (c - mn) * s / (mx - mn);
                }
                float hardLight(float cb, float cs) {
                    return cs <= 0.5 ? cb * 2.0 * cs : cb + (2.0 * cs - 1.0) - cb * (2.0 * cs - 1.0);
                }
                float blendCh(int mode, float cb, float cs) {
                    if (mode == 1) return cb * cs;
                    if (mode == 2) return cb + cs - cb * cs;
                    if (mode == 3) return hardLight(cs, cb);
                    if (mode == 4) return min(cb, cs);
                    if (mode == 5) return max(cb, cs);
                    if (mode == 6) return cb <= 0.0 ? 0.0 : (cs >= 1.0 ? 1.0 : min(1.0, cb / (1.0 - cs)));
                    if (mode == 7) return cb >= 1.0 ? 1.0 : (cs <= 0.0 ? 0.0 : 1.0 - min(1.0, (1.0 - cb) / cs));
                    if (mode == 8) return hardLight(cb, cs);
                    if (mode == 9) {
                        if (cs <= 0.5) return cb - (1.0 - 2.0 * cs) * cb * (1.0 - cb);
                        float d = cb <= 0.25 ? ((16.0 * cb - 12.0) * cb + 4.0) * cb : sqrt(cb);
                        return cb + (2.0 * cs - 1.0) * (d - cb);
                    }
                    if (mode == 10) return abs(cb - cs);
                    if (mode == 11) return cb + cs - 2.0 * cb * cs;
                    return cs;
                }
                vec3 blendRgb(int mode, vec3 cb, vec3 cs) {
                    if (mode == 12) return setLum(setSat(cs, sat(cb)), lum(cb));
                    if (mode == 13) return setLum(setSat(cb, sat(cs)), lum(cb));
                    if (mode == 14) return setLum(cs, lum(cb));
                    if (mode == 15) return setLum(cb, lum(cs));
                    return vec3(blendCh(mode, cb.r, cs.r), blendCh(mode, cb.g, cs.g), blendCh(mode, cb.b, cs.b));
                }

                void main() {
                    vec2 uv = (gl_FragCoord.xy - uOrigin) / uSize;
                    if (uMode == 2) {
                        // ガウスぼかし（片方向）。重みは標本の位置（画素）の正規分布
                        vec4 sum = texture(uSrc, clamp(uv, uClamp.xy, uClamp.zw));
                        float wsum = 1.0;
                        for (int i = 1; i <= uTaps; i++) {
                            float d = float(i) * uStep;
                            float w = exp(-0.5 * d * d / (uSigma * uSigma));
                            vec2 o = uDir / uSize * float(i);
                            sum += (texture(uSrc, clamp(uv + o, uClamp.xy, uClamp.zw)) + texture(uSrc, clamp(uv - o, uClamp.xy, uClamp.zw))) * w;
                            wsum += 2.0 * w;
                        }
                        fragColor = sum / wsum;
                        return;
                    }
                    if (uMode == 3) {
                        vec4 base = texture(uSrc, uv);
                        float sa = texture(uAux, uv - uShadowOffset).a * uShadowColor.a;
                        vec4 shadow = vec4(uShadowColor.rgb * sa, sa);
                        fragColor = base + shadow * (1.0 - base.a);
                        return;
                    }
                    vec4 src = applyMatrix(texture(uSrc, uv));
                    if (uMode == 4) {
                        // backdrop-filter: 加工した背景を箱の形に貼る（箱の外は何もしない）。不透明度はフィルターの opacity()
                        float cov = clipCoverage(gl_FragCoord.xy) * uOpacity;
                        if (cov <= 0.002) discard;
                        fragColor = vec4(src.rgb * cov, cov);
                        return;
                    }
                    if (uClipKind > 0) src *= clipCoverage(gl_FragCoord.xy);
                    if (uMaskMode > 0) {
                        vec4 m = texture(uMask, uv);
                        src *= uMaskMode == 1 ? m.a : dot(m.rgb, vec3(0.2126, 0.7152, 0.0722));
                    }
                    src *= uOpacity;
                    if (uMode == 1) {
                        fragColor = src;
                        return;
                    }
                    if (src.a <= 0.002) discard;
                    if (uBlend == 0) {
                        fragColor = src;
                        return;
                    }
                    vec4 bd = texture(uAux, uv);
                    float ab = uBackdropOpaque > 0.5 ? 1.0 : bd.a;
                    vec3 cb = uBackdropOpaque > 0.5 ? bd.rgb : (ab > 0.0 ? bd.rgb / ab : vec3(0.0));
                    float as = src.a;
                    vec3 cs = src.rgb / as;
                    if (uBlend == 16) {
                        fragColor = vec4(min(vec3(1.0), src.rgb + cb * ab), min(1.0, as + ab));
                        return;
                    }
                    vec3 b = blendRgb(uBlend, cb, cs);
                    vec3 co = as * (1.0 - ab) * cs + as * ab * b + (1.0 - as) * ab * cb;
                    fragColor = vec4(co, as + ab * (1.0 - as));
                }
                """;

        static boolean ensure() {
            if (!initialized) init();
            return ok;
        }

        static void delete() {
            if (program != -1) GL20.glDeleteProgram(program);
            program = -1;
            initialized = false;
            ok = false;
        }

        private static void init() {
            initialized = true;
            int vs = -1, fs = -1;
            try {
                vs = compile(GL20.GL_VERTEX_SHADER, VERTEX);
                fs = compile(GL20.GL_FRAGMENT_SHADER, FRAGMENT);
                if (vs == -1 || fs == -1) return;
                program = GL20.glCreateProgram();
                GL20.glBindAttribLocation(program, 0, "Position");
                GL20.glAttachShader(program, vs);
                GL20.glAttachShader(program, fs);
                GL20.glLinkProgram(program);
                if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                    com.nishiyu.enderframelib.EnderFrameLib.LOGGER.error("レイヤー合成のシェーダーのリンクに失敗: {}", GL20.glGetProgramInfoLog(program));
                    GL20.glDeleteProgram(program);
                    program = -1;
                    return;
                }
                uModelView = loc("ModelViewMat");
                uProj = loc("ProjMat");
                uPose = loc("uPoseMat");
                uClipSpace = loc("uClipSpace");
                uSrc = loc("uSrc");
                uAux = loc("uAux");
                uSize = loc("uSize");
                uOrigin = loc("uOrigin");
                uMode = loc("uMode");
                uOpacity = loc("uOpacity");
                uHasMatrix = loc("uHasMatrix");
                uRow0 = loc("uRow0");
                uRow1 = loc("uRow1");
                uRow2 = loc("uRow2");
                uBlend = loc("uBlend");
                uBackdropOpaque = loc("uBackdropOpaque");
                uDir = loc("uDir");
                uSigma = loc("uSigma");
                uTaps = loc("uTaps");
                uStep = loc("uStep");
                uShadowOffset = loc("uShadowOffset");
                uShadowColor = loc("uShadowColor");
                uClamp = loc("uClamp");
                uMask = loc("uMask");
                uMaskMode = loc("uMaskMode");
                uClipKind = loc("uClipKind");
                uClipA = loc("uClipA");
                uClipRx = loc("uClipRx");
                uClipRy = loc("uClipRy");
                uEdges = loc("uEdges[0]");
                uEdgeCount = loc("uEdgeCount");
                uEvenOdd = loc("uEvenOdd");
                uInv = loc("uInv");
                uInvT = loc("uInvT");
                ok = true;
            } catch (Throwable t) {
                ok = false;
            } finally {
                if (vs != -1) GL20.glDeleteShader(vs);
                if (fs != -1) GL20.glDeleteShader(fs);
            }
        }

        private static int loc(String name) {
            return GL20.glGetUniformLocation(program, name);
        }

        private static int compile(int type, String source) {
            int shader = GL20.glCreateShader(type);
            GL20.glShaderSource(shader, source);
            GL20.glCompileShader(shader);
            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
                com.nishiyu.enderframelib.EnderFrameLib.LOGGER.error("レイヤー合成のシェーダーのコンパイルに失敗: {}", GL20.glGetShaderInfoLog(shader));
                GL20.glDeleteShader(shader);
                return -1;
            }
            return shader;
        }

        static void use() {
            GL20.glUseProgram(program);
            GL20.glUniform1i(uSrc, 0);
            GL20.glUniform1i(uAux, 1);
            GL20.glUniform1i(uMask, 2);
            GL20.glUniform1i(uClipKind, 0);
            GL20.glUniform1i(uMaskMode, 0);
            GL20.glUniform4f(uClamp, 0f, 0f, 1f, 1f);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                FloatBuffer buf = stack.mallocFloat(16);
                RenderSystem.getModelViewMatrix().get(buf);
                GL20.glUniformMatrix4fv(uModelView, false, buf);
                RenderSystem.getProjectionMatrix().get(buf);
                GL20.glUniformMatrix4fv(uProj, false, buf);
            }
        }

        static void common(int[] viewport, int w, int h) {
            GL20.glUniform2f(uSize, w, h);
            GL20.glUniform2f(uOrigin, viewport[0], viewport[1]);
        }

        static void mode(int mode) {
            GL20.glUniform1i(uMode, mode);
        }

        static void opacity(float opacity) {
            GL20.glUniform1f(uOpacity, opacity);
        }

        static void matrix(float[] m) {
            GL20.glUniform1i(uHasMatrix, m == null ? 0 : 1);
            if (m != null) {
                GL20.glUniform4f(uRow0, m[0], m[1], m[2], m[3]);
                GL20.glUniform4f(uRow1, m[4], m[5], m[6], m[7]);
                GL20.glUniform4f(uRow2, m[8], m[9], m[10], m[11]);
            }
        }

        static void pose(Matrix4f pose) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                FloatBuffer buf = stack.mallocFloat(16);
                pose.get(buf);
                GL20.glUniformMatrix4fv(uPose, false, buf);
            }
        }

        /** clip-path の図形と、画素 → 箱の座標の逆変換（{@code map} は {@link #deviceMap} の値）。{@code shape} が null なら切らない。 */
        static void clip(ClipShape shape, float[] map) {
            if (shape == null) {
                GL20.glUniform1i(uClipKind, 0);
                return;
            }
            float det = map[0] * map[3] - map[2] * map[1];
            if (Math.abs(det) < 1e-9f) {
                GL20.glUniform1i(uClipKind, 0);
                return;
            }
            GL20.glUniform4f(uInv, map[3] / det, -map[2] / det, -map[1] / det, map[0] / det);
            GL20.glUniform2f(uInvT, map[4], map[5]);
            float[] d = shape.data();
            switch (shape.kind()) {
                case INSET -> {
                    GL20.glUniform1i(uClipKind, 1);
                    GL20.glUniform4f(uClipA, d[0], d[1], d[2], d[3]);
                    GL20.glUniform4f(uClipRx, d[4], d[5], d[6], d[7]);
                    GL20.glUniform4f(uClipRy, d[8], d[9], d[10], d[11]);
                }
                case CIRCLE -> {
                    GL20.glUniform1i(uClipKind, 2);
                    GL20.glUniform4f(uClipA, d[0], d[1], d[2], 0f);
                }
                case ELLIPSE -> {
                    GL20.glUniform1i(uClipKind, 3);
                    GL20.glUniform4f(uClipA, d[0], d[1], d[2], d[3]);
                }
                case POLYGON -> {
                    GL20.glUniform1i(uClipKind, 4);
                    int edges = Math.min(ClipShape.MAX_EDGES, d.length / 4);
                    GL20.glUniform1i(uEdgeCount, edges);
                    GL20.glUniform1i(uEvenOdd, shape.evenOdd() ? 1 : 0);
                    GL20.glUniform4fv(uEdges, java.util.Arrays.copyOf(d, edges * 4));
                }
            }
        }

        static void clipSpace(boolean clip) {
            GL20.glUniform1f(uClipSpace, clip ? 1f : 0f);
        }
    }
}
