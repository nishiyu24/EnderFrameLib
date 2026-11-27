package com.nishiyu.enderframelib.core.dom.parser;

import java.util.Set;

/** ツリー構築（{@link TreeBuilder}）の規則が引く要素名の集合（WHATWG HTML §13.2.4〜§13.2.6）。 */
final class TagSets {

    private TagSets() {}

    /** 特殊な要素（§13.2.4.2）。終了タグの一般規則はこれを越えて閉じない。 */
    static final Set<String> SPECIAL = Set.of(
            "address", "applet", "area", "article", "aside", "base", "basefont", "bgsound", "blockquote",
            "body", "br", "button", "caption", "center", "col", "colgroup", "dd", "details", "dir", "div",
            "dl", "dt", "embed", "fieldset", "figcaption", "figure", "footer", "form", "frame", "frameset",
            "h1", "h2", "h3", "h4", "h5", "h6", "head", "header", "hgroup", "hr", "html", "iframe", "img",
            "input", "keygen", "li", "link", "listing", "main", "marquee", "menu", "meta", "nav", "noembed",
            "noframes", "noscript", "object", "ol", "p", "param", "plaintext", "pre", "script", "search",
            "section", "select", "source", "style", "summary", "table", "tbody", "td", "template", "textarea",
            "tfoot", "th", "thead", "title", "tr", "track", "ul", "wbr", "xmp");

    /** 開始タグが開いている {@code p} を閉じる要素（in body の規則）。 */
    static final Set<String> CLOSES_P = Set.of(
            "address", "article", "aside", "blockquote", "center", "details", "dialog", "dir", "div", "dl",
            "fieldset", "figcaption", "figure", "footer", "header", "hgroup", "main", "menu", "nav", "ol", "p",
            "search", "section", "summary", "ul", "pre", "listing", "form", "table", "hr", "xmp");

    /** 終了タグがスコープ内の同名の要素まで閉じるブロック（in body の規則）。 */
    static final Set<String> BLOCK_END_TAGS = Set.of(
            "address", "article", "aside", "blockquote", "button", "center", "details", "dialog", "dir", "div",
            "dl", "fieldset", "figcaption", "figure", "footer", "header", "hgroup", "listing", "main", "menu",
            "nav", "ol", "pre", "search", "section", "select", "summary", "ul", "form");

    static final Set<String> HEADINGS = Set.of("h1", "h2", "h3", "h4", "h5", "h6");

    /** {@code head} に入る要素（in head の規則）。本文の中に書かれたらその場に置く。 */
    static final Set<String> HEAD_CONTENT = Set.of(
            "base", "basefont", "bgsound", "link", "meta", "noframes", "script", "style", "template", "title");

    /** 暗黙に終了タグを生成する要素（§13.2.6.3）。 */
    static final Set<String> IMPLIED_END = Set.of("dd", "dt", "li", "optgroup", "option", "p", "rb", "rp", "rt", "rtc");

    /** 既定のスコープの境界（§13.2.4.2）。 */
    static final Set<String> SCOPE_BOUNDARY = Set.of("applet", "caption", "html", "table", "td", "th", "marquee", "object", "template");

    static final Set<String> TABLE_SECTIONS = Set.of("tbody", "thead", "tfoot");
    static final Set<String> TABLE_PARTS = Set.of("caption", "col", "colgroup", "tbody", "td", "tfoot", "th", "thead", "tr");
    /** これが現在のノードのとき、表の中の文字と要素は表の前へ出す（foster parenting）。 */
    static final Set<String> FOSTER_TARGETS = Set.of("table", "tbody", "tfoot", "thead", "tr");

    /** マーカーを置く要素。これが閉じるとマーカー以降の書式要素の項目を捨てる（§13.2.4.3）。 */
    static final Set<String> MARKER_ELEMENTS = Set.of("applet", "caption", "marquee", "object", "td", "th", "template");

    /** 中の空白を原文のまま持つ要素。 */
    static final Set<String> PRESERVE_WHITESPACE_TAGS = Set.of("textarea", "pre", "code", "script", "style");
}
