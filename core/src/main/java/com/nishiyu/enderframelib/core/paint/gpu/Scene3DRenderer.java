package com.nishiyu.enderframelib.core.paint.gpu;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.BufferDesc;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.BufferUsage;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.CullMode;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.FilterMode;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.LoadOp;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.PipelineDesc;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.TextureDesc;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.TextureFormat;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.VertexLayout;

import com.nishiyu.enderframelib.core.kernel.gpu.PersistentVramBuffer;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuBindings;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuBuffer;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuDevice;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuFrame;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuPass;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuPipeline;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuTexture;
import com.nishiyu.enderframelib.core.kernel.gpu.shader.ShaderProgram;
import com.nishiyu.enderframelib.core.kernel.gpu.shader.ShaderPrograms;
import com.nishiyu.enderframelib.core.scene3d.BlendMode3D;
import com.nishiyu.enderframelib.core.scene3d.CameraSpec;
import com.nishiyu.enderframelib.core.scene3d.DrawBatch3D;
import com.nishiyu.enderframelib.core.scene3d.GeometryPool;
import com.nishiyu.enderframelib.core.scene3d.InstancedBatcher;
import com.nishiyu.enderframelib.core.scene3d.LightDesc;
import com.nishiyu.enderframelib.core.scene3d.MeshHandle;
import com.nishiyu.enderframelib.core.scene3d.Scene3DUploadPlan;
import com.nishiyu.enderframelib.core.scene3d.Scene3DWorld;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * RHI ({@link GpuDevice}) 上で動く 3D インスタンスレンダラ。
 *
 * <p>{@link Scene3DWorld} のオフヒープ SoA と 3 面リングバッファを使い、
 * ワールドごとのオフスクリーンターゲットへプリマルチプライド RGBA として描画する。</p>
 */
public final class Scene3DRenderer implements AutoCloseable {
    private static final int INITIAL_INSTANCE_CAPACITY = 256;
    private static final GpuTexture[] EMPTY_TEX = new GpuTexture[0];

    private final GpuDevice device;
    /** 4 通り: [blend ? 2 : 0 | doubleSided ? 1 : 0]。 */
    private final GpuPipeline[] pipelines = new GpuPipeline[4];
    private final ByteBuffer uniforms = ByteBuffer.allocateDirect(ShaderPrograms.SCENE3D.uniformBytes())
            .order(ByteOrder.nativeOrder());
    private final GpuBuffer[] recordBindings = new GpuBuffer[2];

    private GpuBuffer instanceGpuBuffer;
    private PersistentVramBuffer instanceBuffer;

    private final Map<Long, WorldTarget> targets = new HashMap<>();
    private ByteBuffer readbackScratch;
    private int fboReallocations;
    private int lastDrawCalls;
    private int lastInstanceCount;
    private boolean closed;

    private static final class WorldTarget implements AutoCloseable {
        GpuTexture resolveTex;
        GpuTexture drawColor;
        GpuTexture drawDepth;
        int width;
        int height;
        int samples;

        GpuBuffer vbo;
        GpuBuffer ibo;
        GeometryPool lastPool;
        long uploadedGeomVersion = -1L;

        void closeTargets() {
            if (drawDepth != null) { drawDepth.close(); drawDepth = null; }
            if (drawColor != null && drawColor != resolveTex) { drawColor.close(); }
            drawColor = null;
            if (resolveTex != null) { resolveTex.close(); resolveTex = null; }
        }

        @Override
        public void close() {
            closeTargets();
            if (vbo != null) { vbo.close(); vbo = null; }
            if (ibo != null) { ibo.close(); ibo = null; }
        }
    }

    public Scene3DRenderer(GpuDevice device) {
        this.device = device;
        for (int blend = 0; blend <= 1; blend++) {
            for (int dbl = 0; dbl <= 1; dbl++) {
                boolean premul = blend == 1;
                boolean depthWrite = !premul;
                CullMode cull = dbl == 1 ? CullMode.NONE : CullMode.BACK;
                pipelines[(blend << 1) | dbl] = device.createPipeline(new PipelineDesc(
                        "scene3d", premul, true, depthWrite, cull, VertexLayout.SCENE3D));
            }
        }
        allocateInstanceBuffer(INITIAL_INSTANCE_CAPACITY);
    }

    private void allocateInstanceBuffer(int capacity) {
        if (instanceGpuBuffer != null) {
            instanceGpuBuffer.close();
        }
        long bytes = (long) capacity * InstancedBatcher.INSTANCE_STRIDE_BYTES;
        instanceGpuBuffer = device.createBuffer(new BufferDesc(bytes, BufferUsage.RECORD));
        instanceBuffer = new PersistentVramBuffer(InstancedBatcher.INSTANCE_STRIDE_BYTES, capacity, instanceGpuBuffer);
        recordBindings[0] = instanceGpuBuffer;
        recordBindings[1] = instanceGpuBuffer;
    }

