package com.nishiyu.enderframelib.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.core.dom.node.HostSyntax;
import com.nishiyu.enderframelib.core.dom.node.ReplacedKind;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.parser.HtmlParser;
import com.nishiyu.enderframelib.core.layout.ReplacedElements;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A9: 公開 SPI（{@code api.spi}）だけで宣言した独自タグ・構文が、内部の台帳へ橋渡しされて従来の SPI と同じように働く。
 * 宣言は {@link SpiTestProvider}（{@code META-INF/services} で登録）。
 */
class ApiSpiProviderTest {

    private static HtmlNode find(HtmlNode n, String key, String value) {
        if (value.equals(n.attrs.get(key)) || value.equals(n.tag)) return n;
        for (HtmlNode c : n.children) {
            HtmlNode r = find(c, key, value);
            if (r != null) return r;
        }
        return null;
    }

    @Test
    void replacedElementsAreRegisteredWithTheirKindAndHooks() {
        assertTrue(ReplacedElements.isHostImageLike("x-spi-img"));
        assertEquals(ReplacedKind.IMAGE_LIKE, ReplacedElements.painterOf("x-spi-img").kind());
        assertEquals("spi:abc", ReplacedElements.painterOf("x-spi-img").resolveSrc(Map.of("name", "abc"), "dflt"));
        assertTrue(ReplacedElements.isHostScene("x-spi-scene"));
        var scene = ReplacedElements.painterOf("x-spi-scene");
        assertEquals(77, scene.sceneHeight());
        assertEquals(55, scene.sceneMinWidth());
        assertEquals(false, scene.sceneFillsWidth());
    }

    @Test
    void syntaxAliasesMarkdownAndVoidElementsAreHonored() {
        assertEquals("data-spi-canon", HostSyntax.canonicalOf("x-spi-if"));
        assertTrue(HostSyntax.isMarkdownTag("x-spi-md"));
        HtmlNode root = HtmlParser.parse("<div id=\"p\"><x-spi-sep><span id=\"after\"></span></div>");
        HtmlNode sep = find(root, "", "x-spi-sep");
        assertNotNull(sep);
        assertTrue(sep.children.isEmpty(), "空要素は子を取らない");
        assertEquals("p", find(root, "id", "after").parent.attrs.get("id"), "後続は親の子");
    }

    @Test
    void tagTemplateExpandsToParsedHtmlKeepingAttributesAndInnerHtml() {
        EnderEngineHolder.assertTemplateExpands();
    }

    @Test
    void templateReturningNullLeavesTheElement() {
        HtmlNode root = HtmlParser.parse("<div><x-spi-skip id=\"k\"></x-spi-skip></div>");
        com.nishiyu.enderframelib.common.CustomTagExpander.expand(root);
        assertNotNull(find(root, "id", "k"));
        assertNull(find(root, "id", "nonexistent"));
    }

    /** Builder の翻訳・リソースの差し込みは文脈を書き換えるので、終わったら元に戻す。 */
    private static void withRestoredHost(Runnable body) {
        var ctx = com.nishiyu.enderframelib.core.EngineContext.global();
        var before = ctx.host();
        try {
            body.run();
        } finally {
            ctx.updateHost(h -> before);
        }
    }

    @Test
    void builderResourcesSuppliesStylesheetsAndFallsBackToTheDefaultWhenNull() {
        withRestoredHost(() -> {
            String html = "<html><head><link rel='stylesheet' href='spi-theme.css'><link rel='stylesheet' href='absent.css'></head>"
                    + "<body style='margin:0'><div></div></body></html>";
            try (WebViewSurface s = EnderEngine.html(html).size(60, 40).clearColor(0xFFFFFFFF)
                    .resources(name -> name.equals("spi-theme.css") ? "div{width:30px;height:20px;background:#0000ff}" : null)
                    .build()) {
                s.frame(0, true);
                int[] px = s.pixels();
                assertEquals(0xFF0000FF, px[10 * s.pixelWidth() + 10], "差し込んだ CSS が効く");
                assertEquals(0xFFFFFFFF, px[35 * s.pixelWidth() + 50]);
            }
        });
    }

    private static long ink(String translation) {
        long[] sum = {0};
        withRestoredHost(() -> {
            String html = "<html><body style='margin:0'><div data-i18n='spi.k' style='font-size:20px;color:#000'>key</div></body></html>";
            try (WebViewSurface s = EnderEngine.html(html).size(200, 40).clearColor(0xFFFFFFFF)
                    .i18n(key -> key.equals("spi.k") ? translation : key)
                    .build()) {
                s.frame(0, true);
                for (int px : s.pixels()) if ((px & 0xFFFFFF) != 0xFFFFFF) sum[0]++;
            }
        });
        return sum[0];
    }

    @Test
    void builderI18nResolvesTranslationKeys() {
        long wide = ink("MMMMMMMM");
        long none = ink("");
        assertTrue(wide > none + 50, "翻訳の結果が文字として描かれる: wide=" + wide + " none=" + none);
    }

    /** 展開は文書の読み込みを通す（公開 API だけで描いて画素に出る）。 */
    private static final class EnderEngineHolder {
        static void assertTemplateExpands() {
            String html = "<html><body style='margin:0'><x-spi-box id='b1' color='#ff0000'><i></i></x-spi-box></body></html>";
            try (WebViewSurface s = EnderEngine.html(html).size(60, 40).clearColor(0xFFFFFFFF).build()) {
                s.frame(0, true);
                int[] px = s.pixels();
                int inside = px[10 * s.pixelWidth() + 10];
                int outside = px[35 * s.pixelWidth() + 50];
                assertEquals(0xFFFF0000, inside, "テンプレートが展開され、color 属性が反映される");
                assertEquals(0xFFFFFFFF, outside);
            }
        }
    }
}
