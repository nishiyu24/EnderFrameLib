package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.dom.NodeType;
import com.nishiyu.enderframelib.core.text.FontFace;
import com.nishiyu.enderframelib.core.text.FontStyle;
import com.nishiyu.enderframelib.core.text.TextShaper;

import java.util.ArrayList;
import java.util.List;

public class BlockLayoutEngine extends AbstractLayoutEngine {

    @Override
    public void measure(LayoutBox box, int tentativeInnerW, int parentH, FormattingContext fmtCtx, LayoutContext ctx) {
        int maxChildW = 0;
        int currentInlineW = 0;

        FormattingContext childCtx = new FormattingContext("block", "stretch", box.sizingMode == SizingMode.SHRINK_TO_FIT);

        for (LayoutBox child : box.children) {
            if (child.isAbsolute || isDisplayNone(child.style)) continue;
            child.measure(tentativeInnerW, parentH, childCtx, ctx);
            int childOuterW = child.w + child.mL + child.mR;
            if (isInlineBox(child)) {
                currentInlineW += childOuterW;
                maxChildW = Math.max(maxChildW, Math.min(tentativeInnerW > 0 ? tentativeInnerW : currentInlineW, currentInlineW));
            } else {
                currentInlineW = 0;
                maxChildW = Math.max(maxChildW, childOuterW);
            }
        }
        if (box.sizingMode == SizingMode.SHRINK_TO_FIT) {
            box.w = maxChildW + box.horizontalDecoration();
        }
    }

    @Override
    public void resolveFlexibleHeights(LayoutBox box, int parentH, FormattingContext fmtCtx, LayoutContext ctx) {
        int actualInnerW = Math.max(0, box.w - box.horizontalDecoration());

        if (!box.style.containsKey(CssProperty.HEIGHT) || "auto".equals(box.style.getOrDefault(CssProperty.HEIGHT, "auto"))) {
            int contentH = 0;
            List<LayoutBox> inlineRun = new ArrayList<>();
            int blockSegments = 0;

            for (LayoutBox child : box.getFlowChildren()) {
                if (isInlineBox(child)) {
                    inlineRun.add(child);
                } else {
                    if (!inlineRun.isEmpty()) {
                        contentH += measureInlineRunHeight(inlineRun, actualInnerW, ctx);
                        inlineRun.clear();
                        blockSegments++;
                    }
                    contentH += child.h + child.mT + child.mB;
                    blockSegments++;
                }
            }
            if (!inlineRun.isEmpty()) {
                contentH += measureInlineRunHeight(inlineRun, actualInnerW, ctx);
                blockSegments++;
            }
            if (blockSegments > 1) {
                contentH += box.rowGap * (blockSegments - 1);
            }
            box.h = Math.max(box.h, contentH + box.verticalDecoration());
        }

        measureAbsoluteChildren(box, ctx);
    }

    @Override
    public void arrangeChildren(LayoutBox box, int innerX, int innerY, LayoutContext ctx) {
        String textAlign = box.style.getOrDefault(CssProperty.TEXT_ALIGN, "left");
        int currentY = innerY;
        List<LayoutBox> inlineRun = new ArrayList<>();
        int availableInnerW = box.w - box.horizontalDecoration();

        for (LayoutBox child : box.getFlowChildren()) {
            if (isInlineBox(child)) {
                inlineRun.add(child);
            } else {
                if (!inlineRun.isEmpty()) {
                    currentY = layoutInlineContext(inlineRun, innerX, currentY, availableInnerW, textAlign, ctx) + box.rowGap;
                    inlineRun.clear();
                }
                if ("auto".equals(child.style.get(CssProperty.MARGIN_LEFT)) && "auto".equals(child.style.get(CssProperty.MARGIN_RIGHT))) {
                    child.layout(innerX + Math.max(0, (availableInnerW - (child.w + child.mL + child.mR)) / 2), currentY, availableInnerW, child.h, ctx);
                } else {
                    child.layout(innerX, currentY, availableInnerW, child.h, ctx);
                }
                currentY += child.h + child.mT + child.mB + box.rowGap;
            }
        }
        if (!inlineRun.isEmpty()) {
            layoutInlineContext(inlineRun, innerX, currentY, availableInnerW, textAlign, ctx);
        }
    }

    // ==========================================
    // インライン整形
    // ==========================================