    private void ensureInstanceCapacity(int needed) {
        if (needed <= instanceBuffer.capacity()) return;
        int nextCap = instanceBuffer.capacity();
        while (nextCap < needed) {
            nextCap *= 2;
        }
        allocateInstanceBuffer(nextCap);
    }

    /** 単独のフレームを開いて描画する（テスト・単発呼び出し用）。 */
    public GpuTexture render(Scene3DWorld world, int width, int height) {
        GpuFrame frame = device.beginFrame();
        GpuTexture out = render(frame, world, width, height);
        frame.end();
        return out;
    }

    /** 開いている {@link GpuFrame} 上で 3D ワールドを描き、合成用カラーテクスチャを返す（呼び出し前に開いたパスは閉じておくこと）。 */
    public GpuTexture render(GpuFrame frame, Scene3DWorld world, int width, int height) {
        if (closed) throw new IllegalStateException("Scene3DRenderer is closed");
        int w = Math.max(1, width);
        int h = Math.max(1, height);

        Scene3DUploadPlan plan = world.updateAndPrepare(w, h);
        ensureInstanceCapacity(Math.max(1, plan.instanceCount()));

        WorldTarget target = targets.computeIfAbsent(world.worldId(), k -> new WorldTarget());
        ensureTargets(target, w, h, world.msaaSamples());
        ensureGeometryUploaded(target, world.geometry());

        int bg = world.clearColorArgb();
        int a = (bg >>> 24) & 0xFF;
        int r = (((bg >>> 16) & 0xFF) * a + 127) / 255;
        int g = (((bg >>> 8) & 0xFF) * a + 127) / 255;
        int b = ((bg & 0xFF) * a + 127) / 255;
        int premulClear = (a << 24) | (r << 16) | (g << 8) | b;

        int drawCalls = 0;
        int instancesDrawn = 0;

        boolean hasDraw = plan.instanceCount() > 0 && target.vbo != null && target.ibo != null;
        if (hasDraw) {
            world.packInstances(instanceBuffer);
            instanceBuffer.flush();
        }

        GpuPass pass = frame.beginPass(target.drawColor, target.drawDepth,
                LoadOp.CLEAR, premulClear, 1.0f, null);
        if (hasDraw) {
            GeometryPool geom = world.geometry();
            CameraSpec cam = world.camera();
            LightDesc light = world.light();
            float[] vp = cam.viewProjMatrix();
            float lr = light.lightR() * light.intensity();
            float lg = light.lightG() * light.intensity();
            float lb = light.lightB() * light.intensity();

            boolean meshBound = false;
            for (DrawBatch3D batch : plan.batches()) {
                MeshHandle mesh = geom.meshOrNull(batch.meshId());
                if (mesh == null || batch.instanceCount() <= 0) continue;

                uniforms.clear();
                boolean flipY = !device.caps().bottomUpFramebuffer();
                boolean z01 = device.caps().zeroToOneDepth();
                for (int col = 0; col < 4; col++) {
                    int c4 = col * 4;
                    float cx = vp[c4];
                    float cy = flipY ? -vp[c4 + 1] : vp[c4 + 1];
                    float cz = z01 ? (0.5f * vp[c4 + 2] + 0.5f * vp[c4 + 3]) : vp[c4 + 2];
                    float cw = vp[c4 + 3];
                    uniforms.putFloat(cx).putFloat(cy).putFloat(cz).putFloat(cw);
                }
                uniforms.putFloat(cam.eyeX()).putFloat(cam.eyeY()).putFloat(cam.eyeZ()).putFloat(0f);
                uniforms.putFloat(light.dirX()).putFloat(light.dirY()).putFloat(light.dirZ()).putFloat(0f);
                uniforms.putFloat(lr).putFloat(lg).putFloat(lb).putFloat(0f);
                uniforms.putFloat(light.ambientR()).putFloat(light.ambientG()).putFloat(light.ambientB()).putFloat(0f);
                uniforms.putInt(batch.baseInstance());
                uniforms.flip();

                int key = (batch.blendMode() == BlendMode3D.TRANSPARENT ? 2 : 0) | (batch.doubleSided() ? 1 : 0);
                pass.bind(pipelines[key], new GpuBindings(recordBindings, EMPTY_TEX, uniforms));
                if (!meshBound) {
                    pass.bindMesh(target.vbo, target.ibo);
                    meshBound = true;
                }
                long indexOffsetBytes = (long) mesh.firstIndex() * GeometryPool.INDEX_STRIDE_BYTES;
                pass.drawIndexedInstanced(mesh.indexCount(), indexOffsetBytes,
                        batch.instanceCount(), mesh.firstVertex(), 0);
                drawCalls++;
                instancesDrawn += batch.instanceCount();
            }
        }
        pass.end();

        if (target.drawColor != target.resolveTex) {
            frame.copy(target.drawColor, target.resolveTex, new int[]{0, 0, w, h});
        }

        lastDrawCalls = drawCalls;
        lastInstanceCount = instancesDrawn;
        return target.resolveTex;
    }

