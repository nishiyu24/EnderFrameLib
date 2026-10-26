package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.core.css.CssKeywords;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.paint.model.AnimationSpec;
import com.nishiyu.enderframelib.core.paint.model.ClipRect;
import com.nishiyu.enderframelib.core.paint.model.Corners;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.ImageFitMode;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;

import java.util.List;
import java.util.Map;
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.paint.model.*;
import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;
import com.nishiyu.enderframelib.core.css.model.TextAlign;

/** {@link DisplayListBuilder} の部品群が共通で使う、状態を持たない道具（要素の追加・クリップ・属性の読み取りなど）。 */
final class PaintSupport {
    /** flex / grid アイテムは位置指定が無くても z-index でコンテキストを作る（CSS Flexbox §5.4 / Grid §9.5）。 */
    static boolean isFlexOrGridItem(LayoutBox box) {
        if (box.parent == null || box.isAbsolute) return false;
        LayoutBox.Display.Inner inner = box.parent.display.inner();
        return inner == LayoutBox.Display.Inner.FLEX || inner == LayoutBox.Display.Inner.GRID;
    }

    /** 中身ごと 1 つの単位として描くインラインレベルの箱（inline-block / inline-flex など）と flex / grid アイテム。 */
    static boolean isAtomicInline(LayoutBox box) {
        if (isFlexOrGridItem(box)) return true;
        return box.display.isInlineLevel() && box.display.inner() != LayoutBox.Display.Inner.FLOW;
    }

    /**
     * {@code visibility: hidden / collapse}。箱自身は描かずヒットテストにも出さないが、場所は取り、子孫は自分の値で描く。
     *
     * LIMITATION(制約): 表の行・列の {@code collapse} も hidden と同じで、場所を詰めない。README の "Not supported" に対応。
     */
    static boolean isInvisible(LayoutBox box) {
        return box.style.keywordOf(CssProperty.VISIBILITY, CssKeywords.Visibility.class) != CssKeywords.Visibility.VISIBLE;
    }

    /**
     * {@code empty-cells: hide} で、枠線も背景も描かない空のセルか（CSS 2.2 §17.6.1.1）。
     * {@code border-collapse: separate} の表のセルだけで、中身が空白だけ（子孫に要素も語も無い）のもの。
     */
    static boolean hidesEmptyCell(LayoutBox box) {
        if (box.display == null || box.display.inner() != LayoutBox.Display.Inner.TABLE_CELL || box.collapsedBorders != null) return false;
        if (!"hide".equalsIgnoreCase(String.valueOf(box.style.get("empty-cells")).trim())) return false;
        return hasNoContent(box);
    }

    private static boolean hasNoContent(LayoutBox box) {
        for (LayoutBox.InlineFragment f : box.inlineFragments) {
            if (!f.isText() || !f.text.isBlank()) return false;
        }
        for (LayoutBox c : box.children) {
            if (c.display != null && c.display.isNone()) continue;
            if (c.node != null && c.node.tag != null && !c.node.tag.isEmpty() && !c.node.tag.startsWith("#")) return false;
            if (!hasNoContent(c)) return false;
        }
        return true;
    }

    /**
     * 描画要素を 1 つ足す。レイアウトの float 座標を整数に丸めるのはここ 1 箇所だけ（軸 3）。
     *
     * 幅と高さを個別に丸めず、左右（上下）の辺をそれぞれ丸めて差を取る。こうすると
     * 隣り合う箱の辺が同じ整数に落ち、端数の幅が並んでも隙間や重なりが出ない。
     * 以前はレイアウトの各段が int に切り捨てていたため、丸め誤差が入れ子の深さだけ溜まっていた。
     */
    static void addElement(List<DisplayItem> elements, String baseNodeId, String id, ElementType type, ImageFitMode fitMode,
                            float x, float y, float w, float h, String text,
                            int color, int bgColor, ItemProps events, int radius, float opacity,
                            float tx, float ty, float sx, float sy, float rot, int trans, AnimationSpec animDef,
                            RenderMeta meta) {
        int left = Math.round(x);
        int top = Math.round(y);
        int width = Math.round(x + w) - left;
        int height = Math.round(y + h) - top;
        elements.add(new DisplayItem(id, baseNodeId, type, left, top, width, height, text, color, bgColor, events.attrs(), radius, opacity, tx, ty, sx, sy, rot, trans, animDef, fitMode,
                (meta == null && events.hints() == RenderHints.NONE ? null : (meta != null ? meta : RenderMeta.EMPTY).withHints(events.hints()))));
    }

