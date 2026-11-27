package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.paint.scene.DrawOp;
import com.nishiyu.enderframelib.core.paint.scene.SceneRun;
import com.nishiyu.enderframelib.core.css.model.AnimationSpec;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssLength;
import com.nishiyu.enderframelib.core.css.InheritedStyle;
import com.nishiyu.enderframelib.core.css.model.CssKeywords;
import com.nishiyu.enderframelib.core.css.model.AnimationSpec;
import com.nishiyu.enderframelib.core.paint.model.ClipRect;
import com.nishiyu.enderframelib.core.paint.model.Corners;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.ImageFitMode;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;

import java.util.List;
import java.util.Map;
import com.nishiyu.enderframelib.core.paint.model.*;
import com.nishiyu.enderframelib.core.css.model.TextAlign;
import com.nishiyu.enderframelib.core.css.model.Visibility;

/** {@code DisplayListBuilder} の部品群が共通で使う、状態を持たない道具（要素の追加・クリップ・属性の読み取りなど）。 */
final class PaintSupport {
    /** flex / grid アイテムは位置指定が無くても z-index でコンテキストを作る（CSS Flexbox §5.4 / Grid §9.5）。 */
    static boolean isFlexOrGridItem(SceneAdapterBox box) {
        if (box.paintParent() == null || box.isAbsolute()) return false;
        return box.inFlexOrGrid();
    }

    /** 中身ごと 1 つの単位として描くインラインレベルの箱（inline-block / inline-flex など）と flex / grid アイテム。 */
    static boolean isAtomicInline(SceneAdapterBox box) {
        if (isFlexOrGridItem(box)) return true;
        return box.atomicInline();
    }

    /**
     * {@code visibility: hidden / collapse}。箱自身は描かずヒットテストにも出さないが、場所は取り、子孫は自分の値で描く。
     *
     * LIMITATION(制約): 表の行・列の {@code collapse} も hidden と同じで、場所を詰めない。README の "Not supported" に対応。
     */
    static boolean isInvisible(SceneAdapterBox box) {
        return box.style().typed().inherited.visibility != Visibility.VISIBLE;
    }

    /**
     * {@code empty-cells: hide} で、枠線も背景も描かない空のセルか（CSS 2.2 §17.6.1.1）。
     * {@code border-collapse: separate} の表のセルだけで、中身が空白だけ（子孫に要素も語も無い）のもの。
     */
    static boolean hidesEmptyCell(SceneAdapterBox box) {
        if (!box.tableCell() || box.collapsedBorders() != null) return false;
        String ec = box.style().typed().inheritedPaint.emptyCells;
        if (ec == null || !"hide".equalsIgnoreCase(ec.trim())) return false;
        return hasNoContent(box);
    }

    private static boolean hasNoContent(SceneAdapterBox box) {
        for (SceneRun f : box.runs()) {
            if (!f.isText() || !f.text().isBlank()) return false;
        }
        for (SceneAdapterBox c : box.paintChildren()) {
            if (c.displayNone()) continue;
            if (c.node() != null && c.node().tag != null && !c.node().tag.isEmpty() && !c.node().tag.startsWith("#")) return false;
            if (!hasNoContent(c)) return false;
        }
        return true;
    }

    /**
     * 描画要素を 1 つ命令として足す（M3b-4a）。座標は今の原点（{@link OpList#originX}）からの float のまま持ち、整数に丸めるのは
     * 組み立て（{@link OpComposer}）の 1 箇所だけ（軸 3）。丸めは幅と高さを個別にせず左右（上下）の辺を丸めて差を取る
     * （隣り合う箱の辺が同じ整数に落ち、端数の幅が並んでも隙間や重なりが出ない）。
     */
    static void addElement(OpList elements, String baseNodeId, String id, ElementType type, ImageFitMode fitMode,
                            float x, float y, float w, float h, String text,
                            int color, int bgColor, ItemProps events, int radius, float opacity,
                            float tx, float ty, float sx, float sy, float rot, int trans, AnimationSpec animDef,
                            RenderMeta meta, long key) {
        int ox = elements.originX, oy = elements.originY;
        if (ox != 0 || oy != 0) {
            x -= ox;
            y -= oy;
        }
        RenderHints hints = events.hints();
        if ((ox != 0 || oy != 0) && hints != RenderHints.NONE) {
            if (hints.shadowShape() != null) hints = hints.withShadowShape(hints.shadowShape().offset(-ox, -oy));
            if (hints.shaderGradient() != null && !events.isShaderGradientLocal()) {
                hints = hints.withShaderGradient(hints.shaderGradient().offset(-ox, -oy));
            }
        }
        RenderMeta finalMeta = meta == null && hints == RenderHints.NONE ? null : (meta != null ? meta : RenderMeta.EMPTY).withHints(hints);
        long itemKey = key == 0L ? DisplayItem.keyOf(id) : key;
        int flags = elements.flags();
        if (animDef == OpList.TEXT_COLOR_ANIM_FROM_ANCESTORS) {
            animDef = AnimationSpec.NONE;
            flags |= DrawOp.INHERIT_TEXT_COLOR_ANIM;
        }
        elements.add(new DrawOp.Item(type, id, baseNodeId, itemKey, x, y, w, h, text, color, bgColor, events.attrs(), radius, opacity,
                tx, ty, sx, sy, rot, trans, animDef, fitMode, finalMeta, flags));
    }

