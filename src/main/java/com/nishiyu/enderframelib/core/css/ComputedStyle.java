package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.css.model.*;



import com.nishiyu.enderframelib.core.css.*;
import com.nishiyu.enderframelib.core.css.model.*;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.nishiyu.enderframelib.core.css.model.Overflow;
import com.nishiyu.enderframelib.core.css.model.TextAlign;
import com.nishiyu.enderframelib.core.css.model.BorderStyle;

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

    // 算出スタイルは要素ごとに作られ、描画に使われない要素（display: none の部分木など）も多い。
    // 参照キャッシュは最初の参照まで確保しない（要素 1 つあたり ConcurrentHashMap 4 個の即時確保をやめた）
    /** プロパティ名 -> 単位と数値に分解済みの長さ。参照時に 1 回だけ作る。 */
    private volatile Map<String, LengthResolver.CompiledLength> lengthCache;
    /** プロパティ名 -> ARGB。ビューポートに依存しないため値そのものをキャッシュできる。 */
    private volatile Map<String, Integer> colorCache;
    /** プロパティ名 -> 数値。同上。 */
    private volatile Map<String, Float> numberCache;
    /** プロパティ名 -> キーワードの enum。同上。 */
    private volatile Map<String, Enum<?>> keywordCache;

    private static <V> Map<String, V> newCache() {
        return new ConcurrentHashMap<>(8);
    }

    private Map<String, LengthResolver.CompiledLength> lengths() {
        Map<String, LengthResolver.CompiledLength> m = lengthCache;
        if (m == null) {
            synchronized (this) {
                if ((m = lengthCache) == null) lengthCache = m = newCache();
            }
        }
        return m;
    }

    private Map<String, Integer> colors() {
        Map<String, Integer> m = colorCache;
        if (m == null) {
            synchronized (this) {
                if ((m = colorCache) == null) colorCache = m = newCache();
            }
        }
        return m;
    }

    private Map<String, Float> numbers() {
        Map<String, Float> m = numberCache;
        if (m == null) {
            synchronized (this) {
                if ((m = numberCache) == null) numberCache = m = newCache();
            }
        }
        return m;
    }

    private Map<String, Enum<?>> keywords() {
        Map<String, Enum<?>> m = keywordCache;
        if (m == null) {
            synchronized (this) {
                if ((m = keywordCache) == null) keywordCache = m = newCache();
            }
        }
        return m;
    }

    public ComputedStyle(Map<String, String> properties, float elementFontSize, float rootFontSize) {
        this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        this.elementFontSize = elementFontSize;
        this.rootFontSize = rootFontSize;
    }

    public ComputedStyle(Map<String, String> properties) {
        this(properties, DEFAULT_FONT_SIZE, DEFAULT_FONT_SIZE);
    }

    /**
     * 算出値の {@code font-size}（{@link ValueComputer} が px に解いたもの）からこの要素の
     * フォントサイズを読んで算出スタイルを作る。宣言が無ければ親のフォントサイズを継承する。
     *
     * 何が壊れていたか: 以前はここで {@code font-size} を解いていたが、ビューポートを 0 で渡し
     * 結果を int に切り捨て、0 以下を無効としていたため、{@code 2vw} が無効扱いで既定の 16px に、
     * {@code 6.4px} が 6px に、{@code 0} が 16px になっていた。
     */
    public static ComputedStyle of(Map<String, String> properties, float parentFontSize, float rootFontSize) {
        float own = parentFontSize;
        String fontSize = properties.get(CssProperty.FONT_SIZE);
        if (fontSize != null) {
            own = ValueComputer.resolveFontSize(fontSize,
                    new ValueComputer.Context(Map.of(), parentFontSize, rootFontSize, 0, 0));
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

    /** この要素の行の高さ（px）。{@code rlh} の基準になるルート要素の値を取るのに使う。 */
    public float lineHeightPx() {
        return ValueComputer.lineHeightPx(properties.get(CssProperty.LINE_HEIGHT), elementFontSize, rootFontSize, 0, 0);
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

    /** {@code contain} の値のビット（{@link #containment()}）。 */
    public static final int CONTAIN_SIZE = 1, CONTAIN_INLINE_SIZE = 2, CONTAIN_LAYOUT = 4, CONTAIN_PAINT = 8, CONTAIN_STYLE = 16;

    private volatile int containment = -1;

    /**
     * 有効な containment（{@code contain} と {@code content-visibility} の和）。{@code strict} は size layout paint style、
     * {@code content} は layout paint style。{@code content-visibility: hidden} は size layout paint、{@code auto} は layout paint。
     */
    public int containment() {
        int cached = containment;
        if (cached >= 0) return cached;
        int bits = 0;
        String declared = properties.get(CssProperty.CONTAIN);
        if (declared != null) {
            for (String token : declared.trim().toLowerCase(java.util.Locale.ROOT).split("\s+")) {
                switch (token) {
                    case "strict" -> bits |= CONTAIN_SIZE | CONTAIN_LAYOUT | CONTAIN_PAINT | CONTAIN_STYLE;
                    case "content" -> bits |= CONTAIN_LAYOUT | CONTAIN_PAINT | CONTAIN_STYLE;
                    case "size" -> bits |= CONTAIN_SIZE;
                    case "inline-size" -> bits |= CONTAIN_INLINE_SIZE;
                    case "layout" -> bits |= CONTAIN_LAYOUT;
                    case "paint" -> bits |= CONTAIN_PAINT;
                    case "style" -> bits |= CONTAIN_STYLE;
                    default -> {}
                }
            }
        }
        switch (keywordOf(CssProperty.CONTENT_VISIBILITY, CssKeywords.ContentVisibility.class)) {
            case HIDDEN -> bits |= CONTAIN_SIZE | CONTAIN_LAYOUT | CONTAIN_PAINT;
            case AUTO -> bits |= CONTAIN_LAYOUT | CONTAIN_PAINT;
            default -> {}
        }
        containment = bits;
        return bits;
    }

    /** {@code content-visibility: hidden}: 中身を箱の木に入れない。 */
    public boolean skipsContents() {
        return keywordOf(CssProperty.CONTENT_VISIBILITY, CssKeywords.ContentVisibility.class)
                == CssKeywords.ContentVisibility.HIDDEN;
    }

    private volatile Map<String, String> customProperties;

    /** カスタムプロパティ（{@code --x}）だけの読み取り専用ビュー。{@code style()} コンテナクエリが読む。 */
    public Map<String, String> customProperties() {
        Map<String, String> cached = customProperties;
        if (cached == null) {
            Map<String, String> custom = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : properties.entrySet()) {
                if (e.getKey().startsWith("--")) custom.put(e.getKey(), e.getValue());
            }
            cached = Collections.unmodifiableMap(custom);
            customProperties = cached;
        }
        return cached;
    }

    /** 宣言マップの読み取り専用ビュー。走査が必要な場所だけで使う。 */
    public Map<String, String> asMap() {
        return properties;
    }

    /**
     * 長さをピクセルに解決する。{@code maxPixels} はパーセント指定の基準。
     * 結果は丸めない（軸 3: 丸めは描画の 1 箇所だけ）。以前は int に切り捨てていた。
     */
    public float length(String propertyName, float maxPixels, float rootW, float rootH) {
        String val = properties.get(propertyName);
        if (val == null) return 0;
        return compiledLength(propertyName, val).resolve(maxPixels, rootW, rootH, elementFontSize, rootFontSize);
    }

    /** 宣言が無い場合に {@code defaultPixels} を返す {@link #length}。 */
    public float lengthOrDefault(String propertyName, float defaultPixels, float maxPixels, float rootW, float rootH) {
        if (!properties.containsKey(propertyName)) return defaultPixels;
        return length(propertyName, maxPixels, rootW, rootH);
    }

    private LengthResolver.CompiledLength compiledLength(String propertyName, String value) {
        Map<String, LengthResolver.CompiledLength> cache = lengths();
        LengthResolver.CompiledLength cached = cache.get(propertyName);
        if (cached != null) return cached;
        LengthResolver.CompiledLength compiled = LengthResolver.compile(value);
        cache.put(propertyName, compiled);
        return compiled;
    }

    /** 色を ARGB に解決する。宣言が無ければ {@code defaultColor}。 */
    public int color(String propertyName, int defaultColor) {
        String val = properties.get(propertyName);
        if (val == null) return defaultColor;
        Map<String, Integer> cache = colors();
        Integer cached = cache.get(propertyName);
        if (cached != null) return cached;
        int parsed = ColorParser.parseColor(val);
        cache.put(propertyName, parsed);
        return parsed;
    }

    /** 単位なしの数値（{@code opacity} / {@code flex-grow} / {@code z-index} など）。 */
    public float number(String propertyName, float defaultValue) {
        String val = properties.get(propertyName);
        if (val == null) return defaultValue;
        Map<String, Float> cache = numbers();
        Float cached = cache.get(propertyName);
        if (cached != null) return cached;
        float parsed;
        try {
            parsed = Float.parseFloat(val.trim());
        } catch (NumberFormatException e) {
            parsed = defaultValue;
        }
        cache.put(propertyName, parsed);
        return parsed;
    }

    /**
     * キーワードだけを値に取るプロパティの算出値を、{@link PropertyRegistry} が宣言した enum で返す。
     * 書かれていない値・知らない値はそのプロパティの初期値。
     */
    public <E extends Enum<E> & CssKeywords.Keyword> E keywordOf(String propertyName, Class<E> type) {
        Map<String, Enum<?>> cache = keywords();
        Enum<?> cached = cache.get(propertyName);
        if (cached != null && type.isInstance(cached)) return type.cast(cached);
        E parsed = CssKeywords.parse(type, properties.get(propertyName));
        if (parsed == null) parsed = PropertyRegistry.initialKeyword(propertyName, type);
        cache.put(propertyName, parsed);
        return parsed;
    }

    /** {@code box-sizing: border-box} か。 */
    public boolean isBorderBox() {
        return keywordOf(CssProperty.BOX_SIZING, CssKeywords.BoxSizing.class) == CssKeywords.BoxSizing.BORDER_BOX;
    }

    /** 横方向の {@code overflow}。{@code overflow-x} が無ければショートハンド {@code overflow} の最初の値。 */
    public Overflow overflowX() {
        return overflowAxis(CssProperty.OVERFLOW_X, 0);
    }

    /** 縦方向の {@code overflow}。{@code overflow-y} が無ければショートハンドの 2 つ目（1 つなら同じ値）。 */
    public Overflow overflowY() {
        return overflowAxis(CssProperty.OVERFLOW_Y, 1);
    }

    private Overflow overflowAxis(String property, int shorthandIndex) {
        Overflow declared = declaredOverflowAxis(property, shorthandIndex);
        // paint containment は中身を padding box で切る（overflow: clip と同じ。スクロールコンテナにはならない）
        if (declared == Overflow.VISIBLE && (containment() & CONTAIN_PAINT) != 0) return Overflow.CLIP;
        return declared;
    }

    private Overflow declaredOverflowAxis(String property, int shorthandIndex) {
        if (properties.containsKey(property)) return keywordOf(property, Overflow.class);
        String shorthand = properties.get(CssProperty.OVERFLOW);
        if (shorthand == null) return Overflow.VISIBLE;
        String[] parts = shorthand.trim().split("\\s+");
        String part = parts[Math.min(shorthandIndex, parts.length - 1)];
        Overflow parsed = CssKeywords.parse(Overflow.class, part);
        return parsed != null ? parsed : Overflow.VISIBLE;
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
