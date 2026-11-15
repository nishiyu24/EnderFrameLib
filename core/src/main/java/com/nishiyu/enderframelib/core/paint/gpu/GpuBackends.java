package com.nishiyu.enderframelib.core.paint.gpu;

import com.nishiyu.enderframelib.core.paint.RenderBackend;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;

/** クラスパスにある {@link GpuBackendProvider} から、要求に合う描画先を作る。 */
public final class GpuBackends {
    private GpuBackends() {}

    /** 作った描画先と、それを作った実装。 */
    public record Created(RenderBackend backend, GpuBackendProvider provider) {}

    /** 見つかった実装（優先度順）。 */
    public static List<GpuBackendProvider> providers() {
        return providers(ServiceLoader.load(GpuBackendProvider.class, GpuBackendProvider.class.getClassLoader()));
    }

    static List<GpuBackendProvider> providers(Iterable<GpuBackendProvider> source) {
        List<GpuBackendProvider> out = new ArrayList<>();
        for (GpuBackendProvider p : source) out.add(p);
        out.sort(Comparator.comparingInt(GpuBackendProvider::priority).thenComparing(GpuBackendProvider::id));
        return out;
    }

    /**
     * @param api {@code null} は自動（優先度順に、使える最初のもの）。指定したときはその API だけを試す
     * @throws IllegalStateException 実装が無い・全て作れなかった（原因は suppressed に積む）
     */
    public static Created create(GpuApiKind api, GpuBackendProvider.Request request) {
        return create(api, request, providers());
    }

    static Created create(GpuApiKind api, GpuBackendProvider.Request request, List<GpuBackendProvider> candidates) {
        IllegalStateException failure = null;
        boolean anyForApi = false;
        for (GpuBackendProvider p : candidates) {
            if (api != null && p.api() != api) continue;
            anyForApi = true;
            if (!p.available()) continue;
            try {
                return new Created(p.create(request), p);
            } catch (RuntimeException | LinkageError e) {
                if (failure == null) failure = new IllegalStateException("GPU 描画先を作れませんでした: " + p.id(), e);
                else failure.addSuppressed(e);
            }
        }
        if (failure != null) throw failure;
        throw new IllegalStateException(anyForApi
                ? "GPU 実装は見つかりましたが、この環境では使えません（" + (api == null ? "AUTO" : api) + "）"
                : "GPU 実装がクラスパスにありません（" + (api == null ? "core-gl か core-vulkan" : api == GpuApiKind.GL ? "core-gl" : "core-vulkan") + " を足してください）");
    }
}
