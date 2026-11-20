package com.nishiyu.enderframelib.core.raster.svg;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 画像としての SVG（{@code <img src="*.svg">}・{@code background-image: url(*.svg)}）の取得元。
 * {@code data:} は同期、リソース（{@code ns:path/icon.svg}）は 文脈（{@code HostServices.svgResourceReader}）の読み手から初回に同期で読んで覚え、
 * {@code http(s)} は 文脈（{@code HostServices.svgHttpSource}）の非同期な取得元に任せる（届くまでは null）。
 * Minecraft の ResourceManager や WebMediaManager への依存を持たないので、窓でもそのまま使える。
 */
public final class SvgSources {

    private static final Object MISSING = new Object();
    /** 読み手ごとの覚え（面ごとに読み手が違っても、別の面の読み込み結果を見ない。読み手が消えれば覚えも消える）。 */
    private static final Map<Object, Map<String, Object>> CACHES = Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static Map<String, Object> cacheFor(ResourceReader reader) {
        return CACHES.computeIfAbsent(reader, k -> Collections.synchronizedMap(new LinkedHashMap<>(32, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Object> eldest) {
                return size() > 128;
            }
        }));
    }

    /** 文脈が差し込む取得元（A13。以前は static の差し込み口 2 個）。各値は null なら「無い」。 */
    public interface Source {
        ResourceReader svgResourceReader();

        Function<String, SvgImage> svgHttpSource();
    }

    private static final ResourceReader NO_READER = (n, max) -> null;
    private static final Source NONE = new Source() {
        @Override public ResourceReader svgResourceReader() { return null; }
        @Override public Function<String, SvgImage> svgHttpSource() { return null; }
    };
    private static volatile java.util.function.Supplier<Source> source = () -> NONE;

    /** 「このスレッドの文脈の値」を引く口を結ぶ（{@code EngineContext} が 1 回だけ呼ぶ）。 */
    public static void bindSource(java.util.function.Supplier<Source> contextSource) {
        source = contextSource == null ? () -> NONE : contextSource;
    }

    /** リソース名 → バイト列（無ければ null。読めなければ IOException）。 */
    @FunctionalInterface
    public interface ResourceReader {
        byte[] read(String name, int maxBytes) throws IOException;
    }

    private SvgSources() {}

    /** 読めていれば SVG。読み込み中・失敗なら null。 */
    public static SvgImage get(String src) {
        if (src == null || src.isEmpty()) return null;
        if (SvgImage.isSvgDataUri(src)) return SvgImage.cachedFromDataUri(src);
        if (src.startsWith("http://") || src.startsWith("https://")) {
            Function<String, SvgImage> h = source.get().svgHttpSource();
            return h == null ? null : h.apply(src);
        }
        return resource(src);
    }

    /** 固有サイズ {@code [w, h]}（CSS px）。まだ読めていなければ null。リソースは読んでからサイズを登録する。 */
    public static int[] intrinsicSize(String src) {
        SvgImage svg = get(src);
        if (svg == null) return null;
        return new int[]{(int) Math.max(1, Math.round(svg.width())), (int) Math.max(1, Math.round(svg.height()))};
    }

    private static SvgImage resource(String src) {
        ResourceReader reader = source.get().svgResourceReader();
        if (reader == null) reader = NO_READER;
        Map<String, Object> resources = cacheFor(reader);
        Object cached = resources.get(src);
        if (cached == MISSING) return null;
        if (cached instanceof SvgImage svg) return svg;
        SvgImage loaded = null;
        try {
            byte[] bytes = reader.read(src, SvgImage.MAX_BYTES + 1);
            if (bytes != null && bytes.length <= SvgImage.MAX_BYTES) {
                loaded = SvgImage.parse(new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException ignored) {
            // 読めない資産は無いものとして扱う
        }
        resources.put(src, loaded == null ? MISSING : loaded);
        return loaded;
    }

    /** 覚えている読み込み結果を全面ぶん捨てる。 */
    public static void clearResources() {
        CACHES.clear();
    }
}
