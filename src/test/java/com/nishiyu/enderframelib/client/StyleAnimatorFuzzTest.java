package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.client.renderer.DisplayListBuilder;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.dom.node.HtmlDocument;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 動いている最中のスタイルでも、差分レイアウトの結果が作り直したものと一致すること。
 *
 * <p>ランダムな class の付け外しと時刻の進みを当て、各フレームで {@code StyleAnimator} が出した値を、
 * トランジションを持たない新しい文書のインラインスタイルとして書いた描画と比べる。レイアウトに効くプロパティ
 * （幅・余白・フォント）と描画だけのもの（枠の色）が混ざり、境界の中の要素も動く。</p>
 */
public class StyleAnimatorFuzzTest {

    private static final UiDocumentCompiler COMPILER = new UiDocumentCompiler();
    private static final int W = 480;
    private static final int H = 360;
    private static final String[] IDS = {"a", "b", "c", "d", "e"};
    private static final String[] CLASSES = {"wide", "tall", "pad", "hot", "big", "gap"};

    private static String css(boolean transitions) {
        String t = transitions
                ? "transition: width 400ms linear, height 300ms ease, padding-left 500ms linear, margin-top 350ms linear,"
                  + " border-top-color 600ms linear, font-size 450ms linear;"
                : "";
        return "body{margin:6px;font-size:14px} .n{border:2px solid #555;background-color:#202830;" + t + "}"
                + " .n.wide{width:260px} .n.tall{height:70px} .n.pad{padding-left:18px} .n.hot{border-top-color:#ff6000}"
                + " .n.big{font-size:22px} .n.gap{margin-top:12px}"
                + " #c{contain:strict;width:200px;height:60px} #d{overflow:auto;width:220px;height:50px}"
                + " @keyframes slide{from{margin-left:0}to{margin-left:30px}}"
                + (transitions ? " #e{animation:slide 900ms linear infinite alternate}" : "");
    }

    private static String html(boolean transitions) {
        StringBuilder sb = new StringBuilder("<html><head><style>").append(css(transitions)).append("</style></head><body>");
        for (String id : IDS) {
            sb.append("<div id=\"").append(id).append("\" class=\"n\">Item ").append(id)
                    .append(" <span id=\"s").append(id).append("\">span text that wraps around when narrow</span>")
                    .append("<div id=\"in").append(id).append("\" style=\"width:4em\">inner</div></div>");
        }
        sb.append("</body></html>");
        return sb.toString();
    }

    private static List<String> project(List<DisplayItem> items) {
        List<String> out = new ArrayList<>();
        for (DisplayItem d : items) {
            out.add(d.id() + " " + d.x() + "," + d.y() + " " + d.width() + "x" + d.height() + " bg=" + Integer.toHexString(d.bgColor())
                    + " fg=" + Integer.toHexString(d.color()) + " text=" + d.text());
        }
        return out;
    }

    private static void run(long seed) {
        Random rnd = new Random(seed);
        Document doc = COMPILER.parseDocument(html(true), "", W, H);
        InteractionState state = new InteractionState();
        ((HtmlDocument) doc.root).setInteractionState(state);
        ScrollState scroll = new ScrollState();
        long now = 100_000;
        state.setAnimationTime(now);
        LayoutBox root = COMPILER.layoutDocument(doc, W, H, Map.of(), scroll, FontResolver.FALLBACK, state);
        Map<String, String> classes = new java.util.HashMap<>();
        for (String id : IDS) classes.put(id, "n");

        for (int step = 0; step < 30; step++) {
            if (rnd.nextInt(3) != 0) {
                String id = IDS[rnd.nextInt(IDS.length)];
                List<String> tokens = new ArrayList<>(List.of(classes.get(id).split(" ")));
                String c = CLASSES[rnd.nextInt(CLASSES.length)];
                if (!tokens.remove(c)) tokens.add(c);
                classes.put(id, String.join(" ", tokens));
                state.setAnimationTime(now);
                doc.root.getElementById(id).setAttribute("class", classes.get(id));
            }
            now += rnd.nextInt(5) == 0 ? 0 : 20 + rnd.nextInt(380);
            state.advanceAnimations(now);
            state.setAnimationTime(now);
            root = COMPILER.layoutDocument(doc, W, H, Map.of(), scroll, FontResolver.FALLBACK, state, root);
            List<String> actual = project(new DisplayListBuilder(W, H, doc.sheet.keyframes, scroll).render(root));

            // 作り直し: トランジションなしの文書に、いまの class と動いている値をインラインで書く
            Document fresh = COMPILER.parseDocument(html(false), "", W, H);
            InteractionState freshState = new InteractionState();
            ((HtmlDocument) fresh.root).setInteractionState(freshState);
            for (String id : IDS) {
                HtmlNode node = fresh.root.getElementById(id);
                node.setAttribute("class", classes.get(id));
                Map<String, String> values = state.animator().sample(doc.root.getElementById(id), now);
                if (!values.isEmpty()) {
                    StringBuilder style = new StringBuilder();
                    values.forEach((k, v) -> style.append(k).append(':').append(v).append(';'));
                    node.setAttribute("style", style.toString());
                }
            }
            ScrollState freshScroll = new ScrollState();
            LayoutBox full = COMPILER.layoutDocument(fresh, W, H, Map.of(), freshScroll, FontResolver.FALLBACK, freshState);
            List<String> expected = project(new DisplayListBuilder(W, H, fresh.sheet.keyframes, freshScroll).render(full));
            String label = "seed=" + seed + " step=" + step + " now=" + now + " classes=" + classes;
            for (int i = 0; i < Math.min(expected.size(), actual.size()); i++) {
                assertEquals(expected.get(i), actual.get(i), label + " item " + i);
            }
            assertEquals(expected.size(), actual.size(), label + " item count");
        }
    }

    @Test
    public void animatedFramesMatchStaticRebuild() {
        long skippedBefore = LayoutBox.layoutsSkipped();
        for (long seed = 1; seed <= 40; seed++) run(seed);
        assertTrue(LayoutBox.layoutsSkipped() > skippedBefore, "配置の使い回しが一度も働いていない（検証になっていない）");
    }
}
