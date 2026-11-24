package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxKind;
import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.css.StyleFacts;
import com.nishiyu.enderframelib.core.css.model.TextAlign;
import com.nishiyu.enderframelib.core.kernel.LayoutUnit;
import com.nishiyu.enderframelib.core.text.LineBreaker;
import com.nishiyu.enderframelib.core.text.TextShaper;
import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.text.model.FontStyle;
import com.nishiyu.enderframelib.core.text.model.WhiteSpace;
import com.nishiyu.enderframelib.core.text.model.WordBreak;

import java.util.ArrayList;
import java.util.List;

/**
 * インライン整形（Engine v3 M2a）。ブロックコンテナの中のインライン水準の子（文字の節・インライン要素）を平らに並べ、
 * 語の境で貪欲に折り返して {@link LineFragment} の列を作る。純粋な関数で、状態は呼び出しの局所だけ。
 *
 * <p>M2a の範囲: 空白の畳み（{@code normal} / {@code nowrap} / {@code pre-line}）・改行を保つ型（{@code pre} 系は改行だけで割る）・
 * {@code <br>}・インライン要素の左右の縁（余白＋枠線＋パディング）・{@code line-height}・{@code text-align}（左・中央・右）。
 * 縦の揃え（{@code vertical-align}）・双方向・{@code inline-block} の原子化・ブロックを含むインラインは M2b。</p>
 */
public final class InlineFlow {
    private static final float EPS = TextShaper.WRAP_TOLERANCE;

    private InlineFlow() { }

    /** 整形の結果。{@code height} は行の高さの和。{@code pending} は中の流れの外の箱の静的位置（内容ボックス基準）。 */
    record Result(LineFragment[] lines, float height, List<OutOfFlow.Pending> pending) { }

    /** {@code kids} を幅 {@code availW} で行に組む。{@code container} は行の支柱（strut）と揃えの持ち主。 */
    static Result layout(List<BoxNode> kids, Style container, float availW, LayoutSession s) {
        LineBuilder b = new LineBuilder(container, availW, s);
        for (BoxNode k : kids) b.add(k);
        return b.finish();
    }

    /** {@code kids} の内在サイズ（内容ボックスの幅）。 */
    static MinMaxSizes minMax(List<BoxNode> kids, LayoutSession s) {
        Measure m = new Measure(s);
        for (BoxNode k : kids) m.add(k);
        return m.result();
    }

    /** 1 行の高さ（{@code line-height}。{@code normal} は書体の推奨値）。 */
    public static float lineHeight(Style st, FontFace face) {
        StyleFacts f = st.facts;
        FontStyle fs = f.fontStyle;
        return switch (f.lineHeightKind) {
            case ComputedStyle.LH_PX -> LayoutUnit.snap(f.lineHeightValue);
            case ComputedStyle.LH_PERCENT, ComputedStyle.LH_EM, ComputedStyle.LH_NUMBER -> LayoutUnit.snap(f.lineHeightValue * fs.fontSizePx());
            default -> LayoutUnit.snap(face.normalLineHeight(fs));
        };
    }

    /** {@code from} より後ろで最初の折り返し候補の位置（無ければ {@code to}）。 */
    public static int nextBreak(String t, int from, int to, WordBreak wb) {
        for (int i = t.offsetByCodePoints(from, 1); i < to; i = t.offsetByCodePoints(i, 1)) {
            if (LineBreaker.canBreakBefore(t, i, wb)) return i;
        }
        return to;
    }

    private static boolean isBreak(BoxNode n) {
        return n.kind != BoxKind.TEXT && "br".equalsIgnoreCase(n.node.tag);
    }

    /** インライン要素の左右の縁（{@code [始端, 終端]}）。 */
    private static float[] edges(BoxNode n, float cbw, LayoutSession s) {
        BoxGeometry g = new BoxGeometry(n.style, cbw, s);
        return new float[]{g.mL + g.bL + g.pL, g.mR + g.bR + g.pR};
    }

