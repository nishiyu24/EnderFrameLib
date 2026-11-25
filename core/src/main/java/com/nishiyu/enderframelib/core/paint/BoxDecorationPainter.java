package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.css.model.AnimationSpec;
import com.nishiyu.enderframelib.core.css.BoxGeomStyle;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.paint.model.ClipRect;
import com.nishiyu.enderframelib.core.paint.model.Corners;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.ImageFitMode;
import com.nishiyu.enderframelib.core.raster.svg.SvgImage;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;

import java.util.List;
import com.nishiyu.enderframelib.core.paint.model.ShaderGradient;
import com.nishiyu.enderframelib.core.paint.model.*;
import com.nishiyu.enderframelib.core.css.model.BorderStyle;
import static com.nishiyu.enderframelib.core.paint.PaintSupport.addElement;
import static com.nishiyu.enderframelib.core.paint.PaintSupport.growRadius;
import static com.nishiyu.enderframelib.core.paint.PaintSupport.intersectClip;
import java.util.concurrent.atomic.AtomicInteger;

/** 箱の飾り（背景・枠線・影・輪郭線・インラインの断片）を描画項目にする。 */
public final class BoxDecorationPainter {
    /** ぼかした影の矩形が、ぼかしが消える所まで外へ広がる倍率（ぼかし半径に対して）。 */
    public static final float SHADOW_REACH = 1.5f;

    private int rootW;
    private int rootH;
    private final AtomicInteger fragIds;
    private final ItemProps scratchGradientEvents = new ItemProps();
    private final BorderSpec scratchBorder = new BorderSpec();
    private final int[] scratchBorderColors = new int[4];
    private final String[] scratchBorderStyles = new String[4];
    private final PaintBounds bounds = new PaintBounds();

    void withBounds(LayoutBox box, float x, float y, float w, float h, boolean zeroEdges, Runnable action) {
        bounds.with(box, x, y, w, h, zeroEdges, action);
    }

    static String partId(LayoutBox box, String id, int slot, String suffix) {
        return PaintSupport.partId(box, id, slot, suffix);
    }

    int rootW() { return rootW; }
    int rootH() { return rootH; }

    /** いま描いている木をレイアウトしたデバイスピクセル比（{@link DisplayListBuilder#render} が描画の頭で入れる）。 */
    private float devicePixelRatio = 1f;

    float devicePixelRatio() { return devicePixelRatio; }
    void setDevicePixelRatio(float dppx) { this.devicePixelRatio = dppx; }

    /**
     * 装飾部品（インライン断片・影）の 64-bit 整数鍵の部品番号の領域（P0-3）。テキスト項目は 0〜0x7FFF（{@code fragIds}）を使うので、
     * 上位ビットを立てた領域に置いて同じ節の鍵と衝突させない。断片 {@code 0x8000〜0xBFFF}・外側の影 {@code 0xC000〜0xDFFF}・内側の影 {@code 0xE000〜0xFFFF}。
     */
    static final int FRAG_PART_BASE = 0x8000;
    static final int SHADOW_PART_BASE = 0xC000;
    static final int INSET_SHADOW_PART_BASE = 0xE000;
    private static final int FRAG_PART_MASK = 0x3FFF;
    private static final int SHADOW_PART_MASK = 0x1FFF;

    /** 装飾部品の整数鍵。ノードが無ければ 0（従来どおり ID 文字列から導く）。部品番号は領域内へ丸める。 */
    static long decorationKey(LayoutBox box, int base, int ordinal) {
        int handle = box.node != null ? box.node.paintHandle() : 0;
        if (handle <= 0) return 0L;
        int mask = base == FRAG_PART_BASE ? FRAG_PART_MASK : SHADOW_PART_MASK;
        return DisplayItem.keyOf(handle, base | (ordinal & mask));
    }

