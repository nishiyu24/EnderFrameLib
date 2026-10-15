package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.text.TextMetrics;

import java.util.ArrayList;
import java.util.List;

public class BlockLayoutEngine implements LayoutEngine {
    @Override
    public void computeSize(LayoutBox box, int tentativeInnerW, int parentH, boolean isParentFlexRow, boolean isParentFlexColumn, String parentAlignItems, boolean isParentShrink, int rootW, int rootH) {
        int maxChildW = 0;
        int currentInlineW = 0;
        for (LayoutBox child : box.children) {
            if (child.isAbsolute || child.style.getOrDefault("display", "block").equals("none")) continue;
            child.computeSize(tentativeInnerW, parentH, false, false, "stretch", box.w == -1, rootW, rootH);
            int childOuterW = child.w + child.mL + child.mR;
            if (isInlineBox(child)) {
                currentInlineW += childOuterW;
                maxChildW = Math.max(maxChildW, Math.min(tentativeInnerW > 0 ? tentativeInnerW : currentInlineW, currentInlineW));
            } else {
                currentInlineW = 0;
                maxChildW = Math.max(maxChildW, childOuterW);
            }
        }
        if (box.w == -1) {
            box.w = maxChildW + box.horizontalDecoration();
        }
    }

    @Override
    public void computeHeightAndFinalizeChildren(LayoutBox box, int parentH, boolean isParentFlexRow, boolean isParentFlexColumn, int rootW, int rootH) {
        int actualInnerW = Math.max(0, box.w - box.horizontalDecoration());

        if (!box.style.containsKey("height") || box.style.get("height").equals("auto")) {
            int contentH = 0;
            List<LayoutBox> inlineRun = new ArrayList<>();
            List<LayoutBox> flowChildren = box.getFlowChildren();
            int blockSegments = 0;

            for (LayoutBox child : flowChildren) {
                if (isInlineBox(child)) {
                    inlineRun.add(child);
                } else {
                    if (!inlineRun.isEmpty()) {
                        contentH += measureInlineRunHeight(inlineRun, actualInnerW);
                        inlineRun.clear();
                        blockSegments++;
                    }
                    contentH += child.h + child.mT + child.mB;
                    blockSegments++;
                }
            }
            if (!inlineRun.isEmpty()) {
                contentH += measureInlineRunHeight(inlineRun, actualInnerW);
                blockSegments++;
            }
            if (blockSegments > 1) {
                contentH += box.gap * (blockSegments - 1);
            }
            box.h = Math.max(box.h, contentH + box.verticalDecoration());
        }

        int actualInnerH = Math.max(0, box.h - box.verticalDecoration());
        for (LayoutBox child : box.children) {
            if (child.isAbsolute && !child.style.getOrDefault("display", "block").equals("none")) {
                child.computeSize(actualInnerW, actualInnerH, false, false, "stretch", false, rootW, rootH);
            }
        }
    }

    private static boolean isBoldBox(LayoutBox box, List<LayoutBox> parents) {
        if (box.node.tag.equals("b") || box.node.tag.equals("strong")) return true;
        if (box.style.getOrDefault("font-weight", "").matches("(?i)bold|bolder|700|800|900")) return true;
        if (parents != null) {
            for (LayoutBox p : parents) {
                if (p.node.tag.equals("b") || p.node.tag.equals("strong")) return true;
                if (p.style.getOrDefault("font-weight", "").matches("(?i)bold|bolder|700|800|900")) return true;
            }
        }
        return false;
    }

