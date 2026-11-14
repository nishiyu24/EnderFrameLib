package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.paint.RenderBackend;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** R6: 事前の温め（EnginePrewarm）は、完了し、何度呼んでも 1 回で、画面の結果を変えず、並走しても壊れない。 */
public class EnginePrewarmTest {

    private static final String DOC = "<html><head><style>body{margin:8px} .a{display:flex;gap:4px} .a div{padding:4px;background:#def}"
            + " p{line-height:1.3}</style></head><body><p>Some wrapped paragraph text for the prewarm equivalence check, long enough to wrap.</p>"
            + "<div class=\"a\"><div>x</div><div>y</div><div>z</div></div><ul><li>one</li><li>two</li></ul></body></html>";

    private static final class Capture implements RenderBackend {
        List<DisplayItem> last = List.of();
        @Override public void render(List<DisplayItem> items, long nowMs) { last = new ArrayList<>(items); }
        @Override public Pixmap pixmap() { throw new UnsupportedOperationException(); }
        @Override public float devicePixelRatio() { return 1f; }
    }

    private static List<DisplayItem> open() {
        StandaloneView v = new StandaloneView(DOC, 400, 300, FontResolver.FALLBACK);
        try {
            Capture c = new Capture();
            v.frame(1000, c, true);
            v.frame(1016, c, true);
            return c.last;
        } finally {
            v.close();
        }
    }

    @Test
    public void startCompletesAndIsIdempotent() throws Exception {
        CompletableFuture<Void> a = EnginePrewarm.start();
        CompletableFuture<Void> b = EnginePrewarm.start();
        assertSame(a, b, "2 回目以降は同じ温めを返す");
        a.get(60, TimeUnit.SECONDS);
        assertTrue(a.isDone() && !a.isCompletedExceptionally());
    }

    @Test
    public void prewarmDoesNotChangeTheRenderedResult() throws Exception {
        List<DisplayItem> before = open();
        assertFalse(before.isEmpty());
        EnginePrewarm.runNow();
        assertEquals(before, open(), "温めたあとに開いた画面は、温める前と同じ表示リスト");
    }

    @Test
    public void openingWhilePrewarmRunsOnAnotherThreadGivesTheSameResult() throws Exception {
        List<DisplayItem> expected = open();
        for (int i = 0; i < 5; i++) {
            Thread t = new Thread(EnginePrewarm::runNow);
            t.start();
            assertEquals(expected, open(), "別スレッドの温めと並走しても結果は同じ（" + i + "）");
            t.join(60_000);
            assertFalse(t.isAlive());
        }
    }
}
