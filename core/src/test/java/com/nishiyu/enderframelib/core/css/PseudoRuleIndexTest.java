package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.common.SoftwareRendering;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 疑似要素の規則が 1 つも無い文書では要素ごとに ::before / ::after を解かない。規則があれば従来どおり生成される。 */
class PseudoRuleIndexTest {

    private static boolean hasText(List<DisplayItem> items, String text) {
        return items.stream().anyMatch(i -> text.equals(i.text()));
    }

    @Test
    void generatedContentStillAppearsWhenRulesExist() {
        String html = "<html><head><style>.k::before { content: 'PRE-'; } .k::after { content: '-POST'; }</style></head>"
                + "<body><p class='k'>mid</p></body></html>";
        List<DisplayItem> items = SoftwareRendering.displayList(html, 300, 200);
        assertTrue(hasText(items, "PRE-"), "::before の内容");
        assertTrue(hasText(items, "POST"), "::after の内容");
        assertTrue(items.stream().anyMatch(i -> i.id().contains("_before")));
        assertTrue(items.stream().anyMatch(i -> i.id().contains("_after")));
    }

    @Test
    void onlyTheDeclaredPseudoKindIsGenerated() {
        String html = "<html><head><style>.k::before { content: 'ONLY-BEFORE'; }</style></head>"
                + "<body><p class='k'>mid</p><p>plain</p></body></html>";
        List<DisplayItem> items = SoftwareRendering.displayList(html, 300, 200);
        assertTrue(items.stream().anyMatch(i -> i.id().contains("_before")), "宣言した ::before は生成される");
        assertTrue(items.stream().noneMatch(i -> i.id().contains("_after")), "規則の無い ::after は生成されない");
        assertTrue(hasText(items, "plain"));
    }

    @Test
    void documentsWithoutPseudoRulesRenderNormally() {
        String html = "<html><body><p>one</p><p>two</p></body></html>";
        List<DisplayItem> items = SoftwareRendering.displayList(html, 300, 200);
        assertTrue(items.stream().anyMatch(i -> i.text() != null && i.text().contains("one")));
        assertTrue(items.stream().anyMatch(i -> i.text() != null && i.text().contains("two")));
        assertTrue(items.stream().noneMatch(i -> i.id().endsWith("_before") || i.id().endsWith("_after")));
    }
}
