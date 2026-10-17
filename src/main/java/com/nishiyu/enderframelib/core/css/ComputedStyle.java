package com.nishiyu.enderframelib.core.css;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 算出スタイルを保持する不変の値型。
 *
 * 以前は {@code Map<String, String>} が生のまま配られていたため、レイアウトエンジンが
 * キャッシュを書き換えられたうえ、参照のたびに値を文字列から再パースしていた。
 *
 * ここでは
 * <ul>
 *   <li>宣言マップを不変にして、書き換えを型で禁じる</li>
 *   <li>長さ・色・数値を <b>最初の参照時に 1 回だけ</b> パースし、結果を保持する
 *       （長さはビューポート依存なので「単位と数値への分解」までを 1 回で済ませ、
 *        以降は算術だけで解決する）</li>
 *   <li>型付きアクセサ（{@link #length}/{@link #color}/{@link #number}/{@link #keyword}）を提供する</li>
 * </ul>
 * を行う。
 */
public final class ComputedStyle {

    /** 既定のルートフォントサイズ（px）。 */
    public static final float DEFAULT_FONT_SIZE = 16.0f;

    private final Map<String, String> properties;
    private final float elementFontSize;
    private final float rootFontSize;

    /** プロパティ名 -> 単位と数値に分解済みの長さ。参照時に 1 回だけ作る。 */
    private final Map<String, LengthResolver.CompiledLength> lengthCache = new ConcurrentHashMap<>();
    /** プロパティ名 -> ARGB。ビューポートに依存しないため値そのものをキャッシュできる。 */
    private final Map<String, Integer> colorCache = new ConcurrentHashMap<>();
    /** プロパティ名 -> 数値。同上。 */
    private final Map<String, Float> numberCache = new ConcurrentHashMap<>();

    public ComputedStyle(Map<String, String> properties, float elementFontSize, float rootFontSize) {
        this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        this.elementFontSize = elementFontSize;
        this.rootFontSize = rootFontSize;
    }

    public ComputedStyle(Map<String, String> properties) {
        this(properties, DEFAULT_FONT_SIZE, DEFAULT_FONT_SIZE);
    }

    /**
     * 親のフォントサイズを基準に、この要素のフォントサイズを解決した算出スタイルを作る。
     * {@code em} はこの要素のフォントサイズ、{@code rem} はルートのフォントサイズが基準になる。
     */
    public static ComputedStyle of(Map<String, String> properties, float parentFontSize, float rootFontSize) {
        float own = parentFontSize;
        String fontSize = properties.get(CssProperty.FONT_SIZE);
        if (fontSize != null) {
            // font-size 自身の em / % は「親の」フォントサイズが基準
            int resolved = LengthResolver.parseDim(fontSize, (int) parentFontSize, 0, 0, parentFontSize, rootFontSize);
            if (resolved > 0) own = resolved;
        }
        return new ComputedStyle(properties, own, rootFontSize);
    }

    /** この要素のフォントサイズ（px）。 */
    public float fontSizePx() {
        return elementFontSize;
    }

    public float rootFontSizePx() {
        return rootFontSize;
    }

    public String get(String propertyName) {
        return properties.get(propertyName);
    }

    public String getOrDefault(String propertyName, String defaultValue) {
        String val = properties.get(propertyName);
        return val != null ? val : defaultValue;
    }

    public boolean containsKey(String propertyName) {
        return properties.containsKey(propertyName);
    }

    /** 宣言マップの読み取り専用ビュー。走査が必要な場所だけで使う。 */
    public Map<String, String> asMap() {
        return properties;
    }

    /** 長さをピクセルに解決する。{@code maxPixels} はパーセント指定の基準。 */
    public int length(String propertyName, int maxPixels, int rootW, int rootH) {
        String val = properties.get(propertyName);
        if (val == null) return 0;
        return compiledLength(propertyName, val).resolve(maxPixels, rootW, rootH, elementFontSize, rootFontSize);
    }

    /** 宣言が無い場合に {@code defaultPixels} を返す {@link #length}。 */
    public int lengthOrDefault(String propertyName, int defaultPixels, int maxPixels, int rootW, int rootH) {
        if (!properties.containsKey(propertyName)) return defaultPixels;
        return length(propertyName, maxPixels, rootW, rootH);
    }

    private LengthResolver.CompiledLength compiledLength(String propertyName, String value) {
        LengthResolver.CompiledLength cached = lengthCache.get(propertyName);
        if (cached != null) return cached;
        LengthResolver.CompiledLength compiled = LengthResolver.compile(value);
        lengthCache.put(propertyName, compiled);
        return compiled;
    }

    /** 色を ARGB に解決する。宣言が無ければ {@code defaultColor}。 */
    public int color(String propertyName, int defaultColor) {
        String val = properties.get(propertyName);
        if (val == null) return defaultColor;
        Integer cached = colorCache.get(propertyName);
        if (cached != null) return cached;
        int parsed = ColorParser.parseColor(val);
        colorCache.put(propertyName, parsed);
        return parsed;
    }

    /** 単位なしの数値（{@code opacity} / {@code flex-grow} / {@code z-index} など）。 */
    public float number(String propertyName, float defaultValue) {
        String val = properties.get(propertyName);
        if (val == null) return defaultValue;
        Float cached = numberCache.get(propertyName);
        if (cached != null) return cached;
        float parsed;
        try {
            parsed = Float.parseFloat(val.trim());
        } catch (NumberFormatException e) {
            parsed = defaultValue;
        }
        numberCache.put(propertyName, parsed);
        return parsed;
    }

    /** キーワード値（小文字・前後空白なしに正規化）。 */
    public String keyword(String propertyName, String defaultValue) {
        String val = properties.get(propertyName);
        if (val == null) return defaultValue;
        return val.trim().toLowerCase();
    }

    /** キーワード値が {@code expected} と一致するか。 */
    public boolean is(String propertyName, String expected) {
        return expected.equals(keyword(propertyName, null));
    }
}
