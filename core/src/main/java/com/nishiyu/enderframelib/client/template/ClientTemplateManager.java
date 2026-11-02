package com.nishiyu.enderframelib.client.template;

import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.DocumentLoader;
import com.nishiyu.enderframelib.core.dom.node.HtmlDocument;
import com.nishiyu.enderframelib.core.dom.node.Ownership;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
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

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientTemplateManager.class);
    private static final Map<String, ParsedTemplate> CACHE = new ConcurrentHashMap<>();
    /** {@code namespace:path} の形式（Minecraft の {@code ResourceLocation} と同じ文字種）。 */
    private static final Pattern RESOURCE_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    /** テンプレートの取得元。{@code "namespace:path"} を受け取り、HTML を返す（無ければ null）。先に登録したものから試す。 */
    private static final List<Function<String, String>> SOURCES = new CopyOnWriteArrayList<>();

    private ClientTemplateManager() {}

    /**
     * テンプレートの取得元を足します。Minecraft 上ではリソースマネージャー、窓ではファイルなどを渡します。
     * 取得元は登録順に試され、クラスパス（{@code /assets/}・{@code /data/}）は最後の手段です。
     *
     * @param source {@code "namespace:path"} を受け取り HTML を返す（見つからなければ null か空）。例外は無視される
     */
    public static void addSource(@NotNull Function<String, String> source) {
        SOURCES.add(Objects.requireNonNull(source, "source cannot be null"));
    }

    /** 登録済みの取得元をすべて外します（テスト用）。 */
    public static void clearSources() {
        SOURCES.clear();
    }

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
            LOGGER.warn("Template not found for templateId: {}", templateId);
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
        boolean valid = RESOURCE_ID.matcher(resourceLocationStr).matches();

        // 1. 登録された取得元（Minecraft の ResourceManager など）
        if (valid) {
            for (Function<String, String> source : SOURCES) {
                try {
                    String res = source.apply(resourceLocationStr);
                    if (res != null && !res.isEmpty()) return res;
                } catch (Exception e) {
                    LOGGER.debug("Template source failed for {}: {}", resourceLocationStr, e.getMessage());
                }
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
        if (valid) {
            int colon = resourceLocationStr.indexOf(':');
            String ns = resourceLocationStr.substring(0, colon), path = resourceLocationStr.substring(colon + 1);
            String cpAsset = readClasspathString("/assets/" + ns + "/" + path);
            if (cpAsset != null) return cpAsset;

            String cpData = readClasspathString("/data/" + ns + "/" + path);
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
