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
import com.nishiyu.enderframelib.core.scene3d.Bloom3D;
import com.nishiyu.enderframelib.core.scene3d.CameraSpec;
import com.nishiyu.enderframelib.core.scene3d.DrawBatch3D;
import com.nishiyu.enderframelib.core.scene3d.GeometryPool;
import com.nishiyu.enderframelib.core.scene3d.InstancedBatcher;
import com.nishiyu.enderframelib.core.scene3d.LightDesc;
import com.nishiyu.enderframelib.core.scene3d.MeshHandle;
import com.nishiyu.enderframelib.core.scene3d.Scene3DUploadPlan;
import com.nishiyu.enderframelib.core.scene3d.Texture3D;
import com.nishiyu.enderframelib.core.scene3d.Scene3DWorld;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * RHI ({@link GpuDevice}) 上で動く 3D インスタンスレンダラ。
 *
 * <p>{@link Scene3DWorld} のオフヒープ SoA と 3 面リングバッファを使い、
 * ワールドごとのオフスクリーンターゲットへプリマルチプライド RGBA として描画する。</p>
 */
public final class Scene3DRenderer implements AutoCloseable {
    private static final int INITIAL_INSTANCE_CAPACITY = 256;
    /** 追加ライト配列（{@code uExtra}）の uniform ブロック内の位置（std140）。 */
    private static final int EXTRA_OFFSET = ShaderPrograms.SCENE3D.std140Offsets()[7];
    /** 使われなくなった画像のテクスチャを手放すまでのフレーム数。 */
    private static final long IMAGE_IDLE_FRAMES = 300;

    /** 材質のベースカラー画像 → GPU テクスチャ。画像は不変なので、初めて描くときに 1 回だけ上げる。 */
    private static final class ImageEntry {
        final GpuTexture texture;
        long lastUsedFrame;

        ImageEntry(GpuTexture texture) { this.texture = texture; }
    }

    private final Map<Texture3D, ImageEntry> images = new IdentityHashMap<>();
    /** 画像の無い材質が読む 1×1 の白（サンプラーは常に何かを指す必要がある）。 */
    private GpuTexture whiteTex;
    private long frameSeq;

    private final GpuDevice device;
    /** 4 通り: [blend ? 2 : 0 | doubleSided ? 1 : 0]。 */
    private final GpuPipeline[] pipelines = new GpuPipeline[4];
    /** HDR（RGBA16F）へ描く版（Bloom 有効のワールド）。並びは {@link #pipelines} と同じ。 */
    private final GpuPipeline[] pipelinesHdr = new GpuPipeline[4];
    private final GpuPipeline bloomPipeline;
    private final ByteBuffer bloomUniforms = ByteBuffer.allocateDirect(ShaderPrograms.BLOOM.uniformBytes())
            .order(ByteOrder.nativeOrder());
    private static final GpuBuffer[] NO_RECORDS = new GpuBuffer[0];
    private final ByteBuffer uniforms = ByteBuffer.allocateDirect(ShaderPrograms.SCENE3D.uniformBytes())
            .order(ByteOrder.nativeOrder());

    private GpuBuffer instanceGpuBuffer;
    /** このフレームで、これまでのワールドが使ったスロット数（後のワールドは、この続きに書く）。 */
    private GpuFrame cursorFrame;
    private int cursor;
    /** 容量を広げたときに手放した旧バッファ。同じフレームの先のワールドの描画がまだ参照するので、次のフレームまで閉じない。 */
    private final java.util.List<GpuBuffer> retired = new java.util.ArrayList<>();
    private PersistentVramBuffer instanceBuffer;

    private final Map<Long, WorldTarget> targets = new HashMap<>();
    private ByteBuffer readbackScratch;
    private int fboReallocations;
    private int lastDrawCalls;
    private int lastInstanceCount;
    private boolean closed;

    private static final class WorldTarget implements AutoCloseable {
        GpuTexture resolveTex;
        /** Bloom 有効のときの HDR の結果（MSAA なら解決先）。無効なら null。 */
        GpuTexture hdrTex;
        /** Bloom: 縮小の連鎖（添字 1..levels。0 は未使用）と、拡大して足し戻した連鎖（1..levels-1）。 */
        GpuTexture[] bloomDown, bloomUp;
        int bloomLevels;
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
            if (drawColor != null && drawColor != resolveTex && drawColor != hdrTex) { drawColor.close(); }
            drawColor = null;
            if (hdrTex != null) { hdrTex.close(); hdrTex = null; }
            closeBloomLevels();
            if (resolveTex != null) { resolveTex.close(); resolveTex = null; }
        }

        void closeBloomLevels() {
            for (GpuTexture[] arr : new GpuTexture[][]{bloomDown, bloomUp}) {
                if (arr != null) for (GpuTexture t : arr) if (t != null) t.close();
            }
            bloomDown = null;
            bloomUp = null;
            bloomLevels = 0;
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
        for (int blend = 0; blend <= 1; blend++) {
            for (int dbl = 0; dbl <= 1; dbl++) {
                pipelinesHdr[(blend << 1) | dbl] = device.createPipeline(new PipelineDesc(
                        "scene3d_hdr", blend == 1, true, blend == 0, dbl == 1 ? CullMode.NONE : CullMode.BACK, VertexLayout.SCENE3D));
            }
        }
        bloomPipeline = device.createPipeline(new PipelineDesc("bloom", false, false));
        allocateInstanceBuffer(INITIAL_INSTANCE_CAPACITY);
    }

