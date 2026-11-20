package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 増分で作った表示リストが、同じ面・同じ時刻で全面の再レイアウトを強いた結果と一致すること（ENGINE_REARCHITECTURE_PLAN A6b）。
 * 乱数の変更列（{@link MutationScenario}）の毎手で比べる。float や位置指定の子の増減を局所パッチで扱うと、兄弟の行の折り返し・描画順が
 * ずれる不具合（layouts 文書の種 2 / 6 / 7 / 8）の回帰テスト。
 */
class IncrementalVsFullTest {

    @AfterEach
    void clear() {
        MutationScenario.STEP_HOOK = null;
    }

    /** 種 1〜{@code seeds}（実際の種は {@code seedMul * n + (seedMul > 1 ? 1 : 0)}）で、増分の結果と全面の結果を毎手比べ、食い違った種の説明を返す（無ければ空）。 */
    private static List<String> mismatches(String name, int seeds, long seedMul) {
        MutationFuzz.Doc doc = MutationFuzz.DOCS.get(name);
        List<String> bad = new ArrayList<>();
        for (long n = 1; n <= seeds; n++) {
            String m = MutationFuzz.firstMismatch(doc, seedMul * n + (seedMul > 1 ? 1 : 0), doc.steps());
            if (m != null) bad.add(m);
        }
        return bad;
    }

    @Test
    void layoutsDocumentMatchesFullRelayoutEveryStep() {
        assertEquals(List.of(), mismatches("layouts", 8, 1));
    }

    @Test
    void cardsDocumentMatchesFullRelayoutEveryStep() {
        assertEquals(List.of(), mismatches("cards", 8, 1));
    }

    /** nested（flex の入れ子）。ブロックの子を足したとき、新しい子の項目がブロック層の末尾（flex アイテムの手前）に入ること。 */
    @Test
    void nestedDocumentMatchesFullRelayoutEveryStep() {
        assertEquals(List.of(), mismatches("nested", 8, 5));
    }

    /**
     * 中身で幅の決まる flex アイテムの子を消すと、アイテムの幅が縮む（40 → 10）。子パッチは後続の箱の位置と祖先の高さしか合わせないので、
     * 以前はアイテム自身の背景・枠線が古い幅のまま残った（§6-108。T3 の差分ファズ nested の種 17）。幅が変わるなら全体の作り直しへ退く。
     */
    @Test
    void removingAChildThatShrinksAFlexItemMatchesFullRelayout() {
        String html = "<style>body{margin:0;font-size:13px}.row{display:flex}.it{border:1px solid #99a;background:#def;min-width:10px}"
                + ".z{width:30px;height:12px}</style><div class=\"row\"><div class=\"it\" id=\"d1\"><div class=\"z\" id=\"z1\"></div></div>"
                + "<div class=\"it\" id=\"d2\">two</div></div>";
        try (StandaloneView v = new StandaloneView(html, 320, 240, com.nishiyu.enderframelib.core.text.model.FontResolver.FALLBACK);
             var be = new MutationScenario.NoDraw()) {
            long now = 1000;
            v.frame(now, be, true);
            java.util.function.Supplier<List<String>> sigs = () -> v.items().stream().map(MutationScenario::sig).toList();
            var d1 = v.root().ownerDocument.getElementById("d1");
            d1.removeChild(v.root().ownerDocument.getElementById("z1"));
            v.frame(now += 16, be, true);
            List<String> incremental = sigs.get();
            v.ui().markInvalidation(UiView.InvalidationLevel.RELAYOUT);
            v.frame(now, be, true);
            assertEquals(sigs.get(), incremental);
        }
    }

    private static void assertIncrementalMatchesFull(String html, java.util.function.Consumer<StandaloneView> change) {
        assertIncrementalMatchesFull(html, v -> { }, change);
    }

    /** {@code setup} を当てて 1 フレーム描いてから {@code change} を当て、増分の結果が全面の作り直しと一致すること。 */
    static void assertIncrementalMatchesFull(String html, java.util.function.Consumer<StandaloneView> setup, java.util.function.Consumer<StandaloneView> change) {
        try (StandaloneView v = new StandaloneView(html, 320, 240, com.nishiyu.enderframelib.core.text.model.FontResolver.FALLBACK);
             var be = new MutationScenario.NoDraw()) {
            long now = 1000;
            v.frame(now, be, true);
            setup.accept(v);
            v.frame(now += 16, be, true);
            change.accept(v);
            v.frame(now += 16, be, true);
            List<String> incremental = v.items().stream().map(MutationScenario::sig).toList();
            v.ui().markInvalidation(UiView.InvalidationLevel.RELAYOUT);
            v.frame(now, be, true);
            assertEquals(v.items().stream().map(MutationScenario::sig).toList(), incremental);
        }
    }

