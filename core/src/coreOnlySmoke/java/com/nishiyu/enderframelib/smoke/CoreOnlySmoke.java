package com.nishiyu.enderframelib.smoke;

import com.nishiyu.enderframelib.api.EnderEngine;
import com.nishiyu.enderframelib.api.GpuApi;
import com.nishiyu.enderframelib.api.WebViewSurface;

/** G4: {@code :core} だけで動くことの確認。失敗したら例外で終了コードが非 0 になる。 */
public final class CoreOnlySmoke {
    private CoreOnlySmoke() {}

    public static void main(String[] args) throws Exception {
        for (String leaked : new String[]{"org.lwjgl.opengl.GL", "org.lwjgl.glfw.GLFW", "org.lwjgl.vulkan.VK"}) {
            try {
                Class.forName(leaked, false, CoreOnlySmoke.class.getClassLoader());
                throw new AssertionError(leaked + " が :core のクラスパスにある");
            } catch (ClassNotFoundException expected) {
                // 無いのが正しい
            }
        }
        try (WebViewSurface s = EnderEngine.html("<body style=\"background:#336699\"><p>core only</p></body>").size(64, 48).build()) {
            s.frame(0, true);
            if (s.texture().isPresent()) throw new AssertionError("SOFTWARE の面にテクスチャがある");
        }
        expectFailure("GPU + GL", "core-gl", () -> EnderEngine.html("<p>x</p>").size(8, 8)
                .target(EnderEngine.Target.GPU).gpu(GpuApi.GL).build());
        expectFailure("GPU + VULKAN", "core-vulkan", () -> EnderEngine.html("<p>x</p>").size(8, 8)
                .target(EnderEngine.Target.GPU).gpu(GpuApi.VULKAN).build());
        expectFailure("GPU + AUTO", "core-gl", () -> EnderEngine.html("<p>x</p>").size(8, 8)
                .target(EnderEngine.Target.GPU).build());
        expectFailure("openWindow", "core-glfw", () -> EnderEngine.html("<p>x</p>").size(8, 8).openWindow());
        System.out.println("CORE-ONLY-SMOKE ok");
    }

    private static void expectFailure(String what, String mentions, Runnable r) {
        try {
            r.run();
        } catch (IllegalStateException e) {
            if (!String.valueOf(e.getMessage()).contains(mentions)) {
                throw new AssertionError(what + ": 例外の文言に " + mentions + " が無い: " + e.getMessage());
            }
            return;
        }
        throw new AssertionError(what + ": 失敗するはずが成功した");
    }
}
