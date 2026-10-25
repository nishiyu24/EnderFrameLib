package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.client.renderer.DisplayListBuilder;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.css.InvalidationSets;
import com.nishiyu.enderframelib.core.css.model.ElementState;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.dom.node.HtmlDocument;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 無効化セットで範囲を絞った再スタイル（R5）が、何もかも作り直したときと同じ結果になることを確かめる。
 * 変異の列を乱数で作り、同じ列を「生かした文書」と「毎回作り直す文書」へ流して、ディスプレイリストを比べる。
 */
public class InvalidationPipelineTest {

    private static final UiDocumentCompiler COMPILER = new UiDocumentCompiler();
    private static final int W = 640;
    private static final int H = 480;

    private static final String BASE_CSS = """
            body { margin: 4px; font-size: 14px; }
            .box { padding: 3px; border: 1px solid #555; background-color: #111; }
            .grp { border-width: 3px; }
            .a .item { color: #f00; }
            .a > .item.b { background-color: #0f0; }
            .item + .item { margin-top: 2px; }
            .item:first-child { font-weight: bold; }
            .item:last-child { border-bottom: 2px solid #00f; }
            .item:nth-child(2n) { background-color: #222; }
            .box:hover .item { color: #ff0; }
            .box:has(.flag) { border-color: #f0f; }
            .b ~ .c { padding: 5px; }
            .c { font-size: 18px; }
            .item:hover { background-color: #888; }
            .big { font-size: 20px; }
            .tint { background-color: #345; }
            """;

    /** 親の非継承プロパティを {@code inherit} で受ける規則を足したもの。これが無いと子孫へ及ばない。 */
    private static final String INHERIT_CSS = BASE_CSS + """
            .item span { background-color: inherit; border: inherit; }
            """;

    private static String html(String css) {
        StringBuilder sb = new StringBuilder("<html><head><style>").append(css).append("</style></head><body>");
        sb.append("<div id=\"root\" class=\"box\">");
        for (int g = 1; g <= 2; g++) {
            sb.append("<div id=\"g").append(g).append("\" class=\"box grp\">");
            for (int i = 1; i <= 5; i++) {
                sb.append("<div id=\"i").append(g).append('_').append(i).append("\" class=\"item\">Item ")
                        .append(g).append('-').append(i).append(" <span id=\"s").append(g).append('_').append(i)
                        .append("\">inner</span></div>");
            }
            sb.append("</div>");
        }
        sb.append("</div></body></html>");
        return sb.toString();
    }

    private record Op(String kind, String targetId, String arg, int index) {}

    private static final String[] CLASSES = {"a", "b", "c", "flag", "big", "tint"};

    private static List<Op> randomOps(long seed, int count) {
        Random rnd = new Random(seed);
        List<Op> ops = new ArrayList<>();
        List<String> ids = new ArrayList<>(List.of("root", "g1", "g2"));
        for (int g = 1; g <= 2; g++) {
            for (int i = 1; i <= 5; i++) {
                ids.add("i" + g + "_" + i);
                ids.add("s" + g + "_" + i);
            }
        }
        int added = 0;
        for (int n = 0; n < count; n++) {
            String id = ids.get(rnd.nextInt(ids.size()));
            switch (rnd.nextInt(7)) {
                case 0, 1 -> ops.add(new Op("class", id, CLASSES[rnd.nextInt(CLASSES.length)], 0));
                case 2 -> ops.add(new Op("hover", id, "", 0));
                case 3 -> ops.add(new Op("style", id, new String[]{"", "color: #0ff", "background-color: #a0a",
                        "font-size: 22px", "border: 2px solid #fa0"}[rnd.nextInt(5)], 0));
                case 4 -> ops.add(new Op("attr", id, String.valueOf(rnd.nextInt(3)), 0));
                case 5 -> {
                    String parent = rnd.nextBoolean() ? "g1" : "g2";
                    String newId = "n" + added++;
                    ids.add(newId);
                    ops.add(new Op("insert", parent, newId, rnd.nextInt(5)));
                }
                default -> {
                    String victim = ids.get(rnd.nextInt(ids.size()));
                    if (victim.startsWith("i") || victim.startsWith("n")) {
                        ops.add(new Op("remove", victim, "", 0));
                        ids.remove(victim);
                    }
                }
            }
        }
        return ops;
    }

