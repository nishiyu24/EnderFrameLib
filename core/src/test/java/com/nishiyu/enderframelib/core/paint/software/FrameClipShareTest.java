package com.nishiyu.enderframelib.core.paint.software;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * B12: 動く持ち主（アニメーション中の {@code overflow: hidden} の箱）の下の項目は、項目ごとのクリップの覚えが毎フレーム外れる。
 * 同じフレームの中で同じクリップの元を持つ項目どうしは、組んだクリップを分け合う（{@link SoftwareRenderBackend#CLIP_SHARE}）。
 * 分け合っても画素は 1 つも変わらないこと（移動・回転・拡縮の持ち主、入れ子のクリップ、角丸のクリップ）と、実際に分け合ったことを確かめる。
 */
class FrameClipShareTest {
    private static final int W = 360, H = 260;

    private static final String DOC = "<html><head><style>"
            + "@keyframes bob{0%{transform:translateY(0px)}50%{transform:translateY(-9px) rotate(3deg)}100%{transform:translateY(0px)}}"
            + "@keyframes grow{0%{transform:scale(1)}50%{transform:scale(1.2,0.9)}100%{transform:scale(1)}}"
            + "body{margin:8px;font:12px sans-serif;background:#fafafa}"
            + ".win{width:200px;height:90px;overflow:hidden;border-radius:12px;background:#123;color:#fff;animation:bob 1.3s ease-in-out infinite}"
            + ".inner{margin:6px;height:50px;overflow:hidden;background:#345;animation:grow 0.9s linear infinite}"
            + ".row{height:14px;margin:2px 0;background:#6a9;width:260px}"
            + ".still{width:180px;height:60px;overflow:hidden;background:#ddd;margin-top:8px}"
            + "</style></head><body>"
            + "<div class='win'><div class='row'>row a</div><div class='row'>row b</div>"
            + "<div class='inner'><div class='row'>nested 1</div><div class='row'>nested 2</div><div class='row'>nested 3</div></div>"
            + "<div class='row'>row c</div></div>"
            + "<div class='still'><div class='row'>static 1</div><div class='row'>static 2</div></div>"
            + "</body></html>";

    @AfterEach
    void restore() {
        SoftwareRenderBackend.CLIP_SHARE = true;
    }

    private static List<int[]> run(boolean share) {
        SoftwareRenderBackend.CLIP_SHARE = share;
        List<int[]> out = new ArrayList<>();
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(W, H, 1f);
             StandaloneView v = new StandaloneView(DOC, W, H, FontResolver.FALLBACK)) {
            long t = 1000;
            for (int i = 0; i < 24; i++) {
                v.frame(t += 53, cpu, true);
                out.add(cpu.pixmap().pixels().clone());
            }
        }
        return out;
    }

    @Test
    void sharingClipsWithinAFrameKeepsEveryPixel() {
        List<int[]> off = run(false);
        long shared0 = SoftwareRenderBackend.STAT_SHARED_CLIPS.sum();
        List<int[]> on = run(true);
        long shared = SoftwareRenderBackend.STAT_SHARED_CLIPS.sum() - shared0;
        assertTrue(shared > 0, "動く持ち主の下でクリップを実際に分け合った（空の検証ではない）");
        assertEquals(off.size(), on.size());
        for (int i = 0; i < off.size(); i++) {
            assertArrayEquals(off.get(i), on.get(i), "フレーム " + i + " の画素が分け合わない描画と違う");
        }
        boolean moved = false;
        for (int i = 1; i < on.size() && !moved; i++) moved = !java.util.Arrays.equals(on.get(i - 1), on.get(i));
        assertTrue(moved, "アニメーションが進んでいる（静止画の比較ではない）");
    }
}
