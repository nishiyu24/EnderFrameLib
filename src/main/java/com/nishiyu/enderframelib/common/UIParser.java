package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssParser;
import com.nishiyu.enderframelib.core.css.StyleResolver;
import com.nishiyu.enderframelib.core.css.ElementState;
import com.nishiyu.enderframelib.core.css.StyleSet;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.dom.HtmlParser;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.layout.LayoutContext.FormattingContext;
import com.nishiyu.enderframelib.core.layout.LayoutContext.IntrinsicSizeResolver;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.LayoutContext;
import com.nishiyu.enderframelib.core.layout.LayoutContext.ScrollState;
import com.nishiyu.enderframelib.core.layout.LayoutContext.Viewport;
import com.nishiyu.enderframelib.core.markdown.MarkdownParser;
import com.nishiyu.enderframelib.core.model.UIElement;
import com.nishiyu.enderframelib.core.text.FontFace.FontResolver;
import com.nishiyu.enderframelib.server.EnderFrameDataPackManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@org.jetbrains.annotations.ApiStatus.Internal
public class UIParser {

    private static final Logger LOGGER = LoggerFactory.getLogger(UIParser.class);

    private static final Pattern INCLUDE_PATTERN = Pattern.compile("(?is)<include\\b[^>]*\\bsrc=[\"']([^\"']+)[\"'][^>]*(?:/>|>\\s*</include>)");
    private static final Pattern LINK_HREF_PATTERN = Pattern.compile("(?is)<link\\b[^>]*\\bhref=[\"']([^\"']+)[\"'][^>]*>");
    private static final Pattern SCRIPT_TAG_PATTERN = Pattern.compile("(?is)<script\\b[^>]*>\\s*(.*?)\\s*</script>");
    private static final Pattern STYLE_TAG_PATTERN = Pattern.compile("(?is)<style\\b[^>]*>\\s*(.*?)\\s*</style>");
    private static final Pattern TRIM_QUOTES_PATTERN = Pattern.compile("^[\"']|[\"']$");

    @FunctionalInterface
    public interface ILayoutBoxRenderer {
        List<UIElement> render(LayoutBox rootBox, int rootW, int rootH, Map<String, String> keyframes, ScrollState scrollState);
    }

    /**
     * 測定パスの回数。
     * 1 回目で子の内容幅が分かり、2 回目でそれを踏まえた親の幅が子に伝わる。
     * 3 回目以降で変わるケースは現状確認されていないため 2 で固定している。
     */
    private static final int LAYOUT_PASSES = 2;

    public static ILayoutBoxRenderer clientBoxRenderer = null;
    public static Function<String, String> clientResourceFallback = null;

    /**
     * 置換要素（リモート画像など）の固有サイズ解決。
     * core 側に public static な可変フックを置かず、ここから LayoutContext に載せて渡す。
     */
    public static IntrinsicSizeResolver intrinsicSizeResolver = null;

    /**
     * innerHTML 相当のフラグメント解析。
     * Markdown は文字列段階、カスタムタグはパース後の DOM 段階で展開する。
     */
    public static final Function<String, HtmlNode> INNER_HTML_PARSER = html -> {
        HtmlNode root = HtmlParser.parse(MarkdownParser.processDocumentOrBlocks(html));
        CustomTagExpander.expand(root);
        return root;
    };

    public static Function<String, String> resourceTextResolver = path -> {
        if (path == null) return "";
        String clean = path.trim();
        String builtIn = resolveBuiltInTheme(clean);
        if (builtIn != null) return builtIn;

        String fromDatapack = EnderFrameDataPackManager.getHtml(clean);
        if (fromDatapack != null && !fromDatapack.isEmpty()) return fromDatapack;

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
        try (InputStream is = UIParser.class.getResourceAsStream(absolutePath)) {
            if (is != null) return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("リソースの読み込みに失敗しました: {} ({})", absolutePath, e.getMessage());
        }
        return null;
    }

    public UIParser() {}

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
        com.nishiyu.enderframelib.core.css.MediaEnvironment media =
                com.nishiyu.enderframelib.core.css.MediaEnvironment.of(rootW, rootH)
                        .withImportResolver(resourceTextResolver);
        com.nishiyu.enderframelib.core.css.StyleSheet sheet =
                CssParser.parse(userAgentCss(), media, com.nishiyu.enderframelib.core.css.CascadeOrigin.USER_AGENT);

        if (authorCss != null && !authorCss.isEmpty()) {
            com.nishiyu.enderframelib.core.css.StyleSheet authorSheet =
                    CssParser.parse(authorCss, media, com.nishiyu.enderframelib.core.css.CascadeOrigin.AUTHOR);
            for (com.nishiyu.enderframelib.core.css.StyleRule rule : authorSheet.rules) {
                rule.setOrigin(com.nishiyu.enderframelib.core.css.CascadeOrigin.AUTHOR);
            }
            sheet.rules.addAll(authorSheet.rules);
            sheet.keyframes.putAll(authorSheet.keyframes);
            sheet.variables.putAll(authorSheet.variables);
        }

