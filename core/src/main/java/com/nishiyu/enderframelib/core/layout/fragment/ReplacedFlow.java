package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.box.ReplacedInput;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.dom.node.CustomElementPainter;
import com.nishiyu.enderframelib.core.dom.node.ElementTraits;
import com.nishiyu.enderframelib.core.replaced.ReplacedElements;
import com.nishiyu.enderframelib.core.kernel.LayoutUnit;
import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;
import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.text.model.FontStyle;

/**
 * 置換要素とフォーム部品（Engine v3 M2b-1）。中身を整形せず、内在の大きさ（と比）から箱の大きさを決める。供給元は旧経路と同じ
 * （{@link ReplacedElements}・{@code canvas} / {@code svg} の属性・{@code img} の候補と SVG の data URI）で、入力側が
 * {@link ReplacedInput} に写したものだけを読む。計算は旧 {@code BoxMeasurer} / {@code ElementSizing} の移植（箱へ書かず断片で返す）。
 *
 * <ul>
 *   <li>画像類（{@code img} / {@code canvas} / {@code svg} / ホストの画像）: 内在の幅・高さ。分からなければ既定の正方形（読み込み中の
 *       リモート画像は大きめ）。幅だけ指定されたら比で高さ、高さだけなら比で幅（CSS 2 §10.3.2・§10.6.2）。</li>
 *   <li>3D の場面（ホストの {@code SCENE}）: 既定の大きさ、または包含ブロックの幅いっぱい。</li>
 *   <li>部品（{@code input} / {@code select} / {@code progress}）: 既定の幅（{@code size} 属性・選択肢の最長の文字）と、書体から決まる高さ。</li>
 * </ul>
 *
 * <p>範囲外: 外部の画像の大きさの解決（読み込み後の大きさ。旧経路は {@code IntrinsicSizeResolver}。読み込みの完了を台帳へ流す形で
 * M2c に入れる）・{@code object-fit}（描画の側）。{@code video} / {@code iframe} は中身を描かず既定の物体の大きさ 300 x 150
 * （属性の {@code width} / {@code height} があればそれ。旧経路は置換要素として扱っていない）。</p>
 */
final class ReplacedFlow implements FormattingEngine {
    static final ReplacedFlow INSTANCE = new ReplacedFlow();

    private ReplacedFlow() { }

    /** 中身を整形しない置換要素か（入力側が写しを作った箱。{@code button} は中身を持つので含めない）。 */
    static boolean is(BoxNode n) {
        return n.replaced != null && n.pseudo == BoxNode.Pseudo.NONE;
    }

    /** 内容ボックスの内在の {幅, 高さ}、画像類か（比を使う）、包含ブロックの幅いっぱいか（3D の場面）。 */
    private record Intrinsic(float w, float h, boolean image, boolean fillsWidth) {
        float ratio() {
            return image && w > 0 && h > 0 ? w / h : Float.NaN;
        }
    }

    /** 写し（{@link ReplacedInput}）とスタイルの書体だけから内在の大きさを決める（生きた DOM は読まない）。 */
    private static Intrinsic intrinsic(BoxNode n, LayoutSession s) {
        ReplacedInput r = n.replaced;
        String tag = r.tag();
        if (ReplacedElements.isHostScene(tag)) {
            CustomElementPainter p = ReplacedElements.painterOf(tag);
            return new Intrinsic(p.sceneMinWidth(), p.sceneHeight(), false, p.sceneFillsWidth());
        }
        if (ElementTraits.isEmbeddedMedia(tag)) return new Intrinsic(r.width(), r.height(), false, false); // 読み込み前の既定の大きさ
        if (ElementTraits.isBuiltinReplacedImage(tag) || ReplacedElements.isHostImageLike(tag)) {
            if (r.width() > 0 && r.height() > 0) return new Intrinsic(r.width(), r.height(), true, false);
            float fb = r.pending() ? LayoutMetrics.PENDING_IMAGE_SIZE : LayoutMetrics.FALLBACK_REPLACED_SIZE;
            return new Intrinsic(fb, fb, false, false);
        }
        return new Intrinsic(controlWidth(n, s), controlHeight(n, s), false, false);
    }

