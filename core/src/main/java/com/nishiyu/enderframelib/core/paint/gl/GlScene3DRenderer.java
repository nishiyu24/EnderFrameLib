package com.nishiyu.enderframelib.core.paint.gl;

import com.nishiyu.enderframelib.core.kernel.gpu.PersistentVramBuffer;
import com.nishiyu.enderframelib.core.scene3d.BlendMode3D;
import com.nishiyu.enderframelib.core.scene3d.CameraSpec;
import com.nishiyu.enderframelib.core.scene3d.DrawBatch3D;
import com.nishiyu.enderframelib.core.scene3d.GeometryPool;
import com.nishiyu.enderframelib.core.scene3d.InstancedBatcher;
import com.nishiyu.enderframelib.core.scene3d.LightDesc;
import com.nishiyu.enderframelib.core.scene3d.MeshHandle;
import com.nishiyu.enderframelib.core.scene3d.Scene3DUploadPlan;
import com.nishiyu.enderframelib.core.scene3d.Scene3DWorld;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * OpenGL 3.2 Core / GLSL 150 向けの 3D インスタンスレンダラ。
 *
 * <p>{@link Scene3DWorld} のオフヒープ SoA と {@link GlVramDevice} の 3 面リング TBO を使い、
 * ワールドごとのオフスクリーン FBO へプリマルチプライド RGBA として描画する。</p>
 */
public final class GlScene3DRenderer implements AutoCloseable {
    private static final int INITIAL_INSTANCE_CAPACITY = 256;

    private final GlFenceRing fenceRing;
    private final boolean allowPersistent;
    private GlVramDevice instanceDevice;
    private PersistentVramBuffer instanceBuffer;

    private int program;
    private int uViewProj = -1;
    private int uBaseInstance = -1;
    private int uCameraPos = -1;
    private int uLightDir = -1;
    private int uLightColor = -1;
    private int uAmbientColor = -1;

    private final Map<Long, WorldTarget> targets = new HashMap<>();
    private int fboReallocations;
    private int lastDrawCalls;
    private int lastInstanceCount;
    private boolean closed;

    private static final class WorldTarget {
        int fbo;
        int colorTex;
        int depthRbo;
        int msaaFbo;
        int msaaColorRbo;
        int msaaDepthRbo;
        int width;
        int height;
        int samples;

        int vao;
        int vbo;
        int ibo;
        GeometryPool lastPool;
        long uploadedGeomVersion = -1L;
    }

    public GlScene3DRenderer() {
        this(true);
    }

    public GlScene3DRenderer(boolean allowPersistent) {
        this.allowPersistent = allowPersistent;
        this.fenceRing = new GlFenceRing();
        initProgram();
        allocateInstanceBuffer(INITIAL_INSTANCE_CAPACITY);
    }

