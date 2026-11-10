package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.dom.node.HtmlDocument;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.raster.Matrix2D;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 表示リストのサイズが異なる場合（子の挿入・削除 = CHILD_PATCH）において、
 * {@link PaintSceneDriver#patchIndexByShift} による増分インデックスシフトが、
 * 毎回全再構築（{@code rebuildIndex}）した場合と走査出力（描く項目・姿勢・不透明度・層の鎖）が完全に一致することを検証するテスト。
 */
class PaintSceneDriverShiftPatchTest {
    private static final int W = 360, H = 300;

    @AfterEach
    void restore() {
        PaintSceneDriver.patchShiftIndex = true;
        PaintSceneDriver.patchDiffIndex = true;
    }

    private static String baseHtml(int rowCount) {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < rowCount; i++) {
            rows.append("<div class=\"row\" id=\"r").append(i).append("\">row <span id=\"t").append(i).append("\">").append(i).append("</span></div>");
        }
        return "<style>body{margin:0;font-size:13px}.row{height:22px;margin:2px;padding:2px 6px;background:#eef;border:1px solid #99a;border-radius:4px}"
                + ".row:hover{background:#fed}.hot{color:#c00;transform:translateX(3px)}"
                + "#outer{position:absolute;left:0;top:0;width:240px;height:280px;overflow-y:auto;border:2px solid #333;border-radius:8px}"
                + "#side{position:absolute;left:260px;top:0;width:80px;height:120px;background:#eef;border-radius:6px}</style>"
                + "<div id=\"outer\">" + rows + "</div><div id=\"side\">side</div>";
    }

    private static final class NoDraw implements RenderBackend {
        private final String session = "shift-patch-view";
        @Override public void render(List<DisplayItem> items, long nowMs) {
            for (DisplayItem it : items) CssAnimator.getValuesAt(session, it, nowMs);
        }
        @Override public com.nishiyu.enderframelib.core.raster.Pixmap pixmap() { return new com.nishiyu.enderframelib.core.raster.Pixmap(1, 1); }
        @Override public float devicePixelRatio() { return 1f; }
        @Override public String sessionId() { return session; }
        @Override public void close() { }
    }

    private static List<String> trace(PaintSceneDriver d, List<DisplayItem> items, long now) {
        List<String> out = new ArrayList<>();
        d.run(items, now, Matrix2D.IDENTITY, new PaintSceneDriver.Painter() {
            @Override public void syncLayers(List<PaintSceneDriver.Group> want) {
                StringBuilder sb = new StringBuilder("layers:");
                for (var g : want) sb.append(g.id()).append('@').append(g.opacity()).append(',');
                out.add(sb.toString());
            }
            @Override public void drawItem(DisplayItem el, int index, float flat) {
                var r = d.resolveAt(index);
                out.add(el.id() + "|" + index + "|" + flat + "|" + r.pose() + "|" + r.opacity() + "|" + r.residual() + "|"
                        + el.x() + "," + el.y() + "," + el.width() + "," + el.height() + "|" + el.text() + "|" + el.color() + "|" + el.bgColor());
            }
        });
        return out;
    }

    @Test
    void insertRowInMiddleMatchesFullRebuild() {
        StandaloneView view = new StandaloneView(baseHtml(20), W, H, FontResolver.FALLBACK);
        List<List<DisplayItem>> frames = new ArrayList<>();
        try (var backend = new NoDraw()) {
            long now = 1000;
            view.frame(now, backend, true);
            frames.add(view.ui().renderCache());

            // 中間に新しい行を挿入
            HtmlNode outer = view.root().getNodeById("outer");
            HtmlDocument doc = view.root() instanceof HtmlDocument d ? d : null;
            if (doc != null && outer != null) {
                HtmlElement newRow = (HtmlElement) doc.createElement("div");
                newRow.setAttribute("class", "row hot");
                newRow.setAttribute("id", "inserted_mid");
                newRow.appendChild((HtmlNode) doc.createTextNode("inserted middle"));
                HtmlNode ref = view.root().getNodeById("r10");
                outer.insertBefore(newRow, ref);
            }

            now += 16;
            view.frame(now, backend, true);
            frames.add(view.ui().renderCache());
        } finally {
            view.close();
        }

        PaintSceneDriver shifted = new PaintSceneDriver("drv-shift-inserted");
        PaintSceneDriver rebuilt = new PaintSceneDriver("drv-rebuild-inserted");

        // フレーム 0
        PaintSceneDriver.patchShiftIndex = false;
        List<String> expect0 = trace(rebuilt, frames.get(0), 1000);
        PaintSceneDriver.patchShiftIndex = true;
        List<String> actual0 = trace(shifted, frames.get(0), 1000);
        assertEquals(expect0, actual0);

        // フレーム 1 (サイズ増加)
        PaintSceneDriver.patchShiftIndex = false;
        List<String> expect1 = trace(rebuilt, frames.get(1), 1016);
        PaintSceneDriver.patchShiftIndex = true;
        List<String> actual1 = trace(shifted, frames.get(1), 1016);
        assertEquals(expect1, actual1, "挿入フレームの走査結果が毎回再構築と完全一致すること");
        assertTrue(frames.get(1).size() > frames.get(0).size(), "項目数が増加していること");
    }

    @Test
    void removeRowInMiddleMatchesFullRebuild() {
        StandaloneView view = new StandaloneView(baseHtml(20), W, H, FontResolver.FALLBACK);
        List<List<DisplayItem>> frames = new ArrayList<>();
        try (var backend = new NoDraw()) {
            long now = 1000;
            view.frame(now, backend, true);
            frames.add(view.ui().renderCache());

            // 中間の行を削除
            HtmlNode outer = view.root().getNodeById("outer");
            HtmlNode r8 = view.root().getNodeById("r8");
            if (outer != null && r8 != null) {
                outer.removeChild(r8);
            }

            now += 16;
            view.frame(now, backend, true);
            frames.add(view.ui().renderCache());
        } finally {
            view.close();
        }

        PaintSceneDriver shifted = new PaintSceneDriver("drv-shift-removed");
        PaintSceneDriver rebuilt = new PaintSceneDriver("drv-rebuild-removed");

        // フレーム 0
        PaintSceneDriver.patchShiftIndex = false;
        List<String> expect0 = trace(rebuilt, frames.get(0), 1000);
        PaintSceneDriver.patchShiftIndex = true;
        List<String> actual0 = trace(shifted, frames.get(0), 1000);
        assertEquals(expect0, actual0);

        // フレーム 1 (サイズ減少)
        PaintSceneDriver.patchShiftIndex = false;
        List<String> expect1 = trace(rebuilt, frames.get(1), 1016);
        PaintSceneDriver.patchShiftIndex = true;
        List<String> actual1 = trace(shifted, frames.get(1), 1016);
        assertEquals(expect1, actual1, "削除フレームの走査結果が毎回再構築と完全一致すること");
        assertTrue(frames.get(1).size() < frames.get(0).size(), "項目数が減少していること");
    }

    @Test
    void insertRowAtStartMatchesFullRebuild() {
        StandaloneView view = new StandaloneView(baseHtml(20), W, H, FontResolver.FALLBACK);
        List<List<DisplayItem>> frames = new ArrayList<>();
        try (var backend = new NoDraw()) {
            long now = 1000;
            view.frame(now, backend, true);
            frames.add(view.ui().renderCache());

            // 先頭に新しい行を挿入
            HtmlNode outer = view.root().getNodeById("outer");
            HtmlDocument doc = view.root() instanceof HtmlDocument d ? d : null;
            if (doc != null && outer != null && !outer.children.isEmpty()) {
                HtmlElement newRow = (HtmlElement) doc.createElement("div");
                newRow.setAttribute("class", "row hot");
                newRow.setAttribute("id", "inserted_head");
                newRow.appendChild((HtmlNode) doc.createTextNode("inserted head"));
                outer.insertBefore(newRow, outer.children.get(0));
            }

            now += 16;
            view.frame(now, backend, true);
            frames.add(view.ui().renderCache());
        } finally {
            view.close();
        }

        PaintSceneDriver shifted = new PaintSceneDriver("drv-shift-inserted-start");
        PaintSceneDriver rebuilt = new PaintSceneDriver("drv-rebuild-inserted-start");

        trace(rebuilt, frames.get(0), 1000);
        trace(shifted, frames.get(0), 1000);

        PaintSceneDriver.patchShiftIndex = false;
        List<String> expect = trace(rebuilt, frames.get(1), 1016);
        PaintSceneDriver.patchShiftIndex = true;
        List<String> actual = trace(shifted, frames.get(1), 1016);
        assertEquals(expect, actual, "先頭挿入フレームの走査結果が一致すること");
    }

    @Test
    void insertRowAtEndMatchesFullRebuild() {
        StandaloneView view = new StandaloneView(baseHtml(20), W, H, FontResolver.FALLBACK);
        List<List<DisplayItem>> frames = new ArrayList<>();
        try (var backend = new NoDraw()) {
            long now = 1000;
            view.frame(now, backend, true);
            frames.add(view.ui().renderCache());

            // 末尾に新しい行を追加
            HtmlNode outer = view.root().getNodeById("outer");
            HtmlDocument doc = view.root() instanceof HtmlDocument d ? d : null;
            if (doc != null && outer != null) {
                HtmlElement newRow = (HtmlElement) doc.createElement("div");
                newRow.setAttribute("class", "row");
                newRow.setAttribute("id", "inserted_end");
                newRow.appendChild((HtmlNode) doc.createTextNode("inserted end"));
                outer.appendChild(newRow);
            }

            now += 16;
            view.frame(now, backend, true);
            frames.add(view.ui().renderCache());
        } finally {
            view.close();
        }

        PaintSceneDriver shifted = new PaintSceneDriver("drv-shift-inserted-end");
        PaintSceneDriver rebuilt = new PaintSceneDriver("drv-rebuild-inserted-end");

        trace(rebuilt, frames.get(0), 1000);
        trace(shifted, frames.get(0), 1000);

        PaintSceneDriver.patchShiftIndex = false;
        List<String> expect = trace(rebuilt, frames.get(1), 1016);
        PaintSceneDriver.patchShiftIndex = true;
        List<String> actual = trace(shifted, frames.get(1), 1016);
        assertEquals(expect, actual, "末尾挿入フレームの走査結果が一致すること");
    }

    @Test
    void removeRowAtStartMatchesFullRebuild() {
        StandaloneView view = new StandaloneView(baseHtml(20), W, H, FontResolver.FALLBACK);
        List<List<DisplayItem>> frames = new ArrayList<>();
        try (var backend = new NoDraw()) {
            long now = 1000;
            view.frame(now, backend, true);
            frames.add(view.ui().renderCache());

            // 先頭行を削除
            HtmlNode outer = view.root().getNodeById("outer");
            HtmlNode r0 = view.root().getNodeById("r0");
            if (outer != null && r0 != null) {
                outer.removeChild(r0);
            }

            now += 16;
            view.frame(now, backend, true);
            frames.add(view.ui().renderCache());
        } finally {
            view.close();
        }

        PaintSceneDriver shifted = new PaintSceneDriver("drv-shift-removed-start");
        PaintSceneDriver rebuilt = new PaintSceneDriver("drv-rebuild-removed-start");

        trace(rebuilt, frames.get(0), 1000);
        trace(shifted, frames.get(0), 1000);

        PaintSceneDriver.patchShiftIndex = false;
        List<String> expect = trace(rebuilt, frames.get(1), 1016);
        PaintSceneDriver.patchShiftIndex = true;
        List<String> actual = trace(shifted, frames.get(1), 1016);
        assertEquals(expect, actual, "先頭削除フレームの走査結果が一致すること");
    }

    @Test
    void removeRowAtEndMatchesFullRebuild() {
        StandaloneView view = new StandaloneView(baseHtml(20), W, H, FontResolver.FALLBACK);
        List<List<DisplayItem>> frames = new ArrayList<>();
        try (var backend = new NoDraw()) {
            long now = 1000;
            view.frame(now, backend, true);
            frames.add(view.ui().renderCache());

            // 末尾行を削除
            HtmlNode outer = view.root().getNodeById("outer");
            HtmlNode r19 = view.root().getNodeById("r19");
            if (outer != null && r19 != null) {
                outer.removeChild(r19);
            }

            now += 16;
            view.frame(now, backend, true);
            frames.add(view.ui().renderCache());
        } finally {
            view.close();
        }

        PaintSceneDriver shifted = new PaintSceneDriver("drv-shift-removed-end");
        PaintSceneDriver rebuilt = new PaintSceneDriver("drv-rebuild-removed-end");

        trace(rebuilt, frames.get(0), 1000);
        trace(shifted, frames.get(0), 1000);

        PaintSceneDriver.patchShiftIndex = false;
        List<String> expect = trace(rebuilt, frames.get(1), 1016);
        PaintSceneDriver.patchShiftIndex = true;
        List<String> actual = trace(shifted, frames.get(1), 1016);
        assertEquals(expect, actual, "末尾削除フレームの走査結果が一致すること");
    }

    @Test
    void randomInsertRemoveAndPatchSequenceMatchesFullRebuild() {
        StandaloneView view = new StandaloneView(baseHtml(25), W, H, FontResolver.FALLBACK);
        List<List<DisplayItem>> frames = new ArrayList<>();
        try (var backend = new NoDraw()) {
            long now = 1000;
            view.frame(now, backend, true);
            frames.add(view.ui().renderCache());

            Random rnd = new Random(42);
            int dynamicIdSeq = 100;
            for (int step = 0; step < 60; step++) {
                HtmlNode outer = view.root().getNodeById("outer");
                HtmlDocument doc = view.root() instanceof HtmlDocument d ? d : null;
                int op = rnd.nextInt(4);
                if (op == 0 && doc != null && outer != null) {
                    // 挿入
                    HtmlElement newRow = (HtmlElement) doc.createElement("div");
                    newRow.setAttribute("class", "row");
                    newRow.setAttribute("id", "dyn_" + (dynamicIdSeq++));
                    newRow.appendChild((HtmlNode) doc.createTextNode("dyn " + dynamicIdSeq));
                    int childCount = outer.children.size();
                    if (childCount > 0) {
                        int at = rnd.nextInt(childCount);
                        outer.insertBefore(newRow, outer.children.get(at));
                    } else {
                        outer.appendChild(newRow);
                    }
                } else if (op == 1 && outer != null && outer.children.size() > 5) {
                    // 削除
                    int at = rnd.nextInt(outer.children.size());
                    outer.removeChild(outer.children.get(at));
                } else if (op == 2) {
                    // スタイル変更
                    int rowIdx = rnd.nextInt(20);
                    HtmlNode r = view.root().getNodeById("r" + rowIdx);
                    if (r instanceof HtmlElement e) {
                        e.setAttribute("style", "color:#" + Integer.toHexString(0x100000 + rnd.nextInt(0xEFFFFF)));
                    }
                } else {
                    // テキスト変更
                    int rowIdx = rnd.nextInt(20);
                    HtmlNode t = view.root().getNodeById("t" + rowIdx);
                    if (t != null) {
                        t.updateTextContent("text_" + rnd.nextInt(1000));
                    }
                }

                now += 16;
                view.frame(now, backend, true);
                frames.add(view.ui().renderCache());
            }
        } finally {
            view.close();
        }

        PaintSceneDriver shifted = new PaintSceneDriver("drv-random-shift");
        PaintSceneDriver rebuilt = new PaintSceneDriver("drv-random-rebuild");
        int sizeChangedFrames = 0;

        long t = 5000;
        for (int f = 0; f < frames.size(); f++) {
            t += 16;
            PaintSceneDriver.patchShiftIndex = false;
            List<String> expect = trace(rebuilt, frames.get(f), t);

            PaintSceneDriver.patchShiftIndex = true;
            List<String> actual = trace(shifted, frames.get(f), t);

            assertEquals(expect, actual, "ランダム操作列 frame " + f + " で走査結果が一致すること");
            if (f > 0 && frames.get(f).size() != frames.get(f - 1).size()) {
                sizeChangedFrames++;
            }
        }

        assertTrue(sizeChangedFrames >= 15, "十分なサイズ変更（挿入・削除）フレームがテストされていること: " + sizeChangedFrames);
    }
}
