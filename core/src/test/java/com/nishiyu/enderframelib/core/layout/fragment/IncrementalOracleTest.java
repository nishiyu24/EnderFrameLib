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

    /** M2b-1: 疑似要素（::before / ::after の付け外し・attr() の値・表示型の切り替え）の変異。 */
    @Test
    void pseudoElements() { run("pseudo"); }

    /**
     * M2b-2: flex の拡張（折り返しと {@code align-content}・column の折り返し・{@code *-reverse}・{@code wrap-reverse}・ベースライン揃え・
     * 主軸と交差軸の auto 余白・{@code order}・{@code visibility: collapse}・abspos の静的位置の揃え）の変異。
     */
    @Test
    void flexFeatures() { run("flexx"); }

    /** M2b-3: グリッド（トラックの定義・配置・揃え・auto-fit・abspos の領域）の変異。 */
    @Test
    void gridFeatures() { run("grid"); }

    /** M2b-1: 置換要素と部品（大きさの属性・選択肢の文字・表示型・比）の変異。属性だけの変化も箱の作り直しとして届くこと。 */
    @Test
    void replacedElements() { run("replaced"); }

    private static void run(String kind) {
        String html = switch (kind) {
            case "large" -> SpikeView.largeList(300);
            case "varied" -> SpikeView.variedList(120);
            case "flex3" -> SpikeView.nestedFlex(3, 3);
            case "text" -> SpikeView.inlineText(24);
            case "pseudo" -> SpikeView.inlineText(16).replace("</style>", PSEUDO_CSS + "</style>");
            case "grid" -> SpikeView.nestedFlex(4, 3, true).replace(".f{display:flex;flex-wrap:wrap;",
                    ".f{display:grid;grid-template-columns:repeat(auto-fill,minmax(60px,1fr));gap:3px;");
            case "replaced" -> SpikeView.inlineText(16).replace("</body>", "<div id=rp><canvas id=rc width=50 height=20></canvas>"
                    + "<img id=ri><input id=rin size=5><select id=rs><option id=ro>x</option></select>"
                    + "<svg id=rv viewBox='0 0 10 5' width=30></svg></div></body>");
            case "flexx" -> SpikeView.nestedFlex(4, 3, true).replace(".f{display:flex;flex-wrap:wrap;", ".f{display:flex;flex-wrap:wrap;height:220px;");
            default -> SpikeView.nestedFlex(4, 2);
        };
        SpikeView v = new SpikeView(html, 640, 480);
        v.frame();
        Random rnd = new Random(kind.hashCode());
        for (int step = 0; step < 80; step++) {
            String op = kind.equals("replaced") && rnd.nextInt(3) > 0 ? mutateReplaced(v, rnd)
                    : kind.equals("grid") && rnd.nextInt(3) > 0 ? mutateGrid(v, rnd)
                    : kind.equals("flexx") && rnd.nextInt(3) > 0 ? mutateFlex(v, rnd)
                    : kind.equals("pseudo") && rnd.nextInt(3) > 0 ? mutatePseudo(v, rnd)
                    : (kind.equals("text") || kind.equals("pseudo")) && rnd.nextBoolean() ? mutateText(v, rnd, step) : mutate(v, rnd, step);
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
            "padding:0 6px;border:2px solid;margin:0 4px", "border-left:12px solid", "padding:3px 9px", "margin-right:30px",
            "text-wrap:balance", "text-wrap:balance;text-align:center",
            "word-break:break-all", "width:min-content", "width:max-content", "width:fit-content", "display:inline-block;width:fit-content",
            "vertical-align:super", "vertical-align:-4px", "display:inline-block;height:30px;vertical-align:middle",
            "display:inline-block;height:40px;vertical-align:top", "display:inline-block;height:25px;vertical-align:bottom",
            "display:contents", "display:contents;font-size:20px",
    };

    private static final String[] FLEX_STYLES = {
            "display:flex;flex-wrap:wrap;align-content:space-between;height:150px", "display:flex;flex-wrap:wrap-reverse;align-content:center",
            "display:flex;flex-flow:column wrap;height:80px", "display:flex;flex-flow:column-reverse wrap-reverse;max-height:90px",
            "display:flex;flex-direction:row-reverse;justify-content:flex-start", "display:flex;align-items:baseline;flex-wrap:wrap",
            "display:flex;align-items:baseline", "align-self:baseline;padding-top:7px", "align-self:flex-end", "align-self:center",
            "margin:auto", "margin-left:auto", "margin-top:auto;margin-bottom:auto", "order:-1", "order:2", "visibility:collapse",
            "position:absolute", "position:absolute;align-self:center", "display:flex;justify-content:center;align-items:flex-end",
            "display:flex;justify-content:safe center;width:30px", "display:flex;align-content:stretch;flex-wrap:wrap;height:200px",
            "flex:1 0 60px", "flex:0 0 120px", "display:flex;direction:rtl;flex-wrap:wrap", "display:inline-flex;align-items:baseline",
    };

    /** flex の拡張の変異を 1 つ加える。 */
    static String mutateFlex(SpikeView v, Random rnd) {
        List<HtmlNode> elems = new ArrayList<>();
        collect(v.doc.root, elems);
        HtmlElement target = (HtmlElement) elems.get(rnd.nextInt(elems.size()));
        String st = FLEX_STYLES[rnd.nextInt(FLEX_STYLES.length)];
        target.setAttribute("style", st);
        return st + " " + target.tag + " " + target.attrs.get("id");
    }

    private static final String[] GRID_STYLES = {
            "display:grid;grid-template-columns:100px 1fr 2fr;gap:4px", "display:grid;grid-template-columns:auto auto;justify-content:center",
            "display:grid;grid-template-rows:20px 40px;grid-auto-flow:column", "display:grid;grid-template-columns:repeat(auto-fit,50px)",
            "display:grid;grid-template-areas:'a b' 'c c';align-content:space-between;height:200px", "grid-area:c", "grid-column:span 2",
            "grid-row:2 / 4", "grid-column:-1", "justify-self:end", "align-self:center", "margin:auto", "position:absolute;grid-column:2",
            "display:grid;position:relative;grid-template-columns:40px 80px", "order:-1", "display:inline-grid;grid-template-columns:30px auto",
            "display:grid;grid-auto-flow:row dense;grid-template-columns:repeat(3,minmax(0,1fr))", "min-height:0;overflow:hidden",
    };

    /** グリッドの変異を 1 つ加える。 */
    static String mutateGrid(SpikeView v, Random rnd) {
        List<HtmlNode> elems = new ArrayList<>();
        collect(v.doc.root, elems);
        HtmlElement target = (HtmlElement) elems.get(rnd.nextInt(elems.size()));
        String st = GRID_STYLES[rnd.nextInt(GRID_STYLES.length)];
        target.setAttribute("style", st);
        return st + " " + target.tag + " " + target.attrs.get("id");
    }

    /** 置換要素の変異を 1 つ加える（属性だけ・スタイル・選択肢の文字・親の表示型）。 */
    static String mutateReplaced(SpikeView v, Random rnd) {
        String[] ids = {"rc", "ri", "rin", "rs", "rv"};
        HtmlElement e = (HtmlElement) v.byId(ids[rnd.nextInt(ids.length)]);
        if (e == null) return "gone";
        switch (rnd.nextInt(6)) {
            case 0 -> e.setAttribute("width", Integer.toString(10 + rnd.nextInt(200)));
            case 1 -> e.setAttribute("height", Integer.toString(10 + rnd.nextInt(90)));
            case 2 -> e.setAttribute("size", Integer.toString(1 + rnd.nextInt(30)));
            case 3 -> e.setAttribute("style", rnd.nextBoolean() ? "display:block;margin:0 auto;width:" + (20 + rnd.nextInt(100)) + "px"
                    : "height:" + (10 + rnd.nextInt(60)) + "px;padding:" + rnd.nextInt(6) + "px");
            case 4 -> {
                HtmlElement o = (HtmlElement) v.byId("ro");
                if (o != null) o.updateTextContent("option " + "w".repeat(rnd.nextInt(20)));
            }
            default -> {
                HtmlElement p = (HtmlElement) v.byId("rp");
                if (p != null) p.setAttribute("style", rnd.nextBoolean() ? "display:flex;flex-wrap:wrap" : "display:grid;grid-template-columns:1fr 1fr");
            }
        }
        return "replaced " + e.tag;
    }

    private static final String PSEUDO_CSS = " .pb::before{content:'[' attr(data-x) ']';color:#900}"
            + " .pa::after{content:' after'} .blk::before{content:'block';display:block;height:12px}"
            + " .inb::after{content:'ib';display:inline-block;padding:2px}";
    private static final String[] PSEUDO_CLASSES = {"", "pb", "pa", "pb pa", "blk", "inb", "blk pa"};

    /** 疑似要素の付け外しと attr() の値の変異を 1 つ加える。 */
    static String mutatePseudo(SpikeView v, Random rnd) {
        List<HtmlNode> elems = new ArrayList<>();
        collect(v.doc.root, elems);
        HtmlElement target = (HtmlElement) elems.get(rnd.nextInt(elems.size()));
        if (rnd.nextBoolean()) {
            String c = PSEUDO_CLASSES[rnd.nextInt(PSEUDO_CLASSES.length)];
            target.setAttribute("class", c);
            return "class=" + c + " " + target.tag;
        }
        String x = Integer.toString(rnd.nextInt(1000));
        target.setAttribute("data-x", x);
        return "data-x=" + x + " " + target.tag;
    }

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
