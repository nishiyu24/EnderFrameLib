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

    /** 登録済みプロパティの値。{@link CssPropertyId} の添字で引く。宣言が無ければ null。 */
    private final String[] values;
    /** 添字を持たない宣言（{@code --*} のカスタムプロパティなど）。無ければ null で、通常の要素では確保しない。 */
    private final Map<String, String> extras;
    private final int size;
    private final StyleMap properties;
    private final float elementFontSize;
    private final float rootFontSize;

    // 算出スタイルは要素ごとに作られ、描画に使われない要素（display: none の部分木など）も多い。
    // 参照結果のキャッシュは最初の参照まで確保しない。ロックも ConcurrentHashMap も使わず、不変の小さな配列を
    // CAS で差し替える（読み手は配列をなめるだけ。結果は不変の値なので、競って片方の登録が落ちても再計算するだけで正しい）。

    /** 型付き参照（長さ・色・数値・キーワード）の結果。鍵は {@code 添字 * 4 + 種類}。 */
    private volatile Memo memo;

    private static final int KIND_LENGTH = 0, KIND_COLOR = 1, KIND_NUMBER = 2, KIND_KEYWORD = 3;

    /** 不変の「整数の鍵 → 値」の小さな表。更新は新しい表を作って差し替える。 */
    private static final class Memo {
        final int[] keys;
        final Object[] vals;

        Memo(int[] keys, Object[] vals) {
            this.keys = keys;
            this.vals = vals;
        }

        Object get(int key) {
            int[] k = keys;
            for (int i = 0; i < k.length; i++) if (k[i] == key) return vals[i];
            return null;
        }

        Memo with(int key, Object val) {
            int n = keys.length;
            for (int i = 0; i < n; i++) {
                if (keys[i] == key) {
                    Object[] v = vals.clone();
                    v[i] = val;
                    return new Memo(keys, v);
                }
            }
            int[] k = new int[n + 1];
            Object[] v = new Object[n + 1];
            System.arraycopy(keys, 0, k, 0, n);
            System.arraycopy(vals, 0, v, 0, n);
            k[n] = key;
            v[n] = val;
            return new Memo(k, v);
        }
    }

    private static final java.lang.invoke.VarHandle MEMO;
    private static final java.lang.invoke.VarHandle PAINT;

    static {
        try {
            var lookup = java.lang.invoke.MethodHandles.lookup();
            MEMO = lookup.findVarHandle(ComputedStyle.class, "memo", Memo.class);
            PAINT = lookup.findVarHandle(ComputedStyle.class, "paint", Object.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private Object cached(int id, int kind) {
        Memo m = memo;
        return m == null ? null : m.get(id * 4 + kind);
    }

    private void remember(int id, int kind, Object value) {
        int key = id * 4 + kind;
        for (; ; ) {
            Memo m = memo;
            Memo n = m == null ? new Memo(new int[]{key}, new Object[]{value}) : m.with(key, value);
            if (MEMO.compareAndSet(this, m, n)) return;
        }
    }

    /**
     * 描画側が、この算出スタイルだけから決まる結果（アニメーション定義の解決など）を覚える置き場。
     * スタイルは作り直されるたびに別のインスタンスになるので、スタイルが変われば自然に捨てられる。
     * 鍵は {@code equals} / {@code hashCode} を持つ値（record など）。中身は {@link ObjMemo}（小さいうち）か
     * {@link ConcurrentHashMap}（多くの鍵が溜まる、多数の箱が共有するスタイルだけ）。
     */
    private volatile Object paint;

    private static final int OBJ_MEMO_LIMIT = 8;

    /** 不変の「鍵 → 値」の小さな表（鍵は {@code equals} で比べる）。 */
    private static final class ObjMemo {
        final Object[] keys;
        final Object[] vals;

        ObjMemo(Object[] keys, Object[] vals) {
            this.keys = keys;
            this.vals = vals;
        }

        Object get(Object key) {
            Object[] k = keys;
            for (int i = 0; i < k.length; i++) if (k[i] == key || k[i].equals(key)) return vals[i];
            return null;
        }

        ObjMemo with(Object key, Object val) {
            int n = keys.length;
            Object[] k = new Object[n + 1];
            Object[] v = new Object[n + 1];
            System.arraycopy(keys, 0, k, 0, n);
            System.arraycopy(vals, 0, v, 0, n);
            k[n] = key;
            v[n] = val;
            return new ObjMemo(k, v);
        }

        ConcurrentHashMap<Object, Object> toMap() {
            ConcurrentHashMap<Object, Object> m = new ConcurrentHashMap<>(keys.length * 4);
            for (int i = 0; i < keys.length; i++) m.put(keys[i], vals[i]);
            return m;
        }
    }

    /**
     * {@code key} の結果を、この算出スタイルに覚える（無ければ {@code compute} で作る。{@code null} は覚えない）。
     * 競ったときは同じ結果が 2 回計算されうる（結果は同じなので、先に登録されたものを使う）。{@code compute} の中で
     * 同じスタイルの {@code paintMemo} を呼んでもよい。
     */
    @SuppressWarnings("unchecked")
    public <T> T paintMemo(Object key, java.util.function.Function<Object, T> compute) {
        Object cur = paint;
        if (cur instanceof ConcurrentHashMap<?, ?> big) {
            Object hit = ((ConcurrentHashMap<Object, Object>) big).get(key);
            if (hit != null) return (T) hit;
        } else if (cur != null) {
            Object hit = ((ObjMemo) cur).get(key);
            if (hit != null) return (T) hit;
        }
        T value = compute.apply(key);
        if (value == null) return null;
        for (; ; ) {
            cur = paint;
            if (cur instanceof ConcurrentHashMap<?, ?> big) {
                Object prev = ((ConcurrentHashMap<Object, Object>) big).putIfAbsent(key, value);
                return prev != null ? (T) prev : value;
            }
            ObjMemo m = (ObjMemo) cur;
            if (m != null) {
                Object hit = m.get(key);
                if (hit != null) return (T) hit; // 競った相手が先に登録した
            }
            Object next;
            if (m != null && m.keys.length >= OBJ_MEMO_LIMIT) {
                ConcurrentHashMap<Object, Object> map = m.toMap();
                map.putIfAbsent(key, value);
                next = map;
            } else {
                next = m == null ? new ObjMemo(new Object[]{key}, new Object[]{value}) : m.with(key, value);
            }
            if (PAINT.compareAndSet(this, cur, next)) return value;
        }
    }

    public ComputedStyle(Map<String, String> source, float elementFontSize, float rootFontSize) {
        if (source instanceof StyleMap other) {
            // 算出スタイルの複製: 配列をそのままコピーするだけで、マップは作らない
            this.values = other.owner.values.clone();
            this.extras = other.owner.extras;
            this.size = other.owner.size;
        } else {
            String[] v = new String[CssPropertyId.count()];
            Map<String, String> x = null;
            int n = 0;
            for (Map.Entry<String, String> e : source.entrySet()) {
                int id = CssPropertyId.of(e.getKey());
                if (id != CssPropertyId.NONE) {
                    if (v[id] == null) n++;
                    v[id] = e.getValue();
                } else {
                    if (x == null) x = new LinkedHashMap<>();
                    if (x.put(e.getKey(), e.getValue()) == null) n++;
                }
            }
            this.values = v;
            this.extras = x == null ? null : Collections.unmodifiableMap(x);
            this.size = n;
        }
        this.properties = new StyleMap(this);
        this.elementFontSize = elementFontSize;
        this.rootFontSize = rootFontSize;
    }

    /**
     * 登録済みの CSS プロパティ名に振る固定の添字。宣言を名前をキーにしたマップではなく、この添字で引く配列に持つための表で、
     * 起動後は読み取り専用。登録に無い名前（{@code --*} のカスタムプロパティなど）は {@link #NONE}。
     */
    private static final class CssPropertyId {
        static final int NONE = -1;
        /** 名前 → 添字の、開番地法のハッシュ表（{@code HashMap<String, Integer>} の箱詰めと間接参照を避ける）。起動後は読み取り専用。 */
        private static final String[] KEYS;
        private static final int[] VALS;
        private static final int MASK;
        private static final String[] NAMES;
        /** 添字ごとの、描画だけに効く（再レイアウトが要らない）プロパティか。 */
        private static final boolean[] PAINT_ONLY;
        /** 添字ごとの、子孫が値を受け継ぐ（未登録は安全側に true）プロパティか。 */
        private static final boolean[] INHERITED;

        static {
            var all = PropertyRegistry.all();
            String[] names = new String[all.size()];
            int cap = Integer.highestOneBit(Math.max(8, all.size() * 4 - 1)) << 1;
            String[] keys = new String[cap];
            int[] vals = new int[cap];
            int i = 0;
            for (PropertyRegistry.Property p : all) {
                String name = p.name();
                int h = name.hashCode();
                int at = (h ^ (h >>> 16)) & (cap - 1);
                while (keys[at] != null) at = (at + 1) & (cap - 1);
                keys[at] = name;
                vals[at] = i;
                names[i++] = name;
            }
            KEYS = keys;
            VALS = vals;
            MASK = cap - 1;
            NAMES = names;
            PAINT_ONLY = new boolean[names.length];
            INHERITED = new boolean[names.length];
            for (int k = 0; k < names.length; k++) {
                PAINT_ONLY[k] = PropertyRegistry.isPaintOnly(names[k]);
                INHERITED[k] = PropertyRegistry.isInherited(names[k]);
            }
        }

        static int count() {
            return NAMES.length;
        }

        static int of(String name) {
            if (name == null) return NONE;
            int h = name.hashCode(); // 文字列の定数は intern 済みで、ハッシュはキャッシュ済み
            int at = (h ^ (h >>> 16)) & MASK;
            String[] keys = KEYS;
            for (; ; ) {
                String k = keys[at];
                if (k == null) return NONE;
                if (k == name || (k.hashCode() == h && k.equals(name))) return VALS[at];
                at = (at + 1) & MASK;
            }
        }

        static String name(int id) {
            return NAMES[id];
        }
    }

    /** 2 つの算出スタイルの宣言の差分を 1 つずつ渡す先。 */
    public interface DiffVisitor {
        /** @param paintOnly 描画だけに効くプロパティか @param inherited 子孫が値を受け継ぐプロパティか（未登録・{@code --*} は true） */
        void changed(String name, boolean paintOnly, boolean inherited);
    }

    /**
     * 宣言が違うプロパティを {@code visitor} に渡す。マップとして走査して引き直すのではなく、添字ごとの配列を直接比べる。
     * 違いが無ければ何も呼ばずに false（呼び出し側が先に {@link #sameDeclarations} で足切りしてもよい）。
     */
    public static void diff(ComputedStyle a, ComputedStyle b, DiffVisitor visitor) {
        String[] x = a.values, y = b.values;
        for (int i = 0; i < x.length; i++) {
            if (x[i] == y[i]) continue;
            if (x[i] != null && x[i].equals(y[i])) continue;
            visitor.changed(CssPropertyId.name(i), CssPropertyId.PAINT_ONLY[i], CssPropertyId.INHERITED[i]);
        }
        if (a.extras == null && b.extras == null) return;
        Map<String, String> ex = a.extras != null ? a.extras : Map.of();
        Map<String, String> ey = b.extras != null ? b.extras : Map.of();
        for (Map.Entry<String, String> e : ex.entrySet()) {
            if (!java.util.Objects.equals(e.getValue(), ey.get(e.getKey()))) visitor.changed(e.getKey(), false, true);
        }
        for (String key : ey.keySet()) {
            if (!ex.containsKey(key)) visitor.changed(key, false, true);
        }
    }

    /** 宣言が全く同じか（フォントサイズは見ない）。 */
    public static boolean sameDeclarations(ComputedStyle a, ComputedStyle b) {
        return a == b || a.properties.equals(b.properties);
    }

    /**
     * 宣言の読み取り専用ビュー。コピーを作らず、{@link #equals} は配列同士の比較で済ませる。
     * 走査順は登録順（{@link CssPropertyId}）のあとに添字を持たない宣言。
     */
    private static final class StyleMap extends java.util.AbstractMap<String, String> {
        final ComputedStyle owner;

        StyleMap(ComputedStyle owner) {
            this.owner = owner;
        }

        @Override
        public int size() {
            return owner.size;
        }

        @Override
        public boolean isEmpty() {
            return owner.size == 0;
        }

        @Override
        public String get(Object key) {
            if (!(key instanceof String name)) return null;
            int id = CssPropertyId.of(name);
            if (id != CssPropertyId.NONE) return owner.values[id];
            return owner.extras != null ? owner.extras.get(name) : null;
        }

        @Override
        public boolean containsKey(Object key) {
            return get(key) != null;
        }

        @Override
        public boolean equals(Object o) {
            if (o == this) return true;
            if (o instanceof StyleMap other) {
                return owner.size == other.owner.size
                        && java.util.Arrays.equals(owner.values, other.owner.values)
                        && java.util.Objects.equals(owner.extras, other.owner.extras);
            }
            return super.equals(o);
        }

        @Override
        public int hashCode() {
            return super.hashCode();
        }

        @Override
        public java.util.Set<Entry<String, String>> entrySet() {
            return new java.util.AbstractSet<>() {
                @Override
                public int size() {
                    return owner.size;
                }

                @Override
                public java.util.Iterator<Entry<String, String>> iterator() {
                    return new java.util.Iterator<>() {
                        private int next = advance(0);
                        private java.util.Iterator<Entry<String, String>> extra;

                        private int advance(int from) {
                            String[] v = owner.values;
                            while (from < v.length && v[from] == null) from++;
                            return from;
                        }

                        @Override
                        public boolean hasNext() {
                            if (next < owner.values.length) return true;
                            if (extra == null) {
                                extra = owner.extras != null ? owner.extras.entrySet().iterator()
                                        : java.util.Collections.emptyIterator();
                            }
                            return extra.hasNext();
                        }

                        @Override
                        public Entry<String, String> next() {
                            if (next < owner.values.length) {
                                int id = next;
                                next = advance(id + 1);
                                return new java.util.AbstractMap.SimpleImmutableEntry<>(CssPropertyId.name(id), owner.values[id]);
                            }
                            if (!hasNext()) throw new java.util.NoSuchElementException();
                            return extra.next();
                        }
                    };
                }
            };
        }
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

    /** この算出スタイルだけから決まる、利用側（描画順）の派生値の置き場。算出スタイルは不変なので一度作れば使い回せる。 */
    private volatile Object derivedFacts;

    public Object derivedFacts() {
        return derivedFacts;
    }

    public void derivedFacts(Object facts) {
        this.derivedFacts = facts;
    }

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
            if (extras != null) {
                for (Map.Entry<String, String> e : extras.entrySet()) {
                    if (e.getKey().startsWith("--")) custom.put(e.getKey(), e.getValue());
                }
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
        int id = CssPropertyId.of(propertyName);
        String val = rawAt(id, propertyName);
        if (val == null) return 0;
        return compiledLength(id, val).resolve(maxPixels, rootW, rootH, elementFontSize, rootFontSize);
    }

    /**
     * プロパティ名の固定の添字（登録に無い名前は -1）。熱い経路は、クラスの初期化で一度だけこれを引いて
     * {@code static final int} に持ち、名前の代わりに添字を取る {@link #get(int)} / {@link #keywordOf(int, Class)} /
     * {@link #length(int, float, float, float)} などで引く（文字列のハッシュと表引きを毎回しない）。
     */
    public static int idOf(String propertyName) {
        return CssPropertyId.of(propertyName);
    }

    /** {@link #idOf} の添字で宣言の文字列を引く。{@code get(name)} と同じ結果。添字は登録済みのものに限る。 */
    public String get(int id) {
        return values[id];
    }

    /** 添字版の {@link #length(String, float, float, float)}。 */
    public float length(int id, float maxPixels, float rootW, float rootH) {
        String val = values[id];
        if (val == null) return 0;
        return compiledLength(id, val).resolve(maxPixels, rootW, rootH, elementFontSize, rootFontSize);
    }

    /** 添字版の {@link #keywordOf(String, Class)}。 */
    public <E extends Enum<E> & CssKeywords.Keyword> E keywordOf(int id, Class<E> type) {
        Object cached = cached(id, KIND_KEYWORD);
        if (cached != null && type.isInstance(cached)) return type.cast(cached);
        E parsed = CssKeywords.parse(type, values[id]);
        if (parsed == null) parsed = PropertyRegistry.initialKeyword(CssPropertyId.name(id), type);
        remember(id, KIND_KEYWORD, parsed);
        return parsed;
    }

    /** 添字（{@link CssPropertyId#NONE} なら名前）で宣言を引く。{@link StyleMap#get} と同じ結果。 */
    private String rawAt(int id, String name) {
        if (id != CssPropertyId.NONE) return values[id];
        return extras != null ? extras.get(name) : null;
    }

    /** 宣言が無い場合に {@code defaultPixels} を返す {@link #length}。 */
    public float lengthOrDefault(String propertyName, float defaultPixels, float maxPixels, float rootW, float rootH) {
        if (!properties.containsKey(propertyName)) return defaultPixels;
        return length(propertyName, maxPixels, rootW, rootH);
    }

    private LengthResolver.CompiledLength compiledLength(int id, String value) {
        if (id == CssPropertyId.NONE) return LengthResolver.compile(value); // 添字を持たない宣言（--* など）は覚えない
        LengthResolver.CompiledLength cached = (LengthResolver.CompiledLength) cached(id, KIND_LENGTH);
        if (cached != null) return cached;
        LengthResolver.CompiledLength compiled = LengthResolver.compile(value);
        remember(id, KIND_LENGTH, compiled);
        return compiled;
    }

    /** 色を ARGB に解決する。宣言が無ければ {@code defaultColor}。 */
    public int color(String propertyName, int defaultColor) {
        int id = CssPropertyId.of(propertyName);
        String val = rawAt(id, propertyName);
        if (val == null) return defaultColor;
        if (id == CssPropertyId.NONE) return ColorParser.parseColor(val);
        Integer cached = (Integer) cached(id, KIND_COLOR);
        if (cached != null) return cached;
        int parsed = ColorParser.parseColor(val);
        remember(id, KIND_COLOR, parsed);
        return parsed;
    }

    /** 単位なしの数値（{@code opacity} / {@code flex-grow} / {@code z-index} など）。 */
    public float number(String propertyName, float defaultValue) {
        int id = CssPropertyId.of(propertyName);
        String val = rawAt(id, propertyName);
        if (val == null) return defaultValue;
        Float cached = id == CssPropertyId.NONE ? null : (Float) cached(id, KIND_NUMBER);
        if (cached != null) return cached;
        float parsed;
        try {
            parsed = Float.parseFloat(val.trim());
        } catch (NumberFormatException e) {
            parsed = defaultValue;
        }
        if (id != CssPropertyId.NONE) remember(id, KIND_NUMBER, parsed);
        return parsed;
    }

    /**
     * キーワードだけを値に取るプロパティの算出値を、{@link PropertyRegistry} が宣言した enum で返す。
     * 書かれていない値・知らない値はそのプロパティの初期値。
     */
    public <E extends Enum<E> & CssKeywords.Keyword> E keywordOf(String propertyName, Class<E> type) {
        int id = CssPropertyId.of(propertyName);
        Object cached = id == CssPropertyId.NONE ? null : cached(id, KIND_KEYWORD);
        if (cached != null && type.isInstance(cached)) return type.cast(cached);
        E parsed = CssKeywords.parse(type, rawAt(id, propertyName));
        if (parsed == null) parsed = PropertyRegistry.initialKeyword(propertyName, type);
        if (id != CssPropertyId.NONE) remember(id, KIND_KEYWORD, parsed);
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
