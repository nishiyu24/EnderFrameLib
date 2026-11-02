package com.nishiyu.enderframelib.core.paint.software;

import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.RenderPipeline;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.DisplayListBuilder;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.font.SystemFonts;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 画面外を省いた項目列が、全件の項目列の「画面内の部分」と同じ（ID も中身も）であること。 */
class ViewportCullingTest {

    /** 配列の中身は toString が参照の ID になるので、比べるときは落とす。 */
    private static String norm(DisplayItem it) {
        return it.toString().replaceAll("\\[F@[0-9a-f]+", "[F");
    }

    @Test
    void culledListMatchesTheFullListOnScreen() throws Exception {
        String html = Files.readString(Path.of("src/main/resources/data/enderframelib/enderframe_ui/voidwave.html"));
        int w = 1280, h = 800;
        UiDocumentCompiler c = new UiDocumentCompiler();
        Document doc = c.parseDocument(html, "", w, h);
        ScrollState scroll = new ScrollState();
        LayoutBox root = c.layoutDocument(doc, w, h, Map.of(), scroll, SystemFonts.system(), new InteractionState());
        DisplayListBuilder builder = new DisplayListBuilder(w, h, doc.sheet.keyframes, scroll);
        List<DisplayItem> first = builder.render(root, false);

        // .scroller を動かして位置だけ決め直す
        HtmlNode scroller = null;
        for (DisplayItem it : first) if (it.hints().scrollable() && it.height() >= h - 1) { scroller = doc.root.getNodeById(it.baseId()); break; }
        assertNotNull(scroller, "ページ全体のスクロール箱");
        scroll.setScroll(scroller, 0, 3000);
        RenderPipeline.repositionLayout(root, w, h, scroll, SystemFonts.system());

        List<DisplayItem> culled = builder.render(root, true);
        List<DisplayItem> full = new DisplayListBuilder(w, h, doc.sheet.keyframes, scroll).render(root, false);

        assertTrue(culled.size() < full.size() * 0.8, "画面外を省く: " + culled.size() + " / " + full.size());
        Map<String, DisplayItem> fullById = new HashMap<>();
        for (DisplayItem it : full) fullById.put(it.id(), it);
        for (DisplayItem it : culled) {
            DisplayItem ref = fullById.get(it.id());
            assertNotNull(ref, "全件にある ID: " + it.id());
            assertEquals(norm(ref), norm(it), "同じ中身: " + it.id());
        }
        // 画面に掛かる項目は 1 つも落とさない
        for (DisplayItem it : full) {
            boolean visible = it.type() != com.nishiyu.enderframelib.core.paint.model.ElementType.NODE && it.x() + it.width() > 0 && it.x() < w && it.y() + it.height() > 0 && it.y() < h;
            if (visible) assertTrue(culled.stream().anyMatch(x -> x.id().equals(it.id())), "画面内の項目が落ちた: " + it);
        }
    }
}
