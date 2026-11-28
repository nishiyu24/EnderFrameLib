package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 列の階層（{@link FrameStructure}。M3b-4c-2b-3b-1）: 列を組む側がシーンの層をたどって書いた親の錨の位置と保持層の範囲。走査器は
 * これを読み、項目の鍵・ID から親を引き直さない。
 */
public class FrameStructureTest {
    private static final int W = 400, H = 300;

    private static StandaloneView view(String body) {
        StandaloneView v = new StandaloneView("<html><body style='margin:0;font:16px/20px monospace'>" + body + "</body></html>", W, H,
                FontResolver.FALLBACK);
        v.frame(1000L, new SoftwareRenderBackend(W, H, 1f), true);
        return v;
    }

    private static FrameComposer.Composed compose(StandaloneView v) {
        return new FrameComposer().compose(v.ui().renderFrame(List.of(), null, 1000L));
    }

    private static int indexOf(List<DisplayItem> items, String id) {
        for (int i = 0; i < items.size(); i++) if (id.equals(items.get(i).id())) return i;
        return -1;
    }

    private static boolean hasAncestor(FrameStructure s, int i, int ancestor) {
        for (int p = s.parent(i), guard = 0; p >= 0 && guard < 256; p = s.parent(p), guard++) if (p == ancestor) return true;
        return false;
    }

    @Test
    public void partsAndChildAnchorsHangUnderTheirBoxAnchor() {
        try (StandaloneView v = view("<div id=a style='transform:rotate(5deg);background:#eee;padding:4px'>"
                + "<div id=b style='background:#f00;width:20px;height:20px'></div></div>")) {
            FrameComposer.Composed c = compose(v);
            List<DisplayItem> items = c.items();
            FrameStructure s = c.structure();
            assertEquals(items.size(), s.size(), "シーンの項目は全部が階層を持つ");
            int a = indexOf(items, "a"), aBg = indexOf(items, "a_bg"), b = indexOf(items, "b"), bBg = indexOf(items, "b_bg");
            assertTrue(a >= 0 && aBg >= 0 && b >= 0 && bBg >= 0, "錨と背景の項目がある");
            assertEquals(ElementType.NODE, items.get(a).type());
            assertEquals(a, s.parent(aBg), "部品の親は自分の箱の錨");
            assertEquals(a, s.parent(b), "子の箱の錨の親は、自分の錨を出す親の箱の錨");
            assertEquals(b, s.parent(bBg));
        }
    }

    @Test
    public void backdropHangsUnderTheDialogAnchorAddedAfterIt() {
        try (StandaloneView v = view("<style>dialog::backdrop{background:rgba(255,0,0,0.5)}</style><dialog id=d>x</dialog>")) {
            ((HtmlElement) v.root().getNodeById("d")).showModal();
            v.frame(1016L, new SoftwareRenderBackend(W, H, 1f), true);
            FrameComposer.Composed c = compose(v);
            List<DisplayItem> items = c.items();
            int backdrop = indexOf(items, "d_backdrop"), dialog = indexOf(items, "d");
            assertTrue(backdrop >= 0 && dialog > backdrop, "::backdrop は dialog の錨より先に描く");
            assertEquals(dialog, c.structure().parent(backdrop), "後から足した dialog の錨を、組み終えたときに親として引く");
        }
    }

    @Test
    public void retainRangeIsExactlyTheContentDescendants() {
        try (StandaloneView v = view("<div id=s style='overflow:auto;height:60px;width:150px;opacity:.9'>"
                + "<p>one</p><p>two</p><p>three</p><p>four</p><p>five</p></div><div id=after style='height:10px;background:#00f'></div>")) {
            FrameComposer.Composed c = compose(v);
            List<DisplayItem> items = c.items();
            FrameStructure s = c.structure();
            assertEquals(1, s.retainCount(), "中身を 1 つの節にまとめた重なりの文脈のコンテナ");
            int node = s.retainNode(0);
            assertEquals("s" + SceneWalker.SCROLL_CONTENT_SUFFIX, items.get(node).id());
            assertEquals(indexOf(items, "s"), s.parent(node), "中身の節の親はコンテナの錨");
            for (int i = 0; i < items.size(); i++) {
                boolean inside = i >= s.retainFrom(0) && i < s.retainTo(0);
                assertEquals(inside, hasAncestor(s, i, node), "範囲は中身の節の子孫だけ: " + items.get(i).id());
            }
            assertTrue(s.retainTo(0) - s.retainFrom(0) > 5, "子孫の項目がある");
        }
    }

    @Test
    public void contentPaintedOutOfOrderIsNotRetained() {
        // 重なりの文脈を作らないコンテナの中身の文字は祖先のインラインの段で描かれ、後ろの兄弟の背景が間に入る（重ねると順が変わる）
        try (StandaloneView v = view("<div id=s style='overflow:auto;height:40px;width:150px'>"
                + "<p style='background:#eee'>one</p><p style='background:#ddd'>two</p><p>three</p><p>four</p></div>"
                + "<div style='height:10px;background:#00f'></div>")) {
            FrameComposer.Composed c = compose(v);
            assertEquals(1, c.contents().size(), "前提: 中身を 1 つの節にまとめた");
            assertEquals(0, c.structure().retainCount(), "子孫が描画順で連続しないので保持層にしない");
        }
    }

    @Test
    public void listOnlyFramesDeriveParentsFromItemKeys() {
        try (StandaloneView v = view("<div id=a style='background:#eee'><div id=b style='background:#f00;height:5px'></div></div>")) {
            List<DisplayItem> items = v.ui().relativeElements();
            FrameStructure s = PreparedFrame.full(RenderFrame.of(items, 0L)).structure();
            assertEquals(items.size(), s.size());
            assertEquals(indexOf(items, "a"), s.parent(indexOf(items, "b")));
            assertEquals(0, s.retainCount(), "列だけのフレームは保持層の範囲を持たない");
        }
    }
}
