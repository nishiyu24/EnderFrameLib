package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutMetrics;
import com.nishiyu.enderframelib.core.layout.TextShaper.FontFace.FontStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 折り返し・空白処理・省略の唯一の実装。
 *
 * 何が壊れていたか: 折り返しが 2 箇所に別々に書かれていた
 * （{@code LayoutMetrics.wrapText} は余裕なし、
 *  {@code BlockLayoutEngine.breakIntoLines} は 4px の余裕つき）ため、
 * 同じテキストがインライン整形に参加するかどうかで行数が変わっていた。
 * さらに描画側が「幅の 1.3 倍 + 4px」という 3 つめの基準で省略を判定していたので、
 * レイアウトが予約した行数と実際に見える行数が一致しなかった。
 *
 * ここが決めるのは「どこで行が分かれるか」「各行の文字列・幅・ベースライン」だけで、
 * 位置合わせ（text-align）と丸めは呼び出し側の仕事。
 */
public final class TextShaper {

    /** 空白の扱い。CSS の {@code white-space} に対応する。 */
    public enum WhiteSpace {
        NORMAL, NOWRAP, PRE, PRE_WRAP, PRE_LINE;

        /** 連続した空白を 1 つに畳むか。 */
        public boolean collapses() {
            return this == NORMAL || this == NOWRAP || this == PRE_LINE;
        }

        /** 行の折り返しをするか。 */
        public boolean wraps() {
            return this == NORMAL || this == PRE_WRAP || this == PRE_LINE;
        }

        /** 原文の改行を行分割として残すか。 */
        public boolean preservesNewlines() {
            return this == PRE || this == PRE_WRAP || this == PRE_LINE;
        }

        public static WhiteSpace of(String keyword) {
            if (keyword == null) return NORMAL;
            return switch (keyword.trim().toLowerCase()) {
                case "nowrap" -> NOWRAP;
                case "pre" -> PRE;
                case "pre-wrap" -> PRE_WRAP;
                case "pre-line" -> PRE_LINE;
                default -> NORMAL;
            };
        }
    }

    /** 語の内側で分割してよいか。CSS の {@code word-break} に対応する。 */
    public enum WordBreak {
        NORMAL, BREAK_ALL, KEEP_ALL;

        public static WordBreak of(String keyword) {
            if (keyword == null) return NORMAL;
            return switch (keyword.trim().toLowerCase()) {
                case "break-all" -> BREAK_ALL;
                case "keep-all" -> KEEP_ALL;
                default -> NORMAL;
            };
        }
    }

    /**
     * 整形に必要なスタイル。
     *
     * @param breakLongWords 1 行に収まらない語を内側で割るか（{@code overflow-wrap: break-word}）
     * @param ellipsis       折り返さない行があふれたら省略記号で切るか（{@code text-overflow: ellipsis}）
     * @param lineHeightPx   1 行が占める高さ（px）。{@code normal} の解決は呼び出し側が済ませる
     */
    public record TextStyle(FontStyle font, WhiteSpace whiteSpace, WordBreak wordBreak,
                            boolean breakLongWords, boolean ellipsis, float lineHeightPx) {}

    /**
     * 整形結果の 1 行。
     *
     * @param text       実際に描く文字列（省略記号も含めて確定済み）
     * @param width      {@code text} の送り幅（px）
     * @param baseline   行の上端からベースラインまでの距離（px）
     * @param height     行が占める高さ（px）
     * @param start      正規化後テキストにおける開始位置（char 単位）
     * @param end        同じく終了位置。末尾の空白は含まない
     * @param ellipsized 省略記号で切られたか
     */
    public record ShapedLine(String text, float width, float baseline, float height,
                             int start, int end, boolean ellipsized) {}

    /** 省略記号。Minecraft の既定フォントで必ず出せる形を使う。 */
    private static final String ELLIPSIS = "...";

    private static final Pattern LINE_BREAKS = Pattern.compile("[\\r\\n]+");
    private static final Pattern BLANK_RUN = Pattern.compile("[ \\t]+");
    /** タブは Minecraft のフォントで描けないので空白に開く。 */
    private static final String TAB_SPACES = "    ";

