package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.core.dom.parser.DomUtils;
import com.nishiyu.enderframelib.core.css.ColorParser;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.css.StyleResolver;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.paint.model.AnimationSpec;
import com.nishiyu.enderframelib.core.paint.model.ClipRect;
import com.nishiyu.enderframelib.core.paint.model.Corners;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.ImageFitMode;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;

import java.util.List;
import java.util.Locale;
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.raster.svg.SvgImage;
import com.nishiyu.enderframelib.core.paint.model.*;
import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;
import static com.nishiyu.enderframelib.client.renderer.PaintSupport.addElement;
import static com.nishiyu.enderframelib.client.renderer.PaintSupport.resolveLiveInputValue;
import com.nishiyu.enderframelib.client.renderer.BoxDecorationPainter.BorderSpec;

/** フォームの部品・メディア・シーン・フィールドセットを描画項目にする。枠や背景は {@link BoxDecorationPainter} に任せる。 */
final class ControlPainter {
    private final BoxDecorationPainter decor;

    ControlPainter(BoxDecorationPainter decor) {
        this.decor = decor;
    }

    void drawSceneElement(List<DisplayItem> elements, LayoutBox box, String id, int color, int bgColor, int fallbackRadius,
                                  ItemProps partsEvents, ItemProps passiveBorderEvents,
                                  BorderSpec border, RenderMeta baseMeta, RenderMeta innerMeta, float rOff,
                                  int transDur, AnimationSpec colorBgAnim) {
        decor.drawBorder(elements, id, box, rOff, passiveBorderEvents, border, baseMeta, transDur);
        float innerX = box.x + border.bwLeft();
        float innerY = box.y + border.bwTop();
        float innerW = Math.max(0, box.w - border.bwLeft() - border.bwRight());
        float innerH = Math.max(0, box.h - border.bwTop() - border.bwBottom());

        ItemProps sceneEvents = partsEvents.copy();
        if (box.node.attrs.containsKey("data-blocks")) sceneEvents.put("data-blocks", box.node.attrs.get("data-blocks"));
        if (box.node.attrs.containsKey("slice-y")) sceneEvents.put("data-slice-y", box.node.attrs.get("slice-y"));
        else if (box.node.attrs.containsKey("data-slice-y")) sceneEvents.put("data-slice-y", box.node.attrs.get("data-slice-y"));
        if (box.node.attrs.containsKey("auto-rotate")) sceneEvents.put("data-auto-rotate", box.node.attrs.get("auto-rotate"));

        addElement(elements, id, id + "_scene", ElementType.SCENE, innerX, innerY, innerW, innerH, id, color, bgColor, sceneEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, innerMeta);
    }