    private static final String CONTENT_SIZED_BODY = "<html style=\"display:flex\"><head><style>body{margin:0;font-size:13px}"
            + ".row{display:flex;gap:4px;padding:3px;border:1px solid #99a}.box{padding:2px 4px;border:1px solid #88a}"
            + ".c2{flex:2 1 40px}.c1{flex:1 1 0;min-width:0}</style></head><body>"
            + "<div class=\"row\" id=\"n1\"><div class=\"box c2\" id=\"a2\"%s>delta <b>epsilon</b></div><div class=\"box c1\" id=\"a3\">z</div></div>"
            // 変える前の body の幅はこの行で決まり、変えた後は n1 の内在幅がこれを追い越す（n1 の高さは変わらない）
            + "<div style=\"width:210px\">w</div></body></html>";

    /**
     * html が flex なので body は中身の幅で決まる flex アイテム。孫の flex-basis を % にすると（不定の基準なので内容の幅に落ちる）、
     * 子の行の（body の幅で測った）外寸は変わらなくても body の内在幅が 210 → 228 に変わる。子の変化を外寸だけで吸収（cutoff）して
     * body の幅が古いまま残った（§6-117。差分ファズ nested の種 339）。全面の再レイアウト（RELAYOUT）も同じ記憶を使い回して 210 になるので、
     * 増分と全面を比べるだけでは見つからない。最初から変えた後の文書で作った面と比べる。
     */
    @Test
    void flexBasisPercentInsideAContentSizedFlexItemResizesTheItem() {
        java.util.function.Function<StandaloneView, List<String>> sigs = v -> v.items().stream().map(MutationScenario::sig).toList();
        List<String> incremental, relayout, expected;
        try (StandaloneView v = new StandaloneView(String.format(CONTENT_SIZED_BODY, ""), 400, 300,
                     com.nishiyu.enderframelib.core.text.model.FontResolver.FALLBACK);
             var be = new MutationScenario.NoDraw()) {
            v.frame(1000, be, true);
            v.root().ownerDocument.getElementById("a2").setAttribute("style", "flex-basis:50%");
            v.frame(1016, be, true);
            incremental = sigs.apply(v);
            v.ui().markInvalidation(UiView.InvalidationLevel.RELAYOUT);
            v.frame(1016, be, true);
            relayout = sigs.apply(v);
        }
        // 比べる相手は、最初から変えた後の文書で作った面（変えた面の後に作る）
        try (StandaloneView fresh = new StandaloneView(String.format(CONTENT_SIZED_BODY, " style=\"flex-basis:50%\""), 400, 300,
                     com.nishiyu.enderframelib.core.text.model.FontResolver.FALLBACK);
             var be = new MutationScenario.NoDraw()) {
            fresh.frame(1000, be, true);
            expected = sigs.apply(fresh);
        }
        assertEquals(expected, incremental, "増分");
        assertEquals(expected, relayout, "全面の再レイアウト");
    }

    /** 前の兄弟の中に float があるとき、足したブロックの項目は float の層より前（ブロックの層の末尾）に入る（§6-109。差分ファズ layouts の種 17）。 */
    @Test
    void blockAddedAfterASiblingWithAFloatGoesBeforeTheFloatLayer() {
        assertIncrementalMatchesFull("<style>body{margin:0}.fl{float:left;width:70px;background:#cfc}.b{background:#def}</style>"
                + "<div id=\"a\" class=\"b\">x<div class=\"fl\">f</div></div>",
                v -> {
                    var doc = v.root().ownerDocument;
                    var add = (com.nishiyu.enderframelib.core.dom.node.HtmlElement) doc.createElement("div");
                    add.setAttribute("id", "z");
                    add.setTextContent("added");
                    doc.getElementById("a").parent.appendChild(add);
                });
    }

    /** flex の箱を普通のインラインへ変えたとき、中の文字が flex の項目だったころの配置の記録を引き継がない（§6-109。種 23・29）。 */
    @Test
    void flexSpanTurnedInlineKeepsItsTextUnderARelativeAncestor() {
        assertIncrementalMatchesFull("<style>body{margin:0}.rel{position:relative;top:2px}.fx{display:flex}</style>"
                + "<div class=\"rel\"><div>333<span id=\"s\" class=\"fx\">n6</span></div></div>",
                v -> ((com.nishiyu.enderframelib.core.dom.node.HtmlElement) v.root().ownerDocument.getElementById("s")).setAttribute("class", ""));
    }

    /** 記憶を持たない箱（表）が子の削除で動くとき、子パッチは全体の作り直しへ退く（§6-109。種 23 の手 19）。 */
    @Test
    void removingABlockMovesAFollowingTable() {
        assertIncrementalMatchesFull("<style>body{margin:0}td{border:1px solid #999}</style><div id=\"w\"><p id=\"p\">para</p><div>x</div></div>"
                + "<table id=\"tb\"><tr><td>a</td><td>bb</td></tr></table>",
                v -> v.root().ownerDocument.getElementById("w").removeChild(v.root().ownerDocument.getElementById("p")));
    }

