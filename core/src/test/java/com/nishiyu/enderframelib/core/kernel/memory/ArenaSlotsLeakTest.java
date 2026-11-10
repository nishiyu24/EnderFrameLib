package com.nishiyu.enderframelib.core.kernel.memory;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.util.Map;

/**
 * 文書に対して作り捨てた {@link InteractionState} が、文書の寿命のあいだ残らないこと。
 * 以前は {@link DocumentArena} の持ち主表が持ち主を強参照しており、文書に対して再スタイルのたびに
 * {@code new InteractionState()} を渡すと、全節ぶんの状態（算出スタイル）が積み上がってヒープが尽き、再スタイルも遅くなっていた。
 */
class ArenaSlotsLeakTest {

    private static WeakReference<InteractionState> restyleWithThrowawayState(UiDocumentCompiler parser, Document doc,
                                                                            LayoutBox root) {
        InteractionState state = new InteractionState();
        parser.restyleLayoutTree(root, doc, 800, 600, Map.of(), state);
        return new WeakReference<>(state);
    }

    @Test
    void throwawayInteractionStatesAreCollectedWhileTheDocumentLives() throws InterruptedException {
        String html = "<html><head><style>.a{color:#123} .a:hover{color:#456}</style></head><body>"
                + "<div class=a>x</div><div class=a>y</div></body></html>";
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument(html, "", 800, 600);
        LayoutBox root = parser.layoutDocument(doc, 800, 600, Map.of(), new ScrollState(), FontResolver.FALLBACK,
                new InteractionState());

        WeakReference<InteractionState> first = restyleWithThrowawayState(parser, doc, root);
        // 以降の状態が最初の状態を押し出さないよう、別の状態を何度か通す
        for (int i = 0; i < 5; i++) restyleWithThrowawayState(parser, doc, root);

        for (int i = 0; i < 50 && first.get() != null; i++) {
            System.gc();
            Thread.sleep(20);
        }
        assertNull(first.get(), "文書が生きていても、手放した InteractionState は回収される");
        assertNotNull(root, "文書側は生きたまま");
    }
}
