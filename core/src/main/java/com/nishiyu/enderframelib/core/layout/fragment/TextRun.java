package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.text.model.FontStyle;
import com.nishiyu.enderframelib.core.text.model.WhiteSpace;
import com.nishiyu.enderframelib.core.text.model.WordBreak;

/**
 * 文字の節 1 つの走査（Engine v3 M2b-1）。行を組む側（{@link InlineLineBuilder}）と内在サイズを測る側（{@link InlineMeasure}）が
 * 同じ規則で文字を割るよう、割り方をここ 1 か所に置く。走査は状態を持たず、割った単位を {@link Sink} へ順に渡す。
 *
 * <ul>
 *   <li>改行を保つ {@code white-space}（{@code pre} 系・{@code pre-line}）は改行で区間に割り、区間の間で {@link Sink#forcedBreak}。</li>
 *   <li>空白を畳む型（{@code normal} / {@code nowrap} / {@code pre-line}）: 前後の空白は {@link Sink#space}（次の語との間の空き）、
 *       中は折り返しの機会（UAX #14）ごとの語。{@code nowrap} は区間全体で 1 語。</li>
 *   <li>空白を保つ型（{@code pre} / {@code pre-wrap} / {@code break-spaces}）: タブは {@link Sink#tab}（位置で幅が決まる）。
 *       {@code pre} はタブの間を 1 語、{@code pre-wrap} は機会ごとの語で後ろの空白は行末にぶら下がる（{@code hang}）、
 *       {@code break-spaces} は語と空白 1 つずつを別の単位にし、空白も幅を取る（CSS Text 3 §3・§4.1.3）。</li>
 * </ul>
 *
 * <p>{@code first} はその区間の最初の単位で、前の節との境目の機会は受け手が {@link InlineFlow#breaksBetween} で決める。
 * 2 つ目以降の単位の前は、その節の {@code white-space} が折り返しを許せば機会。</p>
 */
final class TextRun {
    /** 割った単位の受け手。 */
    interface Sink {
        /** 折り返せる区間を、まず 1 語として行に収まるか試すか（行を組む側。測る側は語ごとに測るので試さない）。 */
        boolean triesWhole();

        /** 区間をまとめて 1 語として置いてよいか（行に収まる）。{@link #triesWhole} の受け手だけが呼ばれる。 */
        boolean fits(float w);

        /** 畳まれる空白（次の語との間の空き）。{@code wraps} はその空白の {@code white-space} が折り返しを許すか。 */
        void space(float w, boolean wraps);

        /** 語 {@code [start, end)}。{@code hang} は幅 {@code w} のうち行末にぶら下がる後ろの空白の幅。 */
        void word(int start, int end, float w, float hang, boolean first);

        /** 保った空白の中のタブ（次のタブ位置まで進む）。 */
        void tab(int at, boolean first);

        /** 保った改行。 */
        void forcedBreak();

        /**
         * これから測る語の書式（既定は走査した節の書式 {@code scanned}）。行を組む側は {@code ::first-line} の 1 行目の間だけ別の
         * スタイルで測り、1 行目を閉じたら節の本来の書式へ戻す（文字・{@code white-space} は同じ）。
         */
        default Metrics metrics(Metrics scanned) {
            return scanned;
        }
    }

