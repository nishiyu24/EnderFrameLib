package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxKind;
import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.css.model.Overflow;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.kernel.LayoutUnit;
import com.nishiyu.enderframelib.core.text.model.WhiteSpace;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 行を組む（Engine v3 M2a・M2b-1）。1 回の整形の間だけの可変の作業域で、結果は不変の {@link LineFragment} の列。
 * 行の高さはベースラインより上の最大 ＋ 下の最大（文字片も原子もベースラインへ揃える）。
 *
 * <p>文字は {@link TextRun} が割った単位で受け取り、折り返しの機会（{@link InlineFlow} の規則）で貪欲に折り返す。
 * 行の始めに float を避けた左端と幅を決める。行を閉じるときに {@link LineAlign} で揃える（両端揃えは片を割ってずらす）。</p>
 */
final class InlineLineBuilder implements TextRun.Sink {
    private static final float EPS = InlineFlow.EPS;

    private final float availW, strutAsc, strutDesc, strutH, originY;
    private final LineAlign align;
    private final WhiteSpace containerWs;
    private final LayoutSession s;
    private final LayoutFragment prior;
    private final Floats.Space exc;
    private Map<HtmlNode, LayoutFragment> priorAtoms;
    private final List<LineFragment> lines = new ArrayList<>();
    private final List<OutOfFlow.Pending> oofPending = new ArrayList<>();
    // 今の行の原子
    private final List<LayoutFragment> aFrag = new ArrayList<>();
    private final List<float[]> aPos = new ArrayList<>(); // {x, ascent, marginTop}
    // 今の行の文字片
    private final List<BoxNode> pBox = new ArrayList<>();
    private int[] pStart = new int[8], pEnd = new int[8];
    private float[] pX = new float[8], pW = new float[8];
    private boolean[] pGap = new boolean[8];
    // 今の行の位置と、折り返しの判定に要る直前の内容
    private float x, asc, desc, pending, cursor, lineL, lineW;
    private boolean hasContent, pendingWraps, afterWrapSpace;
    private int lastChar = -1;
    /** 走査中の節の書式。 */
    private TextRun.Metrics run;

    InlineLineBuilder(Style container, float availW, LayoutFragment prior, Floats.Space exc, float originY, LayoutSession s) {
        this.exc = exc;
        this.originY = originY;
        this.availW = availW;
        this.s = s;
        this.prior = prior;
        float strut = InlineFlow.lineHeight(container, s.face(container));
        this.strutAsc = InlineFlow.runBaseline(container, s);
        this.strutDesc = strut - strutAsc;
        this.strutH = strut;
        this.align = LineAlign.of(container);
        this.containerWs = container.facts.whiteSpace;
        this.asc = strutAsc;
        this.desc = strutDesc;
        this.x = InlineFlow.textIndent(container, availW, s); // 最初の行だけの字下げ（% は内容ボックスの幅が基準）
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
        add(n, containerWs);
    }

    /** {@code ws} は {@code n} の親（インラインの持ち主）の {@code white-space}（原子の前後の機会を決める）。 */
    private void add(BoxNode n, WhiteSpace ws) {
        switch (n.kind) {
            case NONE -> { }
            case TEXT -> text(n);
            default -> {
                if (OutOfFlow.is(n)) { // 流れの外: 今の行の今の位置を静的位置として渡す
                    oofPending.add(new OutOfFlow.Pending(n, x, cursor));
                    return;
                }
                if (InlineFlow.isBreak(n)) {
                    newLine(false);
                    return;
                }
                if (InlineFlow.isWordBreakOpportunity(n)) {
                    if (ws.wraps()) afterWrapSpace = true;
                    return;
                }
                if (n.kind.atomicInline()) {
                    atom(n, ws);
                    return;
                }
                float[] e = InlineFlow.edges(n, availW, s);
                x += e[0];
                WhiteSpace inner = n.style.facts.whiteSpace;
                for (int i = 0, c = n.children.size(); i < c; i++) add(n.children.get(i), inner);
                x += e[1];
            }
        }
    }