    /**
     * インライン内容の高さを測る。
     * 行分割そのものは {@link #breakIntoLines} が唯一の実装で、配置パスと必ず同じ結果になる。
     * （以前は測定用と配置用に折り返しロジックが別々に書かれていて、
     *  片方だけ直すと測定高と配置高がずれる状態だった）
     */
    private int measureInlineRunHeight(List<LayoutBox> inlines, int maxW, LayoutContext ctx) {
        int totalH = 0;
        for (InlineLine line : breakIntoLines(inlines, maxW, ctx)) {
            totalH += line.height;
        }
        return totalH;
    }

    /**
     * インライン内容を実際に配置し、次のブロックが始まる Y 座標を返す。
     */
    private int layoutInlineContext(List<LayoutBox> inlines, int startX, int startY, int maxW,
                                    String textAlign, LayoutContext ctx) {
        List<InlineLine> lines = breakIntoLines(inlines, maxW, ctx);
        for (LayoutBox inlineBox : inlines) clearFragmentsRecursively(inlineBox);

        int currentDrawY = startY;
        for (InlineLine line : lines) {
            placeLine(line, startX, currentDrawY, maxW, textAlign, ctx);
            currentDrawY += line.height;
        }
        return currentDrawY;
    }

    /**
     * 1 行分の断片を水平に揃え、ベースラインで縦に揃えて確定する。
     *
     * 何が壊れていたか: 以前は断片を行の高さに対して中央寄せしていたため、
     * サイズの違う文字やインラインブロックが混ざった行で文字の足元が揃わなかった。
     * CSS はベースラインを揃える。
     *
     * LIMITATION(制約): 揃え方はベースライン固定で、{@code vertical-align} は読まない。
     * 置換要素とインラインブロックのベースラインは下端で近似している
     * （CSS は最後の行ボックスのベースライン）。
     */
    private void placeLine(InlineLine line, int startX, int drawY, int maxW, String textAlign, LayoutContext ctx) {
        List<LayoutBox.InlineFragment> fragments = line.fragments;
        if (fragments.isEmpty()) return;

        int actualLineW = fragments.getLast().x + fragments.getLast().w - fragments.getFirst().x;
        int offsetX = startX;
        if (textAlign.equals("center")) offsetX += Math.max(0, (maxW - actualLineW) / 2);
        else if (textAlign.equals("right")) offsetX += Math.max(0, maxW - actualLineW);

        for (LayoutBox.InlineFragment f : fragments) {
            Decoration deco = decorationOf(f.parents);
            // 装飾箱の上端。断片のベースラインが行のベースラインに重なる位置に置く
            int offsetY = Math.round(drawY + line.baseline() - (deco.top() + f.box.mT + f.baseline));

            f.x += offsetX;
            f.y = offsetY + deco.top() + f.box.mT;
            f.box.inlineFragments.add(f);

            if (f.parents != null) {
                for (LayoutBox p : f.parents) {
                    LayoutBox.InlineFragment decoFrag = new LayoutBox.InlineFragment(
                            p, f.x - deco.left(), offsetY, f.w + deco.left() + deco.right(),
                            f.h + deco.top() + deco.bottom(), null);
                    decoFrag.baseline = deco.top() + f.baseline;
                    p.inlineFragments.add(decoFrag);
                }
            }
            if (!f.isText()) f.box.layout(f.x, f.y - f.box.mT, f.w, f.h, ctx);
        }
    }

    /** インラインの祖先が足す padding + border の合計。 */
    private Decoration decorationOf(List<LayoutBox> parents) {
        int t = 0, r = 0, b = 0, l = 0;
        if (parents != null) {
            for (LayoutBox p : parents) {
                t += p.pT + p.bT;
                r += p.pR + p.bR;
                b += p.pB + p.bB;
                l += p.pL + p.bL;
            }
        }
        return new Decoration(t, r, b, l);
    }

    private record Decoration(int top, int right, int bottom, int left) {}

