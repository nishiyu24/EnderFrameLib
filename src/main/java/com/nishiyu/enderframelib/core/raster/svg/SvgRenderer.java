package com.nishiyu.enderframelib.core.raster.svg;

import com.nishiyu.enderframelib.core.css.ColorParser;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.raster.CanvasGradient;
import com.nishiyu.enderframelib.core.raster.CanvasRenderingContext2D;
import com.nishiyu.enderframelib.core.raster.Matrix2D;
import com.nishiyu.enderframelib.core.raster.Path2D;
import com.nishiyu.enderframelib.core.raster.Paint;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.text.model.FontResolver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * SVG 1.1 / SVG 2 の静止画としての部分集合を、{@link CanvasRenderingContext2D} の上に描く。
 *
 * 入力は HTML パーサが組んだ {@code svg} 要素の木（インライン）か、{@link SvgImage#parse} が同じパーサで組んだ木（画像）。
 * HTML パーサは要素名と属性名を小文字にするので、{@code viewBox} は {@code viewbox}、{@code linearGradient} は
 * {@code lineargradient} として読む。
 *
 * 対応: {@code g} / {@code defs} / {@code use} / {@code symbol}、{@code rect}（{@code rx} / {@code ry}）・{@code circle}・
 * {@code ellipse}・{@code line}・{@code polyline}・{@code polygon}・{@code path}・{@code text}、{@code viewBox} と
 * {@code preserveAspectRatio}、{@code transform} 属性（matrix / translate / scale / rotate / skewX / skewY）、
 * {@code fill} / {@code stroke} とその不透明度・線幅・端・結合・マイター・破線・{@code fill-rule}・{@code opacity}（グループは
 * 別のビットマップに描いて重ねる）、{@code linearGradient} / {@code radialGradient}（{@code gradientUnits} /
 * {@code gradientTransform} / {@code spreadMethod} / {@code href} の継承）、{@code clipPath}、{@code currentColor}、
 * 提示属性と {@code style} 属性（宣言の優先は style が上）。
 *
 * LIMITATION(R8): ページの {@code <style>} / スタイルシートの規則は適用しない（要素の {@code style} 属性と提示属性だけ）。
 * {@code mask}・{@code filter}・{@code marker}・{@code pattern}・{@code image}・{@code tspan} の個別配置・{@code textPath}・
 * {@code symbol} の {@code viewBox} は持たない。テキストは {@link CanvasRenderingContext2D#fillText} と同じ制約（回転しない）。
 */
public final class SvgRenderer {

    private static final int MAX_DEPTH = 32;
    private static final int MAX_NODES = 20_000;
    private static final Set<String> SHAPES = Set.of("rect", "circle", "ellipse", "line", "polyline", "polygon", "path");
    private static final Set<String> NON_RENDERED = Set.of("defs", "lineargradient", "radialgradient", "clippath", "mask",
            "pattern", "marker", "symbol", "style", "title", "desc", "metadata", "filter", "script");

    /** 継承する提示属性。 */
    private static final Set<String> INHERITED = Set.of("fill", "fill-opacity", "fill-rule", "stroke", "stroke-width",
            "stroke-opacity", "stroke-linecap", "stroke-linejoin", "stroke-miterlimit", "stroke-dasharray",
            "stroke-dashoffset", "color", "font-size", "font-family", "font-weight", "font-style", "text-anchor",
            "visibility", "clip-rule");
    private static final Set<String> PRESENTATION = Set.of("fill", "fill-opacity", "fill-rule", "stroke", "stroke-width",
            "stroke-opacity", "stroke-linecap", "stroke-linejoin", "stroke-miterlimit", "stroke-dasharray",
            "stroke-dashoffset", "color", "font-size", "font-family", "font-weight", "font-style", "text-anchor",
            "visibility", "clip-rule", "opacity", "display", "stop-color", "stop-opacity", "clip-path");

    private CanvasRenderingContext2D ctx;
    private FontResolver fonts;
    private final Map<String, HtmlNode> ids = new HashMap<>();
    private int nodeBudget = MAX_NODES;
    private double vpW;
    private double vpH;

    private SvgRenderer(CanvasRenderingContext2D ctx) {
        this.ctx = ctx;
    }

    /**
     * {@code svg} 要素を {@code pxW × pxH} のビットマップに描く。{@code viewBox} があればそこから、なければ
     * {@code width} / {@code height}（なければ 300×150）の座標系から、{@code preserveAspectRatio} に従って写す。
     *
     * @param currentColor {@code currentColor} と {@code color} の初期値（非乗算 ARGB）
     */
    public static Pixmap render(HtmlNode svg, int pxW, int pxH, int currentColor, FontResolver fonts) {
        int w = Math.max(1, Math.min(Pixmap.MAX_DIMENSION, pxW));
        int h = Math.max(1, Math.min(Pixmap.MAX_DIMENSION, pxH));
        CanvasRenderingContext2D ctx = new CanvasRenderingContext2D(w, h);
        if (fonts != null) ctx.fonts(fonts);
        SvgRenderer r = new SvgRenderer(ctx);
        r.fonts = fonts;
        r.collectIds(svg, 0);
        Style root = new Style();
        root.set("color", ColorParser.format(currentColor));
        root.set("fill", "black");
        root.set("stroke", "none");
        SvgSize.Size size = SvgSize.of(svg);
        double docW = Double.isNaN(size.width()) ? 300 : size.width();
        double docH = Double.isNaN(size.height()) ? 150 : size.height();
        double[] vb = SvgSize.viewBox(svg);
        double vbW = vb != null ? vb[2] : docW;
        double vbH = vb != null ? vb[3] : docH;
        r.vpW = vbW;
        r.vpH = vbH;
        Matrix2D fit = SvgSize.viewBoxTransform(vb, vbW, vbH, w, h, svg.attrs.get("preserveaspectratio"));
        ctx.setTransform(fit);
        ctx.save();
        if (vb != null && !SvgSize.isSlice(svg.attrs.get("preserveaspectratio"))) {
            // viewBox の外は描かない（meet のとき余白に出ない）。slice は出力の枠で切れる。
            ctx.beginPath();
            ctx.rect(vb[0], vb[1], vb[2], vb[3]);
            ctx.clip(false);
        }
        r.renderChildren(svg, r.styleFor(svg, root), 0);
        ctx.restore();
        return ctx.bitmap();
    }

    /**
     * 木の内容（要素名・属性・文字）の署名。同じ署名・同じ大きさ・同じ currentColor なら同じ画素になる
     * （ラスタライズの結果をキャッシュするキー）。
     */
    public static long signature(HtmlNode svg) {
        long[] h = {0xcbf29ce484222325L};
        sign(svg, h, 0);
        return h[0];
    }

    private static void sign(HtmlNode n, long[] h, int depth) {
        if (depth > MAX_DEPTH * 4) return;
        if (n.nodeType == NodeType.TEXT) {
            mix(h, n.text == null ? "" : n.text);
            return;
        }
        if (n.nodeType != NodeType.ELEMENT) return;
        mix(h, n.tag);
        for (Map.Entry<String, String> e : n.attrs.entrySet()) {
            // パイプラインが振る id（gen_N）は文書ごとに違うだけで描画に関わらない
            if (e.getKey().equals("id") && e.getValue() != null && e.getValue().startsWith("gen_")) continue;
            mix(h, e.getKey());
            mix(h, e.getValue());
        }
        h[0] = h[0] * 0x100000001b3L + 0x3b;
        for (HtmlNode c : n.children) sign(c, h, depth + 1);
        h[0] = h[0] * 0x100000001b3L + 0x3d;
    }

    private static void mix(long[] h, String s) {
        long v = h[0];
        for (int i = 0; i < s.length(); i++) v = (v ^ s.charAt(i)) * 0x100000001b3L;
        h[0] = (v ^ 0x1f) * 0x100000001b3L;
    }

    // ------------------------------------------------------------------ 木

    private void collectIds(HtmlNode n, int depth) {
        if (depth > MAX_DEPTH * 4) return;
        String id = n.attrs.get("id");
        if (id != null && !id.isEmpty()) ids.putIfAbsent(id, n);
        for (HtmlNode c : n.children) {
            if (c.nodeType == NodeType.ELEMENT) collectIds(c, depth + 1);
        }
    }

    private void renderChildren(HtmlNode parent, Style style, int depth) {
        for (HtmlNode c : parent.children) {
            if (c.nodeType == NodeType.ELEMENT) renderElement(c, style, depth + 1);
        }
    }

    private void renderElement(HtmlNode el, Style parent, int depth) {
        if (depth > MAX_DEPTH || --nodeBudget < 0) return;
        String tag = el.tag;
        if (NON_RENDERED.contains(tag)) return;
        boolean isShape = SHAPES.contains(tag);
        boolean isContainer = tag.equals("g") || tag.equals("a") || tag.equals("svg") || tag.equals("switch");
        if (!isShape && !isContainer && !tag.equals("use") && !tag.equals("text")) return;
        Style st = styleFor(el, parent);
        if ("none".equals(st.local("display"))) return;

        double opacity = clamp01(number(st.local("opacity"), 1));
        boolean group = opacity < 0.999 && (isContainer || tag.equals("use"));
        ctx.save();
        CanvasRenderingContext2D parentCtx = null;
        try {
            Matrix2D t = parseTransform(el.attrs.get("transform"));
            if (t != null) ctx.transform(t.a(), t.b(), t.c(), t.d(), t.e(), t.f());
            applyClip(el, st);

            if (group) {
                // 子が重なるグループの不透明度は、別のビットマップにまとめて描いてから一度に重ねる
                parentCtx = ctx;
                ctx = new CanvasRenderingContext2D(parentCtx.width(), parentCtx.height());
                if (fonts != null) ctx.fonts(fonts);
                Matrix2D cur = parentCtx.getTransform();
                ctx.setTransform(cur);
            }
            switch (tag) {
                case "g", "a", "switch" -> renderChildren(el, st, depth);
                case "svg" -> renderNestedSvg(el, st, depth);
                case "use" -> renderUse(el, st, depth);
                case "text" -> renderText(el, st, opacity);
                default -> renderShape(el, st, opacity);
            }
        } finally {
            if (parentCtx != null) {
                CanvasRenderingContext2D layer = ctx;
                ctx = parentCtx;
                ctx.save();
                ctx.resetTransform();
                ctx.globalAlpha(opacity);
                ctx.drawImage(layer, 0, 0);
                ctx.restore();
            }
            ctx.restore();
        }
    }

    private void renderNestedSvg(HtmlNode el, Style st, int depth) {
        double x = length(el.attrs.get("x"), 0, vpW);
        double y = length(el.attrs.get("y"), 0, vpH);
        SvgSize.Size size = SvgSize.of(el);
        double w = Double.isNaN(size.width()) ? vpW : size.width();
        double h = Double.isNaN(size.height()) ? vpH : size.height();
        double[] vb = SvgSize.viewBox(el);
        double pw = vpW, ph = vpH;
        if (vb != null) {
            Matrix2D m = SvgSize.viewBoxTransform(vb, vb[2], vb[3], w, h, el.attrs.get("preserveaspectratio"));
            Matrix2D at = Matrix2D.translate(x, y).multiply(m);
            ctx.transform(at.a(), at.b(), at.c(), at.d(), at.e(), at.f());
            vpW = vb[2];
            vpH = vb[3];
        } else {
            ctx.translate(x, y);
        }
        renderChildren(el, st, depth);
        vpW = pw;
        vpH = ph;
    }

    private void renderUse(HtmlNode el, Style st, int depth) {
        String href = el.attrs.getOrDefault("href", el.attrs.get("xlink:href"));
        if (href == null || !href.startsWith("#")) return;
        HtmlNode target = ids.get(href.substring(1));
        if (target == null || isAncestor(target, el)) return;
        double x = length(el.attrs.get("x"), 0, vpW);
        double y = length(el.attrs.get("y"), 0, vpH);
        ctx.translate(x, y);
        if (target.tag.equals("symbol")) {
            renderChildren(target, styleFor(target, st), depth);
        } else {
            renderElement(target, st, depth + 1);
        }
    }

    private static boolean isAncestor(HtmlNode maybeAncestor, HtmlNode of) {
        for (HtmlNode p = of; p != null; p = p.parent) if (p == maybeAncestor) return true;
        return false;
    }

    // ------------------------------------------------------------------ 図形

    private void renderShape(HtmlNode el, Style st, double opacity) {
        Path2D path = shapePath(el);
        if (path == null || path.isEmpty()) return;
        if (!"hidden".equals(st.get("visibility")) && !"collapse".equals(st.get("visibility"))) {
            drawPath(path, st, opacity);
        }
    }

    private void drawPath(Path2D path, Style st, double elementOpacity) {
        double[] bbox = path.bounds(Matrix2D.IDENTITY);
        Paint fill = paintFor(st.get("fill"), st, bbox, number(st.get("fill-opacity"), 1) * elementOpacity);
        Paint stroke = paintFor(st.get("stroke"), st, bbox, number(st.get("stroke-opacity"), 1) * elementOpacity);
        boolean evenOdd = "evenodd".equals(st.get("fill-rule"));
        if (fill != null) {
            ctx.fillStyle(fill);
            ctx.fill(path, evenOdd);
        }
        double sw = length(st.get("stroke-width"), 1, Math.sqrt((vpW * vpW + vpH * vpH) / 2), st);
        if (stroke != null && sw > 0) {
            ctx.strokeStyle(stroke);
            ctx.lineWidth(sw);
            ctx.lineCap(switch (String.valueOf(st.get("stroke-linecap"))) {
                case "round" -> "round";
                case "square" -> "square";
                default -> "butt";
            });
            ctx.lineJoin(switch (String.valueOf(st.get("stroke-linejoin"))) {
                case "round" -> "round";
                case "bevel" -> "bevel";
                default -> "miter";
            });
            ctx.miterLimit(number(st.get("stroke-miterlimit"), 4));
            double[] dash = dashArray(st.get("stroke-dasharray"));
            ctx.setLineDash(dash == null ? new double[0] : dash);
            ctx.lineDashOffset(length(st.get("stroke-dashoffset"), 0, 0));
            ctx.stroke(path);
        }
    }

    private Path2D shapePath(HtmlNode el) {
        Path2D p = new Path2D();
        double w = vpW, h = vpH;
        double diag = Math.sqrt((w * w + h * h) / 2);
        switch (el.tag) {
            case "rect" -> {
                double x = length(el.attrs.get("x"), 0, w), y = length(el.attrs.get("y"), 0, h);
                double rw = length(el.attrs.get("width"), 0, w), rh = length(el.attrs.get("height"), 0, h);
                if (!(rw > 0) || !(rh > 0)) return null;
                String rxs = el.attrs.get("rx"), rys = el.attrs.get("ry");
                double rx = rxs == null ? Double.NaN : length(rxs, 0, w);
                double ry = rys == null ? Double.NaN : length(rys, 0, h);
                if (Double.isNaN(rx) && Double.isNaN(ry)) {
                    p.rect(x, y, rw, rh);
                } else {
                    if (Double.isNaN(rx)) rx = ry;
                    if (Double.isNaN(ry)) ry = rx;
                    rx = Math.min(Math.max(0, rx), rw / 2);
                    ry = Math.min(Math.max(0, ry), rh / 2);
                    roundRect(p, x, y, rw, rh, rx, ry);
                }
            }
            case "circle" -> {
                double r = length(el.attrs.get("r"), 0, diag);
                if (!(r > 0)) return null;
                p.ellipse(length(el.attrs.get("cx"), 0, w), length(el.attrs.get("cy"), 0, h), r, r, 0, 0, Math.PI * 2, false);
                p.closePath();
            }
            case "ellipse" -> {
                double rx = length(el.attrs.get("rx"), 0, w), ry = length(el.attrs.get("ry"), 0, h);
                if (!(rx > 0) || !(ry > 0)) return null;
                p.ellipse(length(el.attrs.get("cx"), 0, w), length(el.attrs.get("cy"), 0, h), rx, ry, 0, 0, Math.PI * 2, false);
                p.closePath();
            }
            case "line" -> {
                p.moveTo(length(el.attrs.get("x1"), 0, w), length(el.attrs.get("y1"), 0, h));
                p.lineTo(length(el.attrs.get("x2"), 0, w), length(el.attrs.get("y2"), 0, h));
            }
            case "polyline", "polygon" -> {
                double[] pts = numbers(el.attrs.get("points"));
                if (pts.length < 4) return null;
                p.moveTo(pts[0], pts[1]);
                for (int i = 2; i + 1 < pts.length; i += 2) p.lineTo(pts[i], pts[i + 1]);
                if (el.tag.equals("polygon")) p.closePath();
            }
            case "path" -> {
                String d = el.attrs.get("d");
                if (d == null) return null;
                return Path2D.fromSvg(d);
            }
            default -> {
                return null;
            }
        }
        return p;
    }

    /** 楕円の角の矩形（{@code rx} と {@code ry} が違ってもよい）。4 つの四分円弧を 3 次ベジェで近似する。 */
    private static void roundRect(Path2D p, double x, double y, double w, double h, double rx, double ry) {
        if (rx <= 0 || ry <= 0) {
            p.rect(x, y, w, h);
            return;
        }
        final double k = 0.5522847498307936;
        double kx = rx * k, ky = ry * k;
        p.moveTo(x + rx, y);
        p.lineTo(x + w - rx, y);
        p.cubicTo(x + w - rx + kx, y, x + w, y + ry - ky, x + w, y + ry);
        p.lineTo(x + w, y + h - ry);
        p.cubicTo(x + w, y + h - ry + ky, x + w - rx + kx, y + h, x + w - rx, y + h);
        p.lineTo(x + rx, y + h);
        p.cubicTo(x + rx - kx, y + h, x, y + h - ry + ky, x, y + h - ry);
        p.lineTo(x, y + ry);
        p.cubicTo(x, y + ry - ky, x + rx - kx, y, x + rx, y);
        p.closePath();
    }

    private void renderText(HtmlNode el, Style st, double opacity) {
        String content = collapseSpaces(el.getTextContent());
        if (content.isEmpty() || "hidden".equals(st.get("visibility"))) return;
        double x = firstLength(el.attrs.get("x"), 0, vpW), y = firstLength(el.attrs.get("y"), 0, vpH);
        double size = length(st.get("font-size"), 16, 0);
        String weight = st.get("font-weight");
        String style = st.get("font-style");
        String family = st.get("font-family");
        ctx.font((style != null && style.startsWith("italic") ? "italic " : "")
                + (weight != null && !weight.isEmpty() ? weight + " " : "")
                + size + "px " + (family == null || family.isEmpty() ? "sans-serif" : family));
        ctx.textAlign(switch (String.valueOf(st.get("text-anchor"))) {
            case "middle" -> "center";
            case "end" -> "right";
            default -> "left";
        });
        ctx.textBaseline("alphabetic");
        double[] bbox = {x, y - size, x + size * content.length() * 0.6, y};
        Paint fill = paintFor(st.get("fill"), st, bbox, number(st.get("fill-opacity"), 1) * opacity);
        if (fill != null) {
            ctx.fillStyle(fill);
            ctx.fillText(content, x, y);
        }
    }

    private static String collapseSpaces(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ");
    }

    private static double firstLength(String list, double def, double ref) {
        if (list == null) return def;
        String[] parts = list.trim().split("[\\s,]+");
        return parts.length == 0 ? def : length(parts[0], def, ref);
    }

    // ------------------------------------------------------------------ 塗り

    /** {@code null} は塗らない。 */
    private Paint paintFor(String value, Style st, double[] bbox, double opacity) {
        if (value == null) return null;
        String v = value.trim();
        if (v.isEmpty() || v.equalsIgnoreCase("none")) return null;
        double alpha = clamp01(opacity);
        if (v.regionMatches(true, 0, "url(", 0, 4)) {
            int close = v.indexOf(')');
            if (close < 0) return null;
            String ref = v.substring(4, close).trim();
            if (ref.length() >= 2 && (ref.startsWith("\"") || ref.startsWith("'"))) ref = ref.substring(1, ref.length() - 1);
            String fallback = v.substring(close + 1).trim();
            Paint g = ref.startsWith("#") ? gradientFor(ids.get(ref.substring(1)), bbox, alpha) : null;
            if (g != null) return g;
            return fallback.isEmpty() ? null : paintFor(fallback, st, bbox, opacity);
        }
        if (v.equalsIgnoreCase("currentcolor")) v = st.get("color") == null ? "black" : st.get("color");
        if (!ColorParser.isColor(v)) return null;
        int argb = ColorParser.parseColor(v);
        return Paint.solid(withAlpha(argb, alpha));
    }

    private Paint gradientFor(HtmlNode g, double[] bbox, double alpha) {
        if (g == null || !(g.tag.equals("lineargradient") || g.tag.equals("radialgradient"))) return null;
        boolean objectBox = !"userspaceonuse".equals(lower(inherited(g, "gradientunits")));
        if (objectBox && (bbox == null || bbox[2] - bbox[0] <= 0 || bbox[3] - bbox[1] <= 0)) return null;
        List<HtmlNode> stops = stopsOf(g);
        if (stops.isEmpty()) return null;
        if (stops.size() == 1) {
            return Paint.solid(withAlpha(stopColor(stops.get(0)), alpha * stopOpacity(stops.get(0))));
        }
        double bw = bbox == null ? 1 : bbox[2] - bbox[0];
        double bh = bbox == null ? 1 : bbox[3] - bbox[1];
        double refW = objectBox ? 1 : vpW, refH = objectBox ? 1 : vpH;
        CanvasGradient grad;
        if (g.tag.equals("lineargradient")) {
            grad = CanvasGradient.linear(
                    length(inherited(g, "x1"), 0, refW), length(inherited(g, "y1"), 0, refH),
                    length(inherited(g, "x2"), objectBox ? 1 : refW, refW), length(inherited(g, "y2"), 0, refH));
        } else {
            double cx = length(inherited(g, "cx"), objectBox ? 0.5 : refW / 2, refW);
            double cy = length(inherited(g, "cy"), objectBox ? 0.5 : refH / 2, refH);
            double r = length(inherited(g, "r"), objectBox ? 0.5 : Math.min(refW, refH) / 2, Math.min(refW, refH));
            double fx = inherited(g, "fx") == null ? cx : length(inherited(g, "fx"), cx, refW);
            double fy = inherited(g, "fy") == null ? cy : length(inherited(g, "fy"), cy, refH);
            grad = CanvasGradient.radial(fx, fy, 0, cx, cy, Math.max(0, r));
        }
        double last = 0;
        for (HtmlNode s : stops) {
            double off = offset(s.attrs.get("offset"));
            off = Math.max(last, Math.min(1, off));
            last = off;
            grad.addColorStop(off, withAlpha(stopColor(s), alpha * stopOpacity(s)));
        }
        grad.spread(switch (String.valueOf(lower(inherited(g, "spreadmethod")))) {
            case "reflect" -> CanvasGradient.Spread.REFLECT;
            case "repeat" -> CanvasGradient.Spread.REPEAT;
            default -> CanvasGradient.Spread.PAD;
        });
        Matrix2D m = Matrix2D.IDENTITY;
        if (objectBox) m = Matrix2D.translate(bbox[0], bbox[1]).multiply(Matrix2D.scale(bw, bh));
        Matrix2D gt = parseTransform(inherited(g, "gradienttransform"));
        if (gt != null) m = m.multiply(gt);
        grad.transform(m);
        return grad;
    }

    private static double offset(String s) {
        if (s == null) return 0;
        String t = s.trim();
        if (t.endsWith("%")) return number(t.substring(0, t.length() - 1), 0) / 100;
        return number(t, 0);
    }

    private int stopColor(HtmlNode stop) {
        Style st = styleFor(stop, new Style());
        String c = st.local("stop-color");
        if (c == null) return 0xFF000000;
        if (c.equalsIgnoreCase("currentcolor")) c = st.get("color") == null ? "black" : st.get("color");
        return ColorParser.isColor(c) ? ColorParser.parseColor(c) : 0xFF000000;
    }

    private double stopOpacity(HtmlNode stop) {
        return clamp01(number(styleFor(stop, new Style()).local("stop-opacity"), 1));
    }

    /** {@code href} をたどって stop を持つ最初のグラデーションの stop を返す。 */
    private List<HtmlNode> stopsOf(HtmlNode g) {
        HtmlNode cur = g;
        for (int i = 0; i < 8 && cur != null; i++) {
            List<HtmlNode> out = new ArrayList<>();
            for (HtmlNode c : cur.children) if (c.nodeType == NodeType.ELEMENT && c.tag.equals("stop")) out.add(c);
            if (!out.isEmpty()) return out;
            cur = hrefTarget(cur);
        }
        return List.of();
    }

    private HtmlNode hrefTarget(HtmlNode n) {
        String href = n.attrs.getOrDefault("href", n.attrs.get("xlink:href"));
        return href != null && href.startsWith("#") ? ids.get(href.substring(1)) : null;
    }

    /** グラデーションの属性は {@code href} の先から継承する。 */
    private String inherited(HtmlNode g, String attr) {
        HtmlNode cur = g;
        for (int i = 0; i < 8 && cur != null; i++) {
            String v = cur.attrs.get(attr);
            if (v != null) return v;
            cur = hrefTarget(cur);
        }
        return null;
    }

    private static String lower(String s) {
        return s == null ? null : s.trim().toLowerCase(Locale.ROOT);
    }

    private static int withAlpha(int argb, double alpha) {
        int a = (int) Math.round(((argb >>> 24) & 0xFF) * clamp01(alpha));
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    // ------------------------------------------------------------------ クリップ

    private void applyClip(HtmlNode el, Style st) {
        String ref = st.local("clip-path");
        if (ref == null || !ref.regionMatches(true, 0, "url(", 0, 4)) return;
        int close = ref.indexOf(')');
        if (close < 0) return;
        String id = ref.substring(4, close).trim().replace("\"", "").replace("'", "");
        HtmlNode cp = id.startsWith("#") ? ids.get(id.substring(1)) : null;
        if (cp == null || !cp.tag.equals("clippath")) return;
        Path2D clip = new Path2D();
        Matrix2D base = Matrix2D.IDENTITY;
        if ("objectboundingbox".equals(lower(cp.attrs.get("clippathunits")))) {
            Path2D own = shapePath(el);
            double[] bb = own == null ? null : own.bounds(Matrix2D.IDENTITY);
            if (bb == null) return;
            base = Matrix2D.translate(bb[0], bb[1]).multiply(Matrix2D.scale(bb[2] - bb[0], bb[3] - bb[1]));
        }
        Matrix2D cpt = parseTransform(cp.attrs.get("transform"));
        if (cpt != null) base = base.multiply(cpt);
        for (HtmlNode c : cp.children) {
            if (c.nodeType != NodeType.ELEMENT || !SHAPES.contains(c.tag)) continue;
            Path2D sp = shapePath(c);
            if (sp == null) continue;
            Matrix2D ct = parseTransform(c.attrs.get("transform"));
            clip.append(sp, ct == null ? base : base.multiply(ct));
        }
        ctx.clip(clip, "evenodd".equals(st.local("clip-rule")));
    }

    // ------------------------------------------------------------------ スタイル

    /** 要素の宣言値（提示属性 + style 属性）と、継承する値。 */
    static final class Style {
        private final Map<String, String> own = new HashMap<>();
        private final Map<String, String> inheritedValues = new HashMap<>();

        void set(String k, String v) {
            own.put(k, v);
        }

        String local(String k) {
            return own.get(k);
        }

        String get(String k) {
            String v = own.get(k);
            return v != null ? v : inheritedValues.get(k);
        }
    }

    private Style styleFor(HtmlNode el, Style parent) {
        Style s = new Style();
        for (String k : INHERITED) {
            String v = parent.get(k);
            if (v != null) s.inheritedValues.put(k, v);
        }
        for (String k : PRESENTATION) {
            String v = el.attrs.get(k);
            if (v != null) s.own.put(k, v.trim());
        }
        String style = el.attrs.get("style");
        if (style != null) {
            for (String decl : style.split(";")) {
                int colon = decl.indexOf(':');
                if (colon <= 0) continue;
                String k = decl.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String v = decl.substring(colon + 1).trim().replace("!important", "").trim();
                if (PRESENTATION.contains(k) && !v.isEmpty()) s.own.put(k, v);
            }
        }
        // currentColor は color の確定後に引く（color 自体が currentColor / inherit のときは親のまま）
        String color = s.own.get("color");
        if (color != null && (color.equalsIgnoreCase("currentcolor") || color.equalsIgnoreCase("inherit"))) s.own.remove("color");
        for (Map.Entry<String, String> e : s.own.entrySet()) {
            if (e.getValue().equalsIgnoreCase("inherit") && !e.getKey().equals("color")) {
                String pv = parent.get(e.getKey());
                if (pv != null) e.setValue(pv);
            }
        }
        return s;
    }

    // ------------------------------------------------------------------ 数値・変換

    static double clamp01(double v) {
        return Double.isNaN(v) ? 1 : Math.max(0, Math.min(1, v));
    }

    static double number(String s, double def) {
        if (s == null) return def;
        try {
            double v = Double.parseDouble(s.trim());
            return Double.isFinite(v) ? v : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static double[] numbers(String s) {
        if (s == null) return new double[0];
        String[] parts = s.trim().split("[\\s,]+");
        double[] out = new double[parts.length];
        int n = 0;
        for (String p : parts) {
            if (p.isEmpty()) continue;
            try {
                out[n++] = Double.parseDouble(p);
            } catch (NumberFormatException e) {
                break;
            }
        }
        return java.util.Arrays.copyOf(out, n);
    }

    private static double length(String s, double def, double ref, Style st) {
        return SvgSize.length(s, def, ref, st == null ? 16 : length(st.get("font-size"), 16, 0));
    }

    private static double length(String s, double def, double ref) {
        return SvgSize.length(s, def, ref, 16);
    }

    private static double[] dashArray(String s) {
        if (s == null || s.trim().equalsIgnoreCase("none")) return null;
        double[] v = numbers(s.replace("px", ""));
        if (v.length == 0) return null;
        double sum = 0;
        for (double d : v) {
            if (d < 0) return null;
            sum += d;
        }
        return sum > 0 ? v : null;
    }

    /** {@code transform} 属性を行列にする。誤りがあれば恒等変換。 */
    static Matrix2D parseTransform(String s) {
        if (s == null || s.isBlank()) return null;
        Matrix2D m = Matrix2D.IDENTITY;
        int i = 0;
        int n = s.length();
        while (i < n) {
            while (i < n && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == ',')) i++;
            int nameStart = i;
            while (i < n && Character.isLetter(s.charAt(i))) i++;
            if (nameStart == i) break;
            String name = s.substring(nameStart, i);
            while (i < n && Character.isWhitespace(s.charAt(i))) i++;
            if (i >= n || s.charAt(i) != '(') return Matrix2D.IDENTITY;
            int close = s.indexOf(')', i);
            if (close < 0) return Matrix2D.IDENTITY;
            double[] a = numbers(s.substring(i + 1, close));
            i = close + 1;
            Matrix2D t;
            switch (name) {
                case "matrix" -> {
                    if (a.length != 6) return Matrix2D.IDENTITY;
                    t = new Matrix2D(a[0], a[1], a[2], a[3], a[4], a[5]);
                }
                case "translate" -> {
                    if (a.length < 1) return Matrix2D.IDENTITY;
                    t = Matrix2D.translate(a[0], a.length > 1 ? a[1] : 0);
                }
                case "scale" -> {
                    if (a.length < 1) return Matrix2D.IDENTITY;
                    t = Matrix2D.scale(a[0], a.length > 1 ? a[1] : a[0]);
                }
                case "rotate" -> {
                    if (a.length != 1 && a.length != 3) return Matrix2D.IDENTITY;
                    Matrix2D r = Matrix2D.rotate(Math.toRadians(a[0]));
                    t = a.length == 3
                            ? Matrix2D.translate(a[1], a[2]).multiply(r).multiply(Matrix2D.translate(-a[1], -a[2]))
                            : r;
                }
                case "skewX" -> {
                    if (a.length != 1) return Matrix2D.IDENTITY;
                    t = new Matrix2D(1, 0, Math.tan(Math.toRadians(a[0])), 1, 0, 0);
                }
                case "skewY" -> {
                    if (a.length != 1) return Matrix2D.IDENTITY;
                    t = new Matrix2D(1, Math.tan(Math.toRadians(a[0])), 0, 1, 0, 0);
                }
                default -> {
                    return Matrix2D.IDENTITY;
                }
            }
            m = m.multiply(t);
        }
        return m;
    }
}