    private void text(BoxNode box) {
        String t = box.text;
        if (t.isEmpty()) return;
        run = TextRun.Metrics.of(box, s);
        TextRun.scan(run, this);
        run = null;
    }

    // ---- TextRun.Sink ----

    @Override
    public boolean triesWhole() {
        return true;
    }

    @Override
    public boolean fits(float w) {
        return x + (hasContent ? pending : 0) + w <= lineW + EPS;
    }

    @Override
    public void space(float w, boolean wraps) {
        pending = Math.max(pending, w);
        pendingWraps |= wraps;
    }

    @Override
    public void word(int start, int end, float w, float hang, boolean first) {
        boolean breakable = first ? boundary(run.box().text.charAt(start)) : run.ws().wraps();
        float gap = hasContent ? pending : 0;
        if (breakable && hasContent && x + gap + w - hang > lineW + EPS) {
            newLine(true);
            gap = 0;
        }
        x += gap;
        clearPending();
        addPiece(run.box(), start, end, w, gap > 0);
        x += w;
        grow(run.ascent(), run.descent());
        lastChar = run.box().text.charAt(end - 1);
        afterWrapSpace = run.ws().wraps() && lastChar == ' ';
    }

    @Override
    public void tab(int at, boolean first) {
        x += hasContent ? pending : 0;
        clearPending();
        float tabW = InlineFlow.tabWidth(run.style(), run.face(), run.fs(), s);
        x = InlineFlow.tabStop(x, tabW, run.spaceW());
        grow(run.ascent(), run.descent());
        lastChar = '\t';
        afterWrapSpace = run.ws().wraps();
    }

    @Override
    public void forcedBreak() {
        newLine(false);
    }

    /** 前の内容との境目が折り返しの機会か（間の畳まれた空白・保った空白・語の続きでないこと）。 */
    private boolean boundary(int c) {
        if (!hasContent) return false;
        if (pending > 0) return pendingWraps;
        if (afterWrapSpace) return true;
        return run.ws().wraps() && InlineFlow.breaksBetween(lastChar, c, run.wb());
    }

    private void clearPending() {
        pending = 0;
        pendingWraps = false;
    }

    private void grow(float a, float d) {
        asc = Math.max(asc, a);
        desc = Math.max(desc, d);
        hasContent = true;
    }

    /** 文字片を足す。同じ節の直前の片に空白だけを挟んで続くなら 1 つにまとめる（タブはまとめない）。 */
    private void addPiece(BoxNode box, int start, int end, float w, boolean gap) {
        int k = pBox.size() - 1;
        if (k >= 0 && pBox.get(k) == box && pEnd[k] <= start && spacesOnly(box.text, pEnd[k], start)) {
            pEnd[k] = end;
            pW[k] = x + w - pX[k];
            return;
        }
        if (k + 1 == pStart.length) {
            int c = pStart.length * 2;
            pStart = Arrays.copyOf(pStart, c);
            pEnd = Arrays.copyOf(pEnd, c);
            pX = Arrays.copyOf(pX, c);
            pW = Arrays.copyOf(pW, c);
            pGap = Arrays.copyOf(pGap, c);
        }
        pBox.add(box);
        pStart[k + 1] = start;
        pEnd[k + 1] = end;
        pX[k + 1] = x;
        pW[k + 1] = w;
        pGap[k + 1] = gap;
    }

    private static boolean spacesOnly(String t, int from, int to) {
        for (int i = from; i < to; i++) if (t.charAt(i) != ' ') return false;
        return true;
    }

