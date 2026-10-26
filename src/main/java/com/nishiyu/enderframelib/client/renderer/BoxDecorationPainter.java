package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.css.ColorParser;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.paint.model.AnimationSpec;
import com.nishiyu.enderframelib.core.paint.model.ClipRect;
import com.nishiyu.enderframelib.core.paint.model.Corners;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.ImageFitMode;
import com.nishiyu.enderframelib.core.raster.svg.SvgImage;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;

import java.util.List;
import com.nishiyu.enderframelib.core.paint.model.ShaderGradient;
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.paint.model.*;
import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;
import com.nishiyu.enderframelib.core.css.model.BorderStyle;
import static com.nishiyu.enderframelib.client.renderer.PaintSupport.addElement;
import static com.nishiyu.enderframelib.client.renderer.PaintSupport.growRadius;
import static com.nishiyu.enderframelib.client.renderer.PaintSupport.intersectClip;
import java.util.concurrent.atomic.AtomicInteger;

/** 箱の飾り（背景・枠線・影・輪郭線・インラインの断片）を描画項目にする。 */
final class BoxDecorationPainter {
    private final int rootW;
    private final int rootH;
    private final AtomicInteger fragIds;

    int rootW() {
        return rootW;
    }

    int rootH() {
        return rootH;
    }

    BoxDecorationPainter(int rootW, int rootH, AtomicInteger fragIds) {
        this.rootW = rootW;
        this.rootH = rootH;
        this.fragIds = fragIds;
    }

    /** 影の広がり {@code g} だけ膨らませた角（丸みの無い角は角のまま。§7.1.1）。 */
    private static Corners grow(Corners c, float g) {
        int[] h = {growRadius(c.topLeft(), g), growRadius(c.topRight(), g), growRadius(c.bottomRight(), g), growRadius(c.bottomLeft(), g)};
        int[] v = {growRadius(c.topLeftV(), g), growRadius(c.topRightV(), g), growRadius(c.bottomRightV(), g), growRadius(c.bottomLeftV(), g)};
        return c.isElliptical() ? Corners.ofEllipses(h, v) : new Corners(h[0], h[1], h[2], h[3]);
    }

    static final String[] BORDER_DIRECTIONS = {"top", "right", "bottom", "left"};

    /**
     * 枠線の幅・色・線種。幅はレイアウトが決めた値（{@code box.bT} など）をそのまま使う（軸 1・3）。
     * 線種が none / hidden の辺は算出値の段で幅 0 になっている。色が無ければ {@code currentcolor}。
     *
     * 何が壊れていたか: 以前は描画が {@code border-<辺>} の文字列から幅と色を読み直しており、
     * 色を書かない {@code border: 1px solid} の枠が透明になり、線種は読まずに常に実線だった。
     */
    BorderSpec parseBorderSpec(LayoutBox box, int[] radii, Corners corners, int textColor) {
        float[] widths = {box.bT, box.bR, box.bB, box.bL};
        int[] colors = new int[4];
        String[] styles = new String[4];
        boolean hasBorder = false;
        for (int i = 0; i < 4; i++) {
            String dir = BORDER_DIRECTIONS[i];
            String colorProp = box.style.get("border-" + dir + "-color");
            colors[i] = colorProp == null ? textColor : ColorParser.parseColor(colorProp);
            styles[i] = box.style.keyword("border-" + dir + "-style", "solid");
            if (widths[i] > 0) hasBorder = true;
        }
        float bwTop = widths[0], bwRight = widths[1], bwBottom = widths[2], bwLeft = widths[3];
        int bcTop = colors[0], bcRight = colors[1], bcBottom = colors[2], bcLeft = colors[3];

        // 4 辺が同じ線種（破線か点線）・太さ・色なら、角の円弧に沿って 1 本の経路として割り付ける（Blink と同じ）。
        // LIMITATION(制約): 辺ごとに線種・太さ・色が違う角丸の枠線は、線種を読まず実線で描き、角の中で隣り合う辺の色が
        // 切り替わる境目は斜めの線ではなく辺の太さの内側か外側かで決める。README の "Not supported" に対応。
        int dashKind = BorderPath.SOLID;
        boolean anyRadius = radii[0] > 0 || radii[1] > 0 || radii[2] > 0 || radii[3] > 0;
        if (anyRadius && bwTop > 0 && bwTop == bwRight && bwRight == bwBottom && bwBottom == bwLeft
                && bcTop == bcRight && bcRight == bcBottom && bcBottom == bcLeft
                && styles[0].equals(styles[1]) && styles[1].equals(styles[2]) && styles[2].equals(styles[3])) {
            dashKind = "dashed".equals(styles[0]) ? BorderPath.DASHED : "dotted".equals(styles[0]) ? BorderPath.DOTTED : BorderPath.SOLID;
        }

        boolean isCircle = dashKind == BorderPath.SOLID
                && radii[0] >= (box.w / 2) - 1 && radii[1] >= (box.w / 2) - 1
                && radii[2] >= (box.w / 2) - 1 && radii[3] >= (box.w / 2) - 1
                && box.w > 0 && box.h > 0 && Math.abs(box.w - box.h) <= 2;
        boolean useRoundedBorder = anyRadius && hasBorder && !isCircle;

        Corners innerRadii;
        if (corners.isElliptical()) {
            // 内側の角は外側の半径から、その軸に接する辺の太さを引いた楕円（CSS Backgrounds 3 §5.2）。どちらかが 0 なら直角
            int[] ih = {Math.round(corners.topLeft() - bwLeft), Math.round(corners.topRight() - bwRight),
                    Math.round(corners.bottomRight() - bwRight), Math.round(corners.bottomLeft() - bwLeft)};
            int[] iv = {Math.round(corners.topLeftV() - bwTop), Math.round(corners.topRightV() - bwTop),
                    Math.round(corners.bottomRightV() - bwBottom), Math.round(corners.bottomLeftV() - bwBottom)};
            for (int i = 0; i < 4; i++) {
                ih[i] = Math.max(0, ih[i]);
                iv[i] = Math.max(0, iv[i]);
            }
            innerRadii = Corners.ofEllipses(ih, iv);
        } else {
            innerRadii = new Corners(
                    Math.max(0, Math.round(radii[0] - Math.max(bwTop, bwLeft))),
                    Math.max(0, Math.round(radii[1] - Math.max(bwTop, bwRight))),
                    Math.max(0, Math.round(radii[2] - Math.max(bwBottom, bwRight))),
                    Math.max(0, Math.round(radii[3] - Math.max(bwBottom, bwLeft)))
            );
        }

        return new BorderSpec(hasBorder, isCircle, useRoundedBorder,
                bwTop, bwRight, bwBottom, bwLeft, bcTop, bcRight, bcBottom, bcLeft, styles, innerRadii, dashKind);
    }

