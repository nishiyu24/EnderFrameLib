package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.diag.PipelineStage;
import com.nishiyu.enderframelib.core.diag.StageProfiler;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.LayoutContext;
import com.nishiyu.enderframelib.core.layout.model.FormattingContext;
import com.nishiyu.enderframelib.core.layout.model.Viewport;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.List;
import java.util.Map;

/**
 * 測った寸法はそのままに位置だけを決め直す走査（{@link RenderPipeline#repositionLayout} の本体。K5 で切り出した）。
 * {@code scrollOnly} が true なら配置の鍵で記憶を使い回し、false なら全箱の配置を捨てる。
 */
final class RepositionPass {
    private RepositionPass() { }

    static void run(LayoutBox rootBox, Document doc, Map<String, String> env, InteractionState interactionState,
                    int rootW, int rootH, ScrollState scrollState, FontResolver fonts, boolean scrollOnly) {
        Viewport viewport = new Viewport(rootW, rootH, rootBox.devicePixelRatio());
        LayoutContext.AutoVisibilitySession av = new LayoutContext.AutoVisibilitySession(
                rootW, rootH,
                doc != null ? b -> RenderTreeBuilder.materializeAutoContent(b, doc.sheet, env, rootW, rootH, interactionState) : null);
        long run = scrollOnly ? RenderPipeline.LAYOUT_RUNS.incrementAndGet() : RenderPipeline.LAYOUT_RUNS.get();
        LayoutContext ctx = new LayoutContext(viewport, RenderPipeline.effectiveIntrinsicResolver(), scrollState, fonts, scrollOnly, run, true, av);
        long prof = StageProfiler.begin(PipelineStage.REPOSITION);
        List<LayoutBox> staleFrags = RenderPipeline.STALE_FRAG_BOXES.get();
        staleFrags.clear();
        LayoutBox.resetPlacementsFast(rootBox, scrollOnly ? run : -1, staleFrags);
        try {
            rootBox.layout(0, 0, rootW, rootH, ctx);
        } finally {
            LayoutBox.finishResetPlacements(staleFrags);
        }
        for (int s = 0; s < RenderPipeline.MAX_CV_STABILIZE_PASSES && av.consumeNonVirtualVisibilityChanged(); s++) {
            long nextRun = RenderPipeline.LAYOUT_RUNS.incrementAndGet();
            LayoutContext stabilizeCtx = new LayoutContext(viewport, RenderPipeline.effectiveIntrinsicResolver(), scrollState, fonts,
                    true, nextRun, true, av);
            rootBox.measure(rootW, rootH, FormattingContext.BLOCK, stabilizeCtx);
            staleFrags.clear();
            LayoutBox.resetPlacementsFast(rootBox, nextRun, staleFrags);
            try {
                rootBox.layout(0, 0, rootW, rootH, stabilizeCtx);
            } finally {
                LayoutBox.finishResetPlacements(staleFrags);
            }
            LayoutBox.clearAllSubtreeMarks(rootBox);
        }
        if (av.hasContentAuto() && interactionState != null) {
            RenderPipeline.syncContentVisibilityFlags(rootBox, interactionState);
        }
        StageProfiler.end(PipelineStage.REPOSITION, prof);
    }
}
