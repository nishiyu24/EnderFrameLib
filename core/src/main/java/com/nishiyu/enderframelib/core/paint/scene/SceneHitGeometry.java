package com.nishiyu.enderframelib.core.paint.scene;

import com.nishiyu.enderframelib.core.box.DomValues;
import com.nishiyu.enderframelib.core.css.CustomProperties;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.css.model.CssKeywords;
import com.nishiyu.enderframelib.core.css.model.Overflow;
import com.nishiyu.enderframelib.core.css.model.Visibility;
import com.nishiyu.enderframelib.core.dom.node.ElementTraits;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.media.ImageChoice;
import com.nishiyu.enderframelib.core.media.ImageSources;
import com.nishiyu.enderframelib.core.paint.model.ImageFitMode;
import com.nishiyu.enderframelib.core.replaced.ReplacedElements;
import com.nishiyu.enderframelib.core.scene3d.Scene3DTags;

import java.util.Map;

/**
 * {@link SceneHitTest} の幾何・要素別当たり判定・スクロールコンテナ列挙・局所座標変換（M3b-3）。
 */
final class SceneHitGeometry {
    private SceneHitGeometry() { }

    static SceneHit hitScrollbars(SceneLayer l, float lx, float ly, double px, double py, HitScratch s) {
        Style st = l.style != null ? l.style.typed() : null;
        if (st == null || st.box.scrollbarWidth == CssKeywords.ScrollbarWidth.NONE) return null;
        if (st.inheritedPaint.pointerEvents == CssKeywords.PointerEvents.NONE) return null;
        if (s.ctx.accept() != null && !s.ctx.accept().test(l)) return null;
        int bar = 4;
        float curX = s.dyn.scrollX(l), curY = s.dyn.scrollY(l);
        if (l.scrollWidth > l.w + 2f && l.w > 12f) {
            float tx = lx + l.bL + 2f, ty = ly + l.h - l.bB - bar - 2f;
            float tw = l.w - l.bL - l.bR - 4f;
            float thumbW = Math.round(Math.max(12f, tw * (l.w / l.scrollWidth)));
            float maxX = Math.max(1f, l.scrollWidth - l.w);
            float thumbX = tx + (tw - thumbW) * Math.clamp(curX / maxX, 0f, 1f);
            if (px >= thumbX && px <= thumbX + thumbW && py >= ty && py <= ty + bar) {
                return new SceneHit(l, px - thumbX, py - ty, "_sb_h", thumbX, ty, null);
            }
            if (px >= tx && px <= tx + tw && py >= ty && py <= ty + bar) {
                return new SceneHit(l, px - tx, py - ty, "_sb_ht", tx, ty, null);
            }
        }
        if (l.scrollHeight > l.h + 2f && l.h > 12f) {
            float tx = lx + l.w - l.bR - bar - 2f, ty = ly + l.bT + 2f;
            float th = l.h - l.bT - l.bB - 4f;
            float thumbH = Math.round(Math.max(12f, th * (l.h / l.scrollHeight)));
            float maxY = Math.max(1f, l.scrollHeight - l.h);
            float thumbY = ty + (th - thumbH) * Math.clamp(curY / maxY, 0f, 1f);
            if (px >= tx && px <= tx + bar && py >= thumbY && py <= thumbY + thumbH) {
                return new SceneHit(l, px - tx, py - thumbY, "_sb_v", tx, thumbY, null);
            }
            if (px >= tx && px <= tx + bar && py >= ty && py <= ty + th) {
                return new SceneHit(l, px - tx, py - ty, "_sb_vt", tx, ty, null);
            }
        }
        return null;
    }

