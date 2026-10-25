package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.client.renderer.DisplayListBuilder;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 差分レイアウト（measure と layout の結果の再利用）が、毎回作り直したときと同じディスプレイリストを出すことを、
 * ブロック・フレックス・グリッド・float・絶対配置・相対配置・sticky・スクロールコンテナ・インラインを混ぜた文書への
 * 乱数の変異列で確かめる。
 */
public class IncrementalLayoutFuzzTest {

    private static final UiDocumentCompiler COMPILER = new UiDocumentCompiler();
    private static final int W = 640;
    private static final int H = 480;

    private static final String CSS = """
            body { margin: 4px; font-size: 14px; }
            .box { padding: 3px; border: 1px solid #555; background-color: #111; }
            .w1 { width: 180px; }
            .w2 { width: 60%; }
            .h1 { height: 40px; }
            .m1 { margin: 6px 3px 9px 12px; }
            .p1 { padding: 8px; }
            .flex { display: flex; gap: 4px; }
            .col { flex-direction: column; }
            .grid { display: grid; grid-template-columns: 1fr 2fr; gap: 3px; }
            .fl { float: left; width: 50px; height: 20px; }
            .abs { position: absolute; left: 20px; top: 15px; }
            .rel { position: relative; left: 5px; top: 3px; }
            .stk { position: sticky; top: 0; }
            .big { font-size: 22px; }
            .ib { display: inline-block; width: 30px; }
            .scr { height: 70px; overflow: auto; }
            .none { display: none; }
            .csize { contain: size; width: 90px; height: 40px; }
            .clay { contain: layout; }
            .cpaint { contain: paint; }
            .cstrict { contain: strict; width: 100px; height: 50px; }
            .cvh { content-visibility: hidden; width: 110px; height: 30px; }
            .cva { content-visibility: auto; }
            .bnd { overflow: auto; width: 130px; height: 55px; }
            .tbl { display: table; }
            .row { display: table-row; }
            .cell { display: table-cell; padding: 2px; }
            """;

    private static final List<String> ALL_IDS = new ArrayList<>();

    private static String html() {
        ALL_IDS.clear();
        StringBuilder sb = new StringBuilder("<html><head><style>").append(CSS).append("</style></head><body>");
        sb.append("<div id=\"root\" class=\"box\">");
        for (int g = 1; g <= 4; g++) {
            String gid = "g" + g;
            ALL_IDS.add(gid);
            sb.append("<div id=\"").append(gid).append("\" class=\"box").append(g == 3 ? " scr" : "").append("\">");
            for (int i = 1; i <= 5; i++) {
                String id = "i" + g + "_" + i;
                ALL_IDS.add(id);
                sb.append("<div id=\"").append(id).append("\" class=\"box\">Item ").append(g).append('-').append(i)
                        .append(" with some words that can wrap around the line <span id=\"s").append(g).append('_').append(i)
                        .append("\">inline</span></div>");
                ALL_IDS.add("s" + g + "_" + i);
            }
            sb.append("</div>");
        }
        sb.append("<div class=\"tbl\" id=\"t\"><div class=\"row\"><div class=\"cell\" id=\"c1\">a</div><div class=\"cell\" id=\"c2\">b</div></div></div>");
        ALL_IDS.add("t");
        ALL_IDS.add("c1");
        ALL_IDS.add("c2");
        sb.append("</div></body></html>");
        return sb.toString();
    }

    private record Op(String kind, String targetId, String arg, int index) {}

    private static final String[] CLASSES = {"w1", "w2", "h1", "m1", "p1", "flex", "col", "grid", "fl", "abs", "rel", "stk", "big",
            "ib", "none", "csize", "clay", "cpaint", "cstrict", "cvh", "cva", "bnd"};

