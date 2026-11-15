package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssPropertyIndex;
import com.nishiyu.enderframelib.core.css.StyleFacts;
import com.nishiyu.enderframelib.core.css.WritingModes;
import com.nishiyu.enderframelib.core.layout.model.ConstraintSpace;

/**
 * 縦書きの根（{@code writing-mode: vertical-rl | sideways-rl | sideways-lr}）のエンジン（CSS Writing Modes §7 の直交フロー）。
 *
 * <p>根の内容は {@link LayoutBox#isVerticalWrapper 回転を持つ生成箱} 1 つに包まれていて、その箱の中は<strong>論理（横書き）の軸</strong>で
 * 組む（行は左から右へ・段落は上から下へ）。論理の「行の長さ（インライン寸法）」が根の物理の高さ、論理の「積み方向（ブロック寸法）」が
 * 根の物理の幅になる。包んだ箱の {@code transform: rotate(±90deg)}（原点は左上）が描画で物理の縦書きにする。
 *
 * <ul>
 *   <li>行の長さ: 根の {@code height}（定まっていれば）。auto なら包含ブロックの高さ（定まっていれば）、無ければビューポートの高さ
 *       （直交フローの「使える大きさ」。CSS Writing Modes §7.3）。{@code max-height} は上限。</li>
 *   <li>根の幅（auto）: 論理のブロック寸法（行を積んだ高さ）。</li>
 *   <li>回転後の置き場所: 90° 回転は包んだ箱の左上を軸に左へ {@code h} だけ張り出すので、箱を右へ {@code h} ずらして置く
 *       （行が右から左へ積まれる）。-90° 回転は上へ {@code w} だけ張り出すので、箱を下へ {@code w} ずらす（行が左から右へ・文字は下から上）。</li>
 * </ul>
 *
 * <p>LIMITATION: 子孫の物理プロパティ（{@code width}・{@code margin-left} 等）は論理の軸で解釈される（根自身の寸法・余白・枠線・
 * padding は物理のまま）。縦書きの中の {@code horizontal-tb}（縦中横）・{@code vertical-lr} の行の積み方向（左から右）は未対応。
 */
final class VerticalWritingEngine extends LayoutEngine {

    /** 測定で決めた行の長さ（根の内容ボックスの物理の高さ）。 */
    private record State(float inline) implements LayoutEngineState {}

    private static LayoutBox wrapperOf(LayoutBox box) {
        for (int i = 0, n = box.children.size(); i < n; i++) {
            LayoutBox c = box.children.get(i);
            if (c.isVerticalWrapper) return c;
        }
        return null;
    }

    /** 根の内容ボックスの物理の高さ＝論理のインライン寸法。 */
    private static float inlineSize(LayoutBox box, ConstraintSpace space, LayoutContext ctx) {
        ComputedStyle style = box.style;
        StyleFacts facts = style.layoutFacts();
        float rootW = ctx.viewport().rootW();
        float rootH = ctx.viewport().rootH();
        float parentH = space.percentageBlockSize();
        float deco = box.verticalDecoration();
        float h;
        if (facts.hasHeightDeclaration && !facts.heightAuto && !(Float.isNaN(parentH) && facts.heightPercent)) {
            float specified = style.length(CssPropertyIndex.HEIGHT, parentH, rootW, rootH);
            h = facts.borderBox ? specified - deco : specified;
        } else if (!Float.isNaN(parentH) && parentH > 0) {
            h = parentH - box.mT - box.mB - deco;
        } else {
            h = rootH - box.mT - box.mB - deco;
        }
        if (style.containsKey(CssPropertyIndex.MAX_HEIGHT)) {
            String maxS = style.get(CssPropertyIndex.MAX_HEIGHT).trim();
            if (!"none".equals(maxS) && !maxS.isEmpty()) {
                float max = style.length(CssPropertyIndex.MAX_HEIGHT, parentH, rootW, rootH);
                if (max > 0) h = Math.min(h, facts.borderBox ? max - deco : max);
            }
        }
        return Math.max(0, h);
    }

    @Override
    public float measureContent(LayoutBox box, ConstraintSpace space, LayoutContext ctx) {
        LayoutBox wrapper = wrapperOf(box);
        float inline = inlineSize(box, space, ctx);
        box.engineState = new State(inline);
        if (wrapper == null) return 0;
        wrapper.measure(ConstraintSpace.block(inline, Float.NaN), ctx);
        return wrapper.h;
    }

    @Override
    public float contentBlockSize(LayoutBox box, ConstraintSpace space, LayoutContext ctx) {
        return box.engineState instanceof State s ? s.inline() : 0;
    }

    @Override
    public void finishLayout(LayoutBox box, ConstraintSpace space, LayoutContext ctx) {
        measureAbsoluteChildren(box, ctx);
    }

    @Override
    public void arrangeChildren(LayoutBox box, float innerX, float innerY, LayoutContext ctx) {
        LayoutBox wrapper = wrapperOf(box);
        if (wrapper == null) return;
        int flow = WritingModes.flowOf(box.style);
        boolean ccw = WritingModes.isCounterClockwise(flow);
        // 行は右端から積む。根の幅（指定）が内容より広ければ、右端に寄せる
        float innerW = Math.max(wrapper.h, box.w - box.horizontalDecoration());
        float dx = ccw || WritingModes.isTransposed(flow) ? 0 : innerW;
        float dy = ccw ? wrapper.w : 0; // 転置は張り出さない
        wrapper.layout(innerX + dx, innerY + dy, wrapper.w, wrapper.h, ctx);
    }
}