    static SceneHit hitSelf(SceneLayer l, float lx, float ly, float originX, float originY,
                            double px, double py, HitScratch s) {
        if (l.anonymous() || l.node == null) return null;
        String tag = l.node.tag;
        if ("root".equals(tag)) return null;
        Style st = l.style != null ? l.style.typed() : null;
        if (st != null && (st.inheritedPaint.pointerEvents == CssKeywords.PointerEvents.NONE
                || st.inherited.visibility != Visibility.VISIBLE)) {
            return null;
        }
        if (l.isEmptyCellHidden()) return null;
        if (l.isText()) {
            DrawOps runs = l.ops();
            for (int i = runs.runCount() - 1; i >= 0; i--) {
                String t = runs.runText(i);
                if (t == null || t.isBlank()) continue;
                float rx = originX + runs.runX(i), ry = originY + runs.runY(i);
                double rxLoc = px - rx, ryLoc = py - ry;
                if (rxLoc >= 0 && rxLoc <= runs.runW(i) && ryLoc >= 0 && ryLoc <= runs.runH(i)) {
                    if (s.ctx.accept() != null && !s.ctx.accept().test(l)) return null;
                    return new SceneHit(l, rxLoc, ryLoc, null, rx, ry, runs.run(i));
                }
            }
            return null;
        }
        if (l.ops().runCount() > 0 && !ElementTraits.isNonFragmented(tag) && !ReplacedElements.isHost(tag)
                && l.stackingKind != StackingKind.ATOMIC && !ReplacedElements.isReplacedTag(tag)) {
            if (!isInteractiveOrOpaque(l, st)) return null;
            DrawOps runs = l.ops();
            for (int i = runs.runCount() - 1; i >= 0; i--) {
                if (runs.runIsText(i)) continue;
                float rx = originX + runs.runX(i), ry = originY + runs.runY(i);
                double rxLoc = px - rx, ryLoc = py - ry;
                if (rxLoc >= 0 && rxLoc <= runs.runW(i) && ryLoc >= 0 && ryLoc <= runs.runH(i)) {
                    if (s.ctx.accept() != null && !s.ctx.accept().test(l)) return null;
                    return new SceneHit(l, rxLoc, ryLoc, null, rx, ry, runs.run(i));
                }
            }
            return null;
        }
        if (l.w <= 0f || l.h <= 0f) return null;
        float hitY = ly, hitH = l.h;
        if (ElementTraits.isFieldset(tag)) {
            SceneLayer legend = renderedLegend(l);
            if (legend != null) {
                float shift = Math.max(0f, (legend.h - l.bT) / 2f);
                hitY = ly + shift;
                hitH = Math.max(0f, l.h - shift);
            }
        }
        double localX = px - lx, localY = py - hitY;
        if (localX < 0 || localX > l.w || localY < 0 || localY > hitH) return null;
        if (!isInteractiveOrOpaque(l, st)) return null;
        if (ElementTraits.isImage(tag) && l.values().has("data-hit-alpha") && s.ctx.alphaHitTest() != null && st != null) {
            float iw = Math.max(0f, l.w - l.bL - l.bR), ih = Math.max(0f, l.h - l.bT - l.bB);
            double ix = px - (lx + l.bL), iy = py - (ly + l.bT);
            if (ix < 0 || ix > iw || iy < 0 || iy > ih) return null;
            ImageChoice chosen = ImageSources.forImg(l.values().attrs(), (int) s.dyn.viewportW, (int) s.dyn.viewportH, 1.0f);
            String src = Style.or(st.rare.src, chosen != null ? chosen.url() : l.values().attr("src", ""));
            ImageFitMode fit = ImageFitMode.fromString(Style.or(st.rare.objectFit, "fill"));
            int threshold = parseAlphaThreshold(l.values().attr("data-hit-alpha"));
            if (!s.ctx.alphaHitTest().isOpaque(l, src, threshold, ix, iy, iw, ih, fit, st.visual.objectPosition, st.inherited.fontSize)) {
                return null;
            }
        }
        if (s.ctx.accept() != null && !s.ctx.accept().test(l)) return null;
        return new SceneHit(l, localX, localY, null, lx, hitY, null);
    }

    private static SceneLayer renderedLegend(SceneLayer fieldset) {
        SceneChildren sc = fieldset.children;
        for (int k = 0; k < sc.chunks.length; k++) {
            for (SceneLayer c : sc.chunks[k].layers) {
                if (c.renderedLegend()) {
                    return c.display != null && c.display.isNone() ? null : c;
                }
            }
        }
        return null;
    }

    private static int parseAlphaThreshold(String v) {
        if (v == null || v.isBlank()) return 128;
        try {
            return Math.max(0, Math.min(255, Integer.parseInt(v.trim())));
        } catch (NumberFormatException e) {
            return 128;
        }
    }

