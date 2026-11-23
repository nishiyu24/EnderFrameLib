package com.nishiyu.enderframelib.core.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

/** K4b: 表示リストが木を下りながら持ち回る絶対位置（{@code LayoutOrigin}: 祖先の寄与の合計 + 親相対）が、{@code LayoutBox.x()/y()} の解決と一致する。 */
class LayoutOriginTest {
    private static final String HTML = """
            <style>
              body { margin: 7px; font-size: 14px }
              .card { padding: 6px; margin: 3px 5px; border: 1px solid #888 }
              .fl { display: flex; gap: 4px } .gr { display: grid; grid-template-columns: 1fr 2fr }
              .ib { display: inline-block; width: 40px } .rel { position: relative; top: 2px; left: 3px }
              .ab { position: absolute; left: 11px; top: 13px } .ct { display: contents } .no { display: none }
            </style>
            <div class="card">text <span>inline <b>bold <i>it</i></b></span> tail
              <div class="fl"><span>a</span><div class="card rel">b<span class="ab">abs</span></div><i>c</i></div>
              <div class="gr"><div>g1<span>in</span></div><div class="ct"><p>contents</p></div></div>
              <span class="ib">ib</span><span class="no">hidden</span>
              <div class="card"><div class="card"><div class="card">deep <span>x</span></div></div></div>
            </div>
            """;

    private static int checked;

    private static void walk(LayoutBox b) {
        if (b.parent != null) {
            float ax = LayoutOrigin.resolvesLocally(b) ? LayoutOrigin.relX(b) : LayoutOrigin.originX(b) + LayoutOrigin.relX(b);
            float ay = LayoutOrigin.resolvesLocally(b) ? LayoutOrigin.relY(b) : LayoutOrigin.originY(b) + LayoutOrigin.relY(b);
            assertEquals(b.x(), ax, 0.01f, "x " + b.node.tag);
            assertEquals(b.y(), ay, 0.01f, "y " + b.node.tag);
            // 子へ渡す原点は、親の原点 + 親の寄与
            for (LayoutBox c : b.children) {
                assertEquals(LayoutOrigin.originX(c), LayoutOrigin.originX(b) + LayoutOrigin.shiftX(b), 0.01f, "子の原点 x " + c.node.tag);
                assertEquals(LayoutOrigin.originY(c), LayoutOrigin.originY(b) + LayoutOrigin.shiftY(b), 0.01f, "子の原点 y " + c.node.tag);
            }
            checked++;
        }
        for (LayoutBox c : b.children) walk(c);
    }

    @Test
    void carriedOriginsEqualTheResolvedPositions() {
        try (StandaloneView v = new StandaloneView(HTML, 400, 300, FontResolver.FALLBACK)) {
            v.ui().flushInvalidation();
            checked = 0;
            walk(v.ui().layoutRoot());
            assertTrue(checked > 25, "確かめた箱が少ない: " + checked);
        }
    }
}
