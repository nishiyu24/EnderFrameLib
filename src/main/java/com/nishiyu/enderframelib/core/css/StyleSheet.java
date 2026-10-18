package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.dom.HtmlNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * パース済みスタイルシートと、それを構成する型（ルール・詳細度・カスケードのオリジン）。
 */
public class StyleSheet {
    public List<StyleRule> rules = new ArrayList<>();
    public Map<String, String> keyframes = new HashMap<>();
    public Map<String, String> variables = new HashMap<>();

    /**
     * 1 本のセレクタと、その宣言ブロック。
     * セレクタのパースと照合は {@link SelectorParser} / {@link SelectorMatcher} が受け持ち、
     * このクラスはルールとしての組み合わせ（宣言・詳細度・疑似クラス状態）を保持する。
     */
    public static class StyleRule {

        private Selector parsed;

        /** 疑似要素を取り除いたセレクタ本体のテキスト。 */
        public String selector;
        public Specificity specificity;

        public boolean isHover;
        public boolean isActive;
        public boolean isFocus;
        public int stateMask;
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
            this.stateMask = parsed.stateMask();
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

        public boolean matches(HtmlNode node, SelectorMatcher.StateLookup stateLookup) {
            return SelectorMatcher.matches(parsed, node, stateLookup);
        }

        /** カスケード順（詳細度の弱い順）の比較子。 */
        public static int compareSpecificity(StyleRule a, StyleRule b) {
            return a.specificity.compareTo(b.specificity);
        }
    }

    /**
     * CSS の詳細度（Specificity）とカスケードの優先順位。
     *
     * 以前は「セレクタ文字列の文字数カウント」と {@code += 10000} のマジック定数だったため、
     * 桁溢れ（クラスが 10 個あると ID 1 個を超える）と {@code ::before} が class より強くなる
     * 逆転が起きていた。ここでは (origin, important, id, class, type) のタプルで比較する。
     *
     * 比較の強さは CSS のカスケード順に従い important → origin → id → class → type。
     */
    public record Specificity(CascadeOrigin origin, boolean important, int id, int clazz, int type)
            implements Comparable<Specificity> {

        /** セレクタ文字列から詳細度を計算する（オリジンは作者スタイル）。 */
        public static Specificity calculate(String selector) {
            return calculate(selector, CascadeOrigin.AUTHOR);
        }

        /** セレクタ文字列から詳細度を計算する。 */
        public static Specificity calculate(String selector, CascadeOrigin origin) {
            return SelectorParser.parse(selector, origin, false).specificity();
        }

        public Specificity withOrigin(CascadeOrigin newOrigin) {
            return new Specificity(newOrigin, important, id, clazz, type);
        }

        public Specificity withImportant(boolean newImportant) {
            return new Specificity(origin, newImportant, id, clazz, type);
        }

        @Override
        public int compareTo(Specificity other) {
            if (this.important != other.important) return this.important ? 1 : -1;
            if (this.origin != other.origin) {
                // 通常宣言では UA < Author < Inline の順だが、!important 同士ではカスケード順が反転し
                // UA の !important が作者の !important に優先する。
                return this.important
                        ? Integer.compare(other.origin.rank(), this.origin.rank())
                        : Integer.compare(this.origin.rank(), other.origin.rank());
            }
            if (this.id != other.id) return Integer.compare(this.id, other.id);
            if (this.clazz != other.clazz) return Integer.compare(this.clazz, other.clazz);
            return Integer.compare(this.type, other.type);
        }
    }

    /**
     * カスケードのオリジン。
     * 以前は {@code specificity += 10000} というマジック定数で表現されていたものを型にしたもの。
     * 宣言順が同じなら、オリジンの強い側が勝つ。
     */
    public enum CascadeOrigin {
        /** UA スタイルシート（ライブラリ組み込みの既定値）。最弱。 */
        USER_AGENT(0),
        /** 作者スタイルシート（HTML の {@code <style>} やテーマ）。 */
        AUTHOR(1),
        /** 要素の {@code style="..."} 属性。最強。 */
        INLINE(2);

        private final int rank;

        CascadeOrigin(int rank) {
            this.rank = rank;
        }

        public int rank() {
            return rank;
        }
    }
}