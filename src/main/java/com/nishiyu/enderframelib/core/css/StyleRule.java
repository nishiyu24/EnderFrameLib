package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.dom.HtmlNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 1 本のセレクタと、その宣言ブロック。
 * セレクタのパースと照合は {@link SelectorParser} / {@link SelectorMatcher} が受け持ち、
 * このクラスはルールとしての組み合わせ（宣言・詳細度・疑似クラス状態）を保持する。
 */
public class StyleRule {

    private Selector parsed;

    /** 疑似要素を取り除いたセレクタ本体のテキスト。 */
    public String selector;
    public Specificity specificity;

    public boolean isHover;
    public boolean isActive;
    public boolean isFocus;
    public boolean isBefore;
    public boolean isAfter;

    /** 宣言順を保つため LinkedHashMap。 */
    public Map<String, String> properties = new LinkedHashMap<>();

    public StyleRule(String selector) {
        this(SelectorParser.parse(selector, CascadeOrigin.AUTHOR, false));
    }

    public StyleRule(String selector, CascadeOrigin origin, boolean important) {
        this(SelectorParser.parse(selector, origin, important));
    }

    public StyleRule(Selector parsed) {
        this.parsed = parsed;
        this.selector = parsed.text();
        this.specificity = parsed.specificity();
        this.isHover = parsed.isHover();
        this.isActive = parsed.isActive();
        this.isFocus = parsed.isFocus();
        this.isBefore = parsed.isBefore();
        this.isAfter = parsed.isAfter();
    }

    public Selector parsedSelector() {
        return parsed;
    }

    /** オリジンを差し替える（UA シートに作者シートを重ねるときに使う）。 */
    public void setOrigin(CascadeOrigin origin) {
        this.parsed = parsed.withOrigin(origin);
        this.specificity = parsed.specificity();
    }

    public boolean matches(HtmlNode node) {
        return SelectorMatcher.matches(parsed, node);
    }

    /** カスケード順（詳細度の弱い順）の比較子。 */
    public static int compareSpecificity(StyleRule a, StyleRule b) {
        return a.specificity.compareTo(b.specificity);
    }
}