    static void addElement(OpList elements, String baseNodeId, String id, ElementType type, ImageFitMode fitMode,
                            float x, float y, float w, float h, String text,
                            int color, int bgColor, ItemProps events, int radius, float opacity,
                            float tx, float ty, float sx, float sy, float rot, int trans, AnimationSpec animDef,
                            RenderMeta meta) {
        addElement(elements, baseNodeId, id, type, fitMode, x, y, w, h, text, color, bgColor, events, radius, opacity, tx, ty, sx, sy, rot, trans, animDef, meta, 0L);
    }

    static void addElement(OpList elements, String baseNodeId, String id, ElementType type,
                            float x, float y, float w, float h, String text,
                            int color, int bgColor, ItemProps events, int radius, float opacity,
                            float tx, float ty, float sx, float sy, float rot, int trans, AnimationSpec animDef,
                            RenderMeta meta, long key) {
        addElement(elements, baseNodeId, id, type, ImageFitMode.FILL, x, y, w, h, text, color, bgColor, events, radius, opacity, tx, ty, sx, sy, rot, trans, animDef, meta, key);
    }

    static void addElement(OpList elements, String baseNodeId, String id, ElementType type,
                            float x, float y, float w, float h, String text,
                            int color, int bgColor, ItemProps events, int radius, float opacity,
                            float tx, float ty, float sx, float sy, float rot, int trans, AnimationSpec animDef,
                            RenderMeta meta) {
        addElement(elements, baseNodeId, id, type, ImageFitMode.FILL, x, y, w, h, text, color, bgColor, events, radius, opacity, tx, ty, sx, sy, rot, trans, animDef, meta, 0L);
    }

    static String resolveLiveInputValue(String nodeId, String attrValue) {
        return attrValue != null ? attrValue : "";
    }

    static int growRadius(int radius, float grow) {
        return radius <= 0 ? 0 : Math.max(0, Math.round(radius + grow));
    }

    static String partId(SceneAdapterBox box, String id, int slot, String suffix) {
        return box != null && box.node() != null && id.equals(box.node().key()) ? box.node().partKey(slot, suffix) : id + suffix;
    }

    private static final Object FONT_ATTRS_KEY = new Object();

    /**
     * 描画側が測定と同じ書体・字の位置を引けるように、書体の照合と字の送りに効く指定を載せる
     * （太さ・斜体・カーニング・字間）。算出スタイルだけで決まるため、不変マップをスタイルへメモ化する。
     */
    static void putFontEvents(SceneAdapterBox box, ItemProps events) {
        putFontEvents(box.style(), events);
    }

    /** 算出スタイルを直接渡す版（{@code ::first-line} の断片用）。 */
    static void putFontEvents(com.nishiyu.enderframelib.core.css.ComputedStyle style, ItemProps events) {
        Map<String, String> cached = style.paintMemoSelf(FONT_ATTRS_KEY, PaintSupport::computeFontAttrs);
        if (!cached.isEmpty()) events.putBaseAttrs(cached);
    }