    /** float の子を足して float が高くなると、後ろの段落の折り返し（高さ）が変わる。子パッチは全体の作り直しへ退く（§6-112。差分ファズ layouts の種 33）。 */
    @Test
    void growingAFloatRewrapsTheFollowingParagraph() {
        assertIncrementalMatchesFull("<style>body{margin:0;width:110px}.fl{float:left;width:70px;background:#fed}p{background:#cfc}</style>"
                + "<div id=\"g\" class=\"fl\"><div>one</div><div>two</div></div><div style=\"padding:9px\"><p>text beside floats that wraps around the floated box for a while</p></div><div>after</div>",
                v -> {
                    var doc = v.root().ownerDocument;
                    var add = (com.nishiyu.enderframelib.core.dom.node.HtmlElement) doc.createElement("div");
                    add.setAttribute("id", "z");
                    add.setTextContent("added");
                    doc.getElementById("g").appendChild(add);
                });
    }

    /**
     * 相対位置の箱は位置指定の層（流れの後ろの兄弟より後）で描く。その子を消して箱が縮むと、先に描いた後ろの兄弟を⑦が動かさない。
     * 子パッチは全体の作り直しへ退く（§6-112。差分ファズ layouts の種 38）。
     */
    @Test
    void shrinkingARelativeBoxMovesTheFollowingSiblings() {
        assertIncrementalMatchesFull("<style>body{margin:0}.rel{position:relative;top:2px;left:3px;padding:8px;background:#def}.b{background:#fed}</style>"
                + "<div id=\"r\" class=\"rel\"><div id=\"a\">alpha beta</div><div>gamma</div></div><div class=\"b\">after</div><p class=\"b\">para</p>",
                v -> v.root().ownerDocument.getElementById("r").removeChild(v.root().ownerDocument.getElementById("a")));
    }

    /** body を消して html の高さが 0 になると、全体の作り直しは html を描かない。子パッチも項目を残さない（§6-112。差分ファズ nested の種 64）。 */
    @Test
    void removingTheLastChildOfAZeroHeightAncestorLeavesNoItems() {
        assertIncrementalMatchesFull("<style>body{margin:0}.box{padding:2px;background:#def}</style><div id=\"only\" class=\"box\">x</div>",
                v -> { // body を消し、html の直下に描かれる子を 1 つ置く
                    var doc = v.root().ownerDocument;
                    var body = doc.getElementById("only").parent;
                    var html = body.parent;
                    html.removeChild(body);
                    var add = (com.nishiyu.enderframelib.core.dom.node.HtmlElement) doc.createElement("div");
                    add.setAttribute("id", "z");
                    add.setAttribute("class", "box");
                    add.setTextContent("z");
                    html.appendChild(add);
                },
                v -> {
                    var z = v.root().ownerDocument.getElementById("z");
                    z.parent.removeChild(z);
                });
    }

    private static final String TABLE_IN_ANON_CELL = "<style>body{margin:0;font-size:13px}.w{width:40%}.p{padding:8px;border:2px solid #555}"
            + "table{border-collapse:collapse}td{border:1px solid #999;padding:2px 4px}</style>"
            + "<table id=\"tb\" class=\"w\"><tr><td>a</td><td class=\"p\">bb</td></tr><tr style=\"display:block\"><td>c<div class=\"p\">n37</div></td></tr></table>";

    /**
     * 表の min-content は、セルを測る前の装飾から求めて行に記憶される（行は自分では測られない）。一から組むと装飾 0 の新しい箱から、増分では前回の装飾から
     * 求めて、匿名のセルの中の匿名の表の幅の下限が食い違った（§6-112。差分ファズ layouts の種 49: 祖先の余白で割合の幅の表が狭くなる）。
     */
    @Test
    void tableInsideAnAnonymousCellKeepsItsMinContentAcrossRelayouts() {
        assertIncrementalMatchesFull(TABLE_IN_ANON_CELL, v -> v.resize(260, 240),
                v -> v.root().ownerDocument.getElementById("tb").parent.setAttribute("style", "margin-left:11px"));
    }

    /** 同じ文書（祖先の余白で狭めた形）を一から組んだとき、匿名のセルは中の匿名の表（セルの padding と中の箱の padding・枠線を含む min-content）より狭くならない。 */
    @Test
    void anonymousCellIsNotNarrowerThanTheTableInsideIt() {
        try (StandaloneView v = new StandaloneView(TABLE_IN_ANON_CELL.replace("<table", "<div style=\"margin-left:11px\"><table"), 260, 240, com.nishiyu.enderframelib.core.text.model.FontResolver.FALLBACK);
             var be = new MutationScenario.NoDraw()) {
            v.frame(1000, be, true);
            com.nishiyu.enderframelib.core.layout.LayoutBox[] innerTable = {null};
            java.util.function.Consumer<com.nishiyu.enderframelib.core.layout.LayoutBox>[] walk = new java.util.function.Consumer[1];
            walk[0] = b -> {
                if (b.isAnonymous && b.display() != null && b.display().inner() == com.nishiyu.enderframelib.core.layout.LayoutBox.Display.Inner.TABLE) innerTable[0] = b;
                for (var c : b.children) walk[0].accept(c);
            };
            walk[0].accept(v.ui().layoutRoot());
            assertNotNull(innerTable[0], "tr{display:block} の中の td は匿名の表に包まれる");
            var cell = innerTable[0].parent.parent; // 匿名の表 → tr（block）→ 匿名のセル
            assertTrue(cell.w() + 0.01f >= innerTable[0].w(), "匿名のセル " + cell.w() + " が中の表 " + innerTable[0].w() + " より狭い");
        }
    }