    void drawMediaElement(List<DisplayItem> elements, LayoutBox box, String id, int color, int bgColor, int fallbackRadius,
                                  Corners radii, ClipRect normalizedClip, float fontHVal, float ox, float oy,
                                  ItemProps partsEvents, ItemProps passiveBorderEvents,
                                  BorderSpec border, RenderMeta baseMeta, RenderMeta innerMeta, float rOff,
                                  int transDur, AnimationSpec colorBgAnim) {
        // img は srcset / sizes から密度に合う候補を選ぶ（無ければ src）
        ImageChoice chosen = "img".equals(box.node.tag)
                ? ImageSources.forImg(box.node.attrs, decor.rootW(), decor.rootH()) : null;
        String src = box.style.getOrDefault(CssProperty.SRC, chosen != null ? chosen.url() : box.node.attrs.getOrDefault("src", ""));
        if ("mc-fluid".equals(box.node.tag) && !src.startsWith("fluid:")) {
            src = "fluid:" + box.node.attrs.getOrDefault("fluid", src);
        } else if ("mc-entity".equals(box.node.tag) && !src.startsWith("entity:")) {
            src = "entity:" + box.node.attrs.getOrDefault("entity", src.isEmpty() ? "@self" : src);
        }

        String tag = box.node.tag;
        boolean gif = "img".equals(tag) && isRemoteGif(src);
        ElementType type = "video".equals(tag) || "audio".equals(tag) || gif ? ElementType.VIDEO : ElementType.IMAGE;
        ItemProps mediaEvents = partsEvents;
        if (type == ElementType.VIDEO) {
            mediaEvents = partsEvents.copy();
            // GIF は音の無い繰り返しの動画として扱う。video / audio は属性で決める
            if (gif || box.node.attrs.containsKey("loop")) mediaEvents.put("media-loop", "1");
            if (gif || box.node.attrs.containsKey("muted")) mediaEvents.put("media-muted", "1");
        }
        ImageFitMode fitMode = ImageFitMode.fromString(box.style.getOrDefault(CssProperty.OBJECT_FIT, "fill"));

        if (border.isCircle() && border.hasBorder()) {
            int thickness = border.maxThickness();
            String bData = thickness + ":" + border.bcTop() + "," + border.bcRight() + "," + border.bcBottom() + "," + border.bcLeft();
            CircleBorderSpec cbSpec = new CircleBorderSpec(thickness, border.bcTop(), border.bcRight(), border.bcBottom(), border.bcLeft());
            addElement(elements, id, id + "_cb", ElementType.CIRCLE_BORDER, box.x, box.y, box.w, box.h, bData, 0, 0, passiveBorderEvents, 0, 1f, 0, 0, 1f, 1f, 0, transDur, AnimationSpec.NONE,
                    new RenderMeta(normalizedClip, radii, cbSpec, fontHVal, ox, oy, 1, false));
        } else {
            decor.drawBorder(elements, id, box, rOff, passiveBorderEvents, border, baseMeta, transDur);
        }

        float innerX = box.x + border.bwLeft();
        float innerY = box.y + border.bwTop();
        float innerW = Math.max(0, box.w - border.bwLeft() - border.bwRight());
        float innerH = Math.max(0, box.h - border.bwTop() - border.bwBottom());

        // SVG は固有サイズ（viewBox）の縦横比で、描く大きさのビットマップにしてから置く。届くまでは何も描かない
        if ("img".equals(box.node.tag) && SvgImage.looksLikeSvg(src)) {
            SvgImage svg = SvgSources.get(src);
            src = svg == null ? "" : RasterPainting.svgSrc(svg.root(), svg.width(), svg.height(), fitMode, innerW, innerH, color);
        }
        ItemProps imageEvents = mediaEvents;
        String position = box.style.get(CssProperty.OBJECT_POSITION);
        if (position != null && !position.isBlank()) {
            imageEvents = mediaEvents.copy();
            imageEvents.put("object-position", position);
        }
        addElement(elements, id, id + "_img", type, fitMode, innerX, innerY, innerW, innerH, src, color, bgColor, imageEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, innerMeta);
    }

    /** 動画デコーダで再生する GIF（パスが .gif の http(s) URL）。それ以外は従来どおり静止画。 */
    static boolean isRemoteGif(String src) {
        if (src == null || !(src.startsWith("https://") || src.startsWith("http://"))) return false;
        int end = src.length();
        int cut = src.indexOf('?');
        if (cut >= 0) end = cut;
        cut = src.indexOf('#');
        if (cut >= 0 && cut < end) end = cut;
        return src.substring(0, end).toLowerCase(java.util.Locale.ROOT).endsWith(".gif");
    }

