package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Test;

/**
 * B9: 兄弟が多い親（並列にする閾値以上）の中の 1 つだけが汚れる hover で、再スタイルが子の数に比例する配列を毎フレーム確保しない。
 * 以前は兄弟 2000 個の親で、並列に配る子の表（int[] 2 本）だけで毎フレーム約 16 KB 確保していた。
 */
class HoverRestyleAllocationTest {

    private static String list(int n) {
        StringBuilder sb = new StringBuilder("<html><head><style>ul{list-style:none;padding:0}li{padding:2px}li:hover{background:#eef}</style></head><body><ul>");
        for (int i = 0; i < n; i++) sb.append("<li id='i").append(i).append("'>row ").append(i).append("</li>");
        return sb.append("</ul></body></html>").toString();
    }

    private static long allocated() {
        return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean()).getThreadAllocatedBytes(Thread.currentThread().threadId());
    }

    @Test
    void hoverOverManySiblingsDoesNotAllocateAPerSiblingTable() {
        try (StandaloneView v = new StandaloneView(list(2000), 800, 600, FontResolver.FALLBACK)) {
            v.ui().flushInvalidation();
            String a = v.root().getNodeById("i100").key(), b = v.root().getNodeById("i101").key();
            for (int i = 0; i < 400; i++) { // 温め
                v.ui().setHoveredElement((i & 1) == 0 ? a : b);
                v.ui().flushInvalidation();
            }
            int frames = 300;
            long before = allocated();
            for (int i = 0; i < frames; i++) {
                v.ui().setHoveredElement((i & 1) == 0 ? a : b);
                v.ui().flushInvalidation();
            }
            long perFrame = (allocated() - before) / frames;
            assertTrue(perFrame < 12_000, "hover 1 フレームの確保が兄弟の数に比例している: " + perFrame + " B/frame");
        }
    }
}
