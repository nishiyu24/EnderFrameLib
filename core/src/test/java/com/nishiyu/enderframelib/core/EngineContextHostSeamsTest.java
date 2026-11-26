package com.nishiyu.enderframelib.core;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.dom.parser.HtmlParser;
import com.nishiyu.enderframelib.core.dom.parser.ParseLimits;
import com.nishiyu.enderframelib.core.paint.CssAnimator;
import com.nishiyu.enderframelib.core.raster.svg.SvgImage;
import com.nishiyu.enderframelib.core.raster.svg.SvgSources;
import com.nishiyu.enderframelib.core.text.font.FontFaceRules;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * A13 第 2〜4 段: SVG の取得元・書体の読み手・アニメ状態の置き場・解析の上限・置換要素・ローカル環境・テンプレート構文（属性の別名・
 * Markdown タグ）・タグマクロが面ごとの文脈にあり、面同士で混ざらない。
 */
class EngineContextHostSeamsTest {
    private static String svg(int w) {
        return "<svg xmlns='http://www.w3.org/2000/svg' width='" + w + "' height='" + w + "'><rect width='" + w + "' height='" + w + "'/></svg>";
    }

    private static EngineContext svgCtx(int w) {
        EngineContext c = new EngineContext();
        c.updateHost(h -> h.withSvgResourceReader((n, max) -> svg(w).getBytes(StandardCharsets.UTF_8))
                .withSvgHttpSource(u -> SvgImage.parse(svg(w + 1))));
        return c;
    }

    @Test
    void svgSourcesReadThroughTheScopedContextAndDoNotShareCachedResources() {
        EngineContext a = svgCtx(10), b = svgCtx(20);
        for (int round = 0; round < 3; round++) { // 覚えを挟んでも別の面の結果を返さない
            EngineContext.runWith(a, () -> {
                assertArrayEquals(new int[] {10, 10}, SvgSources.intrinsicSize("ns:same/path.svg"));
                assertEquals(11, Math.round(SvgSources.get("https://e.test/x.svg").width()));
            });
            EngineContext.runWith(b, () -> {
                assertArrayEquals(new int[] {20, 20}, SvgSources.intrinsicSize("ns:same/path.svg"));
                assertEquals(21, Math.round(SvgSources.get("https://e.test/x.svg").width()));
            });
        }
        // 取得元の無い面（global）は読めない
        assertNull(SvgSources.get("ns:same/path.svg"));
        assertNull(SvgSources.get("https://e.test/x.svg"));
    }

    @Test
    void fontReaderIsPerContext() {
        List<String> a = new ArrayList<>(), b = new ArrayList<>();
        EngineContext ca = new EngineContext(), cb = new EngineContext();
        ca.updateHost(h -> h.withFontReader(s -> { a.add(s); return null; }));
        cb.updateHost(h -> h.withFontReader(s -> { b.add(s); return null; }));
        String css = "@font-face{font-family:iso;src:url(x:f.ttf)}";
        EngineContext.runWith(ca, () -> FontFaceRules.extractAndRegister(css, null));
        EngineContext.runWith(cb, () -> FontFaceRules.extractAndRegister(css, null));
        assertEquals(List.of("x:f.ttf", "x:font/f.ttf"), a);
        assertEquals(List.of("x:f.ttf", "x:font/f.ttf"), b);
        List<String> before = new ArrayList<>(a);
        EngineContext.runWith(cb, () -> FontFaceRules.extractAndRegister(css, null));
        assertEquals(before, a, "別の面の読み込みで a の読み手が呼ばれた");
    }

    @Test
    void animationStateStoreIsPerContext() {
        List<String> seenA = new ArrayList<>(), seenB = new ArrayList<>();
        EngineContext a = new EngineContext(), b = new EngineContext();
        a.updateHost(h -> h.withAnimationStateStore((id, create) -> { seenA.add(id); return new HashMap<>(); }));
        b.updateHost(h -> h.withAnimationStateStore((id, create) -> { seenB.add(id); return null; }));
        EngineContext.runWith(a, () -> CssAnimator.peekState("sess-a", "x"));
        EngineContext.runWith(b, () -> CssAnimator.peekState("sess-b", "x"));
        assertEquals(List.of("sess-a"), seenA);
        assertEquals(List.of("sess-b"), seenB);
    }