    /** 行を組む（1 回の整形の間だけの可変の作業域）。 */
    private static final class LineBuilder {
        private final float availW, strut;
        private final TextAlign align;
        private final LayoutSession s;
        private final List<LineFragment> lines = new ArrayList<>();
        private final List<OutOfFlow.Pending> oofPending = new ArrayList<>();
        // 今の行
        private final List<BoxNode> pBox = new ArrayList<>();
        private int[] pStart = new int[8], pEnd = new int[8];
        private float[] pX = new float[8], pW = new float[8];
        private float x, lineH, pending, cursor;
        private boolean hasContent, wraps = true;

        LineBuilder(Style container, float availW, LayoutSession s) {
            this.availW = availW;
            this.s = s;
            this.strut = lineHeight(container, s.face(container));
            this.align = container.inherited.textAlign;
            this.lineH = strut;
        }

        void add(BoxNode n) {
            switch (n.kind) {
                case NONE -> { }
                case TEXT -> run(n);
                default -> {
                    if (OutOfFlow.is(n)) { // 流れの外: 今の行の今の位置を静的位置として渡す
                        oofPending.add(new OutOfFlow.Pending(n, x, cursor));
                        return;
                    }
                    if (isBreak(n)) {
                        newLine();
                        return;
                    }
                    float[] e = edges(n, availW, s);
                    x += e[0];
                    for (int i = 0, c = n.children.size(); i < c; i++) add(n.children.get(i));
                    x += e[1];
                }
            }
        }

        private void run(BoxNode box) {
            String t = box.text;
            if (t.isEmpty()) return;
            Style st = box.style;
            FontFace face = s.face(st);
            FontStyle fs = st.facts.fontStyle;
            float lh = lineHeight(st, face);
            WhiteSpace ws = st.facts.whiteSpace;
            wraps = ws.wraps();
            if (!ws.collapses()) {
                int from = 0;
                for (int nl; (nl = t.indexOf('\n', from)) >= 0; from = nl + 1) {
                    if (nl > from) place(box, from, nl, face.advance(t.substring(from, nl), fs), lh);
                    newLine();
                }
                if (from < t.length()) place(box, from, t.length(), face.advance(from == 0 ? t : t.substring(from), fs), lh);
                return;
            }
            float spaceW = face.advance(" ", fs);
            int n = t.length(), from = 0, to = n;
            if (t.charAt(0) == ' ') { pending = Math.max(pending, spaceW); from = 1; }
            boolean trailing = to > from && t.charAt(to - 1) == ' ';
            if (trailing) to--;
            if (to > from) {
                float whole = face.advance(from == 0 && to == n ? t : t.substring(from, to), fs);
                if (!wraps || x + (hasContent ? pending : 0) + whole <= availW + EPS) {
                    place(box, from, to, whole, lh); // 1 行に収まる: 1 断片で置く（語ごとに測らない）
                } else { // 折り返し候補（UAX #14。空白の無い和文も割れる）ごとに置く。候補の後ろの空白は次の語との間の空き
                    WordBreak wb = st.facts.wordBreak;
                    for (int w = from; w < to; ) {
                        int b = nextBreak(t, w, to, wb);
                        int core = b;
                        while (core > w && t.charAt(core - 1) == ' ') core--;
                        if (core > w) place(box, w, core, face.advance(t.substring(w, core), fs), lh);
                        if (core < b) pending = Math.max(pending, spaceW);
                        w = b;
                    }
                }
            }
            if (trailing) pending = Math.max(pending, spaceW);
        }

        /** 語（またはまとめて測った語の列）を置く。収まらなければ先に改行する。 */
        private void place(BoxNode box, int start, int end, float w, float lh) {
            float gap = hasContent ? pending : 0;
            if (wraps && hasContent && x + gap + w > availW + EPS) {
                newLine();
                gap = 0;
            }
            x += gap;
            pending = 0;
            int k = pBox.size() - 1;
            if (k >= 0 && pBox.get(k) == box && pEnd[k] <= start) {
                pEnd[k] = end;
                pW[k] = x + w - pX[k];
            } else {
                if (k + 1 == pStart.length) grow();
                pBox.add(box);
                pStart[k + 1] = start;
                pEnd[k + 1] = end;
                pX[k + 1] = x;
                pW[k + 1] = w;
            }
            x += w;
            lineH = Math.max(lineH, lh);
            hasContent = true;
        }

