package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.WritingModes;
import com.nishiyu.enderframelib.core.layout.model.ConstraintSpace;

/**
 * 縦書きの中に置かれた {@code writing-mode: horizontal-tb} の根（縦中横・直交ネスト）のエンジン。
 *
 * <p>内容は {@link LayoutBox#isVerticalWrapper 逆回転を持つ生成箱} 1 つに包まれ、その中身を通常の水平座標で組む。
 * 親は縦書きの回転空間なので、根の幅 {@code w} が水平内容の高さ {@code wrapper.h}、
 * 根の高さ {@code h} が水平内容の幅 {@code wrapper.w} になる。
 */
final class HorizontalReturnEngine extends LayoutEngine {

    private static LayoutBox wrapperOf(LayoutBox box) {
        for (int i = 0, n = box.children.size(); i < n; i++) {
            LayoutBox c = box.children.get(i);
            if (c.isVerticalWrapper) return c;
        }
        return null;
    }

    @Override
    public float measureContent(LayoutBox box, ConstraintSpace space, LayoutContext ctx) {
        LayoutBox wrapper = wrapperOf(box);
        if (wrapper == null) return 0;
        wrapper.measureMemo.noteParentReadOpaque();
        float parentW = space.percentageBlockSize();
        float availW;
        if (!Float.isNaN(parentW) && parentW > 0) {
            availW = parentW;
        } else {
            MeasureReads.recordInput(MeasureReads.READ_VIEWPORT_W);
            availW = ctx.viewport().rootW();
        }
        float availH = space.availableInlineSize();
        wrapper.measure(ConstraintSpace.block(availW, availH), ctx);
        return wrapper.h;
    }

    @Override
    public float contentBlockSize(LayoutBox box, ConstraintSpace space, LayoutContext ctx) {
        LayoutBox wrapper = wrapperOf(box);
        return wrapper != null ? wrapper.w : 0;
    }

    @Override
    public void finishLayout(LayoutBox box, ConstraintSpace space, LayoutContext ctx) {
        measureAbsoluteChildren(box, ctx);
    }

    @Override
    public void arrangeChildren(LayoutBox box, float innerX, float innerY, LayoutContext ctx) {
        LayoutBox wrapper = wrapperOf(box);
        if (wrapper == null) return;
        int flow = box.orthogonalParentFlow & 0xFF;
        boolean tr = WritingModes.isTransposed(flow);
        boolean parentCcw = WritingModes.isCounterClockwise(flow);
        // vertical-rl / sideways-rl 内の水平箱は -90°（上へ wrapper.w 張り出す）→ dy = wrapper.w
        // sideways-lr 内の水平箱は +90°（左へ wrapper.h 張り出す）→ dx = wrapper.h
        // vertical-lr 内の水平箱は転置（張り出さない）→ dx = 0, dy = 0
        float dx = !tr && parentCcw ? wrapper.h : 0;
        float dy = !tr && !parentCcw ? wrapper.w : 0;
        wrapper.layout(innerX + dx, innerY + dy, wrapper.w, wrapper.h, ctx);
    }
}
