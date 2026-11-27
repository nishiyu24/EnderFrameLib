package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssSyntax;
import com.nishiyu.enderframelib.core.css.LengthResolver;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.css.model.AnimationSpec;
import com.nishiyu.enderframelib.core.css.model.CssKeywords;
import com.nishiyu.enderframelib.core.css.model.Keyframes;
import com.nishiyu.enderframelib.core.dom.node.ElementTraits;
import com.nishiyu.enderframelib.core.paint.BoxAnimation.TransitionSpec;
import com.nishiyu.enderframelib.core.paint.BoxDecorationPainter.BorderSpec;
import com.nishiyu.enderframelib.core.paint.model.ClipRect;
import com.nishiyu.enderframelib.core.paint.model.Corners;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.LayerSpec;
import com.nishiyu.enderframelib.core.paint.model.RenderHints;
import com.nishiyu.enderframelib.core.paint.model.RenderMeta;
import com.nishiyu.enderframelib.core.paint.scene.DrawOp;
import com.nishiyu.enderframelib.core.replaced.ReplacedElements;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.nishiyu.enderframelib.core.paint.BoxAnimation.applyTransitionEvents;
import static com.nishiyu.enderframelib.core.paint.BoxAnimation.parseOriginProp;
import static com.nishiyu.enderframelib.core.paint.BoxAnimation.parseTransitionSpec;
import static com.nishiyu.enderframelib.core.paint.ControlPainter.isPlainRange;
import static com.nishiyu.enderframelib.core.paint.ControlPainter.isPlainToggle;
import static com.nishiyu.enderframelib.core.paint.ControlPainter.rangeData;
import static com.nishiyu.enderframelib.core.paint.PaintSupport.addElement;
import static com.nishiyu.enderframelib.core.paint.PaintSupport.isInvisible;
import static com.nishiyu.enderframelib.core.paint.PaintSupport.parseFloatAttr;
import static com.nishiyu.enderframelib.core.paint.PaintSupport.resolveLiveInputValue;

/**
 * 箱 1 つ自身の描画項目（節の錨・背景・枠線・影・部品・文字の行）を作る（M2c ②。旧 {@code DisplayListBuilder} の箱ごとの描画を
 * 取り出したもの）。読むのは {@link SceneAdapterBox}（描画用の木の見え方）だけで、描く順・クリップ・記憶は呼び出し側（{@code TreePainter}）が
 * 決める。
 *
 * <p>部品の ID の通し番号 {@link #fragIds} は呼び出し側が箱ごとに 0 にする（他の箱の増減に左右されない）。</p>
 */
final class BoxItemPainter {
    int rootW, rootH;
    /** 箱の部品の ID に振る通し番号（{@link BoxDecorationPainter} と共有）。 */
    final AtomicInteger fragIds;
    final BoxAnimation anim;
    final BoxDecorationPainter decor;
    private final ControlPainter controls;
    /** 文字の行の項目を作る道具。 */
    private final TextRunPainter text = new TextRunPainter(this);
    private final ItemProps scratchAnchorProps = new ItemProps();
    private final ItemProps scratchPartsProps = new ItemProps();
    private final ItemProps scratchPassiveProps = new ItemProps();
    private final int[] scratchRawRadii = new int[4];

    BoxItemPainter(int rootW, int rootH, Map<String, Keyframes> keyframes, AtomicInteger fragIds) {
        this.rootW = rootW;
        this.rootH = rootH;
        this.fragIds = fragIds;
        this.anim = new BoxAnimation(rootW, rootH, keyframes);
        this.decor = new BoxDecorationPainter(rootW, rootH, fragIds);
        this.controls = new ControlPainter(decor);
    }

    /** ビューポートの大きさ（{@code vw} などの基準）が変わった。 */
    void setViewport(int rootW, int rootH) {
        this.rootW = rootW;
        this.rootH = rootH;
        anim.setViewport(rootW, rootH);
        decor.setViewport(rootW, rootH);
    }

    private static final Object CORNERS_MEMO_KEY = new Object();

    private static final class CornersMemo {
        float[] src;
        Corners corners;
    }

    /** 読み取り専用の丸め済み {@link Corners}。半径の配列（{@code LengthResolver} が有効な間は同じ実体）が変わらない間は作り直さない。 */
    private static Corners cornersShared(ComputedStyle style, float w, float h, float rootW, float rootH) {
        if (!LengthResolver.hasNonZeroCornerRadius(style)) return Corners.ZERO;
        float[] r = LengthResolver.cornerRadiiXYShared(style, w, h, rootW, rootH);
        CornersMemo memo = style.paintMemoSelf(CORNERS_MEMO_KEY, st -> new CornersMemo());
        if (memo.src != r) {
            memo.corners = Corners.ofFloatXY(r);
            memo.src = r;
        }
        return memo.corners;
    }

