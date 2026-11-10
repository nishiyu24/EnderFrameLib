package com.nishiyu.enderframelib.core.text;

import com.nishiyu.enderframelib.core.text.model.*;

import com.nishiyu.enderframelib.core.text.model.FontStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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
        if (isAlreadyNormal(text, whiteSpace)) return text; // 正規表現（Matcher の生成）を避ける
        String result = text.indexOf('\r') >= 0 ? text.replace("\r\n", "\n").replace('\r', '\n') : text;
        if (!whiteSpace.preservesNewlines()) result = collapseRuns(result, true);
        if (result.indexOf('\t') >= 0) result = result.replace("\t", TAB_SPACES);
        if (whiteSpace.collapses()) result = collapseRuns(result, false);
        return result;
    }

    /**
     * 改行の連続（{@code newlines}）または空白の連続を空白 1 つに置き換える。{@link #normalizeWithRegex} の
     * {@code [\r\n]+} / {@code [ \t]+}（タブは直前に空白へ開いてある）の置換と同じ結果を、Matcher を作らずに出す。
     * 書き換えが要らなければ同じ文字列を返す。
     */
    private static String collapseRuns(String text, boolean newlines) {
        int n = text.length();
        int first = -1;
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            // 改行は 1 つでも空白に置き換わる。空白は 2 つ続くときだけ変わる
            if (newlines ? (c == '\n' || c == '\r') : (c == ' ' && i + 1 < n && text.charAt(i + 1) == ' ')) {
                first = i;
                break;
            }
        }
        if (first < 0) return text;
        StringBuilder sb = new StringBuilder(n);
        sb.append(text, 0, first);
        boolean inRun = false;
        for (int i = first; i < n; i++) {
            char c = text.charAt(i);
            if (newlines ? (c == '\n' || c == '\r') : c == ' ') {
                if (!inRun) sb.append(' ');
                inRun = true;
            } else {
                sb.append(c);
                inRun = false;
            }
        }
        return sb.toString();
    }

    /** 検証用: 正規表現による元の実装。 */
    static String normalizeWithRegex(String text, WhiteSpace whiteSpace) {
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

    /** 改行・タブが無く、（畳むなら）空白の連続も無い: 何も書き換わらない文字列。 */
    private static boolean isAlreadyNormal(String text, WhiteSpace whiteSpace) {
        boolean collapses = whiteSpace.collapses();
        char prev = 0;
        for (int i = 0, n = text.length(); i < n; i++) {
            char c = text.charAt(i);
            if (c == '\r' || c == '\n' || c == '\t') return false;
            if (collapses && c == ' ' && prev == ' ') return false;
            prev = c;
        }
        return true;
    }

    // ==========================================
    // 折り返し候補
    // ==========================================

    /**
     * 折り返し候補（UAX #14。{@link LineBreaker}）で区切った断片。末尾の空白は直前の断片に付く。
     *
     * インライン整形も同じ規則で単語を切り出すため公開している。
     * 以前は正規表現 1 本（空白と和文の句読点のみ）だったので、
     * 句読点を含まない日本語の長文がまったく折り返されなかった。
     */
    public static List<String> segments(String text, WordBreak wordBreak) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;

        int start = 0;
        for (int i = text.offsetByCodePoints(0, 1); i < text.length(); i = text.offsetByCodePoints(i, 1)) {
            if (LineBreaker.canBreakBefore(text, i, wordBreak)) {
                out.add(text.substring(start, i));
                start = i;
            }
        }
        out.add(text.substring(start));
        return out;
    }

    private static boolean isBlank(char c) {
        return c == ' ' || c == '	';
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
        if (text == null || face == null) return shapeUncached(text, face, style, availableWidth);
        // 同じ文字列・書体・スタイル・幅の整形は結果も同じ（書体の面は同一性で見分ける。登録が変われば別のインスタンスになる）。
        // 再レイアウトは変わらない文字も測り直すことが多いので、整形のやり直しを避ける
        // 照会は、スレッドごとの 1 個の鍵を書き換えて使う（呼び出しごとに鍵を作らない）。入れるときだけ複製する
        ShapeKey probe = SHAPE_PROBE.get().set(text, face, style, availableWidth);
        List<ShapedLine> hit = SHAPED.get(probe);
        if (hit != null) return hit;
        List<ShapedLine> lines = List.copyOf(shapeUncached(text, face, style, availableWidth));
        if (SHAPED.size() >= SHAPED_LIMIT) SHAPED.clear();
        SHAPED.put(new ShapeKey(text, face, style, availableWidth), lines);
        return lines;
    }

    /**
     * 幅に依存しない段落の下ごしらえ: 折り返し候補の断片・段落内の開始位置・断片ごとの幅（末尾の空白あり／なし）。
     * 幅が変わる（ウィンドウのリサイズ・flex の再測定）たびに UAX #14 の判定と語ごとの測定をやり直さず、
     * 行の分割はこの数値だけで行う。
     */

    private static final class SegKey {
        private String paragraph;
        private FontFace face;
        private FontStyle font;
        private WordBreak wordBreak;

        SegKey(String paragraph, FontFace face, FontStyle font, WordBreak wordBreak) {
            set(paragraph, face, font, wordBreak);
        }

        SegKey set(String paragraph, FontFace face, FontStyle font, WordBreak wordBreak) {
            this.paragraph = paragraph;
            this.face = face;
            this.font = font;
            this.wordBreak = wordBreak;
            return this;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof SegKey k && k.face == face && k.wordBreak == wordBreak
                    && k.font.equals(font) && k.paragraph.equals(paragraph);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * (31 * paragraph.hashCode() + System.identityHashCode(face)) + font.hashCode()) + wordBreak.ordinal();
        }
    }

    private static final ThreadLocal<ShapeKey> SHAPE_PROBE = ThreadLocal.withInitial(() -> new ShapeKey(null, null, null, 0f));
    private static final ThreadLocal<SegKey> SEG_PROBE = ThreadLocal.withInitial(() -> new SegKey(null, null, null, null));

    private static final java.util.concurrent.ConcurrentHashMap<SegKey, ParagraphSegments> SEGMENTED = new java.util.concurrent.ConcurrentHashMap<>();
    private static final AtomicInteger SEGMENTED_BUILDS = new AtomicInteger();

    /** 段落の下ごしらえの作成回数（テスト用）。 */
    static int segmentedBuilds() { return SEGMENTED_BUILDS.get(); }

    public static ParagraphSegments segmented(String paragraph, FontFace face, FontStyle font, WordBreak wordBreak) {
        ParagraphSegments hit = SEGMENTED.get(SEG_PROBE.get().set(paragraph, face, font, wordBreak));
        if (hit != null) return hit;
        SegKey key = new SegKey(paragraph, face, font, wordBreak);
        List<String> list = segments(paragraph, wordBreak);
        int n = list.size();
        String[] segs = list.toArray(new String[0]);
        int[] offsets = new int[n];
        float[] full = new float[n];
        float[] trimmed = new float[n];
        float[] joins = new float[n];
        float[] strippedTrailing = new float[n];
        float[] stripped = new float[n];
        int offset = 0;
        for (int i = 0; i < n; i++) {
            offsets[i] = offset;
            // 行は断片を続けて足すので、行の末尾の文字は直前の断片の末尾と同じ。境目の詰めも幅に依存しない
            joins[i] = i == 0 ? 0 : kerningBetween(face, font, segs[i - 1], segs[i]);
            offset += segs[i].length();
            full[i] = segs[i].isEmpty() ? 0 : face.advance(segs[i], font);
            String t = stripTrailingBlanks(segs[i]);
            trimmed[i] = t.isEmpty() ? 0 : (t.length() == segs[i].length() ? full[i] : face.advance(t, font));
            String st = segs[i].stripTrailing();
            strippedTrailing[i] = st.isEmpty() ? 0 : (st.length() == segs[i].length() ? full[i] : face.advance(st, font));
            String sp = segs[i].strip();
            stripped[i] = sp.isEmpty() ? 0 : (sp.length() == segs[i].length() ? full[i] : face.advance(sp, font));
        }
        ParagraphSegments made = new ParagraphSegments(segs, offsets, full, trimmed, joins, strippedTrailing, stripped);
        SEGMENTED_BUILDS.incrementAndGet();
        if (SEGMENTED.size() >= SHAPED_LIMIT) SEGMENTED.clear();
        SEGMENTED.put(key, made);
        return made;
    }

    /** 整形結果の記憶の鍵。{@code face} は同一性で比べる。 */
    private static final class ShapeKey {
        private String text;
        private FontFace face;
        private TextStyle style;
        private float width;

        ShapeKey(String text, FontFace face, TextStyle style, float width) {
            set(text, face, style, width);
        }

        ShapeKey set(String text, FontFace face, TextStyle style, float width) {
            this.text = text;
            this.face = face;
            this.style = style;
            this.width = width;
            return this;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof ShapeKey k && k.face == face && Float.compare(k.width, width) == 0
                    && k.text.equals(text) && k.style.equals(style);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * (31 * text.hashCode() + System.identityHashCode(face)) + style.hashCode()) + Float.hashCode(width);
        }
    }

    private static final java.util.concurrent.ConcurrentHashMap<ShapeKey, List<ShapedLine>> SHAPED = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int SHAPED_LIMIT = 4096;

    private static List<ShapedLine> shapeUncached(String text, FontFace face, TextStyle style, float availableWidth) {
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

            ParagraphSegments seg = segmented(paragraph, face, style.font(), style.wordBreak());
            for (int i = 0; i < seg.segs().length; i++) {
                append(seg.segs()[i], from + seg.offsets()[i], seg.trimmedW()[i], seg.fullW()[i], seg.joinW()[i]);
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
        void append(String segment, int offset, float trimmedWidth, float fullWidth, float segJoin) {
            float join = line.isEmpty() ? 0 : segJoin;
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
            lineWidth += join + fullWidth;
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
