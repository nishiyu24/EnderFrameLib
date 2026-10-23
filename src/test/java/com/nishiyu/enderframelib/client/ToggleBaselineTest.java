package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.core.text.font.SystemFonts;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * チェックボックス・ラジオボタンを含む行の高さと位置を Chrome の実測と比べる（toggle-baseline.html の 13 通り:
 * UA の既定のマージン・マージン 0・大きさ 30px・文字の大きさ 8 / 40px・下や上だけのマージン・appearance: none・
 * line-height: 1・文字と並べた行・vertical-align: middle）。
 *
 * Chrome の規則（実測から）:
 * <ul>
 *   <li>UA の部品で描くチェックボックス・ラジオボタンのベースラインは border box の下端（下マージンはその下に出る）</li>
 *   <li>行の高さと文字の高さの差（leading）は、半分を整数のピクセルに切り捨てて上に、残りを下に置く</li>
 * </ul>
 *
 * 何が壊れていたか: 以前はベースラインを下マージンの端にし、leading を 0.5px ずつ分けていたので、
 * チェックボックスだけの行が Chrome の 20px に対して 22.5px あり、行を重ねるほど下にずれていった。
 */
public class ToggleBaselineTest {

    @Test
    void linesWithTogglesMatchChrome() throws Exception {
        SystemFonts fonts = ChromeRects.requireFonts("Arial");
        Map<String, ChromeRects.Rect> expected = ChromeRects.chrome("/inline-chrome/toggle-baseline-300.txt");
        Map<String, ChromeRects.Rect> actual = ChromeRects.engine("src/test/resources/inline-chrome/toggle-baseline.html", 300, 800, fonts);
        List<String> failures = ChromeRects.differences(expected, actual, 0.5f);
        assertTrue(expected.size() >= 27, "比べた要素が少なすぎる: " + expected.size());
        assertTrue(failures.isEmpty(), failures.size() + "/" + expected.size() + " 個の要素が Chrome と違う:\n" + String.join("\n", failures));
    }

    /** レンジの行（作者の枠線と padding で広がる箱・accent-color・appearance: none・幅の指定）。 */
    @Test
    void linesWithRangesMatchChrome() throws Exception {
        SystemFonts fonts = ChromeRects.requireFonts("Arial");
        Map<String, ChromeRects.Rect> expected = ChromeRects.chrome("/inline-chrome/range-layout-300.txt");
        Map<String, ChromeRects.Rect> actual = ChromeRects.engine("src/test/resources/inline-chrome/range-layout.html", 300, 200, fonts);
        List<String> failures = ChromeRects.differences(expected, actual, 0.5f);
        assertTrue(failures.isEmpty(), failures.size() + "/" + expected.size() + " 個の要素が Chrome と違う:\n" + String.join("\n", failures));
    }
}
