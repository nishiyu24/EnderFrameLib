package com.nishiyu.enderframelib.core.text;

import com.nishiyu.enderframelib.core.text.FontFace.FontStyle;

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
}