    void setViewport(int rootW, int rootH) {
        this.rootW = rootW;
        this.rootH = rootH;
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
    private static final String[] SOLID_STYLES = {"solid", "solid", "solid", "solid"};

    private static String borderStyleCssName(BoxGeomStyle b, int side) {
        BorderStyle s = switch (side) {
            case 0 -> b.borderTopStyle;
            case 1 -> b.borderRightStyle;
            case 2 -> b.borderBottomStyle;
            default -> b.borderLeftStyle;
        };
        return s == BorderStyle.NONE ? "solid" : s.cssName();
    }

    /** 直前の内側の角（円）。値が同じなら同じ {@link Corners} を返し、毎フレームの作り直しを避ける（B9）。 */
    private Corners lastInner = Corners.ZERO;

    /** 円の角の {@link Corners}。全部 0 なら {@link Corners#ZERO}、直前と同じ値なら直前のものを返す。 */
    Corners innerCircular(int tl, int tr, int br, int bl) {
        if ((tl | tr | br | bl) == 0) return Corners.ZERO;
        Corners c = lastInner;
        if (c.topLeft() == tl && c.topRight() == tr && c.bottomRight() == br && c.bottomLeft() == bl && !c.isElliptical()) return c;
        return lastInner = new Corners(tl, tr, br, bl);
    }

    /**
     * 枠線の幅・色・線種。幅はレイアウトが決めた値（{@code box.bT} など）をそのまま使う（軸 1・3）。
     * 線種が none / hidden の辺は算出値の段で幅 0 になっている。色が無ければ {@code currentcolor}。
     *
     * 何が壊れていたか: 以前は描画が {@code border-<辺>} の文字列から幅と色を読み直しており、
     * 色を書かない {@code border: 1px solid} の枠が透明になり、線種は読まずに常に実線だった。
     */
    BorderSpec parseBorderSpec(LayoutBox box, int[] radii, Corners corners, int textColor) {
        float bwTop = box.bT(), bwRight = box.bR(), bwBottom = box.bB(), bwLeft = box.bL();
        boolean hasBorder = bwTop > 0f || bwRight > 0f || bwBottom > 0f || bwLeft > 0f;
        boolean anyRadius = radii[0] > 0 || radii[1] > 0 || radii[2] > 0 || radii[3] > 0;
        Style ax = box.axisStyle().typed();
        if (!hasBorder) {
            boolean isCircle = anyRadius
                    && radii[0] >= (box.w() / 2) - 1 && radii[1] >= (box.w() / 2) - 1
                    && radii[2] >= (box.w() / 2) - 1 && radii[3] >= (box.w() / 2) - 1
                    && box.w() > 0 && box.h() > 0 && Math.abs(box.w() - box.h()) <= 2;
            int bc0 = ax.borderColor(0, textColor);
            return scratchBorder.set(false, isCircle, false, 0f, 0f, 0f, 0f, bc0, bc0, bc0, bc0,
                    SOLID_STYLES, corners, BorderPath.SOLID);
        }
        int[] colors = scratchBorderColors;
        String[] styles = scratchBorderStyles;
        for (int i = 0; i < 4; i++) {
            colors[i] = ax.borderColor(i, textColor);
            styles[i] = borderStyleCssName(ax.box, i);
        }
        int bcTop = colors[0], bcRight = colors[1], bcBottom = colors[2], bcLeft = colors[3];

        // 4 辺が同じ線種（破線か点線）・太さ・色なら、角の円弧に沿って 1 本の経路として割り付ける（Blink と同じ）。
        // LIMITATION(制約): 辺ごとに線種・太さ・色が違う角丸の枠線は、線種を読まず実線で描き、角の中で隣り合う辺の色が
        // 切り替わる境目は斜めの線ではなく辺の太さの内側か外側かで決める。README の "Not supported" に対応。
        int dashKind = BorderPath.SOLID;
        if (anyRadius && bwTop > 0 && bwTop == bwRight && bwRight == bwBottom && bwBottom == bwLeft
                && bcTop == bcRight && bcRight == bcBottom && bcBottom == bcLeft
                && styles[0].equals(styles[1]) && styles[1].equals(styles[2]) && styles[2].equals(styles[3])) {
            dashKind = "dashed".equals(styles[0]) ? BorderPath.DASHED : "dotted".equals(styles[0]) ? BorderPath.DOTTED : BorderPath.SOLID;
        }

        boolean isCircle = dashKind == BorderPath.SOLID
                && radii[0] >= (box.w() / 2) - 1 && radii[1] >= (box.w() / 2) - 1
                && radii[2] >= (box.w() / 2) - 1 && radii[3] >= (box.w() / 2) - 1
                && box.w() > 0 && box.h() > 0 && Math.abs(box.w() - box.h()) <= 2;
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
            innerRadii = innerCircular(
                    Math.max(0, Math.round(radii[0] - Math.max(bwTop, bwLeft))),
                    Math.max(0, Math.round(radii[1] - Math.max(bwTop, bwRight))),
                    Math.max(0, Math.round(radii[2] - Math.max(bwBottom, bwRight))),
                    Math.max(0, Math.round(radii[3] - Math.max(bwBottom, bwLeft))));
        }

        return scratchBorder.set(hasBorder, isCircle, useRoundedBorder,
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
        LayoutBox op = box.originParent();
        float opX = op != null ? op.x() : 0f, opY = op != null ? op.y() : 0f;
        for (LayoutBox.InlineFragment frag : box.inlineFragments) {
            if (frag.isText()) continue;
            float fx = frag.x + opX, fy = frag.y + opY;
            int[] r = {
                    frag.startEdge ? rawRadii[0] : 0, frag.endEdge ? rawRadii[1] : 0,
                    frag.endEdge ? rawRadii[2] : 0, frag.startEdge ? rawRadii[3] : 0
            };
            Corners radii = Corners.fromArray(r);
            RenderMeta meta = new RenderMeta(clip, radii, null, fontHVal, 0f, box.lineHeightPx(), ox, oy, itemCount, interactive);
            int fragPart = fragIds.getAndIncrement();
            addElement(elements, id, id + "_frag_" + fragPart, ElementType.RECT, fx, fy, frag.w, frag.h,
                    "", color, bgColor, partsEvents, r[0], 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, meta, decorationKey(box, FRAG_PART_BASE, fragPart));
            drawFragmentImages(elements, box, id, fx, fy, frag.w, frag.h, clip, radii, border, passiveBorderEvents, fontHVal, ox, oy);

            float[] widths = {box.bT(), frag.endEdge ? box.bR() : 0, box.bB(), frag.startEdge ? box.bL() : 0};
            if (widths[0] <= 0 && widths[1] <= 0 && widths[2] <= 0 && widths[3] <= 0) continue;
            if (r[0] > 0 || r[1] > 0 || r[2] > 0 || r[3] > 0) {
                RoundedBorderSpec spec = new RoundedBorderSpec(widths[0], widths[1], widths[2], widths[3], colors[0], colors[1], colors[2], colors[3]);
                RenderMeta ringMeta = new RenderMeta(clip, radii, null, fontHVal, 0f, 0f, ox, oy, 1, false, spec);
                int ringPart = fragIds.getAndIncrement();
                addElement(elements, id, id + "_b_round_" + ringPart, ElementType.ROUNDED_BORDER, fx, fy, frag.w, frag.h,
                        "", 0, 0x00000000, passiveBorderEvents, 0, 1f, 0, 0, 1f, 1f, 0, transDur, AnimationSpec.NONE, ringMeta,
                        decorationKey(box, FRAG_PART_BASE, ringPart));
            } else {
                drawLines(elements, id, "_b_", fx, fy, frag.w, frag.h, widths, styles, colors, 0,
                        passiveBorderEvents, meta, transDur, true, box.node != null ? box.node.paintHandle() : 0);
            }
        }
    }

    /**
     * インラインボックスの 1 断片に、{@code background-image}（画像・グラデーション）を描く。断片の矩形を箱の大きさとして
     * 背景のレイヤーを並べる（{@code box-decoration-break: clone} 相当。断片ごとに最初から並べ直す）。
     * 何が壊れていたか: インラインの箱は背景色の矩形と枠線しか出さず、{@code background-image} と {@code background-clip: text} が効かなかった。
     */
    private void drawFragmentImages(List<DisplayItem> elements, LayoutBox box, String id, float fx, float fy, float fw, float fh,
                                    ClipRect clip, Corners radii, BorderSpec border, ItemProps events, float fontHVal, float ox, float oy) {
        List<BoxDecorations.BackgroundLayer> layers = BoxDecorations.layers(box.style, devicePixelRatio);
        boolean any = false;
        for (BoxDecorations.BackgroundLayer l : layers) any |= l.url() != null || l.isGradient();
        if (!any) return;
        bounds.with(box, fx, fy, fw, fh, true, () -> drawImageLayers(elements, box, id, layers, "_f" + fragIds.getAndIncrement() + "_bgl", clip, radii, border, events, fontHVal, ox, oy, null));
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
        Style st = box.style.typed();
        String lineStyle = st.visual.outlineStyle.cssName();
        if ("none".equals(lineStyle) || "hidden".equals(lineStyle)) return;
        float w = st.visPx(st.visual.outlineWidth, box.w(), rootW, rootH);
        if (w <= 0) return;
        float offset = st.visPx(st.visual.outlineOffset, box.w(), rootW, rootH);
        int c = st.outlineColor(color);
        if ("auto".equals(lineStyle)) lineStyle = "solid";

        float ow = box.w() + 2 * (offset + w), oh = box.h() + 2 * (offset + w);
        if (ow <= 0 || oh <= 0) return;
        drawLines(elements, id, "_o_", box.x() - offset - w, box.y() - offset - w, ow, oh,
                new float[]{w, w, w, w}, new String[]{lineStyle, lineStyle, lineStyle, lineStyle},
                new int[]{c, c, c, c}, 0, events, meta, transDur);
    }

    /**
     * 段組（multicol）の列の間の線（{@code column-rule}）。線の位置は {@link LayoutBox#columnRules}
     * （レイアウトが列の間の中央に置いた矩形）で、箱の背景の上・文字の下に出る。
     */
    void drawColumnRules(List<DisplayItem> elements, LayoutBox box, String id, int color,
                         ItemProps events, RenderMeta meta, int transDur) {
        float[][] rules = box.columnRules();
        if (rules == null) return;
        Style st = box.style.typed();
        String lineStyle = st.rare.columnRuleStyle.cssName();
        if ("none".equals(lineStyle) || "hidden".equals(lineStyle)) return;
        int c = st.columnRuleColor(color);
        String[] styles = {lineStyle, lineStyle, lineStyle, lineStyle};
        int[] colors = {c, c, c, c};
        float bx = box.x(), by = box.y();
        for (int i = 0; i < rules.length; i++) {
            float[] r = rules[i];
            if (r[2] > 0 && r[3] > 0) drawLines(elements, id, "_cr" + i + "_", r[0] + bx, r[1] + by, r[2], r[3], new float[]{0, 0, 0, r[2]}, styles, colors, 0, events, meta, transDur);
        }
    }

    /** {@code ::first-line} の背景色（透明以外なら 1 行目の領域に矩形を敷く）。 */
    void drawFirstLineBackground(List<DisplayItem> elements, LayoutBox box, String id, int fallbackColor,
                                 ItemProps events, RenderMeta meta, int transDur) {
        float[] rect = box.firstLineBgRect();
        if (rect == null || rect[2] <= 0 || rect[3] <= 0) return;
        com.nishiyu.enderframelib.core.css.ComputedStyle fl = box.firstLineStyle != null ? box.firstLineStyle
                : (box.isVerticalWrapper() && box.parent != null ? box.parent.firstLineStyle : null);
        if (fl == null) return;
        int bg = fl.typed().visual.backgroundColor;
        if ((bg >>> 24) == 0) return;
        int part = fragIds.getAndIncrement();
        long key = box.node != null ? DisplayItem.keyOf(box.node.paintHandle(), FRAG_PART_BASE + part) : 0L;
        addElement(elements, id, id + "_flbg_" + part, ElementType.RECT, rect[0] + box.x(), rect[1] + box.y(), rect[2], rect[3],
                "", 0, bg, events, 0, 1f, 0, 0, 1f, 1f, 0, transDur, AnimationSpec.NONE, meta, key);
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
        if (shadows.isEmpty()) return;
        ItemProps events = ItemProps.parent(id);
        int n = 0;
        for (int i = shadows.size() - 1; i >= 0; i--) {
            BoxDecorations.Shadow s = shadows.get(i);
            if (s.inset()) continue;
            if (s.blur() > 0) {
                // ぼかしは 1 枚の板に任せ、シェーダーが辺からの距離で滑らかに減衰させる（層の重ね塗りは段が見えた）。
                // 板は影の形から「ぼかしが消える所まで」広げた矩形で、形の角丸は広がりの分だけ変える
                float reach = SHADOW_REACH * s.blur();
                float grow = s.spread() + reach;
                float w = box.w() + 2 * grow, h = box.h() + 2 * grow;
                if (w <= 0 || h <= 0) continue;
                Corners r = grow(radii, s.spread());
                ItemProps blurEvents = events.copy();
                blurEvents.shadowBlur(s.blur());
                // 影は箱の外だけに出す（背景が透明・半透明の箱の内側に透けないよう、箱の形を穴として抜く）
                blurEvents.shadowShape(new ShadowShape(false, box.x(), box.y(), box.x() + box.w(), box.y() + box.h(),
                        radii.topLeft(), radii.topRight(), radii.bottomRight(), radii.bottomLeft(),
                        radii.topLeftV(), radii.topRightV(), radii.bottomRightV(), radii.bottomLeftV()));
                addElement(elements, id, id + "_shadow_" + n, ElementType.RECT,
                        box.x() + s.offsetX() - grow, box.y() + s.offsetY() - grow, w, h, "", 0, s.color(), blurEvents,
                        r.topLeft(), 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE,
                        new RenderMeta(clip, r, null, fontHVal, ox, oy, 1, false), decorationKey(box, SHADOW_PART_BASE, n));
                n++;
                continue;
            }
            for (BoxDecorations.ShadowLayer layer : BoxDecorations.shadowLayers(s)) {
                float grow = s.spread() + layer.grow();
                float w = box.w() + 2 * grow, h = box.h() + 2 * grow;
                if (w <= 0 || h <= 0) continue;
                // 広がりは角の丸みも同じだけ変える（丸みの無い角は角のまま。§7.1.1）
                Corners r = grow(radii, grow);
                addElement(elements, id, id + "_shadow_" + n, ElementType.RECT,
                        box.x() + s.offsetX() - grow, box.y() + s.offsetY() - grow, w, h, "", 0, layer.color(), events,
                        r.topLeft(), 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE,
                        new RenderMeta(clip, r, null, fontHVal, ox, oy, 1, false), decorationKey(box, SHADOW_PART_BASE, n));
                n++;
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
        boolean hasInset = false;
        for (int i = 0, sn = shadows.size(); i < sn; i++) {
            if (shadows.get(i).inset()) { hasInset = true; break; }
        }
        if (!hasInset) return;
        BoxDecorations.Rect pad = bounds.area(box, "padding-box");
        if (pad.w() <= 0 || pad.h() <= 0) return;
        ClipRect padClip = intersectClip(elements, clip, pad, border.innerRadii(), id);
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
                shadowEvents.shadowShape(new ShadowShape(true, hx0, hy0, hx1, hy1,
                        hh[0], hh[1], hh[2], hh[3], hv[0], hv[1], hv[2], hv[3]));
            } else {
                shadowEvents.shadowShape(new ShadowShape(true, hx0, hy0, hx1, hy1,
                        Math.min(Math.max(0, inner.topLeft() - s.spread()), limit), Math.min(Math.max(0, inner.topRight() - s.spread()), limit),
                        Math.min(Math.max(0, inner.bottomRight() - s.spread()), limit), Math.min(Math.max(0, inner.bottomLeft() - s.spread()), limit)));
            }
            addElement(elements, id, id + "_shadow_in" + n, ElementType.RECT, px0, py0, px1 - px0, py1 - py0, "", 0,
                    s.color(), shadowEvents, 0, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE,
                    new RenderMeta(padClip, null, null, fontHVal, ox, oy, 1, false), decorationKey(box, INSET_SHADOW_PART_BASE, n));
            n++;
        }
    }

    void drawDefaultBlockElement(List<DisplayItem> elements, LayoutBox box, String id, int color, int bgColor, int fallbackRadius,
                                         Corners radii, ClipRect normalizedClip, float fontHVal, float ox, float oy,
                                         ItemProps partsEvents, ItemProps passiveBorderEvents,
                                         BorderSpec border, RenderMeta baseMeta, RenderMeta innerMeta, float rOff,
                                         int transDur, AnimationSpec colorBgAnim, List<BoxDecorations.Shadow> shadows) {
        drawDefaultBlockElement(elements, box, id, color, bgColor, fallbackRadius, radii, normalizedClip, fontHVal, ox, oy,
                partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim, shadows, null);
    }

    void drawDefaultBlockElement(List<DisplayItem> elements, LayoutBox box, String id, int color, int bgColor, int fallbackRadius,
                                         Corners radii, ClipRect normalizedClip, float fontHVal, float ox, float oy,
                                         ItemProps partsEvents, ItemProps passiveBorderEvents,
                                         BorderSpec border, RenderMeta baseMeta, RenderMeta innerMeta, float rOff,
                                         int transDur, AnimationSpec colorBgAnim, List<BoxDecorations.Shadow> shadows,
                                         List<DisplayItem> prevItems) {
        ElementType bgType = ElementType.RECT;
        if (com.nishiyu.enderframelib.core.dom.node.ElementTraits.isButton(box.node.tag)) {
            bgType = ElementType.BUTTON;
        } else if (com.nishiyu.enderframelib.core.dom.node.ElementTraits.isAnchor(box.node.tag)) {
            bgType = ElementType.A;
        }
        boolean isInteractiveBox = bgType != ElementType.RECT;
        long bgKey = box.node != null ? DisplayItem.bgKeyOf(box.node.paintHandle()) : 0L;

        if (border.isCircle() && border.hasBorder()) {
            int thickness = border.maxThickness();
            String bData = thickness + ":" + border.bcTop() + "," + border.bcRight() + "," + border.bcBottom() + "," + border.bcLeft();
            CircleBorderSpec cbSpec = new CircleBorderSpec(thickness, border.bcTop(), border.bcRight(), border.bcBottom(), border.bcLeft());
            addElement(elements, id, partId(box, id, 0, "_bg"), bgType, bounds.x(box), bounds.y(box), bounds.w(box), bounds.h(box), "", color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, baseMeta, bgKey);
            drawBackgroundImages(elements, box, id, normalizedClip, radii, border, passiveBorderEvents, fontHVal, ox, oy, prevItems);
            drawInsetShadows(elements, box, id, shadows, border, normalizedClip, fontHVal, ox, oy);
            long cbKey = box.node != null ? DisplayItem.keyOf(box.node.paintHandle(), DisplayItem.PART_BORDER) : 0L;
            addElement(elements, id, partId(box, id, 1, "_cb"), ElementType.CIRCLE_BORDER, bounds.x(box), bounds.y(box), bounds.w(box), bounds.h(box), bData, 0, 0, passiveBorderEvents, 0, 1f, 0, 0, 1f, 1f, 0, transDur, AnimationSpec.NONE,
                    new RenderMeta(normalizedClip, radii, cbSpec, fontHVal, ox, oy, 1, false), cbKey);
        } else {
            // background-clip が border-box でなければ、色は箱の内側だけに塗る。_bg はヒットテストと
            // 状態の受け口なので箱いっぱいのまま残し、色だけを切った矩形に移す
            String colorClip = BoxDecorations.colorClip(box.style);
            int bgFill = bgColor;
            if (bgColor != 0x00000000 && !"border-box".equals(colorClip)) {
                bgFill = 0x00000000;
                BoxDecorations.Rect area = bounds.area(box, colorClip);
                Corners areaRadii = "padding-box".equals(colorClip) ? border.innerRadii() : null;
                int bglCount = BoxDecorations.layersView(box.style, 1.0).size();
                long bglKey = box.node != null ? DisplayItem.keyOf(box.node.paintHandle(), DisplayItem.PART_BGL_BASE + (bglCount & 0x3F)) : 0L;
                addElement(elements, id, id + "_bgl" + bglCount + "_0", ElementType.RECT,
                        area.x(), area.y(), area.w(), area.h(), "", 0, bgColor, passiveBorderEvents, 0, 1f, 0, 0, 1f, 1f, 0, transDur, AnimationSpec.NONE,
                        new RenderMeta(normalizedClip, areaRadii, null, fontHVal, ox, oy, 1, false), bglKey);
            }
            // 角丸の箱でも背景は枠線ボックスいっぱい（枠線の下まで）に塗り、枠線をその上に重ねる（付録 E）
            addElement(elements, id, partId(box, id, 0, "_bg"), bgType, bounds.x(box), bounds.y(box), bounds.w(box), bounds.h(box), "", color, bgFill, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, baseMeta, bgKey);
            drawBackgroundImages(elements, box, id, normalizedClip, radii, border, passiveBorderEvents, fontHVal, ox, oy, prevItems);
            drawInsetShadows(elements, box, id, shadows, border, normalizedClip, fontHVal, ox, oy);
            // border-image（9-slice）が描ければ、枠線の通常の描画に代わる（枠線の太さは配置にそのまま効く）
            if (!drawBorderImage(elements, box, id, normalizedClip, passiveBorderEvents, fontHVal, ox, oy)) {
                drawBorder(elements, id, box, rOff, passiveBorderEvents, border, baseMeta, transDur, prevItems);
            }
        }

        if (box.node.attrs.containsKey("data-ghost-item")) {
            String ghostItem = box.node.attrs.get("data-ghost-item").trim();
            if (!ghostItem.isEmpty() && !"minecraft:air".equals(ghostItem)) {
                float iconX = bounds.x(box) + (bounds.w(box) - 16) / 2;
                float iconY = bounds.y(box) + (bounds.h(box) - 16) / 2;
                long ghostKey = box.node != null ? DisplayItem.keyOf(box.node.paintHandle(), DisplayItem.PART_GHOST_IMAGE) : 0L;
                addElement(elements, id, partId(box, id, 2, "_ghost_img"), ElementType.IMAGE, ImageFitMode.FILL, iconX, iconY, 16, 16, "item:" + ghostItem, color, 0, partsEvents, 0, 0.85f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, innerMeta, ghostKey);
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
        drawBackgroundImages(elements, box, id, clip, radii, border, events, fontHVal, ox, oy, null);
    }

    void drawBackgroundImages(List<DisplayItem> elements, LayoutBox box, String id, ClipRect clip, Corners radii,
                                      BorderSpec border, ItemProps events, float fontHVal, float ox, float oy,
                                      List<DisplayItem> prevItems) {
        drawImageLayers(elements, box, id, BoxDecorations.layersView(box.style, devicePixelRatio), "_bgl", clip, radii, border, events, fontHVal, ox, oy, prevItems);
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
        drawImageLayers(elements, box, id, BoxDecorations.maskLayersView(box.style, devicePixelRatio), "_mask", clip, radii, border, events, fontHVal, ox, oy, null);
    }

    private static String cachedLayerSuffix(String idInfix, int layerIdx, int part) {
        if ("_bgl".equals(idInfix)) {
            return switch ((layerIdx << 2) | part) {
                case 0 -> "_bgl0_0";
                case 1 -> "_bgl0_1";
                case 2 -> "_bgl0_2";
                case 3 -> "_bgl0_3";
                case 4 -> "_bgl1_0";
                case 5 -> "_bgl1_1";
                case 6 -> "_bgl1_2";
                case 7 -> "_bgl1_3";
                case 8 -> "_bgl2_0";
                case 9 -> "_bgl2_1";
                case 10 -> "_bgl2_2";
                case 11 -> "_bgl2_3";
                default -> idInfix + layerIdx + "_" + part;
            };
        }
        return part == 0 ? "_mask0_0" : "_mask0_1";
    }

    private static int layerPartSlot(boolean bglCached, int layerIdx, int part) {
        return bglCached ? (part < 2 ? 4 + layerIdx * 2 + part : 20 + layerIdx * 2 + (part - 2)) : (10 + part);
    }

    private static final Object SHADER_GRADIENT_CACHE_KEY = new Object();

    private static final class ShaderGradientCache {
        Gradients.Gradient g;
        boolean vertical;
        float lx, ly, tw, th, rootW, rootH, fontSize, rootFontSize;
        List<ShaderGradient> result;
    }

    private static List<ShaderGradient> cachedShaderGradients(LayoutBox box, Gradients.Gradient g,
                                                              BoxDecorations.Rect t, float offX, float offY,
                                                              int rootW, int rootH) {
        float fontSize = box.style.fontSizePx();
        float rootFontSize = box.style.rootFontSizePx();
        float lx = t.x() + offX, ly = t.y() + offY;
        boolean vertical = g instanceof Gradients.LinearGradient lg && lg.cornerX() == 0
                && Math.abs(Math.sin(Math.toRadians(lg.angle()))) < 1e-9;
        ShaderGradientCache cache = box.style.paintMemoSelf(SHADER_GRADIENT_CACHE_KEY, s -> new ShaderGradientCache());
        if (cache.result != null && cache.g == g
                && Float.compare(cache.ly, ly) == 0 && Float.compare(cache.th, t.h()) == 0
                && cache.rootW == rootW && cache.rootH == rootH
                && cache.fontSize == fontSize && cache.rootFontSize == rootFontSize
                && (vertical || (Float.compare(cache.lx, lx) == 0 && Float.compare(cache.tw, t.w()) == 0))) {
            return cache.result;
        }
        List<ShaderGradient> res = Gradients.shaderGradients(g, t, offX, offY, rootW, rootH, fontSize, rootFontSize);
        cache.g = g;
        cache.vertical = vertical;
        cache.lx = lx;
        cache.ly = ly;
        cache.tw = t.w();
        cache.th = t.h();
        cache.rootW = rootW;
        cache.rootH = rootH;
        cache.fontSize = fontSize;
        cache.rootFontSize = rootFontSize;
        cache.result = res;
        return res;
    }

    private static DisplayItem findPrevLayerItem(List<DisplayItem> prevItems, long key, String id) {
        if (prevItems == null) return null;
        for (int i = 0, n = prevItems.size(); i < n; i++) {
            DisplayItem it = prevItems.get(i);
            if (key != 0L ? it.key() == key : id.equals(it.id())) return it;
        }
        return null;
    }

    private static RenderMeta reuseLayerMeta(RenderMeta prevMeta, ClipRect clip, Corners radii,
                                             float fontHVal, float ox, float oy, RenderHints hints) {
        if (prevMeta != null
                && prevMeta.circleBorder() == null
                && prevMeta.roundedBorder() == null
                && Float.compare(prevMeta.fontHeight(), fontHVal) == 0
                && prevMeta.baseline() == 0f
                && prevMeta.lineHeight() == 0f
                && Float.compare(prevMeta.originX(), ox) == 0
                && Float.compare(prevMeta.originY(), oy) == 0
                && prevMeta.itemCount() == 1
                && !prevMeta.interactiveState()
                && java.util.Objects.equals(prevMeta.clipRect(), clip)
                && java.util.Objects.equals(prevMeta.borderRadii(), radii)
                && java.util.Objects.equals(prevMeta.hints(), hints)) {
            return prevMeta;
        }
        return new RenderMeta(clip, radii, null, fontHVal, 0f, 0f, ox, oy, 1, false, null, hints);
    }

    /** {@code currentColor} になる色（要素の {@code color}）。 */
    private static int currentColor(LayoutBox box) {
        return box.style.typed().inheritedPaint.color;
    }

    private void drawImageLayers(List<DisplayItem> elements, LayoutBox box, String id, List<BoxDecorations.BackgroundLayer> layers,
                                 String idInfix, ClipRect clip, Corners radii, BorderSpec border, ItemProps events,
                                 float fontHVal, float ox, float oy, List<DisplayItem> prevItems) {
        for (int i = layers.size() - 1; i >= 0; i--) {
            BoxDecorations.BackgroundLayer layer = layers.get(i);
            String url = layer.url();
            Gradients.Gradient gradient = layer.isGradient() ? Gradients.parse(layer.image()) : null;
            if (url == null && gradient == null) continue;

            int[] intrinsic = PaintServices.intrinsicSize(url);
            if (intrinsic != null && layer.density() != 1.0) {
                // image-set() の解像度（2x なら画像の画素数の半分が CSS px の大きさ）
                intrinsic = new int[]{(int) Math.max(1, Math.round(intrinsic[0] / layer.density())),
                        (int) Math.max(1, Math.round(intrinsic[1] / layer.density()))};
            }
            SvgImage svgLayer = url != null && SvgImage.looksLikeSvg(url) ? PaintServices.svg(url) : null;
            if (svgLayer != null && intrinsic == null) {
                intrinsic = new int[]{(int) Math.max(1, Math.round(svgLayer.width() / layer.density())),
                        (int) Math.max(1, Math.round(svgLayer.height() / layer.density()))};
            }
            BoxDecorations.Rect paint = bounds.area(box, layer.clip());
            Corners paintRadii = "border-box".equals(layer.clip()) ? radii : border.innerRadii();
            RenderMeta imageMeta = null;
            RenderMeta exactMeta = null;

            List<BoxDecorations.Rect> tiles = BoxDecorations.tiles(layer, bounds.area(box, layer.origin()), paint,
                    intrinsic, rootW, rootH, box.style.fontSizePx(), box.style.rootFontSizePx());
            int part = 0;
            int nodeHandle = box.node != null ? box.node.paintHandle() : 0;
            boolean bglCached = "_bgl".equals(idInfix) && i < 3;
            boolean maskCached = "_mask".equals(idInfix) && i == 0;
            PaintSupport.CaptureList cap = elements instanceof PaintSupport.CaptureList c ? c : null;
            float offX = cap != null ? -cap.originX : 0f;
            float offY = cap != null ? -cap.originY : 0f;
            boolean localGradient = offX != 0f || offY != 0f;
            for (int ti = 0, tn = tiles.size(); ti < tn; ti++) {
                BoxDecorations.Rect t = tiles.get(ti);
                if (url != null) {
                    String tileSrc = url;
                    if (SvgImage.looksLikeSvg(url)) {
                        // SVG はタイルの大きさで描く（拡大しても滲まない）。届くまでは描かない
                        tileSrc = svgLayer == null ? "" : RasterPainting.svgSrc(svgLayer.root(), t.w(), t.h(), ImageFitMode.FILL, t.w(), t.h(), currentColor(box), devicePixelRatio, svgLayer.sheet());
                    }
                    if (imageMeta == null) {
                        ClipRect layerClip = intersectClip(elements, clip, paint, paintRadii, id);
                        imageMeta = new RenderMeta(layerClip, null, null, fontHVal, 0f, 0f, ox, oy, 1, false, null, events.hints());
                    }
                    long bglPartKey = nodeHandle > 0 ? DisplayItem.keyOf(nodeHandle, DisplayItem.PART_BGL_BASE + ((i & 0x07) << 4) + (part & 0x0F)) : 0L;
                    String pId = ((bglCached && part < 4) || (maskCached && part < 2))
                            ? partId(box, id, layerPartSlot(bglCached, i, part), cachedLayerSuffix(idInfix, i, part))
                            : id + idInfix + i + "_" + part;
                    part++;
                    DisplayItem prevImg = findPrevLayerItem(prevItems, bglPartKey, pId);
                    RenderMeta imgMeta = prevImg != null ? reuseLayerMeta(prevImg.meta(), imageMeta.clipRect(), null, fontHVal, ox, oy, events.hints()) : imageMeta;
                    addElement(elements, id, pId, ElementType.IMAGE, ImageFitMode.FILL, t.x(), t.y(), t.w(), t.h(), tileSrc,
                            0, 0, events, 0, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, imgMeta, bglPartKey);
                    continue;
                }
                // グラデーションはシェーダーが画素ごとに計算して描く（帯・格子の継ぎ目や段が出ない）。
                // 停止点の多いものは区間ごとに同じ矩形へ重ねる（各区間は自分の範囲の外を捨てる）
                List<ShaderGradient> shaderGradients = cachedShaderGradients(box, gradient, t, offX, offY, rootW, rootH);
                float gx0 = Math.max(t.x(), paint.x()), gy0 = Math.max(t.y(), paint.y());
                float gx1 = Math.min(t.x() + t.w(), paint.x() + paint.w()), gy1 = Math.min(t.y() + t.h(), paint.y() + paint.h());
                if (gx1 <= gx0 || gy1 <= gy0) continue;
                boolean coversPaint = Math.abs(gx0 - paint.x()) < 0.01f && Math.abs(gy0 - paint.y()) < 0.01f
                        && Math.abs(gx1 - (paint.x() + paint.w())) < 0.01f && Math.abs(gy1 - (paint.y() + paint.h())) < 0.01f;
                // 塗る範囲 = 描画領域のとき（ふつうの背景）、角丸の形は描画領域そのもので、祖先のクリップは別に渡す。
                // 合成した 1 つの角丸矩形（layerClip）は、祖先の縁が円や角丸の箱を切ると、切り口の角に半径を当てて形を歪め、
                // 祖先が動くと矩形ごと動いてしまう。形（角丸）と切り取り（祖先）を分ければどちらも正確に描ける
                scratchGradientEvents.copyFrom(events);
                for (int si = 0, sn = shaderGradients.size(); si < sn; si++) {
                    ShaderGradient segment = shaderGradients.get(si);
                    scratchGradientEvents.shaderGradient(segment, localGradient);
                    long bglPartKey = nodeHandle > 0 ? DisplayItem.keyOf(nodeHandle, DisplayItem.PART_BGL_BASE + ((i & 0x07) << 4) + (part & 0x0F)) : 0L;
                    String pId = ((bglCached && part < 4) || (maskCached && part < 2))
                            ? partId(box, id, layerPartSlot(bglCached, i, part), cachedLayerSuffix(idInfix, i, part))
                            : id + idInfix + i + "_" + part;
                    part++;
                    DisplayItem prevGrad = findPrevLayerItem(prevItems, bglPartKey, pId);
                    RenderMeta prevGradMeta = prevGrad != null ? prevGrad.meta() : null;
                    RenderHints gHints = scratchGradientEvents.hintsReuse(prevGrad != null ? prevGrad.hints() : null);
                    RenderMeta gradientMeta;
                    if (coversPaint) {
                        gradientMeta = reuseLayerMeta(prevGradMeta, clip, paintRadii, fontHVal, ox, oy, gHints);
                    } else {
                        if (imageMeta == null) {
                            ClipRect prevClip = prevGradMeta != null ? prevGradMeta.clipRect() : null;
                            ClipRect layerClip = intersectClip(elements, clip, paint, paintRadii, id);
                            if (prevClip != null && prevClip.equals(layerClip)) layerClip = prevClip;
                            imageMeta = reuseLayerMeta(prevGradMeta, layerClip, null, fontHVal, ox, oy, gHints);
                        } else if (prevGradMeta != null) {
                            imageMeta = reuseLayerMeta(prevGradMeta, imageMeta.clipRect(), null, fontHVal, ox, oy, gHints);
                        } else {
                            imageMeta = imageMeta.withHints(gHints);
                        }
                        gradientMeta = imageMeta;
                    }
                    addElement(elements, id, pId, ElementType.RECT, gx0, gy0, gx1 - gx0, gy1 - gy0, "",
                            0, 0, scratchGradientEvents, 0, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, gradientMeta, bglPartKey);
                }
            }
        }
    }

    /**
     * {@code border-image-source}（{@code url()}）があれば、9 つ以内の画像の項目（{@link BorderImage} の区画）として出す。
     * 画像の大きさが分からない（読み込み前・SVG・失敗）ときは何もせず false（通常の枠線を描く）。
     * 項目は {@code crop:} の src で元の画像の一部を指すので、どのバックエンドも画像の項目としてそのまま描ける。
     * {@code border-radius} は枠画像を切らない（仕様どおり）。
     */
    private boolean drawBorderImage(List<DisplayItem> elements, LayoutBox box, String id, ClipRect clip, ItemProps events,
                                    float fontHVal, float ox, float oy) {
        String url = BorderImage.sourceUrl(box.style);
        if (url == null || url.isEmpty() || SvgImage.looksLikeSvg(url)) return false;
        int[] size = PaintServices.imagePixelSize(url);
        if (size == null || size[0] <= 0 || size[1] <= 0) return false;
        List<BorderImage.Cell> cells = BorderImage.cells(box.style, size[0], size[1], bounds.x(box), bounds.y(box), bounds.w(box), bounds.h(box),
                new float[]{bounds.bT(box), bounds.bR(box), bounds.bB(box), bounds.bL(box)});
        if (cells.isEmpty()) return false;
        RenderMeta meta = new RenderMeta(clip, null, null, fontHVal, ox, oy, 1, false);
        int part = 0;
        int nodeHandle = box.node != null ? box.node.paintHandle() : 0;
        for (BorderImage.Cell c : cells) {
            long biKey = nodeHandle > 0 ? DisplayItem.keyOf(nodeHandle, DisplayItem.PART_BORDER_IMAGE_BASE + (part & 0x3F)) : 0L;
            addElement(elements, id, id + "_bi" + (part++), ElementType.IMAGE, ImageFitMode.FILL, c.dx(), c.dy(), c.dw(), c.dh(),
                    com.nishiyu.enderframelib.core.paint.CropSrc.of(c.sx(), c.sy(), c.sw(), c.sh(), url),
                    0, 0, events, 0, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, meta, biKey);
        }
        return true;
    }

    /** 角丸なら枠の領域だけを塗る 1 部品、そうでなければ辺ごとの帯。 */
    void drawBorder(List<DisplayItem> elements, String id, LayoutBox box, float rOff, ItemProps events,
                            BorderSpec border, RenderMeta meta, int transDur) {
        drawBorder(elements, id, box, rOff, events, border, meta, transDur, null);
    }

    void drawBorder(List<DisplayItem> elements, String id, LayoutBox box, float rOff, ItemProps events,
                            BorderSpec border, RenderMeta meta, int transDur, List<DisplayItem> prevItems) {
        LayoutBox legend = com.nishiyu.enderframelib.core.dom.node.ElementTraits.isFieldset(box.node.tag) ? box.renderedLegend() : null;
        if (legend != null && border.hasBorder()) {
            drawBorderAroundLegend(elements, id, box, legend, rOff, events, border, meta, transDur);
            return;
        }
        if (border.useRoundedBorder()) drawRoundedBorder(elements, id, box, events, border, meta, transDur, prevItems);
        else if (border.hasBorder()) drawStraightBorders(elements, id, box, rOff, events, border, meta, transDur);
    }

    /**
     * legend の後ろを空けた fieldset の枠線。空ける矩形は、横が legend の border box、縦が枠線の上端から
     * legend の下マージンの端と枠線の下端の低い方まで（HTML §15.3.13）。描画は矩形の切り抜きを持たないので、
     * 空ける矩形の左・右・下の 3 つのクリップで同じ枠線を描く（クリップは重ならないので半透明の色も二重にならない）。
     */
    void drawBorderAroundLegend(List<DisplayItem> elements, String id, LayoutBox box, LayoutBox legend, float rOff,
                                        ItemProps events, BorderSpec b, RenderMeta meta, int transDur) {
        float left = legend.x(), right = legend.x() + legend.w();
        float bottom = Math.max(legend.y() + legend.h() + legend.mB(), bounds.y(box) + b.bwTop());
        float bx = bounds.x(box), by = bounds.y(box), bw = bounds.w(box), bh = bounds.h(box);
        float x0 = bx - 1, y0 = by - 1, x1 = bx + bw + 1, y1 = by + bh + 1;
        float[][] regions = {{x0, y0, left, y1}, {right, y0, x1, y1}, {left, bottom, right, y1}};
        float[] widths = {b.bwTop(), b.bwRight(), b.bwBottom(), b.bwLeft()};
        int[] colors = {b.bcTop(), b.bcRight(), b.bcBottom(), b.bcLeft()};
        int nodeHandle = box.node != null ? box.node.paintHandle() : 0;
        for (int i = 0; i < regions.length; i++) {
            if (regions[i][2] <= regions[i][0] || regions[i][3] <= regions[i][1]) continue;
            ClipRect clip = PaintSupport.intersectClip(elements, meta.clipRect(), new BoxDecorations.Rect(regions[i][0], regions[i][1], // 連鎖に fieldset の空間の
                    regions[i][2] - regions[i][0], regions[i][3] - regions[i][1]), null, id); // 矩形を足す（連鎖の無い矩形はフレームに固定され、スクロールの節が動くと上の枠線が切れた。T2）
            if (b.useRoundedBorder()) {
                RoundedBorderSpec spec = new RoundedBorderSpec(widths[0], widths[1], widths[2], widths[3],
                        colors[0], colors[1], colors[2], colors[3], b.dashKind());
                RenderMeta ringMeta = new RenderMeta(clip, meta.borderRadii(), null,
                        meta.fontHeight(), 0f, 0f, meta.originX(), meta.originY(), 1, false, spec);
                long ringKey = nodeHandle > 0 ? DisplayItem.keyOf(nodeHandle, DisplayItem.PART_BORDER_ROUND_BASE + (i & 0x0F)) : 0L;
                addElement(elements, id, id + "_b_round_" + i, ElementType.ROUNDED_BORDER, bx, by, bw, bh, "", 0, 0x00000000,
                        events, 0, 1f, 0, 0, 1f, 1f, 0, transDur, AnimationSpec.NONE, ringMeta, ringKey);
            } else {
                RenderMeta clipped = new RenderMeta(clip, meta.borderRadii(), null,
                        meta.fontHeight(), meta.originX(), meta.originY(), 1, false);
                drawLines(elements, id, "_b_", bx, by, bw, bh, widths, b.styles(), colors, rOff,
                        events, clipped, transDur, true, nodeHandle);
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
        drawRoundedBorder(elements, id, box, events, b, meta, transDur, null);
    }

    void drawRoundedBorder(List<DisplayItem> elements, String id, LayoutBox box, ItemProps events,
                                   BorderSpec b, RenderMeta meta, int transDur, List<DisplayItem> prevItems) {
        int nodeHandle = box.node != null ? box.node.paintHandle() : 0;
        long borderKey = nodeHandle > 0 ? DisplayItem.keyOf(nodeHandle, DisplayItem.PART_BORDER) : 0L;
        String borderId = partId(box, id, 3, "_b_round");
        DisplayItem prevItem = findPrevLayerItem(prevItems, borderKey, borderId);
        RenderMeta prevMeta = prevItem != null ? prevItem.meta() : null;
        RoundedBorderSpec prevSpec = prevMeta != null ? prevMeta.roundedBorder() : null;
        RoundedBorderSpec spec = (prevSpec != null
                && Float.compare(prevSpec.widthTop(), b.bwTop()) == 0
                && Float.compare(prevSpec.widthRight(), b.bwRight()) == 0
                && Float.compare(prevSpec.widthBottom(), b.bwBottom()) == 0
                && Float.compare(prevSpec.widthLeft(), b.bwLeft()) == 0
                && prevSpec.colorTop() == b.bcTop()
                && prevSpec.colorRight() == b.bcRight()
                && prevSpec.colorBottom() == b.bcBottom()
                && prevSpec.colorLeft() == b.bcLeft()
                && prevSpec.dashKind() == b.dashKind())
                ? prevSpec
                : new RoundedBorderSpec(b.bwTop(), b.bwRight(), b.bwBottom(), b.bwLeft(),
                        b.bcTop(), b.bcRight(), b.bcBottom(), b.bcLeft(), b.dashKind());
        RenderHints bHints = events.hintsReuse(prevItem != null ? prevItem.hints() : null);
        RenderMeta ringMeta = (prevMeta != null
                && prevMeta.circleBorder() == null
                && prevMeta.roundedBorder() == spec
                && Float.compare(prevMeta.fontHeight(), meta.fontHeight()) == 0
                && prevMeta.baseline() == 0f
                && prevMeta.lineHeight() == 0f
                && Float.compare(prevMeta.originX(), meta.originX()) == 0
                && Float.compare(prevMeta.originY(), meta.originY()) == 0
                && prevMeta.itemCount() == 1
                && !prevMeta.interactiveState()
                && java.util.Objects.equals(prevMeta.clipRect(), meta.clipRect())
                && java.util.Objects.equals(prevMeta.borderRadii(), meta.borderRadii())
                && java.util.Objects.equals(prevMeta.hints(), bHints))
                ? prevMeta
                : new RenderMeta(meta.clipRect(), meta.borderRadii(), null,
                        meta.fontHeight(), 0f, 0f, meta.originX(), meta.originY(), 1, false, spec, bHints);
        addElement(elements, id, borderId, ElementType.ROUNDED_BORDER, bounds.x(box), bounds.y(box), bounds.w(box), bounds.h(box), "", 0, 0x00000000,
                events, 0, 1f, 0, 0, 1f, 1f, 0, transDur, AnimationSpec.NONE, ringMeta, borderKey);
    }

    void drawStraightBorders(List<DisplayItem> elements, String id, LayoutBox box, float rOff, ItemProps partsEvents, BorderSpec b, RenderMeta meta, int transDur) {
        float[] widths = {b.bwTop(), b.bwRight(), b.bwBottom(), b.bwLeft()};
        int[] colors = {b.bcTop(), b.bcRight(), b.bcBottom(), b.bcLeft()};
        drawLines(elements, id, "_b_", bounds.x(box), bounds.y(box), bounds.w(box), bounds.h(box), widths, b.styles(), colors, rOff, partsEvents, meta, transDur, false, box.node != null ? box.node.paintHandle() : 0);
    }

    private static final int[] BORDER_SIDE_PARTS = {
            DisplayItem.PART_BORDER_TOP,
            DisplayItem.PART_BORDER_RIGHT,
            DisplayItem.PART_BORDER_BOTTOM,
            DisplayItem.PART_BORDER_LEFT
    };

    /**
     * 矩形の内側に沿った 4 辺の線（枠線と輪郭線で共有）。辺ごとに線種で片に分ける（{@link BoxDecorations}）。
     * 上下の辺は幅いっぱい、左右の辺は高さいっぱいの帯で、角は重ねて塗る（従来どおり）。
     *
     * 片の id は {@code <id><prefix><辺>} と、2 つ目からは {@code _<番号>} を足したもの
     * （{@link DisplayItem#baseId()} がどちらも持ち主の id に戻す）。
     */
    void drawLines(List<DisplayItem> elements, String id, String prefix, float x, float y, float w, float h,
                           float[] widths, String[] styles, int[] colors, float rOff,
                           ItemProps events, RenderMeta meta, int transDur) {
        drawLines(elements, id, prefix, x, y, w, h, widths, styles, colors, rOff, events, meta, transDur, false, 0);
    }

    void drawLines(List<DisplayItem> elements, String id, String prefix, float x, float y, float w, float h,
                           float[] widths, String[] styles, int[] colors, float rOff,
                           ItemProps events, RenderMeta meta, int transDur, boolean numbered) {
        drawLines(elements, id, prefix, x, y, w, h, widths, styles, colors, rOff, events, meta, transDur, numbered, 0);
    }

    /** {@code numbered} なら片の id をすべて番号付きにする（1 つの箱が複数の矩形に線を描くインラインの断片）。 */
    void drawLines(List<DisplayItem> elements, String id, String prefix, float x, float y, float w, float h,
                           float[] widths, String[] styles, int[] colors, float rOff,
                           ItemProps events, RenderMeta meta, int transDur, boolean numbered, int nodeHandle) {
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
                String partId;
                long lineKey;
                if (numbered) {
                    int fid = fragIds.getAndIncrement();
                    partId = base + "_" + fid;
                    lineKey = nodeHandle > 0 ? DisplayItem.keyOf(nodeHandle, FRAG_PART_BASE | (fid & FRAG_PART_MASK)) : 0L;
                } else {
                    partId = k == 0 ? base : base + "_" + k;
                    int partOrdinal = BORDER_SIDE_PARTS[i] + (k & 0x03);
                    lineKey = nodeHandle > 0 ? DisplayItem.keyOf(nodeHandle, partOrdinal) : 0L;
                }
                addElement(elements, id, partId, ElementType.RECT, seg.x(), seg.y(), seg.w(), seg.h(),
                        "", 0, BoxDecorations.shade(colors[i], seg.shade()), events, radius, 1f, 0, 0, 1f, 1f, 0, transDur,
                        AnimationSpec.NONE, segMeta, lineKey);
            }
        }
    }

    static final class BorderSpec {
        private boolean hasBorder, isCircle, useRoundedBorder;
        private float bwTop, bwRight, bwBottom, bwLeft;
        private int bcTop, bcRight, bcBottom, bcLeft;
        private String[] styles;
        private Corners innerRadii;
        private int dashKind;

        BorderSpec set(boolean hasBorder, boolean isCircle, boolean useRoundedBorder,
                       float bwTop, float bwRight, float bwBottom, float bwLeft,
                       int bcTop, int bcRight, int bcBottom, int bcLeft,
                       String[] styles, Corners innerRadii, int dashKind) {
            this.hasBorder = hasBorder;
            this.isCircle = isCircle;
            this.useRoundedBorder = useRoundedBorder;
            this.bwTop = bwTop;
            this.bwRight = bwRight;
            this.bwBottom = bwBottom;
            this.bwLeft = bwLeft;
            this.bcTop = bcTop;
            this.bcRight = bcRight;
            this.bcBottom = bcBottom;
            this.bcLeft = bcLeft;
            this.styles = styles;
            this.innerRadii = innerRadii;
            this.dashKind = dashKind;
            return this;
        }

        boolean hasBorder() { return hasBorder; }
        boolean isCircle() { return isCircle; }
        boolean useRoundedBorder() { return useRoundedBorder; }
        float bwTop() { return bwTop; }
        float bwRight() { return bwRight; }
        float bwBottom() { return bwBottom; }
        float bwLeft() { return bwLeft; }
        int bcTop() { return bcTop; }
        int bcRight() { return bcRight; }
        int bcBottom() { return bcBottom; }
        int bcLeft() { return bcLeft; }
        String[] styles() { return styles; }
        Corners innerRadii() { return innerRadii; }
        int dashKind() { return dashKind; }

        int maxThickness() {
            return Math.round(Math.max(Math.max(bwTop, bwRight), Math.max(bwBottom, bwLeft)));
        }
    }
}