    /**
     * インライン内容を行に分割する。測定と配置が共有する唯一の折り返し実装。
     * 返される断片の x は行内の相対座標で、行の高さは断片から求めた実寸。
     */
    private List<InlineLine> breakIntoLines(List<LayoutBox> inlines, int maxW, LayoutContext ctx) {
        List<FlatRun> runs = new ArrayList<>();
        flattenInlines(inlines, runs, new ArrayList<>());

        List<InlineLine> lines = new ArrayList<>();
        List<LayoutBox.InlineFragment> currentLine = new ArrayList<>();
        // 行内の進み位置は float で持つ。断片ごとに切り上げていたため、
        // 1 行に n 語あると最大 n px ぶんの誤差が溜まり、行全体を 1 回測った幅と合わなかった。
        float lineX = 0;

        for (FlatRun run : runs) {
            Decoration deco = decorationOf(run.inlineParents);

            if (run.isText) {
                TextShaper.TextStyle textStyle = ctx.textStyleFor(run.box.style);
                FontFace face = ctx.faceFor(run.box.style);
                FontStyle font = textStyle.font();
                int runH = Math.round(textStyle.lineHeightPx());
                float baseline = baselineWithin(textStyle, face);

                lineX += deco.left();
                // white-space が改行を残す（pre / pre-wrap / pre-line）なら原文の改行で行を割る。
                // 以前は改行が区切りとして扱われず、<pre> の中身が 1 行に潰れていた。
                String[] paragraphs = textStyle.whiteSpace().preservesNewlines()
                        ? run.text.split("\n", -1)
                        : new String[]{run.text};

                for (int p = 0; p < paragraphs.length; p++) {
                    if (p > 0) {
                        lines.add(finishLine(currentLine, runH, baseline));
                        currentLine = new ArrayList<>();
                        lineX = deco.left();
                    }
                    for (String segment : TextShaper.segments(paragraphs[p], textStyle.wordBreak())) {
                        if (segment.isEmpty()) continue;
                        float advance = face.advance(segment, font);
                        // 行末の空白は幅に数えない（CSS と同じ）
                        float trimmedW = face.advance(segment.stripTrailing(), font);

                        if (textStyle.whiteSpace().wraps()
                                && needsWrap(lineX + trimmedW + deco.right(), maxW, lineX > deco.left())
                                && !segment.isBlank()) {
                            lines.add(finishLine(currentLine, runH, baseline));
                            currentLine = new ArrayList<>();
                            lineX = deco.left();
                        }
                        // 丸めるのは座標に落とすこの 1 箇所だけ
                        int fragX = Math.round(lineX);
                        int fragW = Math.round(lineX + advance) - fragX;
                        LayoutBox.InlineFragment frag =
                                new LayoutBox.InlineFragment(run.box, fragX, deco.top(), fragW, runH, segment);
                        frag.baseline = baseline;
                        frag.parents = run.inlineParents;
                        currentLine.add(frag);
                        lineX += advance;
                    }
                }
                lineX += deco.right();
            } else if (isLineBreak(run)) {
                // <br>: 内容が無い行でもフォント 1 行分の高さを確保する
                TextShaper.TextStyle brStyle = ctx.textStyleFor(run.box.style);
                lines.add(finishLine(currentLine, Math.round(brStyle.lineHeightPx()),
                        baselineWithin(brStyle, ctx.faceFor(run.box.style))));
                currentLine = new ArrayList<>();
                lineX = 0;
            } else {
                int boxOuterW = run.box.w + run.box.mL + run.box.mR;
                int fallbackH = run.box.h + run.box.mT + run.box.mB + deco.top() + deco.bottom();
                if (needsWrap(lineX + boxOuterW, maxW, lineX > 0)) {
                    lines.add(finishLine(currentLine, fallbackH, fallbackH));
                    currentLine = new ArrayList<>();
                    lineX = 0;
                }
                LayoutBox.InlineFragment frag = new LayoutBox.InlineFragment(
                        run.box, Math.round(lineX) + run.box.mL, 0, run.box.w, run.box.h, null);
                // 置換要素・インラインブロックのベースラインは下端（CSS の近似）
                frag.baseline = run.box.h;
                frag.parents = run.inlineParents;
                currentLine.add(frag);
                lineX += boxOuterW;
            }
        }

        if (!currentLine.isEmpty()) lines.add(finishLine(currentLine, 0, 0));
        return lines;
    }

    /** 断片の上端からベースラインまでの距離。整形器と同じ half-leading の分け方を使う。 */
    private float baselineWithin(TextShaper.TextStyle textStyle, FontFace face) {
        FontStyle font = textStyle.font();
        float ascent = face.ascent(font);
        float descent = face.descent(font);
        return (textStyle.lineHeightPx() - (ascent + descent)) / 2f + ascent;
    }

