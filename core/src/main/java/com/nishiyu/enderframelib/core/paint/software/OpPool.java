package com.nishiyu.enderframelib.core.paint.software;

import java.util.ArrayDeque;

/**
 * 実行し終えた画素命令の置き場。次のフレームの命令を作り直さず使い回す（記録中の命令はどこにも載っていない）。
 * {@link #release} は命令が掴んでいる参照（描画先・クリップ・字形など）を手放してから置き場へ戻す。
 */
final class OpPool {
    private final ArrayDeque<PixelOps.PaintOp> freePaint = new ArrayDeque<>();
    private final ArrayDeque<PixelOps.GradientOp> freeGradient = new ArrayDeque<>();
    private final ArrayDeque<PixelOps.GlyphOp> freeGlyph = new ArrayDeque<>();

    PixelOps.PaintOp acquirePaint() {
        PixelOps.PaintOp o = freePaint.pollLast();
        return o != null ? o : new PixelOps.PaintOp();
    }

    PixelOps.GradientOp acquireGradient() {
        PixelOps.GradientOp o = freeGradient.pollLast();
        return o != null ? o : new PixelOps.GradientOp();
    }

    PixelOps.GlyphOp acquireGlyph() {
        PixelOps.GlyphOp o = freeGlyph.pollLast();
        return o != null ? o : new PixelOps.GlyphOp();
    }

    /** 実行し終えた命令を、掴んでいる参照を手放して置き場へ戻す。 */
    void release(Op op) {
        op.px = null;
        if (op instanceof PixelOps.PaintOp p) {
            p.clip = null; p.shader = null; p.fast = null; p.pose = null; p.inv = null;
            freePaint.addLast(p);
        } else if (op instanceof PixelOps.GradientOp g) {
            g.clip = null; g.inv = null; g.g = null; g.rx = null; g.ry = null;
            freeGradient.addLast(g);
        } else if (op instanceof PixelOps.GlyphOp g) {
            g.g = null; g.lut = null; g.clip = null;
            freeGlyph.addLast(g);
        }
    }

    /** 置き場にある命令の数（テスト用）。 */
    int free() {
        return freePaint.size() + freeGradient.size() + freeGlyph.size();
    }
}
