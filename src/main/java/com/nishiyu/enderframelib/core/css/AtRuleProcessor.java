package com.nishiyu.enderframelib.core.css;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code @media} の条件式の評価。
 *
 * ブロックの切り出し自体は {@link CssTokenizer} が行うため、以前の
 * 「@media の中身を文字列として平坦化してから正規表現で再マッチする」処理は不要になった。
 * ここに残るのは条件の真偽判定だけ。
 *
 * 評価規則は CSS に従い、1 つのクエリ内の {@code and} は AND、
 * クエリリストの {@code ,} は OR。
 */
public final class AtRuleProcessor {

    private static final Pattern MAX_WIDTH_MEDIA_PATTERN =
            Pattern.compile("max-width:\\s*(\\d{1,7})px", Pattern.CASE_INSENSITIVE);
    private static final Pattern MIN_WIDTH_MEDIA_PATTERN =
            Pattern.compile("min-width:\\s*(\\d{1,7})px", Pattern.CASE_INSENSITIVE);

    private AtRuleProcessor() {}

    /**
     * メディアクエリリストを評価する。
     *
     * @param condition {@code @media} を除いた条件部（{@code "(min-width: 500px) and (max-width: 1000px)"} など）
     * @param rootW     現在のビューポート幅
     */
    public static boolean evaluateMediaQuery(String condition, int rootW) {
        if (condition == null) return true;
        String cond = condition.trim();
        if (cond.toLowerCase().startsWith("@media")) cond = cond.substring("@media".length()).trim();
        if (cond.isEmpty()) return true;

        // クエリリストのカンマは OR
        for (String query : CssTokenizer.splitSelectorList(cond)) {
            if (evaluateSingleQuery(query, rootW)) return true;
        }
        return false;
    }

    /** 1 つのメディアクエリ内の条件は AND で評価する。 */
    private static boolean evaluateSingleQuery(String query, int rootW) {
        boolean negated = query.trim().toLowerCase().startsWith("not ");
        boolean result = true;

        Matcher mMax = MAX_WIDTH_MEDIA_PATTERN.matcher(query);
        while (mMax.find()) {
            if (rootW > parseWidth(mMax.group(1))) result = false;
        }

        Matcher mMin = MIN_WIDTH_MEDIA_PATTERN.matcher(query);
        while (mMin.find()) {
            if (rootW < parseWidth(mMin.group(1))) result = false;
        }

        return negated != result;
    }

    /** 桁数は正規表現側で 7 桁に制限しているため、ここでの失敗は「条件なし」として扱う。 */
    private static int parseWidth(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }
}
