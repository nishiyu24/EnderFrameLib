package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.CssLength;
import com.nishiyu.enderframelib.core.kernel.CompactList;
import com.nishiyu.enderframelib.core.kernel.layout.FenwickVirtualList;
import com.nishiyu.enderframelib.core.text.ParagraphSegments;
import com.nishiyu.enderframelib.core.layout.model.*;

import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.layout.LayoutContext;
import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.model.CssKeywords;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.css.StyleResolver;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.layout.LayoutBox.Display.Inner;
import com.nishiyu.enderframelib.core.layout.LayoutBox.InlineFragment;
import com.nishiyu.enderframelib.core.layout.LayoutBox.SizingMode;
import com.nishiyu.enderframelib.core.layout.model.ConstraintSpace;
import com.nishiyu.enderframelib.core.layout.model.FormattingContext;
import com.nishiyu.enderframelib.core.text.model.FontStyle;

import java.util.ArrayList;
import java.util.List;

import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;
import com.nishiyu.enderframelib.core.css.model.Overflow;
import com.nishiyu.enderframelib.core.css.model.TextAlign;
import com.nishiyu.enderframelib.core.css.model.BorderStyle;

/**
 * インライン内容を行に分割する。float を避けて各行の左端と幅を決め、行の中の float を置く。
 *
 * インラインボックス（{@code <span>} など）は開始タグと終了タグで 1 回ずつ装飾を足す（CSS Inline 3 /
 * {@code box-decoration-break: slice}）。開始端の margin + border + padding は最初の断片、終了端は最後の断片だけが
 * 持ち、同じ行の中身は 1 つの断片にまとめる。
 *
 * 何が壊れていたか: 以前は祖先の装飾を断片（語）ごとに足していた。{@code <span style="padding: 4px">Hello world</span>}
 * は語ごとに左右の padding が付いて背景が重なり、行を折り返すたびに開始端の装飾も足されていた。
 *
 * <p>A8 で {@link BlockLayoutEngine} の入れ子のクラスから最上位のクラスへ移した。</p>
 */
final class LineBuilder {
    final BlockLayoutEngine.BlockScratch scratch;
    FlowBuilder flow;
    LayoutContext ctx;
    LayoutBox flOwner;
    ComputedStyle flStyle;
    InlineLine firstLine;
    List<InlineLine> lines;
    float strutHeight, strutBaseline;
    List<BlockLayoutEngine.LineItem> current = null;
    List<BlockLayoutEngine.BoxSpan> spans = null;
    List<BlockLayoutEngine.OpenBox> open = null;
    float lineTop;
    float lineLeft, lineWidth;
    /** 行の左端からの進み位置。float のまま持つ（断片ごとの切り上げで誤差が溜まっていた）。 */
    float lineX;
    /** text-wrap: balance で、折り返しの判定に使う幅から引く量（行の幅・揃えはそのまま）。 */
    float balanceShrink;
    /** 直前の run の頭で畳んだ空白が、折り返しを許す white-space だった（次の run の頭で折り返せる）。 */
    boolean wrapSpaceCollapsed;
    /** 最後に置いた語の行末の空白の幅。行を閉じるとき内容の幅から除く。 */
    float trailingSpace;
    float indent;
    boolean lineStarted = false;
    List<LayoutBox> deferredFloats = null;

    LineBuilder(BlockLayoutEngine.BlockScratch scratch) {
        this.scratch = scratch;
    }

    LineBuilder init(FlowBuilder flow, float startY, float indent, boolean allowFirstLine) {
        this.flow = flow;
        this.ctx = flow.ctx;
        this.flOwner = allowFirstLine ? BlockLayoutEngine.firstLineOwner(flow.box) : null;
        this.flStyle = this.flOwner != null ? this.flOwner.firstLineStyle : null;
        this.lineTop = startY;
        this.indent = indent;
        this.firstLine = null;
        this.lines = null;
        if (this.current != null) this.current.clear();
        this.spans = null;
        if (this.open != null) this.open.clear();
        if (this.deferredFloats != null) this.deferredFloats.clear();
        this.lineLeft = 0;
        this.lineWidth = 0;
        this.lineX = 0;
        this.trailingSpace = 0;
        this.lineStarted = false;
        this.wrapSpaceCollapsed = false;
        // 行ボックスの strut: 内容の無い行でも、ブロックの書体と line-height の高さを持つ（CSS 2.2 §10.8.1）
        ComputedStyle strutCss = this.flStyle != null ? this.flStyle : flow.box.style;
        TextStyle strut = ctx.textStyleFor(strutCss);
        this.strutHeight = strut.lineHeightPx();
        this.strutBaseline = BlockLayoutEngine.baselineWithin(strut, ctx.faceFor(strutCss));
        return this;
    }

    void clearRefs() {
        this.flow = null;
        this.ctx = null;
        this.flOwner = null;
        this.flStyle = null;
        this.firstLine = null;
        this.lines = null;
        this.spans = null;
        if (this.open != null) this.open.clear();
        if (this.deferredFloats != null) this.deferredFloats.clear();
        if (this.current != null) {
            if (this.current.size() > 64) this.current = null;
            else this.current.clear();
        }
    }

    boolean onFirstLine() {
        return flStyle != null && firstLine == null && lines == null;
    }

    ComputedStyle effectiveFirstLine(LayoutBox runBox) {
        return onFirstLine() ? runBox.style.derivedFirstLineChild(flOwner.style, flStyle) : null;
    }