    /**
     * インラインボックスを行ごとの断片で描く。各断片は背景と上下の枠線を持ち、左の枠線と角丸は箱の最初の断片
     * （{@link LayoutBox.InlineFragment#startEdge}）、右は最後の断片（{@code endEdge}）だけが持つ
     * （{@code box-decoration-break: slice}）。背景の部品 {@code <id>_frag_<n>} がヒットテストを受ける。
     *
     * 何が壊れていたか: 以前は断片に背景色だけを塗って返していたので、{@code <code>} や {@code <kbd>} の枠線と
     * 角丸が描かれなかった。断片も語ごとにあり、背景が語の数だけ重なっていた。
     */
    void drawInlineFragments(List<DisplayItem> elements, LayoutBox box, String id, int color, int bgColor, int[] rawRadii,
                                     ClipRect clip, float fontHVal, float ox, float oy, int itemCount, boolean interactive,
                                     ItemProps partsEvents, ItemProps passiveBorderEvents,
                                     int transDur, AnimationSpec colorBgAnim) {
        BorderSpec border = parseBorderSpec(box, rawRadii, Corners.fromArray(rawRadii), color);
        String[] styles = border.styles();
        int[] colors = {border.bcTop(), border.bcRight(), border.bcBottom(), border.bcLeft()};
        for (LayoutBox.InlineFragment frag : box.inlineFragments) {
            if (frag.isText()) continue;
            int[] r = {
                    frag.startEdge ? rawRadii[0] : 0, frag.endEdge ? rawRadii[1] : 0,
                    frag.endEdge ? rawRadii[2] : 0, frag.startEdge ? rawRadii[3] : 0
            };
            Corners radii = Corners.fromArray(r);
            RenderMeta meta = new RenderMeta(clip, radii, null, fontHVal, 0f, box.lineHeightPx, ox, oy, itemCount, interactive);
            addElement(elements, id, id + "_frag_" + (fragIds.getAndIncrement()), ElementType.RECT, frag.x, frag.y, frag.w, frag.h,
                    "", color, bgColor, partsEvents, r[0], 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, meta);

            float[] widths = {box.bT, frag.endEdge ? box.bR : 0, box.bB, frag.startEdge ? box.bL : 0};
            if (widths[0] <= 0 && widths[1] <= 0 && widths[2] <= 0 && widths[3] <= 0) continue;
            if (r[0] > 0 || r[1] > 0 || r[2] > 0 || r[3] > 0) {
                RoundedBorderSpec spec = new RoundedBorderSpec(widths[0], widths[1], widths[2], widths[3],
                        colors[0], colors[1], colors[2], colors[3]);
                RenderMeta ringMeta = new RenderMeta(clip, radii, null,
                        fontHVal, 0f, 0f, ox, oy, 1, false, spec);
                addElement(elements, id, id + "_b_round_" + (fragIds.getAndIncrement()), ElementType.ROUNDED_BORDER, frag.x, frag.y, frag.w, frag.h,
                        "", 0, 0x00000000, passiveBorderEvents, 0, 1f, 0, 0, 1f, 1f, 0, transDur, AnimationSpec.NONE, ringMeta);
            } else {
                drawLines(elements, id, "_b_", frag.x, frag.y, frag.w, frag.h, widths, styles, colors, 0,
                        passiveBorderEvents, meta, transDur, true);
            }
        }
    }