    private static List<Op> randomOps(long seed, int count) {
        Random rnd = new Random(seed);
        List<String> ids = new ArrayList<>(ALL_IDS);
        List<Op> ops = new ArrayList<>();
        int added = 0;
        for (int n = 0; n < count; n++) {
            String id = ids.get(rnd.nextInt(ids.size()));
            switch (rnd.nextInt(8)) {
                case 0, 1, 2 -> ops.add(new Op("class", id, CLASSES[rnd.nextInt(CLASSES.length)], 0));
                case 3 -> ops.add(new Op("text", id, "changed " + "x".repeat(rnd.nextInt(30)), 0));
                case 4 -> {
                    String parent = "g" + (1 + rnd.nextInt(4));
                    String newId = "n" + added++;
                    ids.add(newId);
                    ops.add(new Op("insert", parent, newId, rnd.nextInt(5)));
                }
                case 5 -> {
                    String victim = ids.get(rnd.nextInt(ids.size()));
                    if (victim.startsWith("i") || victim.startsWith("n")) {
                        ops.add(new Op("remove", victim, "", 0));
                        ids.remove(victim);
                    }
                }
                case 6 -> ops.add(new Op("scroll", "g3", String.valueOf(rnd.nextInt(60)), 0));
                default -> ops.add(new Op("style", id, new String[]{"", "margin-top: 11px", "width: 120px", "padding: 5px",
                        "height: 33px", "letter-spacing: 1px"}[rnd.nextInt(6)], 0));
            }
        }
        return ops;
    }

