package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A16: テキストの節の算出スタイルを親から写す近道（{@link RenderPipeline#TEXT_STYLE_FROM_PARENT}）が、
 * 要素と同じ経路（カスケード＋算出）と同じ結果になるか。WPT の参照比較の入力と同梱の UI 文書のすべてのテキストの箱で比べる。
 */
class TextNodeStyleEquivalenceTest {
    private static final int W = 800, H = 600;
    private static final Path WPT = Path.of("src/test/resources/wpt/reftests");
    private static final Path UI = Path.of("src/main/resources/data/enderframelib/enderframe_ui");

    @Test
    void textNodeStyleFromParentMatchesFullCascade() throws IOException {
        List<Path> docs = new ArrayList<>();
        Set<String> hangs = Set.copyOf(Files.readAllLines(WPT.resolve("reftest-hangs.txt")));
        for (String line : Files.readAllLines(WPT.resolve("manifest.tsv"))) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] cols = line.split("\t");
            for (String rel : cols) {
                if (!hangs.contains(cols[0])) docs.add(WPT.resolve(rel));
            }
        }
        try (Stream<Path> s = Files.list(UI)) {
            s.filter(p -> p.toString().endsWith(".html")).forEach(docs::add);
        }
        // 縦書き・em・rem・var()・継承するカスタムプロパティ・ルートの文字
        docs.add(null);

        int compared = 0;
        List<String> mismatches = new ArrayList<>();
        for (Path p : docs) {
            String html = p == null
                    ? "<html style='font-size:20px;--a:2vw;color:red'> x <body style='font-size:1.5em;--b:var(--a);line-height:150%'>"
                    + " <div style='writing-mode:vertical-rl;font-size:2rem'> a <span style='font-size:calc(1em + 3px)'> b </span> </div>"
                    + " <p style='--c:3em;letter-spacing:var(--c)'> c <b> d </b> </p></body></html>"
                    : Files.readString(p, StandardCharsets.UTF_8);
            if (p != null && !Files.isRegularFile(p)) continue;
            List<ComputedStyle> fast = textStyles(html, true);
            List<ComputedStyle> full = textStyles(html, false);
            assertEquals(full.size(), fast.size(), "text box count: " + p);
            for (int i = 0; i < fast.size(); i++) {
                ComputedStyle a = fast.get(i), b = full.get(i);
                compared++;
                if (!ComputedStyle.sameDeclarations(a, b) || a.fontSizePx() != b.fontSizePx()
                        || a.rootFontSizePx() != b.rootFontSizePx()) {
                    mismatches.add(p + " #" + i + "\n  fast=" + a.asMap() + " fs=" + a.fontSizePx()
                            + "\n  full=" + b.asMap() + " fs=" + b.fontSizePx());
                }
            }
        }
        assertTrue(compared > 1000, "compared " + compared);
        assertTrue(mismatches.isEmpty(), mismatches.size() + " mismatches, first:\n"
                + String.join("\n", mismatches.subList(0, Math.min(3, mismatches.size()))));
    }

    private static List<ComputedStyle> textStyles(String html, boolean fromParent) {
        boolean saved = RenderPipeline.TEXT_STYLE_FROM_PARENT;
        RenderPipeline.TEXT_STYLE_FROM_PARENT = fromParent;
        try {
            UiDocumentCompiler c = new UiDocumentCompiler();
            Document doc = c.parseDocument(html, "", W, H);
            LayoutBox root = c.layoutDocument(doc, W, H, Map.of(), new ScrollState(), FontResolver.FALLBACK);
            List<ComputedStyle> out = new ArrayList<>();
            collect(root, out);
            return out;
        } finally {
            RenderPipeline.TEXT_STYLE_FROM_PARENT = saved;
        }
    }

    private static void collect(LayoutBox box, List<ComputedStyle> out) {
        if (box.node != null && box.node.nodeType == NodeType.TEXT && !box.isAnonymous) out.add(box.style);
        for (LayoutBox c : box.children) collect(c, out);
    }
}