    /**
     * 箱の {@code mask-image} のレイヤーを項目にして足す（背景画像と同じ手順。クリップは箱の外へは出さない）。{@code ownNode} は箱の項目が
     * 自分の節を原点にする（親相対）か。
     */
    void drawMask(OpList elements, SceneAdapterBox box, boolean ownNode) {
        long savedOrigin = elements.saveOrigin();
        if (ownNode) elements.originSelf(box);
        try {
            Corners radii = cornersShared(box.axisStyle(), box.w(), box.h(), rootW, rootH);
            int[] rawRadii;
            if (radii == Corners.ZERO) {
                rawRadii = Corners.ZERO_RAW;
            } else {
                rawRadii = scratchRawRadii;
                rawRadii[0] = Math.min(radii.topLeft(), radii.topLeftV());
                rawRadii[1] = Math.min(radii.topRight(), radii.topRightV());
                rawRadii[2] = Math.min(radii.bottomRight(), radii.bottomRightV());
                rawRadii[3] = Math.min(radii.bottomLeft(), radii.bottomLeftV());
            }
            int color = box.style().typed().inheritedPaint.color;
            BorderSpec border = decor.parseBorderSpec(box, rawRadii, radii, color);
            float fontHVal = Math.max(1.0f, box.style().fontSizePx());
            decor.drawMaskLayers(elements, box, box.node().key(), null, radii, border, fontHVal, 0.5f, 0.5f);
            if (BoxDecorations.clipsText(box.style())) {
                // background-clip: text: 子孫の文字を白（不透明）で出す。これがマスクになり、箱の背景が文字の形だけに残る
                ItemProps textEvents = ItemProps.parent(box.node().key());
                textEvents.pointerEventsNone();
                suppressTextShadow = true;
                try {
                    maskText(elements, box, box.node().key(), textEvents);
                } finally {
                    suppressTextShadow = false;
                }
            }
        } finally {
            elements.restoreOrigin(savedOrigin);
        }
    }

    /**
     * {@code ::backdrop}（旧 {@code PaintOrder.drawBackdrop} の移植）: 背景色の矩形をビューポート全面に置く（固定配置の箱と同じく
     * スクロールしない。クリップは掛けない）。色か不透明度が 0 なら何も出さない。
     */
    void drawBackdrop(OpList elements, SceneAdapterBox box, com.nishiyu.enderframelib.core.css.Style bd, boolean ownNode) {
        int color = bd.visual.backgroundColor;
        float opacity = com.nishiyu.enderframelib.core.css.Style.num(bd.visual.opacity, 1.0f);
        if ((color >>> 24) == 0 || opacity <= 0f) return;
        long savedOrigin = elements.saveOrigin();
        if (ownNode) elements.originSelf(box);
        try {
            String key = box.node().key();
            int handle = box.node().paintHandle();
            ItemProps props = new ItemProps().parentId(key, handle > 0 ? DisplayItem.nodeKeyOf(handle) : 0L);
            PaintSupport.addElement(elements, key, key + "_backdrop", ElementType.RECT, 0, 0, rootW, rootH, "", 0, color, props, 0,
                    opacity, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, null, 0L);
        } finally {
            elements.restoreOrigin(savedOrigin);
        }
    }

    /** 文字の影を出さない（マスクに影が混ざらないように）。 */
    private boolean suppressTextShadow;

    boolean suppressTextShadow() { return suppressTextShadow; }

    private void maskText(OpList elements, SceneAdapterBox box, String id, ItemProps events) {
        for (SceneAdapterBox child : box.paintChildren()) {
            if (child.displayNone()) continue;
            if ("#text".equals(child.node().tag)) {
                float fontH = Math.max(1f, child.style().fontSizePx());
                text.drawTextNodeElement(elements, child, id, 0xFFFFFFFF, 0, null, fontH, 0.5f, 0.5f, events, 0, AnimationSpec.NONE, null);
            } else {
                maskText(elements, child, id, events);
            }
        }
    }

    /** 錨の命令にレイヤーの指定を載せる。 */
    static DrawOp.Item withLayer(DrawOp.Item anchor, LayerSpec spec) {
        RenderMeta meta = anchor.meta() != null ? anchor.meta() : RenderMeta.EMPTY;
        return anchor.withMeta(meta.withHints(meta.hints().withLayer(spec)));
    }

