package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.assertNull;

import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

/**
 * 親の子の差分〔設計書 L2b: 触れられていない前後の子は前回の箱のまま、間だけ組み直す〕が、最初から作った結果と一致すること。
 * 行内の子〔文字〕に block を足す・番号つき/なしのリスト・長い並びの途中/端の増減を、空白の入る文書と入らない文書で確かめる。
 * 前後の子の先頭・末尾の扱い〔空白の文字が隣の block で落ちる〕を見落とした版で、{@code FrameOracleTest} の乱数列だけが落ちた。
 */
public class ChildDiffTest {
    private static HtmlElement el(String tag, String id) {
        HtmlElement e = new HtmlElement(tag);
        e.setAttribute("id", id);
        e.updateTextContent("n " + id);
        return e;
    }

    private static String rows(String tag, String parent, int n, String sep) {
        StringBuilder sb = new StringBuilder("<" + tag + " id=" + parent + ">");
        for (int i = 0; i < n; i++) sb.append("<li id=").append(parent).append(i).append(">row ").append(i).append("</li>").append(sep);
        return sb.append("</").append(tag).append(">").toString();
    }

    @Test
    void blocksAppendedBesideTextAndAroundLongListsMatchFresh() {
        for (String sep : new String[]{"", "\n  "}) {
            String html = "<style>ul.n{list-style:none;padding:0}li{margin:1px 0}</style><div id=fl><p id=para>text beside</p><div>c</div></div>"
                    + rows("ul class=n", "un", 60, sep).replace("</ul class=n>", "</ul>") + rows("ol", "no", 60, sep) + rows("ul", "dd", 60, sep);
            try (StandaloneView v = new StandaloneView(html, 320, 240, FontResolver.FALLBACK)) {
                v.ui().flushInvalidation();
                HtmlNode para = v.root().getNodeById("para");
                para.updateTextContent(" "); // 空白だけの文字は、隣に block が増えると落ちる
                v.ui().flushInvalidation();
                for (int k = 0; k < 3; k++) {
                    para.appendChild(el("div", "w" + k));
                    v.ui().flushInvalidation();
                    assertNull(FrameOracle.firstDifference(v), "p に block を足す #" + k + " sep=" + sep.length());
                }
                for (String parent : new String[]{"un", "no", "dd"}) {
                    HtmlNode p = v.root().getNodeById(parent);
                    int[] at = {0, 30, p.children.size()};
                    for (int a : at) {
                        HtmlNode ref = a < p.children.size() ? p.children.get(a) : null;
                        p.insertBefore(el("li", parent + "x" + a), ref);
                        v.ui().flushInvalidation();
                        assertNull(FrameOracle.firstDifference(v), parent + " 挿入 @" + a + " sep=" + sep.length());
                    }
                    for (int which = 0; which < 3; which++) {
                        int a = which == 0 ? 0 : which == 1 ? 31 : p.children.size() - 1;
                        p.removeChild(p.children.get(a));
                        v.ui().flushInvalidation();
                        assertNull(FrameOracle.firstDifference(v), parent + " 削除 @" + a + " sep=" + sep.length());
                    }
                }
            }
        }
    }
}
