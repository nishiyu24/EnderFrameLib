package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.text.model.FontResolver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * M2a 縦割りスパイクの実測（計画書 §3.3 B）。{@code ./gradlew :core:m2aSpike}。
 *
 * <ol>
 *   <li>判定基準 1: {@code large-list-8000} / {@code 500} の先頭・中央・末尾への 1 件挿入・削除。旧経路（{@code StandaloneView}・
 *       描画なし）と v3（台帳の凍結 → {@code BoxTreeCompiler} → {@code FragmentLayout}）を<b>同じ JVM で交互に 3 回ずつ</b>回し、
 *       回ごとの中央値の中央値で比べる（絶対値はセッションで ±40% ずれるので、比は同じ回の中で取る）。</li>
 *   <li>判定基準 4: 保持ヒープを DOM / スタイル / {@code BoxNode} 木 / 断片木に分けて実測する。</li>
 * </ol>
 */
public final class M2aSpikeMain {
    private static final int W = 800, H = 600;

    public static void main(String[] args) {
        String only = System.getProperty("m2a.only", "latency,heap");
        if (only.contains("latency")) latency();
        if (only.contains("heap")) heap();
    }

    // ---- 1. 1 件挿入・削除 ----

    private static void latency() {
        System.out.println("== 判定基準 1: 1 件挿入・削除（ms/操作。交互 A/B 3 回の中央値） ==");
        for (int rows : new int[]{8000, 500}) {
            String html = SpikeView.largeList(rows);
            for (String where : new String[]{"head", "mid", "tail"}) {
                String anchor = switch (where) {
                    case "head" -> "item-1";
                    case "mid" -> "item-" + rows / 2;
                    default -> null;
                };
                double[] legacy = new double[3], v3 = new double[3], v3Compile = new double[3], v3Layout = new double[3];
                for (int round = 0; round < 3; round++) {
                    legacy[round] = legacyChild(html, anchor, 60, 200);
                    double[] r = v3Child(html, anchor, 60, 200);
                    v3[round] = r[0];
                    v3Compile[round] = r[1];
                    v3Layout[round] = r[2];
                }
                System.out.printf(Locale.ROOT, "large-list-%d %-4s legacy=%.4f v3=%.4f (compile %.4f + layout %.4f)  ratio=%.3f%n",
                        rows, where, median(legacy), median(v3), median(v3Compile), median(v3Layout), median(v3) / median(legacy));
            }
        }
    }

    /** 旧経路: DOM の変更 ＋ 1 フレーム（描画なし）。 */
    private static double legacyChild(String html, String anchorId, int warmup, int ops) {
        try (StandaloneView v = new StandaloneView(html, W, H, FontResolver.FALLBACK, com.nishiyu.enderframelib.common.EnginePath.LEGACY)) {
            SpikeView.NullBackend backend = new SpikeView.NullBackend();
            long now = 1000;
            v.frame(now, backend, true);
            HtmlNode ul = v.root().getNodeById("list");
            HtmlNode anchor = anchorId != null ? v.root().getNodeById(anchorId) : null;
            double[] ms = new double[ops];
            HtmlNode added = null;
            for (int i = -warmup; i < ops; i++) {
                long t0 = System.nanoTime();
                if ((i & 1) == 0) {
                    added = new HtmlElement("li");
                    added.updateTextContent("inserted " + i);
                    ul.insertBefore(added, anchor);
                } else {
                    ul.removeChild(added);
                }
                v.frame(now += 16, backend, false);
                long t1 = System.nanoTime();
                if (i >= 0) ms[i] = (t1 - t0) / 1e6;
            }
            return median(ms);
        }
    }