    void appendLine(InlineLine line) {
        if (lines != null) {
            lines.add(line);
        } else if (firstLine == null) {
            firstLine = line;
            if (flStyle != null) {
                TextStyle normStrut = ctx.textStyleFor(flow.box.style);
                this.strutHeight = normStrut.lineHeightPx();
                this.strutBaseline = BlockLayoutEngine.baselineWithin(normStrut, ctx.faceFor(flow.box.style));
            }
        } else {
            lines = new ArrayList<>(4);
            lines.add(firstLine);
            lines.add(line);
            firstLine = null;
        }
    }

    List<BlockLayoutEngine.LineItem> ensureCurrent() {
        if (current == null) current = new ArrayList<>();
        return current;
    }

    List<BlockLayoutEngine.LineItem> ensureCurrent(int minCapacity) {
        if (current == null) current = new ArrayList<>(minCapacity);
        else if (current instanceof ArrayList<BlockLayoutEngine.LineItem> al) al.ensureCapacity(minCapacity);
        return current;
    }

    boolean currentEmpty() {
        return current == null || current.isEmpty();
    }

    List<InlineLine> build(List<LayoutBox> inlines) {
        if (inlines.size() == 1) {
            LayoutBox only = inlines.get(0);
            if (!only.display.isNone() && !only.isAbsolute && !BlockLayoutEngine.isFloating(only)
                    && only.node.nodeType == NodeType.TEXT) {
                if (only.displayText != null && !only.displayText.isEmpty()) {
                    addText(only, only.displayText, List.of(), 0f, true);
                }
                if (!currentEmpty() || anyDecorated(spans)) newLine(0, 0, false);
                flushDeferredFloats();
                if (lines != null) return lines;
                return firstLine != null ? List.of(firstLine) : List.of();
            }
        }
        List<BlockLayoutEngine.FlatRun> runs = scratch.runs;
        List<LayoutBox> parents = scratch.parents;
        List<LayoutBox>[] snapshot = scratch.snapshot;
        runs.clear();
        parents.clear();
        snapshot[0] = null;
        try {
            BlockLayoutEngine.flattenInlines(inlines, runs, parents, snapshot);
            for (int i = 0, runCount = runs.size(); i < runCount; i++) {
                BlockLayoutEngine.FlatRun run = runs.get(i);
                switch (run.kind()) {
                    case TEXT -> addText(run.box(), run.text(), run.inlineParents(), closingEdges(runs, i), i == runCount - 1);
                    case BREAK -> addBreak(run);
                    case ATOMIC -> addAtomic(run, closingEdges(runs, i));
                    case FLOAT -> addFloat(run.box());
                    case ABSOLUTE -> {
                        ensureLine();
                        flow.state.items.add(new BlockLayoutEngine.StaticPosition(run.box(), lineLeft + lineX, lineTop));
                    }
                    case OPEN -> openBox(run);
                    case CLOSE -> closeBox();
                }
            }
        } finally {
            runs.clear();
            parents.clear();
            snapshot[0] = null;
        }
        if (!currentEmpty() || anyDecorated(spans)) newLine(0, 0, false);
        flushDeferredFloats();
        if (lines != null) return lines;
        return firstLine != null ? List.of(firstLine) : List.of();
    }

    void flushDeferredFloats() {
        if (deferredFloats == null || deferredFloats.isEmpty()) return;
        for (int i = 0, n = deferredFloats.size(); i < n; i++) {
            flow.placeFloat(deferredFloats.get(i), lineTop);
        }
        deferredFloats.clear();
    }

    boolean anyDecorated(List<BlockLayoutEngine.BoxSpan> list) {
        if (list == null || list.isEmpty()) return false;
        for (int i = 0, n = list.size(); i < n; i++) {
            if (BlockLayoutEngine.isDecorated(list.get(i).open().box)) return true;
        }
        return false;
    }

    void addSpan(BlockLayoutEngine.BoxSpan span) {
        if (spans == null) spans = new ArrayList<>(4);
        spans.add(span);
    }

    /** {@code runs[i]} の直後に閉じるインラインボックスの終了端の装飾。最後の語と一緒に行に入らないといけない。 */
    float closingEdges(List<BlockLayoutEngine.FlatRun> runs, int i) {
        float w = 0;
        for (int j = i + 1; j < runs.size() && runs.get(j).kind() == BlockLayoutEngine.FlatRun.Kind.CLOSE; j++) {
            LayoutBox b = runs.get(j).box();
            w += b.pR + b.bR + b.mR;
        }
        return w;
    }

    boolean constrained() {
        if (flow.innerW > 0) return true;
        // width: 0 を明示した箱は幅 0 で折り返す（全ての折り返し位置で切れる）。測定の途中の仮の幅 0 は制約にしない
        if (flow.innerW == 0 && flow.box.sizingMode == SizingMode.DEFINITE) {
            long w = flow.box.axisStyle().typed().box.width;
            return com.nishiyu.enderframelib.core.css.CssLength.isDeclared(w) && !com.nishiyu.enderframelib.core.css.CssLength.isIntrinsicSize(w, flow.box.axisStyle().typed().box.exprs);
        }
        return false;
    }

    void ensureLine() {
        if (lineStarted) return;
        flushDeferredFloats();
        computeBand();
        lineX = indent;
        indent = 0;
        trailingSpace = 0;
        lineStarted = true;
        // 前の行から続く箱は行の頭から断片を始める。中身を置く前に折り返した箱は開始端を付け直す
        if (open != null) {
            for (int i = 0, n = open.size(); i < n; i++) {
                BlockLayoutEngine.OpenBox ob = open.get(i);
                ob.marginStart = lineX;
                if (ob.reopen) {
                    lineX += ob.box.mL;
                    ob.start = lineX;
                    lineX += ob.box.bL + ob.box.pL;
                    ob.reopen = false;
                } else {
                    ob.start = lineX;
                }
            }
        }
    }

