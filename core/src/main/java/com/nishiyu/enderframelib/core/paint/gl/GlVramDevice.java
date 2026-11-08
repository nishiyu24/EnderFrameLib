package com.nishiyu.enderframelib.core.paint.gl;

import com.nishiyu.enderframelib.core.kernel.gpu.PersistentVramBuffer;
import java.nio.ByteBuffer;
import org.lwjgl.opengl.ARBBufferStorage;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL44;
import org.lwjgl.opengl.GLCapabilities;

/**
 * {@link PersistentVramBuffer.Device} の OpenGL 実装。1 インスタンス = GPU 側のバッファの組（永続マップ時は N 面のリング）。
 *
 * <ul>
 *   <li><b>永続マップ</b>（{@code ARB_buffer_storage} / GL 4.4。{@code GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT}）:
 *       マップしたメモリへ直接書く。無ければ {@code glBufferData} + {@code glBufferSubData}（汚れた範囲だけ）。</li>
 *   <li>シェーダーからは<b>バッファテクスチャ</b>（GL 3.1 core で使える）として読む。浮動小数用の {@code R32F} と整数用の
 *       {@code R32I} の 2 ビューを同じバッファに張る（GLSL 1.50 にはビット再解釈の関数が無いため）。
 *       SSBO（GL 4.3）は Minecraft 1.21.1 が要求する 3.2 core では使えないので採らない。</li>
 *   <li><b>3 面リング</b>（永続マップの既定。{@link #DEFAULT_RING}）: 面ごとに GL バッファ・マップ・フェンス・ビューを持つ。
 *       CPU は現在の面だけへ書き（{@link PersistentVramBuffer#flush} が影から汚れた範囲を写す）、フレーム末の {@link #endFrame} が
 *       その面にフェンスを置いて次の面へ進む。CPU が待つのは「これから書く面」のフェンス（= 面の数 - 1 フレーム前の読み出し）だけで、
 *       GPU が直前のフレームを読み終えていなくても止まらない。面を 1 にすると従来の 1 面（直前のフレームを待つ）。</li>
 * </ul>
 *
 * <p>呼び出しは GL コンテキストが現在のスレッドで行う。マップしたメモリは書き込み専用（読んではならない。
 * 中身の確認は {@link #readBack} か、シェーダー経由）。</p>
 */
public final class GlVramDevice implements PersistentVramBuffer.Device, AutoCloseable {
    private static final int MAP_FLAGS = GL30.GL_MAP_WRITE_BIT | GL44.GL_MAP_PERSISTENT_BIT | GL44.GL_MAP_COHERENT_BIT;
    private static final int STORAGE_FLAGS = GL30.GL_MAP_WRITE_BIT | GL44.GL_MAP_PERSISTENT_BIT | GL44.GL_MAP_COHERENT_BIT;

    /** 永続マップの面の既定数。CPU は GPU を待たずに 2 フレーム先まで書ける。 */
    public static final int DEFAULT_RING = 3;

    private final boolean allowPersistent;
    private final int ringSize;
    /** 面ごとの GL バッファ・マップ・フェンス・ビュー。非永続（glBufferSubData）では 1 面。 */
    private int[] buffers = new int[0];
    private ByteBuffer[] maps = new ByteBuffer[0];
    private long[] fences = new long[0];
    private int[] floatViews = new int[0], intViews = new int[0], halfViews = new int[0];
    private int cur;
    private long bytes;
    private boolean persistent;

    public GlVramDevice() {
        this(true);
    }

    /** {@code allowPersistent = false} なら、対応環境でも {@code glBufferSubData} 経路を使う（テスト・回避用）。 */
    public GlVramDevice(boolean allowPersistent) {
        this(allowPersistent, DEFAULT_RING);
    }

    /** {@code ringSize} は永続マップの面の数（1 なら 1 面）。 */
    public GlVramDevice(boolean allowPersistent, int ringSize) {
        if (ringSize < 1) throw new IllegalArgumentException("ringSize: " + ringSize);
        this.allowPersistent = allowPersistent;
        this.ringSize = ringSize;
    }

    /** 現在のコンテキストが永続マップを使えるか。 */
    public static boolean supportsPersistentMapping() {
        GLCapabilities c = GL.getCapabilities();
        return c.OpenGL44 || c.GL_ARB_buffer_storage;
    }

    public boolean isPersistent() { return persistent; }
    /** 現在の面の GL バッファ。 */
    public int bufferId() { return buffers.length == 0 ? 0 : buffers[cur]; }
    public long sizeBytes() { return bytes; }

    @Override public int segmentCount() { return buffers.length; }
    @Override public int currentSegment() { return cur; }
    @Override public ByteBuffer persistentSegment(int i) { return maps[i]; }

    private void allocateArrays(int n) {
        buffers = new int[n];
        maps = new ByteBuffer[n];
        fences = new long[n];
        floatViews = new int[n];
        intViews = new int[n];
        halfViews = new int[n];
        cur = 0;
    }