    /**
     * 消す部分木の子孫に位置指定の箱があると、その項目は後ろの層にあり、消す区間の間に別の位置指定の箱の項目が挟まる。子パッチはそれごと消していた。
     * 区間が途切れていれば全体の作り直しへ退く（§6-113。差分ファズ layouts の種 145）。
     */
    @Test
    void removingASubtreeWithAPositionedDescendantKeepsOtherPositionedItems() {
        assertIncrementalMatchesFull("<style>body{margin:0}.rel{position:relative;left:3px;background:#def}</style>"
                + "<div><span class=\"rel\" id=\"p1\">p1</span></div><div id=\"r\">text <span class=\"rel\">p2</span></div><div>after</div>",
                v -> {
                    var r = v.root().ownerDocument.getElementById("r");
                    r.parent.removeChild(r);
                });
    }

    /**
     * 描かれなくなった箱（tbody を消して 0×0 になった表）は前回の描画順の番号を持ち続ける。子パッチはそれを挿入位置にして、足したブロックを float の層の
     * 後ろへ入れた（§6-113。差分ファズ layouts の種 137・nested の種 131）。
     */
    @Test
    void blockAppendedAfterAnEmptiedTableGoesToTheEndOfTheBlockLayer() {
        assertIncrementalMatchesFull("<style>body{margin:0}.fl{float:left;width:70px;background:#cfc}td{border:1px solid #999}.b{background:#def}</style>"
                + "<div class=\"b\">x<div class=\"fl\">f</div><p class=\"b\">para</p></div><table id=\"tb\"><tbody id=\"tbody\"><tr><td>a</td></tr></tbody></table>",
                v -> {
                    var tbody = v.root().ownerDocument.getElementById("tbody");
                    tbody.parent.removeChild(tbody);
                },
                v -> {
                    var doc = v.root().ownerDocument;
                    var add = (com.nishiyu.enderframelib.core.dom.node.HtmlElement) doc.createElement("div");
                    add.setAttribute("id", "z");
                    add.setAttribute("class", "b");
                    add.setTextContent("added");
                    doc.getElementById("tb").parent.appendChild(add);
                });
    }

    /** 増分の結果が、測定の記憶を全部捨てて組み直した全面（{@link MutationFuzz} の比べる相手）と、最初から変えた後の文書で作った面の両方と一致すること。 */
    private static void assertMatchesRemeasuredAndFresh(String html, java.util.function.Consumer<StandaloneView> change, String finalHtml) {
        java.util.function.Function<StandaloneView, List<String>> sigs = v -> v.items().stream().map(MutationScenario::sig).toList();
        List<String> incremental, remeasured, fresh;
        try (StandaloneView v = new StandaloneView(html, 320, 240, com.nishiyu.enderframelib.core.text.model.FontResolver.FALLBACK);
             var be = new MutationScenario.NoDraw()) {
            v.frame(1000, be, true);
            change.accept(v);
            v.frame(1016, be, true);
            incremental = sigs.apply(v);
            com.nishiyu.enderframelib.core.layout.LayoutBox.invalidateAllMeasurements();
            v.ui().markInvalidation(UiView.InvalidationLevel.RELAYOUT);
            v.frame(1016, be, true);
            remeasured = sigs.apply(v);
        }
        try (StandaloneView v = new StandaloneView(finalHtml, 320, 240, com.nishiyu.enderframelib.core.text.model.FontResolver.FALLBACK);
             var be = new MutationScenario.NoDraw()) {
            v.frame(1000, be, true);
            fresh = sigs.apply(v);
        }
        assertEquals(fresh, incremental, "増分");
        assertEquals(fresh, remeasured, "測定の記憶を捨てた全面");
    }

    private static final String FLOAT_SPAN = "<style>body{margin:0;font-size:13px}.fl{float:left;width:70px;background:#cfc}"
            + ".fx{display:flex;padding:8px;border:2px solid #555}</style><div id=\"w1\">percent <span id=\"w2\"%s>inline<span id=\"z7\" class=\"fx\">n7</span></span></div>";

