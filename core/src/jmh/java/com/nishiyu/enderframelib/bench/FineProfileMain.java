package com.nishiyu.enderframelib.bench;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.common.UiView;
import com.nishiyu.enderframelib.core.diag.StageProfiler;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.HtmlText;
import com.nishiyu.enderframelib.core.paint.RenderBackend;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.text.model.FontResolver;

import java.util.Arrays;
import java.util.List;

/**
 * 実フレームの経路（{@link StandaloneView#frame}）を、シナリオ別に回して、フレーム全体の分布・段別の時間・確保量を出す。
 *
 * <pre>
 * ./gradlew :core:profile -Pprofile.main=com.nishiyu.enderframelib.bench.FineProfileMain \
 *     -Pprofile.args="&lt;scenario&gt; &lt;doc&gt; &lt;frames&gt; &lt;null|cpu&gt;" [-Pprofile.jfr=out.jfr]
 * </pre>
 * シナリオ: idle / hover / scroll / text / style（1 要素の style 属性）/ inherited（body の継承プロパティ）/
 * child（子の挿入と削除）/ relayout（全体の再レイアウト要求）/ resize / open（文書を開く: 解析 + 初回フレーム）。
 * バックエンド: {@code null} は描画しない（パイプラインだけ）、{@code cpu} は CPU ラスタまで含める。
 */
public final class FineProfileMain {
    private FineProfileMain() { }

    /** 描かないバックエンド（パイプラインのコストだけを見る）。 */
    private static final class NullBackend implements RenderBackend {
        @Override public void render(List<DisplayItem> items, long nowMs) { }
        @Override public Pixmap pixmap() { throw new UnsupportedOperationException(); }
        @Override public float devicePixelRatio() { return 1f; }
    }

    private static long D_STAT(Class<?> c, String name) {
        try {
            return ((java.util.concurrent.atomic.LongAdder) c.getField(name).get(null)).sum();
        } catch (ReflectiveOperationException e) {
            return -1;
        }
    }

