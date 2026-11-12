package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.common.UiView;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * R7: 局所更新の「パッチ命中率」と「1 操作あたりの確保量」を、時間ではなくカウンタで固定するラチェット。
 * 500 行のリストで hover 往復・クラス変更・行の追加を繰り返し、
 * 全面再構築へ落ちた回数が 0、パッチ命中が操作回数と一致、確保量が上限以内であることを要求する。
 * 時間ベンチは全件実行で不安定なため、構造の退行（パッチ未使用化）はここで検出する。
 */
class PatchHitRateRatchetTest {

    private static final int ROWS = 500;
    private static final int CYCLES = 40;
    /** 全面再構築（500 行）は 1 操作で数 MB 確保する。パッチ経路は桁違いに小さい。 */
    private static final long HOVER_ALLOC_CEILING_PER_OP = 50_000L;
    private static final long CHILD_ALLOC_CEILING_PER_OP = 2_500_000L;

    @AfterEach
    void tearDown() {
        UiView.childPatchEnabled = true;
    }

    private static String html() {
        StringBuilder sb = new StringBuilder("""
                <!DOCTYPE html><html><head><style>
                  body { margin: 8px; font-size: 14px; }
                  .row { padding: 4px 8px; margin: 1px 0; background: #eeeeee; }
                  .row:hover { background: #cce0ff; }
                  .row.hot { background: #ffcccc; }
                </style></head><body><div id="list">
                """);
        for (int i = 0; i < ROWS; i++) {
            sb.append("<div id=\"r").append(i).append("\" class=\"row\">Row ").append(i).append("</div>\n");
        }
        return sb.append("</div></body></html>").toString();
    }

    private static StandaloneView open() {
        StandaloneView v = new StandaloneView(html(), 640, 480, FontResolver.FALLBACK);
        v.ui().flushInvalidation();
        return v;
    }

    private static long allocated() {
        return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean())
                .getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    @Test
    void hoverRoundTripAlwaysPatchesWithoutFullRebuild() {
        StandaloneView v = open();
        // 暖機（JIT・初回キャッシュ）
        for (int i = 0; i < 6; i++) {
            v.ui().setHoveredElement("r" + (i + 100));
            v.ui().flushInvalidation();
        }
        long patch0 = UiView.RESTYLE_REPAINT_PATCH.get();
        long full0 = UiView.RESTYLE_REPAINT_FULL.get();
        long alloc0 = allocated();
        int ops = 0;
        for (int i = 0; i < CYCLES; i++) {
            v.ui().setHoveredElement("r" + (200 + i));
            v.ui().flushInvalidation();
            v.ui().setHoveredElement(null);
            v.ui().flushInvalidation();
            ops += 2;
        }
        long perOp = (allocated() - alloc0) / ops;
        System.out.println("[ratchet] hover: ops=" + ops + " patch=" + (UiView.RESTYLE_REPAINT_PATCH.get() - patch0)
                + " full=" + (UiView.RESTYLE_REPAINT_FULL.get() - full0) + " alloc/op=" + perOp + " B");
        assertEquals(0, UiView.RESTYLE_REPAINT_FULL.get() - full0, "hover 往復で表示リスト全面再構築へ落ちない");
        assertEquals(ops, UiView.RESTYLE_REPAINT_PATCH.get() - patch0, "hover の全操作がスライスパッチに命中する（100%）");
        assertTrue(perOp < HOVER_ALLOC_CEILING_PER_OP, "hover 1 操作の確保量 " + perOp + " B が上限以内");
    }

    @Test
    void classToggleAlwaysPatchesWithoutFullRebuild() {
        StandaloneView v = open();
        HtmlElement row = (HtmlElement) v.root().getNodeById("r250");
        row.setAttribute("class", "row hot");
        v.ui().flushInvalidation();
        row.setAttribute("class", "row");
        v.ui().flushInvalidation();

        long patch0 = UiView.RESTYLE_REPAINT_PATCH.get();
        long full0 = UiView.RESTYLE_REPAINT_FULL.get();
        int ops = 0;
        for (int i = 0; i < CYCLES; i++) {
            row.setAttribute("class", "row hot");
            v.ui().flushInvalidation();
            row.setAttribute("class", "row");
            v.ui().flushInvalidation();
            ops += 2;
        }
        assertEquals(0, UiView.RESTYLE_REPAINT_FULL.get() - full0, "クラス付け外しで全面再構築へ落ちない");
        assertEquals(ops, UiView.RESTYLE_REPAINT_PATCH.get() - patch0, "クラス付け外しの全操作がスライスパッチに命中する");
    }

    @Test
    void childAppendAlwaysHitsChildPatch() {
        UiView.childPatchEnabled = true;
        StandaloneView v = open();
        HtmlNode list = v.root().getNodeById("list");
        for (int i = 0; i < 4; i++) { // 暖機
            list.appendChild(newRow("w" + i));
            v.ui().flushInvalidation();
        }
        long hits0 = UiView.CHILD_PATCH_HITS.get();
        long fb0 = UiView.CHILD_PATCH_FALLBACKS.get();
        long alloc0 = allocated();
        int ops = 20;
        for (int i = 0; i < ops; i++) {
            list.appendChild(newRow("n" + i));
            v.ui().flushInvalidation();
        }
        long perOp = (allocated() - alloc0) / ops;
        System.out.println("[ratchet] child: ops=" + ops + " hits=" + (UiView.CHILD_PATCH_HITS.get() - hits0)
                + " fallbacks=" + (UiView.CHILD_PATCH_FALLBACKS.get() - fb0) + " alloc/op=" + perOp + " B");
        assertEquals(ops, UiView.CHILD_PATCH_HITS.get() - hits0, "子の追加の全操作が CHILD_PATCH に命中する: " + UiView.lastChildPatchRejection);
        assertEquals(0, UiView.CHILD_PATCH_FALLBACKS.get() - fb0, "子の追加で全体再レイアウトへ落ちない");
        assertTrue(perOp < CHILD_ALLOC_CEILING_PER_OP, "child 1 操作の確保量 " + perOp + " B が上限以内");
    }

    private static HtmlElement newRow(String id) {
        HtmlElement el = new HtmlElement("div");
        el.setAttribute("class", "row");
        el.setAttribute("id", id);
        el.updateTextContent("Added " + id);
        return el;
    }
}
