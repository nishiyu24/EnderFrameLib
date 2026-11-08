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
}
