package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.SceneWalkAccess;
import com.nishiyu.enderframelib.core.paint.PaintSceneDriver.Group;
import com.nishiyu.enderframelib.core.paint.PaintSceneDriver.Resolved;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.PaintOffsets;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.raster.Matrix2D;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * 走査器の影の確かめ（M3b-4b-2。計画書 §3.4「M3b-4b の設計」の 4b-2）: 実文書 9 本を {@link RealDocumentFramesTest} の操作の手順
 * （開く → ホバー → スクロール → 文字の変更 → 幅の変更）で流し、記録するフレームごとに
 * <ul>
 *   <li>ビューの表示項目の列を {@link PaintSceneDriver} でたどった描画側への呼び出しと、</li>
 *   <li>同じシーンを {@link SceneWalker} でたどった呼び出し</li>
 * </ul>
 * が、描く項目・順・グループの鎖（層にするもの・不透明度・錨・層の指定・マスク）・層にしない不透明度・姿勢（行列・不透明度の積）・補間値まで
 * 一致することを確かめる。走査器は表示項目の列をフレームをまたいで持たず、層の範囲を一時アダプタの知らせから取り、補間の状態を節ごとの表から
 * 引く。保持層・画面外のブロックの間引き・損傷の範囲は 4b-3 で比べる（ここでは描画側が既定の {@link Painter}）。
 */
class SceneWalkerShadowTest {

    @TestFactory
    Stream<DynamicTest> walkerMatchesDriverEveryFrame() {
        return RealDocumentFramesTest.DOCS.stream().map(doc -> DynamicTest.dynamicTest(doc, () -> walkerMatchesDriver(doc)));
    }

    /** 描画側への呼び出しを文字列の列に写す記録係。 */
    private static final class Recorder implements Painter {
        final List<String> calls = new ArrayList<>();
        final List<DisplayItem> drawn = new ArrayList<>();
        /** 描いた項目の姿勢の縦の移動（スクロールの中身の節の平行移動を見る）。 */
        final List<Double> poseY = new ArrayList<>();
        /** 描いた項目の画面の上の形（姿勢で写した箱の 4 隅・不透明度・層の鎖の ID）。スクロールを焼き込む位置に依らない比べ方。 */
        final List<String> placed = new ArrayList<>();
        private String layers = "";
        final IntFunction<Resolved> pose;
        final IntFunction<AnimatedValues> anim;

        Recorder(IntFunction<Resolved> pose, IntFunction<AnimatedValues> anim) {
            this.pose = pose;
            this.anim = anim;
        }

        @Override
        public void syncLayers(List<Group> want) {
            StringBuilder b = new StringBuilder("layers");
            for (Group g : want) {
                b.append(" [").append(g.id()).append(" op=").append(g.opacity()).append(" @").append(g.anchor())
                        .append(" spec=").append(canon(g.spec())).append(" mask=").append(g.maskOf())
                        .append(" pose=").append(matrix(g.pose())).append(']');
            }
            String s = b.toString();
            StringBuilder ids = new StringBuilder();
            for (Group g : want) ids.append(g.id()).append(g.maskOf() != null ? "#mask" : "").append('/').append(g.opacity()).append(' ');
            layers = ids.toString();
            if (calls.isEmpty() || !calls.getLast().equals(s)) calls.add(s); // 同じ層の鎖の繰り返しは描画側で何もしない
        }

        @Override
        public void drawItem(DisplayItem el, int index, float flat) {
            Resolved r = pose.apply(index);
            AnimatedValues a = anim.apply(index);
            calls.add("draw #" + index + " " + el.id() + " key=" + Long.toHexString(el.key()) + " flat=" + flat
                    + " pose=" + matrix(r.pose()) + " op=" + r.opacity() + " res=" + r.residual() + " groups=" + r.groups().size()
                    + " anim=" + a.opacity() + "," + a.tx() + "," + a.ty() + "," + a.sx() + "," + a.sy() + "," + a.rot() + "," + a.skew()
                    + "," + Integer.toHexString(a.color()) + "," + Integer.toHexString(a.bgColor()));
            drawn.add(el);
            poseY.add(r.pose().f());
            Matrix2D m = r.pose();
            double x0 = el.x(), y0 = el.y(), x1 = x0 + el.width(), y1 = y0 + el.height();
            placed.add(el.id() + " " + el.type() + " " + el.width() + "x" + el.height() + " text=" + el.text()
                    + " color=" + Integer.toHexString(el.color()) + "/" + Integer.toHexString(el.bgColor())
                    + " cell=" + el.opacity() + "," + el.translateX() + "," + el.translateY() + "," + el.scaleX() + "," + el.scaleY() + "," + el.rotate()
                    + " trans=" + el.transitionDuration() + " anim=" + canon(el.animDef()) + " events=" + el.events() + " fit=" + el.fitMode()
                    + " flat=" + flat + " op=" + r.opacity() + " [" + layers + "] "
                    + (m.a() * x0 + m.c() * y0 + m.e()) + "," + (m.b() * x0 + m.d() * y0 + m.f()) + " "
                    + (m.a() * x1 + m.c() * y1 + m.e()) + "," + (m.b() * x1 + m.d() * y1 + m.f()));
        }

