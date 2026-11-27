package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

/**
 * 大きさ 0 の要素（節の錨を出さない）の子孫も、祖先の変形・不透明度・スクロールを受ける（M3b-4b-3 で見つけた欠陥）。
 * 以前は子孫の項目の親が出していない錨を指し、座標を絶対にしていたので、祖先の変形・不透明度のグループ・スクロールの中身の節の移動から
 * 外れていた（表示項目の列は作ったときのスクロールを焼き込むので、スクロールだけの更新でその子孫だけが動かなかった）。
 * 比べる相手は包みの高さを auto にした同じ文書（子の置き場所は同じ）で、子の赤い箱の画面の上の位置が一致すること。
 */
class ZeroSizeAncestorTest {
    private static int[] redBox(String wrapperStyle, String ancestorStyle) {
        String html = "<style>body{margin:0}</style><div style='" + ancestorStyle + ";margin:10px 0 0 30px;width:300px;height:120px'>"
                + "<div style='" + wrapperStyle + "'><div style='width:40px;height:20px;background:#f00'></div></div></div>";
        try (StandaloneView v = new StandaloneView(html, 400, 200, FontResolver.FALLBACK);
             SoftwareRenderBackend b = new SoftwareRenderBackend(400, 200)) {
            v.frame(1000L, b, true);
            int[] px = b.pixmap().pixels();
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
            for (int y = 0; y < 200; y++) for (int x = 0; x < 400; x++) {
                int p = px[y * 400 + x];
                int a = p >>> 24, r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, bl = p & 0xFF;
                if (a > 0 && r > 64 && g < 32 && bl < 32) { minX = Math.min(minX, x); minY = Math.min(minY, y); maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); }
            }
            return new int[]{minX, minY, maxX, maxY};
        }
    }

    @Test
    void childOfZeroHeightWrapperFollowsAncestorTransform() {
        String t = "transform:translate(100px,40px)";
        int[] want = redBox("height:auto", t), got = redBox("height:0", t);
        assertEquals(130, want[0], "前提: 包みの高さが auto なら変形が掛かる（祖先の位置 30 + 移動 100）");
        assertArrayEquals(want, got, "高さ 0 の包みの子に祖先の変形が掛からない");
    }

    @Test
    void childOfZeroHeightWrapperIsInsideAncestorOpacityGroup() {
        String o = "opacity:0.5";
        int[] want = redBox("height:auto", o), got = redBox("height:0", o);
        assertArrayEquals(want, got);
        // 不透明度は色で見る: グループの中なら赤は半分の不透明度になる
        assertEquals(pixelAt("height:auto", o, want), pixelAt("height:0", o, want), "高さ 0 の包みの子が祖先の不透明度のグループから外れた");
    }

    private static int pixelAt(String wrapperStyle, String ancestorStyle, int[] box) {
        String html = "<style>body{margin:0}</style><div style='" + ancestorStyle + ";margin:10px 0 0 30px;width:300px;height:120px'>"
                + "<div style='" + wrapperStyle + "'><div style='width:40px;height:20px;background:#f00'></div></div></div>";
        try (StandaloneView v = new StandaloneView(html, 400, 200, FontResolver.FALLBACK);
             SoftwareRenderBackend b = new SoftwareRenderBackend(400, 200)) {
            v.frame(1000L, b, true);
            return b.pixmap().pixels()[(box[1] + 5) * 400 + box[0] + 5];
        }
    }
}
