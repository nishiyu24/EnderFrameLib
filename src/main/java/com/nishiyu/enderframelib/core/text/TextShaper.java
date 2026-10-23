package com.nishiyu.enderframelib.core.text;

import com.nishiyu.enderframelib.core.text.model.*;

import com.nishiyu.enderframelib.core.text.model.FontStyle;

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

    /**
     * 折り返し判定で「入る」とみなす誤差（Chrome の LayoutUnit の 1 単位 = 1/64px）。
     *
     * 何が壊れていたか: 語ごとの送り幅を float で足した値が、全文を 1 回で測った max-content を
     * 1e-5px ほど上回り、内容幅ちょうどの箱（flex アイテムなど）で最後の語が次の行へ落ちていた。
     * Chrome は幅を 1/64px 単位で持つのでこの差は生じない。
     */
    public static final float WRAP_TOLERANCE = 1f / 64f;

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

    /**
     * {@code before} の末尾と {@code after} の先頭が並んだときの詰め（カーニング）。続けて測った幅と
     * 別々に測った幅の和の差で、詰めるなら負。境目の 2 文字だけを測るので、行の長さによらず一定の手間で済む。
     */
    public static float kerningBetween(FontFace face, FontStyle font, CharSequence before, CharSequence after) {
        if (before.isEmpty() || after.isEmpty()) return 0;
        String last = new StringBuilder().appendCodePoint(Character.codePointBefore(before, before.length())).toString();
        String first = new StringBuilder().appendCodePoint(Character.codePointAt(after, 0)).toString();
        return face.advance(last + first, font) - face.advance(last, font) - face.advance(first, font);
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

        /**
         * 断片を現在行に足す。入らなければ行を確定してから足す。
         *
         * 入るかどうかは、断片の幅の和に境目の詰め（{@link #kerningBetween}）を足した幅、つまり行を 1 回で
         * 測った幅（= max-content の測り方）で判定する。
         * 何が壊れていたか: 断片ごとの幅の和だけで判定していたため、境目に詰め（{@code -} と {@code v} など）が
         * あると和の方が max-content より広くなり、内容幅ちょうどの flex アイテムで
         * {@code two-viewers} が {@code two-} の後で折り返していた（実機で確認）。
         */
        void append(String segment, int offset) {
            float trimmedWidth = widthOf(stripTrailingBlanks(segment));
            float join = line.isEmpty() ? 0 : kerningBetween(face, style.font(), line, segment);
            if (bounded && lineWidth + join + trimmedWidth > available + WRAP_TOLERANCE) {
                boolean breakAll = style.wordBreak() == WordBreak.BREAK_ALL;
                boolean tooLongAlone = trimmedWidth > available + WRAP_TOLERANCE;
                boolean canSplit = breakAll || style.breakLongWords();

                if (canSplit && (breakAll || tooLongAlone)) {
                    if (!breakAll && lineWidth > 0) flush(offset, false);
                    splitInside(segment, offset);
                    return;
                }
                if (lineWidth > 0) {
                    flush(offset, false);
                    join = 0;
                }
            }
            line.append(segment);
            lineWidth += join + widthOf(segment);
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
            lines.add(new ShapedLine(text, widthOf(text), leadingAbove(height, ascent, descent) + ascent, height, start, end, ellipsized));
        }

        float widthOf(String text) {
            return text.isEmpty() ? 0 : face.advance(text, style.font());
        }
    }

    /**
     * 行の高さと文字の高さの差（leading）のうち、文字の上に置く分（CSS 2.2 §10.8.1 の half-leading）。
     * 半分を整数のピクセルに切り捨て、残りを下に置く（Chrome の CalculateLeadingSpace と同じ。Arial 16px の
     * 行の高さ 18px なら上 0px・下 1px で、行の上端からベースラインまでは 14px）。
     *
     * 何が壊れていたか: 以前は上下に 0.5px ずつ分けていたので、文字とチェックボックスなどのベースラインが
     * Chrome より 0.5px 下にあり、行の高さも 0.5px ずれることがあった。
     */
    public static float leadingAbove(float lineHeight, float ascent, float descent) {
        return (float) Math.floor((lineHeight - (ascent + descent)) / 2f);
    }

    private static String stripTrailingBlanks(String text) {
        int end = text.length();
        while (end > 0 && isBlank(text.charAt(end - 1))) end--;
        return end == text.length() ? text : text.substring(0, end);
    }
}
