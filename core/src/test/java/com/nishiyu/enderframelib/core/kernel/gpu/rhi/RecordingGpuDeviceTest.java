package com.nishiyu.enderframelib.core.kernel.gpu.rhi;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

/** GL を使わない {@link RecordingGpuDevice}。共通の振る舞い（{@link GpuDeviceContract}）と、命令の記録・呼び出し数の性質。 */
class RecordingGpuDeviceTest extends GpuDeviceContract {
    @Override
    protected GpuDevice open() {
        return new RecordingGpuDevice();
    }

    /** 描画側が 1 フレームに RHI を呼ぶ形（レコードは直接バッファへ書き、パスは層ごと）。図形の数を変えても呼び出し数は同じ。 */
    private static int callsForOneFrame(int rows) {
        RecordingGpuDevice d = new RecordingGpuDevice();
        GpuBuffer records = d.createBuffer(new GpuDevice.BufferDesc(64L * 4096, GpuDevice.BufferUsage.RECORD));
        GpuTexture target = d.createTexture(GpuDevice.TextureDesc.rgba8(800, 600, true));
        GpuPipeline rects = d.createPipeline(new GpuDevice.PipelineDesc("tex", true, false));
        d.clearLog();
        GpuFrame f = d.beginFrame();
        ByteBuffer w = records.map();
        for (int i = 0; i < rows; i++) w.putInt(i * 64, i); // 図形 1 つ = レコード 1 件の書き込み（RHI を呼ばない）
        GpuPass p = f.beginPass(target, GpuDevice.LoadOp.CLEAR, 0xFFFFFFFF, null);
        p.bind(rects, new GpuPass.Bindings(new GpuBuffer[]{records}, new GpuTexture[]{target}, ByteBuffer.allocateDirect(32)));
        p.drawInstanced(6, rows, 0);
        p.end();
        f.end();
        return d.commandCount();
    }

    @Test
    void theNumberOfCallsDoesNotGrowWithTheNumberOfShapes() {
        assertEquals(callsForOneFrame(500), callsForOneFrame(2000));
        assertEquals(6, callsForOneFrame(2000), "BeginFrame・BeginPass・Bind・Draw・EndPass・EndFrame");
    }

    @Test
    void commandsAreRecordedInOrder() {
        RecordingGpuDevice d = new RecordingGpuDevice();
        GpuTexture t = d.createTexture(GpuDevice.TextureDesc.rgba8(4, 4, true));
        GpuFrame f = d.beginFrame();
        f.beginPass(t, GpuDevice.LoadOp.CLEAR, 0xFF000000, new int[]{1, 1, 2, 2}).end();
        f.readback(t, new int[]{0, 0, 1, 1}, ByteBuffer.allocateDirect(4));
        f.end();
        var c = d.commands();
        assertInstanceOf(RecordingGpuDevice.Command.CreateTexture.class, c.get(0));
        assertInstanceOf(RecordingGpuDevice.Command.BeginFrame.class, c.get(1));
        assertInstanceOf(RecordingGpuDevice.Command.BeginPass.class, c.get(2));
        assertArrayEquals(new int[]{1, 1, 2, 2}, ((RecordingGpuDevice.Command.BeginPass) c.get(2)).scissor());
        assertInstanceOf(RecordingGpuDevice.Command.EndPass.class, c.get(3));
        assertInstanceOf(RecordingGpuDevice.Command.Readback.class, c.get(4));
        assertInstanceOf(RecordingGpuDevice.Command.EndFrame.class, c.get(5));
        assertEquals(1, d.framesBegun());
    }

    @Test
    void drawingRequiresABoundPipeline() {
        RecordingGpuDevice d = new RecordingGpuDevice();
        GpuTexture t = d.createTexture(GpuDevice.TextureDesc.rgba8(4, 4, true));
        GpuFrame f = d.beginFrame();
        GpuPass p = f.beginPass(t, GpuDevice.LoadOp.LOAD, 0, null);
        assertThrows(IllegalStateException.class, () -> p.drawInstanced(3, 1, 0));
        p.end();
        assertThrows(IllegalStateException.class, p::end, "パスは 2 度終えられない");
        f.end();
    }

    @Test
    void bufferWritesAreVisibleThroughMap() {
        RecordingGpuDevice d = new RecordingGpuDevice();
        GpuBuffer b = d.createBuffer(new GpuDevice.BufferDesc(256, GpuDevice.BufferUsage.RECORD));
        b.map().putInt(8, 0x1234);
        assertEquals(0x1234, b.map().getInt(8));
        assertEquals(256, b.size());
    }
}
