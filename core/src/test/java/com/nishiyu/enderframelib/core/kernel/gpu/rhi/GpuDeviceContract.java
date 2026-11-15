package com.nishiyu.enderframelib.core.kernel.gpu.rhi;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

/**
 * {@link GpuDevice} が満たすべき振る舞い（G1）。{@link RecordingGpuDevice}（GL 不要）と GL 実装の両方へ同じ検査を通す。
 * 座標は左上原点・画素は RGBA8 の事前乗算。
 */
public abstract class GpuDeviceContract {
    /** 検査する実装を作る（使えなければ assumption で中断）。 */
    protected abstract GpuDevice open();

    protected void closeDevice(GpuDevice d) {
        d.close();
    }

    static byte[] readAll(GpuDevice d, GpuTexture t, int[] region) {
        GpuFrame f = d.beginFrame();
        ByteBuffer out = ByteBuffer.allocateDirect(region[2] * region[3] * t.format().bytesPerPixel);
        f.readback(t, region, out);
        f.end();
        byte[] b = new byte[out.position()];
        out.flip();
        out.get(b);
        return b;
    }

    static int px(byte[] rgba, int w, int x, int y) {
        int o = (y * w + x) * 4;
        return ((rgba[o] & 0xFF) << 24) | ((rgba[o + 1] & 0xFF) << 16) | ((rgba[o + 2] & 0xFF) << 8) | (rgba[o + 3] & 0xFF);
    }

    static void clear(GpuDevice d, GpuTexture t, int argb, int[] scissor) {
        GpuFrame f = d.beginFrame();
        f.beginPass(t, GpuDevice.LoadOp.CLEAR, argb, scissor).end();
        f.end();
    }

    @Test
    void clearFillsTheWholeTarget() {
        GpuDevice d = open();
        try {
            GpuTexture t = d.createTexture(GpuDevice.TextureDesc.rgba8(8, 6, true));
            clear(d, t, 0xFF336699, null);
            byte[] all = readAll(d, t, new int[]{0, 0, 8, 6});
            for (int y = 0; y < 6; y++) for (int x = 0; x < 8; x++) assertEquals(0x336699FF, px(all, 8, x, y), x + "," + y);
        } finally {
            closeDevice(d);
        }
    }

    @Test
    void scissoredClearTouchesOnlyTheRegionWithATopLeftOrigin() {
        GpuDevice d = open();
        try {
            GpuTexture t = d.createTexture(GpuDevice.TextureDesc.rgba8(8, 6, true));
            clear(d, t, 0x00000000, null);
            clear(d, t, 0xFFFF0000, new int[]{2, 1, 3, 2}); // x 2..4, y 1..2（上から数える）
            byte[] all = readAll(d, t, new int[]{0, 0, 8, 6});
            for (int y = 0; y < 6; y++) {
                for (int x = 0; x < 8; x++) {
                    boolean inside = x >= 2 && x < 5 && y >= 1 && y < 3;
                    assertEquals(inside ? 0xFF0000FF : 0x00000000, px(all, 8, x, y), x + "," + y);
                }
            }
        } finally {
            closeDevice(d);
        }
    }

    @Test
    void loadKeepsThePreviousContents() {
        GpuDevice d = open();
        try {
            GpuTexture t = d.createTexture(GpuDevice.TextureDesc.rgba8(4, 4, true));
            clear(d, t, 0xFF00FF00, null);
            GpuFrame f = d.beginFrame();
            f.beginPass(t, GpuDevice.LoadOp.LOAD, 0xFFFF0000, null).end();
            f.end();
            assertEquals(0x00FF00FF, px(readAll(d, t, new int[]{0, 0, 4, 4}), 4, 3, 3));
        } finally {
            closeDevice(d);
        }
    }

    @Test
    void uploadThenReadbackRoundTripsWithTheFirstRowOnTop() {
        GpuDevice d = open();
        try {
            GpuTexture t = d.createTexture(GpuDevice.TextureDesc.rgba8(8, 6, true));
            clear(d, t, 0x00000000, null);
            ByteBuffer src = ByteBuffer.allocateDirect(4 * 3 * 4);
            for (int y = 0; y < 3; y++) for (int x = 0; x < 4; x++) src.put((byte) (x * 10 + 1)).put((byte) (y * 10 + 2)).put((byte) 7).put((byte) 255);
            src.flip();
            GpuFrame f = d.beginFrame();
            f.upload(t, 2, 1, 4, 3, src);
            f.end();
            byte[] all = readAll(d, t, new int[]{0, 0, 8, 6});
            for (int y = 0; y < 3; y++) {
                for (int x = 0; x < 4; x++) {
                    assertEquals(((x * 10 + 1) << 24) | ((y * 10 + 2) << 16) | (7 << 8) | 255, px(all, 8, 2 + x, 1 + y), x + "," + y);
                }
            }
            assertEquals(0, px(all, 8, 1, 1), "外側は触らない");
            assertEquals(0, px(all, 8, 2, 0));
            assertEquals(0, px(all, 8, 2, 4));
        } finally {
            closeDevice(d);
        }
    }