    /** 行の上端で float を避けた左端と幅。 */
    void computeBand() {
        FloatSpace fs = flow.floats;
        if (fs.isEmpty()) {
            lineLeft = 0;
            lineWidth = flow.innerW;
            return;
        }
        float y = flow.oy + lineTop;
        float l = fs.leftEdge(y, strutHeight, flow.ox) - flow.ox;
        float r = fs.rightEdge(y, strutHeight, flow.ox + flow.innerW) - flow.ox;
        lineLeft = l;
        lineWidth = Math.max(0, r - l);
    }

    /** 空の行に幅 {@code w} の内容が入らず、float が幅を狭めているなら、float の下まで行を下げる。 */
    void dropBelowFloats(float w) {
        FloatSpace fs = flow.floats;
        if (!constrained() || fs.isEmpty() || !currentEmpty()) return;
        for (int guard = 0; guard < 256 && w > lineWidth + 0.01f && lineWidth < flow.innerW; guard++) {
            float next = fs.nextBottom(flow.oy + lineTop);
            if (Float.isInfinite(next)) return;
            flow.state.wrapped = true;
            lineTop = next - flow.oy;
            computeBand();
        }
    }

    /**
     * 行を閉じる。開いている箱はこの行の断片を閉じて次の行へ続ける。
     * {@code wrapped}（自動の折り返し）なら、まだ中身を置いていない箱は開始端ごと次の行へ移す。
     */
    void newLine(float fallbackHeight, float fallbackBaseline, boolean wrapped) {
        int openCount = open != null ? open.size() : 0;
        int carried = openCount;
        if (wrapped) {
            flow.state.wrapped = true;
            // 中身の無い箱は開いている箱の末尾に並ぶ（中の箱に中身があれば外の箱にもある）
            while (carried > 0 && open.get(carried - 1).startEdge && !open.get(carried - 1).hasContent) carried--;
        }
        float end = (carried < openCount ? open.get(carried).marginStart : lineX) - trailingSpace;
        for (int i = 0; i < openCount; i++) {
            BlockLayoutEngine.OpenBox ob = open.get(i);
            if (i < carried) {
                addSpan(new BlockLayoutEngine.BoxSpan(ob, ob.start, Math.max(ob.start, end), ob.startEdge, false));
                ob.startEdge = false;
            } else {
                ob.reopen = true;
            }
            ob.hasContent = false;
        }
        InlineLine line = finishLine(fallbackHeight, fallbackBaseline, end);
        line.softWrapped = wrapped;
        appendLine(line);
        lineTop += line.height();
        current = null;
        spans = null;
        lineStarted = false;
    }

    /** 開いている箱に中身が置かれた。 */
    void markContent() {
        if (open == null) return;
        for (int i = 0, n = open.size(); i < n; i++) open.get(i).hasContent = true;
    }

    void openBox(BlockLayoutEngine.FlatRun run) {
        ensureLine();
        LayoutBox b = run.box();
        FontFace face = ctx.faceFor(b.style);
        FontStyle font = ctx.fontStyleFor(b.style);
        float ascent = face.ascent(font);
        BlockLayoutEngine.OpenBox ob = new BlockLayoutEngine.OpenBox(b, alignmentOf(b, run.inlineParents(), ascent + face.descent(font), ascent));
        ob.marginStart = lineX;
        lineX += b.mL;
        ob.start = lineX;
        lineX += b.bL + b.pL;
        if (open == null) open = new ArrayList<>(2);
        open.add(ob);
    }

    void closeBox() {
        ensureLine();
        BlockLayoutEngine.OpenBox ob = open.removeLast();
        lineX += ob.box.pR + ob.box.bR;
        addSpan(new BlockLayoutEngine.BoxSpan(ob, ob.start, lineX, ob.startEdge, true));
        lineX += ob.box.mR;
        trailingSpace = 0;
        markContent();
    }

    /** 行頭か、行の直前の中身が空白で終わっている（次の run の先頭の畳まれる空白は消える。CSS Text §4.1.1–4.1.2）。 */
    boolean precededByCollapsibleSpace() {
        if (currentEmpty()) return true;
        String t = current.getLast().text();
        return t != null && !t.isEmpty() && t.charAt(t.length() - 1) == ' ';
    }

