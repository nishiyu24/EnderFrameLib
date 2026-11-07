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
 * {@link PersistentVramBuffer.Device} の OpenGL 実装。1 インスタンス = GPU 側の 1 バッファ。
 *
 * <ul>
 *   <li><b>永続マップ</b>（{@code ARB_buffer_storage} / GL 4.4。{@code GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT}）:
 *       マップしたメモリへ直接書く。無ければ {@code glBufferData} + {@code glBufferSubData}（汚れた範囲だけ）。</li>
 *   <li>シェーダーからは<b>バッファテクスチャ</b>（GL 3.1 core で使える）として読む。浮動小数用の {@code R32F} と整数用の
 *       {@code R32I} の 2 ビューを同じバッファに張る（GLSL 1.50 にはビット再解釈の関数が無いため）。
 *       SSBO（GL 4.3）は Minecraft 1.21.1 が要求する 3.2 core では使えないので採らない。</li>
 *   <li>永続マップ中の CPU 書き込みと GPU 読み出しの競合は、フレーム末の {@link #endFrame}（フェンス）と
 *       フレーム頭の {@link #beginFrame}（直前のフェンスを待つ）で避ける。GPU が前フレームを読み終えていなければそこで待つ
 *       （1 面のバッファのため。3 面のリングは今後）。</li>
 * </ul>
 *
 * <p>呼び出しは GL コンテキストが現在のスレッドで行う。マップしたメモリは書き込み専用（読んではならない。
 * 中身の確認は {@link #readBack} か、シェーダー経由）。</p>
 */
public final class GlVramDevice implements PersistentVramBuffer.Device, AutoCloseable {
    private static final int MAP_FLAGS = GL30.GL_MAP_WRITE_BIT | GL44.GL_MAP_PERSISTENT_BIT | GL44.GL_MAP_COHERENT_BIT;
    private static final int STORAGE_FLAGS = GL30.GL_MAP_WRITE_BIT | GL44.GL_MAP_PERSISTENT_BIT | GL44.GL_MAP_COHERENT_BIT;

    private final boolean allowPersistent;
    private int buffer;
    private long bytes;
    private boolean persistent;
    private int floatView, intView, halfView;
    private long fence;

    public GlVramDevice() {
        this(true);
    }

    /** {@code allowPersistent = false} なら、対応環境でも {@code glBufferSubData} 経路を使う（テスト・回避用）。 */
    public GlVramDevice(boolean allowPersistent) {
        this.allowPersistent = allowPersistent;
    }

    /** 現在のコンテキストが永続マップを使えるか。 */
    public static boolean supportsPersistentMapping() {
        GLCapabilities c = GL.getCapabilities();
        return c.OpenGL44 || c.GL_ARB_buffer_storage;
    }

    public boolean isPersistent() { return persistent; }
    public int bufferId() { return buffer; }
    public long sizeBytes() { return bytes; }

    @Override
    public ByteBuffer mapPersistent(long size) {
        if (buffer != 0) throw new IllegalStateException("1 つの Device は 1 つのバッファ");
        this.bytes = size;
        int previous = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        buffer = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
        ByteBuffer mapped = null;
        if (allowPersistent && supportsPersistentMapping()) {
            GLCapabilities c = GL.getCapabilities();
            if (c.OpenGL44) GL44.glBufferStorage(GL15.GL_ARRAY_BUFFER, size, STORAGE_FLAGS);
            else ARBBufferStorage.glBufferStorage(GL15.GL_ARRAY_BUFFER, size, STORAGE_FLAGS);
            mapped = GL30.glMapBufferRange(GL15.GL_ARRAY_BUFFER, 0, size, MAP_FLAGS);
        }
        // GPU 側の初期内容は未定義。CPU の影（0 で始まる）と食い違わないよう 0 で埋める
        if (mapped != null) {
            persistent = true;
            for (int i = 0, n = mapped.capacity(); i < n; i++) mapped.put(i, (byte) 0);
        } else {
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, ByteBuffer.allocateDirect((int) size), GL15.GL_DYNAMIC_DRAW);
            persistent = false;
        }
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previous);
        return mapped;
    }

    @Override
    public void subData(ByteBuffer src, int offset, int length) {
        int previous = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
        ByteBuffer view = src.duplicate().order(src.order());
        view.limit(offset + length).position(offset);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, offset, view);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previous);
    }

    /** フレーム頭: 永続マップ中なら、直前のフレームの GPU 読み出しが終わるのを待つ（終わっていれば待たない）。 */
    public void beginFrame() {
        if (fence == 0L) return;
        int r = GL32.glClientWaitSync(fence, GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 1_000_000_000L);
        if (r == GL32.GL_TIMEOUT_EXPIRED) throw new IllegalStateException("GPU が 1 秒以内に前フレームを読み終えない");
        GL32.glDeleteSync(fence);
        fence = 0L;
    }

    /** フレーム末（描画コマンドを発行した後）: この時点までの読み出しにフェンスを置く。 */
    public void endFrame() {
        if (!persistent) return;
        if (fence != 0L) GL32.glDeleteSync(fence);
        fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    }

    /** バッファテクスチャのビューを（必要なら作って）{@code unit} に結ぶ。{@code R32F} 用。 */
    public int bindFloatView(int unit) {
        if (floatView == 0) floatView = newView(GL30.GL_R32F);
        bind(unit, floatView);
        return floatView;
    }

    /** 同じバッファの整数ビュー（{@code R32I}）。 */
    public int bindIntView(int unit) {
        if (intView == 0) intView = newView(GL30.GL_R32I);
        bind(unit, intView);
        return intView;
    }

    /** 16-bit 浮動小数のビュー（{@code R16F}）。半精度で詰めた半径・枠幅を読む。添字は 2 バイト単位。 */
    public int bindHalfView(int unit) {
        if (halfView == 0) halfView = newView(GL30.GL_R16F);
        bind(unit, halfView);
        return halfView;
    }

    private int newView(int internalFormat) {
        int previousTex = GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_BUFFER);
        int tex = GL11.glGenTextures();
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, tex);
        GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, internalFormat, buffer);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, previousTex);
        return tex;
    }

    private static void bind(int unit, int tex) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, tex);
    }

    /** GPU 側の中身を読み戻す（診断・テスト用。永続マップのメモリは読めないのでこちらを使う）。 */
    public ByteBuffer readBack() {
        ByteBuffer out = ByteBuffer.allocateDirect((int) bytes).order(java.nio.ByteOrder.nativeOrder());
        int previous = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
        GL15.glGetBufferSubData(GL15.GL_ARRAY_BUFFER, 0, out);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previous);
        return out;
    }

    @Override
    public void close() {
        if (fence != 0L) { GL32.glDeleteSync(fence); fence = 0L; }
        if (floatView != 0) { GL11.glDeleteTextures(floatView); floatView = 0; }
        if (intView != 0) { GL11.glDeleteTextures(intView); intView = 0; }
        if (halfView != 0) { GL11.glDeleteTextures(halfView); halfView = 0; }
        if (buffer != 0) {
            if (persistent) {
                int previous = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
                GL15.glUnmapBuffer(GL15.GL_ARRAY_BUFFER);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previous);
            }
            GL15.glDeleteBuffers(buffer);
            buffer = 0;
        }
    }
}
