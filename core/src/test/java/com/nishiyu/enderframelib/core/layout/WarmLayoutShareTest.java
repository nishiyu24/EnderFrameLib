package com.nishiyu.enderframelib.core.layout;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 箱の木を一から組み直す（{@code layoutDocument}）とき、同じ {@link InteractionState} を使い続ければ、2 回目以降は
 * 節ごとに覚えた算出スタイルが有効で、カスケードも共有の表（{@code ShareCache}）の照会も起きない。新しい状態で始めると毎回ゼロから解く
 * （最初の 1 回の費用）。実際の画面は状態を持ち続けるので、組み直しの費用は「新しい状態」の数値の約 1/4 になる（計画書 M3 の実測）。
 * {@code PipelineBench.fullLayout} は新しい状態で測る（初回の費用）、{@code fullLayoutWarm} は持ち続ける（定常の費用）。
 */
class WarmLayoutShareTest {

    @Test
    void rebuildingWithTheSameStateDoesNotResolveStylesAgain() throws Exception {
        String html = Files.readString(Path.of("src/main/resources/data/enderframelib/enderframe_ui/showcase.html"), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        UiDocumentCompiler parser = new UiDocumentCompiler();
        var document = parser.parseDocument(html, "", 800, 600);
        InteractionState state = new InteractionState();
        parser.layoutDocument(document, 800, 600, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);
        int hits1 = state.styleShare.hits(), misses1 = state.styleShare.misses();
        parser.layoutDocument(document, 800, 600, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);
        int newHits = state.styleShare.hits() - hits1, newMisses = state.styleShare.misses() - misses1;
        System.out.println("WARM first: hits=" + hits1 + " misses=" + misses1 + "  second: hits=" + newHits + " misses=" + newMisses);
        assertEquals(0, newMisses, "2 回目は解き直さない");
        assertEquals(0, newHits, "2 回目は共有の表も引かない（節ごとの結果がそのまま有効）");
        assertTrue(misses1 > 50, "1 回目は解いた: " + misses1);

        // 新しい状態から始めると、また最初から解く
        InteractionState fresh = new InteractionState();
        parser.layoutDocument(document, 800, 600, Map.of(), new ScrollState(), FontResolver.FALLBACK, fresh);
        assertTrue(fresh.styleShare.misses() > 0);
    }
}
