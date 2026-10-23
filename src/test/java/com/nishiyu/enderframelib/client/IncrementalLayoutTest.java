package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.client.renderer.DisplayListBuilder;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.GoldenFiles;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.dom.parser.MarkdownParser;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 差分レイアウト（箱の使い回しと measure の結果の再利用）が、何もかも作り直したときと同じ結果を出すことを確かめる。
 *
 * <p>同じ HTML から 2 つの文書を作り、同じ変異を同じ順に当てる。片方は前回の箱の木から差分レイアウトし、
 * もう片方は毎回全部作り直す。さらに後者の DOM を、スタイルのキャッシュも持たない新しい対話状態でレイアウトし直す。
 * 3 つのディスプレイリストが毎回一致しなければならない。変異は乱数（種は固定）で選ぶ。</p>
 */
public class IncrementalLayoutTest {

    private static final UiDocumentCompiler PARSER = new UiDocumentCompiler();

    /** flex・grid・float・表・位置指定・リスト・疑似要素・インライン整形・フォーム部品・コンテナクエリを混ぜた文書。 */
    static final String KITCHEN_SINK = """
            <html><head><style>
              body { margin: 6px; font-size: 14px; counter-reset: sec; }
              .row { display: flex; gap: 6px; align-items: center; }
              .row > .grow { flex-grow: 1; }
              .col { display: flex; flex-direction: column; }
              .grid { display: grid; grid-template-columns: 80px 1fr 2fr; gap: 4px; }
              .float-l { float: left; width: 60px; height: 40px; background: #345; }
              .card { border: 2px solid #888; padding: 6px; margin: 4px 0; border-radius: 4px; }
              .wide { width: 70%; }
              .hidden { display: none; }
              .big { font-size: 22px; padding: 10px; }
              .abs { position: absolute; left: 30px; top: 50px; width: 120px; background: #522; }
              .rel { position: relative; left: 5px; top: 3px; }
              .sticky { position: sticky; top: 0; background: #224; }
              .scroller { height: 90px; overflow-y: auto; }
              h2::before { counter-increment: sec; content: counter(sec) ". "; }
              .tag::after { content: " [" attr(data-tag) "]"; }
              ol li::marker { color: #f80; }
              .cq { container-type: inline-size; width: 50%; }
              @container (min-width: 200px) { .cq-inner { padding: 12px; } }
              table { border-collapse: collapse; }
              td { border: 1px solid #999; padding: 3px; }
              pre { white-space: pre; }
              .upper { text-transform: uppercase; }
              .ib { display: inline-block; width: 50px; height: 20px; background: #363; }
            </style></head><body>
              <div id="desk" style="position: relative; height: 200px;">
                <div id="win" class="abs card"><div class="row"><span id="title" class="grow">Window title</span><button id="close">x</button></div>
                  <p id="win-body">Body text that is long enough to wrap inside the window box.</p></div>
                <span id="badge" class="rel">badge</span>
              </div>
              <h2 id="h-a">First section</h2>
              <div id="flex" class="row card"><div id="f1" class="grow">grow one</div><div id="f2">fixed two</div><div id="f3" class="grow big">three</div></div>
              <div id="cols" class="col card"><div id="c1">column a</div><div id="c2" class="wide">column b wide</div></div>
              <h2 id="h-b" class="tag" data-tag="beta">Second section</h2>
              <div id="grid" class="grid card"><div id="g1">g1</div><div id="g2">g2 longer text here</div><div id="g3">g3</div><div id="g4">g4</div></div>
              <div id="floaty" class="card"><div id="fl" class="float-l"></div><p id="wrap">Text wrapping around the float with <b id="bold">bold</b> and <i>italic</i> words, enough to need several lines of text.</p></div>
              <ol id="list"><li id="li1">one</li><li id="li2">two <span class="ib"></span></li><li id="li3" class="upper">three</li></ol>
              <ul id="ul"><li id="u1">alpha</li><li id="u2">beta</li></ul>
              <div id="scroller" class="scroller card"><div id="pin" class="sticky">pinned</div><p id="s1">line 1</p><p id="s2">line 2</p><p id="s3">line 3</p><p id="s4">line 4</p></div>
              <table id="tbl"><tr><td id="t1">a</td><td id="t2">bb</td></tr><tr><td id="t3">ccc</td><td id="t4">d</td></tr></table>
              <details id="det" open><summary id="sum">More</summary><p id="det-p">details body</p></details>
              <div id="cq" class="cq card"><div id="cq-inner" class="cq-inner">container query</div></div>
              <pre id="pre">pre  formatted
              text</pre>
              <form id="form"><input id="inp" value="hello" placeholder="type"><textarea id="ta">area</textarea><select id="sel"><option>one</option><option>two</option></select></form>
              <p id="last">Trailing paragraph with an <span id="inline-span">inline span</span>.</p>
            </body></html>
            """;

