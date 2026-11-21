package com.nishiyu.enderframelib.core.paint.gl;

import com.nishiyu.enderframelib.core.paint.RenderBackend;
import com.nishiyu.enderframelib.core.paint.gpu.GpuApiKind;
import com.nishiyu.enderframelib.core.paint.gpu.GpuBackendProvider;

/** OpenGL 3.2 Core の {@link GpuBackendProvider}（{@code META-INF/services} に登録）。 */
public final class GlBackendProvider implements GpuBackendProvider {
    @Override public String id() { return "gl"; }
    @Override public GpuApiKind api() { return GpuApiKind.GL; }
    @Override public int priority() { return 10; }

    @Override
    public boolean available() {
        try {
            Class.forName("org.lwjgl.opengl.GL", false, GlBackendProvider.class.getClassLoader());
            Class.forName("org.lwjgl.glfw.GLFW", false, GlBackendProvider.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public RenderBackend create(Request r) {
        if (r.host() != null && r.host().api() != GpuApiKind.GL) {
            throw new IllegalArgumentException("GL 実装へ " + r.host().api() + " のホストデバイスは渡せない");
        }
        LwjglGlRenderBackend b = r.host() != null
                ? LwjglGlRenderBackend.sharedWith(r.host().window(), r.widthCss(), r.heightCss(), r.devicePixelRatio())
                : new LwjglGlRenderBackend(r.widthCss(), r.heightCss(), r.devicePixelRatio());
        return b.fonts(r.fonts()).images(r.images()).scene3dWorlds(r.scene3dWorlds());
    }

    @Override
    public long colorTexture(RenderBackend backend) {
        return ((LwjglGlRenderBackend) backend).colorTexture();
    }
}
