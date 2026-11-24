package com.nishiyu.enderframelib.core.css;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.css.model.CssKeywords;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.parser.MarkdownParser;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * M1: 型付き 6 群の全フィールドが、旧 {@code ComputedStyle} の型付き読み口（{@code keywordOf} / {@code length} / {@code color} /
 * {@code number} / {@code get} / {@code StyleFacts}）と同じ値になること。入力は合成した宣言の表と、同梱の実文書の全要素の算出スタイル。
 */
class StyleTypedParityTest {
    private static final float PCT = 321f, VW = 800f, VH = 600f;

    private static final Class<?>[] GROUPS = {InheritedStyle.class, BoxGeomStyle.class, VisualStyle.class,
            FlexGridStyle.class, TableStyle.class, RareStyle.class};

    private static List<Object> groupsOf(ComputedStyle cs) {
        return List.of(InheritedStyle.of(cs), BoxGeomStyle.of(cs), VisualStyle.of(cs), FlexGridStyle.of(cs),
                TableStyle.of(cs), RareStyle.of(cs));
    }

    /** 1 つの算出スタイルについて、全群・全フィールドを旧読み口と突き合わせる。 */
    static int checkAgainstOld(ComputedStyle cs) throws ReflectiveOperationException {
        int checked = 0;
        for (Object g : groupsOf(cs)) {
            Class<?> type = g.getClass();
            CssExprs exprs = (CssExprs) fieldOrNull(type, "exprs", g);
            for (Field idField : type.getDeclaredFields()) {
                if (!Modifier.isStatic(idField.getModifiers()) || !idField.getName().startsWith("ID_")) continue;
                idField.setAccessible(true);
                int id = idField.getInt(null);
                String prop = ComputedStyle.CssPropertyId.name(id);
                String fieldName = idField.getName().substring(3);
                Field f = type.getDeclaredField(fieldName);
                Object actual = f.get(g);
                String declared = cs.get(id);
                String raw = declared != null ? declared : ComputedStyle.initialOf(id);
                String where = type.getSimpleName() + "." + fieldName + " (" + prop + ": " + raw + ")";
                switch (fieldName) {
                    case "fontSize" -> assertEquals(cs.fontSizePx(), (float) actual, 0f, where);
                    case "fontWeight" -> assertEquals(declared != null ? cs.facts().fontWeight : ValueKeywords.numericFontWeight("normal"),
                            (int) actual, where);
                    case "lineHeightValue" -> {
                        if (declared != null) {
                            assertEquals(cs.facts().lineHeightKind, (byte) fieldOrNull(type, "lineHeightKind", g), where);
                            assertEquals(cs.facts().lineHeightValue, (float) actual, 0f, where);
                        } else {
                            assertEquals(ComputedStyle.LH_NORMAL, (byte) fieldOrNull(type, "lineHeightKind", g), where);
                        }
                    }
                    default -> {
                        if (f.getType() == long.class) {
                            float expected = cs.length(id, PCT, VW, VH); // 宣言が無ければ 0（旧と同じ）
                            assertEquals(expected, CssLength.resolve((long) actual, exprs, PCT, cs.fontSizePx(), cs.rootFontSizePx(), VW, VH),
                                    0f, where);
                        } else if (f.getType() == float.class) {
                            float expected = declared != null ? cs.number(id, Float.NaN) : parseOrNaN(raw);
                            assertEquals(Float.floatToIntBits(expected), Float.floatToIntBits((float) actual), where);
                        } else if (f.getType() == int.class) {
                            int expected = declared != null ? cs.color(id, 0) : (raw == null ? 0 : ColorParser.parseColor(raw));
                            assertEquals(expected, (int) actual, where);
                        } else if (f.getType() == String.class) {
                            assertEquals(declared, actual, where); // 文字列のまま持つ値は宣言が無ければ null
                        } else {
                            @SuppressWarnings({"unchecked", "rawtypes"})
                            Object expected = cs.keywordOf(id, (Class) f.getType());
                            assertSame(expected, actual, where);
                        }
                    }
                }
                checked++;
            }
        }
        return checked;
    }

    private static float parseOrNaN(String raw) {
        try {
            return raw == null ? Float.NaN : Float.parseFloat(raw.trim());
        } catch (NumberFormatException e) {
            return Float.NaN;
        }
    }