        sheet.rules.sort(com.nishiyu.enderframelib.core.css.StyleRule::compareSpecificity);
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

        StringBuilder combinedCss = new StringBuilder();
        if (externalCss != null && !externalCss.isEmpty()) {
            combinedCss.append(externalCss).append("\n");
        }

        if (html != null) {
            Matcher linkM = LINK_HREF_PATTERN.matcher(html);
            while (linkM.find()) {
                String tagFull = linkM.group(0).toLowerCase();
                if (tagFull.contains("stylesheet") || linkM.group(1).endsWith(".css")) {
                    String href = linkM.group(1);
                    String loadedCss = resourceTextResolver != null ? resourceTextResolver.apply(href) : "";
                    if (loadedCss != null && !loadedCss.isEmpty()) {
                        combinedCss.append(loadedCss).append("\n");
                    }
                }
            }

            Matcher scriptM = SCRIPT_TAG_PATTERN.matcher(html);
            html = scriptM.replaceAll("");

            Matcher styleM = STYLE_TAG_PATTERN.matcher(html);
            while (styleM.find()) {
                combinedCss.append(styleM.group(1)).append("\n");
            }
            html = styleM.replaceAll("");
        }

        String authorCss = combinedCss.toString();
        com.nishiyu.enderframelib.core.css.StyleSheet sheet = buildStyleSheet(authorCss, rootW, rootH);
        HtmlNode htmlRoot = HtmlParser.parse(html == null ? "" : html);
        // <mc-slot> などのマクロは、正規表現での文字列置換ではなく DOM の部分木差し替えで展開する
        CustomTagExpander.expand(htmlRoot);

