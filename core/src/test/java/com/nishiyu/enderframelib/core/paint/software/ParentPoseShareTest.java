package com.nishiyu.enderframelib.core.paint.software;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.paint.AnimatedValues;
import com.nishiyu.enderframelib.core.paint.PaintSceneDriver;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * B12: 自分の変形・不透明度が恒等の葉の項目は、親の姿勢の実体をそのまま使う（{@link com.nishiyu.enderframelib.core.kernel.Shortcut#REUSE_IDLE_POSES}）。
 * スクロールで中身の節が動くと、以前は見えている全項目の姿勢（{@code Resolved}）を毎フレーム作り直していた（voidwave の {@code scroll} で
 * 1 フレーム約 {@code 62 KB}）。共有しても、毎フレーム作り直したときと同じ絵になること、色だけが動く葉は姿勢を共有しつつ
 * 自分の色で描かれること（項目の補間値は {@link PaintSceneDriver#animAt}）、不透明度・変形の動く葉は共有しないことを確かめる。
 */
class ParentPoseShareTest {
    private static final int W = 260, H = 200;

    static String doc() {
        StringBuilder rows = new StringBuilder();
        for (int r = 0; r < 14; r++) {
            String cls = switch (r % 5) {
                case 1 -> "row hue";   // 色だけが動く（姿勢は共有できる）
                case 2 -> "row fade";  // 不透明度が動く（共有しない）
                case 3 -> "row nudge"; // 変形が動く（共有しない）
                default -> "row";
            };
            rows.append("<div class='").append(cls).append("' id='r").append(r).append("'>row ").append(r).append("<b class='tag'>t</b></div>");
        }
        return "<html><head><style>"
                + "@keyframes hue { from { background-color: #f44 } 50% { background-color: #4f4 } to { background-color: #44f } }"
                + "@keyframes fade { from { opacity: .25 } to { opacity: 1 } }"
                + "@keyframes nudge { from { transform: translateX(0) } to { transform: translateX(12px) } }"
                + "@keyframes spin { to { transform: rotate(360deg) } }"
                + "body{margin:0;font:12px sans-serif;background:#123}"
                + "#sc{position:absolute;left:10px;top:10px;width:220px;height:170px;overflow-y:auto;background:#fff;border-radius:6px}"
                + ".row{height:20px;margin:3px;padding:2px 6px;background:#dde;border:1px solid #99a;color:#123}"
                + ".hue{animation:hue 1.3s linear infinite}"
                + ".fade{animation:fade .9s linear infinite alternate;background:#cfc}"
                + ".nudge{animation:nudge 1.1s ease-in-out infinite alternate;background:#fcc}"
                + ".tag{display:inline-block;margin-left:8px;padding:0 4px;background:#fd8;color:#000}"
                + ".spin{width:40px;height:40px;margin:6px;background:#468;animation:spin 2s linear infinite}"
                + ".spin i{display:block;width:12px;height:12px;margin:4px;background:#fe4}"
                + "</style></head><body><div id='sc'>"
                + "<div class='spin' id='spin'><i id='dot'></i></div>"
                + rows
                + "</div></body></html>";
    }

    @AfterEach
    void restore() {
        com.nishiyu.enderframelib.core.kernel.Shortcut.REUSE_IDLE_POSES.set(true);
    }

    private static List<int[]> frames(boolean share) {
        com.nishiyu.enderframelib.core.kernel.Shortcut.REUSE_IDLE_POSES.set(share);
        List<int[]> out = new ArrayList<>();
        try (SoftwareRenderBackend backend = new SoftwareRenderBackend(W, H, 1f);
             StandaloneView v = new StandaloneView(doc(), W, H, FontResolver.FALLBACK)) {
            long t = 1000;
            v.frame(t, backend, true);
            v.mouseMove(120, 100, backend.sessionId());
            for (int step = 0; step < 24; step++) {
                // 下へ 16 段・戻りを 8 段。段の間にもフレームを進め、スクロールしないフレーム（動く葉だけが変わる）も描く
                v.scroll(0, step < 16 ? -0.3 : 0.3, backend.sessionId());
                for (int f = 0; f < 3; f++) {
                    v.frame(t += 17 + (step + f) % 4 * 9, backend, true);
                    out.add(backend.pixmap().pixels().clone());
                }
            }
        }
        return out;
    }

    @Test
    void sharingParentPosesDrawsTheSamePicture() {
        List<int[]> off = frames(false);
        List<int[]> on = frames(true);
        assertEquals(off.size(), on.size());
        for (int i = 0; i < off.size(); i++) assertArrayEquals(off.get(i), on.get(i), "フレーム " + i + " の画素が、姿勢を毎回作るときと違う");
        long distinct = on.stream().map(java.util.Arrays::hashCode).distinct().count();
        assertTrue(distinct > on.size() * 3 / 4, "スクロールとアニメーションで絵がほとんど変わらない（空の検証）: " + distinct);
    }

    @Test
    void identityLeavesShareTheParentPoseAndKeepTheirOwnColors() {
        com.nishiyu.enderframelib.core.kernel.Shortcut.REUSE_IDLE_POSES.set(true);
        try (SoftwareRenderBackend backend = new SoftwareRenderBackend(W, H, 1f);
             StandaloneView v = new StandaloneView(doc(), W, H, FontResolver.FALLBACK)) {
            long t = 1000;
            v.frame(t, backend, true);
            v.scroll(0, -0.3, backend.sessionId());
            v.frame(t += 16, backend, true);
            v.scroll(0, -0.3, backend.sessionId());
            v.frame(t += 16, backend, true);
            PaintSceneDriver d = backend.driver;
            List<DisplayItem> items = d.items();
            int shared = 0, sharedWithOwnValues = 0, movingNodes = 0;
            for (int i = 0; i < items.size(); i++) {
                int p = d.parentIndex(i);
                if (p < 0 || p == i) continue;
                PaintSceneDriver.Resolved r = d.resolveAt(i), pr = d.resolveAt(p);
                AnimatedValues a = d.animAt(i);
                if (items.get(i).type() == ElementType.NODE) {
                    // 変形・不透明度の動く要素（回る箱・不透明度や位置の動く行）は節になり、節は共有しない
                    if (!identity(a)) {
                        movingNodes++;
                        assertNotSame(pr, r, "動く節が親の姿勢を共有した: " + items.get(i).id());
                    }
                    continue;
                }
                if (r == pr) {
                    shared++;
                    // 共有してよいのは、自分の変形・不透明度が恒等の項目だけ
                    assertTrue(identity(a), "変形・不透明度の動く項目が親の姿勢を共有した: " + items.get(i).id());
                    if (a != pr.anim()) sharedWithOwnValues++; // 色などは項目自身の補間値で描く
                }
            }
            assertTrue(shared > 20, "恒等の葉が親の姿勢を共有していない: " + shared);
            assertTrue(sharedWithOwnValues > 0, "共有した項目の補間値が親のものになっている");
            assertTrue(movingNodes >= 5, "回る箱・動く行の節が見つからない（空の検証）: " + movingNodes);
        }
    }


    /** 姿勢を作った数（1 フレームあたり）。{@code voidwave.html} の中身のスクロールを、往復で {@code frames} フレーム回す。 */
    private static double posesPerScrollFrame(boolean reuse) throws Exception {
        com.nishiyu.enderframelib.core.kernel.Shortcut.REUSE_IDLE_POSES.set(reuse);
        String html = Files.readString(Path.of("src/main/resources/data/enderframelib/enderframe_ui/voidwave.html"), StandardCharsets.UTF_8);
        int frames = 40;
        try (SoftwareRenderBackend backend = new SoftwareRenderBackend(640, 400, 1f);
             StandaloneView v = new StandaloneView(html, 640, 400, FontResolver.FALLBACK)) {
            long t = 1000;
            v.frame(t, backend, true);
            v.mouseMove(50, 50, backend.sessionId()); // FineProfileMain の scroll と同じ点
            for (int i = 0; i < 10; i++) { // 温め（索引と覚えを作る）
                v.scroll(0, (i & 1) == 0 ? -1 : 1, backend.sessionId());
                v.frame(t += 16, backend, true);
            }
            long before = backend.driver.posesBuilt();
            for (int i = 0; i < frames; i++) {
                v.scroll(0, (i & 1) == 0 ? -1 : 1, backend.sessionId());
                v.frame(t += 16, backend, true);
            }
            return (backend.driver.posesBuilt() - before) / (double) frames;
        }
    }

    /**
     * voidwave の {@code scroll}（中身の節が毎フレーム動く）で作る姿勢の数。以前は見えている全項目の姿勢を作り直し、GL で 1 フレーム
     * {@code 129.5 KB} を確保していた（共有で {@code 85.5 KB}）。確保量は機械で揺れるので、作った数で見る（時間に依らない合否）。
     */
    @Test
    void voidwaveScrollBuildsFewPoses() throws Exception {
        double rebuilt = posesPerScrollFrame(false), shared = posesPerScrollFrame(true);
        System.out.printf("POSES voidwave scroll: rebuilt %.1f / shared %.1f per frame%n", rebuilt, shared);
        assertTrue(rebuilt > 300, "毎回作るときの姿勢の数が少なすぎる（スクロールが中身を動かしていない？）: " + rebuilt);
        assertTrue(shared < rebuilt * 0.4, "親との共有で作る姿勢が減っていない: 共有 " + shared + " / 毎回作る " + rebuilt);
    }

    /**
     * K4e: 親の補間値の参照だけが毎フレーム変わる（色だけが動く節。姿勢・不透明度は同じ値）とき、その子孫の姿勢を作り直さない。
     * 以前は親の {@code Resolved} が新しくなるたびに、子孫の節の姿勢が連鎖して作り直されていた（voidwave の {@code hover} で
     * 1 フレーム約 {@code 674} 個・確保の約 35%）。値で見て同じ親なら前回の姿勢のまま。{@code VERIFY_MEMO} を立てて、使い回した姿勢が
     * 作り直した姿勢と食い違わないこと（{@code PoseVerifier}）も確かめる。
     */
    @Test
    void colorOnlyParentDoesNotRebuildDescendantPoses() {
        StringBuilder inner = new StringBuilder();
        for (int g = 0; g < 6; g++) {
            inner.append("<div class='grp' id='g").append(g).append("'>");
            for (int r = 0; r < 4; r++) inner.append("<div class='cell' id='c").append(g).append('_').append(r).append("'><span>cell</span></div>");
            inner.append("</div>");
        }
        String html = "<html><head><style>"
                + "@keyframes hue { from { background-color: #f44; transform: translateX(0) } to { background-color: #44f; transform: translateX(0) } }"
                + "body{margin:0;font:12px sans-serif}"
                + "#pulse{position:absolute;left:4px;top:4px;width:240px;height:190px;overflow:hidden;animation:hue 1s linear infinite}"
                + ".grp{margin:2px;padding:2px;overflow:hidden;position:relative}"
                + ".cell{height:10px;margin:1px;padding:1px;position:relative;overflow:hidden;background:#cde}"
                + "</style></head><body><div id='pulse'>" + inner + "</div></body></html>";
        boolean verify = com.nishiyu.enderframelib.core.kernel.Shortcut.VERIFY_MEMO.on();
        com.nishiyu.enderframelib.core.kernel.Shortcut.VERIFY_MEMO.set(true);
        try (SoftwareRenderBackend backend = new SoftwareRenderBackend(W, H, 1f);
             StandaloneView v = new StandaloneView(html, W, H, FontResolver.FALLBACK)) {
            long t = 1000;
            for (int i = 0; i < 6; i++) v.frame(t += 17, backend, true); // 温め
            PaintSceneDriver d = backend.driver;
            int nodes = 0;
            for (DisplayItem it : d.items()) if (it.type() == ElementType.NODE) nodes++;
            long before = d.posesBuilt();
            int frames = 30;
            for (int i = 0; i < frames; i++) v.frame(t += 17, backend, true);
            double perFrame = (d.posesBuilt() - before) / (double) frames;
            System.out.printf("POSES colorOnlyParent: %.1f per frame (nodes %d)%n", perFrame, nodes);
            assertTrue(nodes >= 20, "子孫の節が足りない（空の検証）: " + nodes);
            assertTrue(perFrame <= 3, "色だけが動く親の下で、子孫の姿勢を作り直している: " + perFrame + " / フレーム（節 " + nodes + "）");
        } finally {
            com.nishiyu.enderframelib.core.kernel.Shortcut.VERIFY_MEMO.set(verify);
        }
    }

    private static boolean identity(AnimatedValues a) {
        return a.opacity() == 1f && a.tx() == 0f && a.ty() == 0f && a.sx() == 1f && a.sy() == 1f && a.rot() == 0f && a.skew() == 0f;
    }
}