        private static String matrix(Matrix2D m) {
            return m == null ? "-" : m.a() + "," + m.b() + "," + m.c() + "," + m.d() + "," + m.e() + "," + m.f();
        }
    }

    /** CPU で実際に描きつつ、描いた列・平行移動・時刻を覚える描画器。 */
    private static final class Remembering implements RenderBackend {
        final SoftwareRenderBackend real = new SoftwareRenderBackend(RealDocumentFramesTest.W, RealDocumentFramesTest.H);
        List<DisplayItem> items = List.of();
        PaintOffsets offsets = PaintOffsets.EMPTY;
        long now;
        /** ビューが前のフレームの列をそのまま渡した（スクロールだけの変化。移動は列を作った時点のスクロールからの差で渡る）。 */
        boolean reused;

        @Override public void render(List<DisplayItem> items, long nowMs) { render(items, PaintOffsets.EMPTY, nowMs); }

        @Override
        public void render(List<DisplayItem> items, PaintOffsets offsets, long nowMs) {
            real.render(items, offsets, nowMs);
            this.reused = items == this.items;
            this.items = items;
            this.offsets = offsets != null ? offsets.snapshot() : PaintOffsets.EMPTY;
            this.now = nowMs;
        }

        @Override public Pixmap pixmap() { return real.pixmap(); }
        @Override public float devicePixelRatio() { return 1f; }
        @Override public String sessionId() { return real.sessionId(); }
        @Override public void close() { real.close(); }
    }

    private static void walkerMatchesDriver(String doc) throws Exception {
        try (Remembering backend = new Remembering()) {
            PaintSceneDriver driver = new PaintSceneDriver(backend.sessionId());
            SceneWalker walker = new SceneWalker(backend.sessionId());
            int[] frames = {0}, draws = {0}, exact = {0}, scrolledExact = {0};
            RealDocumentFramesTest.scriptWithView(doc, backend, false, false, (v, f) -> {
                Recorder viaDriver = new Recorder(driver::resolveAt, driver::animAt);
                driver.run(backend.items, backend.offsets, backend.now, Matrix2D.IDENTITY, viaDriver);
                Recorder viaWalker = new Recorder(walker::resolveAt, walker::animAt);
                // 走査器は利用者の平行移動だけを受け（手順には無い）、スクロールは自分で足す（M3b-4b-3）
                SceneWalkAccess.walk(v, walker, PaintOffsets.EMPTY, backend.now, Matrix2D.IDENTITY, viaWalker);
                boolean scrolled = !v.ui().dynamicState().scroll.snapshot().isEmpty();
                String calls = callMismatch(f.name(), viaDriver, viaWalker);
                if (calls == null) {
                    exact[0]++;
                    if (scrolled) scrolledExact[0]++;
                } else if (scrolled) {
                    // 列はスクロール中に作り直すと、その時点のスクロールを焼き込む（走査器はスクロール 0 で組む）。位置だけが違い、
                    // 画面の上の形と中身は一致する
                    comparePlaced(f.name(), viaDriver, viaWalker);
                } else {
                    fail(calls); // スクロールしていなければ、列と走査器は呼び出しまで一致する
                }
                frames[0]++;
                draws[0] += viaWalker.drawn.size();
            });
            assertTrue(frames[0] >= 10, doc + ": 記録したフレームが少ない（" + frames[0] + "）");
            assertTrue(exact[0] >= 4, doc + ": 呼び出しまで比べたフレームが少ない（" + exact[0] + "/" + frames[0] + "）");
            System.out.println("[walker-shadow] " + doc + " frames=" + frames[0] + " exact=" + exact[0] + " scrolledExact=" + scrolledExact[0]);
            assertTrue(draws[0] > 0, doc + ": 何も描いていない");
        }
    }