    /**
     * 輪郭線。枠線の外側に {@code outline-offset} だけ離して描き、レイアウトには影響しない。
     * 付録 E は輪郭線を最後の層に描いてもよいとしているが、箱自身と一緒に描くのも認めている（こちらを取る）。
     *
     * LIMITATION(制約): 輪郭線は角丸に沿わず、インラインボックスでは行ごとの断片に描かない。
     * README の "Not supported" に対応。
     */
    void drawOutline(List<DisplayItem> elements, LayoutBox box, String id, int color,
                             ItemProps events, RenderMeta meta, int transDur) {
        String lineStyle = box.style.keywordOf("outline-style", BorderStyle.class).cssName();
        if ("none".equals(lineStyle) || "hidden".equals(lineStyle)) return;
        float w = box.style.length("outline-width", box.w, rootW, rootH);
        if (w <= 0) return;
        float offset = box.style.length("outline-offset", box.w, rootW, rootH);
        String colorProp = box.style.get("outline-color");
        int c = colorProp == null || "invert".equalsIgnoreCase(colorProp.trim()) ? color : ColorParser.parseColor(colorProp);
        if ("auto".equals(lineStyle)) lineStyle = "solid";

        float x = box.x - offset - w, y = box.y - offset - w;
        float ow = box.w + 2 * (offset + w), oh = box.h + 2 * (offset + w);
        if (ow <= 0 || oh <= 0) return;
        String[] styles = {lineStyle, lineStyle, lineStyle, lineStyle};
        int[] colors = {c, c, c, c};
        float[] widths = {w, w, w, w};
        drawLines(elements, id, "_o_", x, y, ow, oh, widths, styles, colors, 0, events, meta, transDur);
    }

    /**
     * 段組（multicol）の列の間の線（{@code column-rule}）。線の位置は {@link LayoutBox#columnRules}
     * （レイアウトが列の間の中央に置いた矩形）で、箱の背景の上・文字の下に出る。
     */
    void drawColumnRules(List<DisplayItem> elements, LayoutBox box, String id, int color,
                         ItemProps events, RenderMeta meta, int transDur) {
        float[][] rules = box.columnRules;
        if (rules == null) return;
        String lineStyle = box.style.keywordOf("column-rule-style", BorderStyle.class).cssName();
        if ("none".equals(lineStyle) || "hidden".equals(lineStyle)) return;
        String colorProp = box.style.get("column-rule-color");
        int c = colorProp == null ? color : ColorParser.parseColor(colorProp);
        String[] styles = {lineStyle, lineStyle, lineStyle, lineStyle};
        int[] colors = {c, c, c, c};
        for (int i = 0; i < rules.length; i++) {
            float[] r = rules[i];
            if (r[2] <= 0 || r[3] <= 0) continue;
            drawLines(elements, id, "_cr" + i + "_", r[0], r[1], r[2], r[3], new float[]{0, 0, 0, r[2]}, styles, colors, 0,
                    events, meta, transDur);
        }
    }

    /**
     * 外側の影（{@code inset} でないもの）。リストの先頭が最前面なので後ろから描き、箱の背景より下に置く。
     * 影の形は箱をオフセットだけずらして広がりだけ膨らませた角丸矩形で、ぼかしは層の重ね塗りで近似する
     * （{@link BoxDecorations#shadowLayers}）。部品の id は {@code <id>_shadow_<番号>}。
     *
     * LIMITATION(制約): ぼかしのない外側の影は箱の下全体に塗るので、背景が透明・半透明の箱では箱の内側にも影が見える
     * （ブラウザは影を枠線ボックスの外だけに描く）。ぼかした影は箱の形を穴として抜く。README の "Not supported" に対応。
     */
    void drawOuterShadows(List<DisplayItem> elements, LayoutBox box, String id, List<BoxDecorations.Shadow> shadows,
                                  Corners radii, ClipRect clip, float fontHVal, float ox, float oy) {
        ItemProps events = ItemProps.parent(id);
        int n = 0;
        for (int i = shadows.size() - 1; i >= 0; i--) {
            BoxDecorations.Shadow s = shadows.get(i);
            if (s.inset()) continue;
            if (s.blur() > 0) {
                // ぼかしは 1 枚の板に任せ、シェーダーが辺からの距離で滑らかに減衰させる（層の重ね塗りは段が見えた）。
                // 板は影の形から「ぼかしが消える所まで」広げた矩形で、形の角丸は広がりの分だけ変える
                float reach = BackgroundRenderer.SHADOW_REACH * s.blur();
                float grow = s.spread() + reach;
                float w = box.w + 2 * grow, h = box.h + 2 * grow;
                if (w <= 0 || h <= 0) continue;
                Corners r = grow(radii, s.spread());
                ItemProps blurEvents = events.copy();
                blurEvents.shadowBlur(s.blur());
                // 影は箱の外だけに出す（背景が透明・半透明の箱の内側に透けないよう、箱の形を穴として抜く）
                blurEvents.shadowShape(new RenderHints.ShadowShape(false, box.x, box.y, box.x + box.w, box.y + box.h,
                        radii.topLeft(), radii.topRight(), radii.bottomRight(), radii.bottomLeft(),
                        radii.topLeftV(), radii.topRightV(), radii.bottomRightV(), radii.bottomLeftV()));
                addElement(elements, id, id + "_shadow_" + (n++), ElementType.RECT,
                        box.x + s.offsetX() - grow, box.y + s.offsetY() - grow, w, h, "", 0, s.color(), blurEvents,
                        r.topLeft(), 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE,
                        new RenderMeta(clip, r, null, fontHVal, ox, oy, 1, false));
                continue;
            }
            for (BoxDecorations.ShadowLayer layer : BoxDecorations.shadowLayers(s)) {
                float grow = s.spread() + layer.grow();
                float w = box.w + 2 * grow, h = box.h + 2 * grow;
                if (w <= 0 || h <= 0) continue;
                // 広がりは角の丸みも同じだけ変える（丸みの無い角は角のまま。§7.1.1）
                Corners r = grow(radii, grow);
                addElement(elements, id, id + "_shadow_" + (n++), ElementType.RECT,
                        box.x + s.offsetX() - grow, box.y + s.offsetY() - grow, w, h, "", 0, layer.color(), events,
                        r.topLeft(), 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE,
                        new RenderMeta(clip, r, null, fontHVal, ox, oy, 1, false));
            }
        }
    }