    @Override
    public ByteBuffer mapPersistent(long size) {
        if (buffers.length != 0) throw new IllegalStateException("1 つの Device は 1 組のバッファ");
        this.bytes = size;
        int previous = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        boolean ok = allowPersistent && supportsPersistentMapping();
        if (ok) {
            allocateArrays(ringSize);
            GLCapabilities c = GL.getCapabilities();
            for (int i = 0; i < ringSize && ok; i++) {
                buffers[i] = GL15.glGenBuffers();
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffers[i]);
                if (c.OpenGL44) GL44.glBufferStorage(GL15.GL_ARRAY_BUFFER, size, STORAGE_FLAGS);
                else ARBBufferStorage.glBufferStorage(GL15.GL_ARRAY_BUFFER, size, STORAGE_FLAGS);
                ByteBuffer m = GL30.glMapBufferRange(GL15.GL_ARRAY_BUFFER, 0, size, MAP_FLAGS);
                if (m == null) { ok = false; break; }
                // GPU 側の初期内容は未定義。CPU の影（0 で始まる）と食い違わないよう 0 で埋める
                for (int k = 0, cap = m.capacity(); k < cap; k++) m.put(k, (byte) 0);
                maps[i] = m;
            }
            if (!ok) { // マップに失敗: 作ったものを捨てて 1 面のフォールバックへ
                for (int i = 0; i < buffers.length; i++) {
                    if (buffers[i] == 0) continue;
                    if (maps[i] != null) { GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffers[i]); GL15.glUnmapBuffer(GL15.GL_ARRAY_BUFFER); }
                    GL15.glDeleteBuffers(buffers[i]);
                }
            }
        }
        if (ok) {
            persistent = true;
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previous);
            return maps[0];
        }
        allocateArrays(1);
        buffers[0] = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffers[0]);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, ByteBuffer.allocateDirect((int) size), GL15.GL_DYNAMIC_DRAW);
        persistent = false;
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previous);
        return null;
    }

    @Override
    public void subData(ByteBuffer src, int offset, int length) {
        int previous = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffers[cur]);
        ByteBuffer view = src.duplicate().order(src.order());
        view.limit(offset + length).position(offset);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, offset, view);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previous);
    }

    /** {@code i} 面目の GPU 読み出しが終わるまで待つ（フェンスが無い・終わっていれば待たない）。 */
    @Override
    public void awaitSegment(int i) {
        if (i >= fences.length || fences[i] == 0L) return;
        int r = GL32.glClientWaitSync(fences[i], GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 1_000_000_000L);
        if (r == GL32.GL_TIMEOUT_EXPIRED) throw new IllegalStateException("GPU が 1 秒以内に前フレームを読み終えない");
        GL32.glDeleteSync(fences[i]);
        fences[i] = 0L;
    }

    /** フレーム頭: これから書く面の GPU 読み出しが終わるのを待つ（3 面なら 2 フレーム前の読み出し。普通は終わっている）。 */
    public void beginFrame() {
        awaitSegment(cur);
    }

    /** フレーム末（描画コマンドを発行した後）: 読んだ面にフェンスを置き、次の面へ進む。 */
    public void endFrame() {
        if (!persistent) return;
        if (fences[cur] != 0L) GL32.glDeleteSync(fences[cur]);
        fences[cur] = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        cur = (cur + 1) % buffers.length;
    }

    /** バッファテクスチャのビューを（必要なら作って）{@code unit} に結ぶ。{@code R32F} 用。現在の面のバッファを見る。 */
    public int bindFloatView(int unit) {
        if (floatViews[cur] == 0) floatViews[cur] = newView(GL30.GL_R32F);
        bind(unit, floatViews[cur]);
        return floatViews[cur];
    }

    /** 同じバッファの整数ビュー（{@code R32I}）。 */
    public int bindIntView(int unit) {
        if (intViews[cur] == 0) intViews[cur] = newView(GL30.GL_R32I);
        bind(unit, intViews[cur]);
        return intViews[cur];
    }

    /** 16-bit 浮動小数のビュー（{@code R16F}）。半精度で詰めた半径・枠幅を読む。添字は 2 バイト単位。 */
    public int bindHalfView(int unit) {
        if (halfViews[cur] == 0) halfViews[cur] = newView(GL30.GL_R16F);
        bind(unit, halfViews[cur]);
        return halfViews[cur];
    }

    private int newView(int internalFormat) {
        int previousTex = GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_BUFFER);
        int tex = GL11.glGenTextures();
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, tex);
        GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, internalFormat, buffers[cur]);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, previousTex);
        return tex;
    }

    private static void bind(int unit, int tex) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, tex);
    }

    /** 現在の面の GPU 側の中身を読み戻す（診断・テスト用。永続マップのメモリは読めないのでこちらを使う）。 */
    public ByteBuffer readBack() { return readBack(cur); }

    /** {@code segment} 面目の GPU 側の中身を読み戻す。 */
    public ByteBuffer readBack(int segment) {
        ByteBuffer out = ByteBuffer.allocateDirect((int) bytes).order(java.nio.ByteOrder.nativeOrder());
        int previous = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffers[segment]);
        GL15.glGetBufferSubData(GL15.GL_ARRAY_BUFFER, 0, out);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previous);
        return out;
    }

    @Override
    public void close() {
        for (int i = 0; i < buffers.length; i++) {
            if (fences[i] != 0L) { GL32.glDeleteSync(fences[i]); fences[i] = 0L; }
            if (floatViews[i] != 0) { GL11.glDeleteTextures(floatViews[i]); floatViews[i] = 0; }
            if (intViews[i] != 0) { GL11.glDeleteTextures(intViews[i]); intViews[i] = 0; }
            if (halfViews[i] != 0) { GL11.glDeleteTextures(halfViews[i]); halfViews[i] = 0; }
            if (buffers[i] != 0) {
                if (persistent) {
                    int previous = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
                    GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffers[i]);
                    GL15.glUnmapBuffer(GL15.GL_ARRAY_BUFFER);
                    GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previous);
                }
                GL15.glDeleteBuffers(buffers[i]);
                buffers[i] = 0;
            }
        }
    }
}