    /** v3: DOM の変更 ＋ 台帳の凍結 ＋ コンパイル ＋ 整形。{全体, コンパイル, 整形} の中央値。 */
    static double[] v3Child(String html, String anchorId, int warmup, int ops) {
        SpikeView v = new SpikeView(html, W, H);
        v.frame();
        HtmlNode ul = v.byId("list");
        HtmlNode anchor = anchorId != null ? v.byId(anchorId) : null;
        double[] all = new double[ops], comp = new double[ops], lay = new double[ops];
        HtmlNode added = null;
        for (int i = -warmup; i < ops; i++) {
            long t0 = System.nanoTime();
            if ((i & 1) == 0) {
                added = new HtmlElement("li");
                added.updateTextContent("inserted " + i);
                ul.insertBefore(added, anchor);
            } else {
                ul.removeChild(added);
            }
            long t1 = System.nanoTime();
            BoxNode root = v.compileOnly();
            long t2 = System.nanoTime();
            v.layout.layout(root, W, H);
            long t3 = System.nanoTime();
            if (i >= 0) {
                all[i] = (t3 - t0) / 1e6;
                comp[i] = (t2 - t1) / 1e6;
                lay[i] = (t3 - t2) / 1e6;
            }
        }
        return new double[]{median(all), median(comp), median(lay)};
    }

    // ---- 4. 保持ヒープ ----

    private static void heap() {
        System.out.println("== 判定基準 4: 保持ヒープ（MB）。レイアウト層増分 = BoxNode 木 + 断片木（スタイルは M1 の層として別に数える） ==");
        for (String doc : new String[]{"large-list-2000", "varied-list-2000"}) {
            double[] h = heapOf(doc.startsWith("large") ? SpikeView.largeList(2000) : SpikeView.variedList(2000));
            System.out.printf(Locale.ROOT, "%-17s DOM=%.2f  Style=%.2f  解決の副産物=%.2f  BoxNode木=%.2f  断片木=%.2f  レイアウト層増分=%.2f  全体=%.2f%n",
                    doc, h[0], h[1], h[2], h[3], h[4], h[3] + h[4], h[5]);
        }
    }

    /** {DOM, Style, スタイル解決の副産物（宣言の解析の覚えなど）, BoxNode 木, 断片木, 全体}（MB）。 */
    static double[] heapOf(String html) {
        new SpikeView(SpikeView.largeList(50), W, H).frame(); // クラスの読み込み・書体の初期化を先に済ませる
        long base = used();
        SpikeView v = new SpikeView(html, W, H);
        long dom = used();
        List<Style> styles = new ArrayList<>();
        resolveAll(v, v.doc.root, Style.DEFAULT, styles);
        long withStyles = used();
        styles = null;
        long afterDrop = used();
        v.compileOnly();
        long boxes = used();
        v.layout.layout(v.boxRoot, W, H);
        long frags = used();
        if (v.layout.root() == null) throw new IllegalStateException();
        double styleMb = mb(withStyles - afterDrop);
        return new double[]{mb(dom - base), styleMb, mb(afterDrop - dom), Math.max(0, mb(boxes - afterDrop) - styleMb), mb(frags - boxes), mb(frags - base)};
    }

    /** M1 の層だけの大きさ: 同じ解き方で全要素の {@link Style} を作って保持する（箱の木を作らない）。 */
    private static void resolveAll(SpikeView v, HtmlNode n, Style parent, List<Style> out) {
        for (HtmlNode c : n.children) {
            Style s;
            if (c.nodeType == NodeType.ELEMENT) s = v.styles.element(c, parent);
            else if (c.nodeType == NodeType.TEXT) s = v.styles.text(parent);
            else continue;
            out.add(s);
            if (c.nodeType == NodeType.ELEMENT && !"none".equals(s.box.display)) resolveAll(v, c, s, out);
        }
    }

    private static long used() {
        for (int i = 0; i < 5; i++) {
            System.gc();
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        Runtime r = Runtime.getRuntime();
        return r.totalMemory() - r.freeMemory();
    }

    private static double mb(long bytes) {
        return bytes / 1048576.0;
    }

    static double median(double[] v) {
        double[] c = v.clone();
        Arrays.sort(c);
        return c[c.length / 2];
    }

}