    /**
     * 内側の影。背景（色と画像）の上、枠線の下にパディングボックスの内側へ描く。影の無い「穴」は
     * パディングボックスをオフセットだけずらして広がりだけ縮めた角丸矩形（角丸はパディングボックスの内側の角丸から広がりだけ縮める）で、
     * パディングボックスのうち穴の外を、穴の辺からの距離で減衰するガウス近似の 1 枚で塗る。パディングボックスの角丸で切る。
     * ぼかしが 0 でも同じ経路で、辺は 0.5px だけにじませてアンチエイリアスにする。
     */
    void drawInsetShadows(List<DisplayItem> elements, LayoutBox box, String id, List<BoxDecorations.Shadow> shadows,
                                  BorderSpec border, ClipRect clip, float fontHVal, float ox, float oy) {
        BoxDecorations.Rect pad = BoxDecorations.boxArea(box, "padding-box");
        if (pad.w() <= 0 || pad.h() <= 0) return;
        ClipRect padClip = intersectClip(clip, pad, border.innerRadii(), id);
        ItemProps events = ItemProps.parent(id);
        Corners inner = border.innerRadii() != null ? border.innerRadii() : Corners.ZERO;
        int n = 0;
        for (int i = shadows.size() - 1; i >= 0; i--) {
            BoxDecorations.Shadow s = shadows.get(i);
            if (!s.inset()) continue;
            float px0 = pad.x(), py0 = pad.y(), px1 = pad.x() + pad.w(), py1 = pad.y() + pad.h();
            float hx0 = Math.clamp(px0 + s.offsetX() + s.spread(), px0, px1), hx1 = Math.clamp(px1 + s.offsetX() - s.spread(), px0, px1);
            float hy0 = Math.clamp(py0 + s.offsetY() + s.spread(), py0, py1), hy1 = Math.clamp(py1 + s.offsetY() - s.spread(), py0, py1);
            if (hx1 <= hx0 || hy1 <= hy0) {
                hx0 = hx1 = px0;
                hy0 = hy1 = py0;
            }
            float limitX = (hx1 - hx0) / 2f, limitY = (hy1 - hy0) / 2f, limit = Math.min(limitX, limitY);
            ItemProps shadowEvents = events.copy();
            shadowEvents.shadowBlur(Math.max(s.blur(), 0.5f));
            if (inner.isElliptical()) {
                // 楕円の角は軸ごとに、広がりの分だけ縮める。どちらかが 0 になれば直角
                float[] ih = {inner.topLeft(), inner.topRight(), inner.bottomRight(), inner.bottomLeft()};
                float[] iv = {inner.topLeftV(), inner.topRightV(), inner.bottomRightV(), inner.bottomLeftV()};
                float[] hh = new float[4], hv = new float[4];
                for (int c = 0; c < 4; c++) {
                    hh[c] = Math.min(Math.max(0, ih[c] - s.spread()), limitX);
                    hv[c] = Math.min(Math.max(0, iv[c] - s.spread()), limitY);
                    if (hh[c] <= 0 || hv[c] <= 0) hh[c] = hv[c] = 0;
                }
                shadowEvents.shadowShape(new RenderHints.ShadowShape(true, hx0, hy0, hx1, hy1,
                        hh[0], hh[1], hh[2], hh[3], hv[0], hv[1], hv[2], hv[3]));
            } else {
                shadowEvents.shadowShape(new RenderHints.ShadowShape(true, hx0, hy0, hx1, hy1,
                        Math.min(Math.max(0, inner.topLeft() - s.spread()), limit), Math.min(Math.max(0, inner.topRight() - s.spread()), limit),
                        Math.min(Math.max(0, inner.bottomRight() - s.spread()), limit), Math.min(Math.max(0, inner.bottomLeft() - s.spread()), limit)));
            }
            addElement(elements, id, id + "_shadow_in" + (n++), ElementType.RECT, px0, py0, px1 - px0, py1 - py0, "", 0,
                    s.color(), shadowEvents, 0, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE,
                    new RenderMeta(padClip, null, null, fontHVal, ox, oy, 1, false));
        }
    }