    private static final String[] STYLE_PROPS = {
            "width", "height", "padding", "margin", "display", "font-size", "left", "top", "color",
            "flex-grow", "float", "position", "white-space", "border", "background-color", "text-align", "line-height"
    };

    @Test
    public void kitchenSinkRandomMutationsMatchFullLayout() {
        for (long seed = 1; seed <= 10; seed++) {
            runRandomMutations("kitchen-sink", KITCHEN_SINK, seed, 80);
        }
    }

    @Test
    public void showcaseRandomMutationsMatchFullLayout() {
        runRandomMutations("showcase", GoldenFiles.readFixture("showcase.html"), 11, 50);
    }

    @Test
    public void guidebookRandomMutationsMatchFullLayout() {
        runRandomMutations("guidebook", MarkdownParser.toHtml(GoldenFiles.readFixture("guidebook.md")), 21, 40);
    }

    @Test
    public void unchangedSubtreesKeepTheirBoxes() {
        Document doc = PARSER.parseDocument(KITCHEN_SINK, "", 800, 600);
        InteractionState state = new InteractionState();
        LayoutBox before = PARSER.layoutDocument(doc, 800, 600, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);
        HtmlNode grid = doc.root.getElementById("grid");
        HtmlNode title = doc.root.getElementById("title");
        LayoutBox gridBox = before.findByNode(grid);

        title.setTextContent("Renamed window");
        state.markDirty(title);
        LayoutBox after = PARSER.layoutDocument(doc, 800, 600, Map.of(), new ScrollState(), FontResolver.FALLBACK, state, before);

        assertSame(before, after, "ルートの箱は使い回す");
        assertSame(gridBox, after.findByNode(grid), "変わっていない部分木の箱は使い回す");
        org.junit.jupiter.api.Assertions.assertNotNull(after.findByNode(title));
    }

    @Test
    public void changingOneRowRemeasuresOnlyItsPath() {
        StringBuilder html = new StringBuilder("<html><body><ul>");
        for (int i = 0; i < 300; i++) html.append("<li id=\"row").append(i).append("\">Row ").append(i).append(" <b>bold</b></li>");
        html.append("</ul></body></html>");
        Document doc = PARSER.parseDocument(html.toString(), "", 800, 600);
        InteractionState state = new InteractionState();
        LayoutBox root = PARSER.layoutDocument(doc, 800, 600, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);
        HtmlNode row = doc.root.getElementById("row150");

        long beforeFull = LayoutBox.measurementsComputed();
        row.setTextContent("changed once");
        state.markDirty(row);
        PARSER.layoutDocument(doc, 800, 600, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);
        long full = LayoutBox.measurementsComputed() - beforeFull;

        long beforeIncremental = LayoutBox.measurementsComputed();
        row.setTextContent("changed twice");
        state.markDirty(row);
        PARSER.layoutDocument(doc, 800, 600, Map.of(), new ScrollState(), FontResolver.FALLBACK, state, root);
        long incremental = LayoutBox.measurementsComputed() - beforeIncremental;

        // 測り直すのは変わった行とその祖先（と行の中身）だけ。300 行ぶんを測り直してはいけない
        org.junit.jupiter.api.Assertions.assertTrue(incremental * 20 < full,
                "差分レイアウトの measure が多すぎる: 全部作り直し " + full + " 回に対して " + incremental + " 回");
    }

    // ------------------------------------------------------------------

    /** @param presentational 見た目（style / class / hidden）だけを変えるか。セッションは再スタイルの経路で扱う */
    private record Mutation(String description, java.util.function.BiConsumer<HtmlNode, InteractionState> apply,
                            boolean presentational) {
        Mutation(String description, java.util.function.BiConsumer<HtmlNode, InteractionState> apply) {
            this(description, apply, false);
        }
    }

