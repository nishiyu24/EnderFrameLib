package com.nishiyu.enderframelib.core.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.parser.HtmlParser;
import com.nishiyu.enderframelib.core.kernel.Shortcut;
import com.nishiyu.enderframelib.core.layout.model.ConstraintSpace;
import com.nishiyu.enderframelib.core.layout.model.FormattingContext;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.layout.model.Viewport;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.lang.reflect.Field;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 検証モード {@code VERIFY_MEMO}（C0 ②）の感度: 記憶が古ければ赤にし、正しければ状態を変えない。 */
class MeasureVerifierTest {

    private static LayoutContext ctx(long run) {
        return new LayoutContext(new Viewport(400, 400), null, new ScrollState(), FontResolver.FALLBACK, true, run, true, null);
    }

    private record Fixture(LayoutBox parent, LayoutBox child, ConstraintSpace space) {}

    /** 親（block 400px）の下に高さ 24px の子 1 つ。run 1 で測って記憶を作る。 */
    private static Fixture measured() {
        HtmlNode doc = HtmlParser.parse("<div id=p><div id=c></div></div>");
        LayoutBox parent = new LayoutBox(doc.getElementById("p"), new ComputedStyle(Map.of("display", "block", "width", "400px")));
        LayoutBox child = new LayoutBox(doc.getElementById("c"), new ComputedStyle(Map.of("display", "block", "height", "24px")));
        parent.children.add(child);
        parent.elementChildren.add(child);
        parent.linkParents();
        ConstraintSpace space = new ConstraintSpace(400f, Float.NaN, FormattingContext.of(FormattingContext.Display.BLOCK, false, false));
        LayoutBox.markLayoutChanges(parent);
        child.measure(space, ctx(1));
        LayoutBox.clearAllSubtreeMarks(parent);
        return new Fixture(parent, child, space);
    }

    private static void withVerify(boolean on, Runnable body) {
        boolean was = Shortcut.VERIFY_MEMO.on();
        Shortcut.VERIFY_MEMO.set(on);
        try {
            body.run();
        } finally {
            Shortcut.VERIFY_MEMO.set(was);
        }
    }

    @Test
    void correctMemoPassesAndLeavesTheRecordUntouched() {
        Fixture f = measured();
        MeasureOutputs before = f.child().measureMemo.primaryOutputs();
        assertNotNull(before);
        withVerify(true, () -> f.child().measure(f.space(), ctx(2)));
        assertEquals(before, f.child().measureMemo.primaryOutputs(), "検証は記憶を書き換えない");
        assertEquals(24f, f.child().h(), 0.01f);
        assertTrue(f.child().measureMemo.isValid(), "検証の後も記憶は有効のまま");
    }

    @Test
    void staleMemoIsCaught() throws Exception {
        Fixture f = measured();
        Field outH = MeasureMemo.class.getDeclaredField("outH");
        outH.setAccessible(true);
        outH.setFloat(f.child().measureMemo, 999f); // 記憶の出力が入力の変化を取りこぼした状態を作る
        withVerify(true, () -> {
            AssertionError e = assertThrows(AssertionError.class, () -> f.child().measure(f.space(), ctx(2)));
            assertTrue(e.getMessage().contains("測定の記憶"), e.getMessage());
        });
    }

    @Test
    void staleMemoIsSilentWhenVerifyIsOff() throws Exception {
        Fixture f = measured();
        Field outH = MeasureMemo.class.getDeclaredField("outH");
        outH.setAccessible(true);
        outH.setFloat(f.child().measureMemo, 999f);
        withVerify(false, () -> f.child().measure(f.space(), ctx(2)));
        assertEquals(999f, f.child().h(), 0.01f, "検証が無ければ古い記憶がそのまま使われる（検証モードの存在意義）");
    }
}