    void addText(LayoutBox runBox, String runText, List<LayoutBox> inlineParents, float closing, boolean isLastRun) {
        TextStyle textStyle = ctx.textStyleFor(runBox.style);
        if (!runText.isEmpty() && runText.charAt(0) == ' ' && textStyle.whiteSpace().collapses() && precededByCollapsibleSpace()) {
            runText = runText.substring(1);
            // 畳んだ空白が折り返しを許す white-space なら、その位置は折り返しの機会として残る
            if (textStyle.whiteSpace().wraps() && !currentEmpty()) wrapSpaceCollapsed = true;
            if (runText.isEmpty()) {
                ensureLine(); // 保留中の float はここで置く（空白の run でも従来どおり行を始める）
                return;
            }
        }
        FontFace face = ctx.faceFor(runBox.style);
        FontStyle font = textStyle.font();
        float runH = textStyle.lineHeightPx();
        float baseline = BlockLayoutEngine.baselineWithin(textStyle, face);
        float descent = face.descent(font);
        BlockLayoutEngine.Alignment alignment = inlineParents.isEmpty()
                ? BlockLayoutEngine.ALIGNMENT_BASELINE_ZERO
                : alignmentOf(null, inlineParents, runH, baseline);
        ComputedStyle effFl = effectiveFirstLine(runBox);

        boolean preserveNewlines = textStyle.whiteSpace().preservesNewlines();
        if (effFl == null && flow.floats.isEmpty() && (deferredFloats == null || deferredFloats.isEmpty())
                && (open == null || open.isEmpty()) && currentEmpty() && !lineStarted && lineX == 0 && indent == 0
                && closing == 0 && textStyle.whiteSpace().wraps() && constrained() && runText.indexOf('\t') < 0
                && !textStyle.whiteSpace().breaksSpaces() && font.letterSpacingPx() == 0 && balanceShrink == 0
                && (!preserveNewlines || runText.indexOf('\n') < 0)) {
            TextShaper.WrappedParagraph wp = runBox.cachedWrappedParagraph(
                    runText, face, font, textStyle.wordBreak(), flow.innerW);
            int lineCount = wp.lineCount();
            if (lineCount > 1) flow.state.wrapped = true;
            float lineBaseline;
            float lineHeight;
            if (alignment.align() == BlockLayoutEngine.VAlign.BASELINE) {
                lineBaseline = Math.max(strutBaseline, baseline + alignment.raise());
                float belowBaseline = Math.max(strutHeight - strutBaseline, runH - baseline - alignment.raise());
                lineHeight = lineBaseline + belowBaseline;
            } else {
                lineBaseline = strutBaseline;
                lineHeight = Math.max(strutHeight, runH);
            }
            BlockLayoutEngine.VAlign align = alignment.align();
            float raise = alignment.raise();

            int directLines = isLastRun ? lineCount : (lineCount - 1);
            BlockLayoutEngine.SliceStyle sliceStyle = directLines > 0
                    ? new BlockLayoutEngine.SliceStyle(runBox, runH, baseline, descent, inlineParents, align, raise) : null;
            for (int l = 0; l < directLines; l++) {
                InlineLine slice = InlineLine.ofWrappedSlice(wp, l, sliceStyle, lineTop, 0f, flow.innerW, lineHeight, lineBaseline);
                slice.softWrapped = l < lineCount - 1;
                appendLine(slice);
                lineTop += lineHeight;
            }
            if (isLastRun) {
                return;
            }

            String[] segments = wp.prepared().segs();
            int start = wp.lineStart(lineCount - 1);
            int end = wp.lineStart(lineCount);
            lineLeft = 0;
            lineWidth = flow.innerW;
            lineStarted = true;
            List<BlockLayoutEngine.LineItem> cur = ensureCurrent(Math.max(end - start, 1));
            for (int k = start; k < end; k++) {
                cur.add(new BlockLayoutEngine.LineItem(runBox, wp.itemX(k), wp.advance(k), runH,
                        segments[wp.segIndex(k)], baseline, descent, inlineParents, align, raise, null));
            }
            lineX = wp.finalLineX();
            trailingSpace = wp.finalTrailingSpace();
            markContent();
            return;
        }

        ensureLine();
        // white-space が改行を残す（pre / pre-wrap / pre-line）なら原文の改行で行を割る。
        // 以前は改行が区切りとして扱われず、<pre> の中身が 1 行に潰れていた。
        if (!preserveNewlines || runText.indexOf('\n') < 0) {
            addParagraph(runBox, inlineParents, face, font, textStyle, alignment, runH, baseline, descent, runText, closing, true, effFl);
            return;
        }
        String[] paragraphs = runText.split("\n", -1);
        for (int p = 0; p < paragraphs.length; p++) {
            if (p > 0) {
                markContent();
                newLine(runH, baseline, false);
                ensureLine();
                effFl = null;
            }
            addParagraph(runBox, inlineParents, face, font, textStyle, alignment, runH, baseline, descent, paragraphs[p],
                    p == paragraphs.length - 1 ? closing : 0, false, effFl);
        }
    }

    void addParagraph(LayoutBox runBox, List<LayoutBox> inlineParents, FontFace face, FontStyle font, TextStyle textStyle,
                              BlockLayoutEngine.Alignment alignment, float runH, float baseline, float descent,
                              String paragraph, float closing, boolean cacheOnBox, ComputedStyle effFl) {
        if (effFl != null && onFirstLine()) {
            addFirstLineParagraph(runBox, inlineParents, face, font, textStyle, alignment, runH, baseline, descent,
                    paragraph, closing, effFl);
            return;
        }
        addRemainingParagraph(runBox, inlineParents, face, font, textStyle, alignment, runH, baseline, descent,
                paragraph, closing, cacheOnBox, previousTextSameFont(face, font), 0, null);
    }

    /**
     * 行の直前の断片が同じ書体・同じ字の設定の文字なら、その文字列（要素の境目をまたいでも字の詰めを入れる。ブラウザは
     * 段落の文字をまとめて整形する）。違えば null。
     */
    String previousTextSameFont(FontFace face, FontStyle font) {
        if (currentEmpty()) return null;
        BlockLayoutEngine.LineItem last = current.getLast();
        String t = last.text();
        if (t == null || t.isEmpty() || Character.isWhitespace(t.charAt(t.length() - 1)) || last.box() == null) return null;
        if (ctx.faceFor(last.box().style) != face || !font.equals(ctx.fontStyleFor(last.box().style))) return null;
        return t;
    }

