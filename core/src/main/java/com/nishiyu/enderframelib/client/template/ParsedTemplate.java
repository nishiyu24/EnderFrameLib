package com.nishiyu.enderframelib.client.template;

import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.DocumentLoader;
import com.nishiyu.enderframelib.core.css.StyleSheet;
import com.nishiyu.enderframelib.core.dom.node.HtmlDocument;
import com.nishiyu.enderframelib.core.dom.node.Ownership;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * パース済みテンプレート。DOM ノードツリーのクローン元と抽出済み CSS を保持します。
 */
public class ParsedTemplate {
    private final String templateId;
    private final String rawHtml;
    private final HtmlDocument templateDocument;
    private final String authorCss;

    public ParsedTemplate(@NotNull String templateId, @NotNull String rawHtml,
                          @NotNull HtmlDocument templateDocument, @NotNull String authorCss) {
        this.templateId = Objects.requireNonNull(templateId, "templateId cannot be null");
        this.rawHtml = Objects.requireNonNull(rawHtml, "rawHtml cannot be null");
        this.templateDocument = Objects.requireNonNull(templateDocument, "templateDocument cannot be null");
        this.authorCss = authorCss != null ? authorCss : "";
    }

    public @NotNull String templateId() {
        return templateId;
    }

    public @NotNull String rawHtml() {
        return rawHtml;
    }

    public @NotNull String authorCss() {
        return authorCss;
    }

    /**
     * クローンしたクライアントローカルな {@link HtmlDocument} を生成します。
     * 部分木所有権モデルにおいて、すべてのノードはクライアント所有（{@code data-owner="client"}）となります。
     */
    public @NotNull HtmlDocument cloneDocument() {
        HtmlDocument cloned = (HtmlDocument) templateDocument.cloneNode(true);
        cloned.setDefaultOwnership(Ownership.CLIENT);
        cloned.setAttribute(Ownership.ATTRIBUTE, "client");
        return cloned;
    }

    /**
     * 指定された幅・高さでスタイルシートを解決し、新しい {@link Document} を構築します。
     */
    public @NotNull Document instantiate(int width, int height) {
        HtmlDocument doc = cloneDocument();
        int targetW = width > 0 ? width : 854;
        int targetH = height > 0 ? height : 480;
        StyleSheet sheet = DocumentLoader.buildStyleSheet(authorCss, targetW, targetH);
        return new Document(doc, sheet, authorCss);
    }
}
