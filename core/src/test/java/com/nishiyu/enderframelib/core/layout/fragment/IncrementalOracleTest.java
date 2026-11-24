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

    /** M2b-1: 文字の規則（white-space・タブ・両端揃え・direction・text-transform・letter-spacing・内在サイズの語・keep-all）の変異。 */
    @Test
    void inlineText() { run("text"); }

    private static void run(String kind) {
        String html = switch (kind) {
            case "large" -> SpikeView.largeList(300);
            case "varied" -> SpikeView.variedList(120);
            case "flex3" -> SpikeView.nestedFlex(3, 3);
            case "text" -> SpikeView.inlineText(24);
            default -> SpikeView.nestedFlex(4, 2);
        };
        SpikeView v = new SpikeView(html, 640, 480);
        v.frame();
        Random rnd = new Random(kind.hashCode());
        for (int step = 0; step < 80; step++) {
            String op = kind.equals("text") && rnd.nextBoolean() ? mutateText(v, rnd, step) : mutate(v, rnd, step);
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
        switch (rnd.nextInt(10)) {
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
            case 7 -> { // 流れの外
                ((HtmlElement) target).setAttribute("style", "position:absolute;left:" + rnd.nextInt(200) + "px;top:" + rnd.nextInt(90) + "px"
                        + (rnd.nextBoolean() ? ";width:" + (20 + rnd.nextInt(120)) + "px" : ""));
                return "absolute " + target.tag;
            }
            case 9 -> { // float・clear
                ((HtmlElement) target).setAttribute("style", rnd.nextBoolean() ? "float:" + (rnd.nextBoolean() ? "left" : "right") + ";width:" + (30 + rnd.nextInt(150)) + "px"
                        : "clear:both");
                return "float " + target.tag;
            }
            case 8 -> { // 包含ブロック・相対のずれ
                ((HtmlElement) target).setAttribute("style", "position:relative;left:" + (rnd.nextInt(20) - 10) + "px;top:" + rnd.nextInt(8) + "px");
                return "relative " + target.tag;
            }
            default -> { // 表示の切り替え
                int d = rnd.nextInt(3);
                ((HtmlElement) target).setAttribute("style", d == 0 ? "display:none" : d == 1 ? "display:flex;flex-wrap:wrap" : "display:inline-block;padding:2px");
                return "display " + target.tag;
            }
        }
    }

    private static final String[] TEXT_STYLES = {
            "white-space:nowrap", "white-space:pre", "white-space:pre-wrap", "white-space:pre-line", "white-space:break-spaces",
            "text-transform:uppercase", "text-transform:capitalize", "text-align:justify", "text-align:justify;text-align-last:justify",
            "text-align:center", "text-align:end", "direction:rtl", "letter-spacing:3px", "tab-size:3", "word-break:keep-all",
            "word-break:break-all", "width:min-content", "width:max-content", "width:fit-content", "display:inline-block;width:fit-content",
    };

    /** 文字の規則の変異を 1 つ加える。 */
    static String mutateText(SpikeView v, Random rnd, int step) {
        List<HtmlNode> elems = new ArrayList<>();
        collect(v.doc.root, elems);
        HtmlNode target = elems.get(rnd.nextInt(elems.size()));
        if (rnd.nextInt(4) == 0) { // タブ・和文・その他の空白を含む文字へ差し替える
            String[] texts = {"a\tb\t\tc  d", "東京へ行きましょう。次は", "x\u1680y z", "  lead and trail  ", "line1\nline2\n\nline4 " + step};
            for (HtmlNode c : target.children) {
                if (c instanceof HtmlText t) {
                    t.setData(texts[rnd.nextInt(texts.length)]);
                    return "text-content " + target.tag;
                }
            }
            return "no text";
        }
        String st = TEXT_STYLES[rnd.nextInt(TEXT_STYLES.length)];
        ((HtmlElement) target).setAttribute("style", st);
        return st + " " + target.tag;
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