    /** インライン {@code <svg>}。内容は SVG の木から描く画素にして、画像と同じ経路で置く。 */
    void drawSvgElement(List<DisplayItem> elements, LayoutBox box, String id, int color, int bgColor, int fallbackRadius,
                        ItemProps partsEvents, ItemProps passiveBorderEvents,
                        BorderSpec border, RenderMeta baseMeta, RenderMeta innerMeta, float rOff,
                        int transDur, AnimationSpec colorBgAnim) {
        decor.drawBorder(elements, id, box, rOff, passiveBorderEvents, border, baseMeta, transDur);
        float innerX = box.x + border.bwLeft();
        float innerY = box.y + border.bwTop();
        float innerW = Math.max(0, box.w - border.bwLeft() - border.bwRight());
        float innerH = Math.max(0, box.h - border.bwTop() - border.bwBottom());
        String src = RasterPainting.svgSrc(box.node, innerW, innerH, ImageFitMode.FILL, innerW, innerH, color);
        addElement(elements, id, id + "_svg", ElementType.IMAGE, ImageFitMode.FILL, innerX, innerY, innerW, innerH, src, color, bgColor,
                partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, innerMeta);
    }

    void drawCanvasElement(List<DisplayItem> elements, LayoutBox box, String id, int color, int bgColor, int fallbackRadius,
                                   ItemProps partsEvents, ItemProps passiveBorderEvents,
                                   BorderSpec border, RenderMeta baseMeta, RenderMeta innerMeta, float rOff,
                                   int transDur, AnimationSpec colorBgAnim) {
        decor.drawBorder(elements, id, box, rOff, passiveBorderEvents, border, baseMeta, transDur);
        float innerX = box.x + border.bwLeft();
        float innerY = box.y + border.bwTop();
        float innerW = Math.max(0, box.w - border.bwLeft() - border.bwRight());
        float innerH = Math.max(0, box.h - border.bwTop() - border.bwBottom());
        // canvas の画素の大きさは width / height 属性（既定 300×150）。内容ボックスへは object-fit で収める
        ItemProps canvasEvents = partsEvents.copy();
        canvasEvents.put("canvas-width", box.node.attrs.getOrDefault("width", ""));
        canvasEvents.put("canvas-height", box.node.attrs.getOrDefault("height", ""));
        String position = box.style.get(CssProperty.OBJECT_POSITION);
        if (position != null && !position.isBlank()) canvasEvents.put("object-position", position);
        ImageFitMode fitMode = ImageFitMode.fromString(box.style.getOrDefault(CssProperty.OBJECT_FIT, "fill"));
        addElement(elements, id, id + "_canvas", ElementType.CANVAS, fitMode, innerX, innerY, innerW, innerH, id, color, bgColor, canvasEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, innerMeta);
    }

    void drawTextareaElement(List<DisplayItem> elements, LayoutBox box, String id, int color, int bgColor, int fallbackRadius,
                                     ItemProps partsEvents, ItemProps passiveBorderEvents,
                                     BorderSpec border, RenderMeta baseMeta, RenderMeta innerMeta, float rOff,
                                     int transDur, AnimationSpec colorBgAnim) {
        String val = box.node.attrs.getOrDefault("value", box.node.getTextContent());
        float innerX = box.x + border.bwLeft();
        float innerY = box.y + border.bwTop();
        float innerW = Math.max(0, box.w - border.bwLeft() - border.bwRight());
        float innerH = Math.max(0, box.h - border.bwTop() - border.bwBottom());

        ItemProps taEvents = partsEvents.copy();
        if (box.node.attrs.containsKey("data-syntax")) taEvents.put("data-syntax", box.node.attrs.get("data-syntax"));
        if (box.node.attrs.containsKey("data-line-numbers")) taEvents.put("data-line-numbers", box.node.attrs.get("data-line-numbers"));

        if (border.useRoundedBorder()) {
            decor.drawRoundedBorder(elements, id, box, passiveBorderEvents, border, baseMeta, transDur);
            addElement(elements, id, id + "_textarea", ElementType.TEXTAREA, innerX, innerY, innerW, innerH, val, color, bgColor, taEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, innerMeta);
        } else {
            addElement(elements, id, id + "_textarea", ElementType.TEXTAREA, box.x, box.y, box.w, box.h, val, color, bgColor, taEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, baseMeta);
            if (border.hasBorder()) decor.drawStraightBorders(elements, id, box, rOff, passiveBorderEvents, border, baseMeta, transDur);
        }
    }