    void runRandomMutations(String name, String html, long seed, int steps) {
        Random random = new Random(seed);
        int w = 800, h = 600;

        Document incremental = PARSER.parseDocument(html, "", w, h);
        Document full = PARSER.parseDocument(html, "", w, h);
        InteractionState incrementalState = new InteractionState();
        InteractionState fullState = new InteractionState();
        ScrollState incrementalScroll = new ScrollState();
        ScrollState fullScroll = new ScrollState();
        LayoutBox root = PARSER.layoutDocument(incremental, w, h, Map.of(), incrementalScroll, FontResolver.FALLBACK, incrementalState);

        List<String> history = new ArrayList<>();
        for (int step = 0; step < steps; step++) {
            int kind = random.nextInt(10);
            boolean presentational = false;
            if (kind == 0) {
                // ビューポートの変更
                w = 600 + random.nextInt(500);
                h = 400 + random.nextInt(400);
                history.add("resize " + w + "x" + h);
            } else if (kind == 1) {
                // スクロール
                List<int[]> scrollers = scrollContainers(incremental.root);
                if (!scrollers.isEmpty()) {
                    int[] path = scrollers.get(random.nextInt(scrollers.size()));
                    int y = random.nextInt(120);
                    incrementalScroll.setScroll(resolve(incremental.root, path), 0, y);
                    fullScroll.setScroll(resolve(full.root, path), 0, y);
                    history.add("scroll " + describe(resolve(incremental.root, path)) + " y=" + y);
                }
            } else {
                List<int[]> elements = elementPaths(incremental.root);
                int[] path = elements.get(random.nextInt(elements.size()));
                Mutation mutation = randomMutation(random, resolve(incremental.root, path));
                if (mutation == null) continue;
                history.add(mutation.description() + " on " + describe(resolve(incremental.root, path)));
                presentational = mutation.presentational();
                mutation.apply().accept(resolve(incremental.root, path), incrementalState);
                mutation.apply().accept(resolve(full.root, path), fullState);
            }

            if (presentational && random.nextBoolean()) {
                // クライアントがスタイルを変えたときの経路: 前回の木を再スタイルし、必要な段だけ走らせる
                // （SessionContext.restyleAndUpdate と同じ分岐。前回の木の算出スタイルがその場で書き換わる）
                com.nishiyu.enderframelib.common.RenderPipeline.RestyleResult res =
                        PARSER.restyleLayoutTree(root, incremental, w, h, Map.of(), incrementalState);
                if (res.layoutRequired()) {
                    root = PARSER.layoutDocument(incremental, w, h, Map.of(), incrementalScroll, FontResolver.FALLBACK,
                            incrementalState, root);
                } else {
                    com.nishiyu.enderframelib.common.RenderPipeline.repositionLayout(root, w, h, incrementalScroll,
                            FontResolver.FALLBACK);
                }
                history.add("  (restyle path: layout=" + res.layoutRequired() + " reposition=" + res.repositionRequired() + ")");
            } else {
                root = PARSER.layoutDocument(incremental, w, h, Map.of(), incrementalScroll, FontResolver.FALLBACK,
                        incrementalState, root);
            }
            List<DisplayItem> actual = render(incremental, root, w, h, incrementalScroll);

            LayoutBox fullRoot = PARSER.layoutDocument(full, w, h, Map.of(), fullScroll, FontResolver.FALLBACK, fullState);
            List<DisplayItem> expected = render(full, fullRoot, w, h, fullScroll);
            if (!expected.equals(actual)) history.add("box tree diff: " + firstBoxDifference(fullRoot, root));
            assertSameDisplayList(expected, actual, name + " seed=" + seed + " step=" + step, history);

            // スタイルのキャッシュも持たない新しい状態で、同じ DOM を測り直しても同じになること
            LayoutBox coldRoot = PARSER.layoutDocument(full, w, h, Map.of(), fullScroll, FontResolver.FALLBACK,
                    new InteractionState());
            assertSameDisplayList(render(full, coldRoot, w, h, fullScroll), expected,
                    name + " seed=" + seed + " step=" + step + " (cold)", history);
        }
    }

