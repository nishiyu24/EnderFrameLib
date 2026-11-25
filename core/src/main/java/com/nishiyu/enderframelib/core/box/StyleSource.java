package com.nishiyu.enderframelib.core.box;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.css.SelectorMatcher;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.css.StyleResolver;
import com.nishiyu.enderframelib.core.css.StyleAnimator;
import com.nishiyu.enderframelib.core.css.StyleSheet;
import com.nishiyu.enderframelib.core.css.model.ContainerBox;
import com.nishiyu.enderframelib.core.css.model.StateLookup;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;

import java.util.Map;

/**
 * 要素 1 つの算出スタイルを解く（Engine v3 M2a）。{@link BoxTreeCompiler} は「この節を解き直すべきか」だけを決め、
 * 解き方はここに閉じる。M1 のカスケード（{@link StyleResolver}）をそのまま呼ぶ。
 *
 * <p>ルート要素（{@code html}）の文字の大きさと行の高さ（{@code rem} / {@code rlh} の基準）は、ルート要素を解いたときに
 * この実例が覚える（1 ビューに閉じた状態。プロセス共有はしない）。</p>
 */
public final class StyleSource {
    private final StyleSheet sheet;
    private final StateLookup lookup;
    private final Map<String, String> env;
    private final int viewportW, viewportH;
    private float rootFontSize = ComputedStyle.DEFAULT_FONT_SIZE;
    private float rootLineHeight = Float.NaN;

    public StyleSource(StyleSheet sheet, StateLookup lookup, Map<String, String> env, int viewportW, int viewportH) {
        this.sheet = sheet;
        this.lookup = lookup != null ? lookup : SelectorMatcher.STATIC_ATTRIBUTES;
        this.env = env != null ? env : Map.of();
        this.viewportW = viewportW;
        this.viewportH = viewportH;
    }

    public StyleSheet sheet() { return sheet; }
    public int viewportW() { return viewportW; }
    public int viewportH() { return viewportH; }

    /** 要素の算出スタイル。{@code parent} は親の {@link Style}（根の直下は {@link Style#DEFAULT}）。 */
    public Style element(HtmlNode node, Style parent) {
        return element(node, parent, null);
    }

    /**
     * 要素の算出スタイル。{@code before} は前回の算出スタイル（アニメーションの値を含む。初めてなら null）。トランジションと
     * {@code @keyframes} の値（{@link StyleAnimator}。時刻は {@link InteractionState#animationTimeMs} で注入できる）を目標値に重ねる
     * （計画書 §3.4.2-5。旧 {@code RenderPipeline.animationValues} と同じ規則で、opacity・transform・色は描画の側）。動いている節は
     * 駆動側が毎フレーム {@link #appendAnimating} で台帳へ {@code STYLE} を足す（入口は台帳 1 本）。
     */
    public Style element(HtmlNode node, Style parent, Style before) {
        Style p = parent != null ? parent : Style.DEFAULT;
        boolean isRoot = node.parent != null && node.parent.parent == null && "html".equalsIgnoreCase(node.tag);
        float rootFont = isRoot ? ComputedStyle.DEFAULT_FONT_SIZE : rootFontSize;
        float rootLh = isRoot ? Float.NaN : rootLineHeight;
        Map<String, String> raw = StyleResolver.resolveStyleSet(node, p, sheet).resolve(lookup);
        if (node.attrs.containsKey("hidden")) raw.put(CssProperty.DISPLAY, "none");
        ComputedStyle cs = StyleResolver.finalizeComputedStyleOwned(raw, sheet, env, p, p.inherited.fontSize,
                rootFont, rootLh, viewportW, viewportH, ContainerBox.NONE_SIZE);
        StyleAnimator anim = animator();
        if (anim != null && sheet != null && !node.isPseudoNode) {
            long now = ((InteractionState) lookup).animationTimeMs();
            anim.observe(node, before == null ? null : new ComputedStyle(before), cs, p, sheet.keyframes, now);
            Map<String, String> values = anim.sample(node, now);
            if (!values.isEmpty()) { // 動いている: 目標値の宣言にアニメーションの値を重ねて解き直す
                Map<String, String> animated = StyleResolver.resolveStyleSet(node, p, sheet).resolve(lookup);
                if (node.attrs.containsKey("hidden")) animated.put(CssProperty.DISPLAY, "none");
                animated.putAll(values);
                cs = StyleResolver.finalizeComputedStyleOwned(animated, sheet, env, p, p.inherited.fontSize,
                        rootFont, rootLh, viewportW, viewportH, ContainerBox.NONE_SIZE);
            }
        }
        if (isRoot) {
            rootFontSize = cs.fontSizePx();
            rootLineHeight = cs.lineHeightPx();
        }
        return Style.of(cs, p);
    }

    /**
     * {@code ::before}（{@code after} なら {@code ::after}）の算出スタイル（M1 のカスケードをそのまま呼ぶ。継承元は持ち主の要素）。
     * 箱を作らない（{@code content} が none / normal・{@code display: none}・規則が無い）なら null。
     */
    public Style pseudo(HtmlNode owner, Style ownerStyle, boolean after) {
        ComputedStyle cs = com.nishiyu.enderframelib.core.css.GeneratedContentStyles.resolve(owner, after, sheet, lookup,
                new ComputedStyle(ownerStyle), env, rootFontSize, rootLineHeight, viewportW, viewportH);
        return cs == null ? null : Style.of(cs, ownerStyle);
    }

    /** {@code ::marker} の規則が宣言したスタイル（規則が無ければ null。{@link MarkerBoxes}）。 */
    public com.nishiyu.enderframelib.core.css.ComputedStyle markerDeclared(HtmlNode owner, Style ownerStyle) {
        return com.nishiyu.enderframelib.core.css.GeneratedContentStyles.markerDeclared(owner, sheet, lookup, new ComputedStyle(ownerStyle),
                env, rootFontSize, rootLineHeight, viewportW, viewportH);
    }

    private StyleAnimator animator() {
        return lookup instanceof InteractionState is ? is.animator() : null;
    }

    /**
     * 今の時刻で値が変わる（動いている・動き終えた）要素を台帳へ {@code STYLE} として足す（フレームの始め、台帳を凍結する前に駆動側が
     * 呼ぶ。時刻は {@link InteractionState#animationTimeMs}）。
     */
    public void appendAnimating(com.nishiyu.enderframelib.core.dom.node.ChangeLedger ledger) {
        StyleAnimator anim = animator();
        if (anim == null) return;
        for (HtmlNode n : anim.advance(((InteractionState) lookup).animationTimeMs())) {
            ledger.append(n, com.nishiyu.enderframelib.core.dom.node.ChangeKind.STYLE);
        }
    }

    /** 文字の節のスタイル（親の継承群をそのまま共有する）。 */
    public Style text(Style parent) {
        return Style.forTextChild(parent);
    }
}