    void drawDefaultBlockElement(List<DisplayItem> elements, LayoutBox box, String id, int color, int bgColor, int fallbackRadius,
                                         Corners radii, ClipRect normalizedClip, float fontHVal, float ox, float oy,
                                         ItemProps partsEvents, ItemProps passiveBorderEvents,
                                         BorderSpec border, RenderMeta baseMeta, RenderMeta innerMeta, float rOff,
                                         int transDur, AnimationSpec colorBgAnim, List<BoxDecorations.Shadow> shadows) {
        ElementType bgType = ElementType.RECT;
        if ("button".equalsIgnoreCase(box.node.tag)) {
            bgType = ElementType.BUTTON;
        } else if ("a".equalsIgnoreCase(box.node.tag)) {
            bgType = ElementType.A;
        }
        boolean isInteractiveBox = bgType != ElementType.RECT;

        if (border.isCircle() && border.hasBorder()) {
            int thickness = border.maxThickness();
            String bData = thickness + ":" + border.bcTop() + "," + border.bcRight() + "," + border.bcBottom() + "," + border.bcLeft();
            CircleBorderSpec cbSpec = new CircleBorderSpec(thickness, border.bcTop(), border.bcRight(), border.bcBottom(), border.bcLeft());
            addElement(elements, id, id + "_bg", bgType, box.x, box.y, box.w, box.h, "", color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, baseMeta);
            drawBackgroundImages(elements, box, id, normalizedClip, radii, border, passiveBorderEvents, fontHVal, ox, oy);
            drawInsetShadows(elements, box, id, shadows, border, normalizedClip, fontHVal, ox, oy);
            addElement(elements, id, id + "_cb", ElementType.CIRCLE_BORDER, box.x, box.y, box.w, box.h, bData, 0, 0, passiveBorderEvents, 0, 1f, 0, 0, 1f, 1f, 0, transDur, AnimationSpec.NONE,
                    new RenderMeta(normalizedClip, radii, cbSpec, fontHVal, ox, oy, 1, false));
        } else {
            // background-clip が border-box でなければ、色は箱の内側だけに塗る。_bg はヒットテストと
            // 状態の受け口なので箱いっぱいのまま残し、色だけを切った矩形に移す
            String colorClip = BoxDecorations.colorClip(box.style);
            int bgFill = bgColor;
            if (bgColor != 0x00000000 && !"border-box".equals(colorClip)) {
                bgFill = 0x00000000;
                BoxDecorations.Rect area = BoxDecorations.boxArea(box, colorClip);
                Corners areaRadii = "padding-box".equals(colorClip) ? border.innerRadii() : null;
                addElement(elements, id, id + "_bgl" + BoxDecorations.layers(box.style).size() + "_0", ElementType.RECT,
                        area.x(), area.y(), area.w(), area.h(), "", 0, bgColor, passiveBorderEvents, 0, 1f, 0, 0, 1f, 1f, 0, transDur, AnimationSpec.NONE,
                        new RenderMeta(normalizedClip, areaRadii, null, fontHVal, ox, oy, 1, false));
            }
            // 角丸の箱でも背景は枠線ボックスいっぱい（枠線の下まで）に塗り、枠線をその上に重ねる（付録 E）
            addElement(elements, id, id + "_bg", bgType, box.x, box.y, box.w, box.h, "", color, bgFill, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, baseMeta);
            drawBackgroundImages(elements, box, id, normalizedClip, radii, border, passiveBorderEvents, fontHVal, ox, oy);
            drawInsetShadows(elements, box, id, shadows, border, normalizedClip, fontHVal, ox, oy);
            drawBorder(elements, id, box, rOff, passiveBorderEvents, border, baseMeta, transDur);
        }

        if (box.node.attrs.containsKey("data-ghost-item")) {
            String ghostItem = box.node.attrs.get("data-ghost-item").trim();
            if (!ghostItem.isEmpty() && !"minecraft:air".equals(ghostItem)) {
                float iconX = box.x + (box.w - 16) / 2;
                float iconY = box.y + (box.h - 16) / 2;
                addElement(elements, id, id + "_ghost_img", ElementType.IMAGE, ImageFitMode.FILL, iconX, iconY, 16, 16, "item:" + ghostItem, color, 0, partsEvents, 0, 0.85f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, innerMeta);
            }
        }
    }