    private Mutation randomMutation(Random random, HtmlNode target) {
        int op = random.nextInt(9);
        switch (op) {
            case 0, 1, 2 -> {
                String prop = STYLE_PROPS[random.nextInt(STYLE_PROPS.length)];
                String value = randomStyleValue(random, prop);
                return new Mutation("style " + prop + ": " + value, (node, state) -> {
                    node.setStyleProperty(prop, value);
                    state.markDirty(node);
                }, true);
            }
            case 3 -> {
                String[] classes = {"hidden", "big", "wide", "rel", "card", "upper", "row", "grow"};
                String cls = classes[random.nextInt(classes.length)];
                return new Mutation("toggle class " + cls, (node, state) -> {
                    node.toggleClass(cls);
                    state.markDirty(node);
                }, true);
            }
            case 4 -> {
                if (!hasOnlyTextChildren(target)) return null;
                String text = randomText(random);
                return new Mutation("text \"" + text + "\"", (node, state) -> {
                    node.setTextContent(text);
                    state.markDirty(node);
                });
            }
            case 5 -> {
                String value = random.nextBoolean() ? null : "v" + random.nextInt(5);
                String attr = random.nextBoolean() ? "hidden" : "data-tag";
                return new Mutation("attr " + attr + "=" + value, (node, state) -> {
                    if (value == null) node.removeAttribute(attr);
                    else node.setAttribute(attr, value);
                    state.markDirty(node);
                }, "hidden".equals(attr));
            }
            case 6 -> {
                if (target.parent == null || "body".equals(target.tag) || "html".equals(target.tag)) return null;
                return new Mutation("remove", (node, state) -> {
                    HtmlNode parent = node.parent;
                    parent.removeChild(node);
                    state.markDirty(parent);
                });
            }
            case 7 -> {
                String fragment = random.nextBoolean()
                        ? "<span>inserted " + random.nextInt(100) + "</span>"
                        : "<div class=\"card\"><b>block</b> insert " + random.nextInt(100) + "</div>";
                return new Mutation("append " + fragment, (node, state) -> {
                    HtmlNode holder = new HtmlNode("div");
                    holder.setInnerHTML(fragment);
                    for (HtmlNode child : new ArrayList<>(holder.children)) {
                        holder.removeChild(child);
                        node.appendChild(child);
                    }
                    state.markDirty(node);
                });
            }
            default -> {
                String inner = random.nextBoolean() ? "new <i>inner</i> html " + random.nextInt(50) : "x";
                return new Mutation("innerHTML " + inner, (node, state) -> {
                    node.setInnerHTML(inner);
                    state.markDirty(node);
                });
            }
        }
    }

    private static String randomStyleValue(Random random, String prop) {
        return switch (prop) {
            case "width" -> pick(random, "auto", "120px", "40%", "300px", "min-content");
            case "height" -> pick(random, "auto", "30px", "80px", "50%");
            case "padding" -> pick(random, "0", "4px", "12px 2px");
            case "margin" -> pick(random, "0", "6px", "auto", "10px 0");
            case "display" -> pick(random, "block", "inline", "inline-block", "flex", "grid", "none");
            case "font-size" -> pick(random, "10px", "14px", "20px", "1.5em");
            case "left" -> random.nextInt(200) + "px";
            case "top" -> random.nextInt(150) + "px";
            case "color" -> pick(random, "#f00", "#0f0", "rgb(10, 20, 30)");
            case "flex-grow" -> pick(random, "0", "1", "3");
            case "float" -> pick(random, "none", "left", "right");
            case "position" -> pick(random, "static", "relative", "absolute");
            case "white-space" -> pick(random, "normal", "nowrap", "pre");
            case "border" -> pick(random, "0", "1px solid #f00", "4px dashed #00f");
            case "background-color" -> pick(random, "#123", "transparent", "#ffeeaa");
            case "text-align" -> pick(random, "left", "center", "right");
            case "line-height" -> pick(random, "normal", "1.8", "24px");
            default -> "initial";
        };
    }

    private static String randomText(Random random) {
        return pick(random, "", "short", "a somewhat longer replacement text that should wrap across lines",
                "数字 12345", "x");
    }

    private static String pick(Random random, String... values) {
        return values[random.nextInt(values.length)];
    }