    /** 変異を文書へ流す。文書に無い id（削除済みの子孫など）は読み飛ばす。 */
    private static void apply(Document doc, InteractionState state, Op op) {
        HtmlNode target = doc.root.getElementById(op.targetId());
        if (target == null) return;
        switch (op.kind()) {
            case "class" -> target.toggleClass(op.arg());
            case "hover" -> state.setHoveredNode(target);
            case "style" -> {
                if (op.arg().isEmpty()) target.removeAttribute("style");
                else target.setAttribute("style", op.arg());
            }
            case "attr" -> target.setAttribute("data-k", op.arg());
            case "insert" -> {
                HtmlNode item = doc.root.getElementById("i1_1");
                HtmlNode fresh = item != null ? item.cloneNode(true) : null;
                if (fresh == null) return;
                retagIds(fresh, op.arg());
                int index = Math.min(op.index(), target.children.size());
                HtmlNode ref = index < target.children.size() ? target.children.get(index) : null;
                if (ref == null) target.appendChild(fresh);
                else target.insertBefore(fresh, ref);
            }
            case "remove" -> {
                if (target.parent != null) target.parent.removeChild(target);
            }
            default -> throw new IllegalStateException(op.kind());
        }
    }

    private static void retagIds(HtmlNode node, String id) {
        node.setAttribute("id", id);
        for (HtmlNode child : node.children) {
            if (!child.getId().isEmpty()) child.setAttribute("id", id + "_c");
        }
    }

    private static List<DisplayItem> render(Document doc, LayoutBox root) {
        return new DisplayListBuilder(W, H, doc.sheet.keyframes, new ScrollState()).render(root);
    }

    private void runSequence(String css, long seed, boolean restyleFirst) {
        String source = html(css);
        Document live = COMPILER.parseDocument(source, "", W, H);
        InteractionState state = new InteractionState();
        ((HtmlDocument) live.root).setInteractionState(state);
        LayoutBox root = COMPILER.layoutDocument(live, W, H, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);

        List<Op> history = new ArrayList<>();
        for (Op op : randomOps(seed, 40)) {
            history.add(op);
            apply(live, state, op);
            if (restyleFirst) COMPILER.restyleLayoutTree(root, live, W, H, Map.of(), state);
            root = COMPILER.layoutDocument(live, W, H, Map.of(), new ScrollState(), FontResolver.FALLBACK, state, root);
            List<DisplayItem> incremental = render(live, root);

            Document fresh = COMPILER.parseDocument(source, "", W, H);
            InteractionState freshState = new InteractionState();
            ((HtmlDocument) fresh.root).setInteractionState(freshState);
            for (Op past : history) apply(fresh, freshState, past);
            LayoutBox full = COMPILER.layoutDocument(fresh, W, H, Map.of(), new ScrollState(),
                    FontResolver.FALLBACK, freshState);
            List<DisplayItem> expected = render(fresh, full);

            String label = "seed=" + seed + " restyleFirst=" + restyleFirst + " step=" + history.size() + " " + history;
            assertFalse(expected.isEmpty(), label);
            assertEquals(expected.size(), incremental.size(), label + ": 描画要素の数が違う");
            for (int i = 0; i < expected.size(); i++) {
                assertEquals(expected.get(i), incremental.get(i), label + ": " + i + " 番目の描画要素が違う");
            }
        }
    }

    @Test
    public void randomMutationsMatchFullRebuild() {
        for (long seed = 1; seed <= 12; seed++) {
            runSequence(BASE_CSS, seed, false);
            runSequence(BASE_CSS, seed, true);
        }
    }

    @Test
    public void randomMutationsWithInheritKeywordMatchFullRebuild() {
        for (long seed = 100; seed < 108; seed++) {
            runSequence(INHERIT_CSS, seed, false);
            runSequence(INHERIT_CSS, seed, true);
        }
    }

