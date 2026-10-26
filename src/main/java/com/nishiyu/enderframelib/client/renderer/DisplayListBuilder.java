package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.core.css.ColorParser;
import com.nishiyu.enderframelib.core.css.CssKeywords;
import com.nishiyu.enderframelib.core.css.model.Keyframes;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.css.CssSyntax;
import com.nishiyu.enderframelib.core.css.LengthResolver;
import com.nishiyu.enderframelib.core.layout.BlockLayoutEngine;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.model.AnimationSpec;
import com.nishiyu.enderframelib.core.paint.model.ClipRect;
import com.nishiyu.enderframelib.core.paint.model.Corners;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.TextShaper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.paint.model.*;
import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;
import com.nishiyu.enderframelib.core.css.model.TextAlign;
import java.util.concurrent.atomic.AtomicInteger;
import static com.nishiyu.enderframelib.client.renderer.BoxAnimation.applyTransitionEvents;
import static com.nishiyu.enderframelib.client.renderer.BoxAnimation.parseOriginProp;
import static com.nishiyu.enderframelib.client.renderer.BoxAnimation.parseTransitionSpec;
import static com.nishiyu.enderframelib.client.renderer.ControlPainter.isPlainRange;
import static com.nishiyu.enderframelib.client.renderer.ControlPainter.isPlainToggle;
import static com.nishiyu.enderframelib.client.renderer.ControlPainter.rangeData;
import static com.nishiyu.enderframelib.client.renderer.PaintOrder.canvasBackgroundSource;
import static com.nishiyu.enderframelib.client.renderer.PaintSupport.addElement;
import static com.nishiyu.enderframelib.client.renderer.PaintSupport.alignOffset;
import static com.nishiyu.enderframelib.client.renderer.PaintSupport.isInvisible;
import static com.nishiyu.enderframelib.client.renderer.PaintSupport.normalizeClipRect;
import static com.nishiyu.enderframelib.client.renderer.PaintSupport.parseFloatAttr;
import static com.nishiyu.enderframelib.client.renderer.PaintSupport.putFontEvents;
import static com.nishiyu.enderframelib.client.renderer.PaintSupport.resolveLiveInputValue;
import static com.nishiyu.enderframelib.client.renderer.PaintSupport.roundRadii;
import com.nishiyu.enderframelib.client.renderer.BoxAnimation.TransitionSpec;
import com.nishiyu.enderframelib.client.renderer.BoxDecorationPainter.BorderSpec;
import com.nishiyu.enderframelib.client.renderer.PaintOrder.RenderTask;

public class DisplayListBuilder {
    // 単位なしの 0（例: translate(-50%, 0)）も受け付けるため単位は省略可能（省略時は px 扱い）

    private static final Set<String> NON_FRAGMENT_TAGS = Set.of(
            "#text", "img", "mc-fluid", "mc-entity", "mc-scene",
            "input", "select", "progress", "meter", "textarea", "canvas"
    );

    private final int rootW;

    private final int rootH;

    private final Map<String, Keyframes> keyframes;

    private final ScrollState scrollState;

    /** 要素の部品の id（{@code _txt_3} など）に振る通し番号。描画のたびに 0 から数える。 */
    private final AtomicInteger fragIds = new AtomicInteger();
    private final BoxAnimation anim;
    private final BoxDecorationPainter decor;
    private final ControlPainter controls;
    private final PaintOrder order;

    /** 背景をキャンバスに譲った要素（html か body）。自分の箱には背景色を描かない。 */
    private LayoutBox canvasBackgroundSource;

    public DisplayListBuilder(int rootW, int rootH, Map<String, Keyframes> keyframes, ScrollState scrollState) {
        this.rootW = rootW;
        this.rootH = rootH;
        this.keyframes = keyframes != null ? keyframes : Map.of();
        this.scrollState = scrollState;
        this.anim = new BoxAnimation(rootW, rootH, this.keyframes);
        this.decor = new BoxDecorationPainter(rootW, rootH, fragIds);
        this.controls = new ControlPainter(decor);
        this.order = new PaintOrder(rootW, rootH, scrollState, fragIds, this::drawBoxSelf,
                box -> (box.style.get(CssProperty.ANIMATION) != null || box.style.get(CssProperty.ANIMATION_NAME) != null)
                        && (anim.parseAnimationDef(box.style, box.w, box.h, 0, 0).animatedChannels() & AnimationSpec.CH_OPACITY) != 0);
    }

    /** 箱を描く順（スタッキングコンテキストの順）に並べた箱。 */
    public List<LayoutBox> paintOrder(LayoutBox root) {
        return order.paintOrder(root);
    }

