package com.nishiyu.enderframelib.bench;

import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.RenderPipeline;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.EngineContext;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.text.model.FontResolver;

import java.util.Map;

/**
 * 調査用: レイアウト 1 回あたりの箱の数・測定回数（増幅率）・時間・確保量・段ごとの確保量。
 * <pre>
 * ./gradlew :core:profile -Pprofile.main=com.nishiyu.enderframelib.bench.InvestigateMain -Pprofile.args="showcase 800"
 * </pre>
 * 増幅率 = 測定回数 / 箱の数。1 に近いほど、同じ箱を何度も測っていない。
 */
public final class InvestigateMain {
    private InvestigateMain() { }

    public static void main(String[] args) {
        String doc = args.length > 0 ? args[0] : Workloads.SHOWCASE;
        int iters = args.length > 1 ? Integer.parseInt(args[1]) : 600;
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document d = parser.parseDocument(Workloads.html(doc), "", Workloads.VIEWPORT_W, Workloads.VIEWPORT_H);
        InteractionState st = new InteractionState();
        LayoutBox root = layout(parser, d, st);
        for (int i = 0; i < 300; i++) root = layout(parser, d, st);

        long m0 = LayoutBox.measurementsComputed();
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().getId();
        long a0 = mx.getThreadAllocatedBytes(tid);
        long t0 = System.nanoTime();
        for (int i = 0; i < iters; i++) root = layout(parser, d, st);
        double ms = (System.nanoTime() - t0) / 1e6 / iters;
        long bytes = (mx.getThreadAllocatedBytes(tid) - a0) / iters;
        long measures = (LayoutBox.measurementsComputed() - m0) / iters;
        int[] boxes = new int[1];
        count(root, boxes);
        System.out.printf("doc=%s boxes=%d measures/layout=%d amplification=%.2f ms/layout=%.3f alloc/layout=%d KB (%.0f B/box) ns/box=%.0f%n",
                doc, boxes[0], measures, measures / (double) boxes[0], ms, bytes / 1024, bytes / (double) boxes[0], ms * 1e6 / boxes[0]);

        // 画面を開くコスト（温まった JVM）: 解析 → 初回レイアウト（新しい文書・新しい状態）
        long tp = 0, tl = 0;
        int open = Math.max(50, iters / 4);
        String html = Workloads.html(doc);
        for (int i = 0; i < open + 100; i++) {
            long a = System.nanoTime();
            Document nd = parser.parseDocument(html, "", Workloads.VIEWPORT_W, Workloads.VIEWPORT_H);
            long b = System.nanoTime();
            layout(parser, nd, new InteractionState());
            long c = System.nanoTime();
            if (i >= 100) { tp += b - a; tl += c - b; }
        }
        System.out.printf("open: parse=%.3f ms firstLayout(style+build+measure)=%.3f ms%n", tp / 1e6 / open, tl / 1e6 / open);

        // 解析の内訳
        Document probe = parser.parseDocument(html, "", Workloads.VIEWPORT_W, Workloads.VIEWPORT_H);
        String css = probe.authorCss;
        long th = 0, tc = 0, ts = 0;
        for (int i = 0; i < open + 100; i++) {
            long a = System.nanoTime();
            com.nishiyu.enderframelib.core.dom.parser.HtmlParser.parseDocument(html);
            long b = System.nanoTime();
            com.nishiyu.enderframelib.core.css.CssParser.parse(css == null ? "" : css, Workloads.VIEWPORT_W);
            long c = System.nanoTime();
            parser.buildStyleSheet(css == null ? "" : css, Workloads.VIEWPORT_W, Workloads.VIEWPORT_H);
            long d2 = System.nanoTime();
            if (i >= 100) { th += b - a; tc += c - b; ts += d2 - c; }
        }
        System.out.printf("parse split: html=%.3f ms css(CssParser.parse)=%.3f ms buildStyleSheet=%.3f ms authorCssChars=%d%n",
                th / 1e6 / open, tc / 1e6 / open, ts / 1e6 / open, css == null ? -1 : css.length());

        long tua = 0, tmerge = 0;
        String ua = com.nishiyu.enderframelib.common.DocumentLoader.userAgentCss();
        var media = com.nishiyu.enderframelib.core.css.CssParser.MediaEnvironment.of(Workloads.VIEWPORT_W, Workloads.VIEWPORT_H);
        for (int i = 0; i < open + 100; i++) {
            long a = System.nanoTime();
            var sh = com.nishiyu.enderframelib.core.css.CssParser.parse(ua, media, com.nishiyu.enderframelib.core.css.StyleSheet.CascadeOrigin.USER_AGENT);
            long b = System.nanoTime();
            sh.merge(com.nishiyu.enderframelib.core.css.CssParser.parse(css == null ? "" : css, media, com.nishiyu.enderframelib.core.css.StyleSheet.CascadeOrigin.AUTHOR));
            sh.updateMediaEnvironment(media);
            sh.sortCascade();
            long c = System.nanoTime();
            if (i >= 100) { tua += b - a; tmerge += c - b; }
        }
        System.out.printf("buildStyleSheet split: UA parse=%.3f ms (uaChars=%d) author parse+merge+sort=%.3f ms%n", tua / 1e6 / open, ua.length(), tmerge / 1e6 / open);

        // 段ごとの確保量（build = スタイル + 箱の生成、boxTree = 箱の木の整形、passes = 測定 + 配置）
        EngineContext.current().trackAllocations = true;
        layout(parser, d, st);
        System.out.println("phases: " + RenderPipeline.lastBuildStats());
        EngineContext.current().trackAllocations = false;
    }

    private static LayoutBox layout(UiDocumentCompiler parser, Document d, InteractionState st) {
        return parser.layoutDocument(d, Workloads.VIEWPORT_W, Workloads.VIEWPORT_H, Map.of(), new ScrollState(), FontResolver.FALLBACK, st);
    }

    private static void count(LayoutBox b, int[] c) {
        c[0]++;
        for (LayoutBox k : b.children) count(k, c);
    }
}