    @Test
    void parseLimitsAreContextual() {
        EngineContext strict = new EngineContext();
        strict.updateHost(h -> h.withParseLimits(new ParseLimits(5, 256, true)));
        assertSame(ParseLimits.DEFAULT, HtmlParser.defaultLimits());
        String many = "<div></div>".repeat(50);
        EngineContext.runWith(strict, () -> {
            assertEquals(5, HtmlParser.defaultLimits().maxTokens());
            assertThrows(ParseLimits.HtmlLimitExceededException.class, () -> HtmlParser.parse(many));
        });
        assertNotNull(HtmlParser.parse(many), "global は既定の上限のまま");
    }

    @Test
    void concurrentThreadsKeepTheirOwnLimitsAndSvgs() throws Exception {
        EngineContext a = svgCtx(10), b = svgCtx(20);
        a.updateHost(h -> h.withParseLimits(new ParseLimits(7, 256, false)));
        b.updateHost(h -> h.withParseLimits(new ParseLimits(9, 256, false)));
        AtomicReference<String> fail = new AtomicReference<>();
        Thread[] ts = new Thread[2];
        for (int t = 0; t < 2; t++) {
            EngineContext mine = t == 0 ? a : b;
            int w = t == 0 ? 10 : 20, lim = t == 0 ? 7 : 9;
            ts[t] = new Thread(() -> EngineContext.runWith(mine, () -> {
                for (int i = 0; i < 5000 && fail.get() == null; i++) {
                    if (HtmlParser.defaultLimits().maxTokens() != lim || SvgSources.intrinsicSize("ns:p.svg")[0] != w) {
                        fail.compareAndSet(null, "混ざった: 反復 " + i);
                    }
                }
            }));
            ts[t].start();
        }
        for (Thread t : ts) t.join();
        assertNull(fail.get());
    }

    @Test
    void replacedElementsAreRegisteredPerContext() {
        com.nishiyu.enderframelib.core.dom.node.CustomElementPainter p = new com.nishiyu.enderframelib.core.dom.node.CustomElementPainter() {
            @Override public com.nishiyu.enderframelib.core.dom.node.ReplacedKind kind() { return com.nishiyu.enderframelib.core.dom.node.ReplacedKind.IMAGE_LIKE; }
        };
        EngineContext a = new EngineContext(), b = new EngineContext();
        a.updateHost(h -> h.withReplacedElement("x-iso", p));
        EngineContext.runWith(a, () -> {
            assertTrue(com.nishiyu.enderframelib.core.replaced.ReplacedElements.isHost("x-iso"));
            assertSame(p, com.nishiyu.enderframelib.core.replaced.ReplacedElements.painterOf("x-iso"));
        });
        EngineContext.runWith(b, () -> assertFalse(com.nishiyu.enderframelib.core.replaced.ReplacedElements.isHost("x-iso")));
        assertFalse(com.nishiyu.enderframelib.core.replaced.ReplacedElements.isHost("x-iso"));
        assertTrue(com.nishiyu.enderframelib.core.replaced.ReplacedElements.isHost("scene-3d"), "ビルトインはどの面でも見える");
        assertThrows(IllegalArgumentException.class, () -> a.updateHost(h -> h.withReplacedElement("", p)));
    }

    @Test
    void localEnvironmentVariablesAndSnapshotsDoNotCrossContexts() {
        EngineContext a = new EngineContext(), b = new EngineContext();
        EngineContext.runWith(a, () -> {
            com.nishiyu.enderframelib.common.LocalEnvironment.useBaseSource(() -> Map.of("@lang", "ja_jp"));
            com.nishiyu.enderframelib.common.LocalEnvironment.registerCustomVariable("@mana", () -> "7");
        });
        for (int i = 0; i < 3; i++) { // 写しの覚えを挟んでも混ざらない
            EngineContext.runWith(a, () -> {
                Map<String, String> env = com.nishiyu.enderframelib.common.LocalEnvironment.capture();
                assertEquals("ja_jp", env.get("@lang"));
                assertEquals("7", env.get("@mana"));
            });
            EngineContext.runWith(b, () -> {
                Map<String, String> env = com.nishiyu.enderframelib.common.LocalEnvironment.capture();
                assertEquals("en_us", env.get("@lang"));
                assertNull(env.get("@mana"));
            });
        }
        assertEquals("en_us", com.nishiyu.enderframelib.common.LocalEnvironment.capture().get("@lang"));
    }

