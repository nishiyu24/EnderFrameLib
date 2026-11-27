package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.paint.scene.SceneLayer;
import com.nishiyu.enderframelib.core.paint.scene.SceneRun;
import com.nishiyu.enderframelib.core.css.ColorParser;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.InheritedPaintStyle;
import com.nishiyu.enderframelib.core.css.InheritedStyle;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.css.WritingModes;
import com.nishiyu.enderframelib.core.css.model.AnimationSpec;
import com.nishiyu.enderframelib.core.paint.model.BlendMode;
import com.nishiyu.enderframelib.core.paint.model.ClipRect;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.FilterOp;
import com.nishiyu.enderframelib.core.paint.model.LayerSpec;
import com.nishiyu.enderframelib.core.paint.model.RenderHints;
import com.nishiyu.enderframelib.core.paint.model.RenderMeta;

import java.util.ArrayList;
import java.util.List;

import static com.nishiyu.enderframelib.core.paint.PaintSupport.addElement;
import static com.nishiyu.enderframelib.core.paint.PaintSupport.putFontEvents;

/**
 * 文字の箱の行（語の断片・部品の中の行）を文字の項目にする（M2c ②。旧 {@code DisplayListBuilder} から取り出した）。{@code text-shadow}・
 * {@code ::first-line}・縦書きの正立の字を扱う。部品の ID の通し番号・ビューポート・マスク中の影の抑止は {@link BoxItemPainter} と共有する。
 */
final class TextRunPainter {
    private final BoxItemPainter owner;

    TextRunPainter(BoxItemPainter owner) {
        this.owner = owner;
    }

    /**
     * 文字 1 行（断片）を足す。{@code text-shadow} があれば、後ろに書いた影から先に（下に）、ずらした影色の文字を出してから本体を出す。
     * ぼかしのある影は、錨（NODE）に {@code blur} のレイヤーを載せ、その中に影の文字を置く（ぼかしの σ は半径の半分）。
     * 影はクリックを受けず、色のアニメーション・トランジションも受けない。
     */
    private void addTextRun(OpList elements, SceneLayer box, com.nishiyu.enderframelib.core.css.ComputedStyle effStyle,
                            String id, float x, float y, float w, float h, String text, int color, int bgColor,
                            ItemProps events, RenderMeta meta, int transDur, AnimationSpec colorAnim, List<BoxDecorations.Shadow> shadows) {
        if (effStyle != null && WritingModes.usesUpright(effStyle) && hasUprightChar(effStyle, text)) {
            addVerticalTextRuns(elements, box, effStyle, id, x, y, w, h, text, color, bgColor, events, meta, transDur, colorAnim, shadows);
            return;
        }
        emitTextRun(elements, box, effStyle, id, x, y, w, h, text, color, bgColor, events, meta, transDur, colorAnim, shadows, 0f);
    }

    private static boolean hasUprightChar(ComputedStyle style, String text) {
        WritingModes.Orientation o = WritingModes.orientationOf(style);
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (WritingModes.isUpright(o, cp)) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    /**
     * 縦書きの文字 1 行（論理の横一列）を、寝かせたままの塊（ラテン文字など）と、正立させる字（かな・漢字・全角など。1 字ずつ）に分けて出す。
     * 縦書きの根は内容ごと 90° 回って描かれるので、正立させる字は自分の中心まわりに -90° 回して回転を打ち消す。
     * 各塊の幅は書体の送り幅で測り、行の幅 {@code w} に合うよう等倍して配る。
     */
    private void addVerticalTextRuns(OpList elements, SceneLayer box, com.nishiyu.enderframelib.core.css.ComputedStyle effStyle,
                                     String id, float x, float y, float w, float h, String text,
                                     int color, int bgColor, ItemProps events, RenderMeta meta, int transDur, AnimationSpec colorAnim,
                                     List<BoxDecorations.Shadow> shadows) {
        WritingModes.Orientation o = WritingModes.orientationOf(effStyle);
        List<String> runs = new ArrayList<>();
        List<Boolean> upright = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean curUpright = false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            boolean up = WritingModes.isUpright(o, cp);
            if (cur.length() > 0 && (up != curUpright || up)) {
                runs.add(cur.toString());
                upright.add(curUpright);
                cur.setLength(0);
            }
            curUpright = up;
            cur.appendCodePoint(cp);
        }
        if (cur.length() > 0) {
            runs.add(cur.toString());
            upright.add(curUpright);
        }
        com.nishiyu.enderframelib.core.text.model.FontFace face =
                PaintServices.fontResolver().resolve(effStyle.typed().inherited.fontFamily);
        com.nishiyu.enderframelib.core.text.model.FontStyle font = effStyle.facts().fontStyle;
        float[] adv = new float[runs.size()];
        float total = 0;
        for (int i = 0; i < adv.length; i++) {
            adv[i] = Math.max(0f, face.advance(runs.get(i), font));
            total += adv[i];
        }
        float scale = total > 0 ? w / total : 1f;
        float cx = x;
        for (int i = 0; i < adv.length; i++) {
            float rw = adv[i] * scale;
            emitTextRun(elements, box, effStyle, id, cx, y, rw, h, runs.get(i), color, bgColor, events, meta, transDur, colorAnim, shadows,
                    upright.get(i) ? (WritingModes.isTransposed(WritingModes.flowOf(effStyle)) ? 90f : -90f) : 0f);
            cx += rw;
        }
    }

