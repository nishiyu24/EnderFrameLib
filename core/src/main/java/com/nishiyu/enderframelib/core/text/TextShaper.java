package com.nishiyu.enderframelib.core.text;

import com.nishiyu.enderframelib.core.text.model.*;

import com.nishiyu.enderframelib.core.text.model.FontStyle;

import java.util.ArrayList;
import java.util.Arrays;
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
                out.add(sharedSegment(text, start, i));
                start = i;
            }
        }
        out.add(sharedSegment(text, start, text.length()));
        return out;
    }

    // 語（折り返しの単位）の文字列は、同じ語なら 1 つの String を共有する。段落ごと・書体ごとに別の部分文字列を持つと、
    // 数千行のリストで語の数だけ String と byte[] が残る（語彙は文章の量よりずっと少ない）。
    // 直接配置の表（衝突したら新しい方に置き換える）で、確保なしに引く。作るのは段落の記憶の更新時だけ。
    private static final int SHARED_SEGMENT_MAX_LENGTH = 24;
    private static final String[] SHARED_SEGMENTS = new String[1 << 15];

    /** 切り出し {@code [start, end)} の文字列。短い語は共有する（表への書き込みは競っても同じ内容の不変の文字列）。 */
    private static String sharedSegment(String text, int start, int end) {
        int len = end - start;
        if (len > SHARED_SEGMENT_MAX_LENGTH) return text.substring(start, end);
        int h = 0;
        for (int k = start; k < end; k++) h = 31 * h + text.charAt(k);
        int slot = (h ^ (h >>> 15)) & (SHARED_SEGMENTS.length - 1);
        String hit = SHARED_SEGMENTS[slot];
        if (hit != null && hit.length() == len && text.startsWith(hit, start)) return hit;
        String made = text.substring(start, end);
        SHARED_SEGMENTS[slot] = made;
        return made;
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
        return shapeInterval(text, face, style, availableWidth).lines();
    }

    /**
     * 幅区間情報ごと整形結果を返す（{@link com.nishiyu.enderframelib.core.layout.LayoutBox} の 2 枠メモ用）。
     */
    public static ShapedInterval shapeInterval(String text, FontFace face, TextStyle style, float availableWidth) {
        int hash = shapeKeyHash(text, face, style);
        ShapedHolder holder = lookupShapedHolder(text, face, style, hash);
        if (holder != null) {
            ShapedInterval hit = holder.matchInterval(availableWidth);
            if (hit != null) return hit;
        }
        ShapedInterval built = buildShapedInterval(text, face, style, availableWidth);
        if (holder == null) {
            holder = new ShapedHolder(built);
            ShapeKey key = new ShapeKey(text, face, style, hash);
            SHAPED.put(key, holder);
            putShapeL1(key, holder);
        } else {
            holder.add(built);
        }
        return built;
    }

    private static final int L1_BUCKETS = 1 << 12; // 4096 buckets * 2 ways = 8192 slots
    private static final int L1_BUCKET_MASK = L1_BUCKETS - 1;

    private record ShapeL1Slot(ShapeKey key, ShapedHolder value) {}
    private static final ShapeL1Slot[] SHAPE_L1 = new ShapeL1Slot[L1_BUCKETS * 2];

    private static int shapeKeyHash(String text, FontFace face, TextStyle style) {
        return 31 * (31 * text.hashCode() + System.identityHashCode(face)) + style.hashCode();
    }

    private static ShapedHolder lookupShapedHolder(String text, FontFace face, TextStyle style, int hash) {
        int base = ((hash ^ (hash >>> 16)) & L1_BUCKET_MASK) << 1;
        ShapeL1Slot s0 = SHAPE_L1[base];
        if (s0 != null && s0.key.matches(text, face, style, hash)) return s0.value;
        ShapeL1Slot s1 = SHAPE_L1[base + 1];
        if (s1 != null && s1.key.matches(text, face, style, hash)) return s1.value;
        ShapedHolder hit = SHAPED.get(SHAPE_PROBE.get().set(text, face, style, hash));
        if (hit != null) {
            putShapeL1(new ShapeKey(text, face, style, hash), hit);
        }
        return hit;
    }

    private static void putShapeL1(ShapeKey key, ShapedHolder value) {
        int base = ((key.hash ^ (key.hash >>> 16)) & L1_BUCKET_MASK) << 1;
        ShapeL1Slot s0 = SHAPE_L1[base];
        ShapeL1Slot entry = new ShapeL1Slot(key, value);
        if (s0 == null || s0.key.matches(key.text, key.face, key.style, key.hash)) {
            SHAPE_L1[base] = entry;
        } else {
            SHAPE_L1[base + 1] = s0;
            SHAPE_L1[base] = entry;
        }
    }

    /** 段落の折り返し（幅区間キャッシュ済み）結果。{@link com.nishiyu.enderframelib.core.layout.BlockLayoutEngine} が使う。 */
    public record WrappedParagraph(
            ParagraphSegments prepared,
            int nonEmpty,
            int lineCount,
            int[] intData,
            float[] floatData,
            float finalLineX,
            float finalTrailingSpace,
            float maxAcceptedSum,
            float minRejectedSum
    ) {
        public boolean matches(float availableWidth) {
            return maxAcceptedSum <= availableWidth + WRAP_TOLERANCE
                    && minRejectedSum > availableWidth + WRAP_TOLERANCE;
        }

        public int segIndex(int k) {
            return intData[k];
        }

        public int lineStart(int lineIdx) {
            return intData[nonEmpty + lineIdx];
        }

        public float itemX(int k) {
            return floatData[k << 1];
        }

        public float advance(int k) {
            return floatData[(k << 1) + 1];
        }

        public float lineUsed(int lineIdx) {
            return floatData[(nonEmpty << 1) + lineIdx];
        }
    }

    private static final class WrappedHolder {
        private static final int MAX_ENTRIES = 4;
        volatile WrappedParagraph primary;
        private WrappedParagraph[] entries;

        WrappedHolder(WrappedParagraph first) {
            this.primary = first;
            this.entries = new WrappedParagraph[]{first};
        }

        WrappedParagraph match(float width) {
            WrappedParagraph p = primary;
            if (p != null && p.matches(width)) return p;
            WrappedParagraph[] arr = entries;
            for (int i = 0; i < arr.length; i++) {
                WrappedParagraph e = arr[i];
                if (e != p && e.matches(width)) {
                    primary = e;
                    return e;
                }
            }
            return null;
        }

        synchronized void add(WrappedParagraph entry) {
            WrappedParagraph[] old = entries;
            int keep = Math.min(old.length, MAX_ENTRIES - 1);
            WrappedParagraph[] next = new WrappedParagraph[keep + 1];
            next[0] = entry;
            System.arraycopy(old, 0, next, 1, keep);
            entries = next;
            primary = entry;
        }
    }

    private record WrapL1Slot(SegKey key, WrappedHolder value) {}
    private static final WrapL1Slot[] WRAP_L1 = new WrapL1Slot[L1_BUCKETS * 2];
    private static final Generational<SegKey, WrappedHolder> WRAPPED = new Generational<>();

    /**
     * 幅 {@code availableWidth}（{@code > 0}）での段落折り返し結果と幅区間をキャッシュして返す。
     */
    public static WrappedParagraph wrapParagraph(String paragraph, FontFace face, FontStyle font,
                                                 WordBreak wordBreak, float availableWidth) {
        int hash = segKeyHash(paragraph, face, font, wordBreak);
        WrappedHolder holder = lookupWrappedHolder(paragraph, face, font, wordBreak, hash);
        if (holder != null) {
            WrappedParagraph hit = holder.match(availableWidth);
            if (hit != null) return hit;
        }
        ParagraphSegments prepared = segmented(paragraph, face, font, wordBreak);
        WrappedParagraph built = buildWrappedParagraph(prepared, face, font, availableWidth);
        if (holder == null) {
            holder = new WrappedHolder(built);
            SegKey key = new SegKey(paragraph, face, font, wordBreak, hash);
            WRAPPED.put(key, holder);
            putWrapL1(key, holder);
        } else {
            holder.add(built);
        }
        return built;
    }

    private static WrappedHolder lookupWrappedHolder(String paragraph, FontFace face, FontStyle font, WordBreak wordBreak, int hash) {
        int base = ((hash ^ (hash >>> 16)) & L1_BUCKET_MASK) << 1;
        WrapL1Slot s0 = WRAP_L1[base];
        if (s0 != null && s0.key.matches(paragraph, face, font, wordBreak, hash)) return s0.value;
        WrapL1Slot s1 = WRAP_L1[base + 1];
        if (s1 != null && s1.key.matches(paragraph, face, font, wordBreak, hash)) return s1.value;
        WrappedHolder hit = WRAPPED.get(SEG_PROBE.get().set(paragraph, face, font, wordBreak, hash));
        if (hit != null) {
            putWrapL1(new SegKey(paragraph, face, font, wordBreak, hash), hit);
        }
        return hit;
    }

    private static void putWrapL1(SegKey key, WrappedHolder value) {
        int base = ((key.hash ^ (key.hash >>> 16)) & L1_BUCKET_MASK) << 1;
        WrapL1Slot s0 = WRAP_L1[base];
        WrapL1Slot entry = new WrapL1Slot(key, value);
        if (s0 == null || s0.key.matches(key.paragraph, key.face, key.font, key.wordBreak, key.hash)) {
            WRAP_L1[base] = entry;
        } else {
            WRAP_L1[base + 1] = s0;
            WRAP_L1[base] = entry;
        }
    }

    private static WrappedParagraph buildWrappedParagraph(ParagraphSegments prepared, FontFace face, FontStyle font,
                                                          float availableWidth) {
        String[] segments = prepared.segs();
        int rawCount = segments.length;
        int nonEmpty = 0;
        for (int s = 0; s < rawCount; s++) {
            if (!segments[s].isEmpty()) nonEmpty++;
        }
        int[] intScratch = new int[nonEmpty + nonEmpty + 2];
        int startsOff = nonEmpty;
        float[] floatScratch = new float[nonEmpty * 2 + Math.max(1, nonEmpty)];
        int usedOff = nonEmpty * 2;

        int lineCount = 0;
        intScratch[startsOff] = 0;
        float lineX = 0;
        float trailingSpace = 0;
        String previousOnLine = null;
        float kerned = 0;
        boolean hasItem = false;
        float maxAccepted = Float.NEGATIVE_INFINITY;
        float minRejected = Float.POSITIVE_INFINITY;
        int idx = 0;

        for (int s = 0; s < rawCount; s++) {
            String segment = segments[s];
            if (segment.isEmpty()) continue;
            float advance = prepared.fullW(s);
            float trimmedW = prepared.strippedTrailingW(s);
            float join = previousOnLine == null ? 0
                    : s > 0 && previousOnLine == segments[s - 1] ? prepared.joinW(s)
                    : kerningBetween(face, font, previousOnLine, segment);
            if (!segment.isBlank()) {
                float cand = lineX + kerned + join + trimmedW;
                if (hasItem && cand > availableWidth + WRAP_TOLERANCE) {
                    minRejected = Math.min(minRejected, cand);
                    floatScratch[usedOff + lineCount] = lineX - trailingSpace;
                    lineCount++;
                    intScratch[startsOff + lineCount] = idx;
                    lineX = 0;
                    trailingSpace = 0;
                    hasItem = false;
                    kerned = 0;
                    join = 0;
                } else if (hasItem) {
                    maxAccepted = Math.max(maxAccepted, cand);
                }
            }
            kerned += join;
            previousOnLine = segment;
            intScratch[idx] = s;
            int fBase = idx << 1;
            floatScratch[fBase] = lineX;
            floatScratch[fBase + 1] = advance;
            idx++;
            lineX += advance;
            trailingSpace = advance - trimmedW;
            hasItem = true;
        }
        floatScratch[usedOff + lineCount] = lineX - trailingSpace;
        lineCount++;
        intScratch[startsOff + lineCount] = nonEmpty;

        int[] finalIntData = Arrays.copyOf(intScratch, nonEmpty + lineCount + 1);
        float[] finalFloatData = Arrays.copyOf(floatScratch, usedOff + lineCount);
        return new WrappedParagraph(prepared, nonEmpty, lineCount, finalIntData, finalFloatData,
                lineX, trailingSpace, maxAccepted, minRejected);
    }

    /**
     * 幅に依存しない段落の下ごしらえ: 折り返し候補の断片・段落内の開始位置・断片ごとの幅（末尾の空白あり／なし）。
     * 幅が変わる（ウィンドウのリサイズ・flex の再測定）たびに UAX #14 の判定と語ごとの測定をやり直さず、
     * 行の分割はこの数値だけで行う。
     */

    private record BaseKey(String paragraph, WordBreak wordBreak) {}
    private record SegmentBase(String[] segs, int[] offsets) {}
    private static final Generational<BaseKey, SegmentBase> BASE_SEGMENTS =
            new Generational<>(Math.min(512, Integer.getInteger("enderframe.text.cache", 4096)));

    private static final class NatKey {
        private String paragraph;
        private FontFace face;
        private boolean bold;
        private boolean kerning;
        private WordBreak wordBreak;
        private int hash;

        NatKey(String paragraph, FontFace face, boolean bold, boolean kerning, WordBreak wordBreak, int hash) {
            set(paragraph, face, bold, kerning, wordBreak, hash);
        }

        NatKey set(String paragraph, FontFace face, boolean bold, boolean kerning, WordBreak wordBreak, int hash) {
            this.paragraph = paragraph;
            this.face = face;
            this.bold = bold;
            this.kerning = kerning;
            this.wordBreak = wordBreak;
            this.hash = hash;
            return this;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof NatKey k && k.hash == hash && k.face == face && k.bold == bold
                    && k.kerning == kerning && k.wordBreak == wordBreak && k.paragraph.equals(paragraph);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    private static final int NAT_STRIDE = 7;
    private static final int NAT_FULL = 0, NAT_TRIMMED = 1, NAT_STRIPPED_TRAILING = 2, NAT_STRIPPED = 3,
            NAT_JOIN_PAIR = 4, NAT_JOIN_PREV = 5, NAT_JOIN_CURR = 6;

    /** {@code data} に 7 種（full, trimmed, strippedTrailing, stripped, joinPair, joinPrev, joinCurr）を stride 7 で並べる。 */
    private record NaturalSegments(SegmentBase base, float[] data) {}

    private static final ThreadLocal<NatKey> NAT_PROBE = ThreadLocal.withInitial(() -> new NatKey(null, null, false, false, null, 0));
    private static final Generational<NatKey, NaturalSegments> BITMAP_NATURAL = new Generational<>();

    private static final class SegKey {
        private String paragraph;
        private FontFace face;
        private FontStyle font;
        private WordBreak wordBreak;
        private int hash;

        SegKey(String paragraph, FontFace face, FontStyle font, WordBreak wordBreak, int hash) {
            set(paragraph, face, font, wordBreak, hash);
        }

        SegKey set(String paragraph, FontFace face, FontStyle font, WordBreak wordBreak, int hash) {
            this.paragraph = paragraph;
            this.face = face;
            this.font = font;
            this.wordBreak = wordBreak;
            this.hash = hash;
            return this;
        }

        boolean matches(String paragraph, FontFace face, FontStyle font, WordBreak wordBreak, int hash) {
            return this.hash == hash && this.face == face && this.wordBreak == wordBreak
                    && (this.paragraph == paragraph || this.paragraph.equals(paragraph))
                    && (this.font == font || this.font.equals(font));
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof SegKey k && k.hash == hash && k.face == face && k.wordBreak == wordBreak
                    && (k.font == font || k.font.equals(font))
                    && (k.paragraph == paragraph || k.paragraph.equals(paragraph));
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    private record SegL1Slot(SegKey key, ParagraphSegments value) {}
    private static final SegL1Slot[] SEG_L1 = new SegL1Slot[L1_BUCKETS * 2];

    private static int segKeyHash(String paragraph, FontFace face, FontStyle font, WordBreak wordBreak) {
        return 31 * (31 * (31 * paragraph.hashCode() + System.identityHashCode(face)) + font.hashCode()) + wordBreak.ordinal();
    }

    private static final ThreadLocal<ShapeKey> SHAPE_PROBE = ThreadLocal.withInitial(() -> new ShapeKey(null, null, null, 0));
    private static final ThreadLocal<SegKey> SEG_PROBE = ThreadLocal.withInitial(() -> new SegKey(null, null, null, null, 0));

    private static final Generational<SegKey, ParagraphSegments> SEGMENTED = new Generational<>();
    private static final AtomicInteger SEGMENTED_BUILDS = new AtomicInteger();

    /** 段落の下ごしらえの作成回数（テスト用）。 */
    static int segmentedBuilds() { return SEGMENTED_BUILDS.get(); }

    private static SegmentBase segmentBase(String paragraph, WordBreak wordBreak) {
        BaseKey bk = new BaseKey(paragraph, wordBreak);
        SegmentBase hit = BASE_SEGMENTS.get(bk);
        if (hit != null) return hit;
        List<String> list = segments(paragraph, wordBreak);
        int n = list.size();
        String[] segs = list.toArray(new String[0]);
        int[] offsets = new int[n];
        int offset = 0;
        for (int i = 0; i < n; i++) {
            offsets[i] = offset;
            offset += segs[i].length();
        }
        SegmentBase built = new SegmentBase(segs, offsets);
        BASE_SEGMENTS.put(bk, built);
        return built;
    }

    private static float measureSub(String seg, int start, int end, float fullW, BitmapFontFace bf, boolean bold,
                                    FontFace face, FontStyle font) {
        if (start >= end) return 0f;
        if (start == 0 && end == seg.length()) return fullW;
        String sub = sharedSegment(seg, start, end);
        return bf != null ? bf.cachedNaturalAdvance(sub, bold) : face.advance(sub, font);
    }

    private static NaturalSegments bitmapNaturalSegments(String paragraph, BitmapFontFace bf, boolean bold,
                                                           boolean kerning, WordBreak wordBreak) {
        int hash = 31 * (31 * (31 * (31 * paragraph.hashCode() + System.identityHashCode(bf)) + (bold ? 1 : 0))
                + (kerning ? 1 : 0)) + wordBreak.ordinal();
        NaturalSegments hit = BITMAP_NATURAL.get(NAT_PROBE.get().set(paragraph, bf, bold, kerning, wordBreak, hash));
        if (hit != null) return hit;

        SegmentBase base = segmentBase(paragraph, wordBreak);
        String[] segs = base.segs;
        int n = segs.length;
        float[] data = new float[n * NAT_STRIDE];

        for (int i = 0; i < n; i++) {
            int p = i * NAT_STRIDE;
            String seg = segs[i];
            if (i > 0 && !segs[i - 1].isEmpty() && !seg.isEmpty()) {
                String before = segs[i - 1];
                int cpPrev = Character.codePointBefore(before, before.length());
                int cpCurr = Character.codePointAt(seg, 0);
                String last = Character.toString(cpPrev);
                String first = Character.toString(cpCurr);
                data[p + NAT_JOIN_PAIR] = bf.cachedNaturalAdvance(last + first, bold);
                data[p + NAT_JOIN_PREV] = bf.cachedNaturalAdvance(last, bold);
                data[p + NAT_JOIN_CURR] = bf.cachedNaturalAdvance(first, bold);
            }
            if (seg.isEmpty()) continue;
            float f = bf.cachedNaturalAdvance(seg, bold);
            data[p + NAT_FULL] = f;
            int tbEnd = trimTrailingBlanksEnd(seg);
            float tW = measureSub(seg, 0, tbEnd, f, bf, bold, null, null);
            data[p + NAT_TRIMMED] = tW;
            int stEnd = stripTrailingEnd(seg);
            float stW = stEnd == tbEnd ? tW : measureSub(seg, 0, stEnd, f, bf, bold, null, null);
            data[p + NAT_STRIPPED_TRAILING] = stW;
            int spStart = stripLeadingStart(seg, stEnd);
            data[p + NAT_STRIPPED] = spStart == 0 ? stW : measureSub(seg, spStart, stEnd, f, bf, bold, null, null);
        }

        NaturalSegments built = new NaturalSegments(base, data);
        BITMAP_NATURAL.put(new NatKey(paragraph, bf, bold, kerning, wordBreak, hash), built);
        return built;
    }

    public static ParagraphSegments segmented(String paragraph, FontFace face, FontStyle font, WordBreak wordBreak) {
        int hash = segKeyHash(paragraph, face, font, wordBreak);
        int l1Base = ((hash ^ (hash >>> 16)) & L1_BUCKET_MASK) << 1;
        SegL1Slot s0 = SEG_L1[l1Base];
        if (s0 != null && s0.key.matches(paragraph, face, font, wordBreak, hash)) return s0.value;
        SegL1Slot s1 = SEG_L1[l1Base + 1];
        if (s1 != null && s1.key.matches(paragraph, face, font, wordBreak, hash)) return s1.value;

        ParagraphSegments hit = SEGMENTED.get(SEG_PROBE.get().set(paragraph, face, font, wordBreak, hash));
        if (hit != null) {
            putSegL1(new SegKey(paragraph, face, font, wordBreak, hash), hit, l1Base);
            return hit;
        }

        SegKey key = new SegKey(paragraph, face, font, wordBreak, hash);
        ParagraphSegments made;
        if (face instanceof BitmapFontFace bf && font.letterSpacingPx() == 0f) {
            NaturalSegments nat = bitmapNaturalSegments(paragraph, bf, font.bold(), font.kerning(), wordBreak);
            SegmentBase base = nat.base;
            int n = base.segs.length;
            float[] data = nat.data;
            float scale = bf.scaleFor(font);
            float[] widths = new float[n * ParagraphSegments.STRIDE];
            for (int i = 0, p = 0, w = 0; i < n; i++, p += NAT_STRIDE, w += ParagraphSegments.STRIDE) {
                widths[w + ParagraphSegments.OFF_FULL] = data[p + NAT_FULL] * scale;
                widths[w + ParagraphSegments.OFF_TRIMMED] = data[p + NAT_TRIMMED] * scale;
                widths[w + ParagraphSegments.OFF_STRIPPED_TRAILING] = data[p + NAT_STRIPPED_TRAILING] * scale;
                widths[w + ParagraphSegments.OFF_STRIPPED] = data[p + NAT_STRIPPED] * scale;
                if (i > 0 && data[p + NAT_JOIN_PAIR] != 0f) {
                    widths[w + ParagraphSegments.OFF_JOIN] =
                            data[p + NAT_JOIN_PAIR] * scale - data[p + NAT_JOIN_PREV] * scale - data[p + NAT_JOIN_CURR] * scale;
                }
            }
            made = new ParagraphSegments(base.segs, base.offsets, widths);
        } else {
            SegmentBase base = segmentBase(paragraph, wordBreak);
            String[] segs = base.segs;
            int n = segs.length;
            float[] widths = new float[n * ParagraphSegments.STRIDE];
            for (int i = 0, w = 0; i < n; i++, w += ParagraphSegments.STRIDE) {
                widths[w + ParagraphSegments.OFF_JOIN] = i == 0 ? 0 : kerningBetween(face, font, segs[i - 1], segs[i]);
                String seg = segs[i];
                if (seg.isEmpty()) continue;
                float f = face.advance(seg, font);
                widths[w + ParagraphSegments.OFF_FULL] = f;
                int tbEnd = trimTrailingBlanksEnd(seg);
                float tW = measureSub(seg, 0, tbEnd, f, null, false, face, font);
                widths[w + ParagraphSegments.OFF_TRIMMED] = tW;
                int stEnd = stripTrailingEnd(seg);
                float stW = stEnd == tbEnd ? tW : measureSub(seg, 0, stEnd, f, null, false, face, font);
                widths[w + ParagraphSegments.OFF_STRIPPED_TRAILING] = stW;
                int spStart = stripLeadingStart(seg, stEnd);
                widths[w + ParagraphSegments.OFF_STRIPPED] =
                        spStart == 0 ? stW : measureSub(seg, spStart, stEnd, f, null, false, face, font);
            }
            made = new ParagraphSegments(base.segs, base.offsets, widths);
        }
        SEGMENTED_BUILDS.incrementAndGet();
        SEGMENTED.put(key, made);
        putSegL1(key, made, l1Base);
        return made;
    }

    private static void putSegL1(SegKey key, ParagraphSegments value, int base) {
        SegL1Slot s0 = SEG_L1[base];
        SegL1Slot entry = new SegL1Slot(key, value);
        if (s0 == null || s0.key.matches(key.paragraph, key.face, key.font, key.wordBreak, key.hash)) {
            SEG_L1[base] = entry;
        } else {
            SEG_L1[base + 1] = s0;
            SEG_L1[base] = entry;
        }
    }

    /** 整形結果の記憶の鍵。{@code face} は同一性で比べ、幅は {@link ShapedHolder} 側で区間照合する。 */
    private static final class ShapeKey {
        private String text;
        private FontFace face;
        private TextStyle style;
        private int hash;

        ShapeKey(String text, FontFace face, TextStyle style, int hash) {
            set(text, face, style, hash);
        }

        ShapeKey set(String text, FontFace face, TextStyle style, int hash) {
            this.text = text;
            this.face = face;
            this.style = style;
            this.hash = hash;
            return this;
        }

        boolean matches(String text, FontFace face, TextStyle style, int hash) {
            return this.hash == hash && this.face == face
                    && (this.text == text || this.text.equals(text))
                    && (this.style == style || this.style.equals(style));
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof ShapeKey k && k.hash == hash && k.face == face
                    && (k.text == text || k.text.equals(text))
                    && (k.style == style || k.style.equals(style));
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    public record ShapedInterval(
            List<ShapedLine> lines,
            float maxAcceptedSum,
            float minRejectedSum,
            boolean exactOnly,
            float exactWidth,
            boolean wrapped,
            boolean noWrapEllipsisCheck,
            float maxLineWidth
    ) {
        public boolean matches(float w) {
            if (exactOnly) return Float.compare(exactWidth, w) == 0;
            if (noWrapEllipsisCheck) return w <= 0 || maxLineWidth <= w;
            if (w <= 0) return !wrapped;
            return maxAcceptedSum <= w + WRAP_TOLERANCE && minRejectedSum > w + WRAP_TOLERANCE;
        }
    }

    private static final class ShapedHolder {
        private static final int MAX_ENTRIES = 4;
        volatile ShapedInterval primary;
        private ShapedInterval[] entries;

        ShapedHolder(ShapedInterval first) {
            this.primary = first;
            this.entries = new ShapedInterval[]{first};
        }

        ShapedInterval matchInterval(float w) {
            ShapedInterval p = primary;
            if (p != null && p.matches(w)) return p;
            ShapedInterval[] arr = entries;
            for (int i = 0; i < arr.length; i++) {
                ShapedInterval e = arr[i];
                if (e != p && e.matches(w)) {
                    primary = e;
                    return e;
                }
            }
            return null;
        }

        synchronized void add(ShapedInterval entry) {
            ShapedInterval[] old = entries;
            int keep = Math.min(old.length, MAX_ENTRIES - 1);
            ShapedInterval[] next = new ShapedInterval[keep + 1];
            next[0] = entry;
            System.arraycopy(old, 0, next, 1, keep);
            entries = next;
            primary = entry;
        }
    }

    private static final Generational<ShapeKey, ShapedHolder> SHAPED = new Generational<>();
    /** 1 世代の上限（件数）。2 世代で最大 2 倍まで持つ。段落 × 書体の組が上限を超えると、全消去ではなく古い世代だけが落ちる。 */
    private static final int SHAPED_LIMIT = Integer.getInteger("enderframe.text.cache", 4096);

    /**
     * 2 世代の整形結果の記憶。新しい世代が上限に達したら、それを古い世代にして新しい世代を空にする（古い世代は捨てる）。
     * 以前は上限に達すると全てを消していたため、段落 × 書体の組が上限を少し超える文書（2,000 行のリストで書体の大きさを往復する）では
     * 往復のたびに全てを組み直していた。古い世代の命中はそのまま返す（昇格しない: 鍵を作り直さずに済ませる）。
     */
    private static final class Generational<K, V> {
        private final int limit;
        private volatile java.util.concurrent.ConcurrentHashMap<K, V> young = new java.util.concurrent.ConcurrentHashMap<>();
        private volatile java.util.concurrent.ConcurrentHashMap<K, V> old = new java.util.concurrent.ConcurrentHashMap<>();

        Generational() { this(SHAPED_LIMIT); }
        Generational(int limit) { this.limit = limit; }

        V get(K probe) {
            V v = young.get(probe);
            return v != null ? v : old.get(probe);
        }

        void put(K key, V value) {
            java.util.concurrent.ConcurrentHashMap<K, V> y = young;
            if (y.size() >= limit) {
                synchronized (this) {
                    if (young.size() >= limit) {
                        old = young;
                        young = new java.util.concurrent.ConcurrentHashMap<>();
                    }
                }
                y = young;
            }
            y.put(key, value);
        }
    }

    private static ShapedInterval buildShapedInterval(String text, FontFace face, TextStyle style, float availableWidth) {
        Shaping shaping = new Shaping(face, style, availableWidth);
        String normalized = normalize(text, style.whiteSpace());
        if (!normalized.isEmpty()) {
            int paragraphStart = 0;
            while (true) {
                int newline = normalized.indexOf('\n', paragraphStart);
                int paragraphEnd = newline < 0 ? normalized.length() : newline;
                shaping.shapeParagraph(normalized, paragraphStart, paragraphEnd);
                if (newline < 0) break;
                paragraphStart = newline + 1;
            }
        }
        List<ShapedLine> lines = List.copyOf(shaping.lines);
        boolean noWrapEllipsis = !style.whiteSpace().wraps() && style.ellipsis() && !shaping.exactOnly;
        return new ShapedInterval(lines, shaping.maxAcceptedSum, shaping.minRejectedSum, shaping.exactOnly,
                availableWidth, shaping.wrapped, noWrapEllipsis, shaping.maxLineWidth);
    }

    static List<ShapedLine> shapeUncached(String text, FontFace face, TextStyle style, float availableWidth) {
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
        final boolean canSplit;

        final StringBuilder line = new StringBuilder();
        float lineWidth = 0;
        int lineStart = 0;
        float maxAcceptedSum = Float.NEGATIVE_INFINITY;
        float minRejectedSum = Float.POSITIVE_INFINITY;
        boolean exactOnly = false;
        boolean wrapped = false;
        float maxLineWidth = 0;

        Shaping(FontFace face, TextStyle style, float availableWidth) {
            this.face = face;
            this.style = style;
            this.available = availableWidth;
            this.bounded = availableWidth > 0 && style.whiteSpace().wraps();
            this.canSplit = style.wordBreak() == WordBreak.BREAK_ALL || style.breakLongWords();
        }

        void shapeParagraph(String source, int from, int to) {
            lineStart = from;
            String paragraph = source.substring(from, to);

            if (!style.whiteSpace().wraps()) {
                String visible = style.ellipsis() && available > 0
                        ? ellipsize(paragraph, face, style.font(), available)
                        : paragraph;
                boolean ellipsized = !visible.equals(paragraph);
                if (ellipsized) exactOnly = true;
                emit(visible, from, to, ellipsized);
                return;
            }

            ParagraphSegments seg = segmented(paragraph, face, style.font(), style.wordBreak());
            for (int i = 0; i < seg.segs().length; i++) {
                append(seg.segs()[i], from + seg.offsets()[i], seg.trimmedW(i), seg.fullW(i), seg.joinW(i));
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
            float cand = lineWidth + join + trimmedWidth;
            if (bounded && cand > available + WRAP_TOLERANCE) {
                boolean breakAll = style.wordBreak() == WordBreak.BREAK_ALL;
                boolean tooLongAlone = trimmedWidth > available + WRAP_TOLERANCE;

                if (canSplit && (breakAll || tooLongAlone)) {
                    exactOnly = true;
                    if (!breakAll && lineWidth > 0) flush(offset, false);
                    splitInside(segment, offset);
                    return;
                }
                if (lineWidth > 0) {
                    wrapped = true;
                    minRejectedSum = Math.min(minRejectedSum, cand);
                    flush(offset, false);
                    join = 0;
                    if (canSplit) maxAcceptedSum = Math.max(maxAcceptedSum, trimmedWidth);
                }
            } else if (lineWidth > 0 || canSplit) {
                maxAcceptedSum = Math.max(maxAcceptedSum, cand);
            }
            line.append(segment);
            lineWidth += join + fullWidth;
        }

        /** 1 行に収まらない語を文字単位で割る。 */
        void splitInside(String segment, int offset) {
            exactOnly = true;
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
            float w = widthOf(text);
            if (w > maxLineWidth) maxLineWidth = w;
            lines.add(new ShapedLine(text, w, leadingAbove(height, ascent, descent) + ascent, height, start, end, ellipsized));
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
        int end = trimTrailingBlanksEnd(text);
        return end == text.length() ? text : text.substring(0, end);
    }

    private static int trimTrailingBlanksEnd(String text) {
        int end = text.length();
        while (end > 0 && isBlank(text.charAt(end - 1))) end--;
        return end;
    }

    private static int stripTrailingEnd(String text) {
        int end = text.length();
        while (end > 0) {
            int cp = Character.codePointBefore(text, end);
            if (!Character.isWhitespace(cp)) break;
            end -= Character.charCount(cp);
        }
        return end;
    }

    private static int stripLeadingStart(String text, int end) {
        int start = 0;
        while (start < end) {
            int cp = Character.codePointAt(text, start);
            if (!Character.isWhitespace(cp)) break;
            start += Character.charCount(cp);
        }
        return start;
    }
}