    /**
     * float の span の中の flex の子の文字は flex の項目として layout で置かれ、「配置を使い回せる」印が残る。span を普通のインラインへ戻すと、文字は
     * 行の流れの中身になるのに、断片を消す走査がその印を見て飛ばし、遅延消去の印が解けずに行が足した断片が捨てられて 0,0 に描かれた（§6-119。
     * 差分ファズ layouts の種 1149 ほか。全面の側だけが誤るので、最初から作った面とも比べる）。
     */
    @Test
    void textOfAFormerFlexItemKeepsItsFragmentsWhenItsSpanStopsFloating() {
        assertMatchesRemeasuredAndFresh(String.format(FLOAT_SPAN, " class=\"fl\""),
                v -> v.root().ownerDocument.getElementById("w2").setAttribute("class", ""),
                String.format(FLOAT_SPAN, " class=\"\""));
    }

    private static com.nishiyu.enderframelib.core.dom.node.HtmlElement newElement(StandaloneView v, String tag, String id, String cls, String text) {
        var add = (com.nishiyu.enderframelib.core.dom.node.HtmlElement) v.root().ownerDocument.createElement(tag);
        add.setAttribute("id", id);
        if (cls != null) add.setAttribute("class", cls);
        add.setTextContent(text);
        return add;
    }

    private static final String FLOAT_ONLY = "<style>body{margin:0;font-size:13px}.fl{float:left;width:70px;background:#cfc}"
            + ".fx{display:flex;padding:8px;border:2px solid #555}</style>";

    /**
     * 親の子に float しか無い（インラインの層の手がかりが無い）ところへ文字を持つ箱を足すと、文字の項目をブロックの層の直後に入れていた。描画は
     * 「ブロック → float → インライン」なので float の前に入った（§6-119。差分ファズ layouts の種 1048: body を消した html）。
     */
    @Test
    void textAddedBesideOnlyAFloatGoesAfterTheFloatLayer() {
        assertIncrementalMatchesFull(FLOAT_ONLY + "<div id=\"p\"><div class=\"fl\">f</div></div>",
                v -> v.root().ownerDocument.getElementById("p").appendChild(newElement(v, "span", "z", "fx", "n17")));
    }

    /** 同じ形で、祖先の兄弟に文字があれば、それを手がかりに子パッチのまま（全体を作り直さずに）正しい位置へ入れる（§6-119）。 */
    @Test
    void textAddedBesideOnlyAFloatUsesTheTextOfTheAncestorsSiblingsAsTheAnchor() {
        long before = com.nishiyu.enderframelib.core.paint.DisplayListBuilder.STAT_CHILD_SLICE_PATCH_HITS.sum();
        assertIncrementalMatchesFull(FLOAT_ONLY + "<p>before</p><div id=\"p\" style=\"height:60px\"><div class=\"fl\">f</div></div><p>after</p>",
                v -> v.root().ownerDocument.getElementById("p").appendChild(newElement(v, "span", "z", "fx", "n17")));
        assertTrue(com.nishiyu.enderframelib.core.paint.DisplayListBuilder.STAT_CHILD_SLICE_PATCH_HITS.sum() > before, "子パッチで済んでいる");
    }

    /**
     * 高さ 0 を指定した親は描かれないので、その中へ足したブロック（はみ出して描かれる）の挿入位置の手がかりにならない。以前は描かれていない親の
     * 番号（-1）からリストの先頭 0（html の背景より前）に入れた。祖先の兄弟へたどって手がかりを探す（§6-119）。
     */
    @Test
    void blockAddedIntoAZeroHeightParentGoesAfterThePrecedingSiblingsBlocks() {
        long before = com.nishiyu.enderframelib.core.paint.DisplayListBuilder.STAT_CHILD_SLICE_PATCH_HITS.sum();
        assertIncrementalMatchesFull("<style>body{margin:0;font-size:13px}.in{background:#def}</style>"
                        + "<div class=\"in\">pre</div><div id=\"p\" style=\"height:0\"><div id=\"e\"></div></div><div class=\"in\">post</div>",
                v -> v.root().ownerDocument.getElementById("p").appendChild(newElement(v, "div", "z", "in", "inner")));
        assertTrue(com.nishiyu.enderframelib.core.paint.DisplayListBuilder.STAT_CHILD_SLICE_PATCH_HITS.sum() > before, "子パッチで済んでいる");
    }

    /** 描かれていなかった親（float しか持たず高さ 0）が、子を足して寸法を持つと描かれる。子パッチは祖先の出現を追わないので全体を作り直す（§6-119）。 */
    @Test
    void parentThatStartsToBeDrawnWhenAChildIsAddedMatchesFullRelayout() {
        assertIncrementalMatchesFull(FLOAT_ONLY + "<p>before</p><div id=\"p\" style=\"background:#eee\"><div class=\"fl\">f</div></div><p>after</p>",
                v -> v.root().ownerDocument.getElementById("p").appendChild(newElement(v, "div", "z", null, "n17")));
    }