    /**
     * 高さ 0 の要素は節の錨を出さない（{@code boxHasOwnNode}）。M3b-4b-2 ではその子の項目の親の鎖が途切れ、層の範囲（一時アダプタが層を
     * 描き終えた知らせ）だけが子をフィルターの層に入れていた。M3b-4b-3 で錨を出さない箱を親の鎖と座標の原点から素通しにした
     * （{@code ZeroSizeAncestorTest}）ので、親の鎖でも層に入る。走査器と {@link PaintSceneDriver} の一致と、子が層に入ることを固定する。
     */
    @Test
    void filterLayerCoversItemsWhoseAnchorIsNotDrawn() {
        String html = "<style>body{margin:0}</style><div id=f style='filter:blur(1px);width:200px;height:60px'>"
                + "<div id=z style='height:0'><div id=c style='width:40px;height:20px;background:#c33'></div></div></div>";
        try (Remembering backend = new Remembering();
             com.nishiyu.enderframelib.common.StandaloneView v = new com.nishiyu.enderframelib.common.StandaloneView(
                     html, 300, 100, com.nishiyu.enderframelib.core.text.model.FontResolver.FALLBACK)) {
            v.frame(1000L, backend, true);
            PaintSceneDriver driver = new PaintSceneDriver(backend.sessionId());
            SceneWalker walker = new SceneWalker(backend.sessionId());
            Recorder viaDriver = new Recorder(driver::resolveAt, driver::animAt);
            driver.run(backend.items, backend.offsets, backend.now, Matrix2D.IDENTITY, viaDriver);
            Recorder viaWalker = new Recorder(walker::resolveAt, walker::animAt);
            SceneWalkAccess.walk(v, walker, PaintOffsets.EMPTY, backend.now, Matrix2D.IDENTITY, viaWalker);
            compare("filter-zero-height", viaDriver, viaWalker);
            boolean childInLayer = false;
            for (String p : viaWalker.placed) childInLayer |= p.startsWith(childBg(backend.items)) && !p.contains(" [] ");
            assertTrue(childInLayer, "高さ 0 の要素の子の背景がフィルターの層に入っていない: " + viaWalker.placed);
        }
    }

    /**
     * 走査器はスクロール量を項目に焼き込まない（M3b-4b-3）: 中身を 1 つの節にまとめるスクロールコンテナをスクロールしても、走査器が組む項目は
     * 同じで、中身の項目の姿勢だけがスクロール量だけ動く（つまみは縦に動く）。焼き込む・平行移動の向きを取り違える変異で落ちる。
     */
    @Test
    void walkerItemsDoNotDependOnTheScrollOfOneNodeContainers() {
        StringBuilder html = new StringBuilder("<style>body{margin:0}.r{height:40px}</style><div id=s style='overflow:auto;width:200px;height:100px'>");
        for (int i = 0; i < 10; i++) html.append("<div class=r style='background:#").append(i % 2 == 0 ? "c33" : "3c3").append("'>").append(i).append("</div>");
        html.append("</div>");
        try (Remembering backend = new Remembering();
             com.nishiyu.enderframelib.common.StandaloneView v = new com.nishiyu.enderframelib.common.StandaloneView(
                     html.toString(), 300, 200, com.nishiyu.enderframelib.core.text.model.FontResolver.FALLBACK)) {
            SceneWalker walker = new SceneWalker(backend.sessionId());
            v.frame(1000L, backend, true);
            Recorder top = new Recorder(walker::resolveAt, walker::animAt);
            SceneWalkAccess.walk(v, walker, PaintOffsets.EMPTY, 1000L, Matrix2D.IDENTITY, top);
            v.mouseMove(50, 50, backend.sessionId());
            v.scroll(0, -2, backend.sessionId());
            v.frame(1500L, backend, true);
            float dy = v.ui().dynamicState().scroll.snapshot().values().stream().findFirst().map(xy -> xy[1]).orElse(0f);
            assertTrue(dy > 0f, "前提: 中身がスクロールした");
            Recorder scrolled = new Recorder(walker::resolveAt, walker::animAt);
            SceneWalkAccess.walk(v, walker, PaintOffsets.EMPTY, 1500L, Matrix2D.IDENTITY, scrolled);
            assertEquals(top.drawn.size(), scrolled.drawn.size());
            int moved = 0;
            for (int i = 0; i < top.drawn.size(); i++) {
                DisplayItem a = top.drawn.get(i), b = scrolled.drawn.get(i);
                assertEquals(canon(a), canon(b), "スクロールで走査器の項目が変わった（スクロール量を焼き込んだ）: " + a.id());
                double d = scrolled.poseY.get(i) - top.poseY.get(i);
                if (a.type() == com.nishiyu.enderframelib.core.paint.model.ElementType.TEXT || a.bgColor() == 0xFFCC3333 || a.bgColor() == 0xFF33CC33) {
                    assertEquals(-dy, d, 1e-3, "中身の項目がスクロール量だけ動かない: " + a.id());
                    moved++;
                }
            }
            assertTrue(moved >= 10, "中身の項目が少ない: " + moved);
        }
    }

