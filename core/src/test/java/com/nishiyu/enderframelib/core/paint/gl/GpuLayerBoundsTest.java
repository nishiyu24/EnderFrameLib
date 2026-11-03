package com.nishiyu.enderframelib.core.paint.gl;

import com.nishiyu.enderframelib.common.SoftwareRendering;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GPU の層の効果（ぼかし・影・色の行列・背景のフィルター・マスク・クリップ・ブレンド）を、描いた範囲の中だけに限っても
 * （{@link LwjglGlRenderBackend#boundedLayers}）、画面全体に掛けたときと同じ画像になること。大きなぼかしを縮小して行うとき
 * （{@link LwjglGlRenderBackend#downsampledBlur}）は、ぼかした絵が滑らかなので、小さな差だけを許す。
 *
 * <p>入れ子の層・画面の端・変換・フィルターの連鎖・devicePixelRatio 2 を含む。GL を使えない環境ではスキップする。</p>
 */
class GpuLayerBoundsTest {

    private static final String BODY0 = "<html><body style='margin:0;background:#2a3b4c'>";
    private static final long T0 = 10_000L;

    private static LwjglGlRenderBackend probe;
    private static String unavailable;

    @BeforeAll
    static void probe() {
        try {
            probe = new LwjglGlRenderBackend(16, 16);
        } catch (Throwable t) {
            unavailable = t.toString();
        }
    }

    @AfterAll
    static void cleanup() {
        if (probe != null) probe.close();
    }

    @AfterEach
    void restore() {
        LwjglGlRenderBackend.boundedLayers = true;
        LwjglGlRenderBackend.downsampledBlur = true;
    }

    private static Pixmap render(String body, int w, int h, float dpr, boolean bounded, boolean downsampled) {
        LwjglGlRenderBackend.boundedLayers = bounded;
        LwjglGlRenderBackend.downsampledBlur = downsampled;
        try (LwjglGlRenderBackend g = new LwjglGlRenderBackend(w, h, dpr)) {
            SoftwareRendering.renderHtml(BODY0 + body + "</body></html>", w, h, T0, g);
            Pixmap src = g.pixmap();
            Pixmap copy = new Pixmap(src.width(), src.height());
            System.arraycopy(src.pixels(), 0, copy.pixels(), 0, src.pixels().length);
            return copy;
        }
    }

    private record Diff(int max, int over, int total, String where) {}

    private static Diff diff(Pixmap a, Pixmap b, int threshold) {
        int max = 0, over = 0, x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;
        for (int y = 0; y < a.height(); y++) {
            for (int x = 0; x < a.width(); x++) {
                int p = a.get(x, y), q = b.get(x, y);
                int m = 0;
                for (int s = 0; s <= 24; s += 8) m = Math.max(m, Math.abs(((p >>> s) & 0xFF) - ((q >>> s) & 0xFF)));
                max = Math.max(max, m);
                if (m > threshold) {
                    over++;
                    x0 = Math.min(x0, x);
                    y0 = Math.min(y0, y);
                    x1 = Math.max(x1, x);
                    y1 = Math.max(y1, y);
                }
            }
        }
        return new Diff(max, over, a.width() * a.height(), over == 0 ? "-" : "(" + x0 + "," + y0 + ")-(" + x1 + "," + y1 + ")");
    }

    private static final List<String> SCENES = new ArrayList<>();

    static {
        SCENES.add("<div style='position:absolute;left:60px;top:40px;width:80px;height:60px;background:#f44;filter:blur(6px)'></div>");
        // 画面の端（一部が外）
        SCENES.add("<div style='position:absolute;left:-20px;top:-10px;width:90px;height:70px;background:#4f4;filter:blur(10px)'></div>"
                + "<div style='position:absolute;right:-15px;bottom:-12px;width:90px;height:70px;background:#ff4;filter:blur(8px)'></div>");
        // 大きなぼかし（縮小の経路に入る）
        SCENES.add("<div style='position:absolute;left:50px;top:30px;width:100px;height:100px;background:#f0f;filter:blur(30px)'></div>");
        SCENES.add("<div style='position:absolute;left:-30px;top:20px;width:200px;height:120px;background:#0ff;filter:blur(45px)'></div>");
        // ドロップシャドウ
        SCENES.add("<div style='position:absolute;left:40px;top:30px;width:70px;height:50px;background:#fc3;filter:drop-shadow(8px 6px 5px #000)'></div>");
        // 影の元の絵は画面の内側から読まれる配置にする（端の画素を引き伸ばす基準側の癖を避ける）
        SCENES.add("<div style='position:absolute;left:90px;top:60px;width:50px;height:50px;background:#fc3;filter:drop-shadow(30px 20px 22px #000)'></div>");
        // 色の行列
        SCENES.add("<div style='position:absolute;left:40px;top:30px;width:70px;height:50px;background:#39c;filter:grayscale(1) brightness(1.4)'></div>");
        // フィルターの連鎖（段が 2 つ以上は画面全体に落ちる）
        SCENES.add("<div style='position:absolute;left:40px;top:30px;width:70px;height:50px;background:#c93;filter:blur(4px) brightness(1.3)'></div>");
        SCENES.add("<div style='position:absolute;left:40px;top:30px;width:70px;height:50px;background:#c93;filter:brightness(0.8) blur(4px)'></div>");
        SCENES.add("<div style='position:absolute;left:30px;top:20px;width:70px;height:50px;background:#c39;filter:blur(3px) drop-shadow(6px 6px 4px #000)'></div>");
        // 入れ子の層
        SCENES.add("<div style='position:absolute;left:20px;top:20px;width:160px;height:120px;opacity:.6;background:#444'>"
                + "<div style='position:absolute;left:20px;top:20px;width:60px;height:50px;background:#f80;filter:blur(7px)'></div>"
                + "<div style='position:absolute;left:90px;top:40px;width:50px;height:50px;background:#08f;filter:blur(25px)'></div></div>");
        SCENES.add("<div style='position:absolute;left:20px;top:20px;width:150px;height:110px;filter:blur(3px)'>"
                + "<div style='position:absolute;left:20px;top:20px;width:60px;height:50px;background:#f80;filter:blur(8px)'></div></div>");
        // 変換
        SCENES.add("<div style='position:absolute;left:60px;top:40px;width:80px;height:50px;background:#e33;transform:rotate(30deg);filter:blur(5px)'></div>");
        SCENES.add("<div style='position:absolute;left:60px;top:40px;width:80px;height:50px;background:#e33;transform:scale(1.8);filter:blur(4px)'></div>");
        // 背景のフィルター
        SCENES.add("<div style='position:absolute;left:0;top:0;width:240px;height:180px;background:linear-gradient(90deg,#f00,#00f)'></div>"
                + "<div style='position:absolute;left:50px;top:30px;width:100px;height:80px;border-radius:14px;backdrop-filter:blur(8px);background:rgba(255,255,255,.1)'></div>");
        SCENES.add("<div style='position:absolute;left:0;top:0;width:240px;height:180px;background:repeating-linear-gradient(45deg,#fff,#fff 6px,#000 6px,#000 12px)'></div>"
                + "<div style='position:absolute;left:-10px;top:60px;width:120px;height:90px;backdrop-filter:blur(20px)'></div>");
        SCENES.add("<div style='position:absolute;left:0;top:0;width:240px;height:180px;background:linear-gradient(#fa0,#05f)'></div>"
                + "<div style='position:absolute;left:40px;top:30px;width:90px;height:70px;opacity:.7;backdrop-filter:invert(1) saturate(1.5)'></div>");
        // クリップ・マスク・ブレンド
        SCENES.add("<div style='position:absolute;left:40px;top:30px;width:100px;height:100px;background:#fc0;filter:blur(6px);clip-path:circle(40px)'></div>");
        SCENES.add("<div style='position:absolute;left:40px;top:30px;width:120px;height:90px;background:#0cf;filter:blur(5px);"
                + "-webkit-mask-image:linear-gradient(black,transparent);mask-image:linear-gradient(black,transparent)'></div>");
        SCENES.add("<div style='position:absolute;left:30px;top:30px;width:100px;height:80px;background:#f0f'></div>"
                + "<div style='position:absolute;left:70px;top:50px;width:90px;height:80px;background:#0ff;filter:blur(5px);mix-blend-mode:multiply'></div>");
        // 混ぜる層（mix-blend-mode）+ ぼかし。画面の端・大きなぼかし・重なり（背景ブロブと同じ作り）
        SCENES.add("<div style='position:absolute;left:0;top:0;width:240px;height:180px;background:linear-gradient(90deg,#123,#345)'></div>"
                + "<div style='position:absolute;left:-40px;top:10px;width:150px;height:150px;border-radius:50%;filter:blur(30px);mix-blend-mode:screen;"
                + "background:radial-gradient(circle,#f39,rgba(255,51,153,0) 70%)'></div>"
                + "<div style='position:absolute;right:-60px;bottom:-30px;width:190px;height:190px;border-radius:50%;filter:blur(24px);mix-blend-mode:screen;"
                + "background:radial-gradient(circle,#2ef,rgba(34,238,255,0) 70%)'></div>"
                + "<div style='position:absolute;left:60px;top:40px;width:90px;height:90px;filter:blur(5px);mix-blend-mode:difference;background:#fa0'></div>");
        SCENES.add("<div style='position:absolute;left:20px;top:20px;width:200px;height:140px;opacity:.8;background:#357'>"
                + "<div style='position:absolute;left:30px;top:20px;width:100px;height:90px;filter:blur(12px);mix-blend-mode:overlay;background:#f80'></div></div>");
        // 文字の影
        SCENES.add("<div style='position:absolute;left:20px;top:30px;font-size:30px;color:#fff;text-shadow:0 0 12px #f0f,4px 4px 8px #000'>Hello</div>");
        // overflow で切られる中のぼかし
        SCENES.add("<div style='position:absolute;left:30px;top:30px;width:90px;height:70px;overflow:hidden;background:#111'>"
                + "<div style='position:absolute;left:50px;top:30px;width:80px;height:60px;background:#f60;filter:blur(9px)'></div></div>");
        // 重なる小さな層がたくさん
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            many.append("<div style='position:absolute;left:").append(10 + i * 17).append("px;top:").append(20 + (i % 4) * 30)
                    .append("px;width:30px;height:30px;background:hsl(").append(i * 30).append(",80%,55%);filter:blur(").append(2 + i % 5).append("px)'></div>");
        }
        SCENES.add(many.toString());
    }

    @Test
    void boundingTheLayerEffectsDoesNotChangeTheImage() {
        Assumptions.assumeTrue(probe != null, "OpenGL を使えない: " + unavailable);
        for (int i = 0; i < SCENES.size(); i++) {
            String body = SCENES.get(i);
            for (float dpr : new float[]{1f, 2f}) {
                // 縮小を切って、範囲の限定だけの差を見る（誤差は丸めだけ）
                Pixmap full = render(body, 240, 180, dpr, false, false);
                Pixmap bounded = render(body, 240, 180, dpr, true, false);
                Diff d = diff(full, bounded, 1);
                assertTrue(d.max() <= 2, "範囲の限定で絵が変わった（場面 " + i + "、dpr " + dpr + "）: 最大差 " + d.max() + "、" + d.over() + " 画素、位置 " + d.where());
            }
        }
    }

    @Test
    void downsamplingLargeBlursKeepsTheImageClose() {
        Assumptions.assumeTrue(probe != null, "OpenGL を使えない: " + unavailable);
        for (int i = 0; i < SCENES.size(); i++) {
            String body = SCENES.get(i);
            Pixmap full = render(body, 240, 180, 1f, false, false);
            Pixmap down = render(body, 240, 180, 1f, true, true);
            Diff d = diff(full, down, 10);
            // ぼかした絵は滑らかなので縮小しても近い: 大きくずれる画素は 1% 未満、最大差も小さい
            assertTrue(d.max() <= 28 && d.over() * 100 < d.total(),
                    "縮小のぼかしが全解像度から離れた（場面 " + i + "）: 最大差 " + d.max() + "、10 を超えた画素 " + d.over() + " / " + d.total() + "、位置 " + d.where());
        }
    }

    @Test
    void theScenesReallyDrawSomething() {
        Assumptions.assumeTrue(probe != null, "OpenGL を使えない: " + unavailable);
        // 何も描かない場面では、比べる意味が無い
        Pixmap blank = render("", 240, 180, 1f, false, false);
        for (int i = 0; i < SCENES.size(); i++) {
            Diff d = diff(blank, render(SCENES.get(i), 240, 180, 1f, true, true), 8);
            assertTrue(d.over() > 200, "場面 " + i + " が背景から変わっていない: " + d.over());
        }
    }
}
