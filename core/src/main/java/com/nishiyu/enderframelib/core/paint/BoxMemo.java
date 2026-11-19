package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.css.model.AnimationSpec;
import com.nishiyu.enderframelib.core.css.CssPropertyIndex;
import com.nishiyu.enderframelib.core.css.ColorParser;
import com.nishiyu.enderframelib.core.css.CssKeywords;
import com.nishiyu.enderframelib.core.css.model.Keyframes;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.css.CssSyntax;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.WritingModes;
import com.nishiyu.enderframelib.core.css.LengthResolver;
import com.nishiyu.enderframelib.core.layout.BlockLayoutEngine;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.ReplacedElements;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.css.model.AnimationSpec;
import com.nishiyu.enderframelib.core.paint.model.ClipRect;
import com.nishiyu.enderframelib.core.paint.model.Corners;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.TextShaper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.paint.model.*;
import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;
import com.nishiyu.enderframelib.core.css.model.TextAlign;
import java.util.concurrent.atomic.AtomicInteger;
import static com.nishiyu.enderframelib.core.paint.BoxAnimation.applyTransitionEvents;
import static com.nishiyu.enderframelib.core.paint.BoxAnimation.parseOriginProp;
import static com.nishiyu.enderframelib.core.paint.BoxAnimation.parseTransitionSpec;
import static com.nishiyu.enderframelib.core.paint.ControlPainter.isPlainRange;
import static com.nishiyu.enderframelib.core.paint.ControlPainter.isPlainToggle;
import static com.nishiyu.enderframelib.core.paint.ControlPainter.rangeData;
import static com.nishiyu.enderframelib.core.paint.PaintOrder.canvasBackgroundSource;
import static com.nishiyu.enderframelib.core.paint.PaintSupport.addElement;
import static com.nishiyu.enderframelib.core.paint.PaintSupport.alignOffset;
import static com.nishiyu.enderframelib.core.paint.PaintSupport.isInvisible;
import static com.nishiyu.enderframelib.core.paint.PaintSupport.normalizeClipRect;
import static com.nishiyu.enderframelib.core.paint.PaintSupport.parseFloatAttr;
import static com.nishiyu.enderframelib.core.paint.PaintSupport.putFontEvents;
import static com.nishiyu.enderframelib.core.paint.PaintSupport.resolveLiveInputValue;
import static com.nishiyu.enderframelib.core.paint.PaintSupport.roundRadii;
import com.nishiyu.enderframelib.core.diag.PipelineStage;
import com.nishiyu.enderframelib.core.diag.StageProfiler;
import com.nishiyu.enderframelib.core.paint.BoxAnimation.TransitionSpec;
import com.nishiyu.enderframelib.core.paint.BoxDecorationPainter.BorderSpec;
import com.nishiyu.enderframelib.core.paint.RenderTask;

/**
 * 箱の記憶。{@code generation} は最後に使われた描画の世代（古いものは描画の終わりに捨てる）。
 *
 * <p>箱の描画結果（表示項目）は、その箱の入力だけで決まる: 算出スタイル・位置と大きさ・文字の断片・属性・クリップ・
 * 親の id・祖先のスタイル。入力が前回と全部同じなら前回の項目をそのまま出す（{@link DisplayListBuilder} の
 * 「箱ごとの記憶」）。このクラスは記憶の値と、記憶と今の箱を比べる・記憶の項目を平行移動する純粋な関数を持つ。
 * A8 で {@code DisplayListBuilder} から切り出した。</p>
 */
final class BoxMemo {
static final int NUMS_LENGTH = 13;
    /**
     * 表示リストの項目は、記憶の位置から平行移動した位置にある（{@link #match} が 2 を返した箱。記憶そのものは動かさない）ときの動き。
     * 差分パッチは「箱の今の位置 − 記憶の位置」で動きを見るので、これを足さないと、リストの中で既に動いた分を二重に動かす。
     */
    int appliedDx, appliedDy;
    /** {@link #match} が 2 を返したときの浮動小数の移動量（差分パッチで記憶の基準を進めるのに使う）。 */
    float appliedExactDx, appliedExactDy;
    /** {@link #match} が 2 を返したときの親相対原点の差分。 */
    int appliedOriginDx, appliedOriginDy;
    boolean appliedFragOnly;
    boolean appliedNonUniform;
    /** 各項目に対応する丸める前の {@code (x, y, w, h)}（4 要素ごと。小数移動の平行移動判定用）。 */
    float[] itemCoords;
    int generation;
    /** 記憶を作ったときのビューポートの大きさと、項目がそれに依る箱か（ビューポート基準の単位・ルートの背景）。 */
    int rootW, rootH;
    boolean viewportSensitive;
    List<DisplayItem> items;
    com.nishiyu.enderframelib.core.css.ComputedStyle style;
    com.nishiyu.enderframelib.core.css.ComputedStyle firstLineStyle;
    com.nishiyu.enderframelib.core.css.ComputedStyle placeholderStyle;
    com.nishiyu.enderframelib.core.css.ComputedStyle[] ancestors;
    float[] nums;
    float scrollW, scrollH;
    ClipRect clip;
    String parentId;
    /** 記憶を作ったときの属性の版番号（属性の写しを持たずに、書き換えがあったかを見分ける）。 */
    int attrVersion;
    String displayText;
    Object shaped;
    Object[] fragRefs;
    float[] fragNums;
    String[] fragText;
    boolean canvasSource, interactive, suppressShadow;
    /** 記憶を作った（または変わっていないと確かめた）描画の開始時の通し番号と、そのときの親の箱。 */
    long epoch;
    LayoutBox parentBox;
    /** 位置だけが変わった作り直し（スクロール）で、項目を平行移動して使い回せる箱か。 */
    boolean translatable;
    /** 項目が NODE（箱の位置）と TEXT（断片の位置）だけ。箱が動かず断片だけが動くとき、TEXT だけを動かせば済む形。 */
    boolean nodeAndTextOnly;
    /** 項目が中身の大きさ（スクロール量）を持つ。中身が変わると、箱が動いただけでは使い回せない。 */
    boolean carriesContentExtent;
    /** 記憶を作ったときの親相対モードと、自身および親の NODE の丸め原点。 */
    boolean parentRelative;
    int originX, originY;
    int parentOriginX, parentOriginY;
    /** この箱を含む、保持された部分木。再利用されている間は、箱ごとに世代を書かなくても記憶は捨てられない。 */
    Chunk chunk;
    boolean itemsMutable;