    private static Map<String, String> computeFontAttrs(ComputedStyle style) {
        // 既定値（400 / normal / auto）は載せない。描画側は無ければ既定値として読む
        InheritedStyle inh = style.typed().inherited;
        String w = null, fs = null, fk = null, ls = null;
        int count = 0;
        if (inh.fontWeight != 400) { w = inh.fontWeight == 700 ? "bold" : Integer.toString(inh.fontWeight); count++; }
        if (inh.fontStyle != CssKeywords.FontStyle.NORMAL) { fs = inh.fontStyle.cssName(); count++; }
        if (inh.fontKerning != null && !inh.fontKerning.equals("auto")) { fk = inh.fontKerning; count++; }
        // 長さの解決は要素ごとには重いので、指定があるときだけ（初期値は normal = 0）
        if (CssLength.isDeclared(inh.letterSpacing)) {
            float letterSpacing = CssLength.resolve(inh.letterSpacing, inh.exprs, 0, inh.fontSize, inh.rootFontSizePx, 0, 0);
            if (letterSpacing != 0) { ls = String.valueOf(letterSpacing); count++; }
        }
        if (count == 0) return Map.of();
        if (count == 1) {
            if (w != null) return Map.of("font-weight", w);
            if (fs != null) return Map.of("font-style", fs);
            if (fk != null) return Map.of("font-kerning", fk);
            return Map.of("letter-spacing", ls);
        }
        Map<String, String> out = new java.util.HashMap<>(4);
        if (w != null) out.put("font-weight", w);
        if (fs != null) out.put("font-style", fs);
        if (fk != null) out.put("font-kerning", fk);
        if (ls != null) out.put("letter-spacing", ls);
        return Map.copyOf(out);
    }

    /** text-align が行に与える水平方向のずれ。 */
    static float alignOffset(TextAlign align, boolean rtl, float availableW, float lineW) {
        return switch (align.physical(rtl)) {
            case CENTER -> Math.max(0, (availableW - lineW) / 2);
            case RIGHT -> Math.max(0, availableW - lineW);
            default -> 0;
        };
    }

    /**
     * 描画領域（角丸 {@code r}）で親のクリップを狭める（{@link BoxDecorations#intersectClip}）。
     * 領域は要素 {@code ownerId} 自身の箱なので、その要素の変形に矩形も付いていく（{@link ClipRect#ownerId()}）。
     */
    static ClipRect intersectClip(ClipRect parent, BoxDecorations.Rect area, Corners r, String ownerId) {
        return intersectClip(null, parent, area, r, ownerId);
    }

    static ClipRect intersectClip(OpList elements, ClipRect parent, BoxDecorations.Rect area, Corners r, String ownerId) {
        float x0 = area.x(), y0 = area.y(), x1 = x0 + area.w(), y1 = y0 + area.h();
        float rx0 = 0f, rx1 = 0f, rx2 = 0f, rx3 = 0f, ry0 = 0f, ry1 = 0f, ry2 = 0f, ry3 = 0f;
        boolean ell = false;
        if (r != null) {
            rx0 = r.topLeft(); rx1 = r.topRight(); rx2 = r.bottomRight(); rx3 = r.bottomLeft();
            if (r.isElliptical()) {
                ell = true;
                ry0 = r.topLeftV(); ry1 = r.topRightV(); ry2 = r.bottomRightV(); ry3 = r.bottomLeftV();
            }
        }
        int ox = elements != null ? elements.originX : 0, oy = elements != null ? elements.originY : 0;
        float[] chainOwn = ell
                ? new float[]{x0 - ox, y0 - oy, x1 - ox, y1 - oy, rx0, rx1, rx2, rx3, ry0, ry1, ry2, ry3}
                : new float[]{x0 - ox, y0 - oy, x1 - ox, y1 - oy, rx0, rx1, rx2, rx3};
        return BoxDecorations.intersectClipRect(
                parent, x0, y0, x1, y1, rx0, rx1, rx2, rx3, ry0, ry1, ry2, ry3,
                ell, ownerId, ClipRect.appendChain(parent, chainOwn, ownerId, ox, oy));
    }

    /** 角丸の半径は描画側の整数値（{@link Corners}）なので、解いた長さをここで丸める。 */
    static int[] roundRadii(float[] radii) {
        int[] rounded = new int[radii.length];
        for (int i = 0; i < radii.length; i++) rounded[i] = Math.round(radii[i]);
        return rounded;
    }

    static ClipRect normalizeClipRect(float[] clipRect) {
        if (clipRect == null) return null;
        return ClipRect.fromArray(clipRect);
    }

    static float parseFloatAttr(Map<String, String> map, String key, float fallback) {
        String val = map.get(key);
        if (val == null || val.isEmpty()) return fallback;
        try {
            return Float.parseFloat(val.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