    /**
     * 兄弟を消して親の節が上がっても、絶対位置の箱（包含ブロックは画面）は動かない。子パッチの平行移動は「箱が動いていない」と見て飛ばし、
     * 親相対の座標が古い親の原点のまま残った（§6-119。差分ファズ layouts の種 1005: flex にした head を消した）。
     */
    @Test
    void absoluteBoxIsRebasedWhenItsParentMovesButItDoesNot() {
        assertIncrementalMatchesFull("<style>body{margin:0}.abs{position:absolute;right:4px;top:4px;width:50px;height:20px;background:#f99}</style>"
                        + "<div id=\"h\" style=\"height:20px;background:#eee\"></div><div id=\"b\"><p>t</p><div class=\"abs\" id=\"ab\"></div></div>",
                v -> {
                    var h = v.root().ownerDocument.getElementById("h");
                    h.parent.removeChild(h);
                });
    }

    /**
     * インラインの箱（b）の中のブロックは、ブロックの層で描かれる。挿入位置の探索がインラインの箱の中へ降りなかったので見落とし、後ろへ足した
     * 兄弟のブロックがその前に入った（§6-119。差分ファズ nested の種 1295）。
     */
    @Test
    void blockAddedAfterABlockInsideAnInlineGoesAfterIt() {
        assertIncrementalMatchesFull("<style>body{margin:0;font-size:13px}.fx{display:flex;background:#def}</style>"
                        + "<div id=\"n1\"><div id=\"a2\">delta <b>eps<div class=\"fx\">z15</div></b></div></div>",
                v -> v.root().ownerDocument.getElementById("n1").appendChild(newElement(v, "div", "z", "fx", "n19")));
    }

    /**
     * flex の項目（アトミックな箱）の中は「ブロック → インライン」の順に描くので、木の順で最後の子がブロックだと、それは描画順の最後ではない。
     * 後ろへ足した兄弟の文字を、そのブロックの直後（項目の中の文字の前）に入れていた（§6-119。差分ファズ nested の種 811）。
     */
    @Test
    void textAddedAfterAnAtomicItemWhoseLastChildIsABlockGoesAfterTheItemsText() {
        assertIncrementalMatchesFull("<style>body{margin:0;font-size:13px}.row{display:flex}.bar{height:6px;background:#ccc}</style>"
                        + "<div class=\"row\" id=\"n4\"><div id=\"d3\">grow<div class=\"bar\"></div></div></div>",
                v -> v.root().ownerDocument.getElementById("n4").parent.appendChild(newElement(v, "div", "z", null, "n43")));
    }

    /**
     * 外寸が変わらない子の変化を親で吸収する近道（cutoff）を切ったときも、増分の結果が全面と一致すること（A6b ②）。
     */
    @Test
    void layoutsDocumentWithoutCutoffMatchesFullRelayout() {
        boolean saved = com.nishiyu.enderframelib.core.kernel.Shortcut.CUTOFF.on();
        com.nishiyu.enderframelib.core.kernel.Shortcut.CUTOFF.set(false);
        try {
            assertEquals(List.of(), mismatches("layouts", 8, 1));
        } finally {
            com.nishiyu.enderframelib.core.kernel.Shortcut.CUTOFF.set(saved);
        }
    }

    /**
     * 祖先の高さが変わる（子パッチの後のリサイズなど）とき、上下の枠線の太さが負にならないこと（A6b ⑥）。
     * 以前は枠線の RECT の高さに高さの変化量を足して、1px の線が -20 になった（nested・種 4 の手 40）。全面の結果と比べるだけでは
     * 見えない（全面へ強いた時点で直る）ので、項目の大きさを直接調べる。
     */
    @Test
    void borderLinesNeverGetNegativeSizeAfterAncestorResize() {
        List<String> bad = new ArrayList<>();
        for (long seed = 1; seed <= 8; seed++) {
            final long sd = seed;
            MutationScenario.STEP_HOOK = (step, view) -> {
                for (var it : view.items()) if (it.width() < 0 || it.height() < 0) bad.add("nested/" + sd + " 手 " + step + " " + it.id());
            };
            MutationScenario.run(ShortcutMatrixMain.NESTED, ShortcutMatrixMain.NESTED_CLASSES, ShortcutMatrixMain.NESTED_STYLES, 5 * seed + 1, 60, false);
        }
        assertEquals(List.of(), bad.size() > 8 ? bad.subList(0, 8) : bad);
    }