    /** 行頭に置けない文字（行頭禁則）。 */
    private static final String NO_BREAK_BEFORE =
            "、。，．！？：；」』）］｝〉》・ーぁぃぅぇぉっゃゅょゎヵヶ々ゝゞ.,!?:;)]}>%";
    /** 行末に置けない文字（行末禁則）。 */
    private static final String NO_BREAK_AFTER = "「『（［｛〈《([{<$¥";

    private TextShaper() {}

    // ==========================================
    // 空白の正規化
    // ==========================================

    /** {@code white-space} に従ってテキストを整える。以降の処理はこの結果だけを見る。 */
    public static String normalize(String text, WhiteSpace whiteSpace) {
        if (text == null || text.isEmpty()) return "";
        String result = text.replace("\r\n", "\n").replace('\r', '\n');
        if (!whiteSpace.preservesNewlines()) {
            result = LINE_BREAKS.matcher(result).replaceAll(" ");
        }
        result = result.replace("\t", TAB_SPACES);
        if (whiteSpace.collapses()) {
            result = BLANK_RUN.matcher(result).replaceAll(" ");
        }
        return result;
    }

    // ==========================================
    // 折り返し候補
    // ==========================================

    /**
     * 折り返し候補で区切った断片。末尾の空白は直前の断片に付く。
     *
     * インライン整形も同じ規則で単語を切り出すため公開している。
     * 以前は正規表現 1 本（空白と和文の句読点のみ）だったので、
     * 句読点を含まない日本語の長文がまったく折り返されなかった。
     */
    public static List<String> segments(String text, WordBreak wordBreak) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;

