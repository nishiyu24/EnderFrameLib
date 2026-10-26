package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.core.raster.svg.SvgImage;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 画像としての SVG（{@code <img src="*.svg">}・{@code background-image: url(*.svg)}）の取得元。
 * {@code data:} は同期、リソースパック（{@code ns:path/icon.svg} → {@code assets/ns/path/icon.svg}）は初回に同期で読んで覚え、
 * {@code http(s)} は {@link WebMediaManager} が非同期に取る（届くまでは null で、届いたら固有サイズの登録でレイアウトし直す）。
 */
final class SvgSources {

    private static final Object MISSING = new Object();
    private static final Map<String, Object> RESOURCES = Collections.synchronizedMap(new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Object> eldest) {
            return size() > 128;
        }
    });

    private SvgSources() {}

    /** 読めていれば SVG。読み込み中・失敗なら null。 */
    static SvgImage get(String src) {
        if (src == null || src.isEmpty()) return null;
        if (SvgImage.isSvgDataUri(src)) return SvgImage.cachedFromDataUri(src);
        if (src.startsWith("http://") || src.startsWith("https://")) return WebMediaManager.getSvg(src);
        return resource(src);
    }

    /** 固有サイズ {@code [w, h]}（CSS px）。まだ読めていなければ null。リソースは読んでからサイズを登録する。 */
    static int[] intrinsicSize(String src) {
        SvgImage svg = get(src);
        if (svg == null) return null;
        return new int[]{(int) Math.max(1, Math.round(svg.width())), (int) Math.max(1, Math.round(svg.height()))};
    }

    private static SvgImage resource(String src) {
        Object cached = RESOURCES.get(src);
        if (cached == MISSING) return null;
        if (cached instanceof SvgImage svg) return svg;
        SvgImage loaded = null;
        try {
            ResourceLocation loc = ResourceLocation.tryParse(src);
            if (loc != null) {
                var res = Minecraft.getInstance().getResourceManager().getResource(loc);
                if (res.isPresent()) {
                    try (InputStream in = res.get().open()) {
                        byte[] bytes = in.readNBytes(SvgImage.MAX_BYTES + 1);
                        if (bytes.length <= SvgImage.MAX_BYTES) {
                            loaded = SvgImage.parse(new String(bytes, StandardCharsets.UTF_8));
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // 読めない資産は無いものとして扱う
        }
        RESOURCES.put(src, loaded == null ? MISSING : loaded);
        return loaded;
    }

    static void clearResources() {
        RESOURCES.clear();
    }
}
