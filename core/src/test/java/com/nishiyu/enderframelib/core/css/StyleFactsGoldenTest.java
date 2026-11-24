package com.nishiyu.enderframelib.core.css;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.parser.MarkdownParser;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;

/**
 * M1b-b1: {@link StyleFacts}（導出値）の全フィールド・{@code containment()} が、置き換えの前後で変わらないこと。
 * 基準は旧実装（文字列から導出）で記録した {@code golden/style-facts.txt}（合成入力は全項目、実文書は文書ごとの CRC と要素数）。
 * 基準が無ければ作って失敗する（作り直しは、意図した変化だと確かめてから基準を消して再実行）。
 */
class StyleFactsGoldenTest {
    private static final Path GOLDEN = Path.of("core/src/test/resources/golden/style-facts.txt");

    static String dump(ComputedStyle cs) throws ReflectiveOperationException {
        StyleFacts f = cs.facts();
        StringBuilder sb = new StringBuilder();
        for (Field fld : StyleFacts.class.getDeclaredFields()) {
            if (Modifier.isStatic(fld.getModifiers()) || !Modifier.isPublic(fld.getModifiers()) || fld.getName().equals("hasHeightDeclaration") || fld.getName().equals("containment")) continue;
            sb.append(fld.getName()).append('=').append(fld.get(f)).append(';');
        }
        sb.append("containment=").append(cs.containment()).append(";fontSize=").append(cs.fontSizePx());
        return sb.toString();
    }

    private static ComputedStyle of(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return ComputedStyle.of(m, 18f, 16f);
    }

    private static List<ComputedStyle> samples() {
        return List.of(of(),
                of("width", "100px", "height", "50%", "margin-left", "auto", "margin-right", "auto"),
                of("width", "auto", "margin-left", "auto", "margin-top", "auto", "padding-left", "5%"),
                of("width", "min-content", "height", "calc(100% - 4px)"),
                of("width", "max-content", "min-width", "20%", "left", "10%"),
                of("width", "fit-content", "flex-basis", "30%"),
                of("width", "fit-content(200px)", "height", "fit-content(10%)"),
                of("width", "fit-content(200px", "column-gap", "calc(1em + 5%)", "row-gap", "10%"),
                of("width", "0", "height", "0px", "top", "calc(10px + 2%)", "bottom", "5%"),
                of("width", "calc(50px + 10px)", "height", "2em", "grid-template-rows", "1fr 20%", "grid-auto-rows", "calc(1px + 1%)"),
                of("width", "10vw", "height", "5vh", "margin-top", "1vmin"),
                of("width", "garbage", "float", "left", "display", "none", "overflow-x", "hidden"),
                of("overflow-x", "scroll", "overflow-y", "auto", "contain", "layout paint", "box-sizing", "border-box"),
                of("overflow-x", "hidden", "overflow-y", "auto", "contain", "strict", "direction", "rtl", "writing-mode", "vertical-rl"),
                of("overflow-x", "clip", "overflow-y", "clip", "contain", "paint", "content-visibility", "auto", "container-type", "inline-size"),
                of("content-visibility", "hidden", "container-type", "size", "align-items", "baseline", "align-self", "first-baseline"),
                of("align-items", "normal", "align-self", "auto", "justify-content", "center", "white-space", "pre", "word-break", "break-all"),
                of("align-items", "legacy", "align-self", "last-baseline", "overflow-wrap", "anywhere", "text-overflow", "ellipsis"),
                of("font-weight", "bold", "font-style", "italic", "letter-spacing", "2px", "font-kerning", "none", "line-height", "1.4"),
                of("font-weight", "300", "font-size", "22px", "line-height", "150%", "font-kerning", "NONE"),
                of("height", "auto", "min-height", "10%", "max-height", "50%", "writing-mode", "horizontal-tb"),
                of("height", "10px", "min-height", "50px", "writing-mode", " Horizontal-tb ", "display", "block"),
                of("width", "300px", "margin-left", "10px", "writing-mode", "sideways-lr"));
    }

    private static void collect(HtmlNode n, InteractionState st, List<ComputedStyle> out) {
        ComputedStyle cs = st.computedStyleOf(n);
        if (cs != null) out.add(cs);
        for (HtmlNode c : n.children) collect(c, st, out);
    }

    private static Map<String, String> current() throws Exception {
        Map<String, String> lines = new LinkedHashMap<>();
        int i = 0;
        for (ComputedStyle cs : samples()) lines.put("synthetic-" + (i++), dump(cs));
        Path dir = Path.of("src/main/resources/data/enderframelib/enderframe_ui");
        for (String name : List.of("showcase.html", "voidwave.html", "surface_test.html", "animation_test.html", "media_test.html",
                "hud_test.html", "item_block_test.html", "machine_ui.html", "guidebook.md")) {
            Path p = dir.resolve(name);
            if (!Files.exists(p)) continue;
            String text = Files.readString(p, StandardCharsets.UTF_8).replace("\r\n", "\n");
            String html = name.endsWith(".md") ? MarkdownParser.toHtml(text) : text;
            UiDocumentCompiler compiler = new UiDocumentCompiler();
            Document doc = compiler.parseDocument(html, "", 800, 600);
            InteractionState state = new InteractionState();
            compiler.layoutDocument(doc, 800, 600, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);
            List<ComputedStyle> all = new ArrayList<>();
            collect(doc.root, state, all);
            CRC32 crc = new CRC32();
            for (ComputedStyle cs : all) crc.update(dump(cs).getBytes(StandardCharsets.UTF_8));
            lines.put("doc-" + name, all.size() + " " + Long.toHexString(crc.getValue()));
        }
        return lines;
    }

    @Test
    void factsMatchTheRecordedBaseline() throws Exception {
        Map<String, String> now = current();
        if (!Files.exists(GOLDEN)) {
            Files.createDirectories(GOLDEN.getParent());
            List<String> out = new ArrayList<>();
            now.forEach((k, v) -> out.add(k + "\t" + v));
            Files.write(GOLDEN, out, StandardCharsets.UTF_8);
            fail("基準を新規に作った: " + GOLDEN);
        }
        Map<String, String> expected = new LinkedHashMap<>();
        for (String l : Files.readAllLines(GOLDEN, StandardCharsets.UTF_8)) {
            int t = l.indexOf('\t');
            expected.put(l.substring(0, t), l.substring(t + 1));
        }
        assertEquals(expected.keySet(), now.keySet());
        StringBuilder diff = new StringBuilder();
        for (var e : expected.entrySet()) {
            String a = e.getValue(), b = now.get(e.getKey());
            if (a.equals(b)) continue;
            diff.append(e.getKey()).append(": ").append(a.length()).append("/").append(b.length()).append(" ");
            if (e.getKey().startsWith("doc-")) diff.append(a).append(" -> ").append(b);
            else {
                List<String> pa = List.of(a.split(";")), pb = List.of(b.split(";"));
                for (String y : pb) if (!pa.contains(y)) diff.append("<extra ").append(y).append("> ");
                for (String x : pa) if (!pb.contains(x)) diff.append("[").append(x).append(" -> ").append(pb.stream().filter(y -> y.startsWith(x.substring(0, x.indexOf((char) 61) + 1))).findFirst().orElse("?")).append("] ");
            }
            diff.append((char) 10);
        }
        assertEquals("", diff.toString());
    }
}