    /**
     * 算出スタイルが値として同じか。再スタイルされた箱は宣言が同じでも新しいインスタンスになるので、同一性だけで比べると
     * 子孫まで巻き込んで外れる。描画が読むのは宣言と、文字の大きさ（自分とルート）だけ。
     */
    static boolean sameStyle(com.nishiyu.enderframelib.core.css.ComputedStyle a, com.nishiyu.enderframelib.core.css.ComputedStyle b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.fontSizePx() == b.fontSizePx() && a.rootFontSizePx() == b.rootFontSizePx() && com.nishiyu.enderframelib.core.css.ComputedStyle.sameDeclarations(a, b);
    }

    static float[] numsOf(LayoutBox b) {
        return fillNums(b, null);
    }

    static float[] fillNums(LayoutBox b, float[] reuse) {
        float[] a = reuse != null && reuse.length == NUMS_LENGTH ? reuse : new float[NUMS_LENGTH];
        a[0] = b.x; a[1] = b.y; a[2] = b.w; a[3] = b.h;
        a[4] = b.pL; a[5] = b.pT; a[6] = b.pR; a[7] = b.pB;
        a[8] = b.bL; a[9] = b.bT; a[10] = b.bR; a[11] = b.bB;
        a[12] = b.lineHeightPx;
        return a;
    }

    /** {@link #numsOf} の i 番目を、配列を作らずに返す（{@link #match} が箱ごとに配列を確保しないため）。 */
    static float numAt(LayoutBox b, int i) {
        return switch (i) {
            case 0 -> b.x; case 1 -> b.y; case 2 -> b.w; case 3 -> b.h;
            case 4 -> b.pL; case 5 -> b.pT; case 6 -> b.pR; case 7 -> b.pB;
            case 8 -> b.bL; case 9 -> b.bT; case 10 -> b.bR; case 11 -> b.bB;
            case 12 -> b.lineHeightPx;
            default -> throw new IndexOutOfBoundsException(i);
        };
    }