    private void emitTextRun(OpList elements, SceneLayer box, com.nishiyu.enderframelib.core.css.ComputedStyle effStyle,
                             String id, float x, float y, float w, float h, String text, int color, int bgColor,
                             ItemProps events, RenderMeta meta, int transDur, AnimationSpec colorAnim, List<BoxDecorations.Shadow> shadows, float rot) {
        // vertical-lr は転置で置くので、文字は上下反転で鏡映を打ち消す
        float flipY = effStyle != null && WritingModes.isTransposed(WritingModes.flowOf(effStyle)) ? -1f : 1f;
        int nodeHandle = box.node != null ? box.node.paintHandle() : 0;
        for (int i = shadows.size() - 1; i >= 0; i--) {
            BoxDecorations.Shadow sh = shadows.get(i);
            if ((sh.color() >>> 24) == 0) continue;
            ItemProps shadowEvents = events.copy();
            shadowEvents.pointerEventsNone();
            boolean blurred = sh.blur() > 0f;
            if (blurred) {
                int anchorPart = owner.fragIds.getAndIncrement();
                String anchorId = id + "_tsh" + anchorPart;
                long anchorKey = nodeHandle > 0 ? DisplayItem.keyOf(nodeHandle, anchorPart) : 0L;
                ItemProps anchorEvents = new ItemProps();
                String ownerId = events.hints().parentId();
                if (ownerId != null) anchorEvents.parentId(ownerId);
                anchorEvents.pointerEventsNone();
                addElement(elements, id, anchorId, ElementType.NODE, x, y, w, h, "", 0, 0, anchorEvents, 0, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, meta, anchorKey);
                int at = elements.size() - 1;
                shadowEvents.parentId(anchorId);
                int shadowPart = owner.fragIds.getAndIncrement();
                long shadowKey = nodeHandle > 0 ? DisplayItem.keyOf(nodeHandle, shadowPart) : 0L;
                addElement(elements, id, id + "_txt_" + shadowPart, ElementType.TEXT, x + sh.offsetX(), y + sh.offsetY(), w, h, text,
                        sh.color(), 0, shadowEvents, 0, 1f, 0, 0, 1f, flipY, rot, 0, AnimationSpec.NONE, meta, shadowKey);
                LayerSpec spec = new LayerSpec(elements.size() - at, 1f, List.of(FilterOp.blur(sh.blur() / 2f)), BlendMode.NORMAL, false);
                elements.set(at, BoxItemPainter.withLayer(elements.get(at), spec));
            } else {
                int shadowPart = owner.fragIds.getAndIncrement();
                long shadowKey = nodeHandle > 0 ? DisplayItem.keyOf(nodeHandle, shadowPart) : 0L;
                addElement(elements, id, id + "_txt_" + shadowPart, ElementType.TEXT, x + sh.offsetX(), y + sh.offsetY(), w, h, text,
                        sh.color(), 0, shadowEvents, 0, 1f, 0, 0, 1f, flipY, rot, 0, AnimationSpec.NONE, meta, shadowKey);
            }
        }
        int mainPart = owner.fragIds.getAndIncrement();
        long mainKey = nodeHandle > 0 ? DisplayItem.keyOf(nodeHandle, mainPart) : 0L;
        addElement(elements, id, id + "_txt_" + mainPart, ElementType.TEXT, x, y, w, h, text, color, bgColor, events, 0, 1f, 0, 0, 1f, flipY, rot, transDur, colorAnim, meta, mainKey);
    }

