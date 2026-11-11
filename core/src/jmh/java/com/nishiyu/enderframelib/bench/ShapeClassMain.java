package com.nishiyu.enderframelib.bench;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.text.model.FontResolver;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * M0（ENGINE_REARCHITECTURE_PLAN.md §8.6）: 構造クラス（算出スタイルと子の構造が同じ部分木。文字の内容は無視）の読み取り専用の計測。
 * 「クラス数 / ノード数」と、反復部分（同じ構造が 2 回以上現れる部分木）がノード全体に占める割合、
 * 同じ親の連続兄弟の中で同一構造の列がどれだけあるか、その列の高さが一様かを出す。本番の挙動は変えない。
 *
 * <pre>./gradlew :core:profile -Pprofile.main=com.nishiyu.enderframelib.bench.ShapeClassMain -Pprofile.args="showcase large-list-2000 ..."</pre>
 */
public final class ShapeClassMain {
    private ShapeClassMain() { }

    private static final class NullBackend implements com.nishiyu.enderframelib.core.paint.RenderBackend {
        @Override public void render(java.util.List<com.nishiyu.enderframelib.core.paint.model.DisplayItem> items, long nowMs) { }
        @Override public com.nishiyu.enderframelib.core.raster.Pixmap pixmap() { throw new UnsupportedOperationException(); }
        @Override public float devicePixelRatio() { return 1f; }
    }

    private static final Map<ComputedStyle, Long> STYLE_SIG = new IdentityHashMap<>();

    /** 算出スタイルの内容ハッシュ（宣言の全体）。同じ内容なら別インスタンスでも同じ値。 */
    private static long styleSig(ComputedStyle s) {
        if (s == null) return 0;
        Long c = STYLE_SIG.get(s);
        if (c != null) return c;
        long h = 1125899906842597L;
        for (Map.Entry<String, String> e : new TreeMap<>(s.asMap()).entrySet()) {
            h = 31 * h + e.getKey().hashCode();
            h = 31 * h + (e.getValue() == null ? 0 : e.getValue().hashCode());
        }
        STYLE_SIG.put(s, h);
        return h;
    }

    private static final class Stats {
        int nodes, distinctStyleInstances, distinctStyleSigs;
        final Map<Long, int[]> shapeCount = new HashMap<>();   // shape → {出現数, 部分木ノード数}
        int runs, runNodes, runRows, uniformRuns;
    }

    private static long shape(LayoutBox b, Stats st, long[] outSize) {
        long h = 17;
        h = 31 * h + (b.node != null ? String.valueOf(b.node.tag).hashCode() : 0);
        h = 31 * h + (b.node != null ? b.node.nodeType.ordinal() : -1);
        h = 31 * h + styleSig(b.style);
        long size = 1;
        long[] sz = new long[1];
        for (LayoutBox c : b.children) {
            h = 31 * h + shape(c, st, sz);
            size += sz[0];
        }
        st.nodes++;
        int[] cnt = st.shapeCount.computeIfAbsent(h, k -> new int[2]);
        cnt[0]++;
        cnt[1] = (int) size;
        outSize[0] = size;
        return h;
    }

    /** 同じ親の連続する兄弟で、同一構造が 3 回以上続く列（＝反復ブロック）を数える。 */
    private static void runs(LayoutBox b, Stats st, Map<LayoutBox, Long> shapeOf) {
        int n = b.children.size();
        int i = 0;
        while (i < n) {
            long s = shapeOf.get(b.children.get(i));
            int j = i + 1;
            while (j < n && shapeOf.get(b.children.get(j)) == s) j++;
            if (j - i >= 3) {
                st.runs++;
                st.runRows += j - i;
                boolean uniform = true;
                float h0 = b.children.get(i).h;
                long nodes = 0;
                for (int k = i; k < j; k++) {
                    if (Math.abs(b.children.get(k).h - h0) > 0.01f) uniform = false;
                    nodes += subtreeSize(b.children.get(k));
                }
                st.runNodes += (int) nodes;
                if (uniform) st.uniformRuns++;
            }
            i = j;
        }
        for (LayoutBox c : b.children) runs(c, st, shapeOf);
    }