        private void grow() {
            int c = pStart.length * 2;
            pStart = java.util.Arrays.copyOf(pStart, c);
            pEnd = java.util.Arrays.copyOf(pEnd, c);
            pX = java.util.Arrays.copyOf(pX, c);
            pW = java.util.Arrays.copyOf(pW, c);
        }

        private void newLine() {
            int k = pBox.size();
            LineFragment.Piece[] pieces = new LineFragment.Piece[k];
            for (int i = 0; i < k; i++) pieces[i] = new LineFragment.Piece(pBox.get(i), pStart[i], pEnd[i], pX[i], pW[i]);
            float width = x;
            float off = 0;
            if (align == TextAlign.CENTER) off = Math.max(0, (availW - width) / 2f);
            else if (align == TextAlign.RIGHT || align == TextAlign.END) off = Math.max(0, availW - width);
            lines.add(new LineFragment(LayoutUnit.snap(off), cursor, width, lineH, pieces));
            cursor += lineH;
            pBox.clear();
            x = 0;
            lineH = strut;
            pending = 0;
            hasContent = false;
        }

        Result finish() {
            if (hasContent || !pBox.isEmpty()) newLine();
            return new Result(lines.toArray(new LineFragment[0]), cursor, oofPending);
        }
    }

    /** 内在サイズを測る（折り返せる位置で区切った最長の塊が min、全部を 1 行に並べた幅が max）。 */
    private static final class Measure {
        private final LayoutSession s;
        private float min, total, chunk, pending;
        private boolean any;

        Measure(LayoutSession s) {
            this.s = s;
        }

        void add(BoxNode n) {
            switch (n.kind) {
                case NONE -> { }
                case TEXT -> run(n);
                default -> {
                    if (OutOfFlow.is(n)) return;
                    if (isBreak(n)) {
                        breakHere();
                        return;
                    }
                    float[] e = edges(n, Float.NaN, s);
                    total += e[0];
                    chunk += e[0];
                    for (int i = 0, c = n.children.size(); i < c; i++) add(n.children.get(i));
                    total += e[1];
                    chunk += e[1];
                }
            }
        }

        private void breakHere() {
            min = Math.max(min, chunk);
            chunk = 0;
            pending = 0;
        }

        private void run(BoxNode box) {
            String t = box.text;
            if (t.isEmpty()) return;
            Style st = box.style;
            FontFace face = s.face(st);
            FontStyle fs = st.facts.fontStyle;
            WhiteSpace ws = st.facts.whiteSpace;
            if (!ws.collapses() || !ws.wraps()) {
                for (String seg : t.split("\n", -1)) {
                    float w = face.advance(seg, fs);
                    total += w;
                    chunk += w;
                    if (ws.preservesNewlines()) breakHere();
                }
                return;
            }
            float spaceW = face.advance(" ", fs);
            WordBreak wb = st.facts.wordBreak;
            int n = t.length();
            for (int w = 0; w < n; ) {
                int b = nextBreak(t, w, n, wb);
                int core = b;
                while (core > w && t.charAt(core - 1) == ' ') core--;
                int start = w;
                while (start < core && t.charAt(start) == ' ') start++;
                if (start > w) pending = spaceW;
                if (core > start) {
                    float ww = face.advance(t.substring(start, core), fs);
                    if (any) total += pending;
                    total += ww;
                    chunk += ww;
                    any = true;
                    pending = 0;
                }
                if (b < n) { // 候補で割れる
                    min = Math.max(min, chunk);
                    chunk = 0;
                    if (core < b) pending = spaceW;
                }
                w = b;
            }
        }

        MinMaxSizes result() {
            min = Math.max(min, chunk);
            return new MinMaxSizes(LayoutUnit.snap(min), LayoutUnit.snap(total));
        }
    }
}