        return new Document(htmlRoot, sheet, authorCss);
    }

    public List<UIElement> renderDocument(Document doc, int rootW, int rootH, Map<String, String> env, ScrollState scrollState, FontResolver fonts) {
        return renderDocument(doc, rootW, rootH, env, scrollState, fonts, new InteractionState());
    }

    public List<UIElement> renderDocument(Document doc, int rootW, int rootH, Map<String, String> env,
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
        LayoutBox rootBox = buildLayoutTree(doc.root, rootStyleRaw, doc.sheet, env, false,
                ComputedStyle.DEFAULT_FONT_SIZE, new StyleScope(rootW, rootH, ComputedStyle.DEFAULT_FONT_SIZE),
                effectiveState);

        runLayoutPasses(rootBox, rootW, rootH, scrollState, fonts);
        return rootBox;
    }

    public void runLayoutPasses(LayoutBox rootBox, int rootW, int rootH, ScrollState scrollState, FontResolver fonts) {
        rootBox.linkParents();
        Viewport viewport = new Viewport(rootW, rootH);
        LayoutContext ctx = new LayoutContext(viewport, intrinsicSizeResolver, scrollState, fonts);
        FormattingContext fmtCtx = new FormattingContext("block", "stretch", false);

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
     * @param rootFontSize ルート要素（{@code <html>}）のフォントサイズ。{@code rem} の基準
     */
    private record StyleScope(int viewportW, int viewportH, float rootFontSize) {
        /** {@code <html>} の子孫では、その算出フォントサイズが rem の基準になる。 */
        StyleScope enter(HtmlNode node, ComputedStyle computed) {
            return "html".equalsIgnoreCase(node.tag) ? new StyleScope(viewportW, viewportH, computed.fontSizePx()) : this;
        }
    }

    /** キャッシュした算出スタイルが、今の親・スコープのもとでも有効か。 */
    private static boolean isStyleCacheValid(InteractionState.StateFlags flags, float parentFontSize, StyleScope scope) {
        return flags != null
                && !flags.isStyleDirty
                && flags.computedStyle != null
                && Float.compare(flags.cachedParentFontSize, parentFontSize) == 0
                && Float.compare(flags.cachedRootFontSize, scope.rootFontSize()) == 0
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
                                              Map<String, String> env, float parentFontSize, StyleScope scope) {
        return StyleResolver.finalizeComputedStyle(rawStyle, sheet, env, parentStyle,
                parentFontSize, scope.rootFontSize(), scope.viewportW(), scope.viewportH());
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
        return restyleBoxRecursive(rootBox, rootStyleRaw, doc.sheet, env, false,
                ComputedStyle.DEFAULT_FONT_SIZE, new StyleScope(rootW, rootH, ComputedStyle.DEFAULT_FONT_SIZE),
                interactionState);
    }

    private RestyleResult restyleBoxRecursive(LayoutBox box, Map<String, String> inheritedStyle,
                                              com.nishiyu.enderframelib.core.css.StyleSheet sheet,
                                              Map<String, String> env, boolean forceDirty,
                                              float parentFontSize, StyleScope scope,
                                              InteractionState interactionState) {
        HtmlNode node = box.node;
        InteractionState.StateFlags flags = interactionState.getOrCreate(node.getId());
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
            computed = computeStyle(rawStyle, inheritedStyle, sheet, env, parentFontSize, scope);
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
                boolean wasOpen = box.children.size() > 1;
                if (isOpen != wasOpen) {
                    styleChanged = true;
                    layoutRequired = true;
                }
            }

            box.updateStyle(computed);
            box.hasInteractiveRules = hasInteractive;
            cacheStyle(flags, rawStyle, computed, parentFontSize, scope, hasInteractive);
        } else {
            computed = flags.computedStyle;
        }

        // 現在のノードが再スタイル対象だった場合のみ、子孫に forceDirty を伝播して部分木を再スタイルする
        boolean childForceDirty = isDirty;
        float nextParentFontSize = computed.fontSizePx();
        StyleScope childScope = scope.enter(node, computed);
        for (LayoutBox child : box.children) {
            RestyleResult childRes = restyleBoxRecursive(child, computed.asMap(), sheet, env,
                    childForceDirty, nextParentFontSize, childScope, interactionState);
            styleChanged |= childRes.styleChanged();
            layoutRequired |= childRes.layoutRequired();
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
                ComputedStyle.DEFAULT_FONT_SIZE, new StyleScope(0, 0, ComputedStyle.DEFAULT_FONT_SIZE), effectiveState);
    }

    private LayoutBox buildLayoutTree(HtmlNode node, Map<String, String> inheritedStyle,
                                      com.nishiyu.enderframelib.core.css.StyleSheet sheet,
                                      Map<String, String> env, boolean forceDirty,
                                      float parentFontSize, StyleScope scope, InteractionState interactionState) {
        InteractionState.StateFlags flags = interactionState != null ? interactionState.getOrCreate(node.getId()) : null;
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

            computed = computeStyle(rawStyle, inheritedStyle, sheet, env, parentFontSize, scope);
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

        // 状態が変わった要素の部分木だけを再スタイルするよう、自身が dirty だった場合のみ子へ forceDirty を渡す
        boolean childForceDirty = needsRestyle;

        HtmlNode beforeNode = createPseudoElement(node, sheet, true, interactionState);
        if (beforeNode != null) {
            box.addChild(buildLayoutTree(beforeNode, childInherited, sheet, env, true, computed.fontSizePx(), childScope, interactionState));
        }

        if (!"textarea".equalsIgnoreCase(node.tag)) {
            boolean isOpen = interactionState != null
                    ? interactionState.stateOf(node).open()
                    : node.attrs.containsKey("open");
            boolean isClosedDetails = "details".equalsIgnoreCase(node.tag) && !isOpen;
            for (HtmlNode child : node.children) {
                if (child.tag.equals("#text") && child.text.isEmpty()) continue;
                if (isClosedDetails && !"summary".equalsIgnoreCase(child.tag)) continue;
                box.addChild(buildLayoutTree(child, childInherited, sheet, env, childForceDirty, computed.fontSizePx(), childScope, interactionState));
            }
        }

        HtmlNode afterNode = createPseudoElement(node, sheet, false, interactionState);
        if (afterNode != null) {
            box.addChild(buildLayoutTree(afterNode, childInherited, sheet, env, true, computed.fontSizePx(), childScope, interactionState));
        }

        return box;
    }

    private HtmlNode createPseudoElement(HtmlNode parent, com.nishiyu.enderframelib.core.css.StyleSheet sheet,
                                         boolean isBefore, InteractionState interactionState) {
        Map<String, String> pseudoStyle = new HashMap<>();
        boolean matched = false;
        com.nishiyu.enderframelib.core.css.SelectorMatcher.StateLookup lookup =
                interactionState != null ? interactionState : com.nishiyu.enderframelib.core.css.SelectorMatcher.STATIC_ATTRIBUTES;

        for (com.nishiyu.enderframelib.core.css.StyleRule rule : sheet.rules) {
            if ((isBefore && rule.isBefore) || (!isBefore && rule.isAfter)) {
                if (rule.matches(parent, lookup)) {
                    pseudoStyle.putAll(rule.properties);
                    matched = true;
                }
            }
        }
        if (!matched || !pseudoStyle.containsKey(CssProperty.CONTENT)) return null;

        String content = pseudoStyle.get(CssProperty.CONTENT);
        if (content.equals("none") || content.equals("normal")) return null;

        HtmlNode pseudo = new HtmlNode("span");
        pseudo.isPseudoNode = true;
        pseudo.parent = parent;
        pseudo.setId(parent.getId() + (isBefore ? "_before" : "_after"));

        HtmlNode textNode = HtmlNode.createTextNode("");
        textNode.parent = pseudo;
        textNode.setId(pseudo.getId() + "_txt");

        if (content.startsWith("attr(") && content.endsWith(")")) {
            String attrName = content.substring(5, content.length() - 1).trim();
            textNode.text = parent.attrs.getOrDefault(attrName, "");
        } else {
            textNode.text = TRIM_QUOTES_PATTERN.matcher(content).replaceAll("");
        }

        pseudo.children.add(textNode);

        StringBuilder styleStr = new StringBuilder();
        for (Map.Entry<String, String> entry : pseudoStyle.entrySet()) {
            styleStr.append(entry.getKey()).append(":").append(entry.getValue()).append(";");
        }
        pseudo.attrs.put("style", styleStr.toString());

        return pseudo;
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
}