    private static boolean isInteractiveOrOpaque(SceneLayer l, Style st) {
        String tag = l.node != null ? l.node.tag : "";
        if (ElementTraits.isFormControl(tag) || ElementTraits.isAnchor(tag)) return true;
        if (ElementTraits.isBuiltinReplacedMedia(tag) || ElementTraits.isMediaPlayer(tag)
                || ReplacedElements.isHost(tag) || Scene3DTags.isScene(tag)) {
            return true;
        }
        if (ElementTraits.isClickActivated(tag)) return true;
        DomValues values = l.values();
        if (ElementTraits.isLabel(tag) && values.has("for")) return true;
        if (values.has("title") || values.has("data-tooltip") || values.has("draggable") || values.hasEventHandlers()) return true;
        if (l.interactive()) return true;
        if (st != null) {
            if ((st.visual.backgroundColor & 0xFF000000) != 0) return true;
            CustomProperties custom = st.custom;
            if (custom != null) {
                for (Map.Entry<String, String> e : custom.asMap().entrySet()) {
                    String k = e.getKey();
                    if (k != null && k.regionMatches(true, 0, "on", 0, 2)) return true;
                }
            }
        }
        return false;
    }

    static boolean isVisible(SceneLayer l, float lx, float ly, DynamicSceneState dyn) {
        if (l.displayNone() || (l.node != null && ElementTraits.isOption(l.node.tag))) return false;
        return !l.contentVisibilityAuto() || dyn.nearViewport(lx, ly, l.w, l.h);
    }

    static boolean isValueControl(SceneLayer l) {
        return l.node != null && ElementTraits.isValueControl(l.node.tag);
    }

    static boolean withinChildClip(SceneLayer l, float lx, float ly, double px, double py) {
        String tag = l.node != null ? l.node.tag : "";
        if ("root".equals(tag) || "html".equals(tag) || "body".equals(tag) || "#text".equals(tag)) return true;
        if (l.clipsX() && (px < lx + l.bL || px > lx + l.w - l.bR)) return false;
        if (l.clipsY() && (py < ly + l.bT || py > ly + l.h - l.bB)) return false;
        return true;
    }

    static void toLayerSpace(SceneLayer l, float lx, float ly, double px, double py, HitScratch s) {
        Transform2D tr = l.transform;
        SceneHitTest.TransformOverride ov = s.ctx.transformOverride();
        boolean hasOverride = false;
        float[] a = null;
        if (ov != null) {
            a = s.anim6();
            hasOverride = ov.resolve(l, a);
        }
        if (!hasOverride && tr == null) {
            s.ptX = px;
            s.ptY = py;
            return;
        }
        float tx = hasOverride ? a[0] : tr.tx();
        float ty = hasOverride ? a[1] : tr.ty();
        float sx = hasOverride ? a[2] : tr.sx();
        float sy = hasOverride ? a[3] : tr.sy();
        float rot = hasOverride ? a[4] : tr.rotate();
        float skew = hasOverride ? a[5] : tr.skew();
        float ox = tr != null ? tr.originX() : 0.5f;
        float oy = tr != null ? tr.originY() : 0.5f;
        inverseInto(px, py, lx, ly, l.w, l.h, ox, oy, tx, ty, sx, sy, rot, skew, s);
    }

    private static void inverseInto(double px, double py, float lx, float ly, float w, float h,
                                    float ox, float oy, float tx, float ty, float sx, float sy,
                                    float rot, float skew, HitScratch out) {
        double cx = lx + w * ox;
        double cy = ly + h * oy;
        double dx = px - cx - tx;
        double dy = py - cy - ty;
        if (rot != 0f) {
            double rad = -Math.toRadians(rot);
            double cos = Math.cos(rad), sin = Math.sin(rad);
            double nx = dx * cos - dy * sin;
            double ny = dx * sin + dy * cos;
            dx = nx;
            dy = ny;
        }
        if (skew != 0f) dx -= Math.tan(Math.toRadians(skew)) * dy;
        if (sx != 0f && sx != 1f) dx /= sx;
        if (sy != 0f && sy != 1f) dy /= sy;
        out.ptX = dx + cx;
        out.ptY = dy + cy;
    }

