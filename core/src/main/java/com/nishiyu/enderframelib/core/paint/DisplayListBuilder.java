package com.nishiyu.enderframelib.core.paint;

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
import com.nishiyu.enderframelib.core.paint.BoxAnimation.TransitionSpec;
import com.nishiyu.enderframelib.core.paint.BoxDecorationPainter.BorderSpec;
import com.nishiyu.enderframelib.core.paint.PaintOrder.RenderTask;

public class DisplayListBuilder {
    // 単位なしの 0（例: translate(-50%, 0)）も受け付けるため単位は省略可能（省略時は px 扱い）

    private static final Set<String> NON_FRAGMENT_TAGS = Set.of(
            "#text", "img", "mc-fluid", "mc-entity", "mc-scene",
            "input", "select", "progress", "meter", "textarea", "canvas", "svg"
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
                box -> PaintOrder.factsOf(box.style).hasAnimation()
                        && (anim.parseAnimationDef(box.style, box.w, box.h, 0, 0).animatedChannels() & AnimationSpec.CH_OPACITY) != 0);
    }

    /** 箱を描く順（スタッキングコンテキストの順）に並べた箱。 */
    public List<LayoutBox> paintOrder(LayoutBox root) {
        return order.paintOrder(root);
    }

public List<DisplayItem> render(LayoutBox box) {
    return render(box, false);
}

/**
 * {@code cullOffscreen} が true なら、画面（と余白）の外にある箱の項目は作らない。位置だけが変わるスクロールのように、
 * 直前に全件で描いた木と同じ中身のときに使う（部品の ID は箱ごとに数えるので、省いた箱が他の箱の ID に響かない）。
 * 変形・アニメーション・フィルターを持つ祖先の下は、動いて画面に入ってくるので省かない。
 */