    private static long subtreeSize(LayoutBox b) {
        long s = 1;
        for (LayoutBox c : b.children) s += subtreeSize(c);
        return s;
    }

    private static void fill(LayoutBox b, Map<LayoutBox, Long> shapeOf, Stats st) {
        long[] sz = new long[1];
        shapeOf.put(b, shape(b, st, sz));
        for (LayoutBox c : b.children) fillRec(c, shapeOf);
    }

    private static void fillRec(LayoutBox b, Map<LayoutBox, Long> shapeOf) {
        long[] sz = new long[1];
        shapeOf.put(b, shape(b, new Stats(), sz));
        for (LayoutBox c : b.children) fillRec(c, shapeOf);
    }

    static String html(String name) {
        return switch (name) {
            case "nested-list" -> {
                StringBuilder sb = new StringBuilder("<html><head><style>body{margin:8px} .card{padding:6px;border:1px solid #ccc;display:flex;gap:6px}"
                        + " .badge{background:#eef;padding:1px 4px;border-radius:3px} .t{flex:1}</style></head><body><div id=list>");
                for (int i = 0; i < 1000; i++) {
                    sb.append("<div class=card><span class=badge>#").append(i).append("</span><div class=t><b>Item ").append(i)
                            .append("</b><p>row ").append(i).append("</p></div></div>");
                }
                yield sb.append("</div></body></html>").toString();
            }
            case "long-text" -> {
                StringBuilder sb = new StringBuilder("<html><head><style>body{margin:8px} p{padding:4px;border-bottom:1px solid #ccc}</style></head><body>");
                String lorem = "The quick brown fox jumps over the lazy dog and keeps running through the forest until it reaches the river. ";
                for (int i = 0; i < 1000; i++) {
                    sb.append("<p>").append(lorem.repeat(1 + (i % 5))).append(i).append("</p>");
                }
                yield sb.append("</body></html>").toString();
            }
            case "striped" -> {
                StringBuilder sb = new StringBuilder("<html><head><style>body{margin:8px} li{padding:4px} li:nth-child(odd){background:#f4f4f4}</style></head><body><ul>");
                for (int i = 0; i < 1000; i++) sb.append("<li>row ").append(i).append("</li>");
                yield sb.append("</ul></body></html>").toString();
            }
            default -> Workloads.html(name);
        };
    }

    public static void main(String[] args) {
        String[] docs = args.length > 0 ? args : new String[]{"showcase", "guidebook", "large-list-2000", "nested-list", "long-text", "striped"};
        System.out.printf("%-16s %7s %9s %9s %9s %12s %10s %8s%n", "doc", "nodes", "styleInst", "styleSig", "shapes", "shape/node", "runNodes%", "runs(u)");
        for (String doc : docs) {
            StandaloneView v = new StandaloneView(html(doc), Workloads.VIEWPORT_W, Workloads.VIEWPORT_H, FontResolver.FALLBACK);
            v.ui().flushInvalidation();
            v.frame(1000, new NullBackend(), true);
            LayoutBox root = v.ui().layoutRoot();
            if (root == null) { System.out.println(doc + ": layoutRoot null"); continue; }
            STYLE_SIG.clear();
            Stats st = new Stats();
            Map<LayoutBox, Long> shapeOf = new IdentityHashMap<>();
            long[] sz = new long[1];
            shape(root, st, sz);
            fillRec(root, shapeOf);
            runs(root, st, shapeOf);
            int insts = STYLE_SIG.size();
            java.util.Set<Long> sigs = new java.util.HashSet<>(STYLE_SIG.values());
            int total = st.nodes;
            System.out.printf("%-16s %7d %9d %9d %9d %11.3f%% %9.1f%% %4d(%d)%n", doc, total, insts, sigs.size(), st.shapeCount.size(),
                    100.0 * st.shapeCount.size() / total, 100.0 * st.runNodes / total, st.runs, st.uniformRuns);
        }
    }
}
