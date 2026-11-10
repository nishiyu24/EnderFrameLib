package com.nishiyu.enderframelib.core.dom.node;

import java.util.List;

/**
 * ホストが宣言するカスタムタグの展開規則。タグ要素 1 つを、素の HTML 要素の列（部分木）へ置き換える。
 * 展開しない（元の要素のまま残す）なら null を返す。
 */
@FunctionalInterface
public interface TagMacro {
    List<HtmlNode> expand(HtmlNode source);
}