    // ------------------------------------------------------------------
    // スクロールコンテナ列挙と localCoords
    // ------------------------------------------------------------------

    static void collectScrollRoot(SceneLayer root, double px, double py, HitScratch s) {
        toLayerSpace(root, 0f, 0f, px, py, s);
        double cpx = s.ptX, cpy = s.ptY;
        float cox = SceneQuery.nextOriginX(root, 0f, 0f, s.dyn);
        float coy = SceneQuery.nextOriginY(root, 0f, 0f, s.dyn);
        float pw = s.dyn.viewportW, ph = s.dyn.viewportH;
        collectScrollZ(root, 0f, 0f, cox, coy, 0f, 0f, pw, ph, cpx, cpy, SceneLayer.LAYER_POSZ | SceneLayer.LAYER_HOIST, s);
        collectScrollChildren(root, 0f, 0f, cox, coy, 0f, 0f, pw, ph, cpx, cpy, s);
        collectScrollZ(root, 0f, 0f, cox, coy, 0f, 0f, pw, ph, cpx, cpy, SceneLayer.LAYER_NEG, s);
    }

    private static void collectScrollLayer(SceneLayer l, float lx, float ly, float originX, float originY,
                                           float portX, float portY, float portW, float portH,
                                           double px, double py, HitScratch s) {
        if (!isVisible(l, lx, ly, s.dyn)) return;
        toLayerSpace(l, lx, ly, px, py, s);
        double cpx = s.ptX, cpy = s.ptY;
        if (!isValueControl(l) && withinChildClip(l, lx, ly, cpx, cpy)) {
            float cox = SceneQuery.nextOriginX(l, lx, originX, s.dyn);
            float coy = SceneQuery.nextOriginY(l, ly, originY, s.dyn);
            float npx = l.isScrollContainer() ? lx + l.bL : portX;
            float npy = l.isScrollContainer() ? ly + l.bT : portY;
            float npw = l.isScrollContainer() ? Math.max(0f, l.w - l.bL - l.bR) : portW;
            float nph = l.isScrollContainer() ? Math.max(0f, l.h - l.bT - l.bB) : portH;
            if (l.stackingKind == StackingKind.STACKING) {
                collectScrollZ(l, lx, ly, cox, coy, npx, npy, npw, nph, cpx, cpy, SceneLayer.LAYER_POSZ, s);
                collectScrollChildren(l, lx, ly, cox, coy, npx, npy, npw, nph, cpx, cpy, s);
                collectScrollZ(l, lx, ly, cox, coy, npx, npy, npw, nph, cpx, cpy, SceneLayer.LAYER_NEG, s);
            } else {
                collectScrollChildren(l, lx, ly, cox, coy, npx, npy, npw, nph, cpx, cpy, s);
            }
        }
        String tag = l.node != null ? l.node.tag : "";
        boolean scrollable = "html".equals(tag) || "body".equals(tag);
        if (!scrollable && l.style != null) {
            Overflow ox = l.style.overflowX(), oy = l.style.overflowY();
            scrollable = ox == Overflow.SCROLL || ox == Overflow.AUTO
                    || oy == Overflow.SCROLL || oy == Overflow.AUTO;
        }
        if (scrollable && !l.anonymous() && !"root".equals(tag)) {
            Style st = l.style != null ? l.style.typed() : null;
            if (st == null || (st.inheritedPaint.pointerEvents != CssKeywords.PointerEvents.NONE
                    && st.inherited.visibility == Visibility.VISIBLE)) {
                double localX = cpx - lx, localY = cpy - ly;
                if (localX >= 0 && localX <= l.w && localY >= 0 && localY <= l.h) {
                    s.addScrollHit(new SceneHit(l, localX, localY, null, lx, ly, null));
                }
            }
        }
    }

