package com.nishiyu.enderframelib.core.paint.gl;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.kernel.gpu.GpuAnimation;
import com.nishiyu.enderframelib.core.kernel.gpu.GpuAnimationReference;
import com.nishiyu.enderframelib.core.kernel.gpu.PropertyTrees;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuFrame;
import com.nishiyu.enderframelib.core.kernel.gpu.shader.ShaderProgram;
import com.nishiyu.enderframelib.core.paint.gpu.GpuTreeStore;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * 実際の OpenGL 上で {@link GlDevice} のレコードバッファ・永続 3 面リング・フェンス同期を確かめる。
 * GPU のシェーダー（{@link GpuAnimation#GLSL}）がバッファテクスチャ越しにレコードを読んで世界行列を計算し、
 * その結果を Java の参照実装と突き合わせる。永続マップ経路と {@code glBufferSubData} 経路の両方を通す。
 * GL を使えない環境ではスキップ。
 */
class GlVramDeviceTest {
    private static LwjglGlRenderBackend host;
    private static int ubo;
    private static String unavailable;
    private static int vao, program, fbo, tex;
    private static final int SLOTS = 16;

    @BeforeAll
    static void setUp() {
        try {
            host = new LwjglGlRenderBackend(8, 8);
            GLFW.glfwMakeContextCurrent(host.windowHandle());
            GL.createCapabilities();
            vao = GL30.glGenVertexArrays();
            GL30.glBindVertexArray(vao);
            program = link(com.nishiyu.enderframelib.core.kernel.gpu.shader.Glsl.gl150("""
                    void main() {
                        vec2 p = vec2(float((EF_VERTEX_ID << 1) & 2), float(EF_VERTEX_ID & 2));
                        gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
                    }
                    """), com.nishiyu.enderframelib.core.kernel.gpu.shader.Glsl.gl150(GpuAnimation.GLSL + """
                    out vec4 o;
                    void main() {
                        int x = int(gl_FragCoord.x);
                        mat3 w = efWorld(x >> 1);
                        o = ((x & 1) == 0) ? vec4(w[0].x, w[0].y, w[1].x, w[1].y) : vec4(w[2].x, w[2].y, 0.0, 0.0);
                    }
                    """));
            tex = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, SLOTS * 2, 1, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (FloatBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            fbo = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 0);
            assertEquals(GL30.GL_FRAMEBUFFER_COMPLETE, GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER));
            ubo = org.lwjgl.opengl.GL15.glGenBuffers();
            org.lwjgl.opengl.GL31.glUniformBlockBinding(program, org.lwjgl.opengl.GL31.glGetUniformBlockIndex(program, "PassParams"), 0);
        } catch (Throwable t) {
            unavailable = t.toString();
            if (host != null) { host.close(); host = null; }
        }
    }

    @AfterAll
    static void tearDown() {
        if (host != null) host.close();
    }

    private static int compile(int type, String src) {
        int s = GL20.glCreateShader(type);
        GL20.glShaderSource(s, src);
        GL20.glCompileShader(s);
        if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == 0) throw new IllegalStateException(GL20.glGetShaderInfoLog(s));
        return s;
    }

    private static int link(String vs, String fs) {
        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, compile(GL20.GL_VERTEX_SHADER, vs));
        GL20.glAttachShader(p, compile(GL20.GL_FRAGMENT_SHADER, fs));
        GL20.glLinkProgram(p);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) throw new IllegalStateException(GL20.glGetProgramInfoLog(p));
        return p;
    }

    private static void requireGl() {
        Assumptions.assumeTrue(host != null, "OpenGL を使えない: " + unavailable);
    }

    /** 全スロットの世界行列を GPU で計算して読み戻す: スロットごとに {@code [a b c d tx ty]}。 */
    private static float[][] gpuWorldMatrices(GlDevice dev, GpuTreeStore store, float frameMs) {
        GpuFrame f = dev.beginFrame();
        store.flush();
        return drawOnlyWorldMatrices(dev, store, f, frameMs);
    }

    private static long copiedBytes(PropertyTrees t) {
        return t.transforms.ringCopiedBytes() + t.clips.ringCopiedBytes() + t.effects.ringCopiedBytes()
                + t.primitives.ringCopiedBytes() + t.scrolls.ringCopiedBytes()
                + t.curveTable.ringCopiedBytes() + t.order.ringCopiedBytes();
    }

    private static void buildScene(PropertyTrees t, Random r) {
        int custom = t.registerCurve(0.68f, -0.55f, 0.27f, 1.55f);
        int[] easings = {PropertyTrees.EASE_LINEAR, PropertyTrees.EASE_EASE, PropertyTrees.EASE_IN, PropertyTrees.EASE_OUT,
                PropertyTrees.EASE_IN_OUT, custom};
        int parent = PropertyTrees.NONE;
        for (int i = 0; i < SLOTS; i++) {
            int s = t.addTransform(i % 5 == 0 ? PropertyTrees.NONE : parent, r.nextFloat() * 40 - 20, r.nextFloat() * 40 - 20,
                    0.5f + r.nextFloat(), 0.5f + r.nextFloat(), r.nextFloat() - 0.5f);
            // 一部は分解し直さない行列のままの姿勢（傾きを含む。アニメ無し）。TRS のスロットと同じ鎖に混ざる
            if (i % 7 == 2 && i >= 9) t.setTransformMatrix(s, 0.6f + r.nextFloat(), r.nextFloat() - 0.5f, r.nextFloat() - 0.5f,
                    0.6f + r.nextFloat(), r.nextFloat() * 30 - 15, r.nextFloat() * 30 - 15);
            if (i % 3 == 1) { t.setScroll(s, i); t.setScrollOffset(i, r.nextFloat() * 60, r.nextFloat() * 60); }
            if (i % 2 == 0 && !(i % 7 == 2 && i >= 9)) t.startTransformAnimation(s, r.nextFloat() * 10, r.nextFloat() * 10, 1f, 1f, 0f,
                    100f, 300f, easings[(i / 2) % easings.length]);
            parent = s;
        }
    }

    private static void assertMatchesCpu(PropertyTrees t, float[][] gpu, float frameMs, String msg) {
        float[] m = new float[6], scratch = new float[17];
        for (int s = 0; s < SLOTS; s++) {
            GpuAnimationReference.worldMatrix(t, s, frameMs, m, scratch);
            for (int k = 0; k < 6; k++) {
                float tol = 2e-3f * Math.max(1f, Math.abs(m[k]));
                assertEquals(m[k], gpu[s][k], tol, msg + " slot " + s + " comp " + k + " @" + frameMs);
            }
        }
    }

    private void runBothPaths(boolean persistentRequested) {
        requireGl();
        if (persistentRequested) Assumptions.assumeTrue(GlDevice.supportsPersistentMapping(), "永続マップ非対応");
        GlDevice dev = new GlDevice(persistentRequested, 3);
        try (GpuTreeStore store = new GpuTreeStore(dev, 64, 8, 8, 64)) {
            assertEquals(persistentRequested, store.isPersistent());
            PropertyTrees t = store.trees;
            buildScene(t, new Random(21));
            long before = copiedBytes(t);
            store.flush();
            assertTrue(copiedBytes(t) > before);
            for (float frame : new float[]{0f, 150f, 250f, 5000f}) {
                assertMatchesCpu(t, gpuWorldMatrices(dev, store, frame), frame, persistentRequested ? "persistent" : "subdata");
            }

            // 差分だけを書き換えて再評価: スクロールと 1 つのアニメ
            t.setScrollOffset(1, 33f, 77f);
            t.startTransformAnimation(4, 0, 0, 2f, 2f, 1f, 50f, 100f, PropertyTrees.EASE_IN_OUT);
            long before2 = copiedBytes(t);
            store.flush();
            long sent2 = copiedBytes(t) - before2;
            assertTrue(sent2 > 0 && sent2 < 8 + 64 * 8, "差分は小さい: " + sent2);
            for (float frame : new float[]{60f, 120f, 9000f}) {
                assertMatchesCpu(t, gpuWorldMatrices(dev, store, frame), frame, "after-update");
            }
        }
    }

    @Test
    void shaderReadsTreesThroughPersistentMapping() { runBothPaths(true); }

    @Test
    void shaderReadsTreesThroughBufferSubData() { runBothPaths(false); }

    @Test
    void buffersStartZeroedAndReadBackWhatWasWritten() {
        requireGl();
        GlDevice dev = new GlDevice(false, 3);
        try (GpuTreeStore store = new GpuTreeStore(dev, 64, 8, 8, 64)) {
            ByteBuffer zero = ByteBuffer.allocateDirect((int) store.transforms.size()).order(ByteOrder.nativeOrder());
            dev.readBuffer(store.transforms, zero);
            for (int i = 0; i < zero.capacity(); i += 4) assertEquals(0, zero.getInt(i), "GPU バッファは 0 で始まる");
            int s = store.trees.addTransform(PropertyTrees.NONE, 12.5f, -3f, 1f, 1f, 0f);
            store.trees.setScroll(s, 7);
            long before = store.trees.transforms.ringCopiedBytes();
            store.flush();
            assertEquals(64, store.trees.transforms.ringCopiedBytes() - before, "1 レコード分");
            GpuFrame f = dev.beginFrame();
            dev.flushBuffersForTest();
            f.end();
            ByteBuffer gpu = ByteBuffer.allocateDirect((int) store.transforms.size()).order(ByteOrder.nativeOrder());
            dev.readBuffer(store.transforms, gpu);
            assertEquals(12.5f, gpu.getFloat(s * 64 + 24));
            assertEquals(-3f, gpu.getFloat(s * 64 + 28));
            assertEquals(7, gpu.getInt(s * 64 + 4));
            assertEquals(PropertyTrees.NONE, gpu.getInt(s * 64));
            long before2 = store.trees.transforms.ringCopiedBytes();
            store.flush();
            assertEquals(0, store.trees.transforms.ringCopiedBytes() - before2, "変更が無ければ転送 0");
        }
    }

    @Test
    void ringOfThreeSegmentsStaysCorrectWhileValuesChangeEveryFrame() {
        requireGl();
        Assumptions.assumeTrue(GlDevice.supportsPersistentMapping(), "永続マップ非対応");
        GlDevice dev = new GlDevice(true, 3);
        try (GpuTreeStore store = new GpuTreeStore(dev, 64, 8, 8, 64)) {
            assertEquals(3, dev.segmentCount(store.transforms), "既定は 3 面");
            PropertyTrees t = store.trees;
            buildScene(t, new Random(5));
            Random r = new Random(77);
            // 正規の形 beginFrame → 書き込み → flush → 描画 → endFrame を、リングが何周もするまで繰り返す
            for (int frame = 0; frame < 20; frame++) {
                GpuFrame gf = dev.beginFrame();
                t.setScrollOffset(1 + (frame % 3) * 3, r.nextFloat() * 50, r.nextFloat() * 50);
                if (frame % 4 == 0) t.startTransformAnimation(2 + frame % 5, 0, 0, 1.5f, 1.5f, 0.5f, 50f, 120f, PropertyTrees.EASE_OUT);
                assertEquals(0, store.flush());
                float ms = 40f * frame;
                assertMatchesCpu(t, drawOnlyWorldMatrices(dev, store, gf, ms), ms, "ring frame " + frame);
            }
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        }
    }

    @Test
    void everyRingSegmentConvergesToTheSameContentAfterACycle() {
        requireGl();
        Assumptions.assumeTrue(GlDevice.supportsPersistentMapping(), "永続マップ非対応");
        GlDevice dev = new GlDevice(true, 3);
        try (GpuTreeStore store = new GpuTreeStore(dev, 64, 8, 8, 64)) {
            PropertyTrees t = store.trees;
            buildScene(t, new Random(9));
            for (int frame = 0; frame < 3; frame++) {
                GpuFrame gf = dev.beginFrame();
                if (frame == 1) t.setScrollOffset(1, 11f, 22f);
                store.flush();
                drawOnlyWorldMatrices(dev, store, gf, 0f);
            }
            // 3 フレームで全面が現在になった: どの面の GPU 側も CPU の影と同じ
            for (int seg = 0; seg < 3; seg++) {
                ByteBuffer gpu = ByteBuffer.allocateDirect((int) store.transforms.size()).order(ByteOrder.nativeOrder());
                dev.readBuffer(store.transforms, gpu, seg);
                for (int slot = 0; slot < SLOTS; slot++) {
                    for (int off = 0; off < PropertyTrees.TRANSFORM_STRIDE; off += 4) {
                        assertEquals(t.transforms.getInt(slot, off), gpu.getInt(slot * PropertyTrees.TRANSFORM_STRIDE + off),
                                "seg " + seg + " slot " + slot + " off " + off);
                    }
                }
            }
        }
    }

    @Test
    void ringDoesNotStallWhenTheGpuIsStillBusyWithThePreviousFrame() {
        requireGl();
        Assumptions.assumeTrue(GlDevice.supportsPersistentMapping(), "永続マップ非対応");
        GlDevice dev = new GlDevice(true, 3);
        try (GpuTreeStore ring = new GpuTreeStore(dev, 64, 8, 8, 64)) {
            buildScene(ring.trees, new Random(3));
            for (int frame = 0; frame < 12; frame++) {
                GpuFrame gf = dev.beginFrame();
                ring.trees.setScrollOffset(1, frame, frame);
                ring.flush();
                drawOnlyWorldMatrices(dev, ring, gf, frame * 10f);
            }
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
            // 最後のフレームの結果も CPU と一致（リングで取り違えていない）
            GpuFrame gf = dev.beginFrame();
            ring.flush();
            assertMatchesCpu(ring.trees, drawOnlyWorldMatrices(dev, ring, gf, 500f), 500f, "after-ring");
        }
    }

    @Test
    void sevenBuffersShareOneFencePerFrame() {
        requireGl();
        Assumptions.assumeTrue(GlDevice.supportsPersistentMapping(), "永続マップ非対応");
        GlDevice dev = new GlDevice(true, 3);
        try (GpuTreeStore store = new GpuTreeStore(dev, 64, 8, 8, 64)) {
            buildScene(store.trees, new Random(2));
            int frames = 12;
            for (int f = 0; f < frames; f++) {
                GpuFrame gf = dev.beginFrame();
                store.trees.setScrollOffset(1, f, f);
                store.flush();
                assertMatchesCpu(store.trees, drawOnlyWorldMatrices(dev, store, gf, f * 10f), f * 10f, "shared fence frame " + f);
            }
            assertEquals(frames, dev.fencesPlaced(), "フェンスはフレームにつき 1 つ（バッファ 7 本ぶんではない）");
            assertTrue(dev.fenceWaits() <= frames, "待ち（フェンスがあった回数）もフレームにつき高々 1 回: " + dev.fenceWaits());
            // 書き込み前の待ちは現在の面に限られ、3 面のどのバッファも取り違えない（全面が影と一致）
            for (int f = 0; f < 3; f++) {
                GpuFrame gf = dev.beginFrame();
                store.flush();
                drawOnlyWorldMatrices(dev, store, gf, 0f);
            }
            for (int seg = 0; seg < 3; seg++) {
                ByteBuffer gpu = ByteBuffer.allocateDirect((int) store.transforms.size()).order(ByteOrder.nativeOrder());
                dev.readBuffer(store.transforms, gpu, seg);
                for (int slot = 0; slot < SLOTS; slot++) {
                    assertEquals(store.trees.transforms.getInt(slot, 0), gpu.getInt(slot * PropertyTrees.TRANSFORM_STRIDE), "seg " + seg + " slot " + slot);
                }
            }
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        }
    }

    /** 書き込み・flush は呼び出し側が済ませた後に、描画して読み戻す（beginFrame / flush は呼ばない）。 */
    private static float[][] drawOnlyWorldMatrices(GlDevice dev, GpuTreeStore store, GpuFrame gf, float frameMs) {
        dev.flushBuffersForTest();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL11.glViewport(0, 0, SLOTS * 2, 1);
        GL20.glUseProgram(program);
        dev.bindBufferViewForTest(store.transforms, 0, ShaderProgram.ViewFormat.FLOAT32);
        dev.bindBufferViewForTest(store.transforms, 1, ShaderProgram.ViewFormat.INT32);
        dev.bindBufferViewForTest(store.scrolls, 2, ShaderProgram.ViewFormat.FLOAT32);
        dev.bindBufferViewForTest(store.curves, 3, ShaderProgram.ViewFormat.FLOAT32);
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "uTrF"), 0);
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "uTrI"), 1);
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "uScroll"), 2);
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "uCurves"), 3);
        java.nio.ByteBuffer ub = BufferUtils.createByteBuffer(32);
        ub.putFloat(8, frameMs);
        org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER, ubo);
        org.lwjgl.opengl.GL15.glBufferData(org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER, ub, org.lwjgl.opengl.GL15.GL_STREAM_DRAW);
        GL30.glBindBufferBase(org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER, 0, ubo);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        gf.end();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        FloatBuffer px = BufferUtils.createFloatBuffer(SLOTS * 2 * 4);
        GL11.glReadPixels(0, 0, SLOTS * 2, 1, GL11.GL_RGBA, GL11.GL_FLOAT, px);
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        float[][] out = new float[SLOTS][6];
        for (int s = 0; s < SLOTS; s++) {
            out[s][0] = px.get(s * 8); out[s][1] = px.get(s * 8 + 1); out[s][2] = px.get(s * 8 + 2); out[s][3] = px.get(s * 8 + 3);
            out[s][4] = px.get(s * 8 + 4); out[s][5] = px.get(s * 8 + 5);
        }
        return out;
    }

    @Test
    void persistentMappingMatchesCapabilityAndFencesDoNotStall() {
        requireGl();
        boolean supported = GlDevice.supportsPersistentMapping();
        GlDevice dev = new GlDevice();
        try (GpuTreeStore store = new GpuTreeStore(dev, 64, 8, 8, 64)) {
            assertEquals(supported, store.isPersistent(), "対応していれば自動で永続マップ");
            for (int frame = 0; frame < 30; frame++) {
                GpuFrame gf = dev.beginFrame();
                store.trees.addTransform(PropertyTrees.NONE, frame, 0, 1, 1, 0);
                store.flush();
                drawOnlyWorldMatrices(dev, store, gf, frame);
            }
        }
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
    }
}
