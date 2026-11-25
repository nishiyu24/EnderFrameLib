package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.common.DocumentLoader;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.box.StyleSource;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.dom.node.HtmlDocument;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ビューポートの大きさの変化（M2c。{@link FragmentPipeline#restyleWith}）: 全ての要素を解き直すが、箱の木は前回と照合して値が同じ箱を前回の
 * 参照のまま使い、整形は前回の断片木をキャッシュにする。結果は同じ大きさでゼロから組んだものと一致する（{@code vw}・メディアクエリ・
 * 割合の幅を含む）。
 */
class ViewportResizeTest {
    private static final String DOC = "<html><head><style>body{margin:0;font:14px sans-serif} .fix{width:120px;height:30px}"
            + " .rel{width:50%} .vw{font-size:3vw} @media (max-width:500px){.mq{color:red;padding:8px}}"
            + " .side{position:absolute;right:0;top:0;width:10vw;height:20px}</style></head><body>"
            + "<div id=fix class=fix>fixed <b>bold</b></div><div id=rel class=rel>relative width text that wraps around</div>"
            + "<p id=vw class=vw>viewport font</p><div id=mq class=mq>media</div><div id=side class=side></div>"
            + "<ul id=list>" + items(30) + "</ul></body></html>";

    private static String items(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= n; i++) sb.append("<li id=li").append(i).append(">item ").append(i).append("</li>");
        return sb.toString();
    }

    @Test
    void resizeRestylesIncrementallyAndMatchesAFreshBuild() {
        int h = 480;
        Document doc = DocumentLoader.parseDocument(DOC, null, 640, h);
        InteractionState state = new InteractionState();
        ((HtmlDocument) doc.root).setInteractionState(state);
        FragmentPipeline p = new FragmentPipeline(new StyleSource(doc.sheet, state, null, 640, h), new LayoutSession(FontResolver.FALLBACK));
        state.changeLedger().freeze();
        p.frame(doc.root, state.changeLedger(), 640, h);
        BoxNode fixBefore = boxOf(p.boxRoot(), "fix");
        BoxNode li7Before = boxOf(p.boxRoot(), "li7");
        for (int w : new int[]{480, 400, 640, 700, 700, 300}) {
            boolean rulesChanged = doc.sheet.updateMediaEnvironment(doc.sheet.mediaEnvironment().withSize(w, h)); // UiView.recomputeLayout と同じ
            p.restyleWith(new StyleSource(doc.sheet, state, null, w, h));
            state.changeLedger().freeze();
            LayoutFragment inc = p.frame(doc.root, state.changeLedger(), w, h);
            assertFalse(p.compiler().stats().full(), "w=" + w + " は前回の木と照合する");
            int restyled = p.compiler().stats().restyled();
            if (rulesChanged) assertTrue(restyled >= 30, "w=" + w + " は @media が替わったので全ての要素を解き直す: " + p.compiler().stats());
            else assertTrue(restyled <= 6, "w=" + w + " はビューポートに依る要素（vw の 2 つ・ルート要素）だけを解き直す: " + p.compiler().stats());
            LayoutFragment fresh = new FragmentPipeline(new StyleSource(doc.sheet, state, null, w, h), new LayoutSession(FontResolver.FALLBACK))
                    .frame(doc.root, null, w, h);
            assertEquals(SpikeView.dump(fresh), SpikeView.dump(inc), "w=" + w);
        }
        assertSame(fixBefore, boxOf(p.boxRoot(), "fix"), "ビューポートに依らない箱は前回の参照");
        assertSame(li7Before, boxOf(p.boxRoot(), "li7"), "ビューポートに依らない箱は前回の参照");
        int restyled = p.compiler().stats().restyled();
        state.changeLedger().freeze();
        p.frame(doc.root, state.changeLedger(), 700, h);
        assertTrue(p.compiler().stats().restyled() < restyled, "大きさが変わらなければ解き直さない: " + p.compiler().stats());
    }

    private static BoxNode boxOf(BoxNode b, String id) {
        if (b.node != null && id.equals(b.node.attrs.get("id"))) return b;
        for (int i = 0; i < b.children.size(); i++) {
            BoxNode hit = boxOf(b.children.get(i), id);
            if (hit != null) return hit;
        }
        return null;
    }
}