    private void initProgram() {
        int vs = compileShader(GL20.GL_VERTEX_SHADER, Scene3DShaders.VERTEX_GLSL_150);
        int fs = compileShader(GL20.GL_FRAGMENT_SHADER, Scene3DShaders.FRAGMENT_GLSL_150);
        int prog = GL20.glCreateProgram();
        GL20.glAttachShader(prog, vs);
        GL20.glAttachShader(prog, fs);
        GL20.glBindAttribLocation(prog, Scene3DShaders.ATTR_POS, "aPos");
        GL20.glBindAttribLocation(prog, Scene3DShaders.ATTR_NORM, "aNorm");
        GL20.glBindAttribLocation(prog, Scene3DShaders.ATTR_UV, "aUv");
        GL20.glBindAttribLocation(prog, Scene3DShaders.ATTR_TANGENT, "aTangent");
        GL20.glLinkProgram(prog);
        if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(prog);
            GL20.glDeleteProgram(prog);
            GL20.glDeleteShader(vs);
            GL20.glDeleteShader(fs);
            throw new IllegalStateException("Scene3D shader link failed: " + log);
        }
        GL20.glDetachShader(prog, vs);
        GL20.glDetachShader(prog, fs);
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);

        this.program = prog;
        int prevProg = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        GL20.glUseProgram(prog);
        GL20.glUniform1i(GL20.glGetUniformLocation(prog, "uInstanceTboF"), Scene3DShaders.UNIT_INSTANCE_TBO_F);
        GL20.glUniform1i(GL20.glGetUniformLocation(prog, "uInstanceTboI"), Scene3DShaders.UNIT_INSTANCE_TBO_I);
        uViewProj = GL20.glGetUniformLocation(prog, "uViewProj");
        uBaseInstance = GL20.glGetUniformLocation(prog, "uBaseInstance");
        uCameraPos = GL20.glGetUniformLocation(prog, "uCameraPos");
        uLightDir = GL20.glGetUniformLocation(prog, "uLightDir");
        uLightColor = GL20.glGetUniformLocation(prog, "uLightColor");
        uAmbientColor = GL20.glGetUniformLocation(prog, "uAmbientColor");
        GL20.glUseProgram(prevProg);
    }

    private static int compileShader(int type, String src) {
        int id = GL20.glCreateShader(type);
        GL20.glShaderSource(id, src);
        GL20.glCompileShader(id);
        if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(id);
            GL20.glDeleteShader(id);
            throw new IllegalStateException("Scene3D shader compile failed (" + type + "): " + log);
        }
        return id;
    }

    private void allocateInstanceBuffer(int capacity) {
        if (instanceDevice != null) {
            instanceDevice.close();
        }
        instanceDevice = new GlVramDevice(allowPersistent, 3, fenceRing);
        instanceBuffer = new PersistentVramBuffer(InstancedBatcher.INSTANCE_STRIDE_BYTES, capacity, instanceDevice);
    }

    private void ensureInstanceCapacity(int needed) {
        if (needed <= instanceBuffer.capacity()) return;
        int nextCap = instanceBuffer.capacity();
        while (nextCap < needed) {
            nextCap *= 2;
        }
        allocateInstanceBuffer(nextCap);
    }

    /**
     * {@link Scene3DWorld} をオフスクリーン FBO へ描画し、合成に使う GL テクスチャ ID を返す。
     */
    public int render(Scene3DWorld world, int width, int height) {
        if (closed) throw new IllegalStateException("GlScene3DRenderer is closed");
        int w = Math.max(1, width);
        int h = Math.max(1, height);

        Scene3DUploadPlan plan = world.updateAndPrepare(w, h);
        ensureInstanceCapacity(Math.max(1, plan.instanceCount()));

        WorldTarget target = targets.computeIfAbsent(world.worldId(), k -> new WorldTarget());
        ensureFboTarget(target, w, h, world.msaaSamples());
        ensureGeometryUploaded(target, world.geometry());

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer savedDrawFbo = stack.mallocInt(1);
            IntBuffer savedReadFbo = stack.mallocInt(1);
            IntBuffer savedViewport = stack.mallocInt(4);
            FloatBuffer savedClearColor = stack.mallocFloat(4);
            savedDrawFbo.put(0, GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING));
            savedReadFbo.put(0, GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING));
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, savedViewport);
            GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, savedClearColor);

            int savedProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            int savedVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            int savedArrayBuf = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            int savedElementBuf = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
            int savedActiveTex = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            boolean savedDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            boolean savedDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            boolean savedCullFace = GL11.glIsEnabled(GL11.GL_CULL_FACE);
            int savedCullMode = GL11.glGetInteger(GL11.GL_CULL_FACE_MODE);
            int savedFrontFace = GL11.glGetInteger(GL11.GL_FRONT_FACE);
            boolean savedBlend = GL11.glIsEnabled(GL11.GL_BLEND);
            boolean savedScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);

            GL13.glActiveTexture(GL13.GL_TEXTURE0 + Scene3DShaders.UNIT_INSTANCE_TBO_F);
            int savedTboF = GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_BUFFER);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + Scene3DShaders.UNIT_INSTANCE_TBO_I);
            int savedTboI = GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_BUFFER);

            try {
                int drawFbo = target.msaaFbo != 0 ? target.msaaFbo : target.fbo;
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, drawFbo);
                GL11.glViewport(0, 0, w, h);
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GL11.glDepthMask(true);

                int bg = world.clearColorArgb();
                float ba = ((bg >>> 24) & 0xFF) / 255.0f;
                float br = (((bg >>> 16) & 0xFF) / 255.0f) * ba;
                float bgc = (((bg >>> 8) & 0xFF) / 255.0f) * ba;
                float bb = ((bg & 0xFF) / 255.0f) * ba;
                GL11.glClearColor(br, bgc, bb, ba);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

                int drawCalls = 0;
                int instancesDrawn = 0;

                if (plan.instanceCount() > 0 && target.vao != 0) {
                    instanceDevice.beginFrame();
                    try {
                        world.packInstances(instanceBuffer);
                        instanceBuffer.flush();

                        instanceDevice.bindRgbaFloatView(Scene3DShaders.UNIT_INSTANCE_TBO_F);
                        instanceDevice.bindRgbaIntView(Scene3DShaders.UNIT_INSTANCE_TBO_I);

                        GL20.glUseProgram(program);
                        CameraSpec cam = world.camera();
                        GL20.glUniformMatrix4fv(uViewProj, false, cam.viewProjMatrix());
                        GL20.glUniform3f(uCameraPos, cam.eyeX(), cam.eyeY(), cam.eyeZ());
                        LightDesc light = world.light();
                        GL20.glUniform3f(uLightDir, light.dirX(), light.dirY(), light.dirZ());
                        GL20.glUniform3f(uLightColor,
                                light.lightR() * light.intensity(),
                                light.lightG() * light.intensity(),
                                light.lightB() * light.intensity());
                        GL20.glUniform3f(uAmbientColor, light.ambientR(), light.ambientG(), light.ambientB());

                        GL11.glEnable(GL11.GL_DEPTH_TEST);
                        GL11.glDepthFunc(GL11.GL_LESS);
                        GL11.glFrontFace(GL11.GL_CCW);
                        GL11.glCullFace(GL11.GL_BACK);
                        GL30.glBindVertexArray(target.vao);

                        GeometryPool geom = world.geometry();
                        for (DrawBatch3D batch : plan.batches()) {
                            MeshHandle mesh = geom.meshOrNull(batch.meshId());
                            if (mesh == null || batch.instanceCount() <= 0) continue;

                            if (batch.doubleSided()) {
                                GL11.glDisable(GL11.GL_CULL_FACE);
                            } else {
                                GL11.glEnable(GL11.GL_CULL_FACE);
                            }
                            if (batch.blendMode() == BlendMode3D.TRANSPARENT) {
                                GL11.glEnable(GL11.GL_BLEND);
                                GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
                                GL11.glDepthMask(false);
                            } else {
                                GL11.glDisable(GL11.GL_BLEND);
                                GL11.glDepthMask(true);
                            }

                            GL20.glUniform1i(uBaseInstance, batch.baseInstance());
                            long indexOffsetBytes = (long) mesh.firstIndex() * GeometryPool.INDEX_STRIDE_BYTES;
                            if (mesh.firstVertex() == 0) {
                                GL31.glDrawElementsInstanced(
                                        GL11.GL_TRIANGLES,
                                        mesh.indexCount(),
                                        GL11.GL_UNSIGNED_INT,
                                        indexOffsetBytes,
                                        batch.instanceCount());
                            } else {
                                GL32.glDrawElementsInstancedBaseVertex(
                                        GL11.GL_TRIANGLES,
                                        mesh.indexCount(),
                                        GL11.GL_UNSIGNED_INT,
                                        indexOffsetBytes,
                                        batch.instanceCount(),
                                        mesh.firstVertex());
                            }
                            drawCalls++;
                            instancesDrawn += batch.instanceCount();
                        }
                    } finally {
                        instanceDevice.endFrame();
                    }
                }

                if (target.msaaFbo != 0) {
                    GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target.msaaFbo);
                    GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, target.fbo);
                    GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h,
                            GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
                }

                lastDrawCalls = drawCalls;
                lastInstanceCount = instancesDrawn;
            } finally {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + Scene3DShaders.UNIT_INSTANCE_TBO_F);
                GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, savedTboF);
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + Scene3DShaders.UNIT_INSTANCE_TBO_I);
                GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, savedTboI);
                GL13.glActiveTexture(savedActiveTex);

                GL30.glBindVertexArray(savedVao);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, savedArrayBuf);
                GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, savedElementBuf);
                GL20.glUseProgram(savedProgram);

                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, savedDrawFbo.get(0));
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, savedReadFbo.get(0));
                GL11.glViewport(savedViewport.get(0), savedViewport.get(1), savedViewport.get(2), savedViewport.get(3));
                GL11.glClearColor(savedClearColor.get(0), savedClearColor.get(1), savedClearColor.get(2), savedClearColor.get(3));

                GL11.glDepthMask(savedDepthMask);
                if (savedDepthTest) GL11.glEnable(GL11.GL_DEPTH_TEST); else GL11.glDisable(GL11.GL_DEPTH_TEST);
                if (savedCullFace) GL11.glEnable(GL11.GL_CULL_FACE); else GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glCullFace(savedCullMode);
                GL11.glFrontFace(savedFrontFace);
                if (savedBlend) GL11.glEnable(GL11.GL_BLEND); else GL11.glDisable(GL11.GL_BLEND);
                if (savedScissor) GL11.glEnable(GL11.GL_SCISSOR_TEST); else GL11.glDisable(GL11.GL_SCISSOR_TEST);
            }
        }
        return target.colorTex;
    }

    /**
     * レンダリング済みの FBO カラーテクスチャからトップダウン順 ARGB ピクセルを読み出す（フォールバック・テスト用）。
     */
    public int[] readTexturePixelsArgb(int texId, int width, int height) {
        int[] out = new int[width * height];
        if (texId <= 0 || width <= 0 || height <= 0) return out;
        ByteBuffer buf = ByteBuffer.allocateDirect(width * height * 4);
        int savedTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int savedPack = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        try {
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, buf);
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, savedTex);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, savedPack);
        }
        IntBuffer ib = buf.order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        for (int row = 0; row < height; row++) {
            int srcRow = height - 1 - row;
            ib.position(srcRow * width);
            ib.get(out, row * width, width);
        }
        return out;
    }

    public PersistentVramBuffer instanceBuffer() { return instanceBuffer; }
    public GlVramDevice instanceDevice() { return instanceDevice; }
    public int fboReallocations() { return fboReallocations; }
    public int lastDrawCalls() { return lastDrawCalls; }
    public int lastInstanceCount() { return lastInstanceCount; }

    private void ensureFboTarget(WorldTarget t, int width, int height, int requestedSamples) {
        int maxSamples = GL11.glGetInteger(GL30.GL_MAX_SAMPLES);
        int samples = requestedSamples <= 1 ? 0 : Math.min(requestedSamples, Math.max(0, maxSamples));
        if (t.fbo != 0 && t.width == width && t.height == height && t.samples == samples) {
            return;
        }
        deleteFboOnly(t);
        t.width = width;
        t.height = height;
        t.samples = samples;

        int savedFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int savedTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int savedRbo = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
        try {
            t.fbo = GL30.glGenFramebuffers();
            t.colorTex = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, t.colorTex);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);

            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, t.fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, t.colorTex, 0);

            if (samples == 0) {
                t.depthRbo = GL30.glGenRenderbuffers();
                GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, t.depthRbo);
                GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH_COMPONENT24, width, height);
                GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_RENDERBUFFER, t.depthRbo);
            } else {
                t.msaaFbo = GL30.glGenFramebuffers();
                t.msaaColorRbo = GL30.glGenRenderbuffers();
                GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, t.msaaColorRbo);
                GL30.glRenderbufferStorageMultisample(GL30.GL_RENDERBUFFER, samples, GL11.GL_RGBA8, width, height);
                t.msaaDepthRbo = GL30.glGenRenderbuffers();
                GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, t.msaaDepthRbo);
                GL30.glRenderbufferStorageMultisample(GL30.GL_RENDERBUFFER, samples, GL30.GL_DEPTH_COMPONENT24, width, height);

                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, t.msaaFbo);
                GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_RENDERBUFFER, t.msaaColorRbo);
                GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_RENDERBUFFER, t.msaaDepthRbo);
            }
            fboReallocations++;
        } finally {
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, savedRbo);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, savedTex);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, savedFbo);
        }
    }

    private static void ensureGeometryUploaded(WorldTarget t, GeometryPool pool) {
        if (pool.totalVertices() == 0 || pool.totalIndices() == 0) return;
        if (t.lastPool == pool && t.uploadedGeomVersion == pool.version() && t.vao != 0) {
            return;
        }
        int savedVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int savedArrayBuf = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int savedElementBuf = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        try {
            if (t.vao == 0) t.vao = GL30.glGenVertexArrays();
            if (t.vbo == 0) t.vbo = GL15.glGenBuffers();
            if (t.ibo == 0) t.ibo = GL15.glGenBuffers();

            GL30.glBindVertexArray(t.vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, t.vbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, pool.vertexBufferSlice(), GL15.GL_STATIC_DRAW);

            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, t.ibo);
            GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, pool.indexBufferSlice(), GL15.GL_STATIC_DRAW);

            int stride = GeometryPool.VERTEX_STRIDE_BYTES;
            GL20.glEnableVertexAttribArray(Scene3DShaders.ATTR_POS);
            GL20.glVertexAttribPointer(Scene3DShaders.ATTR_POS, 3, GL11.GL_FLOAT, false, stride, 0L);
            GL20.glEnableVertexAttribArray(Scene3DShaders.ATTR_NORM);
            GL20.glVertexAttribPointer(Scene3DShaders.ATTR_NORM, 3, GL11.GL_FLOAT, false, stride, 12L);
            GL20.glEnableVertexAttribArray(Scene3DShaders.ATTR_UV);
            GL20.glVertexAttribPointer(Scene3DShaders.ATTR_UV, 2, GL11.GL_FLOAT, false, stride, 24L);
            GL20.glEnableVertexAttribArray(Scene3DShaders.ATTR_TANGENT);
            GL20.glVertexAttribPointer(Scene3DShaders.ATTR_TANGENT, 4, GL11.GL_FLOAT, false, stride, 32L);

            t.lastPool = pool;
            t.uploadedGeomVersion = pool.version();
        } finally {
            GL30.glBindVertexArray(savedVao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, savedArrayBuf);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, savedElementBuf);
        }
    }

    private static void deleteFboOnly(WorldTarget t) {
        if (t.msaaColorRbo != 0) { GL30.glDeleteRenderbuffers(t.msaaColorRbo); t.msaaColorRbo = 0; }
        if (t.msaaDepthRbo != 0) { GL30.glDeleteRenderbuffers(t.msaaDepthRbo); t.msaaDepthRbo = 0; }
        if (t.msaaFbo != 0) { GL30.glDeleteFramebuffers(t.msaaFbo); t.msaaFbo = 0; }
        if (t.depthRbo != 0) { GL30.glDeleteRenderbuffers(t.depthRbo); t.depthRbo = 0; }
        if (t.colorTex != 0) { GL11.glDeleteTextures(t.colorTex); t.colorTex = 0; }
        if (t.fbo != 0) { GL30.glDeleteFramebuffers(t.fbo); t.fbo = 0; }
    }

    private static void deleteTarget(WorldTarget t) {
        deleteFboOnly(t);
        if (t.ibo != 0) { GL15.glDeleteBuffers(t.ibo); t.ibo = 0; }
        if (t.vbo != 0) { GL15.glDeleteBuffers(t.vbo); t.vbo = 0; }
        if (t.vao != 0) { GL30.glDeleteVertexArrays(t.vao); t.vao = 0; }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        for (WorldTarget t : targets.values()) {
            deleteTarget(t);
        }
        targets.clear();
        if (instanceDevice != null) {
            instanceDevice.close();
            instanceDevice = null;
        }
        fenceRing.close();
        if (program != 0) {
            GL20.glDeleteProgram(program);
            program = 0;
        }
    }
}