    /** A13 第 4 段: テンプレート構文（属性の別名・Markdown タグ）とタグマクロは面ごと。2 スレッドが同時に自分の面だけを見る。 */
    @Test
    void syntaxAndTagMacrosDoNotCrossContextsAcrossThreads() throws Exception {
        EngineContext a = new EngineContext(), b = new EngineContext();
        a.updateHost(h -> h.withAttributeAlias("a-when", "data-if").withMarkdownTag("a-md")
                .withTagMacro("x-t", src -> List.of(new com.nishiyu.enderframelib.core.dom.node.HtmlElement("i"))));
        b.updateHost(h -> h.withAttributeAlias("b-when", "data-if").withMarkdownTag("b-md")
                .withTagMacro("x-t", src -> List.of(new com.nishiyu.enderframelib.core.dom.node.HtmlElement("b"))));
        AtomicReference<String> fail = new AtomicReference<>();
        Thread[] ts = new Thread[2];
        for (int t = 0; t < 2; t++) {
            EngineContext mine = t == 0 ? a : b;
            String my = t == 0 ? "a" : "b", other = t == 0 ? "b" : "a", tag = t == 0 ? "i" : "b";
            ts[t] = new Thread(() -> EngineContext.runWith(mine, () -> {
                for (int i = 0; i < 3000 && fail.get() == null; i++) {
                    var root = HtmlParser.parse("<div><x-t></x-t></div>");
                    com.nishiyu.enderframelib.common.CustomTagExpander.expand(root);
                    boolean ok = "data-if".equals(com.nishiyu.enderframelib.core.dom.node.HostSyntax.canonicalOf(my + "-when"))
                            && (other + "-when").equals(com.nishiyu.enderframelib.core.dom.node.HostSyntax.canonicalOf(other + "-when"))
                            && com.nishiyu.enderframelib.core.dom.node.HostSyntax.isMarkdownTag(my + "-md")
                            && !com.nishiyu.enderframelib.core.dom.node.HostSyntax.isMarkdownTag(other + "-md")
                            && root.children.get(0).children.size() == 1 && tag.equals(root.children.get(0).children.get(0).tag);
                    if (!ok) fail.compareAndSet(null, "混ざった: 反復 " + i);
                }
            }));
            ts[t].start();
        }
        for (Thread t : ts) t.join();
        assertNull(fail.get());
        assertEquals("a-when", com.nishiyu.enderframelib.core.dom.node.HostSyntax.canonicalOf("a-when"), "global には効かない");
    }

    /** 番人: 窓口に static の setter と、文脈以外の差し込み口が戻らない。登録表（構文・タグマクロ）にも static の登録口を戻さない。 */
    @Test
    void seamsStayStateless() throws Exception {
        Path main = Path.of("core/src/main/java/com/nishiyu/enderframelib/core");
        for (String f : new String[] {"raster/svg/SvgSources.java", "text/font/FontFaceRules.java", "paint/CssAnimator.java", "dom/parser/HtmlParser.java", "css/GlyphUnits.java", "replaced/ReplacedElements.java"}) {
            String src = Files.readString(main.resolve(f));
            assertFalse(src.contains("public static void set"), f + " に static setter が戻った");
            assertEquals(1, src.split("static volatile java.util.function.Supplier<Source>", -1).length - 1, f + ": 文脈の引き口は 1 つだけ");
        }

        for (String f : new String[] {"dom/node/HostSyntax.java", "../common/CustomTagExpander.java"}) {
            String src = Files.readString(main.resolve(f));
            assertFalse(src.contains("static synchronized void register"), f + " に static の登録口が戻った（HostServices の with… を使う）");
            assertFalse(src.contains("static synchronized void resetToDefaults"), f + " に static の後始末が戻った");
        }
        assertFalse(Files.readString(main.resolve("paint/TreePainter.java")).contains("public static void set"), "TreePainter に static setter が戻った");
    }
}
