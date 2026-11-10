package com.nishiyu.enderframelib.core.paint.software;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.nishiyu.enderframelib.core.raster.Pixmap;
import org.junit.jupiter.api.Test;

/**
 * {@link CpuRaster#scrollBlit} のピクセルシフトと露出領域クリアの検証。
 */
class ScrollBlitTest {

    @Test
    void verticalScrollDownShiftsPixelsUpAndClearsExposedBottom() {
        // 幅 4、高さ 6 の面を用意。各セル (x, y) に (y * 10 + x) の値を書き込む
        CpuRaster raster = new CpuRaster(4, 6);
        Pixmap root = raster.root();
        for (int y = 0; y < 6; y++) {
            for (int x = 0; x < 4; x++) {
                root.set(x, y, y * 10 + x);
            }
        }

        // dy = -2（上へ 2 ピクセルシフト = 下スクロール）
        // 露出領域は y in [4, 6) で、クリア色 0xFF を書き込む
        raster.scrollBlit(0, 0, 4, 6, 0, -2, 0xFF);

        // シフトされた行の検証: 新しい y=0 は旧 y=2、新しい y=1 は旧 y=3、新しい y=2 は旧 y=4、新しい y=3 は旧 y=5
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                assertEquals((y + 2) * 10 + x, root.get(x, y), "x=" + x + ", y=" + y);
            }
        }
        // 露出領域の検証: y in [4, 6) はクリア色 0xFF
        for (int y = 4; y < 6; y++) {
            for (int x = 0; x < 4; x++) {
                assertEquals(0xFF, root.get(x, y), "exposed bottom x=" + x + ", y=" + y);
            }
        }
    }

    @Test
    void verticalScrollUpShiftsPixelsDownAndClearsExposedTop() {
        CpuRaster raster = new CpuRaster(4, 6);
        Pixmap root = raster.root();
        for (int y = 0; y < 6; y++) {
            for (int x = 0; x < 4; x++) {
                root.set(x, y, y * 10 + x);
            }
        }

        // dy = +2（下へ 2 ピクセルシフト = 上スクロール）
        // 露出領域は y in [0, 2) で、クリア色 0xEE
        raster.scrollBlit(0, 0, 4, 6, 0, 2, 0xEE);

        // 露出領域の検証: y in [0, 2) はクリア色 0xEE
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 4; x++) {
                assertEquals(0xEE, root.get(x, y), "exposed top x=" + x + ", y=" + y);
            }
        }
        // シフトされた行の検証: 新しい y=2 は旧 y=0、新しい y=3 は旧 y=1、新しい y=4 は旧 y=2、新しい y=5 は旧 y=3
        for (int y = 2; y < 6; y++) {
            for (int x = 0; x < 4; x++) {
                assertEquals((y - 2) * 10 + x, root.get(x, y), "x=" + x + ", y=" + y);
            }
        }
    }

    @Test
    void horizontalScrollLeftShiftsPixelsLeftAndClearsExposedRight() {
        CpuRaster raster = new CpuRaster(6, 4);
        Pixmap root = raster.root();
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 6; x++) {
                root.set(x, y, y * 10 + x);
            }
        }

        // dx = -2（左へ 2 ピクセルシフト）
        // 露出領域は x in [4, 6)
        raster.scrollBlit(0, 0, 6, 4, -2, 0, 0xAA);

        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                assertEquals(y * 10 + (x + 2), root.get(x, y), "x=" + x + ", y=" + y);
            }
            for (int x = 4; x < 6; x++) {
                assertEquals(0xAA, root.get(x, y), "exposed right x=" + x + ", y=" + y);
            }
        }
    }

    @Test
    void subRectScrollBlitPreservesOuterPixels() {
        // 部分領域 [1, 1, 5, 5) のみをスクロール
        CpuRaster raster = new CpuRaster(6, 6);
        Pixmap root = raster.root();
        for (int y = 0; y < 6; y++) {
            for (int x = 0; x < 6; x++) {
                root.set(x, y, y * 10 + x);
            }
        }

        raster.scrollBlit(1, 1, 5, 5, 0, -1, 0xCC);

        // 外周 (x=0 or 5 or y=0 or 5) は一切変化しない
        for (int y = 0; y < 6; y++) {
            for (int x = 0; x < 6; x++) {
                if (x == 0 || x == 5 || y == 0 || y == 5) {
                    assertEquals(y * 10 + x, root.get(x, y), "outer pixel at " + x + "," + y);
                }
            }
        }

        // 内部 [1, 1, 5, 5)
        // dy = -1: 新 y=1 は旧 y=2, 新 y=2 は旧 y=3, 新 y=3 は旧 y=4, 新 y=4 は露出 (0xCC)
        for (int x = 1; x < 5; x++) {
            assertEquals(20 + x, root.get(x, 1));
            assertEquals(30 + x, root.get(x, 2));
            assertEquals(40 + x, root.get(x, 3));
            assertEquals(0xCC, root.get(x, 4));
        }
    }
}