    /**
     * body の背景がキャンバスへ渡る文書で、子孫の :hover が body の背景を変えるとき、ホバーを外したフレームで先頭の bg_root の色が
     * 戻ること（A6b ④。以前は 1 フレーム古い色が残った。bg_root は箱のスライスの外にあり、局所パッチが合わせていなかった）。
     */
    @Test
    void canvasBackgroundFollowsBodyHoverStyleInTheSameFrame() {
        String html = "<style>body{margin:0}body{background:#eef}body:hover{background:#fdd}.r{height:30px}</style><div class=\"r\" id=\"r1\">row</div>";
        try (StandaloneView v = new StandaloneView(html, 320, 240, com.nishiyu.enderframelib.core.text.model.FontResolver.FALLBACK);
             var be = new MutationScenario.NoDraw()) {
            long now = 1000;
            v.frame(now, be, true);
            java.util.function.Supplier<Integer> bg = () -> v.items().stream().filter(i -> i.id().equals("bg_root")).map(i -> i.bgColor()).findFirst().orElse(0);
            int normal = bg.get();
            v.mouseMove(10, 10, be.sessionId());
            v.frame(now += 50, be, true);
            int hovered = bg.get();
            v.mouseMove(10, 235, be.sessionId()); // 行の外（body の余白）へ出ると、body 自身のホバーも外れる文書にするため下の空きへ
            v.mouseMove(330, 100, be.sessionId());
            v.frame(now += 50, be, true);
            assertEquals(true, hovered != normal, "ホバーで背景が変わる前提");
            assertEquals(normal, (int) bg.get());
        }
    }

    private static java.util.Map<String, Object> clipsOf(StandaloneView v, String prefix) {
        java.util.Map<String, Object> out = new java.util.TreeMap<>();
        for (var it : v.items()) if (it.id().startsWith(prefix)) out.put(it.id(), it.meta() == null ? null : it.meta().clipRect());
        return out;
    }

    /**
     * 記憶の無い箱（表のセルは記憶しない。記憶を切った構成ではすべての箱）を :hover の変化で描き直すとき、スクロールする箱の clip が
     * 落ちないこと（A6b ④）。以前は描き直しの clip を祖先の記憶から探し、祖先「自身の」clip（スクロールする箱の外側＝null）を使ったので、
     * 箱の下端をまたぐセルがスクロールする箱の外へはみ出して描かれた（scrolllist・nomemo・種 1 の手 21）。
     */
    @Test
    void repaintedBoxWithoutMemoKeepsScrollContainerClip() {
        String html = "<style>body{margin:0}#sc{margin:4px 8px;width:200px;height:100px;overflow-y:auto}"
                + "table{border-collapse:separate}td{height:40px;width:150px;background:#eef}td:hover{background:#fdd}</style>"
                + "<div id=\"sc\"><table>" + "<tr><td id=\"c0\">0</td></tr><tr><td id=\"c1\">1</td></tr><tr><td id=\"c2\">2</td></tr>"
                + "<tr><td id=\"c3\">3</td></tr></table></div>";
        try (StandaloneView v = new StandaloneView(html, 320, 240, com.nishiyu.enderframelib.core.text.model.FontResolver.FALLBACK);
             var be = new MutationScenario.NoDraw()) {
            long now = 1000;
            v.frame(now, be, true);
            var before = clipsOf(v, "c2");
            assertEquals(true, before.values().stream().allMatch(java.util.Objects::nonNull), "下端をまたぐセルはスクロールする箱で切られる前提: " + before);
            v.mouseMove(50, 95, be.sessionId()); // c2（下端をまたぐ）の上
            v.frame(now += 50, be, true);
            v.mouseMove(300, 230, be.sessionId()); // 外へ
            v.frame(now += 50, be, true);
            var incremental = clipsOf(v, "c2");
            v.ui().markInvalidation(UiView.InvalidationLevel.RELAYOUT);
            v.frame(now, be, true);
            assertEquals(clipsOf(v, "c2"), incremental);
            assertEquals(before, incremental);
        }
    }

    private static List<Integer> scrollListPixels(long seed, boolean scrollNodes) {
        boolean saved = com.nishiyu.enderframelib.core.kernel.Shortcut.SCROLL_CONTENT_NODES.on();
        com.nishiyu.enderframelib.core.kernel.Shortcut.SCROLL_CONTENT_NODES.set(scrollNodes);
        try {
            MutationScenario.run(ShortcutMatrixMain.scrollList(), ShortcutMatrixMain.LIST_CLASSES, ShortcutMatrixMain.LIST_STYLES, seed, 45, true, true);
            return new ArrayList<>(MutationScenario.PIXELS);
        } finally {
            com.nishiyu.enderframelib.core.kernel.Shortcut.SCROLL_CONTENT_NODES.set(saved);
        }
    }

    /**
     * スクロールする箱の中身を 1 つの節にまとめて平行移動する既定と、毎回作り直す経路（{@code noscrollnode}）の画素が毎手一致すること
     * （A6b ⑤）。以前は 3 つの原因で食い違った: (a) 減速の途中のスクロール量が小数で、既定は小数の平行移動（縁がぼける）、作り直しは
     * 整数へ丸めた位置 (b) つまみも小数の平行移動で、角の縁の画素が違った (c) 窓付きの作り直しで、インラインの入れ子の span の箱の
     * x / y が (0, 0) のまま窓の中と判定され、親（画面外の行）が省かれたのにその項目だけが出て、箱の上端に描かれた（種 7 の手 33）。
     */
    @Test
    void scrollListPixelsMatchWithAndWithoutScrollContentNode() {
        List<String> bad = new ArrayList<>();
        for (long seed = 1; seed <= 8; seed++) {
            List<Integer> withNode = scrollListPixels(seed, true), rebuilt = scrollListPixels(seed, false);
            for (int i = 0; i < Math.min(withNode.size(), rebuilt.size()); i++) {
                if (!withNode.get(i).equals(rebuilt.get(i))) { bad.add("scrolllist/" + seed + " 手 " + i); break; }
            }
        }
        assertEquals(List.of(), bad);
    }