    private int measureInlineRunHeight(List<LayoutBox> inlines, int maxW) {
        List<FlatRun> runs = new ArrayList<>();
        flattenInlines(inlines, runs, new ArrayList<>());
        if (runs.isEmpty()) return 0;

        int lineX = 0;
        int totalH = 0;
        int maxLineH = 0;

        for (FlatRun run : runs) {
            float lh = 1.375f;
            if (run.box.style.containsKey("line-height")) {
                try {
                    lh = Float.parseFloat(run.box.style.get("line-height"));
                } catch (Exception e) {
                    if (run.box.style.get("line-height").endsWith("px")) {
                        lh = Float.parseFloat(run.box.style.get("line-height").replace("px", "")) / 16f;
                    }
                }
            }
            int decoT = 0, decoB = 0, decoL = 0, decoR = 0;
            for (LayoutBox p : run.inlineParents) {
                decoT += p.pT + p.bT;
                decoB += p.pB + p.bB;
                decoL += p.pL + p.bL;
                decoR += p.pR + p.bR;
            }

            if (run.isText) {
                String text = run.text;
                boolean isBold = isBoldBox(run.box, run.inlineParents);

                float scale = run.box.fontSize;
                int fontH = (int) (16 * scale);
                int runH = (int) (fontH * lh);
                int totalRunH = runH + decoT + decoB;

                String[] words = text.split("(?<=\\s)|(?=\\s)|(?<=[、。！？.,])");
                lineX += decoL;

                for (String rawWord : words) {
                    if (rawWord.isEmpty()) continue;
                    String word = (isBold && !rawWord.trim().isEmpty()) ? ("§l" + rawWord) : rawWord;
                    int wordW = TextMetrics.getDisplayWidth(word, scale);
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
    public void layoutChildren(LayoutBox box, int innerX, int innerY, int rootW, int rootH) {
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
                    currentY = layoutInlineContext(inlineRun, innerX, currentY, availableInnerW, textAlign, rootW, rootH) + box.gap;
                    inlineRun.clear();
                }
                if (child.style.getOrDefault("margin-left", "").equals("auto") && child.style.getOrDefault("margin-right", "").equals("auto")) {
                    child.layout(innerX + Math.max(0, (availableInnerW - (child.w + child.mL + child.mR)) / 2), currentY, availableInnerW, child.h, rootW, rootH);
                } else {
                    child.layout(innerX, currentY, availableInnerW, child.h, rootW, rootH);
                }
                currentY += child.h + child.mT + child.mB + box.gap;
            }
        }
        if (!inlineRun.isEmpty()) {
            layoutInlineContext(inlineRun, innerX, currentY, availableInnerW, textAlign, rootW, rootH);
        }
    }

    private boolean isInlineBox(LayoutBox box) {
        if (box.isAbsolute) return false;
        String display = box.style.getOrDefault("display", "");
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
            } else if (b.style.getOrDefault("display", "").equals("inline-block")
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

    private int layoutInlineContext(List<LayoutBox> inlines, int startX, int startY, int maxW, String textAlign, int rootW, int rootH) {
        List<FlatRun> runs = new ArrayList<>();
        flattenInlines(inlines, runs, new ArrayList<>());
        for (LayoutBox inlineBox : inlines) clearFragmentsRecursively(inlineBox);

        int lineX = 0, lineY = startY, maxLineH = 0;
        List<LayoutBox.InlineFragment> currentLine = new ArrayList<>();
        List<List<LayoutBox.InlineFragment>> lines = new ArrayList<>();

        for (FlatRun run : runs) {
            float lh = 1.375f;
            if (run.box.style.containsKey("line-height")) {
                try {
                    lh = Float.parseFloat(run.box.style.get("line-height"));
                } catch (Exception e) {
                    if (run.box.style.get("line-height").endsWith("px")) lh = Float.parseFloat(run.box.style.get("line-height").replace("px", "")) / 16f;
                }
            }
            int decoT = 0, decoB = 0, decoL = 0, decoR = 0;
            for (LayoutBox p : run.inlineParents) {
                decoT += p.pT + p.bT; decoB += p.pB + p.bB; decoL += p.pL + p.bL; decoR += p.pR + p.bR;
            }

            if (run.isText) {
                String text = run.text;
                boolean isBold = isBoldBox(run.box, run.inlineParents);

                float scale = run.box.fontSize;
                int fontH = (int) (16 * scale), runH = (int) (fontH * lh), totalRunH = runH + decoT + decoB;

                String[] words = text.split("(?<=\\s)|(?=\\s)|(?<=[、。！？.,])");
                lineX += decoL;

                for (String rawWord : words) {
                    if (rawWord.isEmpty()) continue;
                    // ★ すべての分割トークンに §l を付与することで2単語目以降も確実に太字を維持
                    String word = (isBold && !rawWord.trim().isEmpty()) ? ("§l" + rawWord) : rawWord;
                    int wordW = TextMetrics.getDisplayWidth(word, scale);

                    if (lineX + wordW + decoR > maxW + 4 && lineX > decoL && !rawWord.trim().isEmpty()) {
                        lines.add(currentLine);
                        lineY += maxLineH > 0 ? maxLineH : totalRunH;
                        currentLine = new ArrayList<>();
                        lineX = decoL;
                        maxLineH = 0;
                    }
                    LayoutBox.InlineFragment frag = new LayoutBox.InlineFragment(run.box, lineX, decoT, wordW, runH, word);
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
                if (!f.isText()) f.box.layout(f.x, f.y - f.box.mT, f.w, f.h, rootW, rootH);
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