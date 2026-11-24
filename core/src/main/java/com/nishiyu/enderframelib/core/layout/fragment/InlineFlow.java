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

    /**
     * {@code kids} を幅 {@code availW} で行に組む。{@code container} は行の支柱（strut）と揃えの持ち主。{@code prior} は持ち主の
     * 前回の断片（行に置いた原子の候補を引く。無ければ null）。
     */
    static Result layout(List<BoxNode> kids, Style container, float availW, LayoutFragment prior, LayoutSession s) {
        return layout(kids, container, availW, prior, Floats.Space.EMPTY, 0, s);
    }

    /** float を避けて組む（{@code exc} は容器の内容ボックス基準、{@code originY} はこの行の並びの上端の容器での位置）。 */
    static Result layout(List<BoxNode> kids, Style container, float availW, LayoutFragment prior, Floats.Space exc, float originY,
                         LayoutSession s) {
        LineBuilder b = new LineBuilder(container, availW, prior, exc, originY, s);
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

    /** {@code text-indent}（宣言が無ければ 0。% は {@code availW} が基準）。 */
    static float textIndent(Style st, float availW, LayoutSession s) {
        long v = st.inherited.textIndent;
        if (!com.nishiyu.enderframelib.core.css.CssLength.isDeclared(v) || com.nishiyu.enderframelib.core.css.CssLength.isKeyword(v)) return 0;
        return LayoutUnit.snap(st.px(v, st.inherited.exprs, Float.isFinite(availW) ? availW : 0, s.viewportW(), s.viewportH()));
    }

    /** 文字の並びの上端からベースラインまで（半分の行間 + ascent）。 */
    public static float runBaseline(Style st, LayoutSession s) {
        FontFace face = s.face(st);
        FontStyle fs = st.facts.fontStyle;
        float asc = face.ascent(fs), desc = face.descent(fs);
        return TextShaper.leadingAbove(lineHeight(st, face), asc, desc) + asc;
    }

    /**
     * 断片の border-box の上端からベースラインまで（無ければ NaN）。{@code first} なら最初の行（flex のベースライン）、
     * そうでなければ最後の行（{@code inline-block} のベースライン）。{@code overflow} が visible でない {@code inline-block} は NaN
     * （呼び出し側が下の余白の端を使う）。
     */
    static float baselineOf(LayoutFragment f, boolean first) {
        if (f.lines != null && f.lines.length > 0) {
            LineFragment l = f.lines[first ? 0 : f.lines.length - 1];
            return f.insetTop + l.y() + l.baseline();
        }
        if (f.flow == null) return Float.NaN;
        FlowChunk[] cs = f.flow.chunks;
        for (int k = first ? 0 : cs.length - 1; first ? k < cs.length : k >= 0; k += first ? 1 : -1) {
            FlowChunk c = cs[k];
            for (int i = first ? 0 : c.frags.length - 1; first ? i < c.frags.length : i >= 0; i += first ? 1 : -1) {
                LayoutFragment ch = c.frags[i];
                if (ch.collapseThrough) continue;
                float b = baselineOf(ch, first);
                if (!Float.isNaN(b)) return f.insetTop + f.flow.chunkY[k] + c.y[i] + b;
            }
        }
        return Float.NaN;
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

    /** 行を組む（1 回の整形の間だけの可変の作業域）。行の高さはベースラインより上の最大 ＋ 下の最大。 */
    private static final class LineBuilder {
        private final float availW, strutAsc, strutDesc;
        private final TextAlign align;
        private final LayoutSession s;
        private final LayoutFragment prior;
        private java.util.Map<com.nishiyu.enderframelib.core.dom.node.HtmlNode, LayoutFragment> priorAtoms;
        // 今の行の原子
        private final List<LayoutFragment> aFrag = new ArrayList<>();
        private final List<float[]> aPos = new ArrayList<>(); // {x, ascent, marginTop}
        private final List<LineFragment> lines = new ArrayList<>();
        private final List<OutOfFlow.Pending> oofPending = new ArrayList<>();
        // 今の行
        private final List<BoxNode> pBox = new ArrayList<>();
        private int[] pStart = new int[8], pEnd = new int[8];
        private float[] pX = new float[8], pW = new float[8];
        private float x, asc, desc, pending, cursor;
        private boolean hasContent, wraps = true;
        private final Floats.Space exc;
        private final float originY, strutH;
        /** 今の行の左端と使える幅（float を避けた後）。 */
        private float lineL, lineW;

        LineBuilder(Style container, float availW, LayoutFragment prior, Floats.Space exc, float originY, LayoutSession s) {
            this.exc = exc;
            this.originY = originY;
            this.availW = availW;
            this.s = s;
            this.prior = prior;
            float strut = lineHeight(container, s.face(container));
            this.strutAsc = runBaseline(container, s);
            this.strutDesc = strut - strutAsc;
            this.align = container.inherited.textAlign;
            this.asc = strutAsc;
            this.desc = strutDesc;
            this.strutH = strut;
            this.x = textIndent(container, availW, s); // 最初の行だけの字下げ（% は内容ボックスの幅が基準）
            startLine();
        }

        /** 行の始めに、float を避けた左端と使える幅を決める（行の高さは支柱で見積もる）。 */
        private void startLine() {
            if (exc.isEmpty()) {
                lineL = 0;
                lineW = availW;
                return;
            }
            float[] av = exc.available(originY + cursor, originY + cursor + Math.max(strutH, 0.01f), availW);
            lineL = av[0];
            lineW = Math.max(0, av[1] - av[0]);
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
                    if (n.kind.atomicInline()) {
                        atom(n);
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
            float ra = runBaseline(st, s), rd = lh - ra;
            WhiteSpace ws = st.facts.whiteSpace;
            wraps = ws.wraps();
            if (!ws.collapses()) {
                int from = 0;
                for (int nl; (nl = t.indexOf('\n', from)) >= 0; from = nl + 1) {
                    if (nl > from) place(box, from, nl, face.advance(t.substring(from, nl), fs), ra, rd);
                    newLine();
                }
                if (from < t.length()) place(box, from, t.length(), face.advance(from == 0 ? t : t.substring(from), fs), ra, rd);
                return;
            }
            float spaceW = face.advance(" ", fs);
            int n = t.length(), from = 0, to = n;
            if (t.charAt(0) == ' ') { pending = Math.max(pending, spaceW); from = 1; }
            boolean trailing = to > from && t.charAt(to - 1) == ' ';
            if (trailing) to--;
            if (to > from) {
                float whole = face.advance(from == 0 && to == n ? t : t.substring(from, to), fs);
                if (!wraps || x + (hasContent ? pending : 0) + whole <= lineW + EPS) {
                    place(box, from, to, whole, ra, rd); // 1 行に収まる: 1 断片で置く（語ごとに測らない）
                } else { // 折り返し候補（UAX #14。空白の無い和文も割れる）ごとに置く。候補の後ろの空白は次の語との間の空き
                    WordBreak wb = st.facts.wordBreak;
                    for (int w = from; w < to; ) {
                        int b = nextBreak(t, w, to, wb);
                        int core = b;
                        while (core > w && t.charAt(core - 1) == ' ') core--;
                        if (core > w) place(box, w, core, face.advance(t.substring(w, core), fs), ra, rd);
                        if (core < b) pending = Math.max(pending, spaceW);
                        w = b;
                    }
                }
            }
            if (trailing) pending = Math.max(pending, spaceW);
        }

        /** 語（またはまとめて測った語の列）を置く。収まらなければ先に改行する。 */
        private void place(BoxNode box, int start, int end, float w, float ra, float rd) {
            float gap = hasContent ? pending : 0;
            if (wraps && hasContent && x + gap + w > lineW + EPS) {
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
            asc = Math.max(asc, ra);
            desc = Math.max(desc, rd);
            hasContent = true;
        }

        /**
         * インライン水準の原子（{@code inline-block} など）を 1 つの塊として置く。幅は指定の幅か、縮小して合わせた幅
         * （{@code min(max(min-content, 使える幅), max-content)}）。ベースライン（無ければ下の余白の端）を行のベースラインへ揃える。
         */
        private void atom(BoxNode n) {
            Style cs = n.style;
            BoxGeometry g = new BoxGeometry(cs, availW, s);
            float w = g.specifiedWidth(cs, availW, s);
            if (Float.isNaN(w)) {
                MinMaxSizes mm = s.cache().minMax(n, s);
                w = Math.min(Math.max(mm.min, availW - g.mL - g.mR), mm.max);
            }
            w = LayoutUnit.snap(g.clampWidth(cs, Math.max(0, w), availW, s));
            ConstraintSpace space = s.space(availW, w, Float.NaN, Float.NaN, ConstraintSpace.FORMATTING_ROOT);
            LayoutFragment f = s.cache().layout(n, space, priorAtom(n), s);
            float outerW = g.mL + f.width + g.mR;
            float gap = hasContent ? pending : 0;
            if (wraps && hasContent && x + gap + outerW > lineW + EPS) {
                newLine();
                gap = 0;
            }
            x += gap;
            pending = 0;
            boolean overflowVisible = (cs.box.overflowX == null || cs.box.overflowX == com.nishiyu.enderframelib.core.css.model.Overflow.VISIBLE)
                    && (cs.box.overflowY == null || cs.box.overflowY == com.nishiyu.enderframelib.core.css.model.Overflow.VISIBLE);
            float b = n.kind == BoxKind.INLINE_BLOCK && !overflowVisible ? Float.NaN : baselineOf(f, n.kind == BoxKind.INLINE_FLEX);
            float outerH = g.mT + f.height + g.mB;
            float a = Float.isNaN(b) ? outerH : g.mT + b; // ベースラインが無ければ下の余白の端
            aFrag.add(f);
            aPos.add(new float[]{x + g.mL, a, g.mT});
            asc = Math.max(asc, a);
            desc = Math.max(desc, outerH - a);
            x += outerW;
            hasContent = true;
        }

        private LayoutFragment priorAtom(BoxNode n) {
            if (prior == null || prior.lines == null) return null;
            if (priorAtoms == null) {
                priorAtoms = new java.util.IdentityHashMap<>();
                for (LineFragment l : prior.lines) {
                    for (PlacedFragment p : l.atoms()) if (p.frag().box != null) priorAtoms.put(p.frag().box.node, p.frag());
                }
            }
            return priorAtoms.get(n.node);
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
            float off = lineL;
            if (align == TextAlign.CENTER) off += Math.max(0, (lineW - width) / 2f);
            else if (align == TextAlign.RIGHT || align == TextAlign.END) off += Math.max(0, lineW - width);
            PlacedFragment[] atoms = LineFragment.NO_ATOMS;
            if (!aFrag.isEmpty()) {
                atoms = new PlacedFragment[aFrag.size()];
                for (int i = 0; i < atoms.length; i++) {
                    float[] p = aPos.get(i);
                    atoms[i] = new PlacedFragment(aFrag.get(i), p[0], LayoutUnit.snap(asc - p[1] + p[2]));
                }
                aFrag.clear();
                aPos.clear();
            }
            float lineH = LayoutUnit.snap(asc + desc);
            lines.add(new LineFragment(LayoutUnit.snap(off), cursor, width, lineH, LayoutUnit.snap(asc), pieces, atoms));
            cursor += lineH;
            pBox.clear();
            x = 0;
            asc = strutAsc;
            desc = strutDesc;
            startLine();
            pending = 0;
            hasContent = false;
        }

        Result finish() {
            if (hasContent || !pBox.isEmpty() || !aFrag.isEmpty()) newLine();
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
                    if (n.kind.atomicInline()) { // 割れない塊: 親への寄与（余白込み）
                        BoxGeometry g = new BoxGeometry(n.style, Float.NaN, s);
                        MinMaxSizes c = g.contribution(n.style, s.cache().minMax(n, s), s);
                        float m = BlockFlow.fixedMargin(n.style, n.style.box.marginLeft, s) + BlockFlow.fixedMargin(n.style, n.style.box.marginRight, s);
                        if (any) total += pending;
                        total += c.max + m;
                        chunk += c.min + m;
                        any = true;
                        pending = 0;
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
                } else if (core < b) { // 末尾の空白: 次の節（インライン要素の文字など）との間の空き
                    min = Math.max(min, chunk);
                    chunk = 0;
                    pending = spaceW;
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
