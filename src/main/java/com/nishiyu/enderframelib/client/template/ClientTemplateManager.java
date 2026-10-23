package com.nishiyu.enderframelib.client.template;

import com.nishiyu.enderframelib.EnderFrameLib;
import com.nishiyu.enderframelib.client.gui.AbstractEnderScreen;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.DocumentLoader;
import com.nishiyu.enderframelib.core.css.StyleSheet;
import com.nishiyu.enderframelib.core.dom.node.HtmlDocument;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.Ownership;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * クライアント側 HTML/CSS テンプレートマネージャー。
 *
 * <p>Headless Server State アーキテクチャに基づき、サーバーからは DOM 構造を送らず、
 * クライアント側でアセット（{@code assets/<namespace>/enderframe_ui/<path>.html}）やデータパック、
 * クラスパスから事前にパースしたテンプレートをキャッシュ管理します。</p>
 */
public final class ClientTemplateManager {

    private static final Map<String, ParsedTemplate> CACHE = new ConcurrentHashMap<>();

    private ClientTemplateManager() {}

    /**
     * 指定された templateId のテンプレートを取得します。
     * キャッシュにない場合は自動的にリソースから読み込んでパース・キャッシュします。
     *
     * @param templateId テンプレート識別子（例: {@code "mymod:crusher"}）
     * @return パース済みテンプレート、見つからない場合は null
     */
    @Nullable
    public static ParsedTemplate getTemplate(@NotNull String templateId) {
        Objects.requireNonNull(templateId, "templateId cannot be null");
        return CACHE.computeIfAbsent(templateId, ClientTemplateManager::loadTemplate);
    }

    /**
     * テスト用または動的 UI 用に、パース済みテンプレートを直接登録します。
     *
     * @param templateId テンプレート識別子
     * @param rawHtml    HTML 文字列
     * @return 登録されたパース済みテンプレート
     */
    @NotNull
    public static ParsedTemplate registerTemplate(@NotNull String templateId, @NotNull String rawHtml) {
        Objects.requireNonNull(templateId, "templateId cannot be null");
        Objects.requireNonNull(rawHtml, "rawHtml cannot be null");

        Document doc = DocumentLoader.parseDocument(rawHtml, "", 854, 480);
        HtmlDocument htmlDoc;
        if (doc.root instanceof HtmlDocument d) {
            htmlDoc = d;
        } else {
            htmlDoc = new HtmlDocument();
            htmlDoc.appendChild(doc.root);
        }
        htmlDoc.setDefaultOwnership(Ownership.CLIENT);

        ParsedTemplate template = new ParsedTemplate(templateId, rawHtml, htmlDoc, doc.authorCss);
        CACHE.put(templateId, template);
        return template;
    }

    /**
     * 指定されたテンプレートがキャッシュに存在するかどうかを返します。
     */
    public static boolean hasTemplate(@NotNull String templateId) {
        return CACHE.containsKey(templateId);
    }

    /**
     * 指定されたテンプレートをキャッシュから削除します。
     */
    @Nullable
    public static ParsedTemplate removeTemplate(@NotNull String templateId) {
        return CACHE.remove(templateId);
    }

    /**
     * テンプレートキャッシュを全クリアします。リソースリロード時などに呼び出します。
     */
    public static void clearCache() {
        CACHE.clear();
    }

    /**
     * キャッシュされているテンプレートの数を返します。
     */
    public static int cachedCount() {
        return CACHE.size();
    }

    @Nullable
    private static ParsedTemplate loadTemplate(String templateId) {
        String html = resolveTemplateHtml(templateId);
        if (html == null || html.isEmpty()) {
            EnderFrameLib.LOGGER.warn("Template not found for templateId: {}", templateId);
            return null;
        }

        Document doc = DocumentLoader.parseDocument(html, "", 854, 480);
        HtmlDocument htmlDoc;
        if (doc.root instanceof HtmlDocument d) {
            htmlDoc = d;
        } else {
            htmlDoc = new HtmlDocument();
            htmlDoc.appendChild(doc.root);
        }
        htmlDoc.setDefaultOwnership(Ownership.CLIENT);

        return new ParsedTemplate(templateId, html, htmlDoc, doc.authorCss);
    }

    /**
     * templateId から HTML 文字列を解決します。
     */
    @Nullable
    public static String resolveTemplateHtml(@NotNull String templateId) {
        List<String> candidates = buildCandidateResourcePaths(templateId);
        for (String candidate : candidates) {
            String html = tryReadHtml(candidate);
            if (html != null && !html.isEmpty()) {
                return html;
            }
        }
        return null;
    }

    @NotNull
    private static List<String> buildCandidateResourcePaths(@NotNull String templateId) {
        List<String> candidates = new ArrayList<>();
        String raw = templateId.trim();

        String namespace;
        String path;
        int colonIdx = raw.indexOf(':');
        if (colonIdx >= 0) {
            namespace = raw.substring(0, colonIdx);
            path = raw.substring(colonIdx + 1);
        } else {
            namespace = "enderframelib";
            path = raw;
        }

        boolean hasHtmlSuffix = path.endsWith(".html");
        String htmlPath = hasHtmlSuffix ? path : path + ".html";

        // 候補1: namespace:enderframe_ui/<path>.html
        if (!path.startsWith("enderframe_ui/")) {
            candidates.add(namespace + ":enderframe_ui/" + htmlPath);
        }
        // 候補2: namespace:<path>.html
        candidates.add(namespace + ":" + htmlPath);
        // 候補3: namespace:<path>
        if (hasHtmlSuffix) {
            candidates.add(namespace + ":" + path);
        }
        // 候補4: そのまま
        candidates.add(raw);

        return candidates;
    }

    @Nullable
    private static String tryReadHtml(@NotNull String resourceLocationStr) {
        ResourceLocation loc = ResourceLocation.tryParse(resourceLocationStr);

        // 1. AbstractEnderScreen のリソースファインダー（Minecraft ResourceManager / assets fallback）
        if (loc != null) {
            Optional<String> res = AbstractEnderScreen.findHtmlResource(loc);
            if (res.isPresent() && !res.get().isEmpty()) {
                return res.get();
            }
        }

        // 2. DocumentLoader のリソース解決
        Function<String, String> resolver = DocumentLoader.effectiveResourceTextResolver();
        if (resolver != null) {
            String resolved = resolver.apply(resourceLocationStr);
            if (resolved != null && !resolved.isEmpty()) {
                return resolved;
            }
        }

        // 3. クラスパスフォールバック
        if (loc != null) {
            String cpAsset = readClasspathString("/assets/" + loc.getNamespace() + "/" + loc.getPath());
            if (cpAsset != null) return cpAsset;

            String cpData = readClasspathString("/data/" + loc.getNamespace() + "/" + loc.getPath());
            if (cpData != null) return cpData;
        }

        return null;
    }

    @Nullable
    private static String readClasspathString(@NotNull String path) {
        try (InputStream is = ClientTemplateManager.class.getResourceAsStream(path)) {
            if (is != null) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {}
        return null;
    }
}
