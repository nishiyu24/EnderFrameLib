package com.nishiyu.enderframelib.core.text;

import java.util.ArrayList;
import java.util.List;

public class TextMetrics {

    public static java.util.function.BiFunction<String, Float, Integer> clientWidthFunction = null;

    public static int getCharWidth(char c) {
        if (c == 'l' || c == 'i' || c == '!' || c == '.' || c == ':' || c == '|') return 3;
        else if (c == ' ') return 4;
        else if (c <= 0x00FF) return 7;
        else return 12;
    }

    public static int getDisplayWidth(String text, float scale) {
        if (text == null || text.isEmpty()) return 0;
        if (clientWidthFunction != null) return clientWidthFunction.apply(text, scale);

        int width = 0;
        for (int i = 0; i < text.length(); i++) width += getCharWidth(text.charAt(i));
        return (int) ((width + 2) * scale);
    }

    public static List<String> wrapText(String text, int maxW, float scale) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) return lines;
        if (maxW <= 0) {
            lines.add(text);
            return lines;
        }

        // ★ 日本語は句読点、英語は空白で分割する
        String[] tokens = text.split("(?<=\\s)|(?=\\s)|(?<=[、。！？.,])");
        StringBuilder currentLine = new StringBuilder();
        int currentW = 0;

        for (String token : tokens) {
            int tokenW = getDisplayWidth(token, scale);

            // ★ 単語が長すぎても絶対に強制分割(break-all)しない。はみ出しを許容して丸ごと配置する。
            if (currentW + tokenW > maxW && !currentLine.isEmpty() && !token.trim().isEmpty()) {
                lines.add(currentLine.toString().trim());
                currentLine = new StringBuilder();
                currentW = 0;
            }
            currentLine.append(token);
            currentW += tokenW;
        }
        if (!currentLine.isEmpty()) lines.add(currentLine.toString().trim());
        return lines;
    }
}