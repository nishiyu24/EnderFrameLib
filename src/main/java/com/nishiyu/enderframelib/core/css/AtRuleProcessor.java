package com.nishiyu.enderframelib.core.css;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AtRuleProcessor {
    private static final Pattern MAX_WIDTH_MEDIA_PATTERN = Pattern.compile("max-width:\\s*(\\d+)px", Pattern.CASE_INSENSITIVE);
    private static final Pattern MIN_WIDTH_MEDIA_PATTERN = Pattern.compile("min-width:\\s*(\\d+)px", Pattern.CASE_INSENSITIVE);

    public static String processAtRules(String css, int rootW) {
        StringBuilder sb = new StringBuilder();
        int i = 0, len = css.length();
        while (i < len) {
            char c = css.charAt(i);
            if (c == '@') {
                int blockStart = i;
                while (i < len && css.charAt(i) != '{' && css.charAt(i) != ';') i++;
                if (i >= len) break;

                if (css.charAt(i) == ';') {
                    sb.append(css, blockStart, i + 1);
                    i++;
                    continue;
                }

                String condition = css.substring(blockStart, i).trim();
                i++;
                int braceCount = 1, contentStart = i;
                while (i < len) {
                    char ac = css.charAt(i);
                    if (ac == '{') braceCount++;
                    else if (ac == '}') {
                        braceCount--;
                        if (braceCount == 0) break;
                    }
                    i++;
                }

                if (condition.startsWith("@media")) {
                    boolean isValid = evaluateMediaQuery(condition, rootW);
                    if (isValid && contentStart <= i && i <= len) {
                        sb.append(css, contentStart, i);
                    }
                } else if (contentStart <= i && i <= len) {
                    // media 以外の AtRule (例: @supports, @font-face などが残っていた場合) は保持
                    sb.append(css, blockStart, contentStart - 1);
                    sb.append("{").append(css, contentStart, i).append("}");
                }
                i++;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    private static boolean evaluateMediaQuery(String condition, int rootW) {
        boolean conditionMet = true; // AND評価のベース

        Matcher mMax = MAX_WIDTH_MEDIA_PATTERN.matcher(condition);
        if (mMax.find()) {
            int maxWidth = Integer.parseInt(mMax.group(1));
            if (rootW > maxWidth) conditionMet = false;
        }

        Matcher mMin = MIN_WIDTH_MEDIA_PATTERN.matcher(condition);
        if (mMin.find()) {
            int minWidth = Integer.parseInt(mMin.group(1));
            if (rootW < minWidth) conditionMet = false;
        }

        return conditionMet;
    }
}