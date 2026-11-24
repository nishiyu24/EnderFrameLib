package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2a の新しい整形（{@link FragmentLayout}）が「本物」の結果を出すことの確認: 実 DOM ＋ M1 の実 {@code Style} を、旧経路
 * （{@code StandaloneView} の {@code LayoutBox}）と新経路の両方に通し、{@code id} を持つ箱の位置・寸法を比べる。
 *
 * <p>許容は 1/64 px（flex の柔軟長の分配での丸めの順の差）。意図した差は 2 つで、ここで明示する:
 * ① 負の余白が親の下端を素通しして次の兄弟と相殺される場面は、v3 が CSS の規則どおり（正の最大 ＋ 負の最小）で、旧経路は負を落とす
 * ② インライン要素は行の断片へ平らに入るので、箱の断片を持たない（比較から外す）。</p>
 */
class LegacyParityTest {
    private static final float TOL = 1f / 64f + 1e-4f;

    @Test
    void largeListMatchesLegacy() {
        assertParity(SpikeView.largeList(120), List.of());
    }

    @Test
    void blockFlowMatchesLegacy() {
        assertParity("<html><body><div id=a style='margin:10px 0'><p id=b style='margin:20px 0'>x</p><p id=c style='margin:5px 0 3px'>y</p></div>"
                + "<div id=d style='margin-top:30px;border-top:1px solid'>z</div><div id=e style='width:200px;margin:0 auto;padding:3px'>w</div>"
                + "<div id=f style='width:50%;min-height:10px'></div><div id=g style='margin:7px 0'></div><div id=h style='height:40px;max-height:30px'>q</div>"
                + "<div id=i style='padding:5px;border:2px solid'><div id=j style='margin:12px 4px;width:30%'>inner</div></div>"
                + "<div id=k style='box-sizing:border-box;width:100px;padding:10px;height:50px'>bb</div></body></html>", List.of());
    }

    @Test
    void inlineWrappingMatchesLegacy() {
        assertParity("<html><body style='margin:0'><p id=p style='width:120px'>alpha beta gamma delta epsilon zeta eta theta iota kappa lambda</p>"
                + "<div id=q style='text-align:center;width:300px'>centered text here</div>"
                + "<div id=r>one <b style='padding:0 5px'>bold</b> two <span>three</span> four five six seven eight nine ten eleven twelve</div>"
                + "<div id=u style='width:90px;line-height:30px'>line height wraps here</div><div id=v style='white-space:nowrap;width:50px'>no wrap at all here</div>"
                + "<div id=w style='white-space:pre'>a\nbb\nccc</div></body></html>", List.of());
    }

    @Test
    void flexMatchesLegacy() {
        assertParity("<html><body style='margin:0'><div id=row style='display:flex;width:500px'><div id=r1 style='flex-grow:1'>a</div>"
                + "<div id=r2 style='flex-grow:2;padding:4px'>bb</div><div id=r3 style='width:100px'>ccc</div></div>"
                + "<div id=wrap style='display:flex;flex-wrap:wrap;width:200px'><div id=w1 style='width:120px'>a</div><div id=w2 style='width:120px'>b b b b b</div>"
                + "<div id=w3 style='width:50px;height:40px'>c</div></div>"
                + "<div id=col style='display:flex;flex-direction:column;height:200px'><div id=c1 style='flex-grow:1'>x</div><div id=c2>y</div><div id=c3 style='flex-grow:3'>z</div></div>"
                + "<div id=st style='display:flex;align-items:center;height:60px'><div id=s1>m</div><div id=s2 style='height:30px'>n</div></div>"
                + "<div id=shrink style='display:flex;width:100px'><div id=k1 style='width:80px'>longword</div><div id=k2 style='width:80px'>x</div></div>"
                + "<div id=gap style='display:flex;column-gap:10px;justify-content:space-between;width:300px'><div id=g1>a</div><div id=g2>b</div><div id=g3>c</div></div>"
                + "</body></html>", List.of());
    }

    /** 意図した差 ①: 負の余白は CSS の規則どおり相殺に加わる（旧経路より 3 px 上）。 */
    @Test
    void negativeMarginCollapsesThroughParentPerSpec() {
        String html = "<html><body><div id=a style='margin:10px 0'><p id=b style='margin:20px 0'>x</p><p id=c style='margin:5px 0 -3px'>y</p></div>"
                + "<div id=d style='margin-top:30px'>z</div></body></html>";
        LayoutFragment root = new SpikeView(html, 800, 600).frame();
        float[] a = SpikeView.find(root, "a"), d = SpikeView.find(root, "d");
        // a の下端に、c の -3・a の 10・d の 30 が相殺されて 30 + (-3) = 27
        assertEquals(a[1] + a[3] + 27f, d[1], 1e-3f);
    }

    private static void assertParity(String html, List<String> skip) {
        LayoutFragment root = new SpikeView(html, 800, 600).frame();
        List<String> diffs = new ArrayList<>();
        int[] compared = {0};
        try (StandaloneView sv = new StandaloneView(html, 800, 600, FontResolver.FALLBACK)) {
            sv.frame(1000, new SpikeView.NullBackend(), true);
            LayoutBox legacy = sv.ui().layoutRoot();
            assertNotNull(legacy);
            compare(legacy, root, skip, diffs, compared);
        }
        assertTrue(compared[0] > 0, "比べた箱が無い");
        assertTrue(diffs.isEmpty(), "旧経路との差:\n" + String.join("\n", diffs));
    }

    private static void compare(LayoutBox b, LayoutFragment root, List<String> skip, List<String> diffs, int[] compared) {
        String id = b.node != null ? b.node.attrs.get("id") : null;
        if (id != null && !skip.contains(id) && !"html_root".equals(id)) {
            float[] f = SpikeView.find(root, id);
            if (f != null) { // インライン要素は箱の断片を持たない（意図した差 ②）
                compared[0]++;
                float[] old = {b.x(), b.y(), b.w(), b.h()};
                for (int i = 0; i < 4; i++) {
                    if (Math.abs(old[i] - f[i]) > TOL) {
                        diffs.add(String.format("%s legacy=%.3f,%.3f %.3fx%.3f v3=%.3f,%.3f %.3fx%.3f", id, old[0], old[1], old[2], old[3], f[0], f[1], f[2], f[3]));
                        break;
                    }
                }
            }
        }
        for (LayoutBox c : b.children) compare(c, root, skip, diffs, compared);
    }
}
