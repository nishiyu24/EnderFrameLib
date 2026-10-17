package com.nishiyu.enderframelib.core.layout;

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

        if (!box.style.containsKey("height") || "auto".equals(box.style.getOrDefault("height", "auto"))) {
            int contentH = 0;
            List<LayoutBox> inlineRun = new ArrayList<>();
            List<LayoutBox> flowChildren = box.getFlowChildren();
            int blockSegments = 0;

            for (LayoutBox child : flowChildren) {
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

        int actualInnerH = Math.max(0, box.h - box.verticalDecoration());
        for (LayoutBox child : box.children) {
            if (child.isAbsolute && !isDisplayNone(child.style)) {
                child.measure(actualInnerW, actualInnerH, new FormattingContext("block", "stretch", false), ctx);
            }
        }
    }

    private int measureInlineRunHeight(List<LayoutBox> inlines, int maxW, LayoutContext ctx) {
        List<FlatRun> runs = new ArrayList<>();
        flattenInlines(inlines, runs, new ArrayList<>());
        if (runs.isEmpty()) return 0;

        int lineX = 0;
        int totalH = 0;
        int maxLineH = 0;

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
                String text = run.text;
                boolean isBoldBox = isBold(run.box.style);

                float scale = run.box.fontSize;
                int fontH = (int) (16 * scale);
                int runH = (int) (fontH * lh);
                int totalRunH = runH + decoT + decoB;

                String[] words = text.split("(?<=\\s)|(?=\\s)|(?<=[、。！？.,])");
                lineX += decoL;

                for (String rawWord : words) {
                    if (rawWord.isEmpty()) continue;
                    // B-6 修正: §l の埋め込みを廃止し、FontMetrics の isBold に委譲
                    int wordW = ctx.fontMetrics().getDisplayWidth(rawWord, scale, isBoldBox);
                    if (maxW > 0 && lineX + wordW + decoR > maxW + 4 && lineX > decoL && !rawWord.trim().isEmpty()) {
                        totalH += maxLineH > 0 ? maxLineH : totalRunH;
                        lineX = decoL;
                        maxLineH = 0;
                    }
                    lineX += wordW;
                    maxLineH = Math.max(maxLineH, totalRunH);
                }
                lineX += decoR;
            } else if ("\n".equals(run.text)) {
                totalH += maxLineH > 0 ? maxLineH : (int) (16 * run.box.fontSize * lh);
                lineX = 0;
                maxLineH = 0;
            } else {
                int boxOuterW = run.box.w + run.box.mL + run.box.mR;
                int boxOuterH = run.box.h + run.box.mT + run.box.mB + decoT + decoB;
                if (maxW > 0 && lineX + boxOuterW > maxW + 4 && lineX > 0) {
                    totalH += maxLineH > 0 ? maxLineH : boxOuterH;
                    lineX = 0;
                    maxLineH = 0;
                }
                lineX += boxOuterW;
                maxLineH = Math.max(maxLineH, boxOuterH);
            }
        }
        totalH += maxLineH;
        return totalH;
    }

    @Override
    public void arrangeChildren(LayoutBox box, int innerX, int innerY, LayoutContext ctx) {
        String textAlign = box.style.getOrDefault("text-align", "left");
        int currentY = innerY;
        List<LayoutBox> inlineRun = new ArrayList<>();
        List<LayoutBox> flowChildren = box.getFlowChildren();
        int availableInnerW = box.w - box.pL - box.pR - box.bL - box.bR;

        for (LayoutBox child : flowChildren) {
            if (isInlineBox(child)) {
                inlineRun.add(child);
            } else {
                if (!inlineRun.isEmpty()) {
                    currentY = layoutInlineContext(inlineRun, innerX, currentY, availableInnerW, textAlign, ctx) + box.rowGap;
                    inlineRun.clear();
                }
                if ("auto".equals(child.style.get("margin-left")) && "auto".equals(child.style.get("margin-right"))) {
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

    private boolean isInlineBox(LayoutBox box) {
        if (box.isAbsolute) return false;
        String display = box.style.getOrDefault("display", "block");
        if (display.equals("inline") || display.equals("inline-block")) return true;
        if (display.equals("block") || display.equals("flex") || display.equals("grid")) return false;
        String tag = box.node.tag;
        return tag.equals("#text") || tag.equals("br") || tag.equals("span") || tag.equals("a")
                || tag.equals("b") || tag.equals("i") || tag.equals("strong") || tag.equals("em")
                || tag.equals("img") || tag.equals("input") || tag.equals("button")
                || tag.equals("select") || tag.equals("progress")
                || tag.equals("mc-fluid") || tag.equals("mc-entity") || tag.equals("mc-scene");
    }

    private void flattenInlines(List<LayoutBox> boxes, List<FlatRun> runs, List<LayoutBox> parents) {
        for (LayoutBox b : boxes) {
            if (b.node.tag.equals("#text") && b.displayText != null && !b.displayText.isEmpty()) {
                runs.add(new FlatRun(b, true, b.displayText, new ArrayList<>(parents)));
            } else if (b.node.tag.equals("br")) {
                runs.add(new FlatRun(b, false, "\n", new ArrayList<>(parents)));
            } else if ("inline-block".equals(b.style.getOrDefault("display", "block"))
                    || b.node.tag.equals("img") || b.node.tag.equals("input") || b.node.tag.equals("button")
                    || b.node.tag.equals("select") || b.node.tag.equals("progress")
                    || b.node.tag.equals("mc-fluid") || b.node.tag.equals("mc-entity") || b.node.tag.equals("mc-scene")) {
                runs.add(new FlatRun(b, false, null, new ArrayList<>(parents)));
            } else {
                parents.add(b);
                flattenInlines(b.children, runs, parents);
                parents.removeLast();
            }
        }
    }

    private void clearFragmentsRecursively(LayoutBox box) {
        box.inlineFragments.clear();
        for (LayoutBox child : box.children) clearFragmentsRecursively(child);
    }

    private int layoutInlineContext(List<LayoutBox> inlines, int startX, int startY, int maxW, String textAlign, LayoutContext ctx) {
        List<FlatRun> runs = new ArrayList<>();
        flattenInlines(inlines, runs, new ArrayList<>());
        for (LayoutBox inlineBox : inlines) clearFragmentsRecursively(inlineBox);

        int lineX = 0, lineY = startY, maxLineH = 0;
        List<LayoutBox.InlineFragment> currentLine = new ArrayList<>();
        List<List<LayoutBox.InlineFragment>> lines = new ArrayList<>();

        for (FlatRun run : runs) {
            float lh = parseLineHeight(run.box.style);
            int decoT = 0, decoB = 0, decoL = 0, decoR = 0;
            for (LayoutBox p : run.inlineParents) {
                decoT += p.pT + p.bT; decoB += p.pB + p.bB; decoL += p.pL + p.bL; decoR += p.pR + p.bR;
            }

            if (run.isText) {
                String text = run.text;
                boolean isBoldBox = isBold(run.box.style);

                float scale = run.box.fontSize;
                int fontH = (int) (16 * scale), runH = (int) (fontH * lh), totalRunH = runH + decoT + decoB;

                String[] words = text.split("(?<=\\s)|(?=\\s)|(?<=[、。！？.,])");
                lineX += decoL;

                for (String rawWord : words) {
                    if (rawWord.isEmpty()) continue;
                    int wordW = ctx.fontMetrics().getDisplayWidth(rawWord, scale, isBoldBox);

                    if (lineX + wordW + decoR > maxW + 4 && lineX > decoL && !rawWord.trim().isEmpty()) {
                        lines.add(currentLine);
                        lineY += maxLineH > 0 ? maxLineH : totalRunH;
                        currentLine = new ArrayList<>();
                        lineX = decoL;
                        maxLineH = 0;
                    }
                    LayoutBox.InlineFragment frag = new LayoutBox.InlineFragment(run.box, lineX, decoT, wordW, runH, rawWord);
                    frag.parents = run.inlineParents;
                    currentLine.add(frag);
                    lineX += wordW;
                    maxLineH = Math.max(maxLineH, totalRunH);
                }
                lineX += decoR;
            } else if ("\n".equals(run.text)) {
                lines.add(currentLine);
                lineY += maxLineH > 0 ? maxLineH : (int) (16 * run.box.fontSize * lh);
                currentLine = new ArrayList<>();
                lineX = 0;
                maxLineH = 0;
            } else {
                int boxOuterW = run.box.w + run.box.mL + run.box.mR, boxOuterH = run.box.h + run.box.mT + run.box.mB;
                if (lineX + boxOuterW > maxW + 4 && lineX > 0) {
                    lines.add(currentLine);
                    lineY += maxLineH > 0 ? maxLineH : boxOuterH;
                    currentLine = new ArrayList<>();
                    lineX = 0;
                    maxLineH = 0;
                }
                LayoutBox.InlineFragment frag = new LayoutBox.InlineFragment(run.box, lineX + run.box.mL, 0, run.box.w, run.box.h, null);
                frag.parents = run.inlineParents;
                currentLine.add(frag);
                lineX += boxOuterW;
                maxLineH = Math.max(maxLineH, boxOuterH);
            }
        }
        if (!currentLine.isEmpty()) {
            lines.add(currentLine);
            lineY += maxLineH;
        }

        int currentDrawY = startY;
        for (List<LayoutBox.InlineFragment> line : lines) {
            int lineMaxH = 0;
            for (LayoutBox.InlineFragment f : line) {
                int decoH = 0;
                if (f.parents != null) for (LayoutBox p : f.parents) decoH += p.pT + p.bT + p.pB + p.bB;
                lineMaxH = Math.max(lineMaxH, f.h + f.box.mT + f.box.mB + decoH);
            }

            int actualLineW = line.isEmpty() ? 0 : (line.getLast().x + line.getLast().w - line.getFirst().x);
            int offsetX = startX;
            if (textAlign.equals("center")) offsetX += Math.max(0, (maxW - actualLineW) / 2);
            else if (textAlign.equals("right")) offsetX += Math.max(0, maxW - actualLineW);

            for (LayoutBox.InlineFragment f : line) {
                int decoT = 0, decoB = 0, decoL = 0, decoR = 0;
                if (f.parents != null) {
                    for (LayoutBox p : f.parents) {
                        decoT += p.pT + p.bT; decoB += p.pB + p.bB; decoL += p.pL + p.bL; decoR += p.pR + p.bR;
                    }
                }
                int outerH = f.h + f.box.mT + f.box.mB;
                int offsetY = currentDrawY + Math.max(0, (lineMaxH - (outerH + decoT + decoB)) / 2);

                f.x += offsetX;
                f.y = offsetY + decoT + f.box.mT;
                f.box.inlineFragments.add(f);

                if (f.parents != null) {
                    for (LayoutBox p : f.parents) {
                        LayoutBox.InlineFragment pFrag = new LayoutBox.InlineFragment(p, f.x - decoL, offsetY, f.w + decoL + decoR, f.h + decoT + decoB, null);
                        p.inlineFragments.add(pFrag);
                    }
                }
                if (!f.isText()) f.box.layout(f.x, f.y - f.box.mT, f.w, f.h, ctx);
            }
            currentDrawY += lineMaxH;
        }
        return currentDrawY;
    }

    private static class FlatRun {
        LayoutBox box;
        boolean isText;
        String text;
        List<LayoutBox> inlineParents;

        public FlatRun(LayoutBox box, boolean isText, String text, List<LayoutBox> inlineParents) {
            this.box = box;
            this.isText = isText;
            this.text = text;
            this.inlineParents = inlineParents;
        }
    }
}