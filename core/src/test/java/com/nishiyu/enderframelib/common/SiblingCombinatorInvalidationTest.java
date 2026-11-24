package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.assertNull;

import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

/**
 * 隣接・一般兄弟結合子〔{@code +}・{@code ~}〕を含む規則は、先行する兄弟の挿入・削除でも一致が変わる。以前は構造の変化の範囲
 * 〔{@code InvalidationSets#forStructureChange}〕に入っておらず、{@code .sib > .row + .row { margin-top: 9px }} で先頭の子を消しても、
 * 新しく先頭になった子が前の余白のまま残った（K5b L2 の調査で、差分でない基準の {@link FrameOracle} と比べて見つかった。以前から存在）。
 */
public class SiblingCombinatorInvalidationTest {
    private static String doc(String rule) {
        StringBuilder sb = new StringBuilder("<style>.row{padding:3px;margin-top:2px;margin-bottom:3px;border-bottom:1px solid #ccc} " + rule
                + "</style><div id=pre>pre</div><div id=sib class=sib>");
        for (int i = 0; i < 6; i++) sb.append("<div class=row id=g").append(i).append(">Item ").append(i).append("</div>");
        return sb.append("</div><div id=post>post</div>").toString();
    }

    private static HtmlElement row(String id) {
        HtmlElement e = new HtmlElement("div");
        e.setAttribute("class", "row");
        e.setAttribute("id", id);
        e.updateTextContent("new " + id);
        return e;
    }

    /** 先に 1 度子の増減を反映した後でも〔要素ごとの算出スタイルの記憶が効く状態で〕、兄弟の規則の結果が古いままにならない。 */
    @Test
    void secondChildListChangeAfterAFlushStillRestylesSiblings() {
        for (String rule : new String[]{".sib > .row + .row { margin-top: 9px }", ".sib > .row:nth-child(2) { margin-top: 9px }"}) {
            try (StandaloneView v = new StandaloneView(doc(rule), 420, 520, FontResolver.FALLBACK)) {
                v.ui().flushInvalidation();
                HtmlNode sib = v.root().getNodeById("sib");
                sib.appendChild(row("x"));
                v.ui().flushInvalidation();
                sib.removeChild(sib.children.get(0));
                v.ui().flushInvalidation();
                assertNull(FrameOracle.firstDifference(v), rule);
                sib.children.get(0).toggleClass("hot");
                sib.children.get(1).removeAttribute("class");
                v.ui().flushInvalidation();
                assertNull(FrameOracle.firstDifference(v), rule + " / 属性");
            }
        }
    }

    @Test
    void removingOrInsertingAPrecedingSiblingRestylesTheFollowingSiblings() {
        String[] rules = {".sib > .row + .row { margin-top: 9px }", ".sib > .row ~ .row { margin-top: 9px }", ".sib .row + .row span { color: red } .sib > .row + .row { padding-left: 10px }"};
        for (String rule : rules) {
            for (int pos : new int[]{0, 1, 3, 5}) {
                for (boolean remove : new boolean[]{true, false}) {
                    try (StandaloneView v = new StandaloneView(doc(rule), 420, 520, FontResolver.FALLBACK)) {
                        v.ui().flushInvalidation();
                        HtmlNode sib = v.root().getNodeById("sib");
                        if (remove) sib.removeChild(sib.children.get(pos));
                        else sib.insertBefore(row("n" + pos), sib.children.get(pos));
                        v.ui().flushInvalidation();
                        assertNull(FrameOracle.firstDifference(v), rule + " / " + (remove ? "remove" : "insert") + " #" + pos);
                    }
                }
            }
        }
    }
}