    @Test
    void copyMovesARegionToTheSamePosition() {
        GpuDevice d = open();
        try {
            GpuTexture a = d.createTexture(GpuDevice.TextureDesc.rgba8(8, 6, true));
            GpuTexture b = d.createTexture(GpuDevice.TextureDesc.rgba8(8, 6, true));
            clear(d, a, 0xFF000000, null);
            clear(d, a, 0xFF0000FF, new int[]{1, 4, 3, 2}); // 下寄りの青
            clear(d, b, 0x00000000, null);
            GpuFrame f = d.beginFrame();
            f.copy(a, b, new int[]{0, 3, 8, 3}); // 下半分
            f.end();
            byte[] all = readAll(d, b, new int[]{0, 0, 8, 6});
            assertEquals(0, px(all, 8, 1, 2), "領域の外はそのまま");
            assertEquals(0x000000FF, px(all, 8, 0, 3), "黒（コピーされた）");
            assertEquals(0x0000FFFF, px(all, 8, 1, 4), "青の位置が上下反転していない");
            assertEquals(0x0000FFFF, px(all, 8, 3, 5));
            assertEquals(0x000000FF, px(all, 8, 4, 4));
        } finally {
            closeDevice(d);
        }
    }

    @Test
    void singleChannelTexturesRoundTrip() {
        GpuDevice d = open();
        try {
            GpuTexture t = d.createTexture(new GpuDevice.TextureDesc(4, 2, GpuDevice.TextureFormat.R8, true, 1));
            ByteBuffer src = ByteBuffer.allocateDirect(8);
            for (int i = 0; i < 8; i++) src.put((byte) (i * 30));
            src.flip();
            GpuFrame f = d.beginFrame();
            f.upload(t, 0, 0, 4, 2, src);
            ByteBuffer out = ByteBuffer.allocateDirect(8);
            f.readback(t, new int[]{0, 0, 4, 2}, out);
            f.end();
            for (int i = 0; i < 8; i++) assertEquals(i * 30, out.get(i) & 0xFF, "i=" + i);
        } finally {
            closeDevice(d);
        }
    }

    @Test
    void framesAndPassesCannotOverlap() {
        GpuDevice d = open();
        try {
            GpuTexture t = d.createTexture(GpuDevice.TextureDesc.rgba8(4, 4, true));
            GpuFrame f = d.beginFrame();
            assertThrows(IllegalStateException.class, d::beginFrame, "フレームは 1 つずつ");
            GpuPass p = f.beginPass(t, GpuDevice.LoadOp.CLEAR, 0, null);
            assertThrows(IllegalStateException.class, () -> f.upload(t, 0, 0, 1, 1, ByteBuffer.allocateDirect(4)), "パスの途中で別の命令は入れられない");
            p.end();
            f.end();
            assertThrows(IllegalStateException.class, () -> f.beginPass(t, GpuDevice.LoadOp.LOAD, 0, null), "提出後は使えない");
            d.beginFrame().end(); // 次のフレームは開ける
        } finally {
            closeDevice(d);
        }
    }

    @Test
    void regionsOutsideTheTextureAreRejected() {
        GpuDevice d = open();
        try {
            GpuTexture t = d.createTexture(GpuDevice.TextureDesc.rgba8(4, 4, true));
            GpuFrame f = d.beginFrame();
            assertThrows(IllegalArgumentException.class, () -> f.readback(t, new int[]{2, 2, 4, 4}, ByteBuffer.allocateDirect(64)));
            assertThrows(IllegalArgumentException.class, () -> f.upload(t, 0, 0, 4, 4, ByteBuffer.allocateDirect(8)), "画素が足りない");
            f.end();
        } finally {
            closeDevice(d);
        }
    }

    @Test
    void externalTexturesKeepTheirNativeHandle() {
        GpuDevice d = open();
        try {
            GpuTexture own = d.createTexture(GpuDevice.TextureDesc.rgba8(4, 4, true));
            long h = d.nativeHandle(own);
            GpuTexture wrapped = d.wrapExternal(h, 4, 4);
            assertEquals(h, d.nativeHandle(wrapped));
            assertEquals(4, wrapped.width());
            wrapped.close(); // ホストのテクスチャは消さない
            clear(d, own, 0xFF102030, null);
            assertEquals(0x102030FF, px(readAll(d, own, new int[]{0, 0, 1, 1}), 1, 0, 0), "元のテクスチャはまだ使える");
        } finally {
            closeDevice(d);
        }
    }

    @Test
    void capsDescribeTheDevice() {
        GpuDevice d = open();
        try {
            assertTrue(d.caps().maxTextureSize() >= 1024);
            assertThrows(IllegalArgumentException.class, () -> d.createTexture(GpuDevice.TextureDesc.rgba8(d.caps().maxTextureSize() + 1, 1, false)));
        } finally {
            closeDevice(d);
        }
    }

    @Test
    void unknownShadersAreRejected() {
        GpuDevice d = open();
        try {
            assertThrows(IllegalArgumentException.class, () -> d.createPipeline(new GpuDevice.PipelineDesc("no-such-shader", true, false)));
        } finally {
            closeDevice(d);
        }
    }

    @Test
    void bindingNeedsTheDeclaredUniformsAndTextures() {
        GpuDevice d = open();
        try {
            GpuTexture t = d.createTexture(GpuDevice.TextureDesc.rgba8(4, 4, true));
            GpuPipeline p = d.createPipeline(new GpuDevice.PipelineDesc("tex", true, false));
            GpuFrame f = d.beginFrame();
            GpuPass pass = f.beginPass(t, GpuDevice.LoadOp.LOAD, 0, null);
            assertThrows(IllegalArgumentException.class, () -> pass.bind(p, new GpuBindings(new GpuBuffer[0], new GpuTexture[]{t}, ByteBuffer.allocateDirect(8))), "定数が足りない");
            assertThrows(IllegalArgumentException.class, () -> pass.bind(p, new GpuBindings(new GpuBuffer[0], new GpuTexture[0], ByteBuffer.allocateDirect(32))), "テクスチャが足りない");
            assertThrows(IllegalStateException.class, () -> pass.drawInstanced(4, 1, 0), "結ぶ前は描けない");
            pass.end();
            f.end();
        } finally {
            closeDevice(d);
        }
    }
}