    private static void collectScrollZ(SceneLayer root, float rx, float ry, float cox, float coy,
                                       float px, float py, float pw, float ph,
                                       double cpx, double cpy, int mask, HitScratch s) {
        if ((root.children.propagatedLayers & mask) == 0) return;
        int base = s.zSize;
        SceneHitTest.collectZPass(root, rx, ry, cox, coy, px, py, pw, ph, cpx, cpy, mask, s);
        int end = s.zSize;
        if (end == base) return;
        try {
            s.sortZ(base, end);
            for (int i = end - 1; i >= base; i--) {
                HitScratch.ZNode e = s.z(i);
                collectScrollLayer(e.layer, e.lx, e.ly, e.originX, e.originY,
                        e.portX, e.portY, e.portW, e.portH, e.px, e.py, s);
            }
        } finally {
            s.zSize = base;
        }
    }

    private static void collectScrollChildren(SceneLayer container, float cx, float cy, float cox, float coy,
                                              float portX, float portY, float portW, float portH,
                                              double cpx, double cpy, HitScratch s) {
        SceneChildren sc = container.children;
        if (sc.size == 0) return;
        boolean cull = s.ctx.allowCull() && container.isOrigin();
        double relX = cpx - cox, relY = cpy - coy;
        for (int k = sc.chunks.length - 1; k >= 0; k--) {
            SceneChunk chunk = sc.chunks[k];
            if (chunk.layers.length == 0) continue;
            float chx = sc.chunkX[k], chy = sc.chunkY[k];
            if (cull && !chunk.hasDynamicBounds && (relX < chx + chunk.minX || relX > chx + chunk.maxX
                    || relY < chy + chunk.minY || relY > chy + chunk.maxY)) continue;
            for (int i = chunk.layers.length - 1; i >= 0; i--) {
                SceneLayer child = chunk.layers[i];
                if (child.stackingKind == StackingKind.STACKING) {
                    if (child.isTopLayer() || child.zIndex != 0) continue;
                }
                if (cull && !child.hasDynamicBounds() && child.isOrigin()
                        && (!child.isScrollContainer() || (child.clipsX() && child.clipsY()))) {
                    double lxRel = relX - chx - child.x, lyRel = relY - chy - child.y;
                    if (lxRel < child.boundsMinX || lxRel > child.boundsMaxX
                            || lyRel < child.boundsMinY || lyRel > child.boundsMaxY) continue;
                }
                SceneQuery.resolveChildPos(container, cx, cy, cox, coy, portX, portY, portW, portH, chx, chy, child, s.dyn, s);
                collectScrollLayer(child, s.resX, s.resY, cox, coy, portX, portY, portW, portH, cpx, cpy, s);
            }
        }
    }

    static SceneHit findLocalCoords(SceneLayer layer, float lx, float ly, float originX, float originY,
                                    float portX, float portY, float portW, float portH,
                                    double px, double py, HtmlNode target, HitScratch s) {
        toLayerSpace(layer, lx, ly, px, py, s);
        double cpx = s.ptX, cpy = s.ptY;
        if (!layer.anonymous() && layer.node == target) {
            return new SceneHit(layer, cpx - lx, cpy - ly, null, lx, ly, null);
        }
        SceneChildren sc = layer.children;
        if (sc.size == 0) return null;
        float cox = SceneQuery.nextOriginX(layer, lx, originX, s.dyn);
        float coy = SceneQuery.nextOriginY(layer, ly, originY, s.dyn);
        float npx = layer.isScrollContainer() ? lx + layer.bL : portX;
        float npy = layer.isScrollContainer() ? ly + layer.bT : portY;
        float npw = layer.isScrollContainer() ? Math.max(0f, layer.w - layer.bL - layer.bR) : portW;
        float nph = layer.isScrollContainer() ? Math.max(0f, layer.h - layer.bT - layer.bB) : portH;
        for (int k = 0; k < sc.chunks.length; k++) {
            SceneChunk c = sc.chunks[k];
            float chx = sc.chunkX[k], chy = sc.chunkY[k];
            for (SceneLayer child : c.layers) {
                SceneQuery.resolveChildPos(layer, lx, ly, cox, coy, npx, npy, npw, nph, chx, chy, child, s.dyn, s);
                SceneHit hit = findLocalCoords(child, s.resX, s.resY, cox, coy, npx, npy, npw, nph, cpx, cpy, target, s);
                if (hit != null) return hit;
            }
        }
        return null;
    }
}