    @Test
    public void nonInheritedChangeDoesNotRestyleDescendants() {
        Document doc = COMPILER.parseDocument(
                "<html><head><style>.p { background-color: #111; } .q { background-color: #222; }</style></head><body>"
                        + "<div id=\"p\" class=\"p\"><div id=\"c1\"><span id=\"c2\">x</span></div></div></body></html>", "", W, H);
        InteractionState state = new InteractionState();
        ((HtmlDocument) doc.root).setInteractionState(state);
        LayoutBox root = COMPILER.layoutDocument(doc, W, H, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);
        COMPILER.restyleLayoutTree(root, doc, W, H, Map.of(), state);

        doc.root.getElementById("p").toggleClass("q");
        InteractionState.StateFlags parent = state.get(doc.root.getElementById("p"));
        InteractionState.StateFlags child = state.get(doc.root.getElementById("c1"));
        InteractionState.StateFlags grandchild = state.get(doc.root.getElementById("c2"));
        assertTrue(parent.isStyleDirty, "class を変えた要素自身は再スタイルする");
        assertFalse(child.isStyleDirty, "子孫を読む規則が無いので、子は汚さない");
        assertFalse(grandchild.isStyleDirty);

        COMPILER.restyleLayoutTree(root, doc, W, H, Map.of(), state);
        assertFalse(parent.isStyleDirty);
    }

    @Test
    public void descendantSelectorMarksSubtree() {
        Document doc = COMPILER.parseDocument(
                "<html><head><style>.on .leaf { color: #f00; }</style></head><body>"
                        + "<div id=\"p\"><div id=\"c\"><span id=\"leaf\" class=\"leaf\">x</span></div></div></body></html>", "", W, H);
        InteractionState state = new InteractionState();
        ((HtmlDocument) doc.root).setInteractionState(state);
        COMPILER.layoutDocument(doc, W, H, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);

        doc.root.getElementById("p").toggleClass("on");
        assertTrue(state.get(doc.root.getElementById("p")).subtreeDirty,
                "祖先の class を読む規則があるので、子孫を算出し直す");
    }

    @Test
    public void hoverReachesOnlyWhatRulesRead() {
        Document doc = COMPILER.parseDocument(
                "<html><head><style>.row:hover { background-color: #def; }</style></head><body>"
                        + "<ul id=\"l\"><li id=\"r1\" class=\"row\">a</li><li id=\"r2\" class=\"row\">b</li></ul></body></html>", "", W, H);
        InteractionState state = new InteractionState();
        ((HtmlDocument) doc.root).setInteractionState(state);
        COMPILER.layoutDocument(doc, W, H, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);

        InteractionState.StateFlags r1 = state.get(doc.root.getElementById("r1"));
        InteractionState.StateFlags r2 = state.get(doc.root.getElementById("r2"));
        assertNotNull(r1);
        assertFalse(r1.isStyleDirty, "初回の描画で確定済み");
        state.setHoveredNode(doc.root.getElementById("r1"));
        assertTrue(r1.isStyleDirty);
        assertFalse(r2.isStyleDirty, "兄弟の行はホバーの影響を受けない");
        assertFalse(r1.subtreeDirty, ":hover を子孫の規則が読まないなら、子孫は巻き込まない");
    }

    @Test
    public void hoverOnAncestorRuleReachesDescendants() {
        Document doc = COMPILER.parseDocument(
                "<html><head><style>.box:hover .item { color: #ff0; }</style></head><body></body></html>", "", W, H);
        InvalidationSets sets = doc.sheet.invalidationSets();
        assertTrue(sets.forState(ElementState.HOVER).contains(InvalidationSets.Relation.DESCENDANTS),
                String.valueOf(sets.forState(ElementState.HOVER)));
    }

    @Test
    public void invalidationSetsRootChangeIsSeparateFromStructure() {
        Document doc = COMPILER.parseDocument(
                "<html><head><style>:root { --x: 1; } .a { color: red; }</style></head><body></body></html>", "", W, H);
        InvalidationSets sets = doc.sheet.invalidationSets();
        assertTrue(sets.forStructureChange().isEmpty(),
                ":root だけを読む規則では、兄弟の増減で兄弟を汚さない");
        assertFalse(sets.forRootChange().isEmpty());
        assertTrue(sets.forState(ElementState.HOVER).isEmpty());
    }
}