    void drawSelectElement(List<DisplayItem> elements, LayoutBox box, String id, int color, int bgColor, int fallbackRadius,
                                   ItemProps partsEvents, ItemProps passiveBorderEvents,
                                   BorderSpec border, RenderMeta baseMeta, RenderMeta innerMeta, float rOff,
                                   int transDur, AnimationSpec colorBgAnim) {
        String currentVal = box.node.attrs.getOrDefault("value", "");
        String displayLabel = DomUtils.resolveSelectDisplayLabel(box.node, currentVal) + " \u25be";
        float innerX = box.x + border.bwLeft(), innerY = box.y + border.bwTop();
        float innerW = Math.max(0, box.w - border.bwLeft() - border.bwRight()), innerH = Math.max(0, box.h - border.bwTop() - border.bwBottom());

        if (border.useRoundedBorder()) {
            decor.drawRoundedBorder(elements, id, box, passiveBorderEvents, border, baseMeta, transDur);
            addElement(elements, id, id + "_select", ElementType.SELECT, innerX, innerY, innerW, innerH, displayLabel, color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, innerMeta);
        } else {
            addElement(elements, id, id + "_select", ElementType.SELECT, box.x, box.y, box.w, box.h, displayLabel, color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, baseMeta);
            if (border.hasBorder()) decor.drawStraightBorders(elements, id, box, rOff, passiveBorderEvents, border, baseMeta, transDur);
        }
    }

    /** {@code ::placeholder} に色の規則が無いときの文字色。 */
    static final int DEFAULT_PLACEHOLDER_COLOR = 0xFFAAAAAA;

