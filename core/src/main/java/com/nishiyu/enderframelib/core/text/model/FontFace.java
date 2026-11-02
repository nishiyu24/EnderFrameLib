package com.nishiyu.enderframelib.core.text.model;

import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;

/**
 * 測定と描画が共有する書体のモデル。
 *
 * 部分文字列の幅の合計が一致しなかった（折り返し位置が描画とずれる原因）。
 *
 * 実装は丸めない。丸めるのは描画の最終段だけ。
 */
public interface FontFace {

    /** テキストの送り幅（px）。字送りを含む。 */
    float advance(String text, FontStyle style);

    /** ベースラインから上の高さ（px）。 */
    float ascent(FontStyle style);

    /** ベースラインから下の高さ（px）。 */
    float descent(FontStyle style);

    /** 行と行の間に入る余白（px）。{@code line-height: normal} はこれを含む。 */
    float lineGap(FontStyle style);

    /**
     * x ハイト（px）。{@code vertical-align: middle} の基準。
     * 既定は ascent の 5/7 で、Minecraft のフォント（ascent 7 / 小文字の高さ 5）と一致する。
     */
    default float xHeight(FontStyle style) {
        return ascent(style) * (5f / 7f);
    }

    /** 文字数（コードポイント単位）。省略位置とキャレット位置の決定に使う。 */
    default int glyphCount(String text) {
        return text == null || text.isEmpty() ? 0 : text.codePointCount(0, text.length());
    }

    /** {@code line-height: normal} が予約する 1 行の高さ（px）。 */
    default float normalLineHeight(FontStyle style) {
        return ascent(style) + descent(style) + lineGap(style);
    }

    /**
     * 先頭から {@code maxWidth} に収まる最長の部分文字列の長さ（char 単位）。
     * 戻り値は必ずコードポイント境界なので、そのまま {@code substring} に渡せる。
     */
    default int fitChars(String text, FontStyle style, float maxWidth) {
        if (text == null || text.isEmpty() || maxWidth <= 0) return 0;
        float used = 0;
        int i = 0;
        while (i < text.length()) {
            int next = text.offsetByCodePoints(i, 1);
            float w = advance(text.substring(i, next), style);
            if (used + w > maxWidth) return i;
            used += w;
            i = next;
        }
        return text.length();
    }

    /** ヘッドレステストと Minecraft 不在時のための実装。 */
    FontFace FALLBACK = new BitmapFontFace() {
        @Override
        protected float naturalAdvance(String text, boolean bold) {
            if (text == null || text.isEmpty()) return 0;
            int width = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == 'l' || c == 'i' || c == '!' || c == '.' || c == ':' || c == '|') width += 3;
                else if (c == ' ') width += 4;
                else if (c <= 0x00FF) width += 7;
                else width += 12;
            }
            if (bold) width += text.length(); // 太字は少し広く
            return width;
        }
    };

}
