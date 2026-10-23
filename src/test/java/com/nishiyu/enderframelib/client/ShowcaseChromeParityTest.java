package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.text.font.SystemFonts;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * showcase.html をシステムの書体（ゲーム内と同じ）でレイアウトし、Chrome で同じ大きさに表示したときの
 * 要素の位置・大きさと比べる。大きな画面（ビューポートが広い）でも Chrome と同じに並ぶことを確かめる。
 */
public class ShowcaseChromeParityTest {

    private static String showcaseHtml;
    private static SystemFonts fonts;

    @BeforeAll
    static void setUp() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
        SystemFonts system = SystemFonts.system();
        Assumptions.assumeTrue(system.hasFamily("Segoe UI") && system.hasFamily("Noto Sans JP"));
        fonts = system.withLocale(Locale.JAPANESE);
        showcaseHtml = Files.readString(Path.of("src/main/resources/data/enderframelib/enderframe_ui/showcase.html"),
                StandardCharsets.UTF_8);
    }

    record Rect(float x, float y, float w, float h) {}

    static Map<String, Rect> chrome(int w, int h) throws Exception {
        Map<String, Rect> out = new LinkedHashMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(Objects.requireNonNull(
                ShowcaseChromeParityTest.class.getResourceAsStream("/showcase-chrome/showcase-" + w + "x" + h + ".txt")),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\\|");
                if (p.length < 6) continue;
                out.put(p[0] + "[" + p[1] + "]", new Rect(Float.parseFloat(p[2]), Float.parseFloat(p[3]),
                        Float.parseFloat(p[4]), Float.parseFloat(p[5])));
            }
        }
        return out;
    }

    static Map<String, Rect> engine(int w, int h) {
        UiDocumentCompiler compiler = new UiDocumentCompiler();
        Document doc = compiler.parseDocument(showcaseHtml, "", w, h);
        LayoutBox root = compiler.layoutDocument(doc, w, h, Map.of(), new ScrollState(), fonts, new InteractionState());
        Map<String, Rect> out = new LinkedHashMap<>();
        Map<String, Integer> counters = new LinkedHashMap<>();
        collect(root, new IdentityHashMap<>(), counters, out);
        return out;
    }

    private static void collect(LayoutBox b, IdentityHashMap<HtmlNode, Boolean> seen, Map<String, Integer> counters,
                                Map<String, Rect> out) {
        if (b.node != null && seen.put(b.node, Boolean.TRUE) == null) {
            String cls = b.node.attrs.get("class");
            if (cls != null) {
                for (String c : cls.trim().split("\\s+")) {
                    int i = counters.merge(c, 1, Integer::sum) - 1;
                    out.put(c + "[" + i + "]", new Rect(b.x, b.y, b.w, b.h));
                }
            }
        }
        for (LayoutBox c : b.children) collect(c, seen, counters, out);
    }

    /** 位置・大きさの許容差（px）。Chrome の値は 1/64px 単位。 */
    private static final float TOLERANCE = 0.5f;

    /**
     * Chrome と違ってよい要素と、その理由。ここに無い要素は {@link #TOLERANCE} 以内で一致しなければならない。
     *
     * <ul>
     *   <li>font-weight: 500 のバッジ: Segoe UI に 500 の面は無い。CSS Fonts 4 §5.2 では Regular（400）を選ぶが、
     *       Chrome（Windows）はシステムの書体の照合を DirectWrite に任せるので Semibold を選ぶ（Firefox は仕様どおり）。
     *       バッジが 1〜2.5px 狭く、ヘッダーの中央寄せで検索欄の位置もずれる。</li>
     * </ul>
     */
    private static boolean knownDivergence(String key) {
        String cls = key.substring(0, key.indexOf('['));
        return switch (cls) {
            case "badge-pill", "header-actions", "search-wrapper", "search-select", "search-input" -> true;
            case "btn" -> key.equals("btn[0]");
            default -> false;
        };
    }

    @Test
    void elementsMatchChrome() throws Exception {
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (int[] size : new int[][]{{854, 480}, {1366, 768}, {1920, 1080}, {2560, 1440}}) {
            Map<String, Rect> expected = chrome(size[0], size[1]);
            Map<String, Rect> actual = engine(size[0], size[1]);
            for (var e : expected.entrySet()) {
                if (knownDivergence(e.getKey())) continue;
                Rect c = e.getValue();
                Rect a = actual.get(e.getKey());
                checked++;
                if (a == null) {
                    failures.add(size[0] + "x" + size[1] + " " + e.getKey() + ": エンジンに無い");
                    continue;
                }
                float d = Math.max(Math.max(Math.abs(a.x - c.x), Math.abs(a.y - c.y)),
                        Math.max(Math.abs(a.w - c.w), Math.abs(a.h - c.h)));
                if (d > TOLERANCE) {
                    failures.add(String.format("%dx%d %s: ours [%.2f %.2f %.2fx%.2f] chrome [%.2f %.2f %.2fx%.2f]",
                            size[0], size[1], e.getKey(), a.x, a.y, a.w, a.h, c.x, c.y, c.w, c.h));
                }
            }
        }
        assertTrue(checked > 250, "比べた要素が少なすぎる: " + checked);
        assertTrue(failures.isEmpty(), failures.size() + "/" + checked + " 個の要素が Chrome と違う:\n" + String.join("\n", failures));
    }

    /** 既知の差が本当に差のままか（直ったら台帳から外す）。バッジは Regular で測っているので Chrome より狭い。 */
    @Test
    void knownDivergencesAreStillDivergent() throws Exception {
        Rect chromeBadge = chrome(1920, 1080).get("badge-pill[0]");
        Rect ourBadge = engine(1920, 1080).get("badge-pill[0]");
        assertTrue(chromeBadge.w() - ourBadge.w() > 1.0f, "font-weight: 500 の照合が Chrome と揃ったなら台帳を更新する");
    }
}