    /**
     * 折り返すかどうか。
     * {@code maxW <= 0} は「幅の制約が無い」ことを意味するので折り返さない。
     *
     * 何が壊れていたか: 以前は 4px の余裕（INLINE_WRAP_SLOP）を足していた。
     * 測定が幅を切り上げ、描画が別の基準で測り直していたぶんを吸収するためのものだったが、
     * 測定と描画が同じ送り幅を見るようになったので余裕は不要になり、
     * 代わりに「ブラウザなら折り返す幅で折り返さない」差を生むだけになっていた。
     */
    private boolean needsWrap(float lineEnd, int maxW, boolean hasContentOnLine) {
        return maxW > 0 && hasContentOnLine && lineEnd > maxW;
    }

    /**
     * 行を確定する。高さとベースラインは断片の実寸から求め、
     * 断片が無い行は渡された fallback を使う。
     */
    private InlineLine finishLine(List<LayoutBox.InlineFragment> fragments,
                                  int fallbackHeight, float fallbackBaseline) {
        if (fragments.isEmpty()) return new InlineLine(fragments, fallbackHeight, fallbackBaseline);

        float baseline = 0;
        float belowBaseline = 0;
        for (LayoutBox.InlineFragment f : fragments) {
            Decoration deco = decorationOf(f.parents);
            baseline = Math.max(baseline, deco.top() + f.box.mT + f.baseline);
            belowBaseline = Math.max(belowBaseline, f.h - f.baseline + f.box.mB + deco.bottom());
        }
        return new InlineLine(fragments, Math.round(baseline + belowBaseline), baseline);
    }

    private boolean isInlineBox(LayoutBox box) {
        if (box.isAbsolute) return false;
        String display = box.style.getOrDefault(CssProperty.DISPLAY, "");
        if (display.equals("inline") || display.equals("inline-block")) return true;
        if (display.equals("block") || display.equals("flex") || display.equals("grid")) return false;
        String tag = box.node.tag;
        return box.node.nodeType == NodeType.TEXT || tag.equals("br") || tag.equals("span") || tag.equals("a")
                || tag.equals("b") || tag.equals("i") || tag.equals("strong") || tag.equals("em")
                || tag.equals("img") || tag.equals("input") || tag.equals("button")
                || tag.equals("select") || tag.equals("progress")
                || tag.equals("mc-fluid") || tag.equals("mc-entity") || tag.equals("mc-scene");
    }

    private void flattenInlines(List<LayoutBox> boxes, List<FlatRun> runs, List<LayoutBox> parents) {
        for (LayoutBox b : boxes) {
            if (b.node.nodeType == NodeType.TEXT && b.displayText != null && !b.displayText.isEmpty()) {
                runs.add(new FlatRun(b, true, b.displayText, new ArrayList<>(parents)));
            } else if (b.node.tag.equals("br")) {
                runs.add(new FlatRun(b, false, "\n", new ArrayList<>(parents)));
            } else if (isAtomicInline(b)) {
                runs.add(new FlatRun(b, false, null, new ArrayList<>(parents)));
            } else {
                parents.add(b);
                flattenInlines(b.children, runs, parents);
                parents.removeLast();
            }
        }
    }

    /** 中身を分割せず 1 つの塊として置くインライン要素。 */
    private boolean isAtomicInline(LayoutBox b) {
        if ("inline-block".equals(b.style.getOrDefault(CssProperty.DISPLAY, "block"))) return true;
        String tag = b.node.tag;
        return tag.equals("img") || tag.equals("input") || tag.equals("button")
                || tag.equals("select") || tag.equals("progress")
                || tag.equals("mc-fluid") || tag.equals("mc-entity") || tag.equals("mc-scene");
    }

    private void clearFragmentsRecursively(LayoutBox box) {
        box.inlineFragments.clear();
        for (LayoutBox child : box.children) clearFragmentsRecursively(child);
    }

    /** &lt;br&gt; を表す平坦化結果か。 */
    private boolean isLineBreak(FlatRun run) {
        return !run.isText && run.box.node.tag.equals("br");
    }

    /** 行分割の結果 1 行。{@code baseline} は行の上端からベースラインまでの距離。 */
    private record InlineLine(List<LayoutBox.InlineFragment> fragments, int height, float baseline) {}

    /** 入れ子のインライン要素を平坦化した 1 要素（テキスト / 改行 / 不可分なインラインボックス）。 */
    private record FlatRun(LayoutBox box, boolean isText, String text, List<LayoutBox> inlineParents) {}
}
