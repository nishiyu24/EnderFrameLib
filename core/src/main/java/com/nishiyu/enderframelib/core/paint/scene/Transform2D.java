package com.nishiyu.enderframelib.core.paint.scene;

import com.nishiyu.enderframelib.core.box.DomValues;
import com.nishiyu.enderframelib.core.css.CssSyntax;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.css.VisualStyle;
import com.nishiyu.enderframelib.core.paint.model.Transforms;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@link SceneLayer} の静的な 2D 変形（アニメーション中の上書きは {@link DynamicSceneState} が持つ）。
 * 変形と {@code data-pan-* / data-zoom} のどちらも無ければ {@code null} を返し、通常の節での確保を省く。
 */
public record Transform2D(
        float tx,
        float ty,
        float sx,
        float sy,
        float rotate,
        float skew,
        float originX,
        float originY
) {
    /**
     * スタイルと属性の写しから変形を解く。恒等変換（変形も原点指定も無い）なら {@code null}。
     */
    public static Transform2D parse(Style st, DomValues values, float w, float h, int vw, int vh) {
        VisualStyle v = st.visual;
        String tr = v.translate, rot = v.rotate, sc = v.scale, tf = v.transform;
        boolean hasCssTransform = (tr != null && !tr.isEmpty()) || (rot != null && !rot.isEmpty())
                || (sc != null && !sc.isEmpty()) || (tf != null && !tf.isEmpty());
        Map<String, String> attrs = values != null ? values.attrs() : Map.of();
        float panX = parseFloatAttr(attrs, "data-pan-x", 0f);
        float panY = parseFloatAttr(attrs, "data-pan-y", 0f);
        float zoom = parseFloatAttr(attrs, "data-zoom", 1f);
        boolean hasPanZoom = panX != 0f || panY != 0f || zoom != 1f;
        if (!hasCssTransform && !hasPanZoom && v.transformOrigin == null) return null;

        float tx = panX, ty = panY, sx = zoom, sy = zoom, rotateDeg = 0f, skewDeg = 0f;
        if (hasCssTransform) {
            Transforms.Context ctx = new Transforms.Context(w, h, vw, vh, st.inherited.fontSize, st.inherited.rootFontSizePx);
            float[] trs = Transforms.toTrs(Transforms.compose(tr, rot, sc, tf, ctx));
            tx += trs[0];
            ty += trs[1];
            sx *= trs[2];
            sy *= trs[3];
            rotateDeg = trs[4];
            skewDeg = trs[5];
        }
        float ox = 0.5f, oy = 0.5f;
        if (v.transformOrigin != null) {
            List<String> parts = CssSyntax.words(v.transformOrigin.toLowerCase(Locale.ROOT));
            if (!parts.isEmpty()) ox = parseOriginProp(parts.get(0), w, st.inherited.fontSize, st.inherited.rootFontSizePx);
            if (parts.size() > 1) oy = parseOriginProp(parts.get(1), h, st.inherited.fontSize, st.inherited.rootFontSizePx);
        }
        if (tx == 0f && ty == 0f && sx == 1f && sy == 1f && rotateDeg == 0f && skewDeg == 0f && ox == 0.5f && oy == 0.5f) {
            return null;
        }
        return new Transform2D(tx, ty, sx, sy, rotateDeg, skewDeg, ox, oy);
    }

    private static float parseOriginProp(String p, float size, float fontPx, float rootFontPx) {
        float length = Float.NaN;
        if ("0".equals(p)) length = 0f;
        else if (p.endsWith("rem")) length = originNumber(p, 3) * rootFontPx;
        else if (p.endsWith("em")) length = originNumber(p, 2) * fontPx;
        else if (p.endsWith("px")) length = originNumber(p, 2);
        if (!Float.isNaN(length)) return size > 0 ? length / size : 0f;
        return switch (p) {
            case "left", "top" -> 0.0f;
            case "right", "bottom" -> 1.0f;
            case "center" -> 0.5f;
            default -> {
                if (p.endsWith("%")) {
                    try {
                        yield Float.parseFloat(p.substring(0, p.length() - 1)) / 100f;
                    } catch (NumberFormatException ignored) { /* 既定の中心 */ }
                }
                yield 0.5f;
            }
        };
    }

    private static float originNumber(String p, int unitLength) {
        try {
            return Float.parseFloat(p.substring(0, p.length() - unitLength));
        } catch (NumberFormatException e) {
            return Float.NaN;
        }
    }

    private static float parseFloatAttr(Map<String, String> map, String key, float fallback) {
        String val = map.get(key);
        if (val == null || val.isEmpty()) return fallback;
        try {
            return Float.parseFloat(val.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
