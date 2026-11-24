package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.HtmlText;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * M2a 判定基準 5（出力等価性）: 増分（{@link com.nishiyu.enderframelib.core.box.BoxTreeCompiler} の参照共有 ＋ 断片木のキャッシュ）の結果が、
 * 同じ DOM をゼロから作った結果（FrameOracle 相当の独立の面）と、全ての断片の座標・寸法・行の折り返しで 100% 一致すること。
 * 種を固定したランダムな DOM の変異（挿入・削除・文字・非継承／継承のスタイル・クラス）を各フレームで照合する。
 */
class IncrementalOracleTest {

    @Test
    void largeList() { run("large"); }

    @Test
    void variedList() { run("varied"); }

    @Test
    void nestedFlexDepth3() { run("flex3"); }

    @Test
    void nestedFlexDepth4() { run("flex4"); }

    private static void run(String kind) {
        String html = switch (kind) {
            case "large" -> SpikeView.largeList(300);
            case "varied" -> SpikeView.variedList(120);
            case "flex3" -> SpikeView.nestedFlex(3, 3);
            default -> SpikeView.nestedFlex(4, 2);
        };
        SpikeView v = new SpikeView(html, 640, 480);
        v.frame();
        Random rnd = new Random(kind.hashCode());
        for (int step = 0; step < 80; step++) {
            String op = mutate(v, rnd, step);
            LayoutFragment inc = v.frame();
            List<String> a = SpikeView.dump(inc), b = SpikeView.dump(v.scratch());
            if (!a.equals(b)) {
                for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
                    if (!a.get(i).equals(b.get(i))) {
                        throw new AssertionError(kind + " step " + step + " (" + op + ") 最初の不一致 #" + i
                                + "\n  増分: " + a.get(i) + "\n  全体: " + b.get(i));
                    }
                }
                assertEquals(b.size(), a.size(), kind + " step " + step + " (" + op + ") 断片の数");
            }
        }
    }

    /** ランダムな変異を 1 つ加え、内容を返す（失敗時の手がかり）。 */
    static String mutate(SpikeView v, Random rnd, int step) {
        List<HtmlNode> elems = new ArrayList<>();
        collect(v.doc.root, elems);
        HtmlNode target = elems.get(rnd.nextInt(elems.size()));
        switch (rnd.nextInt(7)) {
            case 0 -> { // 挿入
                HtmlElement e = new HtmlElement(target.parent != null && "ul".equals(target.parent.tag) ? "li" : "div");
                e.updateTextContent("inserted " + step + " word word word");
                if (target.parent == null) return "skip";
                target.parent.insertBefore(e, rnd.nextBoolean() ? target : null);
                return "insert near " + target.tag;
            }
            case 1 -> { // 削除
                if (target.parent == null || "body".equals(target.tag) || "html".equals(target.tag)) return "skip";
                target.parent.removeChild(target);
                return "remove " + target.tag;
            }
            case 2 -> { // 文字
                for (HtmlNode c : target.children) {
                    if (c instanceof HtmlText t) {
                        t.setData(rnd.nextBoolean() ? "short" : "a much longer replacement text that should wrap around " + step);
                        return "text in " + target.tag;
                    }
                }
                return "no text";
            }
            case 3 -> { // 非継承のスタイル
                ((HtmlElement) target).setAttribute("style", "padding:" + rnd.nextInt(9) + "px;margin-top:" + (rnd.nextInt(12) - 3) + "px");
                return "box style " + target.tag;
            }
            case 4 -> { // 継承のスタイル
                ((HtmlElement) target).setAttribute("style", "font-size:" + (10 + rnd.nextInt(14)) + "px");
                return "font-size " + target.tag;
            }
            case 5 -> { // 幅・flex
                ((HtmlElement) target).setAttribute("style", rnd.nextBoolean() ? "width:" + (40 + rnd.nextInt(300)) + "px" : "flex-grow:" + rnd.nextInt(3));
                return "width/grow " + target.tag;
            }
            default -> { // 表示の切り替え
                ((HtmlElement) target).setAttribute("style", rnd.nextBoolean() ? "display:none" : "display:flex;flex-wrap:wrap");
                return "display " + target.tag;
            }
        }
    }

    private static void collect(HtmlNode n, List<HtmlNode> out) {
        if (n.nodeType == NodeType.ELEMENT && n instanceof HtmlElement && !"head".equals(n.tag) && !"style".equals(n.tag)
                && !"html".equals(n.tag)) {
            out.add(n);
        }
        if ("head".equals(n.tag)) return;
        for (HtmlNode c : n.children) collect(c, out);
    }
}
