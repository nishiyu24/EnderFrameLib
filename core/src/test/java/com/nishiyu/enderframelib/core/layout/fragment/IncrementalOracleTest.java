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

    /** M2b-1: リストのマーカー（項目の挿入・削除・start・reversed・value・型・位置）と details の開閉の変異。 */
    @Test
    void listMarkers() { run("lists"); }

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

    /**
     * M2b-4: 表（行の挿入・削除、{@code colspan} / {@code rowspan} の属性だけの変化、枠線の重ね・間隔・固定レイアウト・縦揃え・
     * {@code visibility: collapse}・キャプションの位置・表示型の切り替えで匿名の表の箱が増減する変異）。
     */
    @Test
    void tables() { run("tables"); }

    /**
     * M2b-5: 縦書き（{@code writing-mode} の付け替えで直交フローの根と包む箱ができる・消える、縦書きの中の横書きへ戻す根、根の直下の
     * 位置指定の子の付け外しで軸を入れ替えない部分木が変わる、縦書きの flex・grid、大きさ・余白）の変異。
     */
    @Test
    void writingModes() { run("writing"); }

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
            case "lists" -> lists();
            case "tables" -> tablesDoc();
            case "writing" -> SpikeView.nestedFlex(4, 3, true);
            case "flexx" -> SpikeView.nestedFlex(4, 3, true).replace(".f{display:flex;flex-wrap:wrap;", ".f{display:flex;flex-wrap:wrap;height:220px;");
            default -> SpikeView.nestedFlex(4, 2);
        };
        SpikeView v = new SpikeView(html, 640, 480);
        v.frame();
        Random rnd = new Random(kind.hashCode());
        for (int step = 0; step < 80; step++) {
            String op = kind.equals("replaced") && rnd.nextInt(3) > 0 ? mutateReplaced(v, rnd)
                    : kind.equals("grid") && rnd.nextInt(3) > 0 ? mutateGrid(v, rnd)
                    : kind.equals("tables") && rnd.nextInt(4) > 0 ? mutateTable(v, rnd, step)
                    : kind.equals("flexx") && rnd.nextInt(3) > 0 ? mutateFlex(v, rnd)
                    : kind.equals("writing") && rnd.nextInt(4) > 0 ? mutateWriting(v, rnd)
                    : kind.equals("pseudo") && rnd.nextInt(3) > 0 ? mutatePseudo(v, rnd)
                    : kind.equals("lists") && rnd.nextInt(3) > 0 ? mutateList(v, rnd, step)
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

    private static String tablesDoc() {
        StringBuilder sb = new StringBuilder("<html><head><style>body{margin:8px} td{padding:2px}</style></head><body>");
        sb.append("<table id=t0 style='border-spacing:2px'><caption id=cap>caption text</caption><thead><tr><th>h1</th><th>h2</th><th>h3</th></tr></thead>")
                .append("<tbody id=tb>");
        for (int r = 0; r < 6; r++) {
            sb.append("<tr><td id=c").append(r).append(">cell ").append(r).append("</td><td>").append("word ".repeat(r % 3 + 1))
                    .append("</td><td>").append(r).append("</td></tr>");
        }
        sb.append("</tbody><tfoot><tr><td colspan=3>foot</td></tr></tfoot></table>")
                .append("<table id=t1 style='border-collapse:collapse;border:3px solid'><tr><td id=r0 rowspan=2>a</td><td style='border:1px solid'>b</td></tr>")
                .append("<tr><td style='border:5px solid'>c</td></tr></table>")
                .append("<div id=an><div style='display:table-cell'>anon a</div> <div id=ac style='display:table-cell'>anon b</div>text</div>")
                .append("<div id=dt style='display:table;width:300px'><div style='display:table-row'><div id=dm style='display:table-cell;vertical-align:middle'>m</div>")
                .append("<div style='display:table-cell;height:40px'>tall</div></div></div>")
                .append("<p>inline <span id=it style='display:inline-table'><span style='display:table-cell'>it</span></span> after</p>");
        return sb.append("</body></html>").toString();
    }

    private static final String[] TABLE_STYLES = {
            "border-collapse:collapse", "border-spacing:5px 1px", "table-layout:fixed;width:250px", "width:60%", "vertical-align:bottom",
            "vertical-align:baseline;padding-top:6px", "visibility:collapse", "caption-side:bottom", "display:table-cell", "display:table-row",
            "display:table", "display:inline-table", "border:4px solid", "border:3px hidden", "height:80px", "text-align:right",
            "display:table-caption", "width:120px", "margin:0 auto", "display:none",
    };

    /** 表の変異を 1 つ加える（行の挿入・削除・span の属性・スタイル）。 */
    private static String mutateTable(SpikeView v, Random rnd, int step) {
        HtmlNode tb = v.byId("tb");
        switch (rnd.nextInt(5)) {
            case 0 -> {
                if (tb == null) return "skip";
                HtmlElement tr = new HtmlElement("tr");
                HtmlElement td = new HtmlElement("td");
                td.updateTextContent("new " + step);
                tr.appendChild(td);
                List<HtmlNode> rows = new ArrayList<>();
                for (HtmlNode c : tb.children) if (c instanceof HtmlElement) rows.add(c);
                tb.insertBefore(tr, rows.isEmpty() ? null : rows.get(rnd.nextInt(rows.size())));
                return "insert tr";
            }
            case 1 -> {
                if (tb == null) return "skip";
                List<HtmlNode> rows = new ArrayList<>();
                for (HtmlNode c : tb.children) if (c instanceof HtmlElement) rows.add(c);
                if (rows.isEmpty()) return "skip";
                tb.removeChild(rows.get(rnd.nextInt(rows.size())));
                return "remove tr";
            }
            case 2 -> {
                HtmlElement c = (HtmlElement) v.byId(rnd.nextBoolean() ? "c" + rnd.nextInt(6) : "r0");
                if (c == null) return "skip";
                String a = rnd.nextBoolean() ? "colspan" : "rowspan";
                c.setAttribute(a, Integer.toString(rnd.nextInt(4)));
                return a + " " + c.attrs.get("id");
            }
            default -> {
                List<HtmlNode> elems = new ArrayList<>();
                collect(v.doc.root, elems);
                HtmlElement target = (HtmlElement) elems.get(rnd.nextInt(elems.size()));
                String st = TABLE_STYLES[rnd.nextInt(TABLE_STYLES.length)];
                target.setAttribute("style", st);
                return st + " " + target.tag + " " + target.attrs.get("id");
            }
        }
    }

    private static String lists() {
        StringBuilder sb = new StringBuilder("<html><head><style>body{margin:8px} li{padding:1px}</style></head><body>");
        for (int l = 0; l < 4; l++) {
            sb.append(l % 2 == 0 ? "<ol id=l" : "<ul id=l").append(l).append(l == 2 ? " start=4" : "").append(">");
            for (int i = 0; i < 6 + l; i++) sb.append("<li>item ").append(l).append('.').append(i).append(i == 2 ? "<ol><li>sub</li><li>sub2</li></ol>" : "").append("</li>");
            sb.append(l % 2 == 0 ? "</ol>" : "</ul>");
        }
        for (int d = 0; d < 3; d++) sb.append("<details id=d").append(d).append(d == 1 ? " open" : "").append("><summary>sum ").append(d)
                .append("</summary><p>body ").append(d).append("</p></details>");
        return sb.append("</body></html>").toString();
    }

    private static final String[] LIST_STYLES = {"list-style-type:upper-roman", "list-style-position:inside", "list-style-type:none",
            "list-style-type:square", "display:block", "display:none", "list-style-type:decimal-leading-zero;font-size:20px"};

    /** リストの変異（項目の挿入・削除・属性・スタイル・details の開閉）。 */
    private static String mutateList(SpikeView v, Random rnd, int step) {
        HtmlNode list = v.byId("l" + rnd.nextInt(4));
        if (list == null) return "skip"; // 汎用の変異が消した
        List<HtmlNode> items = new ArrayList<>();
        for (HtmlNode c : list.children) if (c instanceof HtmlElement) items.add(c);
        switch (rnd.nextInt(7)) {
            case 0 -> {
                HtmlElement e = new HtmlElement("li");
                e.updateTextContent("new " + step);
                list.insertBefore(e, items.isEmpty() ? null : items.get(rnd.nextInt(items.size())));
                return "insert li";
            }
            case 1 -> {
                if (items.isEmpty()) return "skip";
                list.removeChild(items.get(rnd.nextInt(items.size())));
                return "remove li";
            }
            case 2 -> {
                ((HtmlElement) list).setAttribute(rnd.nextBoolean() ? "start" : "reversed", String.valueOf(rnd.nextInt(9)));
                return "start/reversed";
            }
            case 3 -> {
                if (items.isEmpty()) return "skip";
                ((HtmlElement) items.get(rnd.nextInt(items.size()))).setAttribute("value", String.valueOf(rnd.nextInt(20)));
                return "value";
            }
            case 4 -> {
                if (items.isEmpty()) return "skip";
                String st = LIST_STYLES[rnd.nextInt(LIST_STYLES.length)];
                ((HtmlElement) items.get(rnd.nextInt(items.size()))).setAttribute("style", st);
                return "item " + st;
            }
            case 5 -> {
                ((HtmlElement) list).setAttribute("style", LIST_STYLES[rnd.nextInt(LIST_STYLES.length)]);
                return "list style";
            }
            default -> {
                HtmlElement d = (HtmlElement) v.byId("d" + rnd.nextInt(3));
                if (d == null) return "skip";
                if (d.attrs.containsKey("open")) d.removeAttribute("open");
                else d.setAttribute("open", "");
                return "toggle details";
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
            "float:left;width:90px;height:45px", "float:right;width:60px", "float:left;width:40%", "overflow:hidden", "clear:both",
            "display:flow-root;margin-top:12px", "margin-top:25px",
            "word-spacing:6px", "word-spacing:-2px;white-space:pre-wrap",
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

    private static final String[] WRITING_STYLES = {
            "writing-mode:vertical-rl;height:120px", "writing-mode:vertical-lr", "writing-mode:sideways-lr;height:80px",
            "writing-mode:sideways-rl", "writing-mode:horizontal-tb", "writing-mode:horizontal-tb;width:60px;height:30px",
            "position:absolute;top:3px;left:5px;width:40px", "position:relative;writing-mode:vertical-rl;height:70px", "",
            "display:flex;writing-mode:vertical-rl;flex-wrap:wrap;height:90px;gap:3px", "display:flex;flex-direction:column;writing-mode:vertical-lr",
            "display:grid;writing-mode:vertical-lr;grid-template-columns:20px 1fr", "display:inline-block;writing-mode:vertical-rl;width:50px",
            "width:30px;height:40px", "margin:2px 7px 0 3px;padding:4px 1px;border-left:3px solid", "direction:rtl;writing-mode:vertical-rl",
    };

    /** 縦書きの変異を 1 つ加える（要素の style を置き換える）。 */
    static String mutateWriting(SpikeView v, Random rnd) {
        List<HtmlNode> elems = new ArrayList<>();
        collect(v.doc.root, elems);
        HtmlElement target = (HtmlElement) elems.get(rnd.nextInt(elems.size()));
        String st = WRITING_STYLES[rnd.nextInt(WRITING_STYLES.length)];
        target.setAttribute("style", st);
        return "writing " + st + " " + target.tag + " " + target.attrs.get("id");
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
            case 3 -> e.setAttribute("style", switch (rnd.nextInt(3)) {
                case 0 -> "display:block;margin:0 auto;width:" + (20 + rnd.nextInt(100)) + "px";
                case 1 -> "height:" + (10 + rnd.nextInt(60)) + "%"; // 容器の高さが確定していれば % の高さの基準になる
                default -> "height:" + (10 + rnd.nextInt(60)) + "px;padding:" + rnd.nextInt(6) + "px";
            });
            case 4 -> {
                HtmlElement o = (HtmlElement) v.byId("ro");
                if (o != null) o.updateTextContent("option " + "w".repeat(rnd.nextInt(20)));
            }
            default -> {
                HtmlElement p = (HtmlElement) v.byId("rp");
                if (p != null) p.setAttribute("style", switch (rnd.nextInt(3)) {
                    case 0 -> "display:flex;flex-wrap:wrap";
                    case 1 -> "height:" + (60 + rnd.nextInt(120)) + "px"; // 行の中の原子の % の高さの基準
                    default -> "display:grid;grid-template-columns:1fr 1fr";
                });
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