    static void addElement(List<DisplayItem> elements, String baseNodeId, String id, ElementType type,
                            float x, float y, float w, float h, String text,
                            int color, int bgColor, ItemProps events, int radius, float opacity,
                            float tx, float ty, float sx, float sy, float rot, int trans, AnimationSpec animDef,
                            RenderMeta meta) {
        addElement(elements, baseNodeId, id, type, ImageFitMode.FILL, x, y, w, h, text, color, bgColor, events, radius, opacity, tx, ty, sx, sy, rot, trans, animDef, meta);
    }

    static String resolveLiveInputValue(String nodeId, String attrValue) {
        return attrValue != null ? attrValue : "";
    }

    static int growRadius(int radius, float grow) {
        return radius <= 0 ? 0 : Math.max(0, Math.round(radius + grow));
    }

    /**
     * 描画側が測定と同じ書体・字の位置を引けるように、書体の照合と字の送りに効く指定を載せる
     * （太さ・斜体・カーニング・字間）。
     */
    static void putFontEvents(LayoutBox box, ItemProps events) {
        // 既定値（400 / normal / auto）は載せない。描画側は無ければ既定値として読む
        String weight = box.style.get(CssProperty.FONT_WEIGHT);
        if (weight != null && !weight.equals("normal") && !weight.equals("400")) events.put("font-weight", weight);
        String fontStyle = box.style.get(CssProperty.FONT_STYLE);
        if (fontStyle != null && !fontStyle.equals("normal")) events.put("font-style", fontStyle);
        String kerning = box.style.get(CssProperty.FONT_KERNING);
        if (kerning != null && !kerning.equals("auto")) events.put("font-kerning", kerning);
        // 長さの解決は要素ごとには重いので、指定があるときだけ（初期値は normal = 0）
        String spacing = box.style.get(CssProperty.LETTER_SPACING);
        if (spacing != null && !spacing.equals("normal") && !spacing.equals("0")) {
            float letterSpacing = box.style.length(CssProperty.LETTER_SPACING, 0, 0, 0);
            if (letterSpacing != 0) events.put("letter-spacing", String.valueOf(letterSpacing));
        }
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
        float[] own = {area.x(), area.y(), area.x() + area.w(), area.y() + area.h(), 0, 0, 0, 0};
        if (r != null) {
            own[4] = r.topLeft();
            own[5] = r.topRight();
            own[6] = r.bottomRight();
            own[7] = r.bottomLeft();
            if (r.isElliptical()) {
                own = java.util.Arrays.copyOf(own, 12);
                own[8] = r.topLeftV();
                own[9] = r.topRightV();
                own[10] = r.bottomRightV();
                own[11] = r.bottomLeftV();
            }
        }
        ClipRect merged = ClipRect.fromArray(BoxDecorations.intersectClip(parent == null ? null : parent.toArray(), own)).withOwner(ownerId);
        // 合成する前の 1 つ 1 つも持つ。持ち主はこの要素（自分の箱で切るクリップは、要素の変形に付いていく）
        java.util.List<ClipRect.Part> chain = new java.util.ArrayList<>(parent == null ? java.util.List.<ClipRect.Part>of() : parent.chain());
        chain.add(new ClipRect.Part(own, ownerId));
        return merged.withChain(chain);
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