    /** 部品の内容の幅: input / progress は 90（{@code size} 属性なら 8 px 倍・チェック類は固定）、select は選択肢の最長の文字。 */
    private static float controlWidth(BoxNode n, LayoutSession s) {
        ReplacedInput r = n.replaced;
        if (ElementTraits.isSelect(r.tag())) {
            FontFace face = s.face(n.style);
            FontStyle fs = n.style.facts.fontStyle;
            float max = face.advance(" ▾", fs);
            for (String label : r.labels()) max = Math.max(max, face.advance(label + " ▾", fs));
            return max;
        }
        if (ElementTraits.isInputTag(r.tag()) && (r.type().equals("checkbox") || r.type().equals("radio"))) {
            return LayoutMetrics.FALLBACK_REPLACED_SIZE;
        }
        return r.size() >= 0 ? r.size() * 8 : 90;
    }

    /**
     * 部品の内容の高さ（Chrome〔Windows〕の実測に合わせた旧経路の値）: 文字の入力欄は {@code line-height} と書体の normal の行の
     * 高さの大きい方、select は ascent + descent + 2、チェック類・range は固定、progress は 12。
     */
    private static float controlHeight(BoxNode n, LayoutSession s) {
        ReplacedInput r = n.replaced;
        if (ElementTraits.isGauge(r.tag())) return 12;
        boolean select = ElementTraits.isSelect(r.tag());
        String type = r.type();
        if (!select && (type.equals("checkbox") || type.equals("radio") || type.equals("range"))) return LayoutMetrics.FALLBACK_REPLACED_SIZE;
        FontFace face = s.face(n.style);
        FontStyle fs = n.style.facts.fontStyle;
        if (select) return face.ascent(fs) + face.descent(fs) + 2;
        return Math.max(InlineFlow.lineHeight(n.style, face), face.normalLineHeight(fs));
    }

    @Override
    public LayoutFragment layout(BoxNode node, ConstraintSpace space, LayoutFragment prior, LayoutSession s) {
        Style st = node.style;
        float cbw = space.availableWidth;
        BoxGeometry g = new BoxGeometry(st, cbw, s);
        Intrinsic in = intrinsic(node, s);
        float ratio = in.ratio();
        float pct = space.percentHeightBase;
        float specH = !Float.isNaN(space.fixedHeight) ? space.fixedHeight : g.specifiedHeight(st, pct, s);
        float w, ml = g.mL;
        if (!Float.isNaN(space.fixedWidth)) {
            w = space.fixedWidth;
        } else {
            float spec = g.specifiedWidth(st, cbw, s);
            if (Float.isNaN(spec)) {
                if (!Float.isNaN(specH) && ratio > 0) spec = Math.max(0, specH - g.vdec()) * ratio + g.hdec();
                else if (in.fillsWidth()) spec = Math.max(in.w(), cbw - g.mL - g.mR);
                else spec = in.w() + g.hdec();
            }
            w = g.clampWidth(st, spec, cbw, s);
            if (node.kind.blockLevel() && g.autoML && g.autoMR) ml = Math.max(0, (cbw - w) / 2f);
            else if (node.kind.blockLevel() && g.autoML) ml = Math.max(0, cbw - w - g.mR);
        }
        w = LayoutUnit.snap(w);
        float h = specH;
        if (Float.isNaN(h)) h = ratio > 0 ? Math.max(0, w - g.hdec()) / ratio + g.vdec() : in.h() + g.vdec();
        if (Float.isNaN(space.fixedHeight)) h = g.clampHeight(st, h, pct, s);
        h = LayoutUnit.snap(h);
        return new LayoutFragment(node, space, w, h, LayoutUnit.snap(ml), Math.max(0, g.mT), Math.min(0, g.mT),
                Math.max(0, g.mB), Math.min(0, g.mB), g.bL + g.pL, g.bT + g.pT, false, null, null, null, null);
    }

    /** 内在サイズ: 指定の幅が無ければ内在の幅（高さだけ指定された画像は比から）。min と max は同じ。 */
    @Override
    public MinMaxSizes computeMinMaxSizes(BoxNode node, MinMaxContext ctx, LayoutSession s) {
        Style st = node.style;
        BoxGeometry g = new BoxGeometry(st, Float.NaN, s);
        Intrinsic in = intrinsic(node, s);
        float ratio = in.ratio();
        float specH = g.specifiedHeight(st, Float.NaN, s);
        float w = !Float.isNaN(specH) && ratio > 0 ? Math.max(0, specH - g.vdec()) * ratio + g.hdec() : in.w() + g.hdec();
        w = LayoutUnit.snap(w);
        return new MinMaxSizes(w, w);
    }
}
