package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.dom.NodeType;

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

    /** 1 行分の断片を水平・垂直に揃えて確定する。 */
    private void placeLine(InlineLine line, int startX, int drawY, int maxW, String textAlign, LayoutContext ctx) {
        List<LayoutBox.InlineFragment> fragments = line.fragments;
        if (fragments.isEmpty()) return;

        int actualLineW = fragments.getLast().x + fragments.getLast().w - fragments.getFirst().x;
        int offsetX = startX;
        if (textAlign.equals("center")) offsetX += Math.max(0, (maxW - actualLineW) / 2);
        else if (textAlign.equals("right")) offsetX += Math.max(0, maxW - actualLineW);

        for (LayoutBox.InlineFragment f : fragments) {
            int decoT = 0, decoB = 0, decoL = 0, decoR = 0;
            if (f.parents != null) {
                for (LayoutBox p : f.parents) {
                    decoT += p.pT + p.bT;
                    decoB += p.pB + p.bB;
                    decoL += p.pL + p.bL;
                    decoR += p.pR + p.bR;
                }
            }
            int outerH = f.h + f.box.mT + f.box.mB;
            int offsetY = drawY + Math.max(0, (line.height - (outerH + decoT + decoB)) / 2);

            f.x += offsetX;
            f.y = offsetY + decoT + f.box.mT;
            f.box.inlineFragments.add(f);

            if (f.parents != null) {
                for (LayoutBox p : f.parents) {
                    p.inlineFragments.add(new LayoutBox.InlineFragment(
                            p, f.x - decoL, offsetY, f.w + decoL + decoR, f.h + decoT + decoB, null));
                }
            }
            if (!f.isText()) f.box.layout(f.x, f.y - f.box.mT, f.w, f.h, ctx);
        }
    }

    /**
     * インライン内容を行に分割する。測定と配置が共有する唯一の折り返し実装。
     * 返される断片の x は行内の相対座標で、行の高さは断片から求めた実寸。
     */
    private List<InlineLine> breakIntoLines(List<LayoutBox> inlines, int maxW, LayoutContext ctx) {
        List<FlatRun> runs = new ArrayList<>();
        flattenInlines(inlines, runs, new ArrayList<>());

        List<InlineLine> lines = new ArrayList<>();
        List<LayoutBox.InlineFragment> currentLine = new ArrayList<>();
        int lineX = 0;

        for (FlatRun run : runs) {
            float lh = parseLineHeight(run.box.style);
            int decoT = 0, decoB = 0, decoL = 0, decoR = 0;
            for (LayoutBox p : run.inlineParents) {
                decoT += p.pT + p.bT;
                decoB += p.pB + p.bB;
                decoL += p.pL + p.bL;
                decoR += p.pR + p.bR;
            }

            if (run.isText) {
                float scale = run.box.fontSize;
                int runH = LayoutMetrics.lineHeightPx(scale, lh);
                int fallbackH = runH + decoT + decoB;
                boolean bold = isBold(run.box.style);

                lineX += decoL;
                for (String word : LayoutMetrics.splitWords(run.text)) {
                    if (word.isEmpty()) continue;
                    int wordW = ctx.fontMetrics().getDisplayWidth(word, scale, bold);

                    if (needsWrap(lineX + wordW + decoR, maxW, lineX > decoL) && !word.trim().isEmpty()) {
                        lines.add(finishLine(currentLine, fallbackH));
                        currentLine = new ArrayList<>();
                        lineX = decoL;
                    }
                    LayoutBox.InlineFragment frag =
                            new LayoutBox.InlineFragment(run.box, lineX, decoT, wordW, runH, word);
                    frag.parents = run.inlineParents;
                    currentLine.add(frag);
                    lineX += wordW;
                }
                lineX += decoR;
            } else if ("\n".equals(run.text)) {
                // <br>: 内容が無い行でもフォント 1 行分の高さを確保する
                lines.add(finishLine(currentLine, LayoutMetrics.lineHeightPx(run.box.fontSize, lh)));
                currentLine = new ArrayList<>();
                lineX = 0;
            } else {
                int boxOuterW = run.box.w + run.box.mL + run.box.mR;
                int fallbackH = run.box.h + run.box.mT + run.box.mB + decoT + decoB;
                if (needsWrap(lineX + boxOuterW, maxW, lineX > 0)) {
                    lines.add(finishLine(currentLine, fallbackH));
                    currentLine = new ArrayList<>();
                    lineX = 0;
                }
                LayoutBox.InlineFragment frag = new LayoutBox.InlineFragment(
                        run.box, lineX + run.box.mL, 0, run.box.w, run.box.h, null);
                frag.parents = run.inlineParents;
                currentLine.add(frag);
                lineX += boxOuterW;
            }
        }

        if (!currentLine.isEmpty()) lines.add(finishLine(currentLine, 0));
        return lines;
    }

    /**
     * 折り返すかどうか。
     * {@code maxW <= 0} は「幅の制約が無い」ことを意味するので折り返さない。
     */
    private boolean needsWrap(int lineEnd, int maxW, boolean hasContentOnLine) {
        return maxW > 0 && hasContentOnLine && lineEnd > maxW + LayoutMetrics.INLINE_WRAP_SLOP;
    }

    /** 行を確定する。高さは断片の実寸から求め、断片が無い行は fallback を使う。 */
    private InlineLine finishLine(List<LayoutBox.InlineFragment> fragments, int fallbackHeight) {
        if (fragments.isEmpty()) return new InlineLine(fragments, fallbackHeight);

        int height = 0;
        for (LayoutBox.InlineFragment f : fragments) {
            int decoH = 0;
            if (f.parents != null) {
                for (LayoutBox p : f.parents) decoH += p.pT + p.bT + p.pB + p.bB;
            }
            height = Math.max(height, f.h + f.box.mT + f.box.mB + decoH);
        }
        return new InlineLine(fragments, height);
    }

    private boolean isInlineBox(LayoutBox box) {
        if (box.isAbsolute) return false;
        String display = box.style.getOrDefault(CssProperty.DISPLAY, "block");
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

    /** 行分割の結果 1 行。 */
    private record InlineLine(List<LayoutBox.InlineFragment> fragments, int height) {}

    /** 入れ子のインライン要素を平坦化した 1 要素（テキスト / 改行 / 不可分なインラインボックス）。 */
    private record FlatRun(LayoutBox box, boolean isText, String text, List<LayoutBox> inlineParents) {}
}
