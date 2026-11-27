package com.nishiyu.enderframelib.core.paint;

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
                            RenderMeta meta, long key) {
        int ox = 0, oy = 0;
        if (elements instanceof CaptureList cap) {
            ox = cap.originX;
            oy = cap.originY;
            if (ox != 0 || oy != 0) {
                x -= ox;
                y -= oy;
            }
        }
        int left = Math.round(x);
        int top = Math.round(y);
        int width = Math.round(x + w) - left;
        int height = Math.round(y + h) - top;
        RenderHints hints = events.hints();
        if ((ox != 0 || oy != 0) && hints != RenderHints.NONE) {
            if (hints.shadowShape() != null) hints = hints.withShadowShape(hints.shadowShape().offset(-ox, -oy));
            if (hints.shaderGradient() != null && !events.isShaderGradientLocal()) {
                hints = hints.withShaderGradient(hints.shaderGradient().offset(-ox, -oy));
            }
        }
        RenderMeta finalMeta = meta == null && hints == RenderHints.NONE ? null : (meta != null ? meta : RenderMeta.EMPTY).withHints(hints);
        long itemKey = key == 0L ? DisplayItem.keyOf(id) : key;
        // 差分パッチの再構築: 前の項目が塗り以外同じなら、作らずに塗りだけを書いて使い回す（B9 の M3b）。
        elements.add(new DisplayItem(id, baseNodeId, type, left, top, width, height, text, color, bgColor, events.attrs(), radius, opacity, tx, ty, sx, sy, rot, trans, animDef, fitMode, finalMeta, itemKey));
    }

    /**
     * 親相対座標のときの、現在の節の原点 {@code (originX, originY)} も保持する。
     */
    static final class CaptureList extends java.util.ArrayList<DisplayItem> {
        int originX, originY;

        CaptureList(int initialCapacity) {
            super(initialCapacity);
        }

        void resetForBuild() {
            clear();
            originX = 0;
            originY = 0;
        }

        void setOrigin(int ox, int oy) {
            this.originX = ox;
            this.originY = oy;
        }

    }

    static void addElement(List<DisplayItem> elements, String baseNodeId, String id, ElementType type, ImageFitMode fitMode,
                            float x, float y, float w, float h, String text,
                            int color, int bgColor, ItemProps events, int radius, float opacity,
                            float tx, float ty, float sx, float sy, float rot, int trans, AnimationSpec animDef,
                            RenderMeta meta) {
        addElement(elements, baseNodeId, id, type, fitMode, x, y, w, h, text, color, bgColor, events, radius, opacity, tx, ty, sx, sy, rot, trans, animDef, meta, 0L);
    }

    static void addElement(List<DisplayItem> elements, String baseNodeId, String id, ElementType type,
                            float x, float y, float w, float h, String text,
                            int color, int bgColor, ItemProps events, int radius, float opacity,
                            float tx, float ty, float sx, float sy, float rot, int trans, AnimationSpec animDef,
                            RenderMeta meta, long key) {
        addElement(elements, baseNodeId, id, type, ImageFitMode.FILL, x, y, w, h, text, color, bgColor, events, radius, opacity, tx, ty, sx, sy, rot, trans, animDef, meta, key);
    }

    static void addElement(List<DisplayItem> elements, String baseNodeId, String id, ElementType type,
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

    static ClipRect intersectClip(List<DisplayItem> elements, ClipRect parent, BoxDecorations.Rect area, Corners r, String ownerId) {
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
        int ox = 0, oy = 0;
        if (elements instanceof CaptureList cap) {
            ox = cap.originX;
            oy = cap.originY;
        }
        float[] chainOwn = ell
                ? new float[]{x0 - ox, y0 - oy, x1 - ox, y1 - oy, rx0, rx1, rx2, rx3, ry0, ry1, ry2, ry3}
                : new float[]{x0 - ox, y0 - oy, x1 - ox, y1 - oy, rx0, rx1, rx2, rx3};
        return BoxDecorations.intersectClipRect(
                parent, x0, y0, x1, y1, rx0, rx1, rx2, rx3, ry0, ry1, ry2, ry3,
                ell, ownerId, ClipRect.appendChain(parent, chainOwn, ownerId, ox, oy));
    }

    /**
     * 箱の {@code m.clip} が新しい {@code newBoxClip} に変わったとき（連鎖は同じ・ページ AABB だけ変化）、
     * 内側の部分クリップ（inset-shadow / 背景画像など）の連鎖を維持したままページ AABB だけを更新する。
     */
    static ClipRect rebaseSubClip(ClipRect partClip, ClipRect oldBoxClip, ClipRect newBoxClip, int curOx, int curOy) {
        return rebaseSubClip(partClip, oldBoxClip, newBoxClip, curOx, curOy, 0f, 0f);
    }

    /**
     * {@link #rebaseSubClip(ClipRect, ClipRect, ClipRect, int, int)} の拡張版。親のノードに対する相対位置が
     * 動いた（箱自身が自分のノードを持たない）とき、末尾の自箱クリップ座標も {@code (shiftX, shiftY)} だけ進める。
     */
    static ClipRect rebaseSubClip(ClipRect partClip, ClipRect oldBoxClip, ClipRect newBoxClip,
                                  int nextSubOx, int nextSubOy, float shiftX, float shiftY) {
        if (partClip == null) return null;
        if (oldBoxClip == partClip || (oldBoxClip != null && oldBoxClip.equals(partClip))) return newBoxClip;
        int baseLen = oldBoxClip == null ? 0 : oldBoxClip.chain().size();
        if (partClip.chain().size() == baseLen + 1) {
            ClipChainPart last = partClip.chain().get(baseLen);
            float[] r = last.rect();
            float[] nr;
            if (shiftX == 0f && shiftY == 0f) {
                nr = r;
            } else {
                nr = r.clone();
                nr[0] += shiftX;
                nr[1] += shiftY;
                nr[2] += shiftX;
                nr[3] += shiftY;
            }
            boolean ell = nr.length >= 12;
            return BoxDecorations.intersectClipRect(
                    newBoxClip, nr[0] + nextSubOx, nr[1] + nextSubOy, nr[2] + nextSubOx, nr[3] + nextSubOy,
                    nr[4], nr[5], nr[6], nr[7],
                    ell ? nr[8] : 0f, ell ? nr[9] : 0f, ell ? nr[10] : 0f, ell ? nr[11] : 0f,
                    ell, last.ownerId(), ClipRect.appendChain(newBoxClip, nr, last.ownerId(), nextSubOx, nextSubOy));
        }
        return partClip;
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