    /** ホイールで動き始めたスクロールは、減速の途中も止まった後も整数の px にいる（A6b ⑤。小数だと上の 2 つの経路の画素が違う）。 */
    @Test
    void smoothScrollStaysOnWholePixels() {
        try (StandaloneView v = new StandaloneView(ShortcutMatrixMain.scrollList(), 320, 240, com.nishiyu.enderframelib.core.text.model.FontResolver.FALLBACK);
             var be = new MutationScenario.NoDraw()) {
            long now = 1000;
            v.frame(now, be, true);
            var sc = v.root().ownerDocument.getElementById("sc");
            v.mouseMove(100, 100, be.sessionId());
            List<Float> seen = new ArrayList<>();
            for (double notch : new double[] {-1, -3, 2}) {
                v.scroll(0, notch, be.sessionId());
                for (int i = 0; i < 40; i++) {
                    v.frame(now += 7 + (i % 3) * 5, be, true); // フレームの間隔を揺らす（減速の割合が毎回違う）
                    seen.add(v.ui().scrollState().getScrollY(sc));
                }
            }
            assertEquals(true, seen.stream().anyMatch(y -> y > 0), "スクロールした前提: " + seen);
            assertEquals(List.of(), seen.stream().filter(y -> y != Math.rint(y)).toList());
            assertEquals(80f, seen.get(seen.size() - 1), "最後は止まっている（-1 と -3 で 160px、+2 で 80px 戻る）");
        }
    }

    private static List<Integer> pixelsWithDamage(boolean damage, long seed) {
        return com.nishiyu.enderframelib.core.kernel.Shortcut.with(java.util.Map.of(com.nishiyu.enderframelib.core.kernel.Shortcut.SOFT_DAMAGE, damage), () -> {
            MutationScenario.run(ShortcutMatrixMain.CARDS, ShortcutMatrixMain.CARD_CLASSES, ShortcutMatrixMain.CARD_STYLES, seed, 45, true);
            return new ArrayList<>(MutationScenario.PIXELS);
        });
    }

    /**
     * 差分描画（変わった範囲だけを塗り直す）の画素が、毎フレーム全面で描いた画素と一致すること（A6b ③④）。以前は、項目の増減のフレームで
     * 遷移中の項目が「止まっている」と記録され、次のフレームで遷移の途中の色が残った（cards・種 6 の手 22 と 34）。
     */
    @Test
    void damageRepaintMatchesFullRepaintEveryStep() throws Exception {
        for (long seed : new long[] {6, 4, 8}) {
            assertEquals(pixelsWithDamage(false, seed), pixelsWithDamage(true, seed), "cards/" + seed + "（手ごとの画素の写し）");
        }
    }

    /**
     * 項目の座標が絶対のとき（親相対を切る。GL など）の局所パッチ（後続の箱の座標の反映）で、箱の高さ・幅が負にならず、
     * 全面の結果と一致すること（A6b ⑤。以前は新しい高さを「下端 - 原点」と「上端」の二重引きで求め、枠線の高さが -150 になった）。
     */
    @Test
    void absoluteCoordinatesPatchMatchesFullRelayoutAndNeverGoesNegative() {
        // 親相対の切り替えは文脈の欄（-Denderframe.paint.noparentrelative は起動時に 1 回だけ読む。S1 の EngineFlags）
        var ctx = com.nishiyu.enderframelib.core.EngineContext.current();
        boolean saved = ctx.flags().parentRelativeDisplayItems();
        ctx.updateFlags(f -> f.withParentRelativeDisplayItems(false));
        try {
            List<String> bad = new ArrayList<>();
            for (long seed = 1; seed <= 8; seed++) {
                final long sd = seed;
                MutationScenario.STEP_HOOK = (step, view) -> {
                    for (var it : view.items()) if (it.width() < 0 || it.height() < 0) bad.add("nested/" + sd + " 手 " + step + " " + it.id());
                };
                MutationScenario.run(ShortcutMatrixMain.NESTED, ShortcutMatrixMain.NESTED_CLASSES, ShortcutMatrixMain.NESTED_STYLES, 5 * seed + 1, 60, false);
            }
            assertEquals(List.of(), bad.size() > 8 ? bad.subList(0, 8) : bad);
        } finally {
            ctx.updateFlags(f -> f.withParentRelativeDisplayItems(saved));
        }
    }
}
