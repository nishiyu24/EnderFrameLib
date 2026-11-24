package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.api.WebDocument;
import com.nishiyu.enderframelib.core.css.StyleSheet;
import com.nishiyu.enderframelib.core.dom.node.HtmlDocument;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public class Document implements WebDocument {
    public HtmlNode root;
    public StyleSheet sheet;
    public String authorCss;

    public Document(HtmlNode root, StyleSheet sheet, String authorCss) {
        this.root = root;
        this.sheet = sheet;
        this.authorCss = authorCss;
        syncSheet();
    }

    /** {@link #sheet} を文書の木（インライン svg の描画が読む）へ反映する。シートを差し替えたら呼ぶ。 */
    public void syncSheet() {
        if (root instanceof HtmlDocument hd) hd.authorSheet = sheet;
    }

    /** {@link #sheet} を作ったときに取り込んだ、採用規則の版（{@link com.nishiyu.enderframelib.core.dom.node.AdoptedStyleRules#version()}）。 */
    private int adoptedRulesSeen;

    /** シートを作る元の CSS: 読み込んだ作者スタイル＋Java から採用した規則（{@link HtmlDocument#adoptStyleRules}）。 */
    String effectiveCss() {
        String base = authorCss != null ? authorCss : "";
        if (!(root instanceof HtmlDocument hd)) return base;
        adoptedRulesSeen = hd.adoptedRules().version();
        String adopted = hd.adoptedRules().css();
        return adopted.isEmpty() ? base : base + "\n" + adopted;
    }

    /** シートを作り直す（読み込んだ作者スタイル＋Java から採用した規則。{@code dppx} は解像度のメディア特性）。 */
    public void rebuildSheet(int width, int height, float dppx) {
        sheet = new UiDocumentCompiler().buildStyleSheet(effectiveCss(), width, height);
        sheet.updateMediaEnvironment(sheet.mediaEnvironment().withResolution(dppx));
        syncSheet();
    }

    /** Java から規則が採用され、{@link #sheet} が古くなったら true。 */
    public boolean adoptedRulesChanged() {
        return root instanceof HtmlDocument hd && hd.adoptedRules().version() != adoptedRulesSeen;
    }

    @Override
    public String authorCss() {
        return authorCss != null ? authorCss : "";
    }

    @Override
    public com.nishiyu.enderframelib.api.dom.Document dom() {
        return root instanceof com.nishiyu.enderframelib.api.dom.Document d ? d : null;
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
