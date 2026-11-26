package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.paint.AbsoluteItems;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.PaintOffsets;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.text.font.OutlineFontFace;
import com.nishiyu.enderframelib.core.text.font.SystemFonts;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 親相対の項目（MOD の面が描く {@code relativeElements()}）でも、textarea の文字が絶対座標の項目と同じに描かれる。
 *
 * <p>何が壊れていたか: textarea の文字を箱で切る矩形を、項目の座標（親相対）のまま根の姿勢で解いていたため、
 * 原点から離れた textarea では切る矩形がずれて文字が全部切り落とされていた（M3a で MOD をコアの描画だけにして見つかった）。
 */
class RelativeTextareaPaintTest {

    private static final String HTML = "<html><body style='margin:0;background:#000'><div style='margin:30px 40px;padding:12px'>"
            + "<textarea style='width:150px;height:44px;font-size:14px;color:#fff;background:#222;border:0'>line one\nline two</textarea>"
            + "</div></body></html>";

    private static Pixmap paint(List<DisplayItem> items, int w, int h) {
        SoftwareRenderBackend sw = new SoftwareRenderBackend(w, h, 1f);
        sw.render(items, PaintOffsets.EMPTY, 0L);
        return sw.pixmap();
    }

    @Test
    void textareaTextIsPaintedFromParentRelativeItems() {
        Assumptions.assumeTrue(SystemFonts.system().resolve(null) instanceof OutlineFontFace, "no system fonts");
        try (StandaloneView v = new StandaloneView(HTML, 300, 160, FontResolver.FALLBACK);
             SoftwareRenderBackend sw = new SoftwareRenderBackend(300, 160, 1f)) {
            v.frame(0, sw, true);
            List<DisplayItem> rel = v.ui().relativeElements();
            assertTrue(rel.stream().anyMatch(i -> i.type() == ElementType.TEXTAREA), "textarea の項目がある");
            Pixmap fromRelative = paint(rel, 300, 160);
            Pixmap fromAbsolute = paint(AbsoluteItems.of(rel), 300, 160);
            int lit = 0;
            for (int y = 0; y < 160; y++) {
                for (int x = 0; x < 300; x++) {
                    assertEquals(fromAbsolute.getStraight(x, y), fromRelative.getStraight(x, y), "親相対と絶対で画素が違う @" + x + "," + y);
                    if (((fromRelative.getStraight(x, y) >> 16) & 0xFF) > 150) lit++;
                }
            }
            assertTrue(lit > 50, "textarea の文字が描かれていない（明るい画素 " + lit + "）");
        }
    }
}
