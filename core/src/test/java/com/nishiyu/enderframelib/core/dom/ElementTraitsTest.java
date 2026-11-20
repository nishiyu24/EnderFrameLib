package com.nishiyu.enderframelib.core.dom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** S2: 要素の性質の表（{@link ElementTraits}）の振る舞いと、「部品の列挙を表の外へ書かない」ラチェット。 */
class ElementTraitsTest {

    private static HtmlNode el(String tag, String... attrs) {
        HtmlElement e = new HtmlElement(tag);
        for (int i = 0; i < attrs.length; i += 2) e.attrs.put(attrs[i], attrs[i + 1]);
        return e;
    }

    @Test
    void formControlAndValueControlSets() {
        for (String t : List.of("input", "button", "select", "textarea", "INPUT", "Button")) {
            assertTrue(ElementTraits.isFormControl(t), t);
        }
        for (String t : List.of("div", "a", "label", "option", "", "img")) assertFalse(ElementTraits.isFormControl(t), t);
        assertFalse(ElementTraits.isFormControl((String) null));
        assertFalse(ElementTraits.isFormControl((HtmlNode) null));
        assertTrue(ElementTraits.isValueControl("select"));
        assertTrue(ElementTraits.isValueControl("textarea"));
        assertFalse(ElementTraits.isValueControl("button"), "button は value 同期の対象外");
        assertTrue(ElementTraits.isTextEntryTag("input"));
        assertFalse(ElementTraits.isTextEntryTag("select"));
        assertTrue(ElementTraits.isGauge("meter"));
        assertTrue(ElementTraits.isGauge("progress"));
        assertFalse(ElementTraits.isGauge("input"));
    }

    @Test
    void checkableTextAndRadioDependOnType() {
        assertTrue(ElementTraits.isCheckable(el("input", "type", "Checkbox")));
        assertTrue(ElementTraits.isCheckable(el("input", "type", "radio")));
        assertFalse(ElementTraits.isCheckable(el("input", "type", "text")));
        assertFalse(ElementTraits.isCheckable(el("button", "type", "checkbox")));
        assertFalse(ElementTraits.isCheckable(null));

        assertTrue(ElementTraits.isTextControl(el("input")), "type 省略は text");
        assertTrue(ElementTraits.isTextControl(el("input", "type", "PASSWORD")));
        assertTrue(ElementTraits.isTextControl(el("textarea")));
        assertFalse(ElementTraits.isTextControl(el("input", "type", "checkbox")));
        assertFalse(ElementTraits.isTextControl(el("input", "type", "range")));
        assertFalse(ElementTraits.isTextControl(el("select")));

        assertTrue(ElementTraits.isRadio(el("input", "type", "RADIO")));
        assertFalse(ElementTraits.isRadio(el("input", "type", "checkbox")));
        assertFalse(ElementTraits.isRadio(el("div", "type", "radio")));
    }

    /** 4 部品（input・button・select・textarea）の列挙を表の外で書き直さない。直し漏れの元になる。 */
    @Test
    void noDuplicatedFormControlEnumerationOutsideTheTable() throws IOException {
        Path root = Path.of("core/src/main/java");
        if (!Files.isDirectory(root)) root = Path.of("src/main/java");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : (Iterable<Path>) s.filter(f -> f.toString().endsWith(".java"))::iterator) {
                if (p.getFileName().toString().equals("ElementTraits.java")) continue;
                List<String> lines = Files.readAllLines(p);
                for (int i = 0; i < lines.size(); i++) {
                    String l = lines.get(i);
                    if (l.contains("case ")) continue; // switch の case は SelectorMatcher の「無効にできる」別の集合
                    if (l.contains("\"input\"") && l.contains("\"textarea\"") && (l.contains("\"button\"") || l.contains("\"select\""))) offenders.add(p + ":" + (i + 1));
                }
            }
        }
        assertEquals(List.of(), offenders, "フォーム部品の列挙は ElementTraits を使う");
    }
}
