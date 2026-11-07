package com.nishiyu.enderframelib.bench;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.common.UiView;
import com.nishiyu.enderframelib.core.diag.StageProfiler;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.HtmlText;
import com.nishiyu.enderframelib.core.paint.ProfilingRenderBackend;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;

/**
 * 実フレームの経路（{@link StandaloneView#frame}）を回して、段ごとの内訳を表にする。
 * JMH が「ある段を孤立させて」測るのに対し、こちらは「本番の経路でどの段がいくらか」を見るためのもの。
 *
 * <pre>
 * ./gradlew :core:profile -Pprofile.args="showcase 400 --alloc"
 * ./gradlew :core:profile -Pprofile.args="large-list 400 --mode=text"
 * ./gradlew :core:profile -Pprofile.args="showcase 200 --mode=relayout" -Pprofile.jfr=build/profile.jfr
 * </pre>
 * 引数: {@code <文書> [フレーム数] [--alloc] [--mode=idle|text|restyle|relayout]}。毎フレーム、次を起こしてから 1 フレーム進める:
 * <ul>
 *   <li>{@code idle}（既定）: 何も起こさない。静止した UI の定常コスト</li>
 *   <li>{@code text}: 1 つの文字ノードの中身を変える（局所更新の経路）</li>
 *   <li>{@code restyle}: 全体の再スタイルを要求する</li>
 *   <li>{@code relayout}: 全体の再レイアウトを要求する</li>
 * </ul>
 */
public final class PipelineProfileMain {

    private PipelineProfileMain() { }

    public static void main(String[] args) {
        String doc = args.length > 0 ? args[0] : Workloads.SHOWCASE;
        int frames = args.length > 1 && !args[1].startsWith("--") ? Integer.parseInt(args[1]) : 300;
        boolean alloc = false;
        String mode = "idle";
        for (String a : args) {
            if (a.equals("--alloc")) alloc = true;
            if (a.startsWith("--mode=")) mode = a.substring("--mode=".length());
        }
        if (!java.util.Set.of("idle", "text", "restyle", "relayout").contains(mode)) {
            throw new IllegalArgumentException("未知の --mode: " + mode);
        }
        int warmup = Math.max(10, Math.min(120, frames / 2));

        StandaloneView view = new StandaloneView(Workloads.html(doc), Workloads.VIEWPORT_W, Workloads.VIEWPORT_H);
        HtmlText target = mode.equals("text") ? findText(view, doc) : null;
        if (mode.equals("text") && target == null) {
            throw new IllegalStateException("変更対象の文字ノードが見つからない: " + doc);
        }
        UiView.InvalidationLevel level = switch (mode) {
            case "restyle" -> UiView.InvalidationLevel.RESTYLE;
            case "relayout" -> UiView.InvalidationLevel.RELAYOUT;
            default -> null;
        };
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(Workloads.VIEWPORT_W, Workloads.VIEWPORT_H, 1f);
             ProfilingRenderBackend backend = new ProfilingRenderBackend(cpu)) {
            long now = 1000;
            view.frame(now, backend, true);
            for (int i = 0; i < warmup; i++) {
                disturb(view, target, level, "warm " + i);
                view.frame(now += 16, backend, false);
            }
            StageProfiler.reset();
            StageProfiler.trackAllocations(alloc);
            StageProfiler.enable(true);
            int rendered = 0;
            long t0 = System.nanoTime();
            for (int i = 0; i < frames; i++) {
                disturb(view, target, level, "frame " + i);
                if (view.frame(now += 16, backend, false)) rendered++;
            }
            double wallMs = (System.nanoTime() - t0) / 1e6;
            StageProfiler.enable(false);
            System.out.printf("document=%s mode=%s frames=%d rendered=%d wall=%.1fms (%.3f ms/frame)%n",
                    doc, mode, frames, rendered, wallMs, wallMs / frames);
            System.out.print(StageProfiler.report());
        }
    }

    private static void disturb(StandaloneView view, HtmlText target, UiView.InvalidationLevel level, String text) {
        if (target != null) target.setData(text);
        if (level != null) view.ui().markInvalidation(level);
    }

    private static HtmlText findText(StandaloneView view, String doc) {
        HtmlNode el = doc.equals(Workloads.LARGE_LIST) ? view.root().getElementById("item-250") : view.root().querySelector("p");
        if (el == null) {
            for (String sel : new String[]{"td", "label", "button", "li", "span", "b", "div"}) {
                for (var c : view.root().querySelectorAll(sel)) {
                    if (c instanceof HtmlNode hn && !hn.children.isEmpty() && hn.children.get(0) instanceof HtmlText t0 && !t0.getData().isBlank()) return t0;
                }
            }
        }
        if (el == null || el.children.isEmpty() || !(el.children.get(0) instanceof HtmlText t)) return null;
        return t;
    }
}