    void addFirstLineParagraph(LayoutBox runBox, List<LayoutBox> inlineParents, FontFace normFace, FontStyle normFont,
                                       TextStyle normTextStyle, BlockLayoutEngine.Alignment normAlign, float normRunH, float normBaseline, float normDescent,
                                       String paragraph, float closing, ComputedStyle effFl) {
        TextStyle flTextStyle = ctx.textStyleFor(effFl);
        FontFace flFace = ctx.faceFor(effFl);
        FontStyle flFont = flTextStyle.font();
        float flRunH = flTextStyle.lineHeightPx();
        float flBaseline = BlockLayoutEngine.baselineWithin(flTextStyle, flFace);
        float flDescent = flFace.descent(flFont);
        BlockLayoutEngine.Alignment flAlign = inlineParents.isEmpty() ? BlockLayoutEngine.ALIGNMENT_BASELINE_ZERO : alignmentOf(null, inlineParents, flRunH, flBaseline);
        String flParagraph = LayoutMetrics.applyTextTransform(paragraph, effFl);
        ParagraphSegments flPrepared = TextShaper.segmented(flParagraph, flFace, flFont, flTextStyle.wordBreak());
        String[] segments = flPrepared.segs();
        int lastSegment = segments.length - 1;
        while (lastSegment >= 0 && segments[lastSegment].isEmpty()) lastSegment--;

        String previousOnLine = null;
        float kerned = 0;
        int consumedChars = 0;
        for (int s = 0; s < segments.length; s++) {
            String segment = segments[s];
            if (segment.isEmpty()) continue;
            float advance = flPrepared.fullW(s);
            float trimmedW = flPrepared.strippedTrailingW(s);
            float edges = s == lastSegment ? closing : 0;
            float join = previousOnLine == null ? 0
                    : s > 0 && previousOnLine == segments[s - 1] ? flPrepared.joinW(s)
                    : TextShaper.kerningBetween(flFace, flFont, previousOnLine, segment);
            if (flTextStyle.whiteSpace().wraps() && !segment.isBlank()) {
                if (needsWrap(lineX + kerned + join + trimmedW + edges)) {
                    newLine(flRunH, flBaseline, true);
                    ensureLine();
                    String rest = consumedChars < paragraph.length()
                            ? paragraph.substring(consumedChars) : "";
                    if (!rest.isEmpty()) {
                        addRemainingParagraph(runBox, inlineParents, normFace, normFont, normTextStyle, normAlign,
                                normRunH, normBaseline, normDescent, rest, closing, false, null, 0, null);
                    }
                    return;
                }
                dropBelowFloats(lineX + kerned + join + trimmedW + edges);
            }
            kerned += join;
            previousOnLine = segment;
            ensureCurrent().add(new BlockLayoutEngine.LineItem(runBox, lineX, advance, flRunH, segment, flBaseline, flDescent,
                    inlineParents, flAlign.align(), flAlign.raise(), effFl));
            lineX += advance;
            trailingSpace = advance - trimmedW;
            consumedChars += segment.length();
            markContent();
        }
    }

    void addRemainingParagraph(LayoutBox runBox, List<LayoutBox> inlineParents, FontFace face, FontStyle font, TextStyle textStyle,
                                       BlockLayoutEngine.Alignment alignment, float runH, float baseline, float descent,
                                       String paragraph, float closing, boolean cacheOnBox,
                                       String previousOnLine, float kerned, ComputedStyle itemFlStyle) {
        // word-break・CJK の折り返しは従来の経路が扱う（こちらは空白でだけ切る）
        if (textStyle.whiteSpace().breaksSpaces() && paragraph.indexOf('\t') < 0
                && textStyle.wordBreak() == com.nishiyu.enderframelib.core.text.model.WordBreak.NORMAL && !hasWideChars(paragraph)) {
            addBreakSpacesParagraph(runBox, inlineParents, face, font, alignment, runH, baseline, descent, paragraph, closing, itemFlStyle);
            return;
        }
        // 断片・幅・境目の詰めは文字列・書体・折り返しの規則だけで決まるので、同じ段落なら測り直さない（幅にも依らない）
        ParagraphSegments prepared = cacheOnBox
                ? runBox.cachedSegments(paragraph, face, font, textStyle.wordBreak())
                : TextShaper.segmented(paragraph, face, font, textStyle.wordBreak());
        String[] segments = prepared.segs();
        // 入るかどうかは語の幅の和に境目の詰め（TextShaper.kerningBetween）を足した幅、つまり続けて測った幅で
        // 見る（TextShaper と同じ。max-content もこの測り方）。kerned はこの run が今の行で詰めた量の合計。
        int lastSegment = segments.length - 1;
        while (lastSegment >= 0 && segments[lastSegment].isEmpty()) lastSegment--;
        for (int s = 0; s < segments.length; s++) {
            String segment = segments[s];
            if (segment.isEmpty()) continue;
            float advance = prepared.fullW(s);
            // 行末の空白は幅に数えない（CSS と同じ）
            float trimmedW = prepared.strippedTrailingW(s);
            // 保持された tab は次の tab 位置まで進む（CSS Text 3 §4.2）。位置は行の始点から測る
            float[] tabPieces = segment.indexOf('\t') >= 0 ? tabLayout(segment, lineX, runBox, face, font) : null;
            if (tabPieces != null) {
                advance = tabPieces[tabPieces.length - 1];
                trimmedW = textStyle.whiteSpace().wraps() ? advance - trailingTabWidth(segment, tabPieces) : advance;
            }
            // 最後の語は、直後に閉じる箱の終了端の装飾と一緒に行に入らないといけない
            float edges = s == lastSegment ? closing : 0;
            // letter-spacing は同じ要素の文字の間に入る。run の最後の文字の後（要素の境目）には足さない（CSS Text 3 §8.2）
            if (s == lastSegment && font.letterSpacingPx() != 0 && tabPieces == null) {
                advance -= font.letterSpacingPx();
                trimmedW -= font.letterSpacingPx();
            }

            // 直前の断片との境目の詰め（直前が断片の並びの隣なら下ごしらえの値、空の断片を挟んだときだけ測る）
            float join = previousOnLine == null ? 0
                    : s > 0 && previousOnLine == segments[s - 1] ? prepared.joinW(s)
                    : TextShaper.kerningBetween(face, font, previousOnLine, segment);
            // 折り返しの機会は空白の側の white-space が決める: 前の run の折り返し可の空白の後なら、この run が nowrap でも折り返せる
            if ((textStyle.whiteSpace().wraps() || (s == 0 && afterWrappableSpace())) && !segment.isBlank()) {
                if (needsWrap(lineX + kerned + join + trimmedW + edges) && !(s == 0 && continuesWord(segment))) {
                    newLine(runH, baseline, true);
                    ensureLine();
                    kerned = 0;
                    join = 0;
                    // 折り返した行の頭の畳まれる空白は消える（CSS Text §4.1.2。run の先頭の空白は前の語と同じ塊に入っている）
                    if (segment.charAt(0) == ' ' && textStyle.whiteSpace().collapses()) {
                        String stripped = segment.stripLeading();
                        float cut = face.advance(segment.substring(0, segment.length() - stripped.length()), font);
                        segment = stripped;
                        advance -= cut;
                        trimmedW -= cut;
                    }
                }
                dropBelowFloats(lineX + kerned + join + trimmedW + edges);
            }
            // run の先頭の、前の run の文字との詰め（同じ行に続くときだけ）は、断片の位置に入れる
            if (s == 0 && previousOnLine != null && join != 0 && !currentEmpty()) lineX += join;
            else kerned += join;
            previousOnLine = segment;
            wrapSpaceCollapsed = false;
            // 断片の座標も float のまま持つ。丸めるのは描画側の 1 箇所だけ
            if (tabPieces != null) {
                if (tabPieces[0] != lineX) tabPieces = tabLayout(segment, lineX, runBox, face, font); // 折り返して始点が変わった
                addTabPieces(segment, tabPieces, runBox, runH, baseline, descent, inlineParents, alignment, itemFlStyle);
                advance = tabPieces[tabPieces.length - 1];
            } else {
                ensureCurrent().add(new BlockLayoutEngine.LineItem(runBox, lineX, advance, runH, segment, baseline, descent, inlineParents,
                        alignment.align(), alignment.raise(), itemFlStyle));
            }
            lineX += advance;
            trailingSpace = advance - trimmedW;
            markContent();
        }
    }

