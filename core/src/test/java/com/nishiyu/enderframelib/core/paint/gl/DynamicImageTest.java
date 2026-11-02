package com.nishiyu.enderframelib.core.paint.gl;

import com.nishiyu.enderframelib.common.SoftwareRendering;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.software.ImageProvider;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 同じ Pixmap の中身を書き換える画像（動画のフレーム）が、GPU でも次のフレームで更新されること。 */
class DynamicImageTest {

    private static final String HTML = "<html><body style='margin:0'>"
            + "<img src='dyn:frame' style='display:block;width:8px;height:8px'></body></html>";

    private static void fill(Pixmap p, int argb) {
        java.util.Arrays.fill(p.pixels(), argb);
        p.markChanged();
    }

    @Test
    void rewrittenPixmapIsReuploadedOnTheGpuAndSeenByTheItemOverload() {
        LwjglGlRenderBackend gl;
        try {
            gl = new LwjglGlRenderBackend(8, 8);
        } catch (Throwable t) {
            Assumptions.abort("OpenGL を使えない: " + t);
            return;
        }
        try {
            Pixmap frame = new Pixmap(4, 4);
            List<DisplayItem> seen = new ArrayList<>();
            ImageProvider provider = new ImageProvider() {
                @Override
                public Image load(String src, int w, int h) {
                    throw new AssertionError("項目つきの load が呼ばれるはず");
                }

                @Override
                public Image load(DisplayItem item, int w, int h) {
                    seen.add(item);
                    return "dyn:frame".equals(item.text()) ? new Image(frame, 4, 4) : null;
                }
            };
            gl.images(provider);
            SoftwareRenderBackend cpu = new SoftwareRenderBackend(8, 8, 1f).images(provider);
            List<DisplayItem> list = SoftwareRendering.displayList(HTML, 8, 8);

            fill(frame, 0xFFFF0000);
            gl.render(list, 1000);
            cpu.render(list, 1000);
            assertEquals(0xFFFF0000, gl.pixmap().get(4, 4));
            assertEquals(cpu.pixmap().get(4, 4), gl.pixmap().get(4, 4));

            fill(frame, 0xFF0000FF);
            gl.render(list, 1016);
            cpu.render(list, 1016);
            assertEquals(0xFF0000FF, gl.pixmap().get(4, 4), "書き換えた画素が次のフレームに出る");
            assertEquals(cpu.pixmap().get(4, 4), gl.pixmap().get(4, 4));

            // 書き換えなければ上げ直さず、同じ絵のまま
            gl.render(list, 1032);
            assertEquals(0xFF0000FF, gl.pixmap().get(4, 4));
            assertEquals(true, seen.stream().allMatch(i -> "dyn:frame".equals(i.text())));
        } finally {
            gl.close();
        }
    }
}