    /**
     * 背景の画像レイヤー（{@code url()} とグラデーション）。リストの末尾がいちばん下なので後ろから描く。
     * 各レイヤーは {@code background-origin} の箱を基準にタイルを並べ、{@code background-clip} の箱で切る。
     *
     * 何が壊れていたか: 以前は背景を色 1 つと 2 色のグラデーション 1 つしか持てず、
     * {@code background-image: url()} は描かれず、{@code background-size / position / repeat / clip / origin} も
     * 読んでいなかった。
     *
     * グラデーションは {@link Gradients#shaderGradients} が区間に分け、区間ごとに 1 部品にする。
     * タイルと区間は描画領域の角丸のクリップで切るので、小さいタイルも角の丸みに沿う。
     *
     * LIMITATION(制約): {@code background-attachment} は常に scroll、インラインボックスの断片には画像を描かない。
     * README の "Not supported" に対応。
     */
    void drawBackgroundImages(List<DisplayItem> elements, LayoutBox box, String id, ClipRect clip, Corners radii,
                                      BorderSpec border, ItemProps events, float fontHVal, float ox, float oy) {
        drawImageLayers(elements, box, id, BoxDecorations.layers(box.style), "_bgl", clip, radii, border, events, fontHVal, ox, oy);
    }

    /**
     * {@code mask-image} のレイヤー。背景の画像と同じ描き方（位置・大きさ・繰り返し・箱）で、部品の id は
     * {@code <id>_mask<番号>_<タイル>}。レイヤーの終わりに足し、描画側がマスク用の描画先に描く（{@link LayerSpec#maskFrom}）。
     * マスクの部品はヒットテストを素通りさせる（子孫のクリックを覆わない）。
     *
     * LIMITATION(制約): {@code mask-composite} は {@code add} だけ（複数のレイヤーは重ねる）。{@code mask-border} と
     * {@code mask-type} は読まない。README の "Not supported" に対応。
     */
    void drawMaskLayers(List<DisplayItem> elements, LayoutBox box, String id, ClipRect clip, Corners radii,
                                BorderSpec border, float fontHVal, float ox, float oy) {
        ItemProps events = ItemProps.parent(id);
        events.pointerEventsNone();
        drawImageLayers(elements, box, id, BoxDecorations.maskLayers(box.style), "_mask", clip, radii, border, events, fontHVal, ox, oy);
    }

    /** {@code currentColor} になる色（要素の {@code color}）。 */
    private static int currentColor(LayoutBox box) {
        return ColorParser.parseColor(box.style.getOrDefault(CssProperty.COLOR, "black"));
    }

    private void drawImageLayers(List<DisplayItem> elements, LayoutBox box, String id, List<BoxDecorations.BackgroundLayer> layers,
                                 String idInfix, ClipRect clip, Corners radii, BorderSpec border, ItemProps events,
                                 float fontHVal, float ox, float oy) {
        for (int i = layers.size() - 1; i >= 0; i--) {
            BoxDecorations.BackgroundLayer layer = layers.get(i);
            String url = layer.url();
            Gradients.Gradient gradient = layer.isGradient() ? Gradients.parse(layer.image()) : null;
            if (url == null && gradient == null) continue;

            int[] intrinsic = url != null && UiDocumentCompiler.intrinsicSizeResolver != null ? UiDocumentCompiler.intrinsicSizeResolver.getSize(url) : null;
            if (intrinsic != null && layer.density() != 1.0) {
                // image-set() の解像度（2x なら画像の画素数の半分が CSS px の大きさ）
                intrinsic = new int[]{(int) Math.max(1, Math.round(intrinsic[0] / layer.density())),
                        (int) Math.max(1, Math.round(intrinsic[1] / layer.density()))};
            }
            SvgImage svgLayer = url != null && SvgImage.looksLikeSvg(url) ? SvgSources.get(url) : null;
            if (svgLayer != null && intrinsic == null) {
                intrinsic = new int[]{(int) Math.max(1, Math.round(svgLayer.width() / layer.density())),
                        (int) Math.max(1, Math.round(svgLayer.height() / layer.density()))};
            }
            BoxDecorations.Rect paint = BoxDecorations.boxArea(box, layer.clip());
            Corners paintRadii = "border-box".equals(layer.clip()) ? radii : border.innerRadii();
            ClipRect layerClip = intersectClip(clip, paint, paintRadii, id);
            RenderMeta imageMeta = new RenderMeta(layerClip, null, null, fontHVal, ox, oy, 1, false);

            List<BoxDecorations.Rect> tiles = BoxDecorations.tiles(layer, box, intrinsic, rootW, rootH,
                    box.style.fontSizePx(), box.style.rootFontSizePx());
            int part = 0;
            for (BoxDecorations.Rect t : tiles) {
                if (url != null) {
                    String tileSrc = url;
                    if (SvgImage.looksLikeSvg(url)) {
                        // SVG はタイルの大きさで描く（拡大しても滲まない）。届くまでは描かない
                        tileSrc = svgLayer == null ? "" : RasterPainting.svgSrc(svgLayer.root(), t.w(), t.h(), ImageFitMode.FILL, t.w(), t.h(), currentColor(box));
                    }
                    addElement(elements, id, id + idInfix + i + "_" + (part++), ElementType.IMAGE, ImageFitMode.FILL, t.x(), t.y(), t.w(), t.h(), tileSrc,
                            0, 0, events, 0, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, imageMeta);
                    continue;
                }
                // グラデーションはシェーダーが画素ごとに計算して描く（帯・格子の継ぎ目や段が出ない）。
                // 停止点の多いものは区間ごとに同じ矩形へ重ねる（各区間は自分の範囲の外を捨てる）
                List<ShaderGradient> shaderGradients = Gradients.shaderGradients(gradient, t, rootW, rootH,
                        box.style.fontSizePx(), box.style.rootFontSizePx());
                float gx0 = Math.max(t.x(), paint.x()), gy0 = Math.max(t.y(), paint.y());
                float gx1 = Math.min(t.x() + t.w(), paint.x() + paint.w()), gy1 = Math.min(t.y() + t.h(), paint.y() + paint.h());
                if (gx1 <= gx0 || gy1 <= gy0) continue;
                for (ShaderGradient segment : shaderGradients) {
                    ItemProps gradientEvents = events.copy();
                    gradientEvents.shaderGradient(segment);
                    addElement(elements, id, id + idInfix + i + "_" + (part++), ElementType.RECT, gx0, gy0, gx1 - gx0, gy1 - gy0, "",
                            0, 0, gradientEvents, 0, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, imageMeta);
                }
            }
        }
    }

