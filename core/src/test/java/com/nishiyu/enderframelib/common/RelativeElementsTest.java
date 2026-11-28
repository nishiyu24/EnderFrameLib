package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ビューの列は親相対のまま公開し、絶対位置は項目・節ごとに解く（M3b-4b-4b で列全体を絶対座標へ直す {@code elements()} と
 * {@code AbsoluteItems} を削除した）。要素の位置はシーンから引く（M3b-4c-2b-1 で列から ID を引く {@code elementById}・{@code toAbsolute}・
 * {@code nodeItemAbsolute} を削除し、{@code UiView#locate} にした）。
 */
class RelativeElementsTest {
    private static final String HTML = "<html><body style='margin:0'><div style='margin:20px 30px;padding:10px;background:#123'>"
            + "<div style='margin:5px;padding:7px;background:#456'>inner <b>text</b></div></div></body></html>";

    @Test
    void relativeElementsAreRelativeAndResolvePerItem() {
        try (StandaloneView v = new StandaloneView(HTML, 400, 300, FontResolver.FALLBACK);
             SoftwareRenderBackend sw = new SoftwareRenderBackend(400, 300, 1f)) {
            v.frame(0, sw, true);
            List<DisplayItem> rel = v.ui().relativeElements();
            assertTrue(rel.stream().anyMatch(i -> i.type() == ElementType.NODE && i.hints().relativeOrigin()), "親相対の NODE がある");
            // 一番内側の文字は、親相対では原点付近・絶対では箱の位置（20+10+5+7 だけずれる）
            DisplayItem relText = rel.stream().filter(i -> i.type() == ElementType.TEXT && "inner".equals(i.text().trim())).findFirst().orElseThrow();
            List<DisplayItem> flat = TestFlatItems.absolute(rel);
            DisplayItem absText = flat.get(rel.indexOf(relText));
            assertTrue(absText.x() > relText.x() && absText.y() > relText.y(), "絶対座標は相対より親の原点だけ大きい");
            for (int i = 0; i < rel.size(); i++) { // 項目ごとの解決は、列全体を平坦にしたもの（テストの補助）と位置が一致する
                DisplayItem a = DisplayItem.resolveAbsolute(rel, rel.get(i));
                assertEquals(flat.get(i).x(), a.x(), rel.get(i).id());
                assertEquals(flat.get(i).y(), a.y(), rel.get(i).id());
            }
        }
    }

    /**
     * 要素の位置はシーンから引く（{@code UiView#locate}）: 要素の節の項目を列で解いた絶対位置・大きさと一致し、ビューには列から ID を引く口・
     * 列全体を絶対座標へ直す口が無い。
     */
    @Test
    void elementsAreLocatedFromTheSceneAndTheViewHasNoListLookups() {
        String html = "<html><body style='margin:0'><div id='outer' style='margin:20px 30px;padding:10px;background:#123'>"
                + "<div id='inner' style='margin:5px;padding:7px;background:#456;transform:translate(3px,4px)'>inner <b id='b'>text</b></div></div>"
                + "<div id='scroller' style='height:40px;overflow:auto'><div id='tall' style='height:200px'>x</div></div></body></html>";
        try (StandaloneView v = new StandaloneView(html, 400, 300, FontResolver.FALLBACK);
             SoftwareRenderBackend sw = new SoftwareRenderBackend(400, 300, 1f)) {
            v.frame(0, sw, true);
            List<DisplayItem> flat = TestFlatItems.absolute(v.ui().relativeElements());
            int checked = 0;
            for (String id : List.of("outer", "inner", "b", "scroller", "tall")) {
                DisplayItem node = flat.stream().filter(i -> i.type() == ElementType.NODE && id.equals(i.id())).findFirst().orElseThrow();
                var loc = v.ui().locate(id);
                assertNotNull(loc, id);
                assertSame(v.ui().findNode(id), loc.node(), id);
                assertEquals(node.x(), loc.x(), 1f, id + " x");
                assertEquals(node.y(), loc.y(), 1f, id + " y");
                assertEquals(node.width(), loc.w(), 1f, id + " w");
                assertEquals(node.height(), loc.h(), 1f, id + " h");
                checked++;
            }
            assertEquals(5, checked);
            assertNull(v.ui().locate("missing"));
            List<String> names = Arrays.stream(UiView.class.getMethods()).map(java.lang.reflect.Method::getName).toList();
            for (String gone : List.of("elements", "absoluteRenderCache", "elementById", "toAbsolute", "nodeItemAbsolute", "setRenderCache")) {
                assertFalse(names.contains(gone), "列から引く口・列を書き換える口は戻さない: " + gone);
            }
        }
    }
}
