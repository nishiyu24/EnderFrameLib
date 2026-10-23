package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.text.font.SystemFonts;
import org.junit.jupiter.api.Assumptions;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * テスト用の HTML を Chrome（Windows）で表示したときの要素の矩形と、エンジンのレイアウトを id ごとに比べる。
 * Chrome の値は {@code id|x|y|w|h} の行（{@code #} で始まる行は注釈）。システムの書体で組むので Windows でだけ走る。
 */
final class ChromeRects {

    record Rect(float x, float y, float w, float h) {}

    private ChromeRects() {}

    /** システムの書体（{@code family} を含む）が無ければテストを飛ばし、あればそれを返す。 */
    static SystemFonts requireFonts(String family) {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
        SystemFonts system = SystemFonts.system();
        Assumptions.assumeTrue(system.hasFamily(family), family + " が無い環境");
        return system.withLocale(Locale.JAPANESE);
    }

    static Map<String, Rect> chrome(String resource) throws Exception {
        Map<String, Rect> out = new LinkedHashMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(Objects.requireNonNull(
                ChromeRects.class.getResourceAsStream(resource), resource), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\\|");
                if (line.startsWith("#") || p.length < 5) continue;
                out.put(p[0], new Rect(Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3]), Float.parseFloat(p[4])));
            }
        }
        return out;
    }

    static Map<String, Rect> engine(String htmlPath, int w, int h, SystemFonts fonts) throws Exception {
        String html = Files.readString(Path.of(htmlPath), StandardCharsets.UTF_8);
        UiDocumentCompiler compiler = new UiDocumentCompiler();
        Document doc = compiler.parseDocument(html, "", w, h);
        LayoutBox root = compiler.layoutDocument(doc, w, h, Map.of(), new ScrollState(), fonts, new InteractionState());
        Map<String, Rect> out = new LinkedHashMap<>();
        collect(root, out);
        return out;
    }

    private static void collect(LayoutBox b, Map<String, Rect> out) {
        if (b.node != null && !b.isAnonymous && b.node.attrs.containsKey("id")) {
            out.putIfAbsent(b.node.attrs.get("id"), new Rect(b.x, b.y, b.w, b.h));
        }
        for (LayoutBox c : b.children) collect(c, out);
    }

    /** 位置・大きさの差が {@code tolerance} を超える要素（と、エンジンに無い要素）の説明。 */
    static List<String> differences(Map<String, Rect> expected, Map<String, Rect> actual, float tolerance) {
        List<String> failures = new ArrayList<>();
        for (var e : expected.entrySet()) {
            Rect c = e.getValue(), a = actual.get(e.getKey());
            if (a == null) {
                failures.add(e.getKey() + ": エンジンに無い");
                continue;
            }
            float d = Math.max(Math.max(Math.abs(a.x - c.x), Math.abs(a.y - c.y)), Math.max(Math.abs(a.w - c.w), Math.abs(a.h - c.h)));
            if (d > tolerance) {
                failures.add(String.format("%s: ours [%.2f %.2f %.2fx%.2f] chrome [%.2f %.2f %.2fx%.2f]",
                        e.getKey(), a.x, a.y, a.w, a.h, c.x, c.y, c.w, c.h));
            }
        }
        return failures;
    }
}