    public static void main(String[] args) {
        String scenario = args.length > 0 ? args[0] : "idle";
        String doc = args.length > 1 ? args[1] : Workloads.SHOWCASE;
        int frames = args.length > 2 ? Integer.parseInt(args[2]) : 2000;
        String backendName = args.length > 3 ? args[3] : "null";
        boolean cpu = backendName.equals("cpu");
        int warmup = scenario.equals("open") ? 300 : 600;
        String html = Workloads.html(doc);
        if ((scenario.equals("hover") || scenario.equals("scrollhover")) && Workloads.isList(doc)) html = html.replace("</style>", " li:hover { background: #eef; }</style>");

        try (RenderBackend raw = switch (backendName) {
            case "cpu" -> new SoftwareRenderBackend(Workloads.VIEWPORT_W, Workloads.VIEWPORT_H, 1f);
            case "gl" -> new com.nishiyu.enderframelib.core.paint.gl.LwjglGlRenderBackend(Workloads.VIEWPORT_W, Workloads.VIEWPORT_H);
            default -> new NullBackend();
        }) {
            // 描画バックエンド呼び出し（CPU 側の描画・GL 送出・索引走査を含む）を PAINT 段として計るため包む。種別判定は raw で行う
            RenderBackend backend = new com.nishiyu.enderframelib.core.paint.ProfilingRenderBackend(raw);
            StandaloneView[] view = {newView(html)};
            long[] now = {1000};
            view[0].frame(now[0], backend, true);
            Target t = Target.find(view[0], doc);

            com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
            long tid = Thread.currentThread().getId();
            double[] ms = new double[frames];
            long allocTotal = 0;
            int rendered = 0;

            for (int i = -warmup; i < frames; i++) {
                if (i == 0) {
                    StageProfiler.reset();
                    StageProfiler.trackAllocations(false);
                    StageProfiler.enable(true);
                }
                long a0 = mx.getThreadAllocatedBytes(tid);
                long t0 = System.nanoTime();
                boolean drew = step(scenario, view, backend, t, now, i, html);
                long t1 = System.nanoTime();
                if (i >= 0) {
                    ms[i] = (t1 - t0) / 1e6;
                    allocTotal += mx.getThreadAllocatedBytes(tid) - a0;
                    if (drew) rendered++;
                }
            }
            StageProfiler.enable(false);

            double[] sorted = ms.clone();
            Arrays.sort(sorted);
            double sum = 0;
            for (double v : ms) sum += v;
            System.out.printf("RESULT scenario=%s doc=%s backend=%s frames=%d drawn=%d mean=%.4f p50=%.4f p95=%.4f p99=%.4f max=%.4f ms alloc=%.1f KB/frame%n",
                    scenario, doc, backendName, frames, rendered, sum / frames, sorted[frames / 2],
                    sorted[(int) (frames * 0.95)], sorted[(int) (frames * 0.99)], sorted[frames - 1], allocTotal / 1024.0 / frames);
            {
                // Chrome 側（bench.html の bench()）と同じ統計: 200 フレーム平均を 15 組取った中央値（GC・JIT の外れ値は組の平均に埋もれて除かれる）
                int n = 200, groups = Math.min(15, frames / n);
                if (groups >= 3) {
                    double[] g = new double[groups];
                    for (int k = 0; k < groups; k++) {
                        double s = 0;
                        for (int j = 0; j < n; j++) s += ms[k * n + j];
                        g[k] = s / n;
                    }
                    Arrays.sort(g);
                    System.out.printf("CHROMESTAT median-of-%d-batch-means(200)=%.4f min=%.4f max=%.4f ms%n", groups, g[groups / 2], g[0], g[groups - 1]);
                }
            }
            System.out.println("DOM elements=" + countElements(view[0].root()));
            System.out.println("ITEMS " + view[0].ui().renderCache().size() + " textPatch hit/fallback=" + UiView.TEXT_PATCH_HITS.get() + "/" + UiView.TEXT_PATCH_FALLBACKS.get() + " childPatch hit/fallback=" + UiView.CHILD_PATCH_HITS.get() + "/" + UiView.CHILD_PATCH_FALLBACKS.get());
            if (raw instanceof com.nishiyu.enderframelib.core.paint.gl.LwjglGlRenderBackend gl) System.out.println("GL shapes=" + gl.rectTreeShapes() + " glyphs=" + gl.gpuGlyphCount() + " rejects(pose,grid,corner,capacity/opacity,clip)=" + java.util.Arrays.toString(gl.rectTreeRejects()) + " drawCalls=" + gl.rectTreeDrawCalls() + " replayed items/shapes per frame=" + String.format("%.1f/%.1f", gl.rectTreeReplayedItems() / (double) (frames + warmup), gl.rectTreeReplayedShapes() / (double) (frames + warmup)) + " changedWrites/frame=" + String.format("%.1f", gl.rectTreeChangedWrites() / (double) (frames + warmup)) + " per-frame frames=" + (frames + warmup));
            {
                long n = frames + warmup;
                // 配置の近道の効き具合（プロセス累計 / フレーム）: shifted = 部分木の平行移動、skipped = 入力が同じで飛ばした
                System.out.printf("LAYOUTSKIP per-frame shifted=%.1f skipped=%.1f%n",
                        com.nishiyu.enderframelib.core.layout.LayoutBox.layoutsShifted() / (double) n,
                        com.nishiyu.enderframelib.core.layout.LayoutBox.layoutsSkipped() / (double) n);
            }
            {
                var D = com.nishiyu.enderframelib.core.paint.DisplayListBuilder.class;
                long n = frames + warmup;
                System.out.printf("BOXMEMO per-frame exact=%.1f translated=%.1f miss=%.1f new=%.1f unmemoizable=%.1f stamped=%.1f%n",
                        D_STAT(D, "STAT_EXACT") / (double) n, D_STAT(D, "STAT_TRANSLATED") / (double) n, D_STAT(D, "STAT_MISS") / (double) n,
                        D_STAT(D, "STAT_NEW") / (double) n, D_STAT(D, "STAT_UNMEMOIZABLE") / (double) n, D_STAT(D, "STAT_STAMPED") / (double) n);
            }
            {
                StringBuilder why = new StringBuilder("MATCHFAIL");
                var fails = com.nishiyu.enderframelib.core.paint.DisplayListBuilder.STAT_MATCH_FAIL;
                for (int i = 1; i < fails.length; i++) if (fails[i].sum() > 0) why.append(' ').append(i).append('=').append(String.format("%.1f", fails[i].sum() / (double) (frames + warmup)));
                System.out.println(why);
            }
            System.out.println("RANGECULL planned=" + com.nishiyu.enderframelib.core.paint.PaintSceneDriver.RANGE_PLANNED.get()
                    + " ineligible=" + com.nishiyu.enderframelib.core.paint.PaintSceneDriver.RANGE_INELIGIBLE.get()
                    + " culledItems=" + com.nishiyu.enderframelib.core.paint.PaintSceneDriver.RANGE_CULLED_TOTAL.get());
            if (raw instanceof SoftwareRenderBackend sw) {
                try {
                    java.lang.reflect.Method p = SoftwareRenderBackend.class.getDeclaredMethod("partialFrames");
                    java.lang.reflect.Method f = SoftwareRenderBackend.class.getDeclaredMethod("fullFrames");
                    java.lang.reflect.Method a = SoftwareRenderBackend.class.getDeclaredMethod("damageAborts");
                    java.lang.reflect.Method s = SoftwareRenderBackend.class.getDeclaredMethod("skippedItems");
                    p.setAccessible(true); f.setAccessible(true); a.setAccessible(true); s.setAccessible(true);
                    java.lang.reflect.Method w = SoftwareRenderBackend.class.getDeclaredMethod("fullReasons");
                    w.setAccessible(true);
                    System.out.println("DAMAGE-WHY " + java.util.Arrays.toString((long[]) w.invoke(sw)));
                    System.out.println("DAMAGE partial=" + p.invoke(sw) + " full=" + f.invoke(sw) + " aborts=" + a.invoke(sw) + " skipped=" + s.invoke(sw));
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
            System.out.print(StageProfiler.report());
            double stageTotal = 0;
            for (StageProfiler.StageStats st : StageProfiler.snapshot()) {
                // GPU 段は数フレーム遅れて届くタイマークエリで CPU フレーム時間と重複するため合算しない
                if (st.stage() != com.nishiyu.enderframelib.core.diag.PipelineStage.GPU) stageTotal += st.totalMs();
            }
            double meanMs = sum / frames;
            double accounted = stageTotal / frames;
            System.out.printf("ACCOUNTED stages=%.4f ms/frame frame=%.4f ms explained=%.1f%% unaccounted=%.4f ms%n",
                    accounted, meanMs, 100.0 * accounted / meanMs, meanMs - accounted);
            System.out.println("PATCH slice(style/text) hit=" + com.nishiyu.enderframelib.core.paint.DisplayListBuilder.STAT_SLICE_PATCH_HITS.sum()
                    + " child-slice hit=" + com.nishiyu.enderframelib.core.paint.DisplayListBuilder.STAT_CHILD_SLICE_PATCH_HITS.sum()
                    + " slice-fallback=" + com.nishiyu.enderframelib.core.paint.DisplayListBuilder.STAT_SLICE_PATCH_FALLBACKS.sum()
                    + " | restyle: structural=" + UiView.RESTYLE_STRUCTURAL.get()
                    + " relayoutFull=" + UiView.RESTYLE_RELAYOUT_FULL.get()
                    + " relayoutInplace=" + UiView.RESTYLE_RELAYOUT_INPLACE.get()
                    + " reposition=" + UiView.RESTYLE_REPOSITION.get()
                    + " repaintPatch=" + UiView.RESTYLE_REPAINT_PATCH.get()
                    + " repaintFull=" + UiView.RESTYLE_REPAINT_FULL.get()
                    + " (ウォームアップ込み累計)");
            StringBuilder cant = new StringBuilder("CANTPATCH");
            for (int w = 0; w < 2; w++) {
                cant.append(w == 0 ? " boxes[" : " child[");
                for (int k = 0; k < 16; k++) {
                    long c = com.nishiyu.enderframelib.core.paint.DisplayListBuilder.STAT_CANT_PATCH[w][k].sum();
                    if (c > 0) cant.append(k).append('=').append(c).append(' ');
                }
                cant.append(']');
            }
            cant.append(" noBuilder=").append(UiView.SLICE_NO_BUILDER.get()).append(" multiPlan=").append(UiView.SLICE_MULTI_PLAN.get());

            System.out.println(cant);
        }
    }

    private static int countElements(HtmlNode n) {
        int c = n instanceof HtmlElement ? 1 : 0;
        for (HtmlNode ch : n.children) c += countElements(ch);
        return c;
    }

    private static StandaloneView newView(String html) {
        return new StandaloneView(html, Workloads.VIEWPORT_W, Workloads.VIEWPORT_H, FontResolver.FALLBACK);
    }

    /** 1 フレームぶんの変化を起こして、1 フレーム進める。描いたら true。 */
    private static boolean step(String scenario, StandaloneView[] view, RenderBackend backend, Target t, long[] now, int i,
                                String html) {
        StandaloneView v = view[0];
        switch (scenario) {
            case "idle" -> { }
            case "hover" -> {
                v.ui().setHoveredElement(t.hoverIds[i & 1]);
            }
            case "scrollhover" -> {
                // スクロールを 1 度起こして保持層を作らせたあと、画面外の項目の :hover を往復する（記録フレームの走査コスト）
                if (i == -600) v.mouseMove(t.scrollPoint[0], t.scrollPoint[1], backend.sessionId());
                if (i == -599 || i == -598) v.scroll(0, -1, backend.sessionId()); // 負が下方向（先頭では上へは動かない）
                if (i >= -597) v.ui().setHoveredElement(t.hoverIds[i & 1]);
            }
            case "scroll" -> {
                if (i == -600) v.mouseMove(t.scrollPoint[0], t.scrollPoint[1], backend.sessionId());
                v.scroll(0, (i & 1) == 0 ? 1 : -1, backend.sessionId());
            }
            case "textbtn" -> t.buttonText.setData((i & 1) == 0 ? "Buy now A" : "Buy now B");
            case "text" -> t.textTarget.setData((i & 1) == 0 ? "Row item A with text " + (i & 7) : "Row item B with text " + (i & 7));
            case "style" -> ((HtmlElement) t.styleTarget).setAttribute("style", (i & 1) == 0 ? "color:#112233" : "color:#223344");
            case "inherited" -> ((HtmlElement) t.bodyTarget).setAttribute("style", (i & 1) == 0 ? "color:#112233" : "color:#223344");
            case "fontsize" -> ((HtmlElement) t.bodyTarget).setAttribute("style", (i & 1) == 0 ? "font-size:16px" : "font-size:15px");
            case "child" -> {
                if ((i & 1) == 0) {
                    HtmlNode added = new HtmlElement("li");
                    added.updateTextContent("inserted " + i);
                    t.parent.insertBefore(added, t.anchor);
                    t.added = added;
                } else if (t.added != null) {
                    t.parent.removeChild(t.added);
                    t.added = null;
                }
            }
            // 切り分け用: child と同じだが、挿入する行の高さを整数（20px + 下枠 1px）に固定する。後続の行の移動量が整数になり、
            // 平行移動の近道（整数の移動だけ許す）が効くかどうかで、child の place の原因が「小数の移動量」かを確かめる
            case "childfixed" -> {
                if ((i & 1) == 0) {
                    HtmlElement added = new HtmlElement("li");
                    added.setAttribute("style", "height:20px;overflow:hidden");
                    added.updateTextContent("inserted " + i);
                    t.parent.insertBefore(added, t.anchor);
                    t.added = added;
                } else if (t.added != null) {
                    t.parent.removeChild(t.added);
                    t.added = null;
                }
            }
            case "relayout" -> v.ui().markInvalidation(UiView.InvalidationLevel.RELAYOUT);
            case "resize" -> v.ui().recomputeLayout(Workloads.VIEWPORT_W - (i & 1), Workloads.VIEWPORT_H);
            // 切り分け用: 幅を 2px ずつ変える。中央寄せの箱の移動量が 0.5px → 1px（整数）になり、表示項目の平行移動の再利用が効くかを確かめる
            case "resize2" -> v.ui().recomputeLayout(Workloads.VIEWPORT_W - 2 * (i & 1), Workloads.VIEWPORT_H);
            // ホストが実際に通る経路（StandaloneView.resize → requestResize → flushInvalidation の resize 分岐）。
            // recomputeLayout の直呼びと違い、表示項目の厳密な平行移動（exactTranslation）が有効になる
            case "resizeframe" -> v.resize(Workloads.VIEWPORT_W - (i & 1), Workloads.VIEWPORT_H);
            // flushInvalidation の resize 分岐と同じく exactTranslation を立てて recomputeLayout する（resizeframe は壁時計の間引きで
            // 連続フレームではレイアウトが走らないため、その分岐の中身だけを直接再現する）
            case "resizeexact" -> {
                try {
                    var f = UiView.class.getDeclaredField("exactTranslation");
                    f.setAccessible(true);
                    f.setBoolean(v.ui(), true);
                    try {
                        v.ui().recomputeLayout(Workloads.VIEWPORT_W - (i & 1), Workloads.VIEWPORT_H);
                    } finally {
                        f.setBoolean(v.ui(), false);
                    }
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
            case "open" -> {
                view[0] = newView(html);
                return view[0].frame(now[0] += 16, backend, true);
            }
            default -> throw new IllegalArgumentException("未知のシナリオ: " + scenario);
        }
        return v.frame(now[0] += 16, backend, false);
    }

    /** シナリオが触る要素。 */
    private static final class Target {
        HtmlText textTarget, buttonText;
        HtmlNode styleTarget, bodyTarget, parent, anchor, added;
        double[][] hover = {{10, 10}, {20, 20}};
        double[] scrollPoint = {50, 50};
        String[] hoverIds = {"", ""};

        static Target find(StandaloneView v, String doc) {
            Target t = new Target();
            boolean list = Workloads.isList(doc);
            HtmlNode row = list ? v.root().getNodeById("item-250") : null;
            if (list) {
                t.styleTarget = v.root().getNodeById("item-100");
                t.parent = row.parent;
                t.anchor = row;
                if (row.children.get(0) instanceof HtmlText tx) t.textTarget = tx;
            } else {
                HtmlNode button = firstOf(v.root(), "button");
                t.styleTarget = button;
                t.parent = button.parent;
                t.anchor = button;
                HtmlNode body = firstOf(v.root(), "body");
                t.textTarget = firstTextUnder(body, false);
                t.buttonText = firstTextUnder(button, true);
            }
            t.bodyTarget = firstOf(v.root(), "body");
            if (list) {
                t.hoverIds = new String[]{v.root().getNodeById("item-100").key(), v.root().getNodeById("item-101").key()};
            } else {
                HtmlNode b2 = secondOf(v.root(), "button");
                t.hoverIds = new String[]{t.styleTarget.key(), (b2 != null ? b2 : t.styleTarget).key()};
            }
            // ホバーの往復: :hover の規則が掛かる（interactiveState の）要素を 2 つ探して、その中心を行き来する
            int found = 0;
            for (DisplayItem it : v.ui().renderCache()) {
                if (it.meta() != null && it.meta().interactiveState() && it.width() > 4 && it.height() > 4 && found < 2) {
                    t.hover[found++] = new double[]{it.x() + it.width() / 2.0, it.y() + it.height() / 2.0};
                }
            }
            if (found < 2) t.hover = new double[][]{{40, 40}, {300, 300}};
            return t;
        }

        private static HtmlNode firstOf(HtmlNode n, String tag) {
            if (tag.equals(n.tag)) return n;
            for (HtmlNode c : n.children) {
                HtmlNode r = firstOf(c, tag);
                if (r != null) return r;
            }
            return null;
        }

        private static HtmlNode secondOf(HtmlNode n, String tag) {
            HtmlNode first = firstOf(n, tag);
            return first == null ? null : after(n, tag, first, new boolean[]{false});
        }

        private static HtmlNode after(HtmlNode n, String tag, HtmlNode first, boolean[] seen) {
            if (n == first) seen[0] = true;
            else if (seen[0] && tag.equals(n.tag)) return n;
            for (HtmlNode c : n.children) {
                HtmlNode r = after(c, tag, first, seen);
                if (r != null) return r;
            }
            return null;
        }

        /** body の下で最初の、空白だけでない文字。{@code insideButtonOnly} なら button の中の文字、でなければ button / label の外の文字。 */
        private static HtmlText firstTextUnder(HtmlNode n, boolean insideButtonOnly) {
            for (HtmlNode c : n.children) {
                if (c instanceof HtmlText tx && !tx.getData().isBlank()) {
                    boolean btn = n.tag != null && (n.tag.equals("button") || n.tag.equals("label"));
                    if (insideButtonOnly == btn || (insideButtonOnly && n.tag != null && n.tag.equals("button"))) return tx;
                    continue;
                }
                HtmlText r = firstTextUnder(c, insideButtonOnly);
                if (r != null) return r;
            }
            return null;
        }

        private static HtmlText firstText(HtmlNode n) {
            for (HtmlNode c : n.children) {
                if (c instanceof HtmlText tx && !tx.getData().isBlank()) return tx;
                HtmlText r = firstText(c);
                if (r != null) return r;
            }
            return null;
        }
    }
}
