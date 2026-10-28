package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.client.renderer.DisplayListBuilder;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.model.AnimationSpec;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * voidwave.html（スクロールする長いサイト。キーフレームのアニメーションを多用する）が、画面の幅によらずパース・レイアウト・
 * 描画項目の生成まで通り、1 つのスクロール領域になっていて、アニメーションとレイヤー（背景のフィルター）の指定が描画項目に載ること。
 */
public class VoidwaveSiteTest {

    private static final Path PAGE = Path.of("src/main/resources/data/enderframelib/enderframe_ui/voidwave.html");

    private static List<DisplayItem> render(String html, int width, int height) {
        UiDocumentCompiler compiler = new UiDocumentCompiler();
        Document doc = compiler.parseDocument(html, "", width, height);
        ScrollState scrollState = new ScrollState();
        LayoutBox root = compiler.layoutDocument(doc, width, height, Map.of(), scrollState, FontResolver.FALLBACK, new InteractionState());
        assertNotNull(root);
        return new DisplayListBuilder(width, height, doc.sheet.keyframes, scrollState).render(root);
    }

    @Test
    @DisplayName("キーフレームが全部パースされ、スクリプトを含まない")
    void keyframesAreParsedAndThereIsNoScript() throws Exception {
        String html = Files.readString(PAGE, StandardCharsets.UTF_8);
        assertFalse(html.toLowerCase().contains("<script"), "このエンジンに JavaScript は無い");
        Document doc = new UiDocumentCompiler().parseDocument(html, "", 1600, 900);
        for (String name : List.of("float", "floatTilt", "drift1", "drift2", "spin", "spinRev", "pulse", "ring", "beacon", "shimmer", "blink",
                "marquee", "bars", "bounceDown", "fadeUp", "hue", "carousel", "sweepLine", "breathe")) {
            assertTrue(doc.sheet.keyframes.containsKey(name), name + " がパースされていること");
        }
    }

    @Test
    @DisplayName("どの幅でもレイアウト・描画項目の生成が通り、1 つの長いスクロール領域になる")
    void layoutsAtEveryWidthAsOneLongScroller() throws Exception {
        String html = Files.readString(PAGE, StandardCharsets.UTF_8);
        for (int[] size : new int[][]{{1600, 900}, {1280, 720}, {854, 480}, {640, 480}}) {
            List<DisplayItem> items = render(html, size[0], size[1]);
            assertTrue(items.size() > 500, size[0] + "x" + size[1] + ": 描画項目が多数あること (size=" + items.size() + ")");
            double longest = items.stream().filter(i -> i.hints().scrollable()).mapToDouble(i -> i.hints().scrollHeight()).max().orElse(0);
            assertTrue(longest > size[1] * 4,
                    size[0] + "x" + size[1] + ": 画面の数倍の長さのスクロール領域があること (scrollHeight=" + longest + ")");
        }
    }

    @Test
    @DisplayName("アニメーション（移動・回転・拡縮・不透明度・背景色）、背景のフィルター、アイテム画像が描画項目に載る")
    void animationsLayersAndItemImagesArePresent() throws Exception {
        String html = Files.readString(PAGE, StandardCharsets.UTF_8);
        List<DisplayItem> items = render(html, 1600, 900);
        List<DisplayItem> animated = items.stream().filter(i -> i.animDef() != null && !i.animDef().isEmpty()).toList();
        assertTrue(animated.size() >= 80, "アニメーション指定の項目が多数あること (count=" + animated.size() + ")");
        assertTrue(animated.stream().anyMatch(i -> i.animDef().animates(AnimationSpec.CH_TRANSFORM)), "移動・回転・拡縮");
        assertTrue(animated.stream().anyMatch(i -> i.animDef().animates(AnimationSpec.CH_OPACITY)), "不透明度");
        assertTrue(animated.stream().anyMatch(i -> i.animDef().animates(AnimationSpec.CH_BG_COLOR)), "背景色");
        assertTrue(items.stream().anyMatch(i -> i.hints().layer() != null && i.hints().layer().hasBackdrop()),
                "ナビゲーションの backdrop-filter がレイヤーの指定として載ること");
        long itemImages = items.stream().filter(i -> i.type() == ElementType.IMAGE && i.text() != null && i.text().startsWith("item:")).count();
        assertTrue(itemImages >= 20, "アイテム画像が多数あること (count=" + itemImages + ")");
    }

    @Test
    @DisplayName("画像として描画しても落ちない")
    void rendersToAnImage() throws Exception {
        String html = Files.readString(PAGE, StandardCharsets.UTF_8);
        BufferedImage image = EnderFrameStandalone.renderToImage(html, 1280, 720);
        assertNotNull(image);
        assertEquals(1280, image.getWidth());
        assertEquals(720, image.getHeight());
    }

