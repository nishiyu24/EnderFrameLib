package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.paint.model.*;

import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.css.StyleSheet;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.layout.model.IntrinsicSizeResolver;
import com.nishiyu.enderframelib.core.paint.scene.DisplayScene;
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
 * 実際の処理は {@link DocumentLoader}（読み込み）と {@link FrameRender}（1 回だけのフレーム）にある。
 */
@ApiStatus.Internal
public class UiDocumentCompiler {

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

    /** 文書を 1 度だけ組んで描いた表示リスト（{@link FrameRender#document}）。 */
    public List<DisplayItem> renderDocument(Document doc, int rootW, int rootH, Map<String, String> env, ScrollState scrollState, FontResolver fonts) {
        return FrameRender.document(doc, rootW, rootH, env, scrollState, fonts, new InteractionState()).items();
    }

    public List<DisplayItem> renderDocument(Document doc, int rootW, int rootH, Map<String, String> env,
                                          ScrollState scrollState, FontResolver fonts, InteractionState interactionState) {
        return FrameRender.document(doc, rootW, rootH, env, scrollState, fonts, interactionState).items();
    }

    /** 文書を 1 度だけ組んだ不変の描画シーン（{@link FrameRender#document}）。 */
    public DisplayScene layoutDocument(Document doc, int rootW, int rootH, Map<String, String> env,
                                       ScrollState scrollState, FontResolver fonts) {
        return FrameRender.document(doc, rootW, rootH, env, scrollState, fonts, new InteractionState()).scene();
    }

    public DisplayScene layoutDocument(Document doc, int rootW, int rootH, Map<String, String> env,
                                       ScrollState scrollState, FontResolver fonts, InteractionState interactionState) {
        return FrameRender.document(doc, rootW, rootH, env, scrollState, fonts, interactionState).scene();
    }
}