    /**
     * tab を含む塊の配置。戻り値は {始点の lineX, 各文字の後の位置（始点から）…, 全体の幅}。
     * tab は {@code tab-size}（数なら空白の幅の倍数、長さならその px）の倍数の位置まで進み、残りが空白の半分未満なら次の位置まで進む。
     */
    float[] tabLayout(String segment, float startX, LayoutBox runBox, FontFace face, FontStyle font) {
        float space = face.advance(" ", font);
        String ts = runBox.style.typed().inherited.tabSize;
        float tabW = 8 * space;
        if (ts != null && !ts.isBlank()) {
            String t = ts.trim();
            try {
                tabW = Float.parseFloat(t) * space;
            } catch (NumberFormatException e) {
                tabW = com.nishiyu.enderframelib.core.css.LengthResolver.compile(ts).resolve(0, ctx.viewport().rootW(), ctx.viewport().rootH(), runBox.style.fontSizePx(), runBox.style.rootFontSizePx());
            }
        }
        int n = segment.length();
        float[] out = new float[n + 2];
        out[0] = startX;
        float x = startX;
        for (int i = 0; i < n; i++) {
            char c = segment.charAt(i);
            if (c == '\t' && tabW > 0) {
                float next = (float) (Math.floor((x + 0.01f) / tabW) + 1) * tabW;
                if (next - x < space / 2) next += tabW;
                x = next;
            } else {
                x += face.advance(String.valueOf(c), font);
            }
            out[i + 1] = x - startX;
        }
        out[n + 1] = x - startX;
        return out;
    }

    /**
     * {@code white-space: break-spaces}: 空白は 1 つずつ幅を取り（行末にぶら下がらない）、各空白の後で折り返せる（CSS Text 3 §3・§5.1）。
     */
    void addBreakSpacesParagraph(LayoutBox runBox, List<LayoutBox> inlineParents, FontFace face, FontStyle font,
                                         BlockLayoutEngine.Alignment alignment, float runH, float baseline, float descent, String paragraph,
                                         float closing, ComputedStyle itemFlStyle) {
        int n = paragraph.length();
        int i = 0;
        while (i < n) {
            int j = i;
            if (paragraph.charAt(i) == ' ') {
                j = i + 1;
            } else {
                while (j < n && paragraph.charAt(j) != ' ') j++;
            }
            String token = paragraph.substring(i, j);
            float w = face.advance(token, font);
            float edges = j == n ? closing : 0;
            if (needsWrap(lineX + w + edges)) {
                newLine(runH, baseline, true);
                ensureLine();
            }
            ensureCurrent().add(new BlockLayoutEngine.LineItem(runBox, lineX, w, runH, token, baseline, descent, inlineParents,
                    alignment.align(), alignment.raise(), itemFlStyle));
            lineX += w;
            trailingSpace = 0;
            markContent();
            i = j;
        }
    }

