package com.nishiyu.enderframelib.core.paint.vulkan;

import com.nishiyu.enderframelib.core.paint.RenderBackend;
import com.nishiyu.enderframelib.core.paint.gpu.GpuApiKind;
import com.nishiyu.enderframelib.core.paint.gpu.GpuBackendProvider;

/** Vulkan 1.1 の {@link GpuBackendProvider}（{@code META-INF/services} に登録）。 */
public final class VkBackendProvider implements GpuBackendProvider {
    @Override public String id() { return "vulkan"; }
    @Override public GpuApiKind api() { return GpuApiKind.VULKAN; }
    @Override public int priority() { return 20; }

    @Override
    public boolean available() {
        try {
            Class.forName("org.lwjgl.vulkan.VK", false, VkBackendProvider.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public RenderBackend create(Request r) {
        if (r.hostWindow() != 0) throw new UnsupportedOperationException("Vulkan はホストの GL ウィンドウを共有できません");
        VkRenderBackend b = new VkRenderBackend(r.widthCss(), r.heightCss(), r.devicePixelRatio());
        b.fonts(r.fonts());
        b.images(r.images());
        b.scene3dWorlds(r.scene3dWorlds());
        return b;
    }

    @Override
    public long colorTexture(RenderBackend backend) {
        return ((VkRenderBackend) backend).colorTexture();
    }
}
