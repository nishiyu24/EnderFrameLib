package com.nishiyu.enderframelib.core.text.font;

import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.text.model.FontStyle;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * システムの書体による測定が Chrome と一致するか。
 *
 * 期待値は Chrome（Windows 11、日本語）で測った値。
 * chrome-win-ja-text-metrics.txt は font-kerning: none、chrome-win-ja-text-metrics-kerning.txt は既定（詰めあり）。
 * 書体・太さ（100〜900）・斜体・大きさ（16 / 13px）・総称ファミリ・字単位の代替（日本語、記号）を網羅する。
 * Chrome が使った書体がこの環境に無い行は飛ばす（Windows 以外では全体を飛ばす）。
 */
public class ChromeTextMetricsParityTest {

    /** Chrome の送り幅は 1/64px 単位の和なので、33 字で最大 0.26px ほどずれ得る。 */
    private static final float WIDTH_TOLERANCE = 0.35f;

    record Row(String family, int weight, boolean italic, int size, String text,
               float width, float content, float ascent, float line, boolean kerning) {}

    private static SystemFonts resolver;
    private static List<Row> rows;

    @BeforeAll
    static void load() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"),
                "Chrome の参照値は Windows の書体で測ったもの");
        SystemFonts system = SystemFonts.system();
        Assumptions.assumeTrue(system.hasFamily("Segoe UI") && system.hasFamily("Noto Sans JP"),
                "参照値を測った環境の書体が無い");
        // 参照値は日本語の Chrome で測った（総称ファミリの割り当てが日本語の値）
        resolver = system.withLocale(Locale.JAPANESE);
        rows = new ArrayList<>();
        read("/fonts/chrome-win-ja-text-metrics.txt", false);
        read("/fonts/chrome-win-ja-text-metrics-kerning.txt", true);
    }

    private static void read(String resource, boolean kerning) throws Exception {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(Objects.requireNonNull(
                ChromeTextMetricsParityTest.class.getResourceAsStream(resource)), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\\|");
                if (p.length < 9) continue;
                rows.add(new Row(p[0], Integer.parseInt(p[1]), p[2].equals("italic"), Integer.parseInt(p[3]), p[4],
                        value(p[5]), value(p[6]), value(p[7]), value(p[8]), kerning));
            }
        }
    }

    private static float value(String kv) {
        return Float.parseFloat(kv.substring(kv.indexOf('=') + 1));
    }

    private static FontFace faceFor(Row row) {
        return resolver.resolve(row.family().equals("initial") ? null : row.family());
    }

    private static FontStyle styleFor(Row row) {
        return new FontStyle(row.size(), row.weight() >= 600, row.italic(), 0, row.weight(), row.kerning());
    }

    private static boolean latinOnly(Row row) {
        return row.text().chars().allMatch(c -> c < 0x80);
    }

    @Test
    void textWidthsMatchChrome() {
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (Row row : rows) {
            FontFace face = faceFor(row);
            float w = face.advance(row.text(), styleFor(row));
            float expected = expectedWidth(row, face);
            checked++;
            if (Math.abs(w - expected) > WIDTH_TOLERANCE) {
                failures.add(String.format("%s %d %s %dpx%s '%s': ours=%.3f expected=%.3f (chrome=%.3f)", row.family(), row.weight(),
                        row.italic() ? "italic" : "normal", row.size(), row.kerning() ? " kern" : "", row.text(), w, expected, row.width()));
            }
        }
        assertTrue(failures.isEmpty(), failures.size() + "/" + checked + " widths differ from Chrome:\n" + String.join("\n", failures));
    }

    /**
     * 期待する幅。ほとんどは Chrome の値そのもの。
     *
     * 例外は 500 の面が無いファミリへの 500 の指定。CSS Fonts 4 §5.2 は 400〜500 の指定に対して
     * 「500 までの重い面 → 軽い面」の順で探す（Segoe UI なら Regular）。Chrome（Windows）はシステムの書体を
     * DirectWrite の照合に任せるので Semibold を選ぶ。ここは仕様に従うので、期待値は同じ行の 400 の値
     * （= Regular で測った Chrome の幅）にする。
     */
    private static float expectedWidth(Row row, FontFace face) {
        if (row.weight() == 500 && face instanceof OutlineFontFace outline
                && outline.primaryFace(styleFor(row)).record().weight() != 500) {
            for (Row r : rows) {
                if (r.weight() == 400 && r.italic() == row.italic() && r.size() == row.size() && r.kerning() == row.kerning()
                        && r.family().equals(row.family()) && r.text().equals(row.text())) {
                    return r.width();
                }
            }
        }
        return row.width();
    }

    /** 仕様（§5.2）で決まる面を選んでいること。Segoe UI の 500 は Regular（400）、600 は Semibold。 */
    @Test
    void weightMatchingFollowsCssFonts4() {
        OutlineFontFace segoe = (OutlineFontFace) resolver.resolve("'Segoe UI'");
        assertTrue(segoe.primaryFace(new FontStyle(16, false, false, 0, 500)).record().weight() == 400);
        assertTrue(segoe.primaryFace(new FontStyle(16, true, false, 0, 600)).record().weight() == 600);
        assertTrue(segoe.primaryFace(new FontStyle(16, false, false, 0, 100)).record().weight() == 300,
                "400 未満は軽い側から探し、無ければ重い側の最も近い面（Light）");
        OutlineFontFace noto = (OutlineFontFace) resolver.resolve("'Noto Sans JP'");
        assertTrue(noto.primaryFace(new FontStyle(16, true, false, 0, 600)).record().weight() == 700,
                "可変フォントは名前付きインスタンスで照合する（SemiBold が無いので Bold）");
    }

    @Test
    void verticalMetricsMatchChrome() {
        List<String> failures = new ArrayList<>();
        for (Row row : rows) {
            FontFace face = faceFor(row);
            FontStyle style = styleFor(row);
            float ascent = face.ascent(style);
            float content = ascent + face.descent(style);
            float line = face.normalLineHeight(style);
            // 日本語・記号の行は代替書体の寸法も行の高さに入る（Chrome）。ここでは第 1 書体の寸法だけを比べる
            boolean lineComparable = latinOnly(row);
            if (Math.abs(ascent - row.ascent()) > 0.01f || Math.abs(content - row.content()) > 0.01f
                    || (lineComparable && Math.abs(line - row.line()) > 0.01f)) {
                failures.add(String.format("%s %d %dpx: ascent %.1f/%.1f content %.1f/%.1f line %.1f/%.1f",
                        row.family(), row.weight(), row.size(), ascent, row.ascent(), content, row.content(), line, row.line()));
            }
        }
        assertTrue(failures.isEmpty(), failures.size() + " rows differ from Chrome:\n" + String.join("\n", failures));
    }
}
