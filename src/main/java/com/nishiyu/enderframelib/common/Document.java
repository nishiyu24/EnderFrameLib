package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.api.WebDocument;
import com.nishiyu.enderframelib.core.css.StyleSheet;
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
