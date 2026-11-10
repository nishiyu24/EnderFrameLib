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

    public static void main(String[] args) {
        String scenario = args.length > 0 ? args[0] : "idle";
        String doc = args.length > 1 ? args[1] : Workloads.SHOWCASE;
        int frames = args.length > 2 ? Integer.parseInt(args[2]) : 2000;
        String backendName = args.length > 3 ? args[3] : "null";
        boolean cpu = backendName.equals("cpu");
        int warmup = scenario.equals("open") ? 300 : 600;
        String html = Workloads.html(doc);
        if (scenario.equals("hover") && Workloads.isList(doc)) html = html.replace("</style>", " li:hover { background: #eef; }</style>");

        try (RenderBackend backend = switch (backendName) {
            case "cpu" -> new SoftwareRenderBackend(Workloads.VIEWPORT_W, Workloads.VIEWPORT_H, 1f);
            case "gl" -> new com.nishiyu.enderframelib.core.paint.gl.LwjglGlRenderBackend(Workloads.VIEWPORT_W, Workloads.VIEWPORT_H);
            default -> new NullBackend();
        }) {
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
            System.out.println("DOM elements=" + countElements(view[0].root()));
            System.out.println("ITEMS " + view[0].ui().renderCache().size() + " textPatch hit/fallback=" + UiView.TEXT_PATCH_HITS.get() + "/" + UiView.TEXT_PATCH_FALLBACKS.get() + " childPatch hit/fallback=" + UiView.CHILD_PATCH_HITS.get() + "/" + UiView.CHILD_PATCH_FALLBACKS.get());
            if (backend instanceof com.nishiyu.enderframelib.core.paint.gl.LwjglGlRenderBackend gl) System.out.println("GL shapes=" + gl.rectTreeShapes() + " glyphs=" + gl.gpuGlyphCount() + " rejects(pose,grid,corner,capacity/opacity,clip)=" + java.util.Arrays.toString(gl.rectTreeRejects()) + " drawCalls=" + gl.rectTreeDrawCalls() + " per-frame frames=" + (frames + warmup));
            System.out.print(StageProfiler.report());
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
            case "relayout" -> v.ui().markInvalidation(UiView.InvalidationLevel.RELAYOUT);
            case "resize" -> v.ui().recomputeLayout(Workloads.VIEWPORT_W - (i & 1), Workloads.VIEWPORT_H);
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
