package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 静止フレームの早期リターン: 何も変わらないフレームでは省き、変化があれば省かず結果が変わる。 */
public class IdleFrameTest {
    private static final String HTML = "<style>body{margin:0}#a{width:50px;height:10px;background:red}"
            + "#a:hover{background:blue}</style><div id=a>x</div><p id=p>hello</p>";

    private static List<String> sig(StandaloneView v) {
        List<String> out = new ArrayList<>();
        for (DisplayItem i : v.ui().renderCache()) {
            out.add(i.id() + "|" + i.x() + "," + i.y() + "," + i.width() + "," + i.height() + "|" + i.text() + "|" + i.bgColor());
        }
        return out;
    }

    @Test
    public void quietFramesAreSkippedAndChangesAreNot() {
        StandaloneView v = new StandaloneView(HTML, 200, 100, FontResolver.FALLBACK);
        v.ui().flushInvalidation(); // 初回の構築は省かれない
        List<String> base = sig(v);

        long idle = UiView.IDLE_FRAMES.get();
        for (int i = 0; i < 10; i++) v.ui().flushInvalidation();
        assertEquals(idle + 10, UiView.IDLE_FRAMES.get(), "変化が無ければ毎回省く");
        assertEquals(base, sig(v));

        v.root().getNodeById("a").setAttribute("style", "width:120px");
        long before = UiView.IDLE_FRAMES.get();
        v.ui().flushInvalidation();
        assertEquals(before, UiView.IDLE_FRAMES.get(), "変更のあったフレームは省かない");
        assertTrue(!base.equals(sig(v)), "変更が反映される");

        v.ui().flushInvalidation();
        assertEquals(before + 1, UiView.IDLE_FRAMES.get(), "反映後はまた省く");
    }
}
