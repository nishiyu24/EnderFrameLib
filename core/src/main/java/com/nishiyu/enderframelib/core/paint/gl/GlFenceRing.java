package com.nishiyu.enderframelib.core.paint.gl;

import org.lwjgl.opengl.GL32;

/**
 * リングの面ごとのフェンスと、現在の面。複数の {@link GlVramDevice} が 1 つを共有できる（{@link GlTreeStore} は 7 本のバッファを同じフレームで書いて
 * 同じフレームで読むので、フェンスは 1 面につき 1 つで足りる）。待つ・置く GL 呼び出しがバッファの数ではなく 1 回になる。
 * 永続マップを使うデバイスが {@link #activate} するまでは面 1・フェンス無し（{@link #end} は何もしない）。
 */
final class GlFenceRing implements AutoCloseable {
    private long[] fences = new long[1];
    private int cur;
    private boolean active;
    private int waits, fencesPlaced;

    /** 永続マップのデバイスが面の数を決める（共有するデバイスは同じ数）。 */
    void activate(int size) {
        if (active) {
            if (size != fences.length) throw new IllegalStateException("共有するデバイスの面の数が違う: " + size + " != " + fences.length);
            return;
        }
        fences = new long[size];
        cur = 0;
        active = true;
    }

    boolean isActive() { return active; }
    int current() { return cur; }
    int size() { return fences.length; }
    /** 実際に待った（フェンスがあった）回数と、置いたフェンスの数（テスト・診断用）。 */
    int waits() { return waits; }
    int fencesPlaced() { return fencesPlaced; }

    /** {@code i} 面目の GPU 読み出しが終わるまで待つ（フェンスが無い・待ち済みなら何もしない）。 */
    void await(int i) {
        if (i >= fences.length || fences[i] == 0L) return;
        int r = GL32.glClientWaitSync(fences[i], GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 1_000_000_000L);
        if (r == GL32.GL_TIMEOUT_EXPIRED) throw new IllegalStateException("GPU が 1 秒以内に前フレームを読み終えない");
        GL32.glDeleteSync(fences[i]);
        fences[i] = 0L;
        waits++;
    }

    /** フレーム末: 読んだ面にフェンスを置き、次の面へ進む。 */
    void end() {
        if (!active) return;
        if (fences[cur] != 0L) GL32.glDeleteSync(fences[cur]);
        fences[cur] = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        fencesPlaced++;
        cur = (cur + 1) % fences.length;
    }

    @Override
    public void close() {
        for (int i = 0; i < fences.length; i++) {
            if (fences[i] != 0L) { GL32.glDeleteSync(fences[i]); fences[i] = 0L; }
        }
    }
}
