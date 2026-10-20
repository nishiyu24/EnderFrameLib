package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssParser;
import com.nishiyu.enderframelib.core.css.StyleResolver;
import com.nishiyu.enderframelib.core.css.Selector.ElementState;
import com.nishiyu.enderframelib.core.css.SelectorMatcher;
import com.nishiyu.enderframelib.core.css.StyleResolver.PseudoType;
import com.nishiyu.enderframelib.core.css.StyleResolver.StyleSet;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.dom.HtmlNode.NodeType;
import com.nishiyu.enderframelib.core.dom.HtmlParser;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutContext.FormattingContext;
import com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutContext.IntrinsicSizeResolver;
import com.nishiyu.enderframelib.core.layout.BoxTreeBuilder;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutContext;
import com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutContext.ScrollState;
import com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutContext.Viewport;
import com.nishiyu.enderframelib.core.dom.MarkdownParser;
import com.nishiyu.enderframelib.core.layout.DisplayItem;
import com.nishiyu.enderframelib.core.layout.TextShaper.FontFace.FontResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;

@org.jetbrains.annotations.ApiStatus.Internal
public class UiDocumentCompiler {

    private static final Logger LOGGER = LoggerFactory.getLogger(UiDocumentCompiler.class);

    private static final Pattern INCLUDE_PATTERN = Pattern.compile("(?is)<include\\b[^>]*\\bsrc=[\"']([^\"']+)[\"'][^>]*(?:/>|>\\s*</include>)");

    @FunctionalInterface
    public interface ILayoutBoxRenderer {
        List<DisplayItem> render(LayoutBox rootBox, int rootW, int rootH,
                               Map<String, com.nishiyu.enderframelib.core.css.StyleSheet.Keyframes> keyframes,
                               ScrollState scrollState);
    }

    /**
     * 測定パスの回数。
     * 1 回目で子の内容幅が分かり、2 回目でそれを踏まえた親の幅が子に伝わる。
     * 3 回目以降で変わるケースは現状確認されていないため 2 で固定している。
     */
    private static final int LAYOUT_PASSES = 2;

    public static ILayoutBoxRenderer clientBoxRenderer = null;
    public static Function<String, String> clientResourceFallback = null;

    /** サーバー（データパック）側リソースを解決するための依存性注入用フック */
    public static Function<String, String> datapackResourceFallback = null;

    /**
     * 置換要素（リモート画像など）の固有サイズ解決。
     * core 側に public static な可変フックを置かず、ここから LayoutContext に載せて渡す。
     */
    public static IntrinsicSizeResolver intrinsicSizeResolver = null;

    /**
     * innerHTML 相当のフラグメント解析（引数は HTML と文脈の要素のタグ）。
     * Markdown は文字列段階、カスタムタグはパース後の DOM 段階で展開する。
     */
    public static final BiFunction<String, String, HtmlNode> INNER_HTML_PARSER = (html, contextTag) -> {
        HtmlNode root = HtmlParser.parseFragment(MarkdownParser.processDocumentOrBlocks(html), contextTag);
        CustomTagExpander.expand(root);
        return root;
    };

    public static Function<String, String> resourceTextResolver = path -> {
        if (path == null) return "";
        String clean = path.trim();
        String builtIn = resolveBuiltInTheme(clean);
        if (builtIn != null) return builtIn;

        if (datapackResourceFallback != null) {
            String fromDatapack = datapackResourceFallback.apply(clean);
            if (fromDatapack != null && !fromDatapack.isEmpty()) return fromDatapack;
        }

        if (clientResourceFallback != null) {
            String fromClient = clientResourceFallback.apply(clean);
            if (fromClient != null && !fromClient.isEmpty()) return fromClient;
        }

        if (clean.contains(":")) {
            String[] sp = clean.split(":", 2);
            String namespace = sanitizeNamespace(sp[0]);
            String resourcePath = sanitizeResourcePath(sp[1]);
            // クラスパスの読み出しなので、".." や "/" 始まりを通すと jar 外（他 Mod のリソース）に出られる
            if (namespace == null || resourcePath == null) {
                LOGGER.warn("不正なリソースパスを拒否しました: {}", clean);
                return "";
            }
            String fromClasspath = readClasspath("/assets/" + namespace + "/" + resourcePath);
            if (fromClasspath != null) return fromClasspath;
            fromClasspath = readClasspath("/data/" + namespace + "/enderframe_ui/" + resourcePath);
            if (fromClasspath != null) return fromClasspath;
        }
        return "";
    };

    private static final Pattern SAFE_NAMESPACE = Pattern.compile("[a-z0-9_.-]{1,64}");
    private static final Pattern SAFE_RESOURCE_PATH = Pattern.compile("[a-zA-Z0-9_./-]{1,256}");

    /** リソース名前空間の検証。Minecraft の名前空間と同じ文字種だけを許す。 */
    private static String sanitizeNamespace(String raw) {
        if (raw == null) return null;
        String value = raw.trim().toLowerCase();
        return SAFE_NAMESPACE.matcher(value).matches() ? value : null;
    }

