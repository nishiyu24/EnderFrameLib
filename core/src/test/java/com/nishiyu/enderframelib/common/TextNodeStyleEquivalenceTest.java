package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.paint.tree.PaintBox;
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
 * M1c: テキストの節の算出スタイル（{@link ComputedStyle#derivedTextChild}）が、
 * 要素と同じ経路（カスケード＋算出）と同じ結果になり、{@code inherited} 群が親と参照共有されること。
 */
class TextNodeStyleEquivalenceTest {
    private static final int W = 800, H = 600;
    private static final Path WPT = Path.of("core/src/test/resources/wpt/reftests");
    private static final Path UI = Path.of("mod/src/main/resources/data/enderframelib/enderframe_ui");

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
            UiDocumentCompiler c = new UiDocumentCompiler();
            Document doc = c.parseDocument(html, "", W, H);
            com.nishiyu.enderframelib.core.dom.InteractionState state = new com.nishiyu.enderframelib.core.dom.InteractionState();
            PaintBox root = c.layoutDocument(doc, W, H, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);
            List<PaintBox> textBoxes = new ArrayList<>();
            collect(root, textBoxes);
            for (int i = 0; i < textBoxes.size(); i++) {
                PaintBox b = textBoxes.get(i);
                boolean generated = b.paintParent() != null && b.paintParent().node != null && b.paintParent().node.isPseudoNode;
                ComputedStyle domParent = !generated && b.node != null && b.node.parent != null ? state.computedStyleOf(b.node.parent) : null;
                ComputedStyle parentStyle = domParent != null ? domParent : (b.paintParent() != null ? b.paintParent().style() : null);
                if (parentStyle == null) continue;
                compared++;
                ComputedStyle full = com.nishiyu.enderframelib.core.css.StyleResolver.finalizeComputedStyle(
                        com.nishiyu.enderframelib.core.css.StyleResolver.resolveDeclarations(
                                b.node, parentStyle.typed(), doc.sheet, state),
                        doc.sheet, Map.of(), parentStyle.typed(), parentStyle.fontSizePx(),
                        parentStyle.typed().inherited.rootFontSizePx, root.style().lineHeightPx(), W, H,
                        com.nishiyu.enderframelib.core.css.model.ContainerBox.NONE_SIZE);
                assertSame(parentStyle.typed().inherited, b.style().typed().inherited, "継承群の参照共有: " + p + " #" + i);
                if (!b.style().typed().equals(full.typed())) {
                    mismatches.add(p + " #" + i + "\n  fast=" + b.style().asMap() + "\n  full=" + full.asMap());
                }
            }
        }
        assertTrue(compared > 1000, "compared " + compared);
        assertTrue(mismatches.isEmpty(), mismatches.size() + " mismatches, first:\n"
                + String.join("\n", mismatches.subList(0, Math.min(3, mismatches.size()))));
    }

    private static void collect(PaintBox box, List<PaintBox> out) {
        if (box.node != null && box.node.nodeType == NodeType.TEXT && !box.isAnonymous) out.add(box);
        for (PaintBox c : box.paintChildren()) collect(c, out);
    }
}
