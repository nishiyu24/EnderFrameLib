package com.nishiyu.enderframelib.core.text;

public interface FontMetrics {
    int getDisplayWidth(String text, float scale, boolean isBold);

    // 純Javaで動作するテスト・フォールバック用の実装
    FontMetrics FALLBACK = (text, scale, isBold) -> {
        if (text == null || text.isEmpty()) return 0;
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 'l' || c == 'i' || c == '!' || c == '.' || c == ':' || c == '|') width += 3;
            else if (c == ' ') width += 4;
            else if (c <= 0x00FF) width += 7;
            else width += 12;
        }
        if (isBold) width += text.length(); // 太字は少し広く
        return (int) ((width + 2) * scale);
    };
}