    private static void apply(Document doc, ScrollState scroll, Op op) {
        HtmlNode target = doc.root.getElementById(op.targetId());
        if (target == null) return;
        switch (op.kind()) {
            case "class" -> target.toggleClass(op.arg());
            case "text" -> {
                HtmlNode leaf = target.children.isEmpty() ? target : target.children.get(0);
                if ("#text".equals(leaf.tag)) leaf.setTextContent(op.arg());
            }
            case "style" -> {
                if (op.arg().isEmpty()) target.removeAttribute("style");
                else target.setAttribute("style", op.arg());
            }
            case "scroll" -> scroll.setScroll(target, 0, Float.parseFloat(op.arg()));
            case "insert" -> {
                HtmlNode item = doc.root.getElementById("i1_1");
                if (item == null) return;
                HtmlNode fresh = item.cloneNode(true);
                fresh.setAttribute("id", op.arg());
                for (HtmlNode child : fresh.children) {
                    if (!child.getId().isEmpty()) child.setAttribute("id", op.arg() + "_c");
                }
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

    private static List<DisplayItem> render(Document doc, LayoutBox root, ScrollState scroll) {
        return new DisplayListBuilder(W, H, doc.sheet.keyframes, scroll).render(root);
    }

    private static LayoutBox find(LayoutBox b, String id) {
        if (b.node != null && id.equals(b.node.getId())) return b;
        for (LayoutBox c : b.children) {
            LayoutBox r = find(c, id);
            if (r != null) return r;
        }
        return null;
    }

    /** 作り直した木と比べて、最初に位置・寸法が食い違う箱の説明（診断用）。 */
    private static String firstDiff(LayoutBox live, LayoutBox full) {
        if (live.children.size() != full.children.size()) return live.debugInfo() + " children " + live.children.size() + " vs " + full.children.size();
        for (int i = 0; i < live.children.size(); i++) {
            String d = firstDiff(live.children.get(i), full.children.get(i));
            if (d != null) return d;
        }
        if (live.x != full.x || live.y != full.y || live.w != full.w || live.h != full.h) {
            StringBuilder kids = new StringBuilder();
            for (int i = 0; i < live.children.size(); i++) {
                LayoutBox l = live.children.get(i), f = full.children.get(i);
                kids.append(" [").append(l.node.tag).append('#').append(l.node.getId()).append(" live=(").append(l.x).append(',').append(l.y)
                        .append(',').append(l.w).append(',').append(l.h).append(") frags=").append(l.inlineFragments.size())
                        .append(" full=(").append(f.x).append(',').append(f.y).append(',').append(f.w).append(',').append(f.h)
                        .append(") frags=").append(f.inlineFragments.size()).append(']');
            }
            return live.debugInfo() + " live=(" + live.x + "," + live.y + "," + live.w + "," + live.h + ") full=("
                    + full.x + "," + full.y + "," + full.w + "," + full.h + ") kids:" + kids;
        }
        return null;
    }

    private static void run(long seed, boolean restyleFirst) {
        String source = html();
        run(seed, restyleFirst, source, randomOps(seed, 30));
    }

    private static void run(long seed, boolean restyleFirst, String source, List<Op> ops) {
        Document live = COMPILER.parseDocument(source, "", W, H);
        InteractionState state = new InteractionState();
        ((HtmlDocument) live.root).setInteractionState(state);
        ScrollState liveScroll = new ScrollState();
        LayoutBox root = COMPILER.layoutDocument(live, W, H, Map.of(), liveScroll, FontResolver.FALLBACK, state);

        List<Op> history = new ArrayList<>();
        for (Op op : ops) {
            history.add(op);
            apply(live, liveScroll, op);
            if (restyleFirst) COMPILER.restyleLayoutTree(root, live, W, H, Map.of(), state);
            root = COMPILER.layoutDocument(live, W, H, Map.of(), liveScroll, FontResolver.FALLBACK, state, root);
            List<DisplayItem> incremental = render(live, root, liveScroll);

            Document fresh = COMPILER.parseDocument(source, "", W, H);
            InteractionState freshState = new InteractionState();
            ((HtmlDocument) fresh.root).setInteractionState(freshState);
            ScrollState freshScroll = new ScrollState();
            for (Op past : history) apply(fresh, freshScroll, past);
            LayoutBox full = COMPILER.layoutDocument(fresh, W, H, Map.of(), freshScroll, FontResolver.FALLBACK, freshState);
            List<DisplayItem> expected = render(fresh, full, freshScroll);

            String label = "seed=" + seed + " restyleFirst=" + restyleFirst + " step=" + history.size() + " " + history;
            assertFalse(expected.isEmpty(), label);
            if (expected.size() != incremental.size()) {
                List<String> e = expected.stream().map(d -> d.id() + "@" + d.x() + "," + d.y()).toList();
                List<String> actual = incremental.stream().map(d -> d.id() + "@" + d.x() + "," + d.y()).toList();
                List<String> missing = new ArrayList<>(e);
                missing.removeAll(actual);
                List<String> extra = new ArrayList<>(actual);
                extra.removeAll(e);
                assertEquals(expected.size(), incremental.size(), label + ": 描画要素の数が違う missing="
                        + missing.subList(0, Math.min(8, missing.size())) + " extra=" + extra.subList(0, Math.min(8, extra.size()))
                        + " firstDiff=" + firstDiff(root, full));
            }
            for (int i = 0; i < expected.size(); i++) {
                if (!expected.get(i).equals(incremental.get(i))) {
                    fail(label + ": " + i + " 番目の描画要素が違う\nexpected=" + expected.get(i) + "\nactual  =" + incremental.get(i)
                            + "\nfirstDiff=" + firstDiff(root, full));
                }
            }
        }
    }

    @Test
    public void measureReuseAloneMatchesFullRebuild() {
        LayoutBox.keepPlacements = false;
        try {
            for (long seed = 1; seed <= 150; seed++) {
                run(seed, false);
                run(seed, true);
            }
        } finally {
            LayoutBox.keepPlacements = true;
        }
    }

    @Test
    public void randomLayoutMutationsMatchFullRebuild() {
        long skippedBefore = LayoutBox.layoutsSkipped();
        for (long seed = 1; seed <= 150; seed++) {
            run(seed, false);
            run(seed, true);
        }
        assertTrue(LayoutBox.layoutsSkipped() > skippedBefore, "layout を飛ばす近道が一度も使われていない（検証になっていない）");
    }
}