    private ItemProps textPropsForStyle(com.nishiyu.enderframelib.core.css.ComputedStyle style, ItemProps partsEvents, int resolvedColor) {
        ItemProps props = partsEvents.copy();
        InheritedStyle inh = style.typed().inherited; InheritedPaintStyle paint = style.typed().inheritedPaint;
        props.put("font-weight", inh.fontWeight == 700 ? "bold"
                : inh.fontWeight == 400 ? "normal" : Integer.toString(inh.fontWeight));
        if (inh.fontFamily != null) props.fontFamily(inh.fontFamily);
        if (paint.textDecoration != null) props.put("text-decoration", paint.textDecoration);
        putFontEvents(style, props);
        float strokeWidth = com.nishiyu.enderframelib.core.css.TextStroke.widthPx(style);
        if (strokeWidth > 0f) {
            String colorValue = paint.webkitTextStrokeColor;
            int strokeColor = colorValue == null || colorValue.isBlank() || "currentcolor".equalsIgnoreCase(colorValue.trim())
                    ? resolvedColor : ColorParser.parseColor(colorValue);
            props.put("text-stroke", strokeWidth + " " + Integer.toHexString(strokeColor));
        }
        return props;
    }

    private List<BoxDecorations.Shadow> textShadowsForStyle(com.nishiyu.enderframelib.core.css.ComputedStyle style, int resolvedColor, ItemProps props) {
        InheritedStyle inh = style.typed().inherited; String shadowValue = style.typed().inheritedPaint.textShadow;
        if (shadowValue == null || shadowValue.isBlank() || "none".equalsIgnoreCase(shadowValue.trim())) return List.of();
        List<BoxDecorations.Shadow> list = owner.suppressTextShadow() ? List.of()
                : BoxDecorations.parseBoxShadows(shadowValue, resolvedColor, owner.rootW, owner.rootH, inh.fontSize, inh.rootFontSizePx)
                        .stream().filter(sh -> !sh.inset()).toList();
        if (list.isEmpty() && !owner.suppressTextShadow()) props.put("text-shadow", "true");
        return list;
    }

    private static RenderMeta textMeta(ClipRect clipRect, float fontHeight, float baseline, float lineHeight, float originX, float originY,
                                       RenderHints hints) {
        return new RenderMeta(clipRect, null, null, fontHeight, baseline, lineHeight, originX, originY, 1, false, null, hints);
    }

    /**
     * 文字の箱 {@code box} の断片（{@link SceneRun}）を文字の項目にする。断片の座標は原点の節からなので、原点の節の左上 {@code (opX, opY)} を
     * 足す（層の記憶は 0。M3b-4a からその命令に {@link com.nishiyu.enderframelib.core.paint.scene.DrawOp#BASE_ORIGIN_PARENT} の印を付ける）。
     */
    void drawTextNodeElement(OpList elements, SceneLayer box, float opX, float opY, String id, int color, int bgColor,
                             ClipRect normalizedClip, float fontHVal, float ox, float oy, ItemProps partsEvents,
                             int transDur, AnimationSpec colorAnim) {
        if (box.runs.length == 0) return;
        ItemProps baseTextEvents = null;
        List<BoxDecorations.Shadow> baseTextShadows = null;
        long savedBase = elements.saveOrigin();
        elements.baseOriginParent(); // 断片は原点の節の座標（M3b-4a）
        try {
            for (SceneRun frag : box.runs) {
                if (frag.text() == null || frag.text().isBlank()) continue;
                com.nishiyu.enderframelib.core.css.ComputedStyle fl = frag.firstLineStyle();
                if (fl != null) {
                    InheritedStyle flInh = fl.typed().inherited;
                    int flColor = fl.typed().inheritedPaint.hasColor ? fl.typed().inheritedPaint.color : color;
                    float flFontH = Math.max(1.0f, flInh.fontSize);
                    ItemProps flEvents = textPropsForStyle(fl, partsEvents, flColor);
                    List<BoxDecorations.Shadow> flShadows = textShadowsForStyle(fl, flColor, flEvents);
                    RenderMeta fragMeta = textMeta(normalizedClip, flFontH, frag.baseline(), frag.h(), ox, oy, flEvents.hints());
                    addTextRun(elements, box, fl, id, frag.x() + opX, frag.y() + opY, frag.w(), frag.h(), frag.text(), flColor, bgColor, flEvents, fragMeta, transDur, AnimationSpec.NONE, flShadows);
                } else {
                    if (baseTextEvents == null) {
                        baseTextEvents = textPropsForStyle(box.style, partsEvents, color);
                        baseTextShadows = textShadowsForStyle(box.style, color, baseTextEvents);
                    }
                    RenderMeta fragMeta = textMeta(normalizedClip, fontHVal, frag.baseline(), frag.h(), ox, oy, baseTextEvents.hints());
                    addTextRun(elements, box, box.style, id, frag.x() + opX, frag.y() + opY, frag.w(), frag.h(), frag.text(), color, bgColor, baseTextEvents, fragMeta, transDur, colorAnim, baseTextShadows);
                }
            }
        } finally {
            elements.restoreOrigin(savedBase);
        }
    }
}
