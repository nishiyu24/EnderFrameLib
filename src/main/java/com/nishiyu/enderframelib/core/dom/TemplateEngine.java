package com.nishiyu.enderframelib.core.dom;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TemplateEngine {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{([^}]+)}}");

    public static Function<String, String> i18nResolver = key -> key;

    /**
     * テンプレート走査の共通実装。
     */
    public static void applyTemplate(HtmlNode node, Function<String, String> replacer) {
        if (node == null || replacer == null) return;

        if (node.text != null && node.text.contains("{{")) {
            node.text = replacer.apply(node.text);
        }

        for (String key : new ArrayList<>(node.attrs.keySet())) {
            String val = node.attrs.get(key);
            if (val != null && val.contains("{{")) {
                DomUtils.updateAttribute(node, key, replacer.apply(val), false);
            }
        }

        if (node.attrs.containsKey("id")) {
            node.setId(node.attrs.get("id"));
        }

        for (HtmlNode child : node.children) {
            applyTemplate(child, replacer);
        }
    }

    /**
     * i18n、ローカル環境変数（@key）、およびスコープデータ（key）の展開を一元的に処理します。
     */
    public static String resolve(String text, Map<String, String> data, Map<String, String> env) {
        if (text == null || !text.contains("{{")) return text != null ? text : "";

        Matcher m = PLACEHOLDER_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String expr = m.group(1).trim();
            String replacement = null;

            if (expr.startsWith("i18n:") || expr.startsWith("@i18n:")) {
                String i18nKey = expr.substring(expr.indexOf(':') + 1).trim();
                replacement = i18nResolver.apply(i18nKey);
            } else if (expr.contains("|") && expr.toLowerCase(Locale.ROOT).endsWith("i18n")) {
                String rawKey = expr.substring(0, expr.indexOf('|')).trim().replaceAll("^['\"]+|['\"]+$", "");
                String resolvedKey = data != null ? data.getOrDefault(rawKey, rawKey) : rawKey;
                replacement = i18nResolver.apply(resolvedKey);
            } else if (expr.startsWith("@")) {
                if (env != null && env.containsKey(expr)) {
                    replacement = env.get(expr);
                } else {
                    replacement = m.group(0);
                }
            } else {
                if (data != null && data.containsKey(expr)) {
                    replacement = data.get(expr);
                } else {
                    replacement = m.group(0);
                }
            }

            if (replacement == null) replacement = m.group(0);
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}