    /**
     * 1 つの節の文字の書式（走査の間だけ使う）。{@code spaceW} は畳まれる空白 1 つの幅（{@code word-spacing} を含む）、
     * {@code wordSpacing} は語の区切りの文字（空白・改行しない空白）ごとに足す幅（CSS Text 3 §8.1。旧経路は持たない）。
     */
    record Metrics(BoxNode box, Style style, FontFace face, FontStyle fs, WhiteSpace ws, WordBreak wb, float spaceW,
                  float ascent, float descent, float wordSpacing, String measured) {
        static Metrics of(BoxNode box, LayoutSession s) {
            return of(box, box.style, s);
        }

        /** 節 {@code box} を、スタイル {@code st}（{@code ::first-line} を掛けたものなど）で測る書式。 */
        static Metrics of(BoxNode box, Style st, LayoutSession s) {
            FontFace face = s.face(st);
            FontStyle fs = st.facts.fontStyle;
            float lh = InlineFlow.lineHeight(st, face);
            float ra = InlineFlow.runBaseline(st, s);
            float wsp = wordSpacing(st, s);
            // ::first-line の text-transform は測る文字だけに掛ける（長さは同じなので折り返しの機会と片の位置は節の中身のまま）
            String measured = st == box.style ? box.text : FirstLine.transform(box.text, box.style, st);
            return new Metrics(box, st, face, fs, st.facts.whiteSpace, st.facts.wordBreak, face.advance(" ", fs) + wsp, ra, lh - ra, wsp,
                    measured);
        }

        private static float wordSpacing(Style st, LayoutSession s) {
            long v = st.inherited.wordSpacing;
            if (!com.nishiyu.enderframelib.core.css.CssLength.isDeclared(v) || com.nishiyu.enderframelib.core.css.CssLength.isKeyword(v)) return 0;
            // % は自分の書体の大きさ（1em）が基準で、% のまま継承する（CSS Text 4 §8.1）
            return st.px(v, st.inherited.exprs, st.facts.fontStyle.fontSizePx(), s.viewportW(), s.viewportH());
        }

        /**
         * {@code [start, end)} の幅。{@code letter-spacing} は同じ要素の文字の間に入り、節の最後の文字の後（要素の境目）には足さない
         * （CSS Text 3 §8.2。旧経路と同じ）ので、節の末尾で終わる区間からは 1 つ分を引く。行を組む側と測る側が同じ幅を使う。
         */
        float advance(int start, int end) {
            String t = measured;
            float w = face.advance(start == 0 && end == t.length() ? t : t.substring(start, end), fs);
            if (wordSpacing != 0) { // 区間の中の語の区切りの文字（保った空白・1 語にまとめた区間の空白）
                for (int i = start; i < end; i++) {
                    char c = t.charAt(i);
                    if (c == ' ' || c == '\u00A0') w += wordSpacing;
                }
            }
            return end == t.length() ? w - fs.letterSpacingPx() : w;
        }
    }

    private TextRun() { }

    static void scan(Metrics r, Sink k) {
        String t = r.box.text;
        if (r.ws.preservesNewlines()) {
            int from = 0;
            for (int nl; (nl = t.indexOf('\n', from)) >= 0; from = nl + 1) {
                segment(r, from, nl, k);
                k.forcedBreak();
            }
            segment(r, from, t.length(), k);
        } else {
            segment(r, 0, t.length(), k);
        }
    }

    private static void segment(Metrics r, int a, int b, Sink k) {
        if (a >= b) return;
        if (r.ws.collapses()) collapsed(r, a, b, k);
        else preserved(r, a, b, k);
    }

    private static void collapsed(Metrics r, int a, int b, Sink k) {
        String t = r.box.text;
        int from = a, to = b;
        if (t.charAt(from) == ' ') {
            k.space(k.metrics(r).spaceW, r.ws.wraps());
            from++;
        }
        boolean trailing = to > from && t.charAt(to - 1) == ' ';
        if (trailing) to--;
        if (to > from) {
            if (!r.ws.wraps()) {
                Metrics m = k.metrics(r);
                float w = m.advance(from, to);
                k.word(from, to, w, otherSpaces(m, from, to, w), true);
            } else {
                // word-spacing があれば語ごとに置く（一時アダプタが片を語ごとに割って書体の幅で並べ直すので、片を語に揃える）
                Metrics m0 = k.metrics(r);
                float whole = k.triesWhole() && m0.wordSpacing == 0 ? m0.advance(from, to) : Float.NaN;
                if (!Float.isNaN(whole) && k.fits(whole)) {
                    k.word(from, to, whole, otherSpaces(m0, from, to, whole), true); // 1 行に収まる: 語ごとに測らない
                } else {
                    for (int w = from; w < to; ) {
                        int brk = InlineFlow.nextBreak(t, w, to, r.wb);
                        int core = brk;
                        while (core > w && t.charAt(core - 1) == ' ') core--;
                        if (core > w) {
                            Metrics m = k.metrics(r);
                            float ww = m.advance(w, core);
                            k.word(w, core, ww, otherSpaces(m, w, core, ww), w == from);
                        }
                        if (core < brk) k.space(k.metrics(r).spaceW, true);
                        w = brk;
                    }
                }
            }
        }
        if (trailing) k.space(k.metrics(r).spaceW, r.ws.wraps());
    }