    /** 角丸なら枠の領域だけを塗る 1 部品、そうでなければ辺ごとの帯。 */
    void drawBorder(List<DisplayItem> elements, String id, LayoutBox box, float rOff, ItemProps events,
                            BorderSpec border, RenderMeta meta, int transDur) {
        LayoutBox legend = "fieldset".equals(box.node.tag) ? box.renderedLegend() : null;
        if (legend != null && border.hasBorder()) {
            drawBorderAroundLegend(elements, id, box, legend, rOff, events, border, meta, transDur);
            return;
        }
        if (border.useRoundedBorder()) drawRoundedBorder(elements, id, box, events, border, meta, transDur);
        else if (border.hasBorder()) drawStraightBorders(elements, id, box, rOff, events, border, meta, transDur);
    }

    /**
     * legend の後ろを空けた fieldset の枠線。空ける矩形は、横が legend の border box、縦が枠線の上端から
     * legend の下マージンの端と枠線の下端の低い方まで（HTML §15.3.13）。描画は矩形の切り抜きを持たないので、
     * 空ける矩形の左・右・下の 3 つのクリップで同じ枠線を描く（クリップは重ならないので半透明の色も二重にならない）。
     */
    void drawBorderAroundLegend(List<DisplayItem> elements, String id, LayoutBox box, LayoutBox legend, float rOff,
                                        ItemProps events, BorderSpec b, RenderMeta meta, int transDur) {
        float left = legend.x, right = legend.x + legend.w;
        float bottom = Math.max(legend.y + legend.h + legend.mB, box.y + b.bwTop());
        float x0 = box.x - 1, y0 = box.y - 1, x1 = box.x + box.w + 1, y1 = box.y + box.h + 1;
        float[][] regions = {{x0, y0, left, y1}, {right, y0, x1, y1}, {left, bottom, right, y1}};
        float[] widths = {b.bwTop(), b.bwRight(), b.bwBottom(), b.bwLeft()};
        int[] colors = {b.bcTop(), b.bcRight(), b.bcBottom(), b.bcLeft()};
        for (int i = 0; i < regions.length; i++) {
            if (regions[i][2] <= regions[i][0] || regions[i][3] <= regions[i][1]) continue;
            ClipRect clip = ClipRect.fromArray(BoxDecorations.intersectClip(
                    meta.clipRect() == null ? null : meta.clipRect().toArray(), regions[i]));
            if (b.useRoundedBorder()) {
                RoundedBorderSpec spec = new RoundedBorderSpec(widths[0], widths[1], widths[2], widths[3],
                        colors[0], colors[1], colors[2], colors[3], b.dashKind());
                RenderMeta ringMeta = new RenderMeta(clip, meta.borderRadii(), null,
                        meta.fontHeight(), 0f, 0f, meta.originX(), meta.originY(), 1, false, spec);
                addElement(elements, id, id + "_b_round_" + i, ElementType.ROUNDED_BORDER, box.x, box.y, box.w, box.h, "", 0, 0x00000000,
                        events, 0, 1f, 0, 0, 1f, 1f, 0, transDur, AnimationSpec.NONE, ringMeta);
            } else {
                RenderMeta clipped = new RenderMeta(clip, meta.borderRadii(), null,
                        meta.fontHeight(), meta.originX(), meta.originY(), 1, false);
                drawLines(elements, id, "_b_", box.x, box.y, box.w, box.h, widths, b.styles(), colors, rOff,
                        events, clipped, transDur, true);
            }
        }
    }