    /** レンダリング済みカラーテクスチャからトップダウン順 ARGB ピクセルを読み出す（テスト・CPU フォールバック用）。 */
    public int[] readTexturePixelsArgb(GpuTexture tex, int width, int height) {
        int[] out = new int[width * height];
        if (tex == null || width <= 0 || height <= 0) return out;
        GpuFrame frame = device.beginFrame();
        readTexturePixelsArgb(frame, tex, width, height, out);
        frame.end();
        return out;
    }

    /** 開いているフレーム上でテクスチャからトップダウン順 ARGB ピクセルを読み出す。 */
    public void readTexturePixelsArgb(GpuFrame frame, GpuTexture tex, int width, int height, int[] out) {
        int need = width * height * 4;
        if (readbackScratch == null || readbackScratch.capacity() < need) {
            readbackScratch = ByteBuffer.allocateDirect(need).order(ByteOrder.BIG_ENDIAN);
        }
        readbackScratch.clear().limit(need);
        frame.readback(tex, new int[]{0, 0, width, height}, readbackScratch);
        readbackScratch.flip();
        IntBuffer ib = readbackScratch.asIntBuffer();
        int n = width * height;
        for (int i = 0; i < n; i++) {
            out[i] = Integer.rotateRight(ib.get(i), 8);
        }
    }

    public boolean isPersistent() { return device.caps().persistentMap(); }
    public PersistentVramBuffer instanceBuffer() { return instanceBuffer; }
    public GpuBuffer instanceGpuBuffer() { return instanceGpuBuffer; }
    public int fboReallocations() { return fboReallocations; }
    public int lastDrawCalls() { return lastDrawCalls; }
    public int lastInstanceCount() { return lastInstanceCount; }

    private void ensureTargets(WorldTarget t, int width, int height, int requestedSamples) {
        int maxS = device.caps().maxSamples();
        int samples = requestedSamples <= 1 ? 1 : Math.min(requestedSamples, Math.max(1, maxS));
        if (t.resolveTex != null && t.width == width && t.height == height && t.samples == samples) {
            return;
        }
        t.closeTargets();
        t.width = width;
        t.height = height;
        t.samples = samples;

        t.resolveTex = device.createTexture(TextureDesc.rgba8Linear(width, height, true));
        if (samples > 1) {
            t.drawColor = device.createTexture(new TextureDesc(
                    width, height, TextureFormat.RGBA8, true, samples, FilterMode.NEAREST));
        } else {
            t.drawColor = t.resolveTex;
        }
        t.drawDepth = device.createTexture(new TextureDesc(
                width, height, TextureFormat.DEPTH24, true, samples, FilterMode.NEAREST));
        fboReallocations++;
    }

    private void ensureGeometryUploaded(WorldTarget t, GeometryPool pool) {
        if (pool.totalVertices() == 0 || pool.totalIndices() == 0) return;
        if (t.lastPool == pool && t.uploadedGeomVersion == pool.version() && t.vbo != null && t.ibo != null) {
            return;
        }
        ByteBuffer vbSrc = pool.vertexBufferSlice();
        ByteBuffer ibSrc = pool.indexBufferSlice();
        int vbBytes = vbSrc.remaining();
        int ibBytes = ibSrc.remaining();

        if (t.vbo == null || t.vbo.size() < vbBytes) {
            if (t.vbo != null) t.vbo.close();
            t.vbo = device.createBuffer(new BufferDesc(vbBytes, BufferUsage.VERTEX));
        }
        if (t.ibo == null || t.ibo.size() < ibBytes) {
            if (t.ibo != null) t.ibo.close();
            t.ibo = device.createBuffer(new BufferDesc(ibBytes, BufferUsage.INDEX));
        }

        ByteBuffer vbDst = t.vbo.map();
        vbDst.position(0);
        vbDst.put(vbSrc);
        vbDst.position(0);
        t.vbo.markDirty(0, vbBytes);

        ByteBuffer ibDst = t.ibo.map();
        ibDst.position(0);
        ibDst.put(ibSrc);
        ibDst.position(0);
        t.ibo.markDirty(0, ibBytes);

        t.lastPool = pool;
        t.uploadedGeomVersion = pool.version();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        for (WorldTarget t : targets.values()) {
            t.close();
        }
        targets.clear();
        if (instanceGpuBuffer != null) {
            instanceGpuBuffer.close();
            instanceGpuBuffer = null;
        }
        for (int i = 0; i < pipelines.length; i++) {
            if (pipelines[i] != null) {
                pipelines[i].close();
                pipelines[i] = null;
            }
        }
    }
}