    private static void preserved(Metrics r, int a, int b, Sink k) {
        String t = r.box.text;
        boolean wraps = r.ws.wraps(), bs = r.ws.breaksSpaces();
        int tab = t.indexOf('\t', a);
        if ((tab < 0 || tab >= b) && (!wraps || k.triesWhole())) {
            Metrics m = k.metrics(r);
            float whole = m.advance(a, b);
            if (!wraps || k.fits(whole)) {
                k.word(a, b, whole, wraps && !bs ? trailingSpaces(m, a, b, whole) : 0, true);
                return;
            }
        }
        for (int i = a; i < b; ) {
            char c = t.charAt(i);
            if (c == '\t') {
                k.tab(i, i == a);
                i++;
                continue;
            }
            int j;
            if (!wraps) {
                j = nextTab(t, i, b);
            } else if (bs && c == ' ') {
                j = i + 1;
            } else {
                j = Math.min(InlineFlow.nextBreak(t, i, b, r.wb), nextTab(t, i, b));
                if (bs) {
                    int sp = t.indexOf(' ', i);
                    if (sp > i && sp < j) j = sp;
                }
            }
            Metrics m = k.metrics(r);
            float w = m.advance(i, j);
            k.word(i, j, w, wraps && !bs ? trailingSpaces(m, i, j, w) : 0, i == a);
            i = j;
        }
    }

    /**
     * 畳む型の語の後ろの「その他の空白」（U+0020・改行しない空白を除く Zs。U+1680 など）の幅。行末にぶら下がり、行の幅と
     * 内在サイズに数えない（CSS Text 3 §4.1.3 の段階 II）。
     */
    private static float otherSpaces(Metrics r, int a, int b, float w) {
        String t = r.box.text;
        int core = b;
        while (core > a && hangingSpace(t.charAt(core - 1))) core--;
        if (core == b) return 0;
        return core == a ? w : w - r.advance(a, core);
    }

    /** 語 {@code [a, b)}（幅 {@code w}）のうち行末にぶら下がる幅（{@link Sink#word} の {@code hang} と同じ規則。測り直した語に使う）。 */
    static float hangOf(Metrics r, int a, int b, float w) {
        if (r.ws.collapses()) return otherSpaces(r, a, b, w);
        return r.ws.wraps() && !r.ws.breaksSpaces() ? trailingSpaces(r, a, b, w) : 0;
    }

    private static boolean hangingSpace(char c) {
        return c != ' ' && c != ' ' && c != ' ' && c != ' ' && Character.getType(c) == Character.SPACE_SEPARATOR;
    }

    private static int nextTab(String t, int from, int to) {
        int i = t.indexOf('\t', from);
        return i < 0 || i > to ? to : i;
    }

    /** {@code [a, b)}（幅 {@code w}）の後ろの空白の幅（{@code pre-wrap} で行末にぶら下がる）。 */
    private static float trailingSpaces(Metrics r, int a, int b, float w) {
        String t = r.box.text;
        int core = b;
        while (core > a && t.charAt(core - 1) == ' ') core--;
        if (core == b) return 0;
        return core == a ? w : w - r.advance(a, core);
    }
}
