package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.paint.model.*;

import com.nishiyu.enderframelib.core.css.StyleSheet;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.layout.model.IntrinsicSizeResolver;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 下流 Mod および既存呼び出しのための委譲ファサード。
 * 実際の処理は {@link DocumentLoader} と {@link RenderPipeline} に分離されています。
 */
@ApiStatus.Internal
public class UiDocumentCompiler {

    public interface ILayoutBoxRenderer extends RenderPipeline.ILayoutBoxRenderer {}

    public static RenderPipeline.ILayoutBoxRenderer clientBoxRenderer = null;
    public static Function<String, String> clientResourceFallback = null;
    public static Function<String, String> datapackResourceFallback = null;
    public static IntrinsicSizeResolver intrinsicSizeResolver = null;
    public static Function<String, String> resourceTextResolver = DocumentLoader.DEFAULT_RESOURCE_TEXT_RESOLVER;
    public static final BiFunction<String, String, HtmlNode> INNER_HTML_PARSER = DocumentLoader.INNER_HTML_PARSER;

    public UiDocumentCompiler() {}

    public static String resolveBuiltInTheme(String href) {
        return DocumentLoader.resolveBuiltInTheme(href);
    }

    public static String userAgentCss() {
        return DocumentLoader.userAgentCss();
    }

    public StyleSheet buildStyleSheet(String authorCss, int rootW) {
        return DocumentLoader.buildStyleSheet(authorCss, rootW);
    }

    public StyleSheet buildStyleSheet(String authorCss, int rootW, int rootH) {
        return DocumentLoader.buildStyleSheet(authorCss, rootW, rootH);
    }

    public StyleSheet buildStyleSheet(String userCss, String authorCss, int rootW, int rootH) {
        return DocumentLoader.buildStyleSheet(userCss, authorCss, rootW, rootH);
    }

    public Document parseDocument(String html, String externalCss, int rootW, int rootH) {
        return DocumentLoader.parseDocument(html, externalCss, rootW, rootH);
    }

    public List<DisplayItem> renderDocument(Document doc, int rootW, int rootH, Map<String, String> env, ScrollState scrollState, FontResolver fonts) {
        return RenderPipeline.renderDocument(doc, rootW, rootH, env, scrollState, fonts);
    }

    public List<DisplayItem> renderDocument(Document doc, int rootW, int rootH, Map<String, String> env,
                                          ScrollState scrollState, FontResolver fonts, InteractionState interactionState) {
        return RenderPipeline.renderDocument(doc, rootW, rootH, env, scrollState, fonts, interactionState);
    }

    public LayoutBox layoutDocument(Document doc, int rootW, int rootH, Map<String, String> env,
                                    ScrollState scrollState, FontResolver fonts) {
        return RenderPipeline.layoutDocument(doc, rootW, rootH, env, scrollState, fonts);
    }

    public LayoutBox layoutDocument(Document doc, int rootW, int rootH, Map<String, String> env,
                                    ScrollState scrollState, FontResolver fonts, InteractionState interactionState) {
        return RenderPipeline.layoutDocument(doc, rootW, rootH, env, scrollState, fonts, interactionState);
    }

    /** 前回の結果 {@code previousRoot} から差分レイアウトする（{@link RenderPipeline#layoutDocument} を参照）。 */
    public LayoutBox layoutDocument(Document doc, int rootW, int rootH, Map<String, String> env,
                                    ScrollState scrollState, FontResolver fonts, InteractionState interactionState,
                                    LayoutBox previousRoot) {
        return RenderPipeline.layoutDocument(doc, rootW, rootH, env, scrollState, fonts, interactionState, previousRoot);
    }

    public void runLayoutPasses(LayoutBox rootBox, int rootW, int rootH, ScrollState scrollState, FontResolver fonts) {
        RenderPipeline.runLayoutPasses(rootBox, rootW, rootH, scrollState, fonts);
    }

    public RenderPipeline.RestyleResult restyleLayoutTree(LayoutBox rootBox, Document doc, int rootW, int rootH,
                                           Map<String, String> env, InteractionState interactionState) {
        return RenderPipeline.restyleLayoutTree(rootBox, doc, rootW, rootH, env, interactionState);
    }

    public LayoutBox buildLayoutTree(HtmlNode node, Map<String, String> inheritedStyle,
                              StyleSheet sheet,
                              Map<String, String> env, boolean forceDirty) {
        return RenderPipeline.buildLayoutTree(node, inheritedStyle, sheet, env, forceDirty);
    }

    public LayoutBox buildLayoutTree(HtmlNode node, Map<String, String> inheritedStyle,
                                     StyleSheet sheet,
                                     Map<String, String> env, boolean forceDirty,
                                     InteractionState interactionState) {
        return RenderPipeline.buildLayoutTree(node, inheritedStyle, sheet, env, forceDirty, interactionState);
    }
}