    /** 錨の項目にレイヤーの指定を載せる。 */
    static DisplayItem withLayer(DisplayItem anchor, LayerSpec spec) {
        RenderMeta meta = anchor.meta() != null ? anchor.meta() : RenderMeta.EMPTY;
        RenderMeta withLayer = meta.withHints(meta.hints().withLayer(spec));
        return new DisplayItem(anchor.id(), anchor.baseNodeId(), anchor.type(), anchor.x(), anchor.y(), anchor.width(), anchor.height(),
                anchor.text(), anchor.color(), anchor.bgColor(), anchor.events(), anchor.radius(), anchor.opacity(),
                anchor.translateX(), anchor.translateY(), anchor.scaleX(), anchor.scaleY(), anchor.rotate(),
                anchor.transitionDuration(), anchor.animDef(), anchor.fitMode(), withLayer, anchor.key());
    }

    private static RenderMeta meta(ClipRect clipRect, Corners borderRadii, float fontHeight, float lineHeight,
                                   float originX, float originY, int itemCount, boolean interactiveState, RenderHints hints) {
        return new RenderMeta(clipRect, borderRadii, null, fontHeight, 0f, lineHeight, originX, originY,
                itemCount, interactiveState, null, hints);
    }

    /**
     * 箱 1 つ自身の項目（節の錨・背景・枠線・部品・文字）を {@code elements} へ足す。
     *
     * @param originBox  項目の座標の原点にする節の箱（親相対のとき。無ければ原点 0）
     * @param ownNode    箱の項目が自分の節を原点にする（親相対で、自分の節を持つ）か
     * @param canvasBackground 背景をキャンバスに譲った要素（html か body）か（自分の箱には背景色を描かない）
     */
    void drawBox(OpList elements, SceneAdapterBox box, ClipRect clipRect, String parentId,
                 SceneAdapterBox originBox, boolean ownNode, boolean canvasBackground) {
        Corners radii = cornersShared(box.axisStyle(), box.w(), box.h(), rootW, rootH);
        // 円しか持たない所（インラインの断片・円判定）には、横と縦の小さい方の円で近似した半径を渡す
        int[] rawRadii;
        if (radii == Corners.ZERO) {
            rawRadii = Corners.ZERO_RAW;
        } else {
            rawRadii = scratchRawRadii;
            rawRadii[0] = Math.min(radii.topLeft(), radii.topLeftV());
            rawRadii[1] = Math.min(radii.topRight(), radii.topRightV());
            rawRadii[2] = Math.min(radii.bottomRight(), radii.bottomRightV());
            rawRadii[3] = Math.min(radii.bottomLeft(), radii.bottomLeftV());
        }
        int fallbackRadius = radii.topLeft();
        float rOff = Math.min(fallbackRadius, Math.min(box.w(), box.h()) / 2);

        Style st = box.style().typed();
        // 何が壊れていたか: 以前は 0x00000000 をオフホワイトに書き換えていたので、color: transparent の文字が
        // 白で描かれていた。color は継承するので、宣言の無い箱にも親か初期値（black）が届いている
        int color = st.inheritedPaint.color;

        // 背景色と画像のレイヤーは別物（色はいちばん下）。background ショートハンドは算出の段で
        // ロングハンドに展開済み。キャンバスに背景を譲った要素は色を描かない
        int bgColor = canvasBackground ? 0x00000000 : st.visual.backgroundColor;

        String id = box.node().key();

        float opacity = Style.num(st.visual.opacity, 1.0f);
        float[] tr = anim.transformOf(box);
        float tx = tr[0] + parseFloatAttr(box.values().attrs(), "data-pan-x", 0f);
        float ty = tr[1] + parseFloatAttr(box.values().attrs(), "data-pan-y", 0f);
        float zoom = parseFloatAttr(box.values().attrs(), "data-zoom", 1.0f);
        float sx = tr[2] * zoom;
        float sy = tr[3] * zoom;
        float rot = tr[4];

        TransitionSpec transSpec = parseTransitionSpec(box.style());
        AnimationSpec animDef = anim.parseAnimationDef(box.style(), box.w(), box.h(), color, bgColor);
        AnimationSpec colorBgAnim = animDef.isEmpty() ? AnimationSpec.NONE : animDef.withChannels(AnimationSpec.CH_COLOR | AnimationSpec.CH_BG_COLOR);
        int transDur = transSpec.durationMs();
        boolean interactive = box.interactiveRules();

        float ox = 0.5f, oy = 0.5f;
        ItemProps anchorEvents = scratchAnchorProps.reset();
        if (parentId != null) anchorEvents.parentId(parentId);
        if (tr.length > 5 && tr[5] != 0f) anchorEvents.skew(tr[5]);
        applyTransitionEvents(anchorEvents, transSpec);
        if (st.visual.transformOrigin != null) {
            List<String> parts = CssSyntax.words(st.visual.transformOrigin.toLowerCase(Locale.ROOT));
            if (parts.size() > 0) ox = parseOriginProp(parts.get(0), box.w(), st.inherited.fontSize, st.inherited.rootFontSizePx);
            if (parts.size() > 1) oy = parseOriginProp(parts.get(1), box.h(), st.inherited.fontSize, st.inherited.rootFontSizePx);
        }

        // 算出済みの font-size をそのまま運ぶ。描画側は書体の em からの倍率に直す。
        // 以前は 16 倍した「倍率」を渡し、描画側が 12 で割るという二重の換算だった。
        float fontHVal = Math.max(1.0f, st.inherited.fontSize);
        int itemCount = (int) parseFloatAttr(box.values().attrs(), "data-count", 1f);

        boolean pointerEventsNone = st.inheritedPaint.pointerEvents == CssKeywords.PointerEvents.NONE;
        if (pointerEventsNone) anchorEvents.pointerEventsNone();

        boolean useRel = ownNode;
        if (useRel) anchorEvents.relativeOrigin();
        anchorEvents.hintsReuse(null);
        ClipRect normalizedClip = clipRect;
        RenderMeta anchorMeta = meta(normalizedClip, radii, fontHVal, box.lineHeightPx(), ox, oy,
                itemCount, interactive, anchorEvents.hints());
        long nodeKey = box.node() != null ? DisplayItem.nodeKeyOf(box.node().paintHandle()) : 0L;
        long savedOrigin = elements.saveOrigin();
        elements.originNode(originBox);
        try {
            addElement(elements, id, id, ElementType.NODE, box.x(), box.y(), box.w(), box.h(), "", color, bgColor, anchorEvents, 0, opacity, tx, ty, sx, sy, rot, transDur, animDef, anchorMeta, nodeKey);
            elements.markAnchor();

            // visibility: hidden の箱は場所だけを取る。錨（NODE）は子孫の変形と不透明度の親として残す
            // 何が壊れていたか: 以前は visibility を読んでおらず、hidden の箱が描かれクリックも受けていた
            if (isInvisible(box) || PaintSupport.hidesEmptyCell(box)) return;

            if (useRel) elements.originSelf(box);

            ItemProps partsEvents = ItemProps.populateForParts(scratchPartsProps, box, id, transSpec, null);
            if (pointerEventsNone) partsEvents.pointerEventsNone();
            partsEvents.reuseCandidate(null);
            if ("#text".equals(box.node().tag)) {
                AnimationSpec textAnim;
                if (elements.deferred) { // 祖先の文字色のアニメーションは組み立てで解く（M3b-4a）
                    AnimationSpec own = animDef.withChannels(AnimationSpec.CH_COLOR);
                    textAnim = own.isEmpty() ? OpList.TEXT_COLOR_ANIM_FROM_ANCESTORS : own;
                } else {
                    textAnim = anim.textColorAnimation(box, animDef);
                }
                text.drawTextNodeElement(elements, box, id, color, bgColor, normalizedClip, fontHVal, ox, oy, partsEvents, transDur, textAnim, null);
                return;
            }
            ItemProps passiveBorderEvents = ItemProps.populateForPassiveBorder(scratchPassiveProps, id, nodeKey, transSpec);
            // 何が壊れていたか: 以前は pointer-events を部品に載せていなかったので、ヒットテストの
            // pointer-events: none の判定は常に偽だった
            if (pointerEventsNone) {
                passiveBorderEvents.pointerEventsNone();
            }
            passiveBorderEvents.reuseCandidate(null);

            if (!box.runs().isEmpty() && !ElementTraits.isNonFragmented(box.node().tag) && !ReplacedElements.isHost(box.node().tag)
                && !box.atomicInline() && !ReplacedElements.isReplacedTag(box.node().tag)) {
                decor.drawInlineFragments(elements, box, id, color, bgColor, rawRadii, normalizedClip, fontHVal, ox, oy, itemCount, interactive,
                        partsEvents, passiveBorderEvents, transDur, colorBgAnim);
                return;
            }

            RenderMeta baseMeta = meta(normalizedClip, radii, fontHVal, box.lineHeightPx(), ox, oy,
                    itemCount, interactive, partsEvents.hints());

            List<BoxDecorations.Shadow> shadows = BoxDecorations.parseBoxShadows(st.visual.boxShadow, color,
                    rootW, rootH, st.inherited.fontSize, st.inherited.rootFontSizePx);
            decor.drawOuterShadows(elements, box, id, shadows, radii, normalizedClip, fontHVal, ox, oy);

            BorderSpec border = decor.parseBorderSpec(box, rawRadii, radii, color);
            RenderMeta innerMeta = border.innerRadii().equals(radii)
                    ? baseMeta
                    : meta(normalizedClip, border.innerRadii(), fontHVal, box.lineHeightPx(), ox, oy,
                            itemCount, interactive, RenderHints.NONE);

            switch (box.node().tag) {
                case "img", "video", "audio" -> controls.drawMediaElement(elements, box, id, color, bgColor, fallbackRadius, radii, normalizedClip, fontHVal, ox, oy, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
                case "progress", "meter" -> {
                    String val = box.values().attrs().getOrDefault("value", "0");
                    String min = box.values().attrs().getOrDefault("min", "0");
                    String max = box.values().attrs().getOrDefault("max", "100");
                    // 何が壊れていたか: ここが RANGE を出していたため、progress / meter が
                    // つまみ付きのスライダーとして描かれていた。進捗バーの描画実装
                    // (ControlElementRenderer.renderProgressElement) は呼ばれない死にコードだった。
                    long progressKey = box.node() != null ? DisplayItem.keyOf(box.node().paintHandle(), DisplayItem.PART_PROGRESS) : 0L;
                    addElement(elements, id, id + "_progress", ElementType.PROGRESS, box.x(), box.y(), box.w(), box.h(), val + ":" + min + ":" + max, color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, baseMeta, progressKey);
                }
                case "svg" -> controls.drawSvgElement(elements, box, id, color, bgColor, fallbackRadius, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
                case "canvas" -> controls.drawCanvasElement(elements, box, id, color, bgColor, fallbackRadius, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
                case "textarea" -> controls.drawTextareaElement(elements, box, id, color, bgColor, fallbackRadius, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
                case "select" -> controls.drawSelectElement(elements, box, id, color, bgColor, fallbackRadius, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
                case "input" -> {
                    if (isPlainRange(box)) {
                        // appearance: none のレンジは作者の CSS で描いた箱に、つまみ（::-webkit-slider-thumb は部品のまま）だけを重ねる
                        decor.drawDefaultBlockElement(elements, box, id, color, bgColor, fallbackRadius, radii, normalizedClip, fontHVal, ox, oy, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim, shadows);
                        controls.drawNativeRange(elements, box, id, rangeData(box, resolveLiveInputValue(id, box.values().attrs().getOrDefault("value", ""))),
                                color, false, partsEvents, border, baseMeta, transDur, colorBgAnim);
                    } else if (isPlainToggle(box)) {
                        // appearance: none のチェックボックス・ラジオボタンは普通の箱（作者の枠線・背景だけで描き、印は無い）
                        decor.drawDefaultBlockElement(elements, box, id, color, bgColor, fallbackRadius, radii, normalizedClip, fontHVal, ox, oy, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim, shadows);
                    } else {
                        controls.drawInputElement(elements, box, id, color, bgColor, fallbackRadius, fontHVal, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
                    }
                }
                case "fieldset" -> controls.drawFieldsetElement(elements, box, id, color, bgColor, fallbackRadius, radii, normalizedClip, fontHVal, ox, oy, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim, shadows);
                default -> {
                    if (ReplacedElements.isHostScene(box.node().tag)) {
                        controls.drawSceneElement(elements, box, id, color, bgColor, fallbackRadius, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
                    } else if (ReplacedElements.isHostImageLike(box.node().tag)) {
                        controls.drawMediaElement(elements, box, id, color, bgColor, fallbackRadius, radii, normalizedClip, fontHVal, ox, oy, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
                    } else {
                        decor.drawDefaultBlockElement(elements, box, id, color, bgColor, fallbackRadius, radii, normalizedClip, fontHVal, ox, oy, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim, shadows);
                    }
                }
            }
            decor.drawFirstLineBackground(elements, box, id, color, partsEvents, innerMeta, transDur);
            decor.drawColumnRules(elements, box, id, color, passiveBorderEvents, baseMeta, transDur);
            decor.drawOutline(elements, box, id, color, passiveBorderEvents, baseMeta, transDur);
        } finally {
            elements.restoreOrigin(savedOrigin);
        }
    }
}
