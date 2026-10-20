package com.nishiyu.enderframelib.server;

import com.nishiyu.enderframelib.EnderFrameLib;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.dom.MarkdownParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jetbrains.annotations.NotNull;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * データパック内の "data/<namespace>/enderframe_ui/<path>.html" (.htm / .md / .css) を読み込み、
 * コマンドやスクリプトから ID ("namespace:path") で呼び出せるようにするマネージャー
 */
public class EnderFrameDataPackManager extends SimplePreparableReloadListener<Map<ResourceLocation, String>> {

    private static final String FOLDER = "enderframe_ui";
    /** 生の ID（{@code enderframe_ui/foo.html}）と短縮 ID（{@code foo}）の両方をキーに持つ。 */
    private static final Map<ResourceLocation, String> LOADED_UIS = new ConcurrentHashMap<>();

    @Override
    protected @NotNull Map<ResourceLocation, String> prepare(@NotNull ResourceManager resourceManager, @NotNull ProfilerFiller profiler) {
        Map<ResourceLocation, String> map = new ConcurrentHashMap<>();
        Map<ResourceLocation, Resource> resources = resourceManager.listResources(FOLDER,
                loc -> loc.getPath().endsWith(".html")
                        || loc.getPath().endsWith(".htm")
                        || loc.getPath().endsWith(".md")
                        || loc.getPath().endsWith(".css"));

        for (Map.Entry<ResourceLocation, Resource> entry : resources.entrySet()) {
            ResourceLocation rawLoc = entry.getKey();
            try (InputStream is = entry.getValue().open()) {
                String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                String cleanPath = rawLoc.getPath();

                if (cleanPath.endsWith(".md")) {
                    content = """
                            <html>
                            <head><link rel="stylesheet" href="enderframelib:dark.css"></head>
                            <body style="padding: 16px;">
                                <article class="mc-panel markdown-body" style="max-width: 780px; margin: 0 auto;">
                            """
                            + MarkdownParser.toHtml(content)
                            + """
                                </article>
                            </body>
                            </html>
                            """;
                }

                map.put(rawLoc, content);

                // "enderframe_ui/foo.html" -> "namespace:foo" でも引けるように正規化登録
                if (cleanPath.startsWith(FOLDER + "/")) {
                    cleanPath = cleanPath.substring(FOLDER.length() + 1);
                }
                if (cleanPath.endsWith(".html")) {
                    cleanPath = cleanPath.substring(0, cleanPath.length() - 5);
                } else if (cleanPath.endsWith(".htm")) {
                    cleanPath = cleanPath.substring(0, cleanPath.length() - 4);
                } else if (cleanPath.endsWith(".md")) {
                    cleanPath = cleanPath.substring(0, cleanPath.length() - 3);
                }
                ResourceLocation shortId = ResourceLocation.fromNamespaceAndPath(rawLoc.getNamespace(), cleanPath);
                map.put(shortId, content);
            } catch (Exception e) {
                EnderFrameLib.LOGGER.error("Failed to load EnderFrame UI datapack resource: {}", rawLoc, e);
            }
        }
        return map;
    }

    @Override
    protected void apply(@NotNull Map<ResourceLocation, String> prepared, @NotNull ResourceManager resourceManager, @NotNull ProfilerFiller profiler) {
        LOADED_UIS.clear();
        LOADED_UIS.putAll(prepared);
        // common層のUiDocumentCompilerにデータパックリソースの解決処理を注入する
        UiDocumentCompiler.datapackResourceFallback = EnderFrameDataPackManager::getHtml;
        EnderFrameLib.LOGGER.info("Loaded {} EnderFrame UI entries from datapacks.", entryCount());
    }

    /** 読み込んだファイル数（生 ID の数）。 */
    static int entryCount() {
        return (int) LOADED_UIS.keySet().stream()
                .filter(loc -> loc.getPath().startsWith(FOLDER + "/"))
                .count();
    }

    /** サーバー停止時に読み込み済みリソースを捨てる（I-6）。 */
    static void clearAll() {
        LOADED_UIS.clear();
    }

    public static String getHtml(ResourceLocation id) {
        if (id == null) return null;
        return LOADED_UIS.get(id);
    }

    public static String getHtml(String idStr) {
        if (idStr == null || idStr.isEmpty()) return null;
        try {
            return LOADED_UIS.get(ResourceLocation.parse(idStr.trim()));
        } catch (Exception e) {
            return null;
        }
    }

    public static Collection<ResourceLocation> getAvailableShortIds() {
        return LOADED_UIS.keySet().stream()
                .filter(loc -> !loc.getPath().startsWith(FOLDER + "/") && !loc.getPath().endsWith(".css"))
                .toList();
    }
}