        int start = 0;
        for (int i = 1; i < text.length(); i++) {
            if (canBreakBefore(text, i, wordBreak)) {
                out.add(text.substring(start, i));
                start = i;
            }
        }
        out.add(text.substring(start));
        return out;
    }

    private static boolean canBreakBefore(String text, int index, WordBreak wordBreak) {
        char prev = text.charAt(index - 1);
        char cur = text.charAt(index);

        if (cur == '\n') return false;
        // 空白の直後で切る。空白の手前では切らない（末尾の空白は前の行にぶら下げる）
        if (isBlank(prev)) return !isBlank(cur);
        if (isBlank(cur)) return false;

        if (wordBreak == WordBreak.BREAK_ALL) return true;
        if (wordBreak == WordBreak.KEEP_ALL) return prev == '-';

        if (isIdeographic(prev) || isIdeographic(cur)) {
            return NO_BREAK_BEFORE.indexOf(cur) < 0 && NO_BREAK_AFTER.indexOf(prev) < 0;
        }
        // 欧文は空白とハイフンだけが候補
        return prev == '-';
    }

    private static boolean isBlank(char c) {
        return c == ' ' || c == '\t';
    }

    /** 表意文字・仮名・全角記号。語の内側でも分割できる文字。 */
    private static boolean isIdeographic(char c) {
        return (c >= 0x3000 && c <= 0x30FF)    // CJK 記号・ひらがな・カタカナ
                || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0x4E00 && c <= 0x9FFF)  // CJK 統合漢字
                || (c >= 0xF900 && c <= 0xFAFF)
                || (c >= 0xFF00 && c <= 0xFF60); // 全角英数・記号
    }

    // ==========================================
    // 整形
    // ==========================================

    /**
     * テキストを行に分ける。
     *
     * @param availableWidth 使える幅（px）。{@code 0} 以下は「制約なし」
     */
    public static List<ShapedLine> shape(String text, FontFace face, TextStyle style, float availableWidth) {
        Shaping shaping = new Shaping(face, style, availableWidth);
        String normalized = normalize(text, style.whiteSpace());
        if (normalized.isEmpty()) return shaping.lines;

        int paragraphStart = 0;
        while (true) {
            int newline = normalized.indexOf('\n', paragraphStart);
            int paragraphEnd = newline < 0 ? normalized.length() : newline;
            shaping.shapeParagraph(normalized, paragraphStart, paragraphEnd);
            if (newline < 0) break;
            paragraphStart = newline + 1;
        }
        return shaping.lines;
    }

    /** 1 行に収まるよう末尾を省略記号で切る。 */
    public static String ellipsize(String text, FontFace face, FontStyle font, float maxWidth) {
        if (text == null || text.isEmpty()) return "";
        if (maxWidth <= 0 || face.advance(text, font) <= maxWidth) return text;

        float ellipsisW = face.advance(ELLIPSIS, font);
        if (ellipsisW >= maxWidth) return text.substring(0, face.fitChars(text, font, maxWidth));
        int fit = face.fitChars(text, font, maxWidth - ellipsisW);
        return text.substring(0, fit) + ELLIPSIS;
    }

    /** 1 回の整形の作業状態。行を跨いで持ち回る値をまとめている。 */
    private static final class Shaping {
        final List<ShapedLine> lines = new ArrayList<>();
        final FontFace face;
        final TextStyle style;
        final float available;
        final boolean bounded;

        final StringBuilder line = new StringBuilder();
        float lineWidth = 0;
        int lineStart = 0;

        Shaping(FontFace face, TextStyle style, float availableWidth) {
            this.face = face;
            this.style = style;
            this.available = availableWidth;
            this.bounded = availableWidth > 0 && style.whiteSpace().wraps();
        }

        void shapeParagraph(String source, int from, int to) {
            lineStart = from;
            String paragraph = source.substring(from, to);

            if (!style.whiteSpace().wraps()) {
                String visible = style.ellipsis() && available > 0
                        ? ellipsize(paragraph, face, style.font(), available)
                        : paragraph;
                emit(visible, from, to, !visible.equals(paragraph));
                return;
            }

            int offset = from;
            for (String segment : segments(paragraph, style.wordBreak())) {
                append(segment, offset);
                offset += segment.length();
            }
            flush(to, true);
        }

        /** 断片を現在行に足す。入らなければ行を確定してから足す。 */
        void append(String segment, int offset) {
            float trimmedWidth = widthOf(stripTrailingBlanks(segment));
            if (bounded && lineWidth + trimmedWidth > available) {
                boolean breakAll = style.wordBreak() == WordBreak.BREAK_ALL;
                boolean tooLongAlone = trimmedWidth > available;
                boolean canSplit = breakAll || style.breakLongWords();

                if (canSplit && (breakAll || tooLongAlone)) {
                    if (!breakAll && lineWidth > 0) flush(offset, false);
                    splitInside(segment, offset);
                    return;
                }
                if (lineWidth > 0) flush(offset, false);
            }
            line.append(segment);
            lineWidth += widthOf(segment);
        }

        /** 1 行に収まらない語を文字単位で割る。 */
        void splitInside(String segment, int offset) {
            String rest = segment;
            int cursor = offset;
            while (!rest.isEmpty()) {
                int fit = face.fitChars(rest, style.font(), available - lineWidth);
                if (fit == 0) {
                    if (lineWidth > 0) {
                        flush(cursor, false);
                        continue;
                    }
                    // 1 文字分の幅も無いときでも必ず進める（無限ループを作らない）
                    fit = rest.offsetByCodePoints(0, 1);
                }
                String chunk = rest.substring(0, fit);
                line.append(chunk);
                lineWidth += widthOf(chunk);
                rest = rest.substring(fit);
                cursor += fit;
                if (!rest.isEmpty()) flush(cursor, false);
            }
        }

        /** 現在行を確定する。末尾の空白は幅に数えない（CSS と同じ）。 */
        void flush(int end, boolean force) {
            if (line.isEmpty() && !force) return;
            String text = stripTrailingBlanks(line.toString());
            emit(text, lineStart, lineStart + text.length(), false);
            line.setLength(0);
            lineWidth = 0;
            lineStart = end;
        }

        void emit(String text, int start, int end, boolean ellipsized) {
            float ascent = face.ascent(style.font());
            float descent = face.descent(style.font());
            float height = Math.max(style.lineHeightPx(), 0);
            // CSS の half-leading: 行高と文字の高さの差を上下に等分する
            float halfLeading = (height - (ascent + descent)) / 2f;
            lines.add(new ShapedLine(text, widthOf(text), halfLeading + ascent, height, start, end, ellipsized));
        }

        float widthOf(String text) {
            return text.isEmpty() ? 0 : face.advance(text, style.font());
        }
    }

    private static String stripTrailingBlanks(String text) {
        int end = text.length();
        while (end > 0 && isBlank(text.charAt(end - 1))) end--;
        return end == text.length() ? text : text.substring(0, end);
    }

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
}
