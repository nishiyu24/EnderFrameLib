package com.nishiyu.enderframelib.core.raster.svg;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 画像としての SVG（{@code <img src="*.svg">}・{@code background-image: url(*.svg)}）の取得元。
 * {@code data:} は同期、リソース（{@code ns:path/icon.svg}）は {@link #setResourceReader} で差し込まれた読み手から初回に同期で読んで覚え、
 * {@code http(s)} は {@link #setHttpSource} の非同期な取得元に任せる（届くまでは null）。
 * Minecraft の ResourceManager や WebMediaManager への依存を持たないので、窓でもそのまま使える。
 */
public final class SvgSources {

    private static final Object MISSING = new Object();
    private static final Map<String, Object> RESOURCES = Collections.synchronizedMap(new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Object> eldest) {
            return size() > 128;
        }
    });

    /** リソース名 → バイト列（無ければ null。読めなければ IOException）。 */
    @FunctionalInterface
    public interface ResourceReader {
        byte[] read(String name, int maxBytes) throws IOException;
    }

    private static volatile ResourceReader resourceReader = (n, max) -> null;
    private static volatile Function<String, SvgImage> httpSource = u -> null;

    private SvgSources() {}

    public static void setResourceReader(ResourceReader reader) {
        resourceReader = reader == null ? (n, max) -> null : reader;
        RESOURCES.clear();
    }

    public static void setHttpSource(Function<String, SvgImage> source) {
        httpSource = source == null ? u -> null : source;
    }

    /** 読めていれば SVG。読み込み中・失敗なら null。 */
    public static SvgImage get(String src) {
        if (src == null || src.isEmpty()) return null;
        if (SvgImage.isSvgDataUri(src)) return SvgImage.cachedFromDataUri(src);
        if (src.startsWith("http://") || src.startsWith("https://")) return httpSource.apply(src);
        return resource(src);
    }

    /** 固有サイズ {@code [w, h]}（CSS px）。まだ読めていなければ null。リソースは読んでからサイズを登録する。 */
    public static int[] intrinsicSize(String src) {
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
            byte[] bytes = resourceReader.read(src, SvgImage.MAX_BYTES + 1);
            if (bytes != null && bytes.length <= SvgImage.MAX_BYTES) {
                loaded = SvgImage.parse(new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException ignored) {
            // 読めない資産は無いものとして扱う
        }
        RESOURCES.put(src, loaded == null ? MISSING : loaded);
        return loaded;
    }

    public static void clearResources() {
        RESOURCES.clear();
    }
}