    /**
     * クラスパス相対パスの正規化（I-9）。
     * 絶対パス・親ディレクトリ参照・バックスラッシュ・空セグメントを一切通さない。
     */
    private static String sanitizeResourcePath(String raw) {
        if (raw == null) return null;
        String value = raw.trim().replace('\\', '/');
        while (value.startsWith("/")) value = value.substring(1);
        if (value.isEmpty() || !SAFE_RESOURCE_PATH.matcher(value).matches()) return null;
        for (String segment : value.split("/")) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) return null;
        }
        return value;
    }

    private static String readClasspath(String absolutePath) {
        try (InputStream is = UiDocumentCompiler.class.getResourceAsStream(absolutePath)) {
            if (is != null) return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("リソースの読み込みに失敗しました: {} ({})", absolutePath, e.getMessage());
        }
        return null;
    }

    public UiDocumentCompiler() {}

    /** 組み込みテーマ名 -> href の末尾として受け付ける形。 */
    private static final String[] BUILT_IN_THEMES = {"dark", "vanilla", "brass"};

    /**
     * 組み込みテーマの href を解決する。
     * CSS 本体は resources/assets/enderframelib/css/theme/*.css が単一の出所。
     */
    public static String resolveBuiltInTheme(String href) {
        if (href == null) return null;
        String lower = href.toLowerCase().trim();
        for (String name : BUILT_IN_THEMES) {
            if (lower.endsWith("theme/" + name + ".css")
                    || lower.endsWith("themes/" + name + ".css")
                    || lower.equals("enderframelib:" + name + ".css")) {
                String css = CssResources.load(CssResources.themePath(name));
                return css.isEmpty() ? null : css;
            }
        }
        return null;
    }

    /**
     * UA スタイルシート。本体は resources/assets/enderframelib/css/ua.css。
     * 以前は約 60 行の CSS が Java の文字列リテラルとして埋め込まれていた。
     */
    public static String userAgentCss() {
        return CssResources.load(CssResources.USER_AGENT);
    }

    public com.nishiyu.enderframelib.core.css.StyleSheet buildStyleSheet(String authorCss, int rootW) {
        return buildStyleSheet(authorCss, rootW, 0);
    }

    /**
     * UA と作者のシートを 1 枚にまとめる。{@code rootH} は {@code @media} の
     * {@code height} / {@code orientation} の評価に使う（0 は「分からない」）。
     */
    public com.nishiyu.enderframelib.core.css.StyleSheet buildStyleSheet(String authorCss, int rootW, int rootH) {
        return buildStyleSheet(null, authorCss, rootW, rootH);
    }

    /**
     * UA・ユーザー・作者のシートを 1 枚にまとめる。ユーザーのシートは UA より強く作者より弱いオリジンで、
     * {@code !important} では順が反転する（UA > ユーザー > 作者）。{@code userCss} が null か空なら無い。
     */
    public com.nishiyu.enderframelib.core.css.StyleSheet buildStyleSheet(String userCss, String authorCss, int rootW, int rootH) {
        com.nishiyu.enderframelib.core.css.CssParser.MediaEnvironment media =
                com.nishiyu.enderframelib.core.css.CssParser.MediaEnvironment.of(rootW, rootH)
                        .withImportResolver(resourceTextResolver);
        com.nishiyu.enderframelib.core.css.StyleSheet.CascadeOrigin userAgent =
                com.nishiyu.enderframelib.core.css.StyleSheet.CascadeOrigin.USER_AGENT;
        com.nishiyu.enderframelib.core.css.StyleSheet sheet = CssParser.parse(userAgentCss(), media, userAgent);

        if (userCss != null && !userCss.isEmpty()) {
            sheet.merge(CssParser.parse(userCss, media, com.nishiyu.enderframelib.core.css.StyleSheet.CascadeOrigin.USER));
        }
        if (authorCss != null && !authorCss.isEmpty()) {
            sheet.merge(CssParser.parse(authorCss, media, com.nishiyu.enderframelib.core.css.StyleSheet.CascadeOrigin.AUTHOR));
        }

        sheet.updateMediaEnvironment(media);
        sheet.sortCascade();
        return sheet;
    }

    private String resolveIncludes(String html, int depth) {
        if (html == null || depth > 5 || !html.contains("<include")) return html;
        Matcher incM = INCLUDE_PATTERN.matcher(html);
        StringBuilder sb = new StringBuilder();
        boolean found = false;
        while (incM.find()) {
            found = true;
            String src = incM.group(1);
            String loaded = resourceTextResolver != null ? resourceTextResolver.apply(src) : "";
            if (loaded != null && src.toLowerCase().endsWith(".md")) {
                loaded = MarkdownParser.toHtml(loaded);
            }
            incM.appendReplacement(sb, Matcher.quoteReplacement(loaded != null ? loaded : ""));
        }
        incM.appendTail(sb);
        return found ? resolveIncludes(sb.toString(), depth + 1) : html;
    }

    public Document parseDocument(String html, String externalCss, int rootW, int rootH) {
        if (html != null) {
            html = resolveIncludes(html, 0);
            html = MarkdownParser.processDocumentOrBlocks(html);
            if (html.length() > 131072) html = html.substring(0, 131072);
        }
        if (externalCss != null && externalCss.length() > 65536) externalCss = externalCss.substring(0, 65536);

        // 文書として解析するので、断片だけの文書にも html / head / body が補われる（W7）
        HtmlNode htmlRoot = HtmlParser.parseDocument(html == null ? "" : html);
        // <mc-slot> などのマクロは、正規表現での文字列置換ではなく DOM の部分木差し替えで展開する
        CustomTagExpander.expand(htmlRoot);

        StringBuilder combinedCss = new StringBuilder();
        if (externalCss != null && !externalCss.isEmpty()) {
            combinedCss.append(externalCss).append("\n");
        }
        collectStyleSheets(htmlRoot, combinedCss);

        String authorCss = combinedCss.toString();
        com.nishiyu.enderframelib.core.css.StyleSheet sheet = buildStyleSheet(authorCss, rootW, rootH);
        return new Document(htmlRoot, sheet, authorCss);
    }

    /**
     * 文書の {@code <style>} と {@code <link rel="stylesheet">} を文書順に集める。{@code <template>} の中身は不活性なので読まない。
     * {@code <style>} / {@code <script>} の要素は DOM に残る（ua.css の {@code display: none} で描かれない）。
     *
     * 何が壊れていたか: 以前は HTML を解析する前に正規表現で抜き出していたので、コメントの中の
     * {@code <style>} や {@code <textarea>} の中の文字列まで作者 CSS として読み、本文から消していた。
     * {@code <link>} はすべての {@code <style>} より先に読んでいた（文書順でなかった）。
     */
    private static void collectStyleSheets(HtmlNode node, StringBuilder css) {
        for (HtmlNode child : node.children) {
            if (child.nodeType != NodeType.ELEMENT || "template".equals(child.tag)) continue;
            if ("style".equals(child.tag)) {
                css.append(child.getTextContent().trim()).append("\n");
            } else if ("link".equals(child.tag)) {
                String href = child.attrs.get("href");
                String rel = child.attrs.getOrDefault("rel", "").toLowerCase(Locale.ROOT);
                if (href != null && (rel.contains("stylesheet") || href.endsWith(".css"))) {
                    String loadedCss = resourceTextResolver != null ? resourceTextResolver.apply(href) : "";
                    if (loadedCss != null && !loadedCss.isEmpty()) css.append(loadedCss).append("\n");
                }
            }
            collectStyleSheets(child, css);
        }
    }

    public List<DisplayItem> renderDocument(Document doc, int rootW, int rootH, Map<String, String> env, ScrollState scrollState, FontResolver fonts) {
        return renderDocument(doc, rootW, rootH, env, scrollState, fonts, new InteractionState());
    }

    public List<DisplayItem> renderDocument(Document doc, int rootW, int rootH, Map<String, String> env,
                                          ScrollState scrollState, FontResolver fonts, InteractionState interactionState) {
        if (clientBoxRenderer == null) return new ArrayList<>();

        LayoutBox rootBox = layoutDocument(doc, rootW, rootH, env, scrollState, fonts, interactionState);
        return clientBoxRenderer.render(rootBox, rootW, rootH, doc.sheet.keyframes, scrollState);
    }

    /**
     * 文書をレイアウトしてルートの {@link LayoutBox} を返す。
     *
     * 描画と分けているのは、忠実度の差分テスト（{@code WebFidelityTest}）が
     * Minecraft なしで本番と同じ経路を通すため。
     */
    public LayoutBox layoutDocument(Document doc, int rootW, int rootH, Map<String, String> env,
                                    ScrollState scrollState, FontResolver fonts) {
        return layoutDocument(doc, rootW, rootH, env, scrollState, fonts, new InteractionState());
    }

    public LayoutBox layoutDocument(Document doc, int rootW, int rootH, Map<String, String> env,
                                    ScrollState scrollState, FontResolver fonts, InteractionState interactionState) {
        // ルートに渡すのはビューポートの寸法だけ。
        // 以前は color: #f5f5f0 も渡していたが、ua.css の body { color: #333 } に
        // 負けて効いていない二重の既定値だった（UA の既定値は ua.css が唯一の出所）。
        Map<String, String> rootStyleRaw = rootViewportStyle(rootW, rootH);

        InteractionState effectiveState = interactionState != null ? interactionState : new InteractionState();
        // @media はシートを読み直さず、ビューポートの大きさで条件だけ評価し直す
        if (doc.sheet.updateMediaEnvironment(doc.sheet.mediaEnvironment().withSize(rootW, rootH))) {
            effectiveState.markAllDirty();
        }
        boolean hasContainers = doc.sheet.declaresContainers() || doc.sheet.usesContainerQueries();

        LayoutBox rootBox = null;
        // コンテナの寸法はレイアウトの結果なので、測った寸法でコンテナ問い合わせとコンテナ単位を解き直す。
        // size / inline-size のコンテナは中身で寸法が決まらない（containment）ので、数回で収束する
        for (int settle = 0; settle <= MAX_CONTAINER_PASSES; settle++) {
            rootBox = buildLayoutTree(doc.root, rootStyleRaw, doc.sheet, env, false,
                    ComputedStyle.DEFAULT_FONT_SIZE, StyleScope.initial(rootW, rootH),
                    effectiveState, StyleResolver.hasSiblingStateRules(doc.sheet));
            BoxTreeBuilder.build(rootBox);
            runLayoutPasses(rootBox, rootW, rootH, scrollState, fonts);
            if (!hasContainers || !measureContainers(rootBox, effectiveState)) break;
        }
        return rootBox;
    }

    /** コンテナの寸法を測り直してもまだ変わるときの再レイアウトの上限。 */
    private static final int MAX_CONTAINER_PASSES = 4;

    /**
     * 問い合わせコンテナ（{@code container-type} が {@code normal} でない要素）の内容ボックスの寸法を、
     * レイアウトの結果から記録する。前と違う寸法があれば true（その要素の部分木を解き直す）。
     */
    private static boolean measureContainers(LayoutBox box, InteractionState interactionState) {
        boolean changed = false;
        if (box.node != null && !box.node.isPseudoNode) {
            InteractionState.StateFlags flags = interactionState.get(box.node);
            SelectorMatcher.ContainerBox container = flags == null ? null : interactionState.containerOf(box.node);
            if (container != null && container.isSizeContainer()) {
                float width = Math.max(0, box.w - box.pL - box.pR - box.bL - box.bR);
                // inline-size のコンテナは高さが中身で決まる。測らない（高さを答えない）ので、中身の変化で再測定にならない
                float height = container.type() == SelectorMatcher.ContainerType.INLINE_SIZE
                        ? Float.NaN : Math.max(0, box.h - box.pT - box.pB - box.bT - box.bB);
                changed |= interactionState.setContainerSize(box.node, width, height);
            }
        }
        for (LayoutBox child : box.elementChildren) {
            changed |= measureContainers(child, interactionState);
        }
        return changed;
    }

    public void runLayoutPasses(LayoutBox rootBox, int rootW, int rootH, ScrollState scrollState, FontResolver fonts) {
        rootBox.linkParents();
        Viewport viewport = new Viewport(rootW, rootH);
        LayoutContext ctx = new LayoutContext(viewport, intrinsicSizeResolver, scrollState, fonts);
        FormattingContext fmtCtx = FormattingContext.BLOCK;

        // 測定は 1 パスでは収束しない。shrink-to-fit の子は親の幅が決まらないと最終幅が決まらず、
        // 親の幅はその子の幅から決まるという相互依存があるため、同じパスを規定回数だけ回す。
        for (int pass = 0; pass < LAYOUT_PASSES; pass++) {
            rootBox.measure(rootW, rootH, fmtCtx, ctx);
        }
        rootBox.layout(0, 0, rootW, rootH, ctx);
    }

    private static Map<String, String> rootViewportStyle(int rootW, int rootH) {
        Map<String, String> rootStyleRaw = new HashMap<>();
        rootStyleRaw.put("width", String.valueOf(rootW));
        rootStyleRaw.put("min-height", String.valueOf(rootH));
        rootStyleRaw.put("box-sizing", "border-box");
        return rootStyleRaw;
    }

    /**
     * 再スタイルの結果。
     *
     * @param styleChanged   いずれかの要素の算出スタイルが変化したか
     * @param layoutRequired 寸法や配置に影響するプロパティが変化し、measure/layout の再実行が必要か
     */
    public record RestyleResult(boolean styleChanged, boolean layoutRequired) {}

    /**
     * 部分木の算出値が依存する文書全体の値。
     *
     * @param rootFontSize   ルート要素（{@code <html>}）のフォントサイズ。{@code rem} の基準
     * @param rootLineHeight ルート要素の行の高さ（px）。{@code rlh} の基準。ルートをまだ見ていなければ NaN
     */
    private record StyleScope(int viewportW, int viewportH, float rootFontSize, float rootLineHeight) {

        /** ルート要素をまだ見ていない（既定のフォントサイズ・{@code normal} の行の高さ）スコープ。 */
        static StyleScope initial(int viewportW, int viewportH) {
            return new StyleScope(viewportW, viewportH, ComputedStyle.DEFAULT_FONT_SIZE, Float.NaN);
        }

        /** {@code <html>} の子孫では、その算出フォントサイズと行の高さが rem / rlh の基準になる。 */
        StyleScope enter(HtmlNode node, ComputedStyle computed) {
            return "html".equalsIgnoreCase(node.tag)
                    ? new StyleScope(viewportW, viewportH, computed.fontSizePx(), computed.lineHeightPx())
                    : this;
        }
    }

    /** キャッシュした算出スタイルが、今の親・スコープのもとでも有効か。 */
    private static boolean isStyleCacheValid(InteractionState.StateFlags flags, float parentFontSize, StyleScope scope) {
        return flags != null
                && !flags.isStyleDirty
                && flags.computedStyle != null
                && Float.compare(flags.cachedParentFontSize, parentFontSize) == 0
                && Float.compare(flags.cachedRootFontSize, scope.rootFontSize()) == 0
                && Float.compare(flags.cachedRootLineHeight, scope.rootLineHeight()) == 0
                && flags.cachedViewportW == scope.viewportW()
                && flags.cachedViewportH == scope.viewportH();
    }

    private static void cacheStyle(InteractionState.StateFlags flags, Map<String, String> rawStyle,
                                   ComputedStyle computed, float parentFontSize, StyleScope scope,
                                   boolean hasInteractive) {
        flags.computedStyleCache = rawStyle;
        flags.computedStyle = computed;
        flags.cachedParentFontSize = parentFontSize;
        flags.cachedRootFontSize = scope.rootFontSize();
        flags.cachedRootLineHeight = scope.rootLineHeight();
        flags.cachedViewportW = scope.viewportW();
        flags.cachedViewportH = scope.viewportH();
        flags.hasInteractiveRules = hasInteractive;
        flags.isStyleDirty = false;
    }

    /**
     * カスケードと算出値の確定。子に渡す継承元は<b>算出</b>スタイル
     * （{@code font-size: 2em} を子孫が親に掛け直さないよう、継承するのは解決済みの px）。
     */
    private static ComputedStyle computeStyle(Map<String, String> rawStyle, Map<String, String> parentStyle,
                                              com.nishiyu.enderframelib.core.css.StyleSheet sheet,
                                              Map<String, String> env, float parentFontSize, StyleScope scope,
                                              SelectorMatcher.ContainerBox container) {
        return StyleResolver.finalizeComputedStyle(rawStyle, sheet, env, parentStyle,
                parentFontSize, scope.rootFontSize(), scope.rootLineHeight(), scope.viewportW(), scope.viewportH(), container);
    }

    /** {@code cqw} などのコンテナ単位の基準になる、{@code node} の祖先の最も近い問い合わせコンテナ。 */
    private static SelectorMatcher.ContainerBox containerFor(HtmlNode node, InteractionState interactionState) {
        return interactionState != null
                ? interactionState.nearestSizeContainer(node.parent)
                : SelectorMatcher.ContainerBox.NONE_SIZE;
    }

    /**
     * 既存の {@link LayoutBox} ツリーに対して、状態が変化した部分木だけを再スタイルする。
     * 色・背景・枠線色・影・不透明度・transform など描画のみのプロパティ変化であれば
     * {@code layoutRequired == false} を返し、呼び出し側は {@code measure()} / {@code layout()} を省略できる。
     */
    public RestyleResult restyleLayoutTree(LayoutBox rootBox, Document doc, int rootW, int rootH,
                                           Map<String, String> env, InteractionState interactionState) {
        if (rootBox == null || doc == null || interactionState == null) {
            return new RestyleResult(true, true);
        }
        Map<String, String> rootStyleRaw = rootViewportStyle(rootW, rootH);
        // :has() の持ち主は、子孫や後続の兄弟の状態が変わると自分の見た目も変わる
        if (doc.sheet != null && doc.sheet.usesHasSelectors()) interactionState.propagateDirtyToAncestors();
        return restyleBoxRecursive(rootBox, rootStyleRaw, doc.sheet, env, false,
                ComputedStyle.DEFAULT_FONT_SIZE, StyleScope.initial(rootW, rootH),
                interactionState, StyleResolver.hasSiblingStateRules(doc.sheet));
    }

    private RestyleResult restyleBoxRecursive(LayoutBox box, Map<String, String> inheritedStyle,
                                              com.nishiyu.enderframelib.core.css.StyleSheet sheet,
                                              Map<String, String> env, boolean forceDirty,
                                              float parentFontSize, StyleScope scope,
                                              InteractionState interactionState, boolean siblingInvalidation) {
        HtmlNode node = box.node;
        InteractionState.StateFlags flags = interactionState.getOrCreate(node);
        boolean isDirty = forceDirty || !isStyleCacheValid(flags, parentFontSize, scope);

        boolean styleChanged = false;
        boolean layoutRequired = false;
        ComputedStyle computed;

        if (isDirty) {
            StyleSet styleSet = StyleResolver.resolveStyleSet(node, inheritedStyle, sheet);
            Map<String, String> rawStyle = styleSet.resolve(interactionState);
            if (node.attrs.containsKey("hidden")) {
                rawStyle.put(CssProperty.DISPLAY, "none");
            }
            computed = computeStyle(rawStyle, inheritedStyle, sheet, env, parentFontSize, scope,
                    containerFor(node, interactionState));
            boolean hasInteractive = styleSet.hasInteractiveRules();

            ComputedStyle prevStyle = box.style;
            if (prevStyle == null || !prevStyle.asMap().equals(computed.asMap())) {
                styleChanged = true;
                if (StyleResolver.isLayoutAffectingChange(prevStyle, computed)) {
                    layoutRequired = true;
                }
            }

            if ("details".equalsIgnoreCase(node.tag)) {
                boolean isOpen = interactionState.stateOf(node).open();
                boolean wasOpen = box.elementChildren.size() > 1;
                if (isOpen != wasOpen) {
                    styleChanged = true;
                    layoutRequired = true;
                }
            }

            box.updateStyle(computed);
            resolveHighlightPseudos(box, computed, sheet, env, scope.enter(node, computed), interactionState);
            BoxTreeBuilder.restyleGeneratedBoxes(box);
            box.hasInteractiveRules = hasInteractive;
            cacheStyle(flags, rawStyle, computed, parentFontSize, scope, hasInteractive);
        } else {
            computed = flags.computedStyle;
        }

        // 現在のノードが再スタイル対象だった場合のみ、子孫に forceDirty を伝播して部分木を再スタイルする
        boolean childForceDirty = isDirty;
        float nextParentFontSize = computed.fontSizePx();
        StyleScope childScope = scope.enter(node, computed);

        // 擬似要素のスタイルは持ち主の状態と算出スタイルから決まるので、持ち主と一緒に解き直す
        if (isDirty) {
            for (PseudoType type : PseudoType.GENERATED) {
                RestyleResult pseudoRes = restylePseudoElement(box, type, computed, sheet, env, childScope,
                        interactionState, siblingInvalidation);
                styleChanged |= pseudoRes.styleChanged();
                layoutRequired |= pseudoRes.layoutRequired();
            }
        }

        // 要素ツリーを辿る（ボックスツリーには匿名ボックスが挟まり、display: contents の要素が無い）
        boolean siblingForceDirty = false;
        for (LayoutBox child : box.elementChildren) {
            if (child.node.isPseudoNode) continue;
            boolean stateChanged = siblingInvalidation && isStateDirty(interactionState, child.node);
            RestyleResult childRes = restyleBoxRecursive(child, computed.asMap(), sheet, env,
                    childForceDirty || siblingForceDirty, nextParentFontSize, childScope, interactionState,
                    siblingInvalidation);
            styleChanged |= childRes.styleChanged();
            layoutRequired |= childRes.layoutRequired();
            siblingForceDirty |= stateChanged;
        }

        return new RestyleResult(styleChanged, layoutRequired);
    }

    /**
     * 状態（または属性）が変わって再スタイル待ちの要素か。兄弟結合子の規則があるとき、こうした要素の
     * 後続の兄弟は自分が汚れていなくても再スタイルする（{@code input:checked + label}）。
     *
     * 何が壊れていたか: 以前は汚れた要素の子孫にだけ再スタイルを伝えていたので、{@code :checked} が
     * 変わっても隣の {@code label} の見た目が変わらなかった。
     */
    private static boolean isStateDirty(InteractionState interactionState, HtmlNode node) {
        if (interactionState == null) return false;
        InteractionState.StateFlags flags = interactionState.get(node);
        return flags == null || flags.isStyleDirty;
    }

    /**
     * 持ち主の再スタイルに合わせて {@code ::before} / {@code ::after} を解き直す。
     * 現れる・消えるときは要素の子の並びを直し、ボックスツリーが変わるので再レイアウトを要求する
     * （{@code :hover::after { content: "→" }}）。
     */
    private RestyleResult restylePseudoElement(LayoutBox owner, PseudoType type, ComputedStyle ownerStyle,
                                               com.nishiyu.enderframelib.core.css.StyleSheet sheet,
                                               Map<String, String> env, StyleScope scope,
                                               InteractionState interactionState, boolean siblingInvalidation) {
        String pseudoId = pseudoElementId(owner.node, type);
        LayoutBox existing = null;
        for (LayoutBox child : owner.elementChildren) {
            if (child.node.isPseudoNode && pseudoId.equals(child.node.getId())) {
                existing = child;
                break;
            }
        }
        ComputedStyle next = resolvePseudoStyle(owner.node, type, ownerStyle, sheet, env, scope, interactionState);
        if (existing == null && next == null) return new RestyleResult(false, false);

        if (existing == null || next == null) {
            if (existing != null) {
                owner.elementChildren.remove(existing);
            } else {
                LayoutBox created = buildPseudoBox(owner.node, type, next, owner.hasInteractiveRules, sheet, env,
                        scope, interactionState, siblingInvalidation);
                owner.addElementChild(created);
                // addElementChild は末尾に足すので、::before は先頭へ移す
                if (type == PseudoType.BEFORE) {
                    owner.elementChildren.remove(created);
                    owner.elementChildren.add(0, created);
                }
            }
            return new RestyleResult(true, true);
        }

        boolean styleChanged = !existing.style.asMap().equals(next.asMap());
        // content はペイント専用ではないので、文字が変われば再レイアウトになる（BoxTreeBuilder が文字を評価し直す）
        boolean layoutRequired = styleChanged && StyleResolver.isLayoutAffectingChange(existing.style, next);
        existing.updateStyle(next);
        existing.hasInteractiveRules = owner.hasInteractiveRules;
        BoxTreeBuilder.restyleGeneratedBoxes(existing);
        for (LayoutBox text : existing.elementChildren) {
            RestyleResult textRes = restyleBoxRecursive(text, next.asMap(), sheet, env, true, next.fontSizePx(),
                    scope, interactionState, siblingInvalidation);
            styleChanged |= textRes.styleChanged();
            layoutRequired |= textRes.layoutRequired();
        }
        return new RestyleResult(styleChanged, layoutRequired);
    }

    /** パッケージ可視なのは、状態スタイルの生成をヘッドレステストから検証するため。 */
    LayoutBox buildLayoutTree(HtmlNode node, Map<String, String> inheritedStyle,
                              com.nishiyu.enderframelib.core.css.StyleSheet sheet,
                              Map<String, String> env, boolean forceDirty) {
        return buildLayoutTree(node, inheritedStyle, sheet, env, forceDirty, new InteractionState());
    }

    public LayoutBox buildLayoutTree(HtmlNode node, Map<String, String> inheritedStyle,
                                     com.nishiyu.enderframelib.core.css.StyleSheet sheet,
                                     Map<String, String> env, boolean forceDirty,
                                     InteractionState interactionState) {
        InteractionState effectiveState = interactionState != null ? interactionState : new InteractionState();
        // ビューポートが分からない呼び出し（ヘッドレステスト）では vw / vh のフォントサイズは 0 で解かれる
        return buildLayoutTree(node, inheritedStyle, sheet, env, forceDirty,
                ComputedStyle.DEFAULT_FONT_SIZE, StyleScope.initial(0, 0), effectiveState,
                StyleResolver.hasSiblingStateRules(sheet));
    }

    private LayoutBox buildLayoutTree(HtmlNode node, Map<String, String> inheritedStyle,
                                      com.nishiyu.enderframelib.core.css.StyleSheet sheet,
                                      Map<String, String> env, boolean forceDirty,
                                      float parentFontSize, StyleScope scope, InteractionState interactionState,
                                      boolean siblingInvalidation) {
        InteractionState.StateFlags flags = interactionState != null ? interactionState.getOrCreate(node) : null;
        boolean needsRestyle = forceDirty || !isStyleCacheValid(flags, parentFontSize, scope);

        ComputedStyle computed;
        boolean hasInteractive;

        if (needsRestyle) {
            StyleSet styleSet = StyleResolver.resolveStyleSet(node, inheritedStyle, sheet);
            Map<String, String> rawStyle = interactionState != null
                    ? styleSet.resolve(interactionState)
                    : styleSet.resolve(ElementState.NONE);
            if (node.attrs.containsKey("hidden")) {
                rawStyle.put(CssProperty.DISPLAY, "none");
            }

            computed = computeStyle(rawStyle, inheritedStyle, sheet, env, parentFontSize, scope,
                    containerFor(node, interactionState));
            hasInteractive = styleSet.hasInteractiveRules();

            if (flags != null) cacheStyle(flags, rawStyle, computed, parentFontSize, scope, hasInteractive);
        } else {
            computed = flags.computedStyle;
            hasInteractive = flags.hasInteractiveRules;
        }
        Map<String, String> childInherited = computed.asMap();
        StyleScope childScope = scope.enter(node, computed);

        LayoutBox box = new LayoutBox(node, computed);
        box.hasInteractiveRules = hasInteractive;
        // ::marker の規則はリスト項目の算出スタイルから解く（箱は BoxTreeBuilder が作る）
        resolveHighlightPseudos(box, computed, sheet, env, childScope, interactionState);

        // 状態が変わった要素の部分木だけを再スタイルするよう、自身が dirty だった場合のみ子へ forceDirty を渡す
        boolean childForceDirty = needsRestyle;

        ComputedStyle beforeStyle = resolvePseudoStyle(node, PseudoType.BEFORE, computed, sheet, env, childScope, interactionState);
        if (beforeStyle != null) {
            box.addElementChild(buildPseudoBox(node, PseudoType.BEFORE, beforeStyle, hasInteractive, sheet, env,
                    childScope, interactionState, siblingInvalidation));
        }

        if (!"textarea".equalsIgnoreCase(node.tag)) {
            boolean isOpen = interactionState != null
                    ? interactionState.stateOf(node).open()
                    : node.attrs.containsKey("open");
            boolean isClosedDetails = "details".equalsIgnoreCase(node.tag) && !isOpen;
            boolean siblingForceDirty = false;
            for (HtmlNode child : node.children) {
                if (child.tag.equals("#text") && child.text.isEmpty()) continue;
                if (isClosedDetails && !"summary".equalsIgnoreCase(child.tag)) continue;
                boolean stateChanged = siblingInvalidation && isStateDirty(interactionState, child);
                box.addElementChild(buildLayoutTree(child, childInherited, sheet, env, childForceDirty || siblingForceDirty,
                        computed.fontSizePx(), childScope, interactionState, siblingInvalidation));
                siblingForceDirty |= stateChanged;
            }
        }

        ComputedStyle afterStyle = resolvePseudoStyle(node, PseudoType.AFTER, computed, sheet, env, childScope, interactionState);
        if (afterStyle != null) {
            box.addElementChild(buildPseudoBox(node, PseudoType.AFTER, afterStyle, hasInteractive, sheet, env,
                    childScope, interactionState, siblingInvalidation));
        }

        return box;
    }

    /**
     * 箱を作らない擬似要素（リスト項目の {@code ::marker}・入力欄の {@code ::placeholder}）の算出スタイルを、
     * 持ち主の箱に持たせる。マーカーの箱は {@link BoxTreeBuilder}、プレースホルダーの文字は描画が読む。
     */
    private static void resolveHighlightPseudos(LayoutBox box, ComputedStyle computed,
                                                com.nishiyu.enderframelib.core.css.StyleSheet sheet,
                                                Map<String, String> env, StyleScope childScope,
                                                InteractionState interactionState) {
        HtmlNode node = box.node;
        box.markerStyle = box.display.listItem()
                ? resolvePseudoStyle(node, PseudoType.MARKER, computed, sheet, env, childScope, interactionState)
                : null;
        boolean textInput = "input".equals(node.tag) || "textarea".equals(node.tag);
        box.placeholderStyle = textInput
                ? resolvePseudoStyle(node, PseudoType.PLACEHOLDER, computed, sheet, env, childScope, interactionState)
                : null;
    }

    /** 持ち主の算出スタイルと状態から擬似要素の算出スタイルを解く。箱を作らないなら null。 */
    private static ComputedStyle resolvePseudoStyle(HtmlNode owner, PseudoType type, ComputedStyle ownerStyle,
                                                    com.nishiyu.enderframelib.core.css.StyleSheet sheet,
                                                    Map<String, String> env, StyleScope scope,
                                                    InteractionState interactionState) {
        com.nishiyu.enderframelib.core.css.SelectorMatcher.StateLookup lookup =
                interactionState != null ? interactionState : com.nishiyu.enderframelib.core.css.SelectorMatcher.STATIC_ATTRIBUTES;
        return StyleResolver.resolvePseudoStyle(owner, type, sheet, lookup, ownerStyle, env,
                scope.rootFontSize(), scope.rootLineHeight(), scope.viewportW(), scope.viewportH());
    }

    private static String pseudoElementId(HtmlNode owner, PseudoType type) {
        return owner.getId() + (type == PseudoType.BEFORE ? "_before" : "_after");
    }

    /**
     * 擬似要素の箱。DOM に無い合成ノード（{@code isPseudoNode}）に、解いた算出スタイルをそのまま持たせる。
     * 中の文字（{@code content} の評価）はカウンタを数える {@link BoxTreeBuilder} が埋める。
     */
    private LayoutBox buildPseudoBox(HtmlNode owner, PseudoType type, ComputedStyle style, boolean ownerInteractive,
                                     com.nishiyu.enderframelib.core.css.StyleSheet sheet,
                                     Map<String, String> env, StyleScope scope,
                                     InteractionState interactionState, boolean siblingInvalidation) {
        HtmlNode pseudo = new HtmlNode("span");
        pseudo.isPseudoNode = true;
        pseudo.parent = owner;
        pseudo.setId(pseudoElementId(owner, type));

        HtmlNode textNode = new com.nishiyu.enderframelib.core.dom.HtmlDocument.HtmlText("");
        textNode.parent = pseudo;
        textNode.setId(pseudo.getId() + "_txt");
        pseudo.children.add(textNode);

        LayoutBox box = new LayoutBox(pseudo, style);
        // 擬似要素のホバーは持ち主のホバーなので、受け口になるかは持ち主に倣う
        box.hasInteractiveRules = ownerInteractive;
        box.addElementChild(buildLayoutTree(textNode, style.asMap(), sheet, env, true, style.fontSizePx(), scope,
                interactionState, siblingInvalidation));
        return box;
    }

    @org.jetbrains.annotations.ApiStatus.Internal
    public static class Document implements com.nishiyu.enderframelib.api.WebDocument {
        public HtmlNode root;
        public com.nishiyu.enderframelib.core.css.StyleSheet sheet;
        public String authorCss;

        public Document(HtmlNode root, com.nishiyu.enderframelib.core.css.StyleSheet sheet, String authorCss) {
            this.root = root;
            this.sheet = sheet;
            this.authorCss = authorCss;
        }

        @Override
        public String authorCss() {
            return authorCss != null ? authorCss : "";
        }

        @Override
        public boolean hasElement(String elementId) {
            return find(elementId) != null;
        }

        @Override
        public String tagOf(String elementId) {
            HtmlNode node = find(elementId);
            return node != null ? node.tag : null;
        }

        @Override
        public String attributeOf(String elementId, String attributeName) {
            HtmlNode node = find(elementId);
            return node != null && attributeName != null ? node.attrs.get(attributeName) : null;
        }

        private HtmlNode find(String elementId) {
            if (root == null || elementId == null || elementId.isEmpty()) return null;
            return root.getElementById(elementId);
        }
    }

    /**
     * jar に同梱した CSS リソースの読み込み。
     *
     * UA スタイルシート・組み込みテーマ・開発キットのプレビュー CSS は、以前
     * Java の文字列リテラルとして UiDocumentCompiler と WebDevKitExporter に重複して埋め込まれていた
     * （focus-visible の宣言は 4 箇所にコピーがあった）。現在はリソースが単一の出所で、
     * ここが唯一の読み込み口。
     */
    public static final class CssResources {

        private static final Logger LOGGER = LoggerFactory.getLogger(CssResources.class);

        /** UA スタイルシート。 */
        public static final String USER_AGENT = "/assets/enderframelib/css/ua.css";
        /** 開発キットのプレビュー用スタイルシート（ブラウザで開く HTML が参照する）。 */
        public static final String DEV_KIT = "/assets/enderframelib/preview/enderframe-devkit.css";

        private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

        private CssResources() {}

        /** 組み込みテーマのリソースパス。 */
        public static String themePath(String name) {
            return "/assets/enderframelib/css/theme/" + name + ".css";
        }

        /**
         * リソースを読み込む（結果はキャッシュされる）。
         * 見つからない場合は空文字列を返し、警告を 1 度だけ出す。
         */
        public static String load(String resourcePath) {
            return CACHE.computeIfAbsent(resourcePath, CssResources::read);
        }

        private static String read(String resourcePath) {
            try (InputStream is = CssResources.class.getResourceAsStream(resourcePath)) {
                if (is == null) {
                    LOGGER.warn("CSS リソースが見つかりません: {}", resourcePath);
                    return "";
                }
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                LOGGER.warn("CSS リソースの読み込みに失敗しました: {}", resourcePath, e);
                return "";
            }
        }
    }
}