    static boolean sameNums(float[] a, float[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (Float.compare(a[i], b[i]) != 0) return false;
        return true;
    }

    /** クリップの値としての一致（連鎖の矩形が配列なので、{@code ClipChainPart} の equals が中身を見る）。 */
    static boolean sameClip(ClipRect a, ClipRect b) {
        return a == b || (a != null && a.equals(b));
    }

    /** クリップの連鎖（各持ち主のローカル空間の矩形と所有者）が同じか。ページ AABB だけ動いたクリップを再利用できるか見る。 */
    static boolean sameClipChain(ClipRect a, ClipRect b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        List<ClipChainPart> ca = a.chain(), cb = b.chain();
        int n = ca.size();
        if (n == 0 || n != cb.size()) return false;
        for (int i = 0; i < n; i++) {
            if (!ca.get(i).equals(cb.get(i))) return false;
        }
        return true;
    }

    /**
     * 祖先クリップの境界変更が、この箱の全描画項目の範囲の外側だけで起きているか。
     * どの項目の描かれる矩形にも触れない辺の変化なら、記憶している {@code clipRect} のまま画素一致する。
     */
    static boolean clipDifferenceOutsideItems(BoxMemo m, ClipRect newClip, LayoutBox box,
                                                      ScrollState scrollState,
                                                      int originX, int originY, int shiftX, int shiftY,
                                                      int[] itemRects) {
        ClipRect oldClip = m.clip;
        if (oldClip == null || newClip == null) return false;
        if (!java.util.Objects.equals(oldClip.ownerId(), newClip.ownerId())) return false;
        List<ClipChainPart> oldChain = oldClip.chain(), newChain = newClip.chain();
        if (oldChain.size() != newChain.size()) return false;
        boolean hasOwn = m.parentRelative && PaintOrder.boxHasOwnNode(box);
        int oldOriginDeltaX = hasOwn ? m.originX - originX : 0;
        int oldOriginDeltaY = hasOwn ? m.originY - originY : 0;
        if (!rectEdgesSafe(m.items, oldClip.minX(), oldClip.minY(), oldClip.maxX(), oldClip.maxY(),
                newClip.minX(), newClip.minY(), newClip.maxX(), newClip.maxY(),
                oldClip.radiusTopLeft(), oldClip.radiusTopRight(), oldClip.radiusBottomRight(), oldClip.radiusBottomLeft(),
                oldClip.radiusTopLeftV(), oldClip.radiusTopRightV(), oldClip.radiusBottomRightV(), oldClip.radiusBottomLeftV(),
                newClip.radiusTopLeft(), newClip.radiusTopRight(), newClip.radiusBottomRight(), newClip.radiusBottomLeft(),
                newClip.radiusTopLeftV(), newClip.radiusTopRightV(), newClip.radiusBottomRightV(), newClip.radiusBottomLeftV(),
                oldClip, originX, originY, oldOriginDeltaX, oldOriginDeltaY, shiftX, shiftY, itemRects)) {
            return false;
        }
        for (int ci = 0, cn = oldChain.size(); ci < cn; ci++) {
            ClipChainPart pa = oldChain.get(ci), pb = newChain.get(ci);
            if (pa.equals(pb)) continue;
            String oid = pa.ownerId();
            if (!java.util.Objects.equals(oid, pb.ownerId())) return false;
            float oAx = 0f, oAy = 0f, nAx = 0f, nAy = 0f;
            if (oid != null) {
                LayoutBox owner = null;
                boolean axisAlignedNoScroll = true;
                for (LayoutBox a = box.parent; a != null; a = a.parent) {
                    if (PaintOrder.hasMovingProps(a.style)
                            || (scrollState != null && a.node != null
                            && (scrollState.getScrollX(a.node) != 0f || scrollState.getScrollY(a.node) != 0f))) {
                        axisAlignedNoScroll = false;
                        break;
                    }
                    if (a.node != null && oid.equals(a.node.key())) {
                        owner = a;
                        break;
                    }
                }
                if (!axisAlignedNoScroll || owner == null) return false;
                oAx = pa.ownerOriginX();
                oAy = pa.ownerOriginY();
                nAx = pb.ownerOriginX();
                nAy = pb.ownerOriginY();
            }
            int len = pa.rectLength();
            if (len != pb.rectLength() || len < 8) return false;
            float aTLv = len >= 12 ? pa.rectAt(8) : 0f, aTRv = len >= 12 ? pa.rectAt(9) : 0f;
            float aBRv = len >= 12 ? pa.rectAt(10) : 0f, aBLv = len >= 12 ? pa.rectAt(11) : 0f;
            float bTLv = len >= 12 ? pb.rectAt(8) : 0f, bTRv = len >= 12 ? pb.rectAt(9) : 0f;
            float bBRv = len >= 12 ? pb.rectAt(10) : 0f, bBLv = len >= 12 ? pb.rectAt(11) : 0f;
            if (!rectEdgesSafe(m.items,
                    pa.rectAt(0) + oAx, pa.rectAt(1) + oAy, pa.rectAt(2) + oAx, pa.rectAt(3) + oAy,
                    pb.rectAt(0) + nAx, pb.rectAt(1) + nAy, pb.rectAt(2) + nAx, pb.rectAt(3) + nAy,
                    pa.rectAt(4), pa.rectAt(5), pa.rectAt(6), pa.rectAt(7), aTLv, aTRv, aBRv, aBLv,
                    pb.rectAt(4), pb.rectAt(5), pb.rectAt(6), pb.rectAt(7), bTLv, bTRv, bBRv, bBLv,
                    oldClip, originX, originY, oldOriginDeltaX, oldOriginDeltaY, shiftX, shiftY, itemRects)) {
                return false;
            }
        }
        return true;
    }

    static boolean isSubClipOf(ClipRect sub, ClipRect parent) {
        List<ClipChainPart> sc = sub.chain(), pc = parent.chain();
        int pn = pc.size();
        if (sc.size() <= pn) return false;
        for (int i = 0; i < pn; i++) {
            if (!sc.get(i).equals(pc.get(i))) return false;
        }
        return true;
    }

    static boolean rectEdgesSafe(List<DisplayItem> items,
                                         float oMinX, float oMinY, float oMaxX, float oMaxY,
                                         float nMinX, float nMinY, float nMaxX, float nMaxY,
                                         float oTL, float oTR, float oBR, float oBL,
                                         float oTLv, float oTRv, float oBRv, float oBLv,
                                         float nTL, float nTR, float nBR, float nBL,
                                         float nTLv, float nTRv, float nBRv, float nBLv,
                                         ClipRect oldClip, int originX, int originY,
                                         int oldOriginDeltaX, int oldOriginDeltaY, int shiftX, int shiftY,
                                         int[] itemRects) {
        if ((oTL != 0f || oTR != 0f || oBR != 0f || oBL != 0f || oTLv != 0f || oTRv != 0f || oBRv != 0f || oBLv != 0f
                || nTL != 0f || nTR != 0f || nBR != 0f || nBL != 0f || nTLv != 0f || nTRv != 0f || nBRv != 0f || nBLv != 0f)
                && (Float.compare(oTL, nTL) != 0 || Float.compare(oTR, nTR) != 0
                || Float.compare(oBR, nBR) != 0 || Float.compare(oBL, nBL) != 0
                || Float.compare(oTLv, nTLv) != 0 || Float.compare(oTRv, nTRv) != 0
                || Float.compare(oBRv, nBRv) != 0 || Float.compare(oBLv, nBLv) != 0)) {
            return false;
        }
        boolean diffMinX = Float.compare(oMinX, nMinX) != 0;
        boolean diffMinY = Float.compare(oMinY, nMinY) != 0;
        boolean diffMaxX = Float.compare(oMaxX, nMaxX) != 0;
        boolean diffMaxY = Float.compare(oMaxY, nMaxY) != 0;
        if (!diffMinX && !diffMinY && !diffMaxX && !diffMaxY) return true;
        float rL = Math.max(Math.max(oTL, oBL), Math.max(nTL, nBL));
        float rR = Math.max(Math.max(oTR, oBR), Math.max(nTR, nBR));
        float rT = Math.max(Math.max(oTLv > 0f ? oTLv : oTL, oTRv > 0f ? oTRv : oTR),
                Math.max(nTLv > 0f ? nTLv : nTL, nTRv > 0f ? nTRv : nTR));
        float rB = Math.max(Math.max(oBLv > 0f ? oBLv : oBL, oBRv > 0f ? oBRv : oBR),
                Math.max(nBLv > 0f ? nBLv : nBL, nBRv > 0f ? nBRv : nBR));
        float safeMinX = Math.max(oMinX, nMinX) + rL;
        float safeMinY = Math.max(oMinY, nMinY) + rT;
        float safeMaxX = Math.min(oMaxX, nMaxX) - rR;
        float safeMaxY = Math.min(oMaxY, nMaxY) - rB;
        int minShiftX = Math.min(oldOriginDeltaX, shiftX), maxShiftX = Math.max(oldOriginDeltaX, shiftX);
        int minShiftY = Math.min(oldOriginDeltaY, shiftY), maxShiftY = Math.max(oldOriginDeltaY, shiftY);
        for (int i = 0, n = items.size(); i < n; i++) {
            DisplayItem it = items.get(i);
            if (it.type() == ElementType.NODE) {
                // 箱自身のノードの範囲は親相対で、ここの境界検査に載らない。クリップが違うまま古い印を残すと
                // 子へ伝わるので、ノードがクリップを持つなら（ここへ来たのは箱のクリップが前と違うとき）「外側だけの差」とは言えない
                RenderMeta nodeMeta = it.meta();
                ClipRect nodeClip = nodeMeta != null ? nodeMeta.clipRect() : null;
                if (nodeClip != null) return false;
                continue;
            }
            if (it.baseTranslateX() != 0f || it.baseTranslateY() != 0f
                    || it.scaleX() != 1f || it.scaleY() != 1f || it.rotate() != 0f) {
                return false;
            }
            RenderMeta meta = it.meta();
            RenderHints h = meta != null ? meta.hints() : null;
            if (meta != null) {
                ClipRect mc = meta.clipRect();
                if (mc != null && !sameClip(mc, oldClip) && !isSubClipOf(mc, oldClip)) return false;
                if (h != null && h.skew() != 0f) return false;
            }
            float pad = (it.type() == ElementType.TEXT ? 4f : 1f) + (h != null && h.shadowBlur() > 0f ? 2f : 0f);
            float x0, y0, x1, y1;
            if (itemRects != null) {
                int base = i * 4;
                float ox0 = it.x() + oldOriginDeltaX, oy0 = it.y() + oldOriginDeltaY;
                float nx0 = itemRects[base], ny0 = itemRects[base + 1];
                float minXLocal = Math.min(ox0, nx0), minYLocal = Math.min(oy0, ny0);
                float maxXLocal = Math.max(ox0 + it.width(), nx0 + itemRects[base + 2]);
                float maxYLocal = Math.max(oy0 + it.height(), ny0 + itemRects[base + 3]);
                if (h != null && h.shadowShape() != null) {
                    ShadowShape ss = h.shadowShape();
                    minXLocal = Math.min(minXLocal, ss.x0() + oldOriginDeltaX);
                    minYLocal = Math.min(minYLocal, ss.y0() + oldOriginDeltaY);
                    maxXLocal = Math.max(maxXLocal, ss.x1() + oldOriginDeltaX);
                    maxYLocal = Math.max(maxYLocal, ss.y1() + oldOriginDeltaY);
                }
                x0 = minXLocal + originX - pad;
                y0 = minYLocal + originY - pad;
                x1 = maxXLocal + originX + pad;
                y1 = maxYLocal + originY + pad;
            } else {
                float ix0 = it.x(), iy0 = it.y(), ix1 = ix0 + it.width(), iy1 = iy0 + it.height();
                if (h != null && h.shadowShape() != null) {
                    ShadowShape ss = h.shadowShape();
                    ix0 = Math.min(ix0, ss.x0());
                    iy0 = Math.min(iy0, ss.y0());
                    ix1 = Math.max(ix1, ss.x1());
                    iy1 = Math.max(iy1, ss.y1());
                }
                x0 = ix0 + originX + minShiftX - pad;
                y0 = iy0 + originY + minShiftY - pad;
                x1 = ix1 + originX + maxShiftX + pad;
                y1 = iy1 + originY + maxShiftY + pad;
            }
            if (diffMinX && x0 < safeMinX) return false;
            if (diffMinY && y0 < safeMinY) return false;
            if (diffMaxX && x1 > safeMaxX) return false;
            if (diffMaxY && y1 > safeMaxY) return false;
        }
        return true;
    }

    static com.nishiyu.enderframelib.core.css.ComputedStyle[] ancestorStyles(LayoutBox box,
            com.nishiyu.enderframelib.core.css.ComputedStyle[] reuse) {
        int n = 0;
        for (LayoutBox a = box.parent; a != null; a = a.parent) n++;
        com.nishiyu.enderframelib.core.css.ComputedStyle[] out = reuse != null && reuse.length == n
                ? reuse : new com.nishiyu.enderframelib.core.css.ComputedStyle[n];
        int i = 0;
        for (LayoutBox a = box.parent; a != null; a = a.parent) out[i++] = a.style;
        return out;
    }

    static void syncParentRelPositionOnly(BoxMemo m, LayoutBox box, int curOx, int curOy, int curPox, int curPoy, float dx, float dy) {
        if (dx == 0f && dy == 0f && curOx == m.originX && curOy == m.originY && curPox == m.parentOriginX && curPoy == m.parentOriginY
                && (m.fragNums == null || box.inlineFragments.isEmpty() || (box.inlineFragments.get(0).x == m.fragNums[0] && box.inlineFragments.get(0).y == m.fragNums[1]))) {
            return;
        }
        m.nums[0] = box.x;
        m.nums[1] = box.y;
        m.originX = curOx;
        m.originY = curOy;
        m.parentOriginX = curPox;
        m.parentOriginY = curPoy;
        if (m.fragNums != null && !box.inlineFragments.isEmpty()) {
            for (int j = 0, fn = box.inlineFragments.size(); j < fn && j * 8 + 1 < m.fragNums.length; j++) {
                LayoutBox.InlineFragment f = box.inlineFragments.get(j);
                m.fragNums[j * 8] = f.x;
                m.fragNums[j * 8 + 1] = f.y;
            }
        }
    }

    static boolean hasMovingShadowShape(List<DisplayItem> items, boolean parentRel, boolean fragOnly) {
        for (int i = 0, n = items.size(); i < n; i++) {
            DisplayItem it = items.get(i);
            if (parentRel && i == 0 && it.type() == ElementType.NODE && it.hints().relativeOrigin()) continue;
            if (fragOnly && !movesWithFragment(it)) continue;
            RenderMeta meta = it.meta();
            if (meta != null && meta.hints() != null && meta.hints().shadowShape() != null) return true;
        }
        return false;
    }

    static boolean movesWithFragment(DisplayItem it) {
        return it.type() == ElementType.TEXT || it.id().contains("_tsh");
    }

    static boolean nearHalfBoundary(float v, float base, float delta) {
        float diffFromHalf = Math.abs(Math.abs(v - (float) Math.rint(v)) - 0.5f);
        boolean exactGrid = com.nishiyu.enderframelib.core.kernel.LayoutUnit.isOnGrid(base)
                && com.nishiyu.enderframelib.core.kernel.LayoutUnit.isOnGrid(delta);
        if (diffFromHalf >= (exactGrid ? 1e-4f : 2e-3f)) return false;
        return diffFromHalf > 0f || !exactGrid;
    }

    static boolean isIntegerTranslation(float d) {
        return Math.abs(d - Math.rint(d)) <= 1e-3f;
    }

    /**
     * 親相対モードで、局所座標（箱の原点からの相対）は変わらず、親に対する {@code NODE} 位置・スクロール寸法・
     * クリップ AABB のみが変わった箱の記憶を再利用する。部品 {@code DisplayItem} の作り直しを避け、必要な項目だけを差し替える。
     */
    static void appendNodePatched(List<DisplayItem> out, BoxMemo m, LayoutBox box, ClipRect newClip,
                                          int newRelX, int newRelY, int curOx, int curOy, int curPox, int curPoy,
                                          boolean relPosChanged, boolean scrollChanged, boolean clipChanged,
                                          int partDx, int partDy, int[] itemRects,
                                          float fracDx, float fracDy, boolean fracFragOnly) {
        List<DisplayItem> items = m.items;
        int n = items.size();
        boolean nodePosChanged = n > 0 && items.get(0).type() == ElementType.NODE
                && (relPosChanged || items.get(0).x() != newRelX || items.get(0).y() != newRelY);
        boolean hasGradMove = !fracFragOnly && (fracDx != 0f || fracDy != 0f);
        boolean patchItems = nodePosChanged || scrollChanged || clipChanged || partDx != 0 || partDy != 0
                || itemRects != null || (hasGradMove && carriesShaderGradient(items));
        boolean hasOwnNode = PaintOrder.boxHasOwnNode(box);
        int subOx = hasOwnNode ? curOx : curPox;
        int subOy = hasOwnNode ? curOy : curPoy;
        float subShiftX = (hasOwnNode || fracFragOnly) ? 0f : fracDx;
        float subShiftY = (hasOwnNode || fracFragOnly) ? 0f : fracDy;
        boolean ownSubClipMoved = hasOwnNode ? (curOx != m.originX || curOy != m.originY) : (curPox != m.parentOriginX || curPoy != m.parentOriginY || subShiftX != 0f || subShiftY != 0f);
        if (patchItems) {
            DisplayItem[] updatedArr = null;
            ClipRect lastOldClip = null;
            ClipRect lastNextClip = null;
            boolean haveLastClip = false;
            RenderMeta lastOldMeta = null;
            RenderMeta lastNextMeta = null;
            for (int i = 0; i < n; i++) {
                DisplayItem it = items.get(i);
                int nextX = it.x();
                int nextY = it.y();
                int nextW = it.width();
                int nextH = it.height();
                if (itemRects != null) {
                    int base = i * 4;
                    if (i == 0 && it.type() == ElementType.NODE) {
                        if (nodePosChanged) {
                            nextX = newRelX;
                            nextY = newRelY;
                        }
                        nextW = itemRects[base + 2];
                        nextH = itemRects[base + 3];
                    } else if (!fracFragOnly || movesWithFragment(it)) {
                        nextX = itemRects[base];
                        nextY = itemRects[base + 1];
                        nextW = itemRects[base + 2];
                        nextH = itemRects[base + 3];
                    }
                } else if (i == 0 && it.type() == ElementType.NODE) {
                    if (nodePosChanged) {
                        nextX = newRelX;
                        nextY = newRelY;
                    }
                } else if ((partDx != 0 || partDy != 0) && (!fracFragOnly || movesWithFragment(it))) {
                    nextX = it.x() + partDx;
                    nextY = it.y() + partDy;
                }
                RenderMeta meta = it.meta();
                RenderMeta nextMeta = meta;
                if (meta != null) {
                    if (meta == lastOldMeta) {
                        nextMeta = lastNextMeta;
                    } else {
                        ClipRect curMetaClip = meta.clipRect();
                        boolean updScroll = scrollChanged && meta.hints() != null && meta.hints().scrollable();
                        boolean isDirectSubClip = curMetaClip != null && !sameClip(curMetaClip, m.clip);
                        boolean updClip = curMetaClip != null && (clipChanged || (isDirectSubClip && ownSubClipMoved));
                        boolean updGrad = hasGradMove && it.type() != ElementType.NODE && meta.hints() != null && meta.hints().shaderGradient() != null;
                        if (updScroll || updClip || updGrad) {
                            ClipRect nextClip;
                            if (!updClip) {
                                nextClip = curMetaClip;
                            } else if (haveLastClip && curMetaClip == lastOldClip) {
                                nextClip = lastNextClip;
                            } else {
                                ClipRect targetBaseClip = clipChanged ? newClip : m.clip;
                                nextClip = isDirectSubClip
                                        ? PaintSupport.rebaseSubClip(curMetaClip, m.clip, targetBaseClip, subOx, subOy, subShiftX, subShiftY)
                                        : targetBaseClip;
                                lastOldClip = curMetaClip;
                                lastNextClip = nextClip;
                                haveLastClip = true;
                            }
                            RenderHints nextHints = meta.hints();
                            if (updScroll) nextHints = nextHints.withScroll(box.scrollWidth, box.scrollHeight);
                            if (updGrad) nextHints = nextHints.withShaderGradient(nextHints.shaderGradient().offset(fracDx, fracDy));
                            if (!sameClip(nextClip, curMetaClip) || nextHints != meta.hints()) {
                                nextMeta = new RenderMeta(nextClip, meta.borderRadii(), meta.circleBorder(), meta.fontHeight(),
                                        meta.baseline(), meta.lineHeight(), meta.originX(), meta.originY(), meta.itemCount(),
                                        meta.interactiveState(), meta.roundedBorder(), nextHints);
                            }
                            lastOldMeta = meta;
                            lastNextMeta = nextMeta;
                        }
                    }
                }
                DisplayItem res = it.withBoundsAndMeta(nextX, nextY, nextW, nextH, nextMeta);
                if (res != it) {
                    if (m.itemsMutable) {
                        items.set(i, res);
                    } else {
                        if (updatedArr == null) {
                            updatedArr = new DisplayItem[n];
                            for (int j = 0; j < i; j++) updatedArr[j] = items.get(j);
                        }
                        updatedArr[i] = res;
                    }
                } else if (updatedArr != null) {
                    updatedArr[i] = it;
                }
                out.add(res);
            }
            if (updatedArr != null) {
                m.items = java.util.Arrays.asList(updatedArr);
                m.itemsMutable = true;
            }
        } else {
            DisplayListBuilder.appendItems(out, items);
        }
        if ((fracDx != 0f || fracDy != 0f) && m.itemCoords != null && m.itemCoords.length == n * 4) {
            for (int i = 0; i < n; i++) {
                DisplayItem it = items.get(i);
                if (i == 0 && it.type() == ElementType.NODE) {
                    if (!it.hints().relativeOrigin()) {
                        m.itemCoords[0] = box.x - curPox;
                        m.itemCoords[1] = box.y - curPoy;
                    }
                    continue;
                }
                if (fracFragOnly && !movesWithFragment(it)) continue;
                m.itemCoords[i * 4] += fracDx;
                m.itemCoords[i * 4 + 1] += fracDy;
            }
        }
        if (m.fragNums != null && !box.inlineFragments.isEmpty()) {
            for (int j = 0, fn = box.inlineFragments.size(); j < fn && j * 8 + 1 < m.fragNums.length; j++) {
                LayoutBox.InlineFragment f = box.inlineFragments.get(j);
                m.fragNums[j * 8] = f.x;
                m.fragNums[j * 8 + 1] = f.y;
            }
        }
        m.nums[0] = box.x;
        m.nums[1] = box.y;
        m.originX = curOx;
        m.originY = curOy;
        m.parentOriginX = curPox;
        m.parentOriginY = curPoy;
        m.scrollW = box.scrollWidth;
        m.scrollH = box.scrollHeight;
        if (clipChanged) m.clip = newClip;
        m.appliedDx = m.appliedDy = 0;
        m.appliedExactDx = m.appliedExactDy = 0f;
        m.appliedOriginDx = m.appliedOriginDy = 0;
        m.appliedFragOnly = false;
        m.appliedNonUniform = false;
    }

    /**
     * 前回の項目を平行移動（一括 {@code (ix, iy)}、または {@code itemRects} に基づく項目ごとの丸め）し、
     * クリップを今のものに替えた写しを {@code out} へ追加する。
     * {@link ShadowShape} は小数座標を保持しているため {@code (dx, dy)} で進める。
     */
    static void appendTranslated(List<DisplayItem> out, BoxMemo m, ClipRect newClip,
                                         float dx, float dy, int ix, int iy, boolean textOnly, int[] itemRects,
                                         boolean parentRel, int newRelX, int newRelY, int newRelW, int newRelH,
                                         int curOx, int curOy, LayoutBox box, boolean scrollChanged, boolean clipChanged) {
        DisplayListBuilder.STAT_APPEND_TRANSLATED.increment();
        List<DisplayItem> items = m.items;
        boolean hasOwnNode = parentRel && PaintOrder.boxHasOwnNode(box);
        for (int i = 0, n = items.size(); i < n; i++) {
            DisplayItem it = items.get(i);
            boolean isRelNode = parentRel && i == 0 && it.type() == ElementType.NODE;
            boolean moves = !textOnly || movesWithFragment(it);
            int nx, ny, nw, nh;
            if (itemRects != null) {
                int base = i * 4;
                nx = itemRects[base];
                ny = itemRects[base + 1];
                nw = itemRects[base + 2];
                nh = itemRects[base + 3];
            } else if (isRelNode) {
                nx = newRelX;
                ny = newRelY;
                nw = newRelW;
                nh = newRelH;
            } else {
                int ox = moves ? ix : 0, oy = moves ? iy : 0;
                nx = it.x() + ox;
                ny = it.y() + oy;
                nw = it.width();
                nh = it.height();
            }
            RenderMeta meta = it.meta();
            if (meta != null) {
                RenderHints h = meta.hints();
                if (scrollChanged && h != null && h.scrollable()) {
                    h = h.withScroll(box.scrollWidth, box.scrollHeight);
                }
                if (!isRelNode && h != null && h.shadowShape() != null && (moves && (dx != 0 || dy != 0))) {
                    h = h.withShadowShape(h.shadowShape().offset(dx, dy));
                }
                if (!isRelNode && parentRel && h != null && h.shaderGradient() != null && (moves && (dx != 0 || dy != 0))) {
                    h = h.withShaderGradient(h.shaderGradient().offset(dx, dy));
                }
                ClipRect c = meta.clipRect();
                if (c != null) {
                    if (parentRel) {
                        LayoutBox pNode = PaintOrder.effectiveParentNodeBox(box, m.parentId);
                        int curPox = pNode != null ? Math.round(pNode.x) : 0;
                        int curPoy = pNode != null ? Math.round(pNode.y) : 0;
                        c = PaintSupport.rebaseSubClip(c, m.clip, newClip,
                                hasOwnNode ? curOx : curPox, hasOwnNode ? curOy : curPoy,
                                (hasOwnNode || !moves) ? 0f : dx, (hasOwnNode || !moves) ? 0f : dy);
                    } else {
                        c = newClip;
                    }
                }
                if (c != meta.clipRect() || (h != null && h != meta.hints())) {
                    meta = new RenderMeta(c, meta.borderRadii(), meta.circleBorder(), meta.fontHeight(), meta.baseline(),
                            meta.lineHeight(), meta.originX(), meta.originY(), meta.itemCount(), meta.interactiveState(),
                            meta.roundedBorder(), h != null ? h : meta.hints());
                }
            }
            out.add(new DisplayItem(it.id(), it.baseNodeId(), it.type(), nx, ny, nw, nh,
                    it.text(), it.color(), it.bgColor(), it.events(), it.radius(), it.opacity(), it.baseTranslateX(), it.baseTranslateY(),
                    it.scaleX(), it.scaleY(), it.rotate(), it.transitionDuration(), it.animDef(), it.fitMode(), meta, it.key()));
        }
    }

    /** 項目のどれかがスクロールの大きさ（中身の幅・高さ）を持つか。 */
    static boolean carriesContentExtent(List<DisplayItem> items) {
        for (int i = 0, n = items.size(); i < n; i++) {
            RenderMeta meta = items.get(i).meta();
            if (meta != null && meta.hints() != null && meta.hints().scrollable()) return true;
        }
        return false;
    }

    static boolean onlyNodeAndText(List<DisplayItem> items) {
        for (int i = 0, n = items.size(); i < n; i++) {
            ElementType t = items.get(i).type();
            if (t != ElementType.NODE && t != ElementType.TEXT) return false;
        }
        return true;
    }

    static boolean carriesShaderGradient(List<DisplayItem> items) {
        for (int i = 0, n = items.size(); i < n; i++) {
            RenderMeta meta = items.get(i).meta();
            if (meta != null && meta.hints() != null && meta.hints().shaderGradient() != null) return true;
        }
        return false;
    }

    static boolean isBoxOwnedDirectSubClip(ClipRect mc, ClipRect clip, String ownerKey) {
        if (ownerKey == null) return false;
        List<ClipChainPart> sc = mc.chain();
        List<ClipChainPart> pc = clip == null ? List.of() : clip.chain();
        int pn = pc.size();
        if (sc.size() != pn + 1) return false;
        for (int i = 0; i < pn; i++) {
            if (!sc.get(i).equals(pc.get(i))) return false;
        }
        return ownerKey.equals(sc.get(pn).ownerId());
    }

    static boolean survivesTranslation(LayoutBox box, ClipRect clip, List<DisplayItem> items, boolean parentRel) {
        String attachment = box.style.get(CssPropertyIndex.BACKGROUND_ATTACHMENT);
        if (attachment != null && attachment.contains("fixed")) return false;
        String ownerKey = box.node != null ? box.node.key() : null;
        for (int i = 0, n = items.size(); i < n; i++) {
            DisplayItem it = items.get(i);
            ElementType t = it.type();
            if (t != ElementType.NODE && t != ElementType.RECT && t != ElementType.TEXT && t != ElementType.ROUNDED_BORDER) return false;
            RenderMeta meta = it.meta();
            if (meta == null) continue;
            ClipRect mc = meta.clipRect();
            if (mc != null && !sameClip(mc, clip) && !(parentRel && isBoxOwnedDirectSubClip(mc, clip, ownerKey))) return false;
            RenderHints h = meta.hints();
            if (h.shaderGradient() != null && !parentRel) return false;
            LayerSpec layer = h.layer();
            if (layer != null && (layer.clip() != null || layer.backdropShape() != null)) return false;
        }
        return true;
    }

    /** 項目が記憶の位置から動いている分（{@link BoxMemo#appliedExactDx}）を、記憶の基準の位置へ取り込む（差分パッチの前に呼ぶ）。 */
    static void syncApplied(BoxMemo m) {
        float ax = m.appliedExactDx, ay = m.appliedExactDy;
        int odx = m.appliedOriginDx, ody = m.appliedOriginDy;
        if (ax == 0f && ay == 0f && odx == 0 && ody == 0 && m.appliedDx == 0 && m.appliedDy == 0) return;
        float pageDx = odx + ax, pageDy = ody + ay;
        if (!m.appliedFragOnly && m.nums != null && m.nums.length >= 2) {
            m.nums[0] += pageDx;
            m.nums[1] += pageDy;
        }
        m.originX += odx;
        m.originY += ody;
        if (m.fragNums != null) {
            for (int i = 0; i + 1 < m.fragNums.length; i += 8) {
                m.fragNums[i] += pageDx;
                m.fragNums[i + 1] += pageDy;
            }
        }
        if (m.itemCoords != null && m.items != null && m.itemCoords.length == m.items.size() * 4) {
            for (int i = 0, n = m.items.size(); i < n; i++) {
                if (m.appliedFragOnly && !movesWithFragment(m.items.get(i))) continue;
                m.itemCoords[i * 4] += ax;
                m.itemCoords[i * 4 + 1] += ay;
            }
        }
        m.appliedDx = m.appliedDy = 0;
        m.appliedExactDx = m.appliedExactDy = 0f;
        m.appliedOriginDx = m.appliedOriginDy = 0;
        m.appliedFragOnly = false;
        m.appliedNonUniform = false;
    }

    static List<DisplayItem> sliceCopy(BoxMemo reuse, List<DisplayItem> elements, int start) {
        int count = elements.size() - start;
        if (count <= 0) return List.of();
        if (count == 1) return List.of(elements.get(start));
        if (count == 2) return List.of(elements.get(start), elements.get(start + 1));
        if (reuse != null && reuse.itemsMutable && reuse.items != null && reuse.items.size() == count) {
            List<DisplayItem> dst = reuse.items;
            for (int i = 0; i < count; i++) dst.set(i, elements.get(start + i));
            return dst;
        }
        DisplayItem[] arr = new DisplayItem[count];
        for (int i = 0; i < count; i++) arr[i] = elements.get(start + i);
        return java.util.Arrays.asList(arr);
    }

    /**
     * 子パッチ（{@link ChildSlicePatch}）が追える寸法の変化か。⑦は後続の箱の位置、⑧は祖先の高さしか合わせないので、祖先の幅が変わった（中身で幅の決まる
     * flex アイテムの子を消すなど）・flex / grid / 表など（ブロックの流れ以外）の中で兄弟の寸法が変わったときは、古い寸法の項目が残る（§6-108。T3 の差分ファズ
     * nested の種 17）。レイアウトは済んでいるので、表示リストに触る前に記憶の寸法と比べて退く。
     */
    static boolean sizesFollowable(java.util.Map<LayoutBox, BoxMemo> memoOf, LayoutBox parentBox) {
        for (LayoutBox anc = parentBox; anc != null; anc = anc.parent) {
            BoxMemo am = memoOf.get(anc);
            if (am != null && am.nums != null && am.nums.length >= 4 && Float.compare(am.nums[2], anc.w) != 0) return false;
            if (anc.display == null || anc.display.isBlockContainer()) continue; // ブロックの流れでは兄弟の寸法は互いに響かない（float は計画で除いてある）
            for (LayoutBox c : anc.children) {
                BoxMemo cm = memoOf.get(c);
                if (cm != null && cm.nums != null && cm.nums.length >= 4 && (Float.compare(cm.nums[2], c.w) != 0 || Float.compare(cm.nums[3], c.h) != 0)) return false;
            }
        }
        return true;
    }
}