    /**
     * この run の先頭が、行の直前の文字の続き（同じ語）か。要素の境目（{@code h<span></span>ighway}・語の中の絶対配置）は
     * 折り返しの機会ではない（UAX #14。CJK の字の間と、原子的インラインの前後は除く）。
     */
    boolean continuesWord(String segment) {
        if (currentEmpty() || segment.isEmpty()) return false;
        String prev = current.getLast().text();
        if (prev == null || prev.isEmpty()) return false;
        char a = prev.charAt(prev.length() - 1), b = segment.charAt(0);
        if (Character.isWhitespace(a) || Character.isWhitespace(b) || a == '-' || a == '­') return false;
        return a < 0x2E80 && b < 0x2E80;
    }

    /** 行の直前の断片が、折り返しを許す white-space の空白で終わっているか（CSS Text 3 §5.1）。 */
    boolean afterWrappableSpace() {
        if (currentEmpty()) return false;
        if (wrapSpaceCollapsed) return true;
        BlockLayoutEngine.LineItem last = current.getLast();
        String t = last.text();
        if (t == null || t.isEmpty() || t.charAt(t.length() - 1) != ' ' || last.box() == null) return false;
        return WhiteSpace.of(last.box().style.typed().inherited.whiteSpace).wraps();
    }

    static boolean hasWideChars(String s) {
        for (int i = 0, n = s.length(); i < n; i++) if (s.charAt(i) >= 0x2E80) return true;
        return false;
    }

    /** 塊の末尾の空白（tab を含む）の幅。 */
    static float trailingTabWidth(String segment, float[] pieces) {
        int end = segment.length();
        int k = end;
        while (k > 0 && (segment.charAt(k - 1) == ' ' || segment.charAt(k - 1) == '\t')) k--;
        return pieces[end] - (k == 0 ? 0 : pieces[k]);
    }

    /** tab の部分（空の断片）と文字の部分を別の断片に置く（描画は断片の文字を続けて描くので、tab の位置で分ける）。 */
    void addTabPieces(String segment, float[] pieces, LayoutBox runBox, float runH, float baseline, float descent,
                              List<LayoutBox> inlineParents, BlockLayoutEngine.Alignment alignment, ComputedStyle itemFlStyle) {
        int n = segment.length();
        int i = 0;
        while (i < n) {
            boolean tab = segment.charAt(i) == '\t';
            int j = i;
            while (j < n && (segment.charAt(j) == '\t') == tab) j++;
            float from = i == 0 ? 0 : pieces[i];
            float to = pieces[j];
            String text = tab ? " " : segment.substring(i, j);
            ensureCurrent().add(new BlockLayoutEngine.LineItem(runBox, lineX + from, to - from, runH, text, baseline, descent, inlineParents,
                    alignment.align(), alignment.raise(), itemFlStyle));
            i = j;
        }
    }

    void addBreak(BlockLayoutEngine.FlatRun run) {
        // <br>: 内容が無い行でもフォント 1 行分の高さを確保する
        TextStyle brStyle = ctx.textStyleFor(run.box().style);
        ensureLine();
        markContent();
        newLine(brStyle.lineHeightPx(), BlockLayoutEngine.baselineWithin(brStyle, ctx.faceFor(run.box().style)), false);
    }

    void addAtomic(BlockLayoutEngine.FlatRun run, float closing) {
        wrapSpaceCollapsed = false;
        LayoutBox b = run.box();
        float boxOuterW = b.w + b.mL + b.mR;
        float baseline = BlockLayoutEngine.atomicBaseline(b);
        BlockLayoutEngine.Alignment alignment = alignmentOf(b, run.inlineParents(), b.mT + b.mB + b.h, b.mT + baseline);

        ensureLine();
        // 折り返せるかは不可分なインラインを含む側（親）の white-space で決まる。
        // 以前は見ておらず、nowrap の行でもインラインブロックが折り返していた
        ComputedStyle around = run.inlineParents().isEmpty() ? flow.box.style : run.inlineParents().getLast().style;
        if (WhiteSpace.of(around.typed().inherited.whiteSpace).wraps() && needsWrap(lineX + boxOuterW + closing)) {
            newLine(0, 0, true);
            ensureLine();
        }
        dropBelowFloats(lineX + boxOuterW + closing);
        ensureCurrent().add(new BlockLayoutEngine.LineItem(b, lineX + b.mL, b.w, b.h, null, baseline, 0, run.inlineParents(), alignment.align(), alignment.raise(), null));
        lineX += boxOuterW;
        trailingSpace = 0;
        markContent();
    }

    /** 行の中の float。今の行に入るなら行の上端に置き、入らなければ次の行の上端に置く。 */
    void addFloat(LayoutBox f) {
        float w = f.w + f.mL + f.mR;
        if (!lineStarted || currentEmpty() || w <= lineWidth - lineX + 0.01f) {
            flow.placeFloat(f, lineTop);
            if (lineStarted) computeBand();
        } else {
            if (deferredFloats == null) deferredFloats = new ArrayList<>(2);
            deferredFloats.add(f);
        }
    }

    /**
     * 折り返すかどうか。
     * 幅の制約が無いとき（内容ボックスの幅が 0）と、行にまだ何も無いときは折り返さない。
     *
     * 何が壊れていたか: 以前は 4px の余裕（INLINE_WRAP_SLOP）を足していた。
     * 測定が幅を切り上げ、描画が別の基準で測り直していたぶんを吸収するためのものだったが、
     * 測定と描画が同じ送り幅を見るようになったので余裕は不要になり、
     * 代わりに「ブラウザなら折り返す幅で折り返さない」差を生むだけになっていた。
     */
    boolean needsWrap(float lineEnd) {
        return constrained() && !currentEmpty() && lineEnd > lineWidth - balanceShrink + TextShaper.WRAP_TOLERANCE;
    }

