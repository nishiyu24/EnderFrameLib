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
    void replacedSizingSets() {
        for (String t : List.of("input", "select", "progress")) assertTrue(ElementTraits.isControlSizedByContent(t), t);
        for (String t : List.of("textarea", "button", "img", "meter", "")) assertFalse(ElementTraits.isControlSizedByContent(t), t);
        assertFalse(ElementTraits.isControlSizedByContent(null));
        for (String t : List.of("img", "canvas", "svg")) assertTrue(ElementTraits.isBuiltinReplacedImage(t), t);
        for (String t : List.of("video", "iframe", "picture", "input")) assertFalse(ElementTraits.isBuiltinReplacedImage(t), t);
        for (String t : List.of("img", "canvas", "svg", "video", "iframe")) assertTrue(ElementTraits.isBuiltinReplacedMedia(t), t);
        assertFalse(ElementTraits.isBuiltinReplacedMedia("audio"));
        assertFalse(ElementTraits.isBuiltinReplacedMedia(null));
        for (String t : List.of("span", "a", "img", "input", "button", "br")) assertTrue(ElementTraits.isDefaultInline(t), t);
        for (String t : List.of("div", "p", "textarea", "video", "")) assertFalse(ElementTraits.isDefaultInline(t), t);
    }

    @Test
    void behaviorSets() {
        assertTrue(ElementTraits.hasOpaqueContent("textarea"));
        assertTrue(ElementTraits.hasOpaqueContent("svg"));
        assertFalse(ElementTraits.hasOpaqueContent("input"));
        assertFalse(ElementTraits.hasOpaqueContent(null));
        for (String t : List.of("ol", "fieldset", "details", "textarea", "svg", "select", "button")) {
            assertTrue(ElementTraits.blocksInlineRunFolding(t), t);
        }
        assertFalse(ElementTraits.blocksInlineRunFolding("div"));
        assertFalse(ElementTraits.blocksInlineRunFolding(null));
        for (String t : List.of("button", "select", "summary")) assertTrue(ElementTraits.isClickActivated(t), t);
        assertFalse(ElementTraits.isClickActivated("a"));

        HtmlNode details = el("details");
        assertTrue(ElementTraits.isHiddenByClosedDetails(details, false, el("p")));
        assertFalse(ElementTraits.isHiddenByClosedDetails(details, false, el("summary")));
        assertFalse(ElementTraits.isHiddenByClosedDetails(details, true, el("p")), "開いていれば隠さない");
        assertFalse(ElementTraits.isHiddenByClosedDetails(el("div"), false, el("p")), "details 以外は隠さない");

        assertTrue(ElementTraits.isNaturallyFocusable(el("input")));
        assertFalse(ElementTraits.isNaturallyFocusable(el("input", "type", "HIDDEN")));
        assertTrue(ElementTraits.isNaturallyFocusable(el("summary")));
        assertTrue(ElementTraits.isNaturallyFocusable(el("a", "href", "#")));
        assertFalse(ElementTraits.isNaturallyFocusable(el("a")));
        assertFalse(ElementTraits.isNaturallyFocusable(el("div")));
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

    /** レイアウトの置換要素の判定（img/svg/canvas・input/select/progress の列挙）を表の外で書き直さない。 */
    @Test
    void noDuplicatedReplacedEnumerationInLayout() throws IOException {
        Path root = Path.of("core/src/main/java/com/nishiyu/enderframelib/core/layout");
        if (!Files.isDirectory(root)) root = Path.of("src/main/java/com/nishiyu/enderframelib/core/layout");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : (Iterable<Path>) s.filter(f -> f.toString().endsWith(".java"))::iterator) {
                if (p.getFileName().toString().equals("ReplacedElements.java")) continue; // 置換要素の台帳そのもの
                List<String> lines = Files.readAllLines(p);
                for (int i = 0; i < lines.size(); i++) {
                    String l = lines.get(i);
                    boolean media = l.contains("\"svg\"") && l.contains("\"canvas\"") && l.contains("\"img\"");
                    boolean control = l.contains("\"input\"") && l.contains("\"select\"") && l.contains("\"progress\"");
                    if (media || control) offenders.add(p + ":" + (i + 1));
                }
            }
        }
        assertEquals(List.of(), offenders, "置換要素の分類は ElementTraits を使う");
    }
}
