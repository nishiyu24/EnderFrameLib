package com.nishiyu.enderframelib.core.text;

/**
 * 1 つの書体。幅とベースライン方向の指標を px で返す。
 *
 * 何が壊れていたか: 旧 {@code FontMetrics} は幅を {@code int} で 1 つ返すだけで、
 * ascent / descent が無かったためベースライン揃えが原理的に不可能で、
 * 描画側は「8px の ascent 箱」を仮定した行内センタリングでごまかしていた。
 * また幅を切り上げて返していたので、連結した文字列の幅と
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
            // 送り幅は必ず加法的にする（部分文字列の幅の合計 == 全体の幅）。
            // 以前は一律 +2 していたため、整形器が断片ごとに測った合計と
            // 1 行まとめて測った幅が食い違い、折り返し位置が 1 語ずれた。
            return width;
        }
    };

    /**
     * 1 回の測定を決める書体の指定。
     *
     * 何が壊れていたか: 以前の測定インターフェースは {@code (String, float scale, boolean isBold)}
     * だけを取っていたため、字送り（letter-spacing）と斜体が測定に入らず、
     * 「倍率」で渡されるフォントサイズは基準 16px という暗黙の約束に依存していた。
     * ここでは px そのものを運ぶ。
     */
    public record FontStyle(float fontSizePx, boolean bold, boolean italic, float letterSpacingPx) {

        public static final FontStyle DEFAULT = new FontStyle(16.0f, false, false, 0.0f);

        public FontStyle withSize(float px) {
            return new FontStyle(px, bold, italic, letterSpacingPx);
        }

        public FontStyle withBold(boolean isBold) {
            return new FontStyle(fontSizePx, isBold, italic, letterSpacingPx);
        }
    }

    /**
     * {@code font-family} から書体を引く。
     *
     * 何が壊れていたか: レイアウトは常に Minecraft の既定フォントで測るのに、
     * 描画は {@code font-family} で別のフォントを選んでいたため、
     * {@code @font-face} を当てたテキストは折り返し・中央寄せ・省略が全部ずれていた。
     * 測定も描画もこの 1 本を通す。
     */
    public interface FontResolver {

        /** {@code null} や未知のファミリ名には既定の書体を返す（例外にしない）。 */
        FontFace resolve(String fontFamily);

        /** ヘッドレステスト用。常に {@link FontFace#FALLBACK}。 */
        FontResolver FALLBACK = fontFamily -> FontFace.FALLBACK;
    }

    /**
     * ビットマップフォント用の {@link FontFace} の骨組み。
     * 実装側に残るのは「素のサイズでの送り幅」だけで、
     * px への換算・ベースライン・行送りはここが一手に決める。
     *
     * Minecraft のフォントは素のサイズで ascent 7px / descent 2px（行高 9px）。
     * これを 1em = 9px と見なすと、{@code font-size: 16px} のときの
     * キャップハイト 12.4px・x ハイト 8.9px が、ブラウザで一般的な UI フォント
     * （16px でそれぞれ約 11.5px / 8.3px）とほぼ重なる。
     * 以前は 1em = 12px 相当（測定の {@code 16/12} と描画の {@code /12.0f}）で、
     * 同じ {@code font-size} でもブラウザより 3 割小さく出ていた。
     *
     * LIMITATION(制約): 合わせられるのは文字の見かけの大きさと行送りまで。
     * グリフの形と 1 文字あたりの送り幅はビットマップフォント固有なので、
     * 折り返し位置・省略位置・中央寄せの offset はブラウザと一致しない
     * （README の "Glyph-level fidelity" と FIDELITY.md の「意図的に違うもの」）。
     */
    public static abstract class BitmapFontFace implements FontFace {

        /** 1em に相当する素のサイズ（px）。 */
        public static final float MC_UNITS_PER_EM = 9.0f;
        /** 素のサイズでのベースライン上の高さ（px）。 */
        public static final float MC_ASCENT = 7.0f;
        /** 素のサイズでのベースライン下の高さ（px）。 */
        public static final float MC_DESCENT = 2.0f;
        /**
         * 行間（em 比）。
         * Minecraft のフォント自体は行間を持たない（行高 = ascent + descent）が、
         * それだと {@code line-height: normal} が 1.0 になりブラウザ（1.15 前後）より詰まる。
         * UA 水準の行間はここで与える。
         */
        public static final float MC_LINE_GAP_RATIO = 0.15f;

        private final float unitsPerEm;
        private final float naturalAscent;
        private final float naturalDescent;
        private final float lineGapRatio;

        protected BitmapFontFace() {
            this(MC_UNITS_PER_EM, MC_ASCENT, MC_DESCENT, MC_LINE_GAP_RATIO);
        }

        protected BitmapFontFace(float unitsPerEm, float naturalAscent, float naturalDescent, float lineGapRatio) {
            this.unitsPerEm = unitsPerEm;
            this.naturalAscent = naturalAscent;
            this.naturalDescent = naturalDescent;
            this.lineGapRatio = lineGapRatio;
        }

        /**
         * 素のサイズでの送り幅。太字の表現方法（Minecraft では {@code §l} の前置）は
         * 実装の内側に閉じる。
         */
        protected abstract float naturalAdvance(String text, boolean bold);

        /** {@code font-size} に対応する素のサイズからの倍率。描画側も同じ値を使う。 */
        public float scaleFor(FontStyle style) {
            return style.fontSizePx() / unitsPerEm;
        }

        @Override
        public float advance(String text, FontStyle style) {
            if (text == null || text.isEmpty()) return 0;
            float advance = naturalAdvance(text, style.bold()) * scaleFor(style);
            if (style.letterSpacingPx() != 0) advance += style.letterSpacingPx() * glyphCount(text);
            return advance;
        }

        @Override
        public float ascent(FontStyle style) {
            return naturalAscent * scaleFor(style);
        }

        @Override
        public float descent(FontStyle style) {
            return naturalDescent * scaleFor(style);
        }

        @Override
        public float lineGap(FontStyle style) {
            return lineGapRatio * style.fontSizePx();
        }
    }
}
