package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxKind;
import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.css.model.Overflow;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.kernel.LayoutUnit;
import com.nishiyu.enderframelib.core.text.TextShaper;
import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.text.model.FontStyle;
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

    private final float availW, strutAsc, strutDesc, strutH, originY, shrink;
    /** 原子の % の高さの基準（容器の確定した内容の高さ。不定なら NaN。CSS 2 §10.5）。 */
    private final float pctH;
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
    private final List<float[]> aPos = new ArrayList<>(); // {x, ascent, marginTop, 揃え, 外側の高さ}
    // 今の行の文字片
    private final List<BoxNode> pBox = new ArrayList<>();
    private int[] pStart = new int[8], pEnd = new int[8];
    private float[] pX = new float[8], pW = new float[8];
    /** 片の前の空白の始まりの x（両端揃えの機会の位置。前に空白が無ければ NaN）。 */
    private float[] pGapAt = new float[8];
    // 文字片の縦の揃え（vertical-align）: 持ち上げ量・揃え・並びの上端からベースライン・並びの高さ
    private float[] pRaise = new float[8], pAbove = new float[8], pHt = new float[8];
    private byte[] pVa = new byte[8];
    // インラインの祖先と、それぞれの親のスタイル（vertical-align の持ち上げを足し合わせる）
    private final Style container;
    private final List<BoxNode> inl = new ArrayList<>();
    private final List<Style> inlParent = new ArrayList<>();
    private int vaDepth;
    // 開いているインライン要素の箱（外側が先）と、今の行に出したその分（行を閉じるときに縦の位置を決めて Span にする）
    private final List<Open> open = new ArrayList<>();
    private final List<SpanAt> spanAt = new ArrayList<>();
    /** 今の節の直後に閉じるインライン要素の終了端の幅（右の padding・border・margin。最後の語と一緒に行に入る）。 */
    private float trail;
    /** 箱の開始端の前へ出した畳まれる空白の幅（行末なら行の幅に数えない）と、次の片の前に空白があった印（両端揃えの機会）。 */
    private float trailingSpace, gapCarryAt;
    private boolean gapCarry;
    /** 今の項目の揃え（{@link VerticalAlign}）・持ち上げ量と、行の上端・下端へ揃える項目の高さの最大。 */
    private byte itemAlign;
    private float itemRaise, tbMax;
    // 今の行の位置と、折り返しの判定に要る直前の内容
    private float x, asc, desc, pending, cursor, lineL, lineW;
    private boolean hasContent, pendingWraps, afterWrapSpace;
    private int lastChar = -1;
    /** 走査中の節の書式。 */
    private TextRun.Metrics run;

    /**
     * @param shrink 折り返しの判定に使う幅から引く量（{@code text-wrap: balance} が行を揃えるために狭める。行の幅と揃えは元のまま）
     */
    InlineLineBuilder(Style container, float availW, LayoutFragment prior, Floats.Space exc, float originY, float shrink, float pctH,
                      LayoutSession s) {
        this.shrink = shrink;
        this.pctH = pctH;
        this.exc = exc;
        this.originY = originY;
        this.availW = availW;
        this.s = s;
        this.prior = prior;
        this.container = container;
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

    /**
     * 空の行に幅 {@code need} の最初の中身が float の横に入らなければ、入るか float が無くなるまで次に float が終わる所へ行を下げる
     * （CSS 2.2 §9.5.1。旧 {@code LineBuilder.dropBelowFloats}）。
     */
    private void dropBelowFloats(float need) {
        for (int guard = 0; guard < 64 && need > lineW + EPS && lineW < availW - EPS; guard++) {
            float next = exc.nextBottom(originY + cursor);
            if (next == Float.POSITIVE_INFINITY) return;
            cursor = next - originY;
            startLine();
        }
    }

    void add(BoxNode n) {
        add(n, containerWs);
    }

    /** {@code ws} は {@code n} の親（インラインの持ち主）の {@code white-space}（原子の前後の機会を決める）。 */
    private void add(BoxNode n, WhiteSpace ws) {
        switch (n.kind) {
            case NONE -> { }
            case TEXT -> text(n);
            case CONTENTS -> { // 箱を作らない: 中身を親の続きとして並べる
                float outerTrail = trail;
                for (int i = 0, c = n.flow.size(); i < c; i++) {
                    trail = i == c - 1 ? outerTrail : 0;
                    add(n.flow.get(i), n.style.facts.whiteSpace);
                }
                trail = outerTrail;
            }
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
                Open ob = openBox(n);
                WhiteSpace inner = n.style.facts.whiteSpace;
                boolean va = !VerticalAlign.isBaseline(n.style);
                inlParent.add(currentParent());
                inl.add(n);
                if (va) vaDepth++;
                float outerTrail = trail;
                for (int i = 0, c = n.flow.size(); i < c; i++) {
                    trail = i == c - 1 ? outerTrail + ob.g.pR + ob.g.bR + ob.g.mR : 0;
                    add(n.flow.get(i), inner);
                }
                trail = outerTrail;
                inl.removeLast();
                inlParent.removeLast();
                if (va) vaDepth--;
                closeBox(ob);
            }
        }
    }

    /**
     * インライン要素の箱を開く（旧 {@code LineBuilder.openBox} の移植）。前に畳まれる空白が待っていれば箱の開始端より前へ出す
     * （空白は箱の外の文字に属する）。開始端（左の margin・border・padding）の分だけ進める。
     */
    private Open openBox(BoxNode n) {
        BoxGeometry g = new BoxGeometry(n.style, availW, s);
        FontFace face = s.face(n.style);
        FontStyle fs = n.style.facts.fontStyle;
        float a = face.ascent(fs), d = face.descent(fs);
        alignItem(n.style, currentParent(), a + d, a);
        Open ob = new Open(n, open.size(), g, itemAlign, itemRaise, a, d, InlineFlow.lineHeight(n.style, face));
        if (hasContent && pending > 0) {
            gapCarryAt = x;
            x += pending;
            trailingSpace = pending;
            afterWrapSpace = pendingWraps;
            gapCarry = true;
            clearPending();
        }
        ob.marginStart = x;
        x += g.mL;
        ob.start = x;
        x += g.bL + g.pL;
        open.add(ob);
        return ob;
    }

    /** 箱を閉じる: 終了端（右の padding・border）までをこの行の分として出し、右の margin だけ進める。 */
    private void closeBox(Open ob) {
        open.remove(open.size() - 1);
        x += ob.g.pR + ob.g.bR;
        spanAt.add(new SpanAt(ob, ob.start, x, ob.startEdge, true));
        x += ob.g.mR;
        trailingSpace = 0;
        markContent();
    }

    /** 開いている箱に中身が置かれた（折り返すとき、中身の無い箱は開始端ごと次の行へ移す）。 */
    private void markContent() {
        for (int i = 0, n = open.size(); i < n; i++) open.get(i).hasContent = true;
    }

    private void text(BoxNode box) {
        String t = box.text;
        if (t.isEmpty()) return;
        run = TextRun.Metrics.of(box, s);
        alignItem(null, null, run.ascent() + run.descent(), run.ascent());
        TextRun.scan(run, this);
        run = null;
    }

    private Style currentParent() {
        return inl.isEmpty() ? container : inl.get(inl.size() - 1).style;
    }

    /** 今の項目（{@code self} が原子なら自分も含む）の揃えと持ち上げ量を、内側から外側のインラインの祖先へ足し合わせて決める。 */
    private void alignItem(Style self, Style selfParent, float h, float above) {
        itemAlign = VerticalAlign.BASELINE;
        itemRaise = 0;
        if (self != null && !VerticalAlign.isBaseline(self)) applyAlign(self, selfParent, h, above);
        if (vaDepth == 0) return;
        for (int i = inl.size() - 1; i >= 0; i--) {
            Style el = inl.get(i).style;
            if (!VerticalAlign.isBaseline(el)) applyAlign(el, inlParent.get(i), h, above);
        }
    }

    private void applyAlign(Style el, Style parent, float h, float above) {
        byte k = VerticalAlign.kind(el);
        if (k != VerticalAlign.BASELINE) itemAlign = k;
        else itemRaise += VerticalAlign.raise(el, parent, h, above, s);
    }

    /** 今の項目（ベースラインより上 {@code a}・下 {@code d}）で行の高さを広げる（top / bottom の項目は高さだけ覚える）。 */
    private void growItem(float a, float d) {
        if (itemAlign == VerticalAlign.BASELINE) {
            grow(a + itemRaise, d - itemRaise);
        } else {
            tbMax = Math.max(tbMax, a + d);
            hasContent = true;
        }
    }

    // ---- TextRun.Sink ----

    @Override
    public boolean triesWhole() {
        return true;
    }

    @Override
    public boolean fits(float w) {
        String t = run.box().text;
        float closing = t.charAt(t.length() - 1) == ' ' ? 0 : trail;
        return x + (hasContent ? pending : 0) + w + closing <= lineW - shrink + EPS;
    }

    @Override
    public void space(float w, boolean wraps) {
        if (gapCarry) return; // 箱の開始端の前へ出した空白に続く畳まれる空白は、要素の境目をまたいで 1 つに畳まれる（CSS Text 3 §4.1.1）
        pending = Math.max(pending, w);
        pendingWraps |= wraps;
    }

    @Override
    public void word(int start, int end, float w, float hang, boolean first) {
        boolean breakable = first ? boundary(run.box().text.charAt(start)) : run.ws().wraps();
        float gap = hasContent ? pending : 0;
        float closing = end == run.box().text.length() ? trail : 0; // 直後に閉じる箱の終了端は最後の語と一緒に行に入る
        if (!hasContent) dropBelowFloats(x + w - hang + closing);
        if (breakable && hasContent && x + gap + w - hang + closing > lineW - shrink + EPS) {
            newLine(true);
            gap = 0;
        }
        float gapAt = gapCarry ? gapCarryAt : gap > 0 ? x : Float.NaN;
        x += gap;
        clearPending();
        addPiece(run.box(), start, end, w, gapAt);
        x += w;
        growItem(run.ascent(), run.descent());
        lastChar = run.box().text.charAt(end - 1);
        afterWrapSpace = run.ws().wraps() && lastChar == ' ';
    }

    @Override
    public void tab(int at, boolean first) {
        x += hasContent ? pending : 0;
        clearPending();
        float tabW = InlineFlow.tabWidth(run.style(), run.face(), run.fs(), s);
        x = InlineFlow.tabStop(x, tabW, run.spaceW());
        trailingSpace = 0;
        gapCarry = false;
        markContent();
        growItem(run.ascent(), run.descent());
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
    private void addPiece(BoxNode box, int start, int end, float w, float gapAt) {
        trailingSpace = 0;
        gapCarry = false;
        markContent();
        int k = pBox.size() - 1;
        if (k >= 0 && pBox.get(k) == box && pEnd[k] <= start && spacesOnly(box.text, pEnd[k], start) && run.wordSpacing() == 0) {
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
            pGapAt = Arrays.copyOf(pGapAt, c);
            pRaise = Arrays.copyOf(pRaise, c);
            pAbove = Arrays.copyOf(pAbove, c);
            pHt = Arrays.copyOf(pHt, c);
            pVa = Arrays.copyOf(pVa, c);
        }
        pBox.add(box);
        pStart[k + 1] = start;
        pEnd[k + 1] = end;
        pX[k + 1] = x;
        pW[k + 1] = w;
        pGapAt[k + 1] = gapAt;
        pRaise[k + 1] = itemRaise;
        pVa[k + 1] = itemAlign;
        pAbove[k + 1] = run.ascent();
        pHt[k + 1] = run.ascent() + run.descent();
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
        ConstraintSpace space = s.space(availW, w, Float.NaN, pctH, ConstraintSpace.FORMATTING_ROOT);
        LayoutFragment f = s.cache().layout(n, space, priorAtom(n), s);
        float outerW = g.mL + f.width + g.mR;
        float gap = hasContent ? pending : 0;
        if (!hasContent) dropBelowFloats(x + outerW);
        boolean breakable = hasContent && (pending > 0 ? pendingWraps : afterWrapSpace || ws.wraps());
        if (breakable && x + gap + outerW > lineW - shrink + EPS) {
            newLine(true);
            gap = 0;
        }
        x += gap;
        clearPending();
        trailingSpace = 0;
        gapCarry = false;
        markContent();
        boolean overflowVisible = (cs.box.overflowX == null || cs.box.overflowX == Overflow.VISIBLE)
                && (cs.box.overflowY == null || cs.box.overflowY == Overflow.VISIBLE);
        // inline-block は最後の行・inline-flex は最初の行のベースライン。overflow が visible でない inline-block は下の余白の端
        float b = n.kind == BoxKind.INLINE_BLOCK && !overflowVisible ? Float.NaN : InlineFlow.baselineOf(f, n.kind == BoxKind.INLINE_FLEX || n.kind == BoxKind.INLINE_GRID);
        float outerH = g.mT + f.height + g.mB;
        float a = Float.isNaN(b) ? outerH : g.mT + b;
        alignItem(cs, currentParent(), outerH, a);
        if (itemAlign == VerticalAlign.BASELINE) a += itemRaise;
        aFrag.add(f);
        aPos.add(new float[]{x + g.mL, a, g.mT, itemAlign, outerH});
        if (itemAlign == VerticalAlign.BASELINE) grow(a, outerH - a);
        else growItem(a, outerH - a);
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
        // 開いている箱はこの行の分を閉じて次の行へ続ける。折り返しなら、まだ中身を置いていない箱は開始端ごと次の行へ移す
        int openCount = open.size(), carried = openCount;
        if (soft) {
            while (carried > 0 && open.get(carried - 1).startEdge && !open.get(carried - 1).hasContent) carried--;
        }
        float end = (carried < openCount ? open.get(carried).marginStart : x) - trailingSpace;
        for (int i = 0; i < openCount; i++) {
            Open ob = open.get(i);
            if (i < carried) {
                spanAt.add(new SpanAt(ob, ob.start, Math.max(ob.start, end), ob.startEdge, false));
                ob.startEdge = false;
            } else {
                ob.reopen = true;
            }
            ob.hasContent = false;
        }
        int k = pBox.size();
        float lineH = LayoutUnit.snap(Math.max(asc + desc, tbMax));
        LineFragment.Piece[] pieces = new LineFragment.Piece[k];
        for (int i = 0; i < k; i++) {
            // 文字の並びのベースラインの、行のベースラインからのずれ（top / bottom は行の上端・下端へ）
            float dy = pVa[i] == VerticalAlign.TOP ? pAbove[i] - asc
                    : pVa[i] == VerticalAlign.BOTTOM ? lineH - (pHt[i] - pAbove[i]) - asc : -pRaise[i];
            pieces[i] = new LineFragment.Piece(pBox.get(i), pStart[i], pEnd[i], pX[i], pW[i], LayoutUnit.snap(dy));
        }
        float width = end, off = lineL;
        // 両端揃えでずらす x（原子の左端・箱の左右の端。右端は、その位置より前で始まる機会の分だけずれる）
        int na = aFrag.size(), ns = spanAt.size();
        float[] atomX = new float[na + 2 * ns];
        boolean[] rightEdge = new boolean[atomX.length];
        for (int i = 0; i < na; i++) atomX[i] = aPos.get(i)[0];
        for (int i = 0; i < ns; i++) {
            atomX[na + 2 * i] = spanAt.get(i).left();
            atomX[na + 2 * i + 1] = spanAt.get(i).right();
            rightEdge[na + 2 * i + 1] = true;
        }
        LineFragment.Piece[] justified = align.justifies(soft) && k > 0
                ? align.justify(pieces, Arrays.copyOf(pGapAt, k), lineW - width, atomX, rightEdge, s) : null;
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
                float ay = p[3] == VerticalAlign.TOP ? p[2] : p[3] == VerticalAlign.BOTTOM ? lineH - p[4] + p[2] : asc - p[1] + p[2];
                atoms[i] = new PlacedFragment(aFrag.get(i), LayoutUnit.snap(atomX[i]), LayoutUnit.snap(ay)); // 箱の位置は LayoutUnit の格子
            }
            aFrag.clear();
            aPos.clear();
        }
        LineFragment.Span[] spans = LineFragment.NO_SPANS;
        if (ns > 0) {
            spans = new LineFragment.Span[ns];
            int[] depth = new int[ns];
            for (int i = 0; i < ns; i++) {
                spans[i] = spanAt.get(i).span(atomX[na + 2 * i], atomX[na + 2 * i + 1], lineH, asc);
                depth[i] = spanAt.get(i).ob().depth;
            }
            sortByDepth(spans, depth); // 外側の箱が先（描く順）
            spanAt.clear();
        }
        lines.add(new LineFragment(LayoutUnit.snap(off), cursor, width, lineH, LayoutUnit.snap(asc), pieces, atoms, spans));
        cursor += lineH;
        pBox.clear();
        x = 0;
        asc = strutAsc;
        desc = strutDesc;
        tbMax = 0;
        startLine();
        clearPending();
        hasContent = false;
        afterWrapSpace = false;
        lastChar = -1;
        trailingSpace = 0;
        gapCarry = false;
        for (int i = 0, n = open.size(); i < n; i++) { // 続く箱は行の頭から。次の行へ移した箱は開始端を付け直す
            Open ob = open.get(i);
            ob.marginStart = x;
            if (ob.reopen) {
                x += ob.g.mL;
                ob.start = x;
                x += ob.g.bL + ob.g.pL;
                ob.reopen = false;
            } else {
                ob.start = x;
            }
        }
    }

    /** 入れ子の浅い順に並べる（同じ深さは出した順のまま。挿入整列: 1 行の箱は数個）。 */
    private static void sortByDepth(LineFragment.Span[] spans, int[] depth) {
        for (int i = 1; i < spans.length; i++) {
            LineFragment.Span v = spans[i];
            int d = depth[i], j = i - 1;
            while (j >= 0 && depth[j] > d) {
                spans[j + 1] = spans[j];
                depth[j + 1] = depth[j];
                j--;
            }
            spans[j + 1] = v;
            depth[j + 1] = d;
        }
    }

    InlineFlow.Result finish() {
        if (hasContent || !pBox.isEmpty() || !aFrag.isEmpty() || anyDecorated()) newLine(false);
        return new InlineFlow.Result(lines.toArray(new LineFragment[0]), cursor, oofPending);
    }

    /** 今の行の箱に、余白・枠線・padding を持つものがあるか（中身の無い行でも、そういう箱があれば行を作る。CSS 2.2 §9.4.2）。 */
    private boolean anyDecorated() {
        for (SpanAt a : spanAt) {
            BoxGeometry g = a.ob().g;
            if (g.mL != 0 || g.mR != 0 || g.bL + g.pL + g.pR + g.bR != 0 || g.bT + g.pT + g.pB + g.bB != 0) return true;
        }
        return false;
    }

    /** 開いているインライン要素の箱（1 回の整形の間だけの作業域。旧 {@code BlockLayoutEngine.OpenBox}）。 */
    private static final class Open {
        final BoxNode box;
        final int depth;
        final BoxGeometry g;
        final byte align;
        final float raise, ascent, descent, lineHeight;
        float start, marginStart;
        boolean startEdge = true, hasContent, reopen;

        Open(BoxNode box, int depth, BoxGeometry g, byte align, float raise, float ascent, float descent, float lineHeight) {
            this.box = box;
            this.depth = depth;
            this.g = g;
            this.align = align;
            this.raise = raise;
            this.ascent = ascent;
            this.descent = descent;
            this.lineHeight = lineHeight;
        }
    }

    /** 今の行に出した箱の分（左右の端は行の左端基準）。 */
    private record SpanAt(Open ob, float left, float right, boolean startEdge, boolean endEdge) {
        /** 縦の位置を決めた {@link LineFragment.Span}（行の高さ {@code lineH}・行のベースライン {@code lineBase}）。 */
        LineFragment.Span span(float l, float r, float lineH, float lineBase) {
            float above = TextShaper.leadingAbove(ob.lineHeight, ob.ascent, ob.descent) + ob.ascent; // 自分の行の高さの上端からベースライン
            float baseY = ob.align == VerticalAlign.TOP ? above
                    : ob.align == VerticalAlign.BOTTOM ? lineH - ob.lineHeight + above : lineBase - ob.raise;
            BoxGeometry g = ob.g;
            float top = LayoutUnit.snap(baseY - ob.ascent - g.pT - g.bT);
            float h = LayoutUnit.snap(g.bT + g.pT + ob.ascent + ob.descent + g.pB + g.bB);
            return new LineFragment.Span(ob.box, l, top, Math.max(0, r - l), h, // 横は文字片と同じく丸めない（行の左端と二重に丸めない）
                    LayoutUnit.snap(baseY) - top, startEdge, endEdge);
        }
    }
}