    public List<DisplayItem> render(LayoutBox box) {
        List<DisplayItem> elements = new ArrayList<>();
        canvasBackgroundSource = null;

        if ("root".equals(box.node.tag)) {
            // キャンバスの背景はルート要素の背景、それが透明なら body の背景（CSS Backgrounds 3 §2.11.2）。
            // 背景を譲った要素は自分の箱には背景色を描かない
            int canvasBg = DEFAULT_CANVAS_COLOR;
            LayoutBox source = canvasBackgroundSource(box);
            if (source != null) {
                canvasBg = ColorParser.parseColor(source.style.getOrDefault(CssProperty.BACKGROUND_COLOR, "transparent"));
                canvasBackgroundSource = source;
            }
            if (canvasBg != 0x00000000) {
                addElement(elements, "bg_root", "bg_root", ElementType.RECT, box.x, box.y, Math.max(box.w, rootW), Math.max(box.h, rootH),
                        "", 0, canvasBg, new ItemProps(), 0, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, RenderMeta.EMPTY);
            }
        }

        // レイヤーに描く箱: 開始の印の次に出る錨の位置を覚え、終了の印でそこから出し終えた項目の数（span）を錨に書く
        java.util.ArrayDeque<int[]> openLayers = new java.util.ArrayDeque<>();
        java.util.ArrayDeque<LayerSpec> openSpecs = new java.util.ArrayDeque<>();
        java.util.ArrayDeque<LayoutBox> openBoxes = new java.util.ArrayDeque<>();
        for (RenderTask task : order.paintTasks(box)) {
            if (task.layerStart() != null) {
                openLayers.push(new int[]{elements.size()});
                openSpecs.push(task.layerStart());
                openBoxes.push(task.layerBox());
                continue;
            }
            if (task.layerEnd()) {
                int start = openLayers.pop()[0];
                LayerSpec spec = openSpecs.pop();
                LayoutBox layerBox = openBoxes.pop();
                if (elements.size() - start > 0) {
                    // マスクは、箱の中身を出し終えたあとに足す（描画側は maskFrom 以降をマスク用の描画先へ描く）
                    if (spec.wantsMask()) {
                        int maskFrom = elements.size() - start;
                        drawMaskItems(elements, layerBox);
                        spec = spec.withMaskFrom(elements.size() - start > maskFrom ? maskFrom : -1);
                    }
                    DisplayItem anchor = elements.get(start);
                    elements.set(start, withLayer(anchor, spec.withSpan(elements.size() - start)));
                }
                continue;
            }
            task.drawAction().accept(elements);
        }
        return elements;
    }

    /** 箱の {@code mask-image} のレイヤーを項目にして足す（背景画像と同じ手順。クリップは箱の外へは出さない）。 */
    private void drawMaskItems(List<DisplayItem> elements, LayoutBox box) {
        int[] xy = roundRadii(LengthResolver.cornerRadiiXY(box.style, box.w, box.h, rootW, rootH));
        Corners radii = Corners.ofEllipses(java.util.Arrays.copyOfRange(xy, 0, 4), java.util.Arrays.copyOfRange(xy, 4, 8));
        int[] rawRadii = {Math.min(radii.topLeft(), radii.topLeftV()), Math.min(radii.topRight(), radii.topRightV()),
                Math.min(radii.bottomRight(), radii.bottomRightV()), Math.min(radii.bottomLeft(), radii.bottomLeftV())};
        int color = box.style.color(CssProperty.COLOR, 0xFF000000);
        BorderSpec border = decor.parseBorderSpec(box, rawRadii, radii, color);
        float fontHVal = Math.max(1.0f, box.style.fontSizePx());
        decor.drawMaskLayers(elements, box, box.node.getId(), null, radii, border, fontHVal, 0.5f, 0.5f);
    }

    /** 錨の項目にレイヤーの指定を載せる。 */
    private static DisplayItem withLayer(DisplayItem anchor, LayerSpec spec) {
        RenderMeta meta = anchor.meta() != null ? anchor.meta() : RenderMeta.EMPTY;
        RenderMeta withLayer = meta.withHints(meta.hints().withLayer(spec));
        return new DisplayItem(anchor.id(), anchor.baseNodeId(), anchor.type(), anchor.x(), anchor.y(), anchor.width(), anchor.height(),
                anchor.text(), anchor.color(), anchor.bgColor(), anchor.events(), anchor.radius(), anchor.opacity(),
                anchor.translateX(), anchor.translateY(), anchor.scaleX(), anchor.scaleY(), anchor.rotate(),
                anchor.transitionDuration(), anchor.animDef(), anchor.fitMode(), withLayer);
    }

