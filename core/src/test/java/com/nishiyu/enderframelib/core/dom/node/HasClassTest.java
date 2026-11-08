package com.nishiyu.enderframelib.core.dom.node;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** 割り当てなしの {@code hasClass} が、従来の定義（空白で分けた語に含まれるか）と一致すること。 */
class HasClassTest {
    @Test
    void matchesTheSplitDefinitionOnRandomClassStrings() {
        Random rnd = new Random(11);
        String[] words = {"a", "ab", "b", "card", "on", "x-1", "ab"};
        String[] seps = {" ", "  ", "\t", "\n", " \t "};
        HtmlElement e = new HtmlElement("div");
        for (int t = 0; t < 3000; t++) {
            StringBuilder sb = new StringBuilder(rnd.nextBoolean() ? " " : "");
            int n = rnd.nextInt(5);
            for (int i = 0; i < n; i++) sb.append(words[rnd.nextInt(words.length)]).append(seps[rnd.nextInt(seps.length)]);
            String cls = sb.toString();
            e.setAttribute("class", cls);
            List<String> split = cls.trim().isEmpty() ? List.of() : List.of(cls.trim().split("\\s+"));
            for (String w : new String[]{"a", "ab", "b", "card", "on", "x-1", "c", "", " a ", "abc"}) {
                assertEquals(!w.trim().isEmpty() && split.contains(w.trim()), e.hasClass(w), "class='" + cls + "' w='" + w + "'");
            }
        }
        assertFalse(new HtmlElement("p").hasClass("a"));
        assertFalse(e.hasClass(null));
    }

    /** 番号を持つ節（文書に接続済み）は atom 比較の経路を通る。文字列の定義と一致し、属性の更新にも追随すること。 */
    @Test
    void numberedNodesUseAtomsAndStayInSyncWithTheAttribute() {
        Random rnd = new Random(23);
        String[] words = {"a", "ab", "b", "card", "on", "x-1", "never-seen-before-zq"};
        String[] seps = {" ", "  ", "\t", "\n", " \t "};
        HtmlDocument doc = new HtmlDocument();
        HtmlElement e = new HtmlElement("div");
        doc.appendChild(e);
        assertTrue(e.hasNodeId(), "文書に接続した節は番号を持つ");
        for (int t = 0; t < 3000; t++) {
            StringBuilder sb = new StringBuilder(rnd.nextBoolean() ? " " : "");
            int n = rnd.nextInt(5);
            for (int i = 0; i < n; i++) sb.append(words[rnd.nextInt(words.length - 1)]).append(seps[rnd.nextInt(seps.length)]);
            String cls = sb.toString();
            e.setAttribute("class", cls);
            List<String> split = cls.trim().isEmpty() ? List.of() : List.of(cls.trim().split("\\s+"));
            for (String w : new String[]{"a", "ab", "b", "card", "on", "x-1", "c", "", " a ", "abc", words[words.length - 1]}) {
                assertEquals(!w.trim().isEmpty() && split.contains(w.trim()), e.hasClass(w), "class='" + cls + "' w='" + w + "'");
            }
        }
        assertFalse(e.hasClass(null));
        e.addClass("zz-toggled");
        assertTrue(e.hasClass("zz-toggled"));
        e.removeClass("zz-toggled");
        assertFalse(e.hasClass("zz-toggled"), "removeClass で atom 配列も更新される");
    }
}
