package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 有界の全列挙による安全性の確認（ENGINE_REARCHITECTURE_PLAN C7 ④）。ランダムなファズは「見つからなかった」しか言えないが、
 * ここは小さな文書と有限の操作の集合について、<b>長さ {@value #DEPTH} 以下の全ての操作の列</b>を数え上げ、各手の後で、
 * 記憶を使う描画の結果が、記憶なしの新しい描画器の結果と項目ごとに同じことを確かめる。範囲の中では「見つからなかった」でなく
 * 「ない」と言える〔小さな範囲の仮説: 記憶の取り違えは、少数の操作の組合せで起きる〕。
 *
 * <p>スクロールは、実際のホイール経路〔バックエンドの作業単位を通る〕でないと状態が揃わないので、この集合に含めない〔ファズの scroll モードが見張る〕。</p>
 * <p>操作は、描画の入力の種類ごとに 1 つ以上: クラス・インラインスタイル〔スタイルの印〕、文字〔文字の断片〕、子の増減〔構造〕、
 * 表示の切替・位置指定〔配置〕、属性。何もしない手〔時間だけが進む〕も含める。</p>
 */
class BoundedModelCheckTest {
    static final int DEPTH = 4;

    private static final String HTML = """
            <style>
              body { margin: 0; font-size: 14px }
              .card { margin: 3px; padding: 4px; border: 1px solid #888 }
              .hot { background: #fdd; border-radius: 8px }
              .big { font-size: 20px }
              .rel { position: relative; top: 2px }
              .sc { height: 30px; overflow: auto }
              .fl::first-line { color: #a00 }
              .ab { position: absolute; left: 5px; top: 5px }
              span.b { font-weight: bold; text-shadow: 1px 1px 2px #000 }
            </style>
            <div id=a class=card>alpha <span id=s class=b>bold</span> tail words that wrap</div>
            <div id=b class=card><div id=b1>b1</div></div>
            <p id=p>first line of a paragraph that wraps over a couple of lines in the narrow box</p>
            <div id=sc class="card sc"><div id=r1>r1</div><div>r2</div><div>r3</div><div>r4</div></div>
            """;

    /** 操作: 名前と、見に入る手の中身。 */
    private record Op(String name, java.util.function.Consumer<StandaloneView> run) {}

    private static int made;

    private static HtmlElement el(StandaloneView v, String text) {
        HtmlElement e = (HtmlElement) v.root().ownerDocument.createElement("div");
        e.setAttribute("id", "m" + (++made));
        e.setTextContent(text);
        return e;
    }

    private static void toggleClass(StandaloneView v, String id, String cls) {
        HtmlNode n = v.root().getNodeById(id);
        String cur = n.getAttribute("class") == null ? "" : n.getAttribute("class");
        List<String> parts = new ArrayList<>(List.of(cur.split("\\s+")));
        parts.removeIf(String::isEmpty);
        if (!parts.remove(cls)) parts.add(cls);
        n.setAttribute("class", String.join(" ", parts));
    }

    private static void toggleStyle(StandaloneView v, String id, String css) {
        HtmlNode n = v.root().getNodeById(id);
        n.setAttribute("style", css.equals(n.getAttribute("style")) ? "" : css);
    }

    private static List<Op> alphabet() {
        List<Op> ops = new ArrayList<>();
        ops.add(new Op("noop", v -> {}));
        ops.add(new Op("a.hot", v -> toggleClass(v, "a", "hot")));
        ops.add(new Op("a.big", v -> toggleClass(v, "a", "big")));
        ops.add(new Op("a.width", v -> toggleStyle(v, "a", "width:80px")));
        ops.add(new Op("s.text", v -> {
            HtmlNode s = v.root().getNodeById("s");
            s.setTextContent("bold".equals(s.getTextContent()) ? "bolder text" : "bold");
        }));
        ops.add(new Op("b.append", v -> v.root().getNodeById("b").appendChild(el(v, "new " + made))));
        ops.add(new Op("b.removeLast", v -> {
            HtmlNode b = v.root().getNodeById("b");
            if (b.children.size() > 1) b.removeChild(b.children.get(b.children.size() - 1));
        }));
        ops.add(new Op("b.hide", v -> toggleStyle(v, "b", "display:none")));
        ops.add(new Op("b.rel", v -> toggleClass(v, "b", "rel")));
        ops.add(new Op("p.firstLine", v -> toggleClass(v, "p", "fl")));
        ops.add(new Op("r1.abs", v -> toggleClass(v, "r1", "ab")));
        ops.add(new Op("a.title", v -> v.root().getNodeById("a").setAttribute("title", "t" + (made++ % 2))));
        return ops;
    }

    private static String sig(DisplayItem i) {
        return i.id() + "|" + i.baseId() + "|" + i.type() + "|" + i.x() + "," + i.y() + "," + i.width() + "," + i.height()
                + "|" + i.text() + "|" + i.color() + "|" + i.bgColor() + "|" + i.radius() + "|" + i.opacity()
                + "|" + i.translateX() + "," + i.translateY() + "," + i.scaleX() + "," + i.scaleY() + "," + i.rotate()
                + "|" + i.events() + "|" + i.meta();
    }

    private static List<String> sigs(List<DisplayItem> items) {
        List<String> out = new ArrayList<>();
        for (DisplayItem i : items) out.add(sig(i));
        return out;
    }

    /** 記憶を使う描画と、記憶なしの描画が、項目ごとに同じか。違えば説明、同じなら null。 */
    private static String firstDifference(StandaloneView view) {
        var ui = view.ui();
        ui.flushInvalidation();
        List<String> memoized = sigs(ui.relativeElements());
        List<String> expected = sigs(V3Fresh.displayList(ui));
        if (expected.size() != memoized.size()) return "項目の数 " + memoized.size() + " != " + expected.size();
        for (int i = 0; i < expected.size(); i++) if (!expected.get(i).equals(memoized.get(i))) return "項目 " + i + " 違う所: " + around(memoized.get(i), expected.get(i));
        return null;
    }

    /** 2 つの文字列が最初に違う所の前後（記憶あり / 記憶なし）。 */
    private static String around(String a, String b) {
        int k = 0;
        while (k < a.length() && k < b.length() && a.charAt(k) == b.charAt(k)) k++;
        int from = Math.max(0, k - 60);
        return "記憶あり=…" + a.substring(from, Math.min(a.length(), k + 80)) + " / 記憶なし=…" + b.substring(from, Math.min(b.length(), k + 80));
    }

    private long sequences;
    private long steps;
    private final List<String> failures = new ArrayList<>();

    private void enumerate(List<Op> ops, List<Integer> prefix) {
        if (!prefix.isEmpty()) check(ops, prefix);
        if (prefix.size() == DEPTH || failures.size() >= 5) return;
        for (int i = 0; i < ops.size(); i++) {
            prefix.add(i);
            enumerate(ops, prefix);
            prefix.remove(prefix.size() - 1);
        }
    }

    /** 列を新しい画面で先頭から実行する。手ごとに、記憶ありと記憶なしを比べる。 */
    private void check(List<Op> ops, List<Integer> seq) {
        sequences++;
        StandaloneView view = new StandaloneView(HTML, 320, 240, FontResolver.FALLBACK);
        try {
            String d = firstDifference(view); // 初回
            if (d != null) failures.add("初回: " + d);
            StringBuilder path = new StringBuilder();
            for (int idx : seq) {
                Op op = ops.get(idx);
                path.append(op.name()).append(' ');
                op.run().accept(view);
                steps++;
                String diff = firstDifference(view);
                if (diff != null) {
                    failures.add("[" + path.toString().trim() + "] " + diff);
                    return;
                }
            }
        } finally {
            view.close();
        }
    }

    @Test
    void memoizedPaintEqualsFreshPaintForEveryShortSequence() {
        List<Op> ops = alphabet();
        enumerate(ops, new ArrayList<>());
        System.out.println("[model-check] ops=" + ops.size() + " depth=" + DEPTH + " sequences=" + sequences + " steps=" + steps);
        assertTrue(failures.isEmpty(), "記憶を使う描画が、記憶なしの描画と違う: " + failures);
        long expected = 0, p = 1;
        for (int d = 1; d <= DEPTH; d++) { p *= ops.size(); expected += p; }
        assertEquals(expected, sequences, "全ての列を数え上げたか");
    }
}
