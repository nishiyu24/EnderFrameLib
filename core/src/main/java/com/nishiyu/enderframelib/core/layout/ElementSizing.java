package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;
import com.nishiyu.enderframelib.core.raster.svg.SvgSize;
import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.text.model.FontStyle;

/**
 * ネイティブ部品（input / select / progress）と埋め込み（canvas / svg）の固有寸法の計算（S2）。
 * タグごとに値そのものが違う計算をここへ集め、測定の各段（{@link BoxMeasurer}・{@link IntrinsicSizing}）はタグ名を見ない。
 * 対象外の要素には {@code NaN} / {@code null} を返す。
 */
final class ElementSizing {
    private ElementSizing() {}

    /** 最小内容幅（装飾込み）。input / progress は既定 90・size 属性で 8px 倍・チェック類は固定、select は選択肢の最長の幅。部品でなければ NaN。 */
    static float minContentWidth(LayoutBox box, LayoutContext ctx) {
        String tag = box.node.tag;
        if (tag.equals("select")) {
            return selectLabelWidth(box, ctx) + box.horizontalDecoration();
        }
        if (tag.equals("input") || tag.equals("progress")) {
            float inputW = 90;
            String type = box.node.attrs.getOrDefault("type", "text");
            if (tag.equals("input") && (type.equals("checkbox") || type.equals("radio"))) {
                inputW = LayoutMetrics.FALLBACK_REPLACED_SIZE;
            } else if (box.node.attrs.containsKey("size")) {
                try {
                    inputW = Integer.parseInt(box.node.attrs.get("size")) * 8;
                } catch (NumberFormatException ignored) {
                }
            }
            return inputW + box.horizontalDecoration();
        }
        return Float.NaN;
    }

    /** 高さ（装飾込み）。input / select / progress 以外は NaN。 */
    static float height(LayoutBox box, LayoutContext ctx) {
        String tag = box.node.tag;
        if (tag.equals("input") || tag.equals("select")) {
            float h = LayoutMetrics.FALLBACK_REPLACED_SIZE + box.verticalDecoration();
            String type = box.node.attrs.getOrDefault("type", "text");
            boolean compact = type.equals("checkbox") || type.equals("radio") || type.equals("range");
            // 内容の高さは Chrome（Windows）の実測に合わせる。以前は「行の高さ + 8px」という独自の値で、
            // padding を指定した入力欄が Chrome より 8px 高くなっていた。
            // - テキストの入力欄: line-height と書体の normal の行の高さの大きい方（字が欠けない）
            // - select: line-height によらず ascent + descent + 2px
            if (tag.equals("select") || !compact) {
                FontFace face = ctx.faceFor(box.style);
                FontStyle font = ctx.fontStyleFor(box.style);
                float content = tag.equals("select")
                        ? face.ascent(font) + face.descent(font) + 2
                        : Math.max(box.lineHeightPx, face.normalLineHeight(font));
                h = content + box.verticalDecoration();
            }
            return h;
        }

        if (tag.equals("progress")) {
            return 12 + box.verticalDecoration();
        }
        return Float.NaN;
    }


    /** canvas（width / height 属性・既定 300×150）とインライン svg（属性か viewBox）の固有寸法。それ以外は null。 */
    static int[] embeddedIntrinsic(LayoutBox box) {
        String tag = box.node.tag;
        if (tag.equals("canvas")) {
            return new int[]{canvasDimension(box.node.attrs.get("width"), 300), canvasDimension(box.node.attrs.get("height"), 150)};
        }
        if (tag.equals("svg")) {
            double[] d = SvgSize.intrinsic(box.node);
            double w = Double.isNaN(d[0]) ? 300 : d[0];
            double h = Double.isNaN(d[1]) ? 150 : d[1];
            return new int[]{(int) Math.max(1, Math.round(w)), (int) Math.max(1, Math.round(h))};
        }
        return null;
    }

    private static int canvasDimension(String attr, int def) {
        if (attr == null) return def;
        try {
            int v = Integer.parseInt(attr.trim());
            return v >= 0 && v <= 32767 ? v : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static float selectLabelWidth(LayoutBox box, LayoutContext ctx) {
        FontFace face = ctx.faceFor(box.style);
        FontStyle font = ctx.fontStyleFor(box.style);
        float max = face.advance(" ▾", font);
        for (HtmlNode option : box.node.children) {
            if ("optgroup".equals(option.tag)) {
                for (HtmlNode o : option.children) {
                    if ("option".equals(o.tag)) max = Math.max(max, optionLabelWidth(o, face, font));
                }
            } else if ("option".equals(option.tag)) {
                max = Math.max(max, optionLabelWidth(option, face, font));
            }
        }
        return max;
    }

    private static float optionLabelWidth(HtmlNode o, FontFace face, FontStyle font) {
        String label = o.attrs.containsKey("label") ? o.attrs.get("label") : o.getTextContent();
        label = label == null ? "" : collapseSelectSpaces(label);
        return face.advance(label + " ▾", font);
    }

    private static String collapseSelectSpaces(String s) {
        int len = s.length();
        int start = 0;
        while (start < len && Character.isWhitespace(s.charAt(start))) start++;
        int end = len;
        while (end > start && Character.isWhitespace(s.charAt(end - 1))) end--;
        if (start == end) return "";
        StringBuilder sb = null;
        boolean prevSpace = false;
        for (int i = start; i < end; i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                if (prevSpace || c != ' ') {
                    if (sb == null) {
                        sb = new StringBuilder(end - start);
                        sb.append(s, start, i);
                    }
                    if (!prevSpace) sb.append(' ');
                } else if (sb != null) {
                    sb.append(' ');
                }
                prevSpace = true;
            } else {
                if (sb != null) sb.append(c);
                prevSpace = false;
            }
        }
        if (sb != null) return sb.toString();
        return (start == 0 && end == len) ? s : s.substring(start, end);
    }
}
