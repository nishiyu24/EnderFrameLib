package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Phase B-2: 静止フレームで再描画を飛ばす判定（ホストの CoreGpuSurface から切り出した純粋部品）。 */
class StaticFrameGateTest {

    private static List<DisplayItem> items(String body) {
        StandaloneView v = new StandaloneView("<style>body{margin:0}div{height:10px}</style>" + body, 200, 100, FontResolver.FALLBACK);
        try { return List.copyOf(v.items()); } finally { v.close(); }
    }

    @Test
    void neverPaintedMeansPaint() {
        StaticFrameGate g = new StaticFrameGate(true);
        assertFalse(g.canSkip(items("<div>a</div>"), 0));
    }

    @Test
    void sameItemsStillAndSameFontSkip() {
        List<DisplayItem> a = items("<div>a</div><div>b</div>");
        StaticFrameGate g = new StaticFrameGate(true);
        g.painted(a, 3, false);
        assertTrue(g.canSkip(a, 3));
        // 別のリストでも、同じ項目インスタンスが同じ順に並んでいれば同じ絵
        assertTrue(g.canSkip(new ArrayList<>(a), 3));
        for (int i = 0; i < 5; i++) assertTrue(g.canSkip(a, 3), "何度でも飛ばせる");
    }

    @Test
    void anyChangeForcesRepaint() {
        List<DisplayItem> a = items("<div>a</div><div>b</div>");
        List<DisplayItem> other = items("<div>a</div><div>b</div>"); // 中身は同じでも別インスタンス
        StaticFrameGate g = new StaticFrameGate(true);
        g.painted(a, 3, false);

        assertFalse(g.canSkip(other, 3), "別インスタンスの項目は不変とみなせない");
        assertFalse(g.canSkip(a, 4), "フォント世代が変われば字形が変わる");
        assertFalse(g.canSkip(a.subList(0, a.size() - 1), 3), "項目数が違う");
        if (a.size() >= 2) {
            List<DisplayItem> swapped = new ArrayList<>(a);
            Collections.swap(swapped, 0, swapped.size() - 1);
            assertFalse(g.canSkip(swapped, 3), "順序が違う");
        }
    }

    @Test
    void animatingOrDisabledNeverSkips() {
        List<DisplayItem> a = items("<div>a</div>");
        StaticFrameGate animating = new StaticFrameGate(true);
        animating.painted(a, 0, true);
        assertFalse(animating.canSkip(a, 0), "アニメーション中は毎フレーム描く");
        animating.painted(a, 0, false);
        assertTrue(animating.canSkip(a, 0), "止まれば飛ばせる（止まった最初の 1 枚は描いたうえで）");

        StaticFrameGate off = new StaticFrameGate(false);
        off.painted(a, 0, false);
        assertFalse(off.canSkip(a, 0), "無効化時は常に描く");
    }

    @Test
    void timeDependentItemsNeverSettle() {
        List<DisplayItem> img = items("<div>a</div><img src=\"nothing.png\" width=10 height=10>");
        assertTrue(StaticFrameGate.timeDependent(img), "画像は読み込み状況で絵が変わりうる");
        assertFalse(StaticFrameGate.timeDependent(items("<div>a</div>")));
        StaticFrameGate g = new StaticFrameGate(true);
        g.painted(img, 0, false);
        assertFalse(g.canSkip(img, 0));
    }

    @Test
    void invalidateForcesNextPaint() {
        List<DisplayItem> a = items("<div>a</div>");
        StaticFrameGate g = new StaticFrameGate(true);
        g.painted(a, 0, false);
        g.invalidate();
        assertFalse(g.canSkip(a, 0));
        g.painted(a, 0, false);
        assertTrue(g.canSkip(a, 0));
    }

    /** 控えの配列を使い回しても、長さの違うリストを行き来して誤って飛ばさない・古い参照を引きずらない。 */
    @Test
    void randomSequenceMatchesNaiveModel() {
        List<List<DisplayItem>> pool = List.of(items("<div>1</div>"), items("<div>1</div><div>2</div><div>3</div>"),
                items("<div>x</div><div>y</div>"), items("<p>q</p>"));
        Random r = new Random(11);
        StaticFrameGate g = new StaticFrameGate(true);
        List<DisplayItem> last = null;
        int lastFont = -1;
        boolean lastSettled = false;
        for (int step = 0; step < 400; step++) {
            List<DisplayItem> cur = pool.get(r.nextInt(pool.size()));
            int font = r.nextInt(2);
            boolean expect = lastSettled && lastFont == font && last != null && sameInstances(last, cur);
            assertEquals(expect, g.canSkip(cur, font), "step " + step);
            if (!expect || r.nextInt(4) == 0) {
                boolean anim = r.nextInt(5) == 0;
                g.painted(cur, font, anim);
                last = cur;
                lastFont = font;
                lastSettled = !anim && !StaticFrameGate.timeDependent(cur);
            }
        }
    }

    private static boolean sameInstances(List<DisplayItem> a, List<DisplayItem> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (a.get(i) != b.get(i)) return false;
        return true;
    }

    /** 番人: ホストが判定を自前で持ち直す（毎回の toArray 確保・個別の時間依存判定）形に戻ると失敗する。 */
    @Test
    void hostUsesTheGateInsteadOfItsOwnCopy() throws IOException {
        Path src = Path.of("../src/main/java/com/nishiyu/enderframelib/client/renderer/CoreGpuSurface.java");
        if (!Files.exists(src)) src = Path.of("src/main/java/com/nishiyu/enderframelib/client/renderer/CoreGpuSurface.java");
        if (!Files.exists(src)) return; // :core 単体で配られた場合はホストが無い
        String t = Files.readString(src);
        assertTrue(t.contains("StaticFrameGate"), "ホストは StaticFrameGate を使う");
        for (String bad : new String[]{"sameItems(", "items.toArray()", "boolean timeDependent("}) {
            assertFalse(t.contains(bad), "判定は core の StaticFrameGate に一本化: " + bad);
        }
    }
}