    private static boolean hasOnlyTextChildren(HtmlNode node) {
        for (HtmlNode child : node.children) {
            if (child.nodeType != NodeType.TEXT) return false;
        }
        return true;
    }

    /** body 以下の要素（head・style・script を除く）の、ルートからの子の番号の並び。 */
    private static List<int[]> elementPaths(HtmlNode root) {
        List<int[]> out = new ArrayList<>();
        collectPaths(root, new ArrayList<>(), out, false);
        return out;
    }

    private static void collectPaths(HtmlNode node, List<Integer> path, List<int[]> out, boolean inBody) {
        boolean body = inBody || "body".equals(node.tag);
        if (node.nodeType != NodeType.TEXT && body && !"style".equals(node.tag) && !"script".equals(node.tag)) {
            out.add(path.stream().mapToInt(Integer::intValue).toArray());
        }
        if ("head".equals(node.tag)) return;
        for (int i = 0; i < node.children.size(); i++) {
            path.add(i);
            collectPaths(node.children.get(i), path, out, body);
            path.removeLast();
        }
    }

    private static List<int[]> scrollContainers(HtmlNode root) {
        List<int[]> out = new ArrayList<>();
        for (int[] path : elementPaths(root)) {
            HtmlNode node = resolve(root, path);
            String cls = node.attrs.getOrDefault("class", "");
            String style = node.attrs.getOrDefault("style", "");
            if (cls.contains("scroller") || style.contains("overflow") || "scroller".equals(node.getId())) out.add(path);
        }
        return out;
    }

    private static HtmlNode resolve(HtmlNode root, int[] path) {
        HtmlNode cur = root;
        for (int index : path) cur = cur.children.get(index);
        return cur;
    }

    private static String describe(HtmlNode node) {
        return "<" + node.tag + (node.getId().isEmpty() ? "" : " #" + node.getId()) + ">";
    }

    /** 2 つの箱の木を同じ順に辿り、最初に位置・寸法の違う箱を返す（失敗したときの手がかり）。 */
    static String firstBoxDifference(LayoutBox expected, LayoutBox actual) {
        List<String> e = new ArrayList<>();
        List<String> a = new ArrayList<>();
        dumpBoxes(expected, "", e);
        dumpBoxes(actual, "", a);
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (int i = 0; i < Math.min(e.size(), a.size()) && shown < 6; i++) {
            if (!e.get(i).equals(a.get(i))) {
                sb.append("\n      expected ").append(e.get(i)).append("\n      actual   ").append(a.get(i));
                shown++;
            }
        }
        if (shown > 0) return sb.toString();
        return e.size() == a.size() ? "(none)" : "box count " + e.size() + " vs " + a.size();
    }

    private static void dumpBoxes(LayoutBox box, String path, List<String> out) {
        out.add(path + " " + box.node.tag + "#" + box.node.getId() + " x=" + box.x + " y=" + box.y + " w=" + box.w
                + " h=" + box.h + " m=" + box.mT + "," + box.mR + "," + box.mB + "," + box.mL
                + " display=" + box.style.get("display") + " style#" + box.style.asMap().hashCode());
        for (int i = 0; i < box.children.size(); i++) dumpBoxes(box.children.get(i), path + "/" + i, out);
    }

    private static List<DisplayItem> render(Document doc, LayoutBox root, int w, int h, ScrollState scroll) {
        return new DisplayListBuilder(w, h, doc.sheet.keyframes, scroll).render(root);
    }

    static void assertSameDisplayList(List<DisplayItem> expected, List<DisplayItem> actual, String label, List<String> history) {
        int n = Math.min(expected.size(), actual.size());
        for (int i = 0; i < n; i++) {
            if (!expected.get(i).equals(actual.get(i))) {
                fail(label + ": " + i + " 番目の描画要素が違う\n  期待: " + expected.get(i)
                        + "\n  実際: " + actual.get(i) + "\n  変異の履歴:\n    " + String.join("\n    ", tail(history)));
            }
        }
        assertEquals(expected.size(), actual.size(), label + ": 描画要素の数が違う\n  変異の履歴:\n    "
                + String.join("\n    ", tail(history)));
    }

    private static List<String> tail(List<String> history) {
        return history.subList(Math.max(0, history.size() - 12), history.size());
    }
}
