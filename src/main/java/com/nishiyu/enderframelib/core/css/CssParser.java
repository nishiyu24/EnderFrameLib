package com.nishiyu.enderframelib.core.css;

import java.util.function.Consumer;

/**
 * CSS テキストから {@link StyleSheet} を組み立てる入口。
 *
 * 実際の処理は {@link CssTokenizer}（切り出し）→ {@link CssRuleParser}（積み上げ）→
 * {@link SelectorParser}（セレクタ）に分かれていて、このクラスはその組み立てだけを行う。
 */
public final class CssParser {

    /** {@code @font-face} を含む CSS を受け取ってフォントを登録するフック（クライアント側から注入）。 */
    public static Consumer<String> fontFaceCallback = null;

    private CssParser() {}

    /** 作者スタイルシートとしてパースする。 */
    public static StyleSheet parse(String css, int rootW) {
        return parse(css, rootW, CascadeOrigin.AUTHOR);
    }

    public static StyleSheet parse(String css, int rootW, CascadeOrigin origin) {
        StyleSheet sheet = new StyleSheet();
        if (css == null || css.isEmpty()) return sheet;

        // @font-face はフック側が CSS 全体を見て解釈する（src の URL 解決などが必要なため）
        if (fontFaceCallback != null && css.toLowerCase().contains("@font-face")) {
            fontFaceCallback.accept(css);
        }

        CssRuleParser.parseInto(css, sheet, rootW, origin);

        // 安定ソートなので、詳細度が同じルールは記述順（後勝ち）が保たれる
        sheet.rules.sort(StyleRule::compareSpecificity);
        return sheet;
    }
}