    private static LayoutBox findByClass(LayoutBox b, String cls) {
        if (b.node != null && b.node.attrs != null && (" " + b.node.attrs.getOrDefault("class", "") + " ").contains(" " + cls + " ")) return b;
        for (LayoutBox c : b.children) {
            LayoutBox r = findByClass(c, cls);
            if (r != null) return r;
        }
        return null;
    }

    /** {@code @keyframes} の {@code translateX} の絶対値の最大（動く距離）。 */
    private static float keyframeDistance(Document doc, String name) {
        float max = 0;
        for (var block : doc.sheet.keyframes.get(name).blocks()) {
            String t = block.declarations().get("transform");
            if (t == null) continue;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("translateX\\(-?([0-9.]+)px\\)").matcher(t);
            if (m.find()) max = Math.max(max, Float.parseFloat(m.group(1)));
        }
        assertTrue(max > 0, name + ": 動く距離が読めること");
        return max;
    }

    @Test
    @DisplayName("流れるループは継ぎ目が無い: 動く距離が繰り返しの 1 周期で、動き切ったとき右端に空きが出ない")
    void marqueesAndCarouselLoopSeamlessly() throws Exception {
        String html = Files.readString(PAGE, StandardCharsets.UTF_8);
        UiDocumentCompiler compiler = new UiDocumentCompiler();
        Document doc = compiler.parseDocument(html, "", 1920, 1080);
        LayoutBox root = compiler.layoutDocument(doc, 1920, 1080, Map.of(), new ScrollState(), FontResolver.FALLBACK, new InteractionState());
        record Loop(String box, String keyframes, int period) {}
        // 周期は「同じ内容が現れるまでの個数」（リボンは 6 個、カルーセルは 6 枚）
        for (Loop loop : List.of(new Loop("track", "marquee", 6), new Loop("rail", "carousel", 6))) {
            LayoutBox track = findByClass(root, loop.box());
            assertNotNull(track, loop.box());
            float distance = keyframeDistance(doc, loop.keyframes());
            float period = track.children.get(loop.period()).x - track.children.get(0).x;
            assertEquals(period, distance, 0.5f, loop.keyframes() + ": 動く距離が 1 周期に等しい（ずれると、戻るときに飛ぶ）");
            LayoutBox last = track.children.get(track.children.size() - 1);
            float contentRight = last.x + last.w - track.x;
            assertTrue(contentRight >= distance + 1920, loop.keyframes() + ": 周期 + 画面の幅 (1920) 以上の内容があること (right=" + contentRight + ")");
        }
    }

    private static java.util.Set<String> matches(String regex, String text, int group) {
        java.util.Set<String> out = new java.util.TreeSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(text);
        while (m.find()) out.add(m.group(group));
        return out;
    }

    @Test
    @DisplayName("ページが散らからない: 使われていない・定義されていない class と @keyframes が無い")
    void classesAndKeyframesAreAllUsedAndDefined() throws Exception {
        String html = Files.readString(PAGE, StandardCharsets.UTF_8);
        int styleEnd = html.indexOf("</style>");
        String css = html.substring(0, styleEnd).replaceAll("(?s)/\\*.*?\\*/", "");
        String body = html.substring(styleEnd);

        java.util.Set<String> usedInHtml = new java.util.TreeSet<>();
        for (String value : matches("class=\"([^\"]*)\"", body, 1)) usedInHtml.addAll(List.of(value.trim().split("\\s+")));
        java.util.Set<String> definedInCss = matches("\\.([A-Za-z_][\\w-]*)", css.replaceAll("(?s)\\{[^{}]*\\}", "{}"), 1);

        java.util.Set<String> undefined = new java.util.TreeSet<>(usedInHtml);
        undefined.removeAll(definedInCss);
        assertTrue(undefined.isEmpty(), "HTML が使っているのに CSS に無い class: " + undefined);
        java.util.Set<String> unused = new java.util.TreeSet<>(definedInCss);
        unused.removeAll(usedInHtml);
        assertTrue(unused.isEmpty(), "CSS にあるのに HTML で使われていない class: " + unused);

        java.util.Set<String> keyframes = matches("@keyframes\\s+([\\w-]+)", css, 1);
        java.util.Set<String> referenced = matches("animation:\\s*([\\w-]+)", css, 1);
        java.util.Set<String> missing = new java.util.TreeSet<>(referenced);
        missing.removeAll(keyframes);
        assertTrue(missing.isEmpty(), "animation が参照しているのに @keyframes が無い: " + missing);
        java.util.Set<String> orphan = new java.util.TreeSet<>(keyframes);
        orphan.removeAll(referenced);
        assertTrue(orphan.isEmpty(), "@keyframes があるのにどこからも参照されていない: " + orphan);
    }
}