    private static Object fieldOrNull(Class<?> type, String name, Object g) throws ReflectiveOperationException {
        try {
            return type.getDeclaredField(name).get(g);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    private static ComputedStyle of(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return ComputedStyle.of(m, 18f, 16f);
    }

    @Test
    void syntheticDeclarationsMatchTheOldReaders() throws ReflectiveOperationException {
        List<ComputedStyle> samples = List.of(
                of(),
                of("display", "flex", "position", "absolute", "width", "calc(100% - 20px)", "height", "auto", "min-width", "min-content",
                        "max-width", "none", "margin-top", "2em", "padding-left", "10%", "border-top-width", "thin",
                        "border-top-style", "solid", "overflow-x", "hidden", "overflow-y", "auto", "z-index", "5", "float", "left"),
                of("font-size", "20px", "font-weight", "bold", "line-height", "1.5", "color", "#336699", "text-align", "center",
                        "white-space", "pre-wrap", "letter-spacing", "2px", "font-family", "\"Fira Sans\", sans-serif",
                        "text-shadow", "1px 1px 2px red", "border-collapse", "collapse", "border-spacing", "2px", "cursor", "pointer"),
                of("line-height", "150%", "font-weight", "600", "visibility", "hidden", "direction", "rtl", "writing-mode", "vertical-rl"),
                of("line-height", "20px", "font-weight", "lighter", "pointer-events", "none"),
                of("background-color", "rgba(10,20,30,0.5)", "background-image", "linear-gradient(red, blue)", "opacity", "0.25",
                        "transform", "rotate(10deg)", "border-top-left-radius", "8px", "box-shadow", "0 0 4px #000", "filter", "blur(2px)",
                        "outline-style", "dashed", "outline-width", "3px", "mix-blend-mode", "multiply"),
                of("flex-direction", "column", "flex-wrap", "wrap", "flex-grow", "2", "flex-shrink", "0", "flex-basis", "30%",
                        "order", "-1", "justify-content", "space-between", "align-items", "center", "row-gap", "4px", "column-gap", "1em",
                        "grid-template-columns", "1fr 2fr", "grid-column", "1 / 3", "grid-auto-flow", "column"),
                of("table-layout", "fixed", "content", "\"x\"", "contain", "layout paint", "container-type", "inline-size",
                        "transition", "all 1s", "animation-name", "spin", "column-count", "3", "perspective", "100px",
                        "anchor-name", "--a", "--custom", "7", "x-unknown", "1"));
        int checked = 0;
        for (ComputedStyle cs : samples) checked += checkAgainstOld(cs);
        assertTrue(checked > 6 * 150, "突き合わせたフィールド数: " + checked);
    }

    private static void collect(HtmlNode n, InteractionState st, List<ComputedStyle> out) {
        ComputedStyle cs = st.computedStyleOf(n);
        if (cs != null) out.add(cs);
        for (HtmlNode c : n.children) collect(c, st, out);
    }

    @Test
    void realDocumentsMatchTheOldReadersForEveryElement() throws Exception {
        Path dir = Path.of("src/main/resources/data/enderframelib/enderframe_ui");
        int docs = 0, styles = 0;
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
            for (ComputedStyle cs : all) {
                checkAgainstOld(cs);
                styles++;
            }
            docs++;
        }
        assertTrue(docs >= 5, "読めた実文書: " + docs);
        assertTrue(styles > 500, "突き合わせた算出スタイル: " + styles);
    }

    @Test
    void keywordEnumsRoundTripThroughTheRegistryInitials() {
        Style d = Style.DEFAULT;
        assertSame(CssKeywords.Position.STATIC, d.box.position);
        assertSame(CssKeywords.BoxSizing.CONTENT_BOX, d.box.boxSizing);
        assertFalse(CssLength.isDeclared(d.box.width)); // 宣言の無い長さは UNDECLARED（0 px。読み手が「宣言があるか」を問える）
        assertFalse(CssLength.isDeclared(d.box.maxWidth));
        assertEquals(0xFF000000, d.inherited.color);
        assertEquals(16f, d.inherited.fontSize);
    }
}