    private GpuTexture textureFor(DrawBatch3D batch) {
        if (batch.texture() == null) return whiteTex;
        ImageEntry e = images.get(batch.texture());
        e.lastUsedFrame = frameSeq;
        return e.texture;
    }

    /** 画像がまだ GPU に無ければ上げる（RGBA8・先頭の行が上・線形補間・端は張り付き）。 */
    private void ensureImage(GpuFrame frame, Texture3D img) {
        ImageEntry e = images.get(img);
        if (e == null) {
            int w = img.width(), h = img.height();
            GpuTexture tex = device.createTexture(TextureDesc.rgba8Linear(w, h, false));
            ByteBuffer bytes = ByteBuffer.allocateDirect(w * h * 4);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = img.argb(x, y);
                    bytes.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >>> 24));
                }
            }
            bytes.flip();
            frame.upload(tex, 0, 0, w, h, bytes);
            e = new ImageEntry(tex);
            images.put(img, e);
        }
        e.lastUsedFrame = frameSeq;
    }

    /** しばらく使われていない画像のテクスチャを閉じる（描画が終わった古いフレームの分だけなので、使用中のものには触れない）。 */
    private void sweepIdleImages() {
        if (images.isEmpty()) return;
        images.values().removeIf(e -> {
            if (frameSeq - e.lastUsedFrame <= IMAGE_IDLE_FRAMES) return false;
            e.texture.close();
            return true;
        });
    }

    /** GPU に載っている画像の枚数（試験用）。 */
    public int imageTextureCount() { return images.size(); }

    private void allocateInstanceBuffer(int capacity) {
        if (instanceGpuBuffer != null) {
            retired.add(instanceGpuBuffer);
        }
        long bytes = (long) capacity * InstancedBatcher.INSTANCE_STRIDE_BYTES;
        instanceGpuBuffer = device.createBuffer(new BufferDesc(bytes, BufferUsage.RECORD));
        instanceBuffer = new PersistentVramBuffer(InstancedBatcher.INSTANCE_STRIDE_BYTES, capacity, instanceGpuBuffer);
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

        if (frame != cursorFrame) {
            frameSeq++;
            sweepIdleImages(); // 新しいフレーム: 範囲を頭から使い直し、前のフレームで手放したバッファを閉じる
            cursorFrame = frame;
            cursor = 0;
            for (GpuBuffer old : retired) old.close();
            retired.clear();
        }
        Scene3DUploadPlan plan = world.updateAndPrepare(w, h);
        int base = cursor;
        ensureInstanceCapacity(Math.max(1, base + plan.instanceCount()));
        // このワールドの描画が参照するバッファ（容量を広げると以降は別のバッファになるので、描画ごとに持つ）
        GpuBuffer[] bindingsBuffers = {instanceGpuBuffer, instanceGpuBuffer};

        WorldTarget target = targets.computeIfAbsent(world.worldId(), k -> new WorldTarget());
        boolean bloom = world.bloomEnabled();
        Bloom3D.Plan bloomPlan = bloom ? Bloom3D.plan(world.bloomSigma(), w, h) : null;
        ensureTargets(target, w, h, world.msaaSamples(), bloomPlan);
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
            cursor = base + world.packInstances(instanceBuffer, base);
            instanceBuffer.flush();
        }

        if (hasDraw) {
            for (DrawBatch3D batch : plan.batches()) {
                if (batch.texture() != null) ensureImage(frame, batch.texture());
            }
            if (whiteTex == null) {
                whiteTex = device.createTexture(TextureDesc.rgba8(1, 1, false));
                ByteBuffer one = ByteBuffer.allocateDirect(4);
                for (int i = 0; i < 4; i++) one.put((byte) 0xFF);
                one.flip();
                frame.upload(whiteTex, 0, 0, 1, 1, one);
            }
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
                uniforms.putInt(base + batch.baseInstance());
                int extras = light.extraCount();
                uniforms.putInt(extras);
                uniforms.position(EXTRA_OFFSET);
                float[] extraData = light.extraData();
                for (int i = 0, n = extras * LightDesc.VEC4_PER_EXTRA * 4; i < n; i++) uniforms.putFloat(extraData[i]);
                uniforms.position(ShaderPrograms.SCENE3D.uniformBytes());
                uniforms.flip();

                int key = (batch.blendMode() == BlendMode3D.TRANSPARENT ? 2 : 0) | (batch.doubleSided() ? 1 : 0);
                pass.bind((bloom ? pipelinesHdr : pipelines)[key], new GpuBindings(bindingsBuffers, new GpuTexture[]{textureFor(batch)}, uniforms));
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

        GpuTexture hdrDst = bloom ? target.hdrTex : target.resolveTex;
        if (target.drawColor != hdrDst) {
            frame.copy(target.drawColor, hdrDst, new int[]{0, 0, w, h});
        }
        if (bloom) runBloom(frame, target, world, w, h, bloomPlan);

        lastDrawCalls = drawCalls;
        lastInstanceCount = instancesDrawn;
        return target.resolveTex;
    }

    /**
     * HDR の {@code hdrTex} から Bloom を作り、ACES で {@code resolveTex}（RGBA8）へ合成する。
     * 取り出し付き縮小 → 縮小 → 拡大して足し戻し（{@link Bloom3D} と同じ式）→ 合成。
     */
    private void runBloom(GpuFrame frame, WorldTarget t, Scene3DWorld world, int w, int h, Bloom3D.Plan plan) {
        int p = plan.levels();
        float threshold = world.bloomThreshold();
        float knee = Math.max(1e-4f, threshold * Bloom3D.KNEE_RATIO);
        GpuTexture src = t.hdrTex;
        for (int i = 1; i <= p; i++) {
            GpuTexture dst = t.bloomDown[i];
            bloomPass(frame, dst, i == 1 ? 0 : 1, src, src, plan.offset(), threshold, knee, 0f);
            src = dst;
        }
        GpuTexture cur = t.bloomDown[p];
        for (int i = p - 1; i >= 1; i--) {
            GpuTexture dst = t.bloomUp[i];
            bloomPass(frame, dst, 2, cur, t.bloomDown[i], plan.offset(), threshold, knee, 0f);
            cur = dst;
        }
        bloomPass(frame, t.resolveTex, 3, t.hdrTex, cur, plan.offset(), threshold, knee, world.bloomIntensity());
    }

    private void bloomPass(GpuFrame frame, GpuTexture target, int mode, GpuTexture src, GpuTexture aux,
                           float offset, float threshold, float knee, float intensity) {
        int tw = target.width(), th = target.height();
        bloomUniforms.clear();
        bloomUniforms.putFloat(0f).putFloat(0f).putFloat(tw).putFloat(th);
        bloomUniforms.putFloat(tw).putFloat(th);
        bloomUniforms.putFloat(offset).putFloat(threshold).putFloat(knee).putFloat(intensity);
        bloomUniforms.putInt(mode);
        bloomUniforms.flip();
        GpuPass pass = frame.beginPass(target, LoadOp.CLEAR, 0, null);
        pass.bind(bloomPipeline, new GpuBindings(NO_RECORDS, new GpuTexture[]{src, aux}, bloomUniforms));
        pass.drawInstanced(4, 1, 0);
        pass.end();
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

    private void ensureTargets(WorldTarget t, int width, int height, int requestedSamples, Bloom3D.Plan bloomPlan) {
        int maxS = device.caps().maxSamples();
        int samples = requestedSamples <= 1 ? 1 : Math.min(requestedSamples, Math.max(1, maxS));
        int levels = bloomPlan == null ? 0 : bloomPlan.levels();
        if (t.resolveTex != null && t.width == width && t.height == height && t.samples == samples && t.bloomLevels == levels) {
            return;
        }
        t.closeTargets();
        t.width = width;
        t.height = height;
        t.samples = samples;

        t.resolveTex = device.createTexture(TextureDesc.rgba8Linear(width, height, true));
        TextureFormat drawFormat = levels > 0 ? TextureFormat.RGBA16F : TextureFormat.RGBA8;
        if (levels > 0) { // Bloom: HDR で描き、縮小・拡大の連鎖を作る
            t.hdrTex = device.createTexture(new TextureDesc(width, height, TextureFormat.RGBA16F, true, 1, FilterMode.LINEAR));
            t.bloomLevels = levels;
            t.bloomDown = new GpuTexture[levels + 1];
            t.bloomUp = new GpuTexture[levels];
            int lw = width, lh = height;
            for (int i = 1; i <= levels; i++) {
                lw = Bloom3D.half(lw);
                lh = Bloom3D.half(lh);
                t.bloomDown[i] = device.createTexture(new TextureDesc(lw, lh, TextureFormat.RGBA16F, true, 1, FilterMode.LINEAR));
                if (i < levels) t.bloomUp[i] = device.createTexture(new TextureDesc(lw, lh, TextureFormat.RGBA16F, true, 1, FilterMode.LINEAR));
            }
        }
        if (samples > 1) {
            t.drawColor = device.createTexture(new TextureDesc(
                    width, height, drawFormat, true, samples, FilterMode.NEAREST));
        } else {
            t.drawColor = levels > 0 ? t.hdrTex : t.resolveTex;
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
        for (ImageEntry e : images.values()) e.texture.close();
        images.clear();
        if (whiteTex != null) { whiteTex.close(); whiteTex = null; }
        if (instanceGpuBuffer != null) {
            instanceGpuBuffer.close();
            instanceGpuBuffer = null;
        }
        for (GpuBuffer old : retired) old.close();
        retired.clear();
        for (int i = 0; i < pipelines.length; i++) {
            if (pipelines[i] != null) {
                pipelines[i].close();
                pipelines[i] = null;
            }
        }
    }
}