    /**
     * インライン水準の原子（{@code inline-block} など）を 1 つの塊として置く。幅は指定の幅か、縮小して合わせた幅
     * （{@code min(max(min-content, 使える幅), max-content)}）。ベースライン（無ければ下の余白の端）を行のベースラインへ揃える。
     * 前後は表意文字と同じく折り返しの機会（親の {@code white-space} が許すとき）。
     */
    private void atom(BoxNode n, WhiteSpace ws) {
        Style cs = n.style;
        BoxGeometry g = new BoxGeometry(cs, availW, s);
        float w = g.specifiedWidth(n, availW, availW - g.mL - g.mR, s);
        if (Float.isNaN(w)) {
            MinMaxSizes mm = s.cache().minMax(n, s);
            w = Math.min(Math.max(mm.min, availW - g.mL - g.mR), mm.max);
        }
        w = LayoutUnit.snap(g.clampWidth(cs, Math.max(0, w), availW, s));
        ConstraintSpace space = s.space(availW, w, Float.NaN, Float.NaN, ConstraintSpace.FORMATTING_ROOT);
        LayoutFragment f = s.cache().layout(n, space, priorAtom(n), s);
        float outerW = g.mL + f.width + g.mR;
        float gap = hasContent ? pending : 0;
        boolean breakable = hasContent && (pending > 0 ? pendingWraps : afterWrapSpace || ws.wraps());
        if (breakable && x + gap + outerW > lineW + EPS) {
            newLine(true);
            gap = 0;
        }
        x += gap;
        clearPending();
        boolean overflowVisible = (cs.box.overflowX == null || cs.box.overflowX == Overflow.VISIBLE)
                && (cs.box.overflowY == null || cs.box.overflowY == Overflow.VISIBLE);
        // inline-block は最後の行・inline-flex は最初の行のベースライン。overflow が visible でない inline-block は下の余白の端
        float b = n.kind == BoxKind.INLINE_BLOCK && !overflowVisible ? Float.NaN : InlineFlow.baselineOf(f, n.kind == BoxKind.INLINE_FLEX);
        float outerH = g.mT + f.height + g.mB;
        float a = Float.isNaN(b) ? outerH : g.mT + b;
        aFrag.add(f);
        aPos.add(new float[]{x + g.mL, a, g.mT});
        grow(a, outerH - a);
        x += outerW;
        lastChar = InlineFlow.ATOM_CHAR;
        afterWrapSpace = false;
    }

    private LayoutFragment priorAtom(BoxNode n) {
        if (prior == null || prior.lines == null) return null;
        if (priorAtoms == null) {
            priorAtoms = new IdentityHashMap<>();
            for (LineFragment l : prior.lines) {
                for (PlacedFragment p : l.atoms()) if (p.frag().box != null) priorAtoms.put(p.frag().box.node, p.frag());
            }
        }
        return priorAtoms.get(n.node);
    }

    /** 行を閉じる。{@code soft} は折り返した行（揃えは {@code text-align}）、そうでなければ最終行の扱い（{@code text-align-last}）。 */
    private void newLine(boolean soft) {
        int k = pBox.size();
        LineFragment.Piece[] pieces = new LineFragment.Piece[k];
        for (int i = 0; i < k; i++) pieces[i] = new LineFragment.Piece(pBox.get(i), pStart[i], pEnd[i], pX[i], pW[i]);
        float width = x, off = lineL;
        float[] atomX = new float[aFrag.size()];
        for (int i = 0; i < atomX.length; i++) atomX[i] = aPos.get(i)[0];
        LineFragment.Piece[] justified = align.justifies(soft) && k > 0
                ? align.justify(pieces, Arrays.copyOf(pGap, k), lineW - width, atomX, s) : null;
        if (justified != null) {
            pieces = justified;
            width = Math.max(width, lineW);
        } else {
            off += align.offset(soft, lineW, width);
        }
        PlacedFragment[] atoms = LineFragment.NO_ATOMS;
        if (!aFrag.isEmpty()) {
            atoms = new PlacedFragment[aFrag.size()];
            for (int i = 0; i < atoms.length; i++) {
                float[] p = aPos.get(i);
                atoms[i] = new PlacedFragment(aFrag.get(i), atomX[i], LayoutUnit.snap(asc - p[1] + p[2]));
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
        clearPending();
        hasContent = false;
        afterWrapSpace = false;
        lastChar = -1;
    }

    InlineFlow.Result finish() {
        if (hasContent || !pBox.isEmpty() || !aFrag.isEmpty()) newLine(false);
        return new InlineFlow.Result(lines.toArray(new LineFragment[0]), cursor, oofPending);
    }
}