    /**
     * 背景の指定が無い文書のキャンバス。ゲームの上に重ねて描く文書は iframe と同じ扱いで透明
     * （ブラウザが白で塗るのは最上位の文書だけ）。html / body はパーサが必ず補う（W7）ので、背景を書かない HUD の断片も透明になる。
     * 以前は ua.css が body を白で塗り、body の無い文書は #111111 で塗りつぶしていた。
     */
    private static final int DEFAULT_CANVAS_COLOR = 0x00000000;

    private void drawBoxSelf(List<DisplayItem> elements, LayoutBox box, float[] clipRect, String parentId) {
        int[] xy = roundRadii(LengthResolver.cornerRadiiXY(box.style, box.w, box.h, rootW, rootH));
        Corners radii = Corners.ofEllipses(java.util.Arrays.copyOfRange(xy, 0, 4), java.util.Arrays.copyOfRange(xy, 4, 8));
        // 円しか持たない所（インラインの断片・円判定）には、横と縦の小さい方の円で近似した半径を渡す
        int[] rawRadii = {Math.min(radii.topLeft(), radii.topLeftV()), Math.min(radii.topRight(), radii.topRightV()),
                Math.min(radii.bottomRight(), radii.bottomRightV()), Math.min(radii.bottomLeft(), radii.bottomLeftV())};
        int fallbackRadius = radii.topLeft();
        float rOff = Math.min(fallbackRadius, Math.min(box.w, box.h) / 2);

        // 何が壊れていたか: 以前は 0x00000000 をオフホワイトに書き換えていたので、color: transparent の文字が
        // 白で描かれていた。color は継承するので、宣言の無い箱にも親か初期値（black）が届いている
        int color = box.style.color(CssProperty.COLOR, 0xFF000000);

        // 背景色と画像のレイヤーは別物（色はいちばん下）。background ショートハンドは算出の段で
        // ロングハンドに展開済み。キャンバスに背景を譲った要素は色を描かない
        int bgColor = box == canvasBackgroundSource ? 0x00000000
                : ColorParser.parseColor(box.style.getOrDefault(CssProperty.BACKGROUND_COLOR, "transparent"));

        String id = box.node.getId();

        float opacity = box.style.number(CssProperty.OPACITY, 1.0f);
        float[] tr = anim.transformOf(box);
        float tx = tr[0] + parseFloatAttr(box.node.attrs, "data-pan-x", 0f);
        float ty = tr[1] + parseFloatAttr(box.node.attrs, "data-pan-y", 0f);
        float zoom = parseFloatAttr(box.node.attrs, "data-zoom", 1.0f);
        float sx = tr[2] * zoom;
        float sy = tr[3] * zoom;
        float rot = tr[4];

        TransitionSpec transSpec = parseTransitionSpec(box.style.asMap());
        AnimationSpec animDef = anim.parseAnimationDef(box.style, box.w, box.h, color, bgColor);
        AnimationSpec colorBgAnim = animDef.withChannels(AnimationSpec.CH_COLOR | AnimationSpec.CH_BG_COLOR);
        int transDur = transSpec.durationMs();
        boolean interactive = box.hasInteractiveRules;

        float ox = 0.5f, oy = 0.5f;
        ItemProps anchorEvents = new ItemProps();
        if (parentId != null) anchorEvents.parentId(parentId);
        applyTransitionEvents(anchorEvents, transSpec);
        if (box.style.containsKey(CssProperty.TRANSFORM_ORIGIN)) {
            List<String> parts = CssSyntax.words(box.style.get(CssProperty.TRANSFORM_ORIGIN).toLowerCase(Locale.ROOT));
            if (parts.size() > 0) ox = parseOriginProp(parts.get(0), box.w, box.style.fontSizePx(), box.style.rootFontSizePx());
            if (parts.size() > 1) oy = parseOriginProp(parts.get(1), box.h, box.style.fontSizePx(), box.style.rootFontSizePx());
        }

        // 算出済みの font-size をそのまま運ぶ。描画側は書体の em からの倍率に直す。
        // 以前は 16 倍した「倍率」を渡し、描画側が 12 で割るという二重の換算だった。
        float fontHVal = Math.max(1.0f, box.style.fontSizePx());
        int itemCount = (int) parseFloatAttr(box.node.attrs, "data-count", 1f);

        boolean pointerEventsNone = box.style.keywordOf(CssProperty.POINTER_EVENTS, CssKeywords.PointerEvents.class)
                == CssKeywords.PointerEvents.NONE;
        if (pointerEventsNone) anchorEvents.pointerEventsNone();

        ClipRect normalizedClip = normalizeClipRect(clipRect);
        RenderMeta anchorMeta = new RenderMeta(normalizedClip, radii, null, fontHVal, 0f, box.lineHeightPx, ox, oy, itemCount, interactive);
        addElement(elements, id, id, ElementType.NODE, box.x, box.y, box.w, box.h, "", color, bgColor, anchorEvents, 0, opacity, tx, ty, sx, sy, rot, transDur, animDef, anchorMeta);

        // visibility: hidden の箱は場所だけを取る。錨（NODE）は子孫の変形と不透明度の親として残す
        // 何が壊れていたか: 以前は visibility を読んでおらず、hidden の箱が描かれクリックも受けていた
        if (isInvisible(box)) return;

        ItemProps partsEvents = ItemProps.forParts(box, id, transSpec);
        ItemProps passiveBorderEvents = ItemProps.forPassiveBorder(id, transSpec);
        // 何が壊れていたか: 以前は pointer-events を部品に載せていなかったので、ヒットテストの
        // pointer-events: none の判定は常に偽だった
        if (pointerEventsNone) {
            partsEvents.pointerEventsNone();
            passiveBorderEvents.pointerEventsNone();
        }

        RenderMeta baseMeta = new RenderMeta(normalizedClip, radii, null, fontHVal, 0f, box.lineHeightPx, ox, oy, itemCount, interactive);

        if (!box.inlineFragments.isEmpty() && !NON_FRAGMENT_TAGS.contains(box.node.tag) && !BlockLayoutEngine.isAtomicInline(box)) {
            decor.drawInlineFragments(elements, box, id, color, bgColor, rawRadii, normalizedClip, fontHVal, ox, oy, itemCount, interactive,
                    partsEvents, passiveBorderEvents, transDur, colorBgAnim);
            return;
        }

        List<BoxDecorations.Shadow> shadows = BoxDecorations.parseBoxShadows(box.style.get(CssProperty.BOX_SHADOW), color,
                rootW, rootH, box.style.fontSizePx(), box.style.rootFontSizePx());
        decor.drawOuterShadows(elements, box, id, shadows, radii, normalizedClip, fontHVal, ox, oy);

        BorderSpec border = decor.parseBorderSpec(box, rawRadii, radii, color);
        RenderMeta innerMeta = new RenderMeta(normalizedClip, border.innerRadii(), null, fontHVal, 0f, box.lineHeightPx, ox, oy, itemCount, interactive);

        switch (box.node.tag) {
            case "mc-scene" -> controls.drawSceneElement(elements, box, id, color, bgColor, fallbackRadius, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
            case "img", "video", "mc-fluid", "mc-entity" -> controls.drawMediaElement(elements, box, id, color, bgColor, fallbackRadius, radii, normalizedClip, fontHVal, ox, oy, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
            case "#text" -> drawTextNodeElement(elements, box, id, color, bgColor, normalizedClip, fontHVal, ox, oy, partsEvents, transDur, anim.textColorAnimation(box, animDef));
            case "progress", "meter" -> {
                String val = box.node.attrs.getOrDefault("value", "0");
                String min = box.node.attrs.getOrDefault("min", "0");
                String max = box.node.attrs.getOrDefault("max", "100");
                // 何が壊れていたか: ここが RANGE を出していたため、progress / meter が
                // つまみ付きのスライダーとして描かれていた。進捗バーの描画実装
                // (ControlElementRenderer.renderProgressElement) は呼ばれない死にコードだった。
                addElement(elements, id, id + "_progress", ElementType.PROGRESS, box.x, box.y, box.w, box.h, val + ":" + min + ":" + max, color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, baseMeta);
            }
            case "canvas" -> controls.drawCanvasElement(elements, box, id, color, bgColor, fallbackRadius, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
            case "textarea" -> controls.drawTextareaElement(elements, box, id, color, bgColor, fallbackRadius, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
            case "select" -> controls.drawSelectElement(elements, box, id, color, bgColor, fallbackRadius, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
            case "input" -> {
                if (isPlainRange(box)) {
                    // appearance: none のレンジは作者の CSS で描いた箱に、つまみ（::-webkit-slider-thumb は部品のまま）だけを重ねる
                    decor.drawDefaultBlockElement(elements, box, id, color, bgColor, fallbackRadius, radii, normalizedClip, fontHVal, ox, oy, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim, shadows);
                    controls.drawNativeRange(elements, box, id, rangeData(box, resolveLiveInputValue(id, box.node.attrs.getOrDefault("value", ""))),
                            color, false, partsEvents, border, baseMeta, transDur, colorBgAnim);
                } else if (isPlainToggle(box)) {
                    // appearance: none のチェックボックス・ラジオボタンは普通の箱（作者の枠線・背景だけで描き、印は無い）
                    decor.drawDefaultBlockElement(elements, box, id, color, bgColor, fallbackRadius, radii, normalizedClip, fontHVal, ox, oy, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim, shadows);
                } else {
                    controls.drawInputElement(elements, box, id, color, bgColor, fallbackRadius, fontHVal, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
                }
            }
            case "fieldset" -> controls.drawFieldsetElement(elements, box, id, color, bgColor, fallbackRadius, radii, normalizedClip, fontHVal, ox, oy, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim, shadows);
            default -> decor.drawDefaultBlockElement(elements, box, id, color, bgColor, fallbackRadius, radii, normalizedClip, fontHVal, ox, oy, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim, shadows);
        }
        decor.drawOutline(elements, box, id, color, passiveBorderEvents, baseMeta, transDur);
    }

    private void drawTextNodeElement(List<DisplayItem> elements, LayoutBox box, String id, int color, int bgColor,
                                     ClipRect normalizedClip, float fontHVal, float ox, float oy, ItemProps partsEvents,
                                     int transDur, AnimationSpec colorAnim) {
        ItemProps baseTextEvents = partsEvents.copy();
        if (box.style.containsKey(CssProperty.FONT_WEIGHT)) baseTextEvents.put("font-weight", box.style.get(CssProperty.FONT_WEIGHT));
        if (box.style.containsKey(CssProperty.FONT_FAMILY)) baseTextEvents.put("font-family", box.style.get(CssProperty.FONT_FAMILY));
        if (box.style.containsKey(CssProperty.TEXT_DECORATION)) baseTextEvents.put("text-decoration", box.style.get(CssProperty.TEXT_DECORATION));
        if (box.style.containsKey(CssProperty.TEXT_SHADOW) && !"none".equals(box.style.get(CssProperty.TEXT_SHADOW))) baseTextEvents.put("text-shadow", "true");
        putFontEvents(box, baseTextEvents);

        if (!box.inlineFragments.isEmpty()) {
            for (LayoutBox.InlineFragment frag : box.inlineFragments) {
                if (frag.text == null || frag.text.isBlank()) continue;
                RenderMeta fragMeta = new RenderMeta(
                        normalizedClip, null, null, fontHVal, frag.baseline, frag.h, ox, oy, 1, false);
                addElement(elements, id, id + "_txt_" + (fragIds.getAndIncrement()), ElementType.TEXT, frag.x, frag.y, frag.w, frag.h, frag.text, color, bgColor, baseTextEvents, 0, 1f, 0, 0, 1f, 1f, 0, transDur, colorAnim, fragMeta);
            }
        } else if (box.shapedLines != null && !box.shapedLines.isEmpty()) {
            // 行の文字列・幅・ベースラインは整形器（TextShaper）が確定済み。
            // ここがするのは text-align の offset と丸めだけで、描画側は測り直さない。
            TextAlign align = box.style.keywordOf(CssProperty.TEXT_ALIGN, TextAlign.class);
            boolean rtl = "rtl".equalsIgnoreCase(box.style.getOrDefault(CssProperty.DIRECTION, "ltr").trim());
            float contentX = box.x + box.pL + box.bL;
            float availableW = Math.max(0, box.w - box.pL - box.pR - box.bL - box.bR);
            float lineY = box.y + box.pT + box.bT;

            for (ShapedLine line : box.shapedLines) {
                if (!line.text().isBlank()) {
                    float lineW = line.width();
                    float lineX = contentX + alignOffset(align, rtl, availableW, lineW);
                    RenderMeta lineMeta = new RenderMeta(
                            normalizedClip, null, null, fontHVal, line.baseline(), line.height(), ox, oy, 1, false);
                    // text-transform は displayText を作る時点で適用済み（測定と描画で同じ文字列）
                    addElement(elements, id, id + "_txt_" + (fragIds.getAndIncrement()), ElementType.TEXT, lineX, lineY, lineW, line.height(), line.text(), color, bgColor, baseTextEvents, 0, 1f, 0, 0, 1f, 1f, 0, transDur, colorAnim, lineMeta);
                }
                lineY += line.height();
            }
        }
    }
}