    /** 文書の {@code #c} の背景の項目の ID（{@code placed} の先頭）。 */
    private static String childBg(List<DisplayItem> items) {
        for (DisplayItem it : items) if (it.bgColor() == 0xFFCC3333 && it.type() != com.nishiyu.enderframelib.core.paint.model.ElementType.NODE) return it.id() + " ";
        throw new AssertionError("前提: #c の背景の項目が無い");
    }

    private static void comparePlaced(String frame, Recorder want, Recorder got) {
        int n = Math.min(want.placed.size(), got.placed.size());
        for (int i = 0; i < n; i++) {
            if (!want.placed.get(i).equals(got.placed.get(i))) {
                fail(frame + "（スクロール中）: 描いた項目 " + i + " の画面の上の形が違う\n  driver: " + want.placed.get(i)
                        + "\n  walker: " + got.placed.get(i));
            }
        }
        assertEquals(want.placed.size(), got.placed.size(), frame + "（スクロール中）: 描いた項目の数が違う");
    }

    private static void compare(String frame, Recorder want, Recorder got) {
        String m = callMismatch(frame, want, got);
        if (m != null) fail(m);
    }

    /** 呼び出し（と描いた項目の中身）が一致すれば null、違えば最初の違いの説明。 */
    private static String callMismatch(String frame, Recorder want, Recorder got) {
        int n = Math.min(want.calls.size(), got.calls.size());
        for (int i = 0; i < n; i++) {
            if (!want.calls.get(i).equals(got.calls.get(i))) {
                return frame + ": 呼び出し " + i + " が違う\n  driver: " + want.calls.get(i) + "\n  walker: " + got.calls.get(i);
            }
        }
        if (want.calls.size() != got.calls.size()) {
            return frame + ": 呼び出しの数が違う（driver " + want.calls.size() + " / walker " + got.calls.size() + "）"
                    + (want.calls.size() > n ? "\n  driver の次: " + want.calls.get(n) : "\n  walker の次: " + got.calls.get(n));
        }
        for (int i = 0; i < want.drawn.size(); i++) {
            DisplayItem a = want.drawn.get(i), b = got.drawn.get(i);
            if (!a.equals(b) && !canon(a).equals(canon(b))) {
                return frame + ": 描いた項目 " + i + " の中身が違う\n  driver: " + canon(a) + "\n  walker: " + canon(b);
            }
        }
        return null;
    }

    /** 値の比べられる文字列（記録の部品を辿り、配列は中身で書く。配列を持つ記録の {@code toString} は実体の番号になるため）。 */
    static String canon(Object o) {
        if (o == null) return "null";
        Class<?> c = o.getClass();
        if (c.isArray()) {
            StringBuilder b = new StringBuilder("[");
            for (int i = 0, n = java.lang.reflect.Array.getLength(o); i < n; i++) {
                if (i > 0) b.append(',');
                b.append(canon(java.lang.reflect.Array.get(o, i)));
            }
            return b.append(']').toString();
        }
        if (o instanceof List<?> l) {
            StringBuilder b = new StringBuilder("(");
            for (Object e : l) b.append(canon(e)).append(';');
            return b.append(')').toString();
        }
        if (c.isRecord()) {
            StringBuilder b = new StringBuilder(c.getSimpleName()).append('{');
            for (var rc : c.getRecordComponents()) {
                try {
                    b.append(rc.getName()).append('=').append(canon(rc.getAccessor().invoke(o))).append(' ');
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
            return b.append('}').toString();
        }
        return String.valueOf(o);
    }
}
