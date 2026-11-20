package com.nishiyu.enderframelib.api.spi;

import java.util.Map;

/**
 * 独自タグを素の HTML の断片へ展開する規則。文書の読み込み時に、タグ要素 1 つがこの断片の解析結果に置き換わる。
 * 内部の DOM 型に触れず、文字列だけで書く（属性値を断片へ差し込むときは {@link #escape} を使う）。
 */
@FunctionalInterface
public interface TagTemplate {

    /**
     * @param attributes 元の要素の属性（名前は小文字）
     * @param innerHtml  元の要素の中身の HTML
     * @return 置き換える HTML の断片。展開しない（元の要素のまま残す）なら {@code null}
     */
    String expand(Map<String, String> attributes, String innerHtml);

    /** 属性値・テキストへ差し込む文字列の HTML エスケープ。 */
    static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case (char) 39 -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
