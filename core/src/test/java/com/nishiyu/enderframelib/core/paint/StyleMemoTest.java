package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.common.SoftwareRendering;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 表示リスト作りは、スタイルだけで決まる解析結果（animation・background の層・角丸・on* ）を算出スタイルごとに覚える。
 * 覚えた結果を使っても、毎回解いたときと同じ表示リストになることを確かめる。
 */
class StyleMemoTest {
    private static final String HTML = "<html><head><style>"
            + "@keyframes pulse { 0% { opacity: .2; transform: scale(.5); } 100% { opacity: 1; transform: scale(1); } }"
            + ".a { width: 80px; height: 40px; animation: pulse 2s ease-in-out infinite; }"
            + ".b { width: 120px; height: 60px; border-radius: 10px 20px 30px 40px / 5px 6px 7px 8px;"
            + "     background: linear-gradient(red, blue), radial-gradient(circle, #fff, #000); background-size: 50% 50%, auto; }"
            + ".c { width: 50px; height: 50px; border-radius: 50%; background: #3b82f6; box-shadow: 0 4px 8px rgba(0,0,0,.4); }"
            + "</style></head><body>"
            + "<div class='a'>anim</div><div class='b'>layers</div><div class='c'></div>"
            + "<div class='a' style='animation-delay:-1s'>again</div></body></html>";

    @Test
    void repeatedBuildsOfTheSameLayoutAreIdentical() {
        var compiler = new com.nishiyu.enderframelib.common.UiDocumentCompiler();
        var doc = compiler.parseDocument(HTML, "", 400, 300);
        var scroll = new com.nishiyu.enderframelib.core.layout.model.ScrollState();
        var root = compiler.layoutDocument(doc, 400, 300, java.util.Map.of(), scroll,
                com.nishiyu.enderframelib.core.text.font.SystemFonts.system(), new com.nishiyu.enderframelib.core.dom.InteractionState());
        List<DisplayItem> first = new DisplayListBuilder(400, 300, doc.sheet.keyframes, scroll).render(root);
        List<DisplayItem> second = new DisplayListBuilder(400, 300, doc.sheet.keyframes, scroll).render(root); // メモを使う
        assertFalse(first.isEmpty());
        assertEquals(first, second);
        assertTrue(first.stream().anyMatch(i -> !i.animDef().isEmpty()), "animation が項目に載る");
    }

    @Test
    void memoizedListEqualsAFreshParse() {
        List<DisplayItem> warm = SoftwareRendering.displayList(HTML, 400, 300);
        SoftwareRendering.displayList(HTML, 400, 300); // 別の文書（別のスタイル）でも結果は同じ
        List<DisplayItem> again = SoftwareRendering.displayList(HTML, 400, 300);
        assertEquals(warm, again);
    }

    @Test
    void returnedCollectionsCanBeMutatedWithoutPoisoningTheMemo() {
        var compiler = new com.nishiyu.enderframelib.common.UiDocumentCompiler();
        var doc = compiler.parseDocument(HTML, "", 400, 300);
        var scroll = new com.nishiyu.enderframelib.core.layout.model.ScrollState();
        var root = compiler.layoutDocument(doc, 400, 300, java.util.Map.of(), scroll,
                com.nishiyu.enderframelib.core.text.font.SystemFonts.system(), new com.nishiyu.enderframelib.core.dom.InteractionState());
        var b = find(root, "b");
        var layers = BoxDecorations.layers(b.style);
        int size = layers.size();
        assertEquals(2, size);
        layers.clear();
        assertEquals(size, BoxDecorations.layers(b.style).size(), "写しを返すので書き換えても覚えた値は変わらない");

        float[] radii = com.nishiyu.enderframelib.core.css.LengthResolver.cornerRadiiXY(b.style, 120, 60, 400, 300);
        float first = radii[0];
        radii[0] = -99f;
        assertEquals(first, com.nishiyu.enderframelib.core.css.LengthResolver.cornerRadiiXY(b.style, 120, 60, 400, 300)[0], 0f);
        // 大きさが変われば別の解（パーセント値）
        float[] other = com.nishiyu.enderframelib.core.css.LengthResolver.cornerRadiiXY(find(root, "c").style, 50, 50, 400, 300);
        float[] bigger = com.nishiyu.enderframelib.core.css.LengthResolver.cornerRadiiXY(find(root, "c").style, 100, 100, 400, 300);
        assertNotEquals(other[0], bigger[0], "50% は箱の大きさに追従する");
    }

    private static com.nishiyu.enderframelib.core.layout.LayoutBox find(com.nishiyu.enderframelib.core.layout.LayoutBox box, String cls) {
        if (box.node != null && cls.equals(box.node.attrs.get("class"))) return box;
        for (var child : box.elementChildren) {
            var hit = find(child, cls);
            if (hit != null) return hit;
        }
        return null;
    }
}