    /**
     * 行を確定する。高さとベースラインは断片の実寸と strut から求め、
     * 断片が無い行は渡された fallback を使う（装飾を持つインラインボックスだけの行は strut の高さ）。
     *
     * インラインボックスの上下の padding・border・margin は行の高さに入らない（CSS 2.2 §10.8.1）。
     * 何が壊れていたか: 以前は祖先の上下の装飾を断片ごとに足していて、{@code <code>} の padding が行を押し広げていた。
     */
    InlineLine finishLine(float fallbackHeight, float fallbackBaseline, float used) {
        List<BlockLayoutEngine.BoxSpan> outSpans = spans == null || spans.isEmpty() ? List.of() : spans;
        if (currentEmpty()) {
            boolean decorated = fallbackHeight == 0 && anyDecorated(outSpans);
            return new InlineLine(List.of(), outSpans, lineTop, lineLeft, lineWidth,
                    decorated ? strutHeight : fallbackHeight, decorated ? strutBaseline : fallbackBaseline, used);
        }
        float baseline = strutBaseline;
        float belowBaseline = strutHeight - strutBaseline;
        boolean hasNonBaseline = false;
        for (int i = 0, n = current.size(); i < n; i++) {
            BlockLayoutEngine.LineItem item = current.get(i);
            if (item.align() != BlockLayoutEngine.VAlign.BASELINE) {
                hasNonBaseline = true;
                continue;
            }
            baseline = Math.max(baseline, item.box().mT + item.baseline() + item.raise());
            belowBaseline = Math.max(belowBaseline, item.h() - item.baseline() + item.box().mB - item.raise());
        }
        float height = baseline + belowBaseline;
        // top / bottom は行ボックスの端に揃える。ベースラインで揃えた内容より高ければ行を広げる
        if (hasNonBaseline) {
            for (int i = 0, n = current.size(); i < n; i++) {
                BlockLayoutEngine.LineItem item = current.get(i);
                if (item.align() == BlockLayoutEngine.VAlign.BASELINE) continue;
                height = Math.max(height, BlockLayoutEngine.outerHeight(item));
            }
        }
        List<BlockLayoutEngine.LineItem> outItems;
        if (current.size() == 1) {
            outItems = List.of(current.get(0));
            current.clear();
        } else {
            outItems = current;
            current = null;
        }
        return new InlineLine(outItems, outSpans, lineTop, lineLeft, lineWidth, height, baseline, used);
    }

    /**
     * {@code vertical-align} の揃え方と持ち上げる量。{@code self} と {@code inlineParents} を内側から外側へ辿り、
     * 入れ子の span の持ち上げを累積する。すべて既定の {@code baseline} なら {@link #ALIGNMENT_BASELINE_ZERO} を返す。
     */
    BlockLayoutEngine.Alignment alignmentOf(LayoutBox self, List<LayoutBox> inlineParents, float height, float above) {
        float raise = 0;
        BlockLayoutEngine.VAlign align = BlockLayoutEngine.VAlign.BASELINE;
        if (self != null) {
            long va = self.style.typed().box.verticalAlign;
            if (CssLength.isDeclared(va) && !CssLength.isKeyword(va, CssLength.KW_BASELINE)) {
                if (CssLength.isKeyword(va, CssLength.KW_TOP)) align = BlockLayoutEngine.VAlign.TOP;
                else if (CssLength.isKeyword(va, CssLength.KW_BOTTOM)) align = BlockLayoutEngine.VAlign.BOTTOM;
                else raise += resolveVerticalAlignRaise(self, va, height, above);
            }
        }
        for (int i = inlineParents.size() - 1; i >= 0; i--) {
            LayoutBox el = inlineParents.get(i);
            long va = el.style.typed().box.verticalAlign;
            if (!CssLength.isDeclared(va) || CssLength.isKeyword(va, CssLength.KW_BASELINE)) continue;
            if (CssLength.isKeyword(va, CssLength.KW_TOP)) align = BlockLayoutEngine.VAlign.TOP;
            else if (CssLength.isKeyword(va, CssLength.KW_BOTTOM)) align = BlockLayoutEngine.VAlign.BOTTOM;
            else raise += resolveVerticalAlignRaise(el, va, height, above);
        }
        return align == BlockLayoutEngine.VAlign.BASELINE && raise == 0 ? BlockLayoutEngine.ALIGNMENT_BASELINE_ZERO : new BlockLayoutEngine.Alignment(align, raise);
    }

    float resolveVerticalAlignRaise(LayoutBox el, long va, float height, float above) {
        LayoutBox parentBox = el.parent != null ? el.parent : flow.box;
        FontFace face = ctx.faceFor(parentBox.style);
        FontStyle font = ctx.fontStyleFor(parentBox.style);
        float parentFont = parentBox.style.fontSizePx();
        if (CssLength.isKeyword(va, CssLength.KW_MIDDLE)) return height / 2 + face.xHeight(font) / 2 - above;
        if (CssLength.isKeyword(va, CssLength.KW_SUB)) return -(parentFont / 5 + 1);
        if (CssLength.isKeyword(va, CssLength.KW_SUPER)) return parentFont / 3 + 1;
        if (CssLength.isKeyword(va, CssLength.KW_TEXT_TOP)) return face.ascent(font) - above;
        if (CssLength.isKeyword(va, CssLength.KW_TEXT_BOTTOM)) return (height - above) - face.descent(font);
        return el.style.typed().px(va, el.style.typed().box.exprs, el.lineHeightPx,
                ctx.viewport().rootW(), ctx.viewport().rootH());
    }
}