    /**
     * 角丸の枠線。枠の領域（外側の角丸矩形 − パディングボックス）だけを塗る部品 {@code <id>_b_round} を 1 つ出す。
     * 片への分割は描画側が {@link BoxDecorations#roundedBorderSpans} で行う。
     *
     * 何が壊れていたか: 以前は箱全体を枠の色で塗った下地（{@code _bbg}）に内側の背景を重ねていたので、
     * 背景が透明な角丸の箱（{@code background: transparent; border: 1px solid; border-radius: 4px}）は
     * 中身ごと枠の色で塗りつぶされ、半透明の背景は枠の色と混ざっていた。
     */
    void drawRoundedBorder(List<DisplayItem> elements, String id, LayoutBox box, ItemProps events,
                                   BorderSpec b, RenderMeta meta, int transDur) {
        RoundedBorderSpec spec = new RoundedBorderSpec(b.bwTop(), b.bwRight(), b.bwBottom(), b.bwLeft(),
                b.bcTop(), b.bcRight(), b.bcBottom(), b.bcLeft(), b.dashKind());
        RenderMeta ringMeta = new RenderMeta(meta.clipRect(), meta.borderRadii(), null,
                meta.fontHeight(), 0f, 0f, meta.originX(), meta.originY(), 1, false, spec);
        addElement(elements, id, id + "_b_round", ElementType.ROUNDED_BORDER, box.x, box.y, box.w, box.h, "", 0, 0x00000000,
                events, 0, 1f, 0, 0, 1f, 1f, 0, transDur, AnimationSpec.NONE, ringMeta);
    }

    void drawStraightBorders(List<DisplayItem> elements, String id, LayoutBox box, float rOff, ItemProps partsEvents, BorderSpec b, RenderMeta meta, int transDur) {
        float[] widths = {b.bwTop(), b.bwRight(), b.bwBottom(), b.bwLeft()};
        int[] colors = {b.bcTop(), b.bcRight(), b.bcBottom(), b.bcLeft()};
        drawLines(elements, id, "_b_", box.x, box.y, box.w, box.h, widths, b.styles(), colors, rOff, partsEvents, meta, transDur);
    }

    /**
     * 矩形の内側に沿った 4 辺の線（枠線と輪郭線で共有）。辺ごとに線種で片に分ける（{@link BoxDecorations}）。
     * 上下の辺は幅いっぱい、左右の辺は高さいっぱいの帯で、角は重ねて塗る（従来どおり）。
     *
     * 片の id は {@code <id><prefix><辺>} と、2 つ目からは {@code _<番号>} を足したもの
     * （{@code getBaseNodeId} がどちらも持ち主の id に戻す）。
     */
    void drawLines(List<DisplayItem> elements, String id, String prefix, float x, float y, float w, float h,
                           float[] widths, String[] styles, int[] colors, float rOff,
                           ItemProps events, RenderMeta meta, int transDur) {
        drawLines(elements, id, prefix, x, y, w, h, widths, styles, colors, rOff, events, meta, transDur, false);
    }

    /** {@code numbered} なら片の id をすべて番号付きにする（1 つの箱が複数の矩形に線を描くインラインの断片）。 */
    void drawLines(List<DisplayItem> elements, String id, String prefix, float x, float y, float w, float h,
                           float[] widths, String[] styles, int[] colors, float rOff,
                           ItemProps events, RenderMeta meta, int transDur, boolean numbered) {
        float[][] bands = {
                {x + rOff, y, w - rOff * 2, widths[0]},
                {x + w - widths[1], y + rOff, widths[1], h - rOff * 2},
                {x + rOff, y + h - widths[2], w - rOff * 2, widths[2]},
                {x, y + rOff, widths[3], h - rOff * 2},
        };
        BoxDecorations.Side[] sides = BoxDecorations.Side.values();
        // 帯の両端に接する辺（上下は左右、左右は上下）
        float[][] insets = {{widths[3], widths[1]}, {widths[0], widths[2]}, {widths[3], widths[1]}, {widths[0], widths[2]}};
        for (int i = 0; i < 4; i++) {
            if (widths[i] <= 0) continue;
            float[] band = bands[i];
            List<BoxDecorations.Segment> segments = BoxDecorations.sideSegments(styles[i], sides[i],
                    band[0], band[1], band[2], band[3], insets[i][0], insets[i][1]);
            String base = id + prefix + BORDER_DIRECTIONS[i];
            for (int k = 0; k < segments.size(); k++) {
                BoxDecorations.Segment seg = segments.get(k);
                int radius = seg.round() ? Math.round(Math.min(seg.w(), seg.h()) / 2f) : 0;
                Corners corners = radius > 0 ? new Corners(radius, radius, radius, radius) : null;
                RenderMeta segMeta = new RenderMeta(meta.clipRect(), corners, null,
                        meta.fontHeight(), meta.originX(), meta.originY(), 1, false);
                String partId = numbered ? base + "_" + (fragIds.getAndIncrement()) : k == 0 ? base : base + "_" + k;
                addElement(elements, id, partId, ElementType.RECT, seg.x(), seg.y(), seg.w(), seg.h(),
                        "", 0, BoxDecorations.shade(colors[i], seg.shade()), events, radius, 1f, 0, 0, 1f, 1f, 0, transDur,
                        AnimationSpec.NONE, segMeta);
            }
        }
    }

    record BorderSpec(
            boolean hasBorder, boolean isCircle, boolean useRoundedBorder,
            float bwTop, float bwRight, float bwBottom, float bwLeft,
            int bcTop, int bcRight, int bcBottom, int bcLeft,
            String[] styles, Corners innerRadii, int dashKind
    ) {
        int maxThickness() {
            return Math.round(Math.max(Math.max(bwTop, bwRight), Math.max(bwBottom, bwLeft)));
        }
    }
}