    void drawInputElement(List<DisplayItem> elements, LayoutBox box, String id, int color, int bgColor, int fallbackRadius, float fontHVal,
                                  ItemProps partsEvents, ItemProps passiveBorderEvents,
                                  BorderSpec border, RenderMeta baseMeta, RenderMeta innerMeta, float rOff,
                                  int transDur, AnimationSpec colorBgAnim) {
        String inputType = box.node.attrs.getOrDefault("type", "text");
        String val = resolveLiveInputValue(id, box.node.attrs.getOrDefault("value", ""));

        float innerX = box.x + border.bwLeft();
        float innerY = box.y + border.bwTop();
        float innerW = Math.max(0, box.w - border.bwLeft() - border.bwRight());
        float innerH = Math.max(0, box.h - border.bwTop() - border.bwBottom());

        ItemProps innerEvents = partsEvents.copy();

        switch (inputType) {
            case "radio", "checkbox" -> drawNativeToggle(elements, box, id, "radio".equals(inputType), color,
                    partsEvents, passiveBorderEvents, border, baseMeta, transDur, colorBgAnim);
            case "range" -> drawNativeRange(elements, box, id, rangeData(box, val), color, true, partsEvents, border, baseMeta, transDur, colorBgAnim);
            case "color" -> {
                int swatchColor = ColorParser.parseColor(val.isEmpty() ? "#89b4fa" : val);
                drawBorderedControl(elements, id, "_input", ElementType.INPUT, box, innerX, innerY, innerW, innerH, val, color, swatchColor, fallbackRadius, partsEvents, innerEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
            }
            default -> {
                drawBorderedControl(elements, id, "_input", ElementType.INPUT, box, innerX, innerY, innerW, innerH, val, color, bgColor, fallbackRadius, partsEvents, innerEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
                if (box.node.attrs.containsKey("placeholder")) {
                    String placeholderText = val.isEmpty() ? box.node.attrs.get("placeholder") : "";
                    // 入力欄の内容領域をそのまま渡す。縦方向の中央寄せは書体の
                    // ascent / descent を知っている描画側が 1 箇所で行う
                    float phX = box.x + border.bwLeft() + 4;
                    ItemProps phEvents = passiveBorderEvents.copy();
                    phEvents.pointerEventsNone();
                    // ::placeholder の color（UA の既定は #aaa）。規則が掛からなければ既定の色
                    int placeholderColor = box.placeholderStyle != null
                            ? box.placeholderStyle.color(CssProperty.COLOR, DEFAULT_PLACEHOLDER_COLOR)
                            : DEFAULT_PLACEHOLDER_COLOR;
                    addElement(elements, id, id + "_ph", ElementType.TEXT, phX, innerY, innerW, innerH, placeholderText, placeholderColor, 0x00000000, phEvents, 0, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, innerMeta);
                }
            }
        }
    }

    /**
     * UA の部品で描くラジオボタン・チェックボックス（appearance: auto）。Chromium の描き方
     * （NativeThemeBase::PaintRadio / PaintCheckbox）に合わせ、箱に収まる最大の正方形を中央に置いて描く:
     * <ul>
     *   <li>ラジオボタン: 白い円と 1px の輪。選択時は輪と点（直径の 60%）を強調色で</li>
     *   <li>チェックボックス: 未選択は白い角丸（2px）の四角と 1px の枠。選択・未確定（indeterminate 属性）は
     *       四角全体を強調色で塗って枠を描かず、白い印（チェックの折れ線・横棒）を重ねる</li>
     * </ul>
     * 作者の枠線・背景・padding は使わない（{@code StyleResolver#adjustForAppearance} が枠線と padding を 0 にし、
     * 枠の色を UA の状態ごとの色（ua.css の :hover / :active / :checked）に置き換えてある）。
     * {@code accent-color} があれば選択時の強調色はその色。その色が白と見分けにくい（コントラスト比 3 未満）なら、
     * Chrome と同じく選択時だけ暗い配色にする（チェックの印・ラジオの輪と点の間が #3b3b3b）。
     *
     * 何が壊れていたか: 以前は選択の印を文字色（黒）の四角で描き、作者の枠線・背景がそのまま部品に効いていた。
     */
    void drawNativeToggle(List<DisplayItem> elements, LayoutBox box, String id, boolean radio, int color,
                                  ItemProps partsEvents, ItemProps passiveBorderEvents,
                                  BorderSpec border, RenderMeta baseMeta, int transDur, AnimationSpec colorBgAnim) {
        boolean mixed = !radio && box.node.attrs.containsKey("indeterminate");
        boolean checked = box.node.attrs.containsKey("checked");
        int stateColor = border.bcTop();
        Integer accent = accentColor(box, color);
        // accent-color が白と見分けにくければ暗い配色（Chrome の実測。濃淡の向きもこれで決まる）
        boolean darkAccent = accent != null && contrastRatio(accent, NATIVE_WHITE) < 3f;
        int strong = (checked || mixed) && accent != null ? shadeAccent(accent, stateColor, darkAccent) : stateColor;

        float size = Math.min(box.w, box.h);
        float x = box.x + (box.w - size) / 2, y = box.y + (box.h - size) / 2;
        int radius = radio ? Math.round(size / 2) : Math.min(NATIVE_CHECKBOX_RADIUS, Math.round(size / 2));
        Corners corners = Corners.all(radius);
        RenderMeta meta = new RenderMeta(baseMeta.clipRect(), corners, null,
                baseMeta.fontHeight(), baseMeta.originX(), baseMeta.originY(), 1, false);

        String state = mixed ? "mixed" : Boolean.toString(checked);
        boolean filled = !radio && (checked || mixed);
        // 暗い配色ではチェックの印と、ラジオの輪と点の間が #3b3b3b になる（未確定の横棒は白のまま、未選択の部品は変わらない）
        boolean dark = checked && darkAccent;
        int mark = radio ? strong : dark && !mixed ? NATIVE_DARK : NATIVE_WHITE;
        int fill = filled ? strong : radio && dark ? NATIVE_DARK : NATIVE_WHITE;
        // 印の部品が背景を塗り、印（点・チェック）を中身として描く。輪はその上に重ねる
        addElement(elements, id, id + "_input", radio ? ElementType.RADIO : ElementType.CHECKBOX, x, y, size, size, state,
                mark, fill, partsEvents, radius,
                1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, meta);
        if (filled) return;
        int ring = radio ? strong : stateColor;
        RoundedBorderSpec spec = new RoundedBorderSpec(1f, 1f, 1f, 1f, ring, ring, ring, ring);
        RenderMeta ringMeta = new RenderMeta(baseMeta.clipRect(), corners, null,
                baseMeta.fontHeight(), 0f, 0f, baseMeta.originX(), baseMeta.originY(), 1, false, spec);
        addElement(elements, id, id + "_b_round", ElementType.ROUNDED_BORDER, x, y, size, size, "", 0, 0x00000000,
                passiveBorderEvents, 0, 1f, 0, 0, 1f, 1f, 0, transDur, AnimationSpec.NONE, ringMeta);
    }

    /**
     * {@code accent-color} の状態ごとの濃淡（Chrome の実測。12 色・24 個の値がすべて一致）。HSL の明度を 0.11 ずらし、
     * 明るい配色ではマウスを乗せると暗く・押すと明るく、暗い配色では逆にする。配色は部品の背景との
     * コントラストで決まるので、同じ色でもチェックボックスとレンジで向きが違うことがある（マゼンタなど）。
     * 状態は ua.css が状態ごとに置いた部品の色（StyleResolver が枠の色に置いたもの。作者の CSS では変わらない）から読む。
     *
     * 何が壊れていたか: 以前は accent-color を指定するとマウスを乗せても押しても色が変わらなかった。
     */
    static int shadeAccent(int accent, int stateColor, boolean dark) {
        int direction = stateColor == NATIVE_ACCENT_HOVER ? -1 : stateColor == NATIVE_ACCENT_ACTIVE ? 1 : 0;
        if (direction == 0) return accent;
        return shiftLightness(accent, (dark ? -direction : direction) * ACCENT_LIGHTNESS_STEP);
    }

    /** ua.css の状態ごとの部品の色（マウスを乗せたとき・押したとき）。 */
    static final int NATIVE_ACCENT_HOVER = 0xFF005CC8, NATIVE_ACCENT_ACTIVE = 0xFF3793FF;

    static final float ACCENT_LIGHTNESS_STEP = 0.11f;

    /** HSL の明度を {@code delta} だけずらす（0〜1 に収める）。色相・彩度と不透明度はそのまま。 */
    static int shiftLightness(int argb, float delta) {
        float r = ((argb >>> 16) & 0xFF) / 255f, g = ((argb >>> 8) & 0xFF) / 255f, b = (argb & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        float l = (max + min) / 2f, chroma = max - min;
        float s = chroma == 0f ? 0f : chroma / (1f - Math.abs(2f * l - 1f));
        float h;
        if (chroma == 0f) h = 0f;
        else if (max == r) h = ((g - b) / chroma + 6f) % 6f;
        else if (max == g) h = (b - r) / chroma + 2f;
        else h = (r - g) / chroma + 4f;

        l = Math.clamp(l + delta, 0f, 1f);
        float c = (1f - Math.abs(2f * l - 1f)) * s;
        float x = c * (1f - Math.abs(h % 2f - 1f));
        float m = l - c / 2f;
        float[] rgb = switch ((int) h) {
            case 0 -> new float[]{c, x, 0};
            case 1 -> new float[]{x, c, 0};
            case 2 -> new float[]{0, c, x};
            case 3 -> new float[]{0, x, c};
            case 4 -> new float[]{x, 0, c};
            default -> new float[]{c, 0, x};
        };
        return (argb & 0xFF000000) | Math.round((rgb[0] + m) * 255f) << 16 | Math.round((rgb[1] + m) * 255f) << 8 | Math.round((rgb[2] + m) * 255f);
    }

    /** 部品の背景と印の色（Chromium のライトテーマ）。 */
    static final int NATIVE_WHITE = 0xFFFFFFFF;

    /** 暗い配色の部品の色（Chromium のダークテーマ）。 */
    static final int NATIVE_DARK = 0xFF3B3B3B;

    /** チェックボックスの角の半径（Chromium の既定、CSS px）。 */
    static final int NATIVE_CHECKBOX_RADIUS = 2;

    /**
     * レンジ（Chromium の NativeThemeBase::PaintSliderTrack / PaintSliderThumb と同じ形）。border box いっぱいを使い、
     * 縦の中央に高さ 8px・角丸の溝（#efefef）を置き、値までを強調色で塗り、溝全体に 1px の半透明の枠（#767676 の 50%）を重ね、
     * 直径 16px の強調色の円をつまみにする。つまみの中心は両端から 8px の間を動く。
     * 強調色は ua.css の状態ごとの色（StyleResolver が枠の色に置いたもの）か {@code accent-color}。
     * {@code accent-color} が溝の色と見分けにくい（コントラスト比 3 未満）なら、Chrome と同じく溝を暗い配色（#3b3b3b、枠 #cfcfcf の 50%）にする。
     * {@code track} が偽ならつまみだけ（appearance: none）。
     *
     * 何が壊れていたか: 以前は溝の太さを箱の高さの 30%、つまみの直径を 80% にしていて、溝が細く、つまみの位置も
     * 端から半径の分だけ内側を動く Chrome とずれていた。作者の背景と枠線もそのまま描いていた。
     */
    void drawNativeRange(List<DisplayItem> elements, LayoutBox box, String id, String rangeData, int color, boolean track,
                                 ItemProps partsEvents, BorderSpec border, RenderMeta baseMeta,
                                 int transDur, AnimationSpec colorBgAnim) {
        Integer accent = accentColor(box, color);
        boolean dark = accent != null && contrastRatio(accent, RANGE_TRACK_LIGHT) < 3f;
        int strong = accent != null ? shadeAccent(accent, border.bcTop(), dark) : border.bcTop();
        int fill = !track ? 0x00000000 : dark ? RANGE_TRACK_DARK : RANGE_TRACK_LIGHT;
        int edge = dark ? RANGE_TRACK_BORDER_DARK : RANGE_TRACK_BORDER_LIGHT;
        RoundedBorderSpec trackBorder = track ? new RoundedBorderSpec(1f, 1f, 1f, 1f, edge, edge, edge, edge) : null;
        RenderMeta meta = new RenderMeta(baseMeta.clipRect(), null, null, baseMeta.fontHeight(), 0f, 0f,
                baseMeta.originX(), baseMeta.originY(), 1, false, trackBorder);
        addElement(elements, id, id + "_input", ElementType.RANGE, box.x, box.y, box.w, box.h, rangeData, strong, fill,
                partsEvents, 0, 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, meta);
    }

    /** 溝の色と枠の色（Chrome の実測: 枠は溝の上で #b2b2b2・値の上で #3b75ba になる 50% の #767676。暗い配色は溝の上で #858585）。 */
    static final int RANGE_TRACK_LIGHT = 0xFFEFEFEF, RANGE_TRACK_BORDER_LIGHT = 0x80767676;

    static final int RANGE_TRACK_DARK = 0xFF3B3B3B, RANGE_TRACK_BORDER_DARK = 0x80CFCFCF;

    /** レンジの値の文字列（{@code 値:最小:最大}）。 */
    static String rangeData(LayoutBox box, String val) {
        String min = box.node.attrs.getOrDefault("min", "0");
        String max = box.node.attrs.getOrDefault("max", "100");
        if (val.isEmpty()) val = "50";
        int colonIdx = val.indexOf(':');
        if (colonIdx > 0) val = val.substring(0, colonIdx);
        return val + ":" + min + ":" + max;
    }

    /** WCAG 2 のコントラスト比（1〜21）。 */
    static float contrastRatio(int a, int b) {
        double la = relativeLuminance(a), lb = relativeLuminance(b);
        return (float) ((Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05));
    }

    static double relativeLuminance(int argb) {
        double[] c = new double[3];
        for (int i = 0; i < 3; i++) {
            double v = ((argb >>> (16 - 8 * i)) & 0xFF) / 255.0;
            c[i] = v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    /** {@code appearance: none} のレンジ。 */
    static boolean isPlainRange(LayoutBox box) {
        return "range".equalsIgnoreCase(box.node.attrs.getOrDefault("type", "").trim())
                && !StyleResolver.isNativeRange(box.node, box.style.asMap());
    }

    /** {@code appearance: none} のチェックボックス・ラジオボタン。 */
    static boolean isPlainToggle(LayoutBox box) {
        String type = box.node.attrs.getOrDefault("type", "").trim().toLowerCase(Locale.ROOT);
        return (type.equals("checkbox") || type.equals("radio")) && !StyleResolver.isNativeToggle(box.node, box.style.asMap());
    }

    /** {@code accent-color}。auto（UA の既定）なら null。 */
    static Integer accentColor(LayoutBox box, int textColor) {
        String v = box.style.get(CssProperty.ACCENT_COLOR);
        if (v == null) return null;
        v = v.trim();
        if (v.isEmpty() || "auto".equalsIgnoreCase(v)) return null;
        if ("currentcolor".equalsIgnoreCase(v)) return textColor;
        return ColorParser.isColor(v) ? ColorParser.parseColor(v) : null;
    }

    void drawBorderedControl(List<DisplayItem> elements, String id, String suffix, ElementType type, LayoutBox box,
                                     float innerX, float innerY, float innerW, float innerH, String text, int color, int bgColor, int fallbackRadius,
                                     ItemProps partsEvents, ItemProps innerEvents, ItemProps passiveBorderEvents,
                                     BorderSpec border, RenderMeta baseMeta, RenderMeta innerMeta, float rOff,
                                     int transDur, AnimationSpec colorBgAnim) {
        if (border.useRoundedBorder()) {
            decor.drawRoundedBorder(elements, id, box, passiveBorderEvents, border, baseMeta, transDur);
            addElement(elements, id, id + suffix, type, innerX, innerY, innerW, innerH, text, color, bgColor, innerEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, innerMeta);
        } else {
            addElement(elements, id, id + suffix, type, box.x, box.y, box.w, box.h, text, color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, baseMeta);
            if (border.hasBorder()) decor.drawStraightBorders(elements, id, box, rOff, passiveBorderEvents, border, baseMeta, transDur);
        }
    }

    /**
     * fieldset の背景と枠線（HTML §15.3.13）。legend が上の枠線より高ければ、枠線を legend の中央の高さへ下げ、
     * 背景もそこから塗る。枠線は legend の後ろ（legend の border box の横幅）を描かない。
     *
     * 下げた矩形で描くために、描く間だけ箱の y と高さを差し替える（背景・画像・内側の影・枠線の描き方は
     * どれも箱の矩形を読むので、普通の箱と同じ道を通す）。
     */
    void drawFieldsetElement(List<DisplayItem> elements, LayoutBox box, String id, int color, int bgColor, int fallbackRadius,
                                     Corners radii, ClipRect normalizedClip, float fontHVal, float ox, float oy,
                                     ItemProps partsEvents, ItemProps passiveBorderEvents,
                                     BorderSpec border, RenderMeta baseMeta, RenderMeta innerMeta, float rOff,
                                     int transDur, AnimationSpec colorBgAnim, List<BoxDecorations.Shadow> shadows) {
        LayoutBox legend = box.renderedLegend();
        float shift = legend == null ? 0 : Math.max(0, (legend.h - box.bT) / 2);
        float y = box.y, h = box.h;
        box.y += shift;
        box.h = Math.max(0, h - shift);
        try {
            decor.drawDefaultBlockElement(elements, box, id, color, bgColor, fallbackRadius, radii, normalizedClip, fontHVal, ox, oy,
                    partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim, shadows);
        } finally {
            box.y = y;
            box.h = h;
        }
    }
}