public List<DisplayItem> render(LayoutBox box, boolean cullOffscreen) {
    generation++;
    buildSerial++;
    buildEpoch = LayoutBox.paintEpoch();
    translating = cullOffscreen && TRANSLATE_ENABLED;
    translatedHits = 0;
    List<DisplayItem> elements = new ArrayList<>();
        canvasBackgroundSource = null;
        decor.setDevicePixelRatio(box.devicePixelRatio);

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
                        fragIds.set(MASK_ID_BASE); // 箱の本体の部品（0 から）と ID が重ならないように
                        drawMaskItems(elements, layerBox);
                        spec = spec.withMaskFrom(elements.size() - start > maskFrom ? maskFrom : -1);
                    }
                    DisplayItem anchor = elements.get(start);
                    elements.set(start, withLayer(anchor, spec.withSpan(elements.size() - start)));
                }
                continue;
            }
            LayoutBox owner = task.box();
            if (owner == null) {
                task.drawAction().accept(elements);
                continue;
            }
            if (cullOffscreen && !openBoxes.contains(owner) && offscreen(owner)) {
                BoxMemo kept = boxMemo.get(owner);
                if (kept != null) kept.generation = generation; // 省いた箱の記憶は、画面に戻ってくるまで残す
                continue;
            }
            task.drawAction().accept(elements);
        }
        // 今回使われなかった箱（木から消えた箱）の記憶を捨てる
        boxMemo.values().removeIf(m -> m.generation != generation);
        return elements;
    }

    private static final float CULL_MARGIN = 256f;
    private static final String[] MOVING_PROPS = {"transform", "translate", "rotate", "scale", "animation", "animation-name", "filter", "perspective"};

    private boolean offscreen(LayoutBox box) {
        // 文字の塊の箱は位置を持たない（断片が置かれる）。持ち主の箱の範囲で見る
        LayoutBox at = box;
        while (at.parent != null && ("#text".equals(at.node.tag) || at.isAnonymous)) at = at.parent;
        if (at.x + at.w >= -CULL_MARGIN && at.x <= rootW + CULL_MARGIN
                && at.y + at.h >= -CULL_MARGIN && at.y <= rootH + CULL_MARGIN) return false;
        for (LayoutBox b = box; b != null; b = b.parent) {
            if (b.style == null) continue;
            for (String prop : MOVING_PROPS) {
                String v = b.style.get(prop);
                if (v != null && !v.isBlank() && !"none".equalsIgnoreCase(v.trim())) return false;
            }
        }
        return true;
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
        if (BoxDecorations.clipsText(box.style)) {
            // background-clip: text: 子孫の文字を白（不透明）で出す。これがマスクになり、箱の背景が文字の形だけに残る
            ItemProps textEvents = ItemProps.parent(box.node.getId());
            textEvents.pointerEventsNone();
            suppressTextShadow = true;
            try {
                maskText(elements, box, box.node.getId(), textEvents);
            } finally {
                suppressTextShadow = false;
            }
        }
    }

    /** 文字の影を出さない（マスクに影が混ざらないように）。 */
    private boolean suppressTextShadow;

    private void maskText(List<DisplayItem> elements, LayoutBox box, String id, ItemProps events) {
        for (LayoutBox child : box.children) {
            if (child.display.isNone()) continue;
            if ("#text".equals(child.node.tag)) {
                float fontH = Math.max(1f, child.style.fontSizePx());
                drawTextNodeElement(elements, child, id, 0xFFFFFFFF, 0, null, fontH, 0.5f, 0.5f, events, 0, AnimationSpec.NONE);
            } else {
                maskText(elements, child, id, events);
            }
        }
    }

    /** 錨の項目にレイヤーの指定を載せる。 */
    private static DisplayItem withLayer(DisplayItem anchor, LayerSpec spec) {
        RenderMeta meta = anchor.meta() != null ? anchor.meta() : RenderMeta.EMPTY;
        RenderMeta withLayer = meta.withHints(meta.hints().withLayer(spec));
        return new DisplayItem(anchor.id(), anchor.baseNodeId(), anchor.type(), anchor.x(), anchor.y(), anchor.width(), anchor.height(),
                anchor.text(), anchor.color(), anchor.bgColor(), anchor.events(), anchor.radius(), anchor.opacity(),
                anchor.translateX(), anchor.translateY(), anchor.scaleX(), anchor.scaleY(), anchor.rotate(),
                anchor.transitionDuration(), anchor.animDef(), anchor.fitMode(), withLayer, anchor.key());
    }

    /**
     * 背景の指定が無い文書のキャンバス。ゲームの上に重ねて描く文書は iframe と同じ扱いで透明
     * （ブラウザが白で塗るのは最上位の文書だけ）。html / body はパーサが必ず補う（W7）ので、背景を書かない HUD の断片も透明になる。
     * 以前は ua.css が body を白で塗り、body の無い文書は #111111 で塗りつぶしていた。
     */
    private static final int DEFAULT_CANVAS_COLOR = 0x00000000;

    private static final int MASK_ID_BASE = 1_000_000;

    /**
     * スクロールコンテナの中身を 1 つの節にまとめるか。まとめると、スクロールだけの更新は表示リストを作り直さず、その節の
     * 移動量を更新するだけで済む（{@code UiView} が行う）。false なら従来どおり、中身の項目はコンテナの節の直下に並び、
     * スクロールのたびに作り直す（疑うときの切り分け用。{@code -Denderframe.paint.noscrollnode=true} でも切れる）。結果は同じでなければならない。
     */
    public static volatile boolean scrollContentNodes = !Boolean.getBoolean("enderframe.paint.noscrollnode");

    // ==========================================
    // 箱ごとの記憶（React.memo と同じ考え方）
    // ==========================================
    //
    // 箱の描画結果（表示項目）は、その箱の入力だけで決まる: 算出スタイル・位置と大きさ・文字の断片・属性・クリップ・
    // 親の id・祖先のスタイル（文字色のアニメーションが親を読む）。部品の ID は箱ごとに 0 から数えるので、
    // 他の箱の増減に左右されない。入力が前回と全部同じなら、前回の項目（不変）をそのまま出し、作り直さない。
    // 一致の判定は値の比較（署名のハッシュではない）なので、取り違えは起きない。確実に決まらない箱（表・フォーム部品・
    // SVG・srcset 付きの画像・動画など描画側が別の資源を読む箱）は、いつも描き直す。img（点陣画像）・progress・meter・
    // mc-scene / mc-fluid / mc-entity は属性とスタイルだけで決まるので記憶する。

    /** 箱の記憶。{@code generation} は最後に使われた描画の世代（古いものは描画の終わりに捨てる）。 */
    private static final class BoxMemo {
        int generation;
        List<DisplayItem> items;
        com.nishiyu.enderframelib.core.css.ComputedStyle style;
        com.nishiyu.enderframelib.core.css.ComputedStyle[] ancestors;
        float[] nums;
        ClipRect clip;
        String parentId;
        Map<String, String> attrs;
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
    }

    private final java.util.IdentityHashMap<LayoutBox, BoxMemo> boxMemo = new java.util.IdentityHashMap<>();
    private int generation;
    /** 変更印で署名の比較を省くか（呼び出し側が、変わった箱が分かっている局所更新のときだけ true にする）。 */
    private boolean useChangeStamps;
    private long buildEpoch;
    private int buildSerial;
    /** 診断: 変更印で比較を省いた箱の累計。 */
    public static final java.util.concurrent.atomic.LongAdder STAT_STAMPED = new java.util.concurrent.atomic.LongAdder();
    /** 検証: 変更印が「変わっていない」と言った箱でも署名を比べ、食い違えば例外にする（{@code -Denderframe.paint.verifystamps=true}）。 */
    public static volatile boolean verifyStamps = Boolean.getBoolean("enderframe.paint.verifystamps");

    /** 診断: 描画順の分類用のスタイル値を新しく算出した回数の累計（キャッシュ退行の検出用）。 */
    public static long styleFactsComputed() {
        return PaintOrder.STAT_FACTS_COMPUTED.sum();
    }

    /** 次の {@link #render} だけ変更印を使う。 */
    public void setUseChangeStamps(boolean use) {
        this.useChangeStamps = use;
    }
    /** 診断: 箱ごとの記憶の使われ方の累計（そのまま・平行移動・新規・不一致）。 */
    public static final java.util.concurrent.atomic.LongAdder STAT_EXACT = new java.util.concurrent.atomic.LongAdder(), STAT_TRANSLATED = new java.util.concurrent.atomic.LongAdder(),
            STAT_NEW = new java.util.concurrent.atomic.LongAdder(), STAT_MISS = new java.util.concurrent.atomic.LongAdder(), STAT_UNMEMOIZABLE = new java.util.concurrent.atomic.LongAdder();
    /** 位置だけが変わった作り直し（{@link #render(LayoutBox, boolean)} の {@code cullOffscreen}）か。 */
    private boolean translating;
    private int translatedHits;

    /** 直近の描画で、前回の項目を平行移動して使った箱の数（0 でなければ、位置が最大 1 画素ずれうる）。 */
    public int translatedHits() {
        return translatedHits;
    }
    private static final boolean MEMO_ENABLED = !Boolean.getBoolean("enderframe.paint.nomemo");
    /** 切り分け用: {@code -Denderframe.paint.notranslate=true} で、位置が動いた箱の項目を平行移動で使い回さない。 */
    private static final boolean TRANSLATE_ENABLED = !Boolean.getBoolean("enderframe.paint.notranslate");

    private static boolean memoizable(LayoutBox box) {
        String tag = box.node.tag;
        if (tag == null) return false;
        if (tag.startsWith("mc-")) {
            // 描画が読むのは属性とスタイルだけ（mc-scene / mc-fluid / mc-entity）。他の mc-* は従来どおり除外する
            if (!"mc-scene".equals(tag) && !"mc-fluid".equals(tag) && !"mc-entity".equals(tag)) return false;
        } else {
            switch (tag) {
                case "video", "audio", "svg", "canvas", "textarea", "select", "input", "fieldset",
                     "legend", "table", "caption", "thead", "tbody", "tfoot", "tr", "td", "th", "col", "colgroup" -> {
                    return false;
                }
                case "img" -> {
                    if (!imgMemoizable(box)) return false;
                }
                default -> { } // progress / meter は value・min・max（属性）だけで決まるので解禁
            }
        }
        if (box.collapsedBorders != null || box.display == null) return false;
        return !String.valueOf(box.display.inner()).startsWith("TABLE");
    }

    /**
     * {@code img} の項目が箱の入力（属性・スタイル）だけで決まるか。{@code srcset} / {@code sizes} は表示領域と
     * 密度で候補を選ぶので記憶の署名に入らない。SVG は読み込みの完了で結果が変わる（届くまでは何も描かない）。
     */
    private static boolean imgMemoizable(LayoutBox box) {
        Map<String, String> attrs = box.node.attrs;
        if (attrs.containsKey("srcset") || attrs.containsKey("sizes")) return false;
        String src = box.style.getOrDefault(CssProperty.SRC, attrs.getOrDefault("src", ""));
        return !com.nishiyu.enderframelib.core.raster.svg.SvgImage.looksLikeSvg(src);
    }

    /**
     * 算出スタイルが値として同じか。再スタイルされた箱は宣言が同じでも新しいインスタンスになるので、同一性だけで比べると
     * 子孫まで巻き込んで外れる。描画が読むのは宣言と、文字の大きさ（自分とルート）だけ。
     */
    private static boolean sameStyle(com.nishiyu.enderframelib.core.css.ComputedStyle a, com.nishiyu.enderframelib.core.css.ComputedStyle b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.fontSizePx() == b.fontSizePx() && a.rootFontSizePx() == b.rootFontSizePx() && com.nishiyu.enderframelib.core.css.ComputedStyle.sameDeclarations(a, b);
    }

    private static float[] numsOf(LayoutBox b) {
        return new float[]{b.x, b.y, b.w, b.h, b.pL, b.pT, b.pR, b.pB, b.bL, b.bT, b.bR, b.bB, b.lineHeightPx};
    }

    private static boolean sameNums(float[] a, float[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (Float.compare(a[i], b[i]) != 0) return false;
        return true;
    }

    /** クリップの値としての一致（連鎖の矩形が配列なので、{@code ClipChainPart} の equals が中身を見る）。 */
    private static boolean sameClip(ClipRect a, ClipRect b) {
        return a == b || (a != null && a.equals(b));
    }

    private static com.nishiyu.enderframelib.core.css.ComputedStyle[] ancestorStyles(LayoutBox box) {
        int n = 0;
        for (LayoutBox a = box.parent; a != null; a = a.parent) n++;
        com.nishiyu.enderframelib.core.css.ComputedStyle[] out = new com.nishiyu.enderframelib.core.css.ComputedStyle[n];
        int i = 0;
        for (LayoutBox a = box.parent; a != null; a = a.parent) out[i++] = a.style;
        return out;
    }

    /**
     * 変更印から、記憶が今の箱にそのまま使えると言えるか。記憶を作った後に、自分にも祖先にもレイアウトが触れておらず
     * （印が記憶の通し番号以下）、親の箱・クリップ・親の id・描画側の旗が同じ。属性の変更は印に出ないので、
     * 属性が変わらない更新（文字の局所パッチ）でだけ呼び出し側が有効にする。
     */
    private boolean stampClean(BoxMemo m, LayoutBox box, ClipRect clip, String parentId) {
        return m.parentBox == box.parent && box.pathPaintStamp(buildSerial) <= m.epoch
                && m.canvasSource == (box == canvasBackgroundSource) && m.interactive == box.hasInteractiveRules
                && m.suppressShadow == suppressTextShadow && sameClip(m.clip, clip)
                && java.util.Objects.equals(m.parentId, parentId);
    }

    /** 平行移動で使い回すときの、箱の動き（{@link #match} が 2 を返したとき有効）。 */
    private float hitDx, hitDy;

    /**
     * 記憶が今の箱に使えるか。0: 使えない / 1: 項目をそのまま使える / 2: 箱がまるごと {@code (hitDx, hitDy)} だけ動いただけ
     * （または、クリップだけが違う）なので、前回の項目を平行移動して使える。
     * 2 を返すのは {@link #translating}（スクロールなど位置だけが変わる作り直し）で、記憶が平行移動に耐える箱のときだけ。
     */
    private int match(BoxMemo m, LayoutBox box, ClipRect clip, String parentId) {
        if (!sameStyle(m.style, box.style) || m.canvasSource != (box == canvasBackgroundSource) || m.interactive != box.hasInteractiveRules
                || m.suppressShadow != suppressTextShadow) return 0;
        boolean moveOk = translating && m.translatable;
        float[] nn = numsOf(box);
        float dx = nn[0] - m.nums[0], dy = nn[1] - m.nums[1];
        boolean moved = dx != 0 || dy != 0;
        if (moved && !moveOk) return 0;
        for (int i = 2; i < nn.length; i++) if (Float.compare(m.nums[i], nn[i]) != 0) return 0;
        boolean clipSame = sameClip(m.clip, clip);
        if (!clipSame && !moveOk) return 0;
        if (!java.util.Objects.equals(m.parentId, parentId)) return 0;
        if (!java.util.Objects.equals(m.displayText, box.displayText) || !java.util.Objects.equals(m.shaped, box.shapedLines)) return 0;
        if (!m.attrs.equals(box.node.attrs)) return 0;
        int depth = 0;
        for (LayoutBox a = box.parent; a != null; a = a.parent, depth++) {
            if (depth >= m.ancestors.length || !sameStyle(m.ancestors[depth], a.style)) return 0;
        }
        if (depth != m.ancestors.length) return 0;
        List<LayoutBox.InlineFragment> frags = box.inlineFragments;
        if (m.fragText.length != frags.size()) return 0;
        int r = 0;
        for (int i = 0; i < frags.size(); i++) {
            LayoutBox.InlineFragment f = frags.get(i);
            int n = i * 8;
            if (!java.util.Objects.equals(m.fragText[i], f.text)) return 0;
            // 断片の位置は、箱と同じだけ動いていること（動き方が違う断片があれば、平行移動では済まない）
            if (moved ? (Math.abs(f.x - m.fragNums[n] - dx) > 1e-3f || Math.abs(f.y - m.fragNums[n + 1] - dy) > 1e-3f)
                    : (Float.compare(m.fragNums[n], f.x) != 0 || Float.compare(m.fragNums[n + 1], f.y) != 0)) return 0;
            if (Float.compare(m.fragNums[n + 2], f.w) != 0 || Float.compare(m.fragNums[n + 3], f.h) != 0
                    || Float.compare(m.fragNums[n + 4], f.baseline) != 0 || m.fragNums[n + 5] != (f.startEdge ? 1f : 0f)
                    || m.fragNums[n + 6] != (f.endEdge ? 1f : 0f) || Float.compare(m.fragNums[n + 7], f.descent) != 0) return 0;
            if (m.fragRefs[r++] != f.box || !sameStyle((com.nishiyu.enderframelib.core.css.ComputedStyle) m.fragRefs[r++], f.box != null ? f.box.style : null)) return 0;
            List<LayoutBox> parents = f.parents;
            int pn = parents == null ? 0 : parents.size();
            if (!(m.fragRefs[r++] instanceof Integer count) || count != pn) return 0;
            for (int k = 0; k < pn; k++) {
                if (m.fragRefs[r++] != parents.get(k) || !sameStyle((com.nishiyu.enderframelib.core.css.ComputedStyle) m.fragRefs[r++], parents.get(k).style)) return 0;
            }
        }
        if (!moved && clipSame) return 1;
        hitDx = dx;
        hitDy = dy;
        return 2;
    }

    /**
     * 前回の項目を {@code (dx, dy)} を四捨五入した分だけ動かし、クリップを今のものに替えた写し。項目の座標は整数なので、
     * 位置を整数に丸めたあとの相対関係（箱の中の項目どうしの並び）は崩れない。
     */
    private static List<DisplayItem> translated(BoxMemo m, ClipRect newClip, float dx, float dy) {
        int ix = Math.round(dx), iy = Math.round(dy);
        List<DisplayItem> out = new ArrayList<>(m.items.size());
        for (DisplayItem it : m.items) {
            RenderMeta meta = it.meta();
            if (meta != null && meta.clipRect() != null) {
                meta = new RenderMeta(newClip, meta.borderRadii(), meta.circleBorder(), meta.fontHeight(), meta.baseline(),
                        meta.lineHeight(), meta.originX(), meta.originY(), meta.itemCount(), meta.interactiveState(),
                        meta.roundedBorder(), meta.hints());
            }
            out.add(new DisplayItem(it.id(), it.baseNodeId(), it.type(), it.x() + ix, it.y() + iy, it.width(), it.height(),
                    it.text(), it.color(), it.bgColor(), it.events(), it.radius(), it.opacity(), it.translateX(), it.translateY(),
                    it.scaleX(), it.scaleY(), it.rotate(), it.transitionDuration(), it.animDef(), it.fitMode(), meta, it.key()));
        }
        return out;
    }

    /**
     * 項目が平行移動だけで作り直せるか: 座標を持つのが x・y と（前回のクリップそのものの）クリップだけで、
     * 箱の座標で決まる図形（影・グラデーション・clip-path・backdrop）や、画面に固定の背景を持たない。
     */
    private static boolean survivesTranslation(LayoutBox box, ClipRect clip, List<DisplayItem> items) {
        String attachment = box.style.get("background-attachment");
        if (attachment != null && attachment.contains("fixed")) return false;
        for (DisplayItem it : items) {
            ElementType t = it.type();
            if (t != ElementType.NODE && t != ElementType.RECT && t != ElementType.TEXT && t != ElementType.ROUNDED_BORDER) return false;
            RenderMeta meta = it.meta();
            if (meta == null) continue;
            if (meta.clipRect() != null && !sameClip(meta.clipRect(), clip)) return false;
            RenderHints h = meta.hints();
            if (h.shadowShape() != null || h.shaderGradient() != null) return false;
            LayerSpec layer = h.layer();
            if (layer != null && (layer.clip() != null || layer.backdropShape() != null)) return false;
        }
        return true;
    }

    private BoxMemo remember(LayoutBox box, ClipRect clip, String parentId, List<DisplayItem> items) {
        BoxMemo m = new BoxMemo();
        m.epoch = buildEpoch;
        m.parentBox = box.parent;
        m.items = items;
        m.style = box.style;
        m.ancestors = ancestorStyles(box);
        m.nums = numsOf(box);
        m.clip = clip;
        m.parentId = parentId;
        m.attrs = new java.util.HashMap<>(box.node.attrs);
        m.displayText = box.displayText;
        m.shaped = box.shapedLines == null ? null : List.copyOf(box.shapedLines);
        List<LayoutBox.InlineFragment> frags = box.inlineFragments;
        m.fragText = new String[frags.size()];
        m.fragNums = new float[frags.size() * 8];
        List<Object> refs = new ArrayList<>();
        for (int i = 0; i < frags.size(); i++) {
            LayoutBox.InlineFragment f = frags.get(i);
            m.fragText[i] = f.text;
            int n = i * 8;
            m.fragNums[n] = f.x;
            m.fragNums[n + 1] = f.y;
            m.fragNums[n + 2] = f.w;
            m.fragNums[n + 3] = f.h;
            m.fragNums[n + 4] = f.baseline;
            m.fragNums[n + 5] = f.startEdge ? 1f : 0f;
            m.fragNums[n + 6] = f.endEdge ? 1f : 0f;
            m.fragNums[n + 7] = f.descent;
            refs.add(f.box);
            refs.add(f.box != null ? f.box.style : null);
            int pn = f.parents == null ? 0 : f.parents.size();
            refs.add(pn);
            for (int k = 0; k < pn; k++) {
                refs.add(f.parents.get(k));
                refs.add(f.parents.get(k).style);
            }
        }
        m.fragRefs = refs.toArray();
        m.canvasSource = box == canvasBackgroundSource;
        m.interactive = box.hasInteractiveRules;
        m.suppressShadow = suppressTextShadow;
        m.translatable = survivesTranslation(box, clip, items);
        m.generation = generation;
        return m;
    }

    /** 直近の描画で、中身を 1 つの節にまとめたスクロールコンテナの記録（スクロールだけの更新で、表示リストを作り直さずに済ませる）。 */
    public List<com.nishiyu.enderframelib.core.paint.model.ScrollContentInfo> scrollContents() {
        return order.scrollContentInfos();
    }

    private void drawBoxSelf(List<DisplayItem> elements, LayoutBox box, ClipRect clipRect, String parentId) {
        drawBoxSelfMemo(elements, box, clipRect, parentId);
        PaintOrder.ScrollContent content = order.scrollContentOf(box);
        if (content != null) order.addMovingNode(elements, content.contentId, box, null); // 箱の項目のあと・子孫の項目の前
    }

    private void drawBoxSelfMemo(List<DisplayItem> elements, LayoutBox box, ClipRect clipRect, String parentId) {
        if (!MEMO_ENABLED || !memoizable(box)) {
            STAT_UNMEMOIZABLE.increment();
            fragIds.set(0);
            drawBoxSelfUncached(elements, box, clipRect, parentId);
            return;
        }
        BoxMemo m = boxMemo.get(box);
        int kind;
        if (useChangeStamps && m != null && !translating && stampClean(m, box, clipRect, parentId)) {
            kind = 1;
            STAT_STAMPED.increment();
            if (verifyStamps) {
                int check = match(m, box, clipRect, parentId);
                if (check != 1) throw new AssertionError("変更印は変わっていないと言ったが署名が違う: box=" + box.node.tag + "#" + box.node.getId()
                        + " match=" + check + " stamp=" + box.pathPaintStamp(buildSerial) + " epoch=" + m.epoch);
            }
        } else {
            kind = m == null ? 0 : match(m, box, clipRect, parentId);
        }
        if (kind == 1) {
            STAT_EXACT.increment();
            m.generation = generation;
            m.epoch = buildEpoch;
            elements.addAll(m.items);
            return;
        }
        if (kind == 2) { // 箱が動いただけ。記憶は動く前のまま残す（差が積もっても、丸めの誤差は 1 画素以内）
            m.generation = generation;
            elements.addAll(translated(m, clipRect, hitDx, hitDy));
            translatedHits++;
            STAT_TRANSLATED.increment();
            return;
        }
        (m == null ? STAT_NEW : STAT_MISS).increment();
        fragIds.set(0); // 部品の ID は箱ごとに数える
        int start = elements.size();
        drawBoxSelfUncached(elements, box, clipRect, parentId);
        boxMemo.put(box, remember(box, clipRect, parentId, List.copyOf(elements.subList(start, elements.size()))));
    }

    private void drawBoxSelfUncached(List<DisplayItem> elements, LayoutBox box, ClipRect clipRect, String parentId) {
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
        if (tr.length > 5 && tr[5] != 0f) anchorEvents.skew(tr[5]);
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

        ClipRect normalizedClip = clipRect;
        RenderMeta anchorMeta = new RenderMeta(normalizedClip, radii, null, fontHVal, 0f, box.lineHeightPx, ox, oy, itemCount, interactive);
        addElement(elements, id, id, ElementType.NODE, box.x, box.y, box.w, box.h, "", color, bgColor, anchorEvents, 0, opacity, tx, ty, sx, sy, rot, transDur, animDef, anchorMeta);

        // visibility: hidden の箱は場所だけを取る。錨（NODE）は子孫の変形と不透明度の親として残す
        // 何が壊れていたか: 以前は visibility を読んでおらず、hidden の箱が描かれクリックも受けていた
        if (isInvisible(box) || PaintSupport.hidesEmptyCell(box)) return;

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
            case "img", "video", "audio", "mc-fluid", "mc-entity" -> controls.drawMediaElement(elements, box, id, color, bgColor, fallbackRadius, radii, normalizedClip, fontHVal, ox, oy, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
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
            case "svg" -> controls.drawSvgElement(elements, box, id, color, bgColor, fallbackRadius, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
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
        decor.drawColumnRules(elements, box, id, color, passiveBorderEvents, baseMeta, transDur);
        decor.drawOutline(elements, box, id, color, passiveBorderEvents, baseMeta, transDur);
    }

    /**
     * 文字 1 行（断片）を足す。{@code text-shadow} があれば、後ろに書いた影から先に（下に）、ずらした影色の文字を出してから本体を出す。
     * ぼかしのある影は、錨（NODE）に {@code blur} のレイヤーを載せ、その中に影の文字を置く（ぼかしの σ は半径の半分）。
     * 影はクリックを受けず、色のアニメーション・トランジションも受けない。
     */
    private void addTextRun(List<DisplayItem> elements, String id, float x, float y, float w, float h, String text, int color, int bgColor,
                            ItemProps events, RenderMeta meta, int transDur, AnimationSpec colorAnim, List<BoxDecorations.Shadow> shadows) {
        for (int i = shadows.size() - 1; i >= 0; i--) {
            BoxDecorations.Shadow sh = shadows.get(i);
            if ((sh.color() >>> 24) == 0) continue;
            ItemProps shadowEvents = events.copy();
            shadowEvents.pointerEventsNone();
            boolean blurred = sh.blur() > 0f;
            if (blurred) {
                String anchorId = id + "_tsh" + fragIds.getAndIncrement();
                ItemProps anchorEvents = new ItemProps();
                String owner = events.hints().parentId();
                if (owner != null) anchorEvents.parentId(owner);
                anchorEvents.pointerEventsNone();
                addElement(elements, id, anchorId, ElementType.NODE, x, y, w, h, "", 0, 0, anchorEvents, 0, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, meta);
                int at = elements.size() - 1;
                shadowEvents.parentId(anchorId);
                addElement(elements, id, id + "_txt_" + (fragIds.getAndIncrement()), ElementType.TEXT, x + sh.offsetX(), y + sh.offsetY(), w, h, text,
                        sh.color(), 0, shadowEvents, 0, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, meta);
                LayerSpec spec = new LayerSpec(elements.size() - at, 1f, List.of(FilterOp.blur(sh.blur() / 2f)), BlendMode.NORMAL, false);
                elements.set(at, withLayer(elements.get(at), spec));
            } else {
                addElement(elements, id, id + "_txt_" + (fragIds.getAndIncrement()), ElementType.TEXT, x + sh.offsetX(), y + sh.offsetY(), w, h, text,
                        sh.color(), 0, shadowEvents, 0, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, meta);
            }
        }
        addElement(elements, id, id + "_txt_" + (fragIds.getAndIncrement()), ElementType.TEXT, x, y, w, h, text, color, bgColor, events, 0, 1f, 0, 0, 1f, 1f, 0, transDur, colorAnim, meta);
    }

    /** {@code -webkit-text-stroke-width} の CSS px（{@code thin / medium / thick} は 1 / 3 / 5）。無い・0 以下は 0。 */
    static float textStrokeWidth(com.nishiyu.enderframelib.core.css.ComputedStyle style) {
        String v = style.get("-webkit-text-stroke-width");
        if (v == null) return 0f;
        v = v.trim().toLowerCase(java.util.Locale.ROOT);
        try {
            float w = switch (v) {
                case "thin" -> 1f;
                case "medium" -> 3f;
                case "thick" -> 5f;
                default -> v.endsWith("rem") ? Float.parseFloat(v.substring(0, v.length() - 3)) * style.rootFontSizePx()
                        : v.endsWith("em") ? Float.parseFloat(v.substring(0, v.length() - 2)) * style.fontSizePx()
                        : v.endsWith("px") ? Float.parseFloat(v.substring(0, v.length() - 2))
                        : Float.parseFloat(v);
            };
            return w > 0f && Float.isFinite(w) ? w : 0f;
        } catch (NumberFormatException e) {
            return 0f;
        }
    }

    private void drawTextNodeElement(List<DisplayItem> elements, LayoutBox box, String id, int color, int bgColor,
                                     ClipRect normalizedClip, float fontHVal, float ox, float oy, ItemProps partsEvents,
                                     int transDur, AnimationSpec colorAnim) {
        ItemProps baseTextEvents = partsEvents.copy();
        if (box.style.containsKey(CssProperty.FONT_WEIGHT)) baseTextEvents.put("font-weight", box.style.get(CssProperty.FONT_WEIGHT));
        if (box.style.containsKey(CssProperty.FONT_FAMILY)) baseTextEvents.put("font-family", box.style.get(CssProperty.FONT_FAMILY));
        if (box.style.containsKey(CssProperty.TEXT_DECORATION)) baseTextEvents.put("text-decoration", box.style.get(CssProperty.TEXT_DECORATION));
        // text-shadow: 「x y [ぼかし] [色]」の並びは本物の影（ずらした色付きの文字。ぼかしがあれば合成レイヤーで）。
        // 解いて読めない値（旧来の text-shadow:true など）は、従来の「右下に暗い文字を重ねる」影のまま
        List<BoxDecorations.Shadow> textShadows = List.of();
        String shadowValue = box.style.get(CssProperty.TEXT_SHADOW);
        if (shadowValue != null && !shadowValue.isBlank() && !"none".equalsIgnoreCase(shadowValue.trim())) {
            if (!suppressTextShadow) textShadows = BoxDecorations.parseBoxShadows(shadowValue, color, rootW, rootH, box.style.fontSizePx(), box.style.rootFontSizePx())
                    .stream().filter(sh -> !sh.inset()).toList();
            if (textShadows.isEmpty() && !suppressTextShadow) baseTextEvents.put("text-shadow", "true");
        }
        putFontEvents(box, baseTextEvents);
        float strokeWidth = textStrokeWidth(box.style);
        if (strokeWidth > 0f) {
            String colorValue = box.style.get("-webkit-text-stroke-color");
            int strokeColor = colorValue == null || colorValue.isBlank() || "currentcolor".equalsIgnoreCase(colorValue.trim())
                    ? color : ColorParser.parseColor(colorValue);
            baseTextEvents.put("text-stroke", strokeWidth + " " + Integer.toHexString(strokeColor));
        }

        if (!box.inlineFragments.isEmpty()) {
            for (LayoutBox.InlineFragment frag : box.inlineFragments) {
                if (frag.text == null || frag.text.isBlank()) continue;
                RenderMeta fragMeta = new RenderMeta(
                        normalizedClip, null, null, fontHVal, frag.baseline, frag.h, ox, oy, 1, false);
                addTextRun(elements, id, frag.x, frag.y, frag.w, frag.h, frag.text, color, bgColor, baseTextEvents, fragMeta, transDur, colorAnim, textShadows);
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
                    addTextRun(elements, id, lineX, lineY, lineW, line.height(), line.text(), color, bgColor, baseTextEvents, lineMeta, transDur, colorAnim, textShadows);
                }
                lineY += line.height();
            }
        }
    }
}
