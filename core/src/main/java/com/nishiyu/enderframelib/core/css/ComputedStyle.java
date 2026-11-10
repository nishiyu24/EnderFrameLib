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
    private final CssValue[] values;
    /** 添字を持たない宣言（{@code --*} のカスタムプロパティなど）。無ければ null で、通常の要素では確保しない。 */
    private final Map<String, String> extras;
    private final int size;
    /** {@link #usesViewportUnits()} の結果（1 / 0）。-1 は未確定（値を走査して決め、以後は覚える）。値は不変なので競っても同じ。 */
    private volatile byte relativeUnits = -1;
    private final StyleMap properties;
    private final float elementFontSize;
    private final float rootFontSize;

    /**
     * 描画側が、この算出スタイルだけから決まる結果（アニメーション定義の解決など）を覚える置き場。
     * スタイルは作り直されるたびに別のインスタンスになるので、スタイルが変われば自然に捨てられる。
     * 鍵は {@code equals} / {@code hashCode} を持つ値（record など）。中身は {@link ObjMemo}（小さいうち）か
     * {@link ConcurrentHashMap}（多くの鍵が溜まる、多数の箱が共有するスタイルだけ）。
     */
    private volatile Object paint;

    private static final java.lang.invoke.VarHandle PAINT;

    static {
        try {
            PAINT = java.lang.invoke.MethodHandles.lookup().findVarHandle(ComputedStyle.class, "paint", Object.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** 添字ごとの、宣言が無いときのキーワード（初期値）。全スタイルで共有する。競って 2 回計算しても同じ値。 */
    private static final Object[] INITIAL_KEYWORDS = new Object[CssPropertyId.count()];

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
        this(source, elementFontSize, rootFontSize, false);
    }

    private ComputedStyle(Map<String, String> source, float elementFontSize, float rootFontSize, boolean adopt) {
        if (source instanceof StyleTable table) {
            // カスケード・算出の作業表から: 名前を引き直さない。引き取るなら配列もコピーしない
            this.values = adopt ? table.values : table.values.clone();
            this.extras = table.extras == null ? null
                    : Collections.unmodifiableMap(adopt ? table.extras : new LinkedHashMap<>(table.extras));
            this.size = table.size;
            this.relativeUnits = table.relativeUnits;
        } else if (source instanceof StyleMap other) {
            // 算出スタイルの複製: 配列をそのままコピーするだけで、マップは作らない
            this.values = other.owner.values.clone();
            this.extras = other.owner.extras;
            this.size = other.owner.size;
            this.relativeUnits = other.owner.relativeUnits;
        } else {
            CssValue[] v = new CssValue[CssPropertyId.count()];
            Map<String, String> x = null;
            int n = 0;
            for (Map.Entry<String, String> e : source.entrySet()) {
                int id = CssPropertyId.of(e.getKey());
                if (id != CssPropertyId.NONE) {
                    if (v[id] == null) n++;
                    v[id] = CssValue.of(e.getValue());
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
    static final class CssPropertyId {
        static final int NONE = -1;
        /** 名前 → 添字の、開番地法のハッシュ表（{@code HashMap<String, Integer>} の箱詰めと間接参照を避ける）。起動後は読み取り専用。 */
        private static final String[] KEYS;
        /** {@link #KEYS} と並行する名前のハッシュ。衝突の列をたどるとき、別の名前の文字列オブジェクトを触らずに比べる。 */
        private static final int[] HASHES;
        private static final int[] VALS;
        private static final int MASK;
        private static final String[] NAMES;
        /** 添字ごとの、描画だけに効く（再レイアウトが要らない）プロパティか。 */
        private static final boolean[] PAINT_ONLY;
        /** 添字ごとの、子孫が値を受け継ぐ（未登録は安全側に true）プロパティか。 */
        private static final boolean[] INHERITED;
        /** 添字ごとの、カスケードの初期値として親から受け取る（継承するロングハンド）プロパティか。 */
        private static final boolean[] INHERITS_AS_LONGHAND;
        /** 添字ごとの初期値（無ければ null）。算出が名前でレジストリを引き直さないための表。 */
        private static final String[] INITIALS;
        private static final CssValue[] INITIAL_VALUES;

        static {
            var all = PropertyRegistry.all();
            String[] names = new String[all.size()];
            int cap = Integer.highestOneBit(Math.max(8, all.size() * 4 - 1)) << 1;
            String[] keys = new String[cap];
            int[] hashes = new int[cap];
            int[] vals = new int[cap];
            int i = 0;
            for (PropertyRegistry.Property p : all) {
                String name = p.name();
                int h = name.hashCode();
                int at = (h ^ (h >>> 16)) & (cap - 1);
                while (keys[at] != null) at = (at + 1) & (cap - 1);
                keys[at] = name;
                hashes[at] = h;
                vals[at] = i;
                names[i++] = name;
            }
            KEYS = keys;
            HASHES = hashes;
            VALS = vals;
            MASK = cap - 1;
            NAMES = names;
            PAINT_ONLY = new boolean[names.length];
            INHERITED = new boolean[names.length];
            INHERITS_AS_LONGHAND = new boolean[names.length];
            INITIALS = new String[names.length];
            INITIAL_VALUES = new CssValue[names.length];
            for (int k = 0; k < names.length; k++) {
                PAINT_ONLY[k] = PropertyRegistry.isPaintOnly(names[k]);
                INHERITED[k] = PropertyRegistry.isInherited(names[k]);
                PropertyRegistry.Property p = PropertyRegistry.get(names[k]);
                INHERITS_AS_LONGHAND[k] = p != null && p.inherited() && !p.isShorthand();
                INITIALS[k] = p != null ? p.initial() : null;
                INITIAL_VALUES[k] = CssValue.of(INITIALS[k]);
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
            int[] hashes = HASHES;
            for (; ; ) {
                String k = keys[at];
                if (k == null) return NONE;
                if (k == name || (hashes[at] == h && k.equals(name))) return VALS[at];
                at = (at + 1) & MASK;
            }
        }

        static String name(int id) {
            return NAMES[id];
        }
    }

    /** 親の算出値（{@code ctx.parent()}）を添字で引く。算出スタイル・作業表なら配列を直接引き、素のマップなら名前で引く。 */
    static String parentValue(Map<String, String> parent, int id) {
        CssValue v = parentCssValue(parent, id);
        return v != null ? v.text : null;
    }

    /** {@link #parentValue} の値オブジェクト版（継承で値を引き取るとき、文字列を引き直さない）。 */
    static CssValue parentCssValue(Map<String, String> parent, int id) {
        if (parent instanceof StyleMap view) return view.owner.values[id];
        if (parent instanceof StyleTable table) return table.values[id];
        return CssValue.of(parent.get(CssPropertyId.name(id)));
    }

    /** {@code source} が算出スタイルのビューなら、宣言を値オブジェクトのまま {@code into} へ写す（文字列を引き直さない）。 */
    static boolean copyStyleMapInto(Map<String, String> source, StyleTable into) {
        if (!(source instanceof StyleMap view)) return false;
        CssValue[] v = view.owner.values;
        System.arraycopy(v, 0, into.values, 0, v.length);
        into.size = view.owner.size;
        if (view.owner.extras != null) into.extras = new LinkedHashMap<>(view.owner.extras);
        return true;
    }

    /** 添字のプロパティの初期値（無ければ null）。 */
    static String initialOf(int id) {
        return CssPropertyId.INITIALS[id];
    }

    /** 添字のプロパティの初期値の値オブジェクト（無ければ null）。 */
    static CssValue initialValueOf(int id) {
        return CssPropertyId.INITIAL_VALUES[id];
    }

    /** 添字のプロパティが継承するか（{@link PropertyRegistry#isInherited} と同じ）。 */
    static boolean isInheritedId(int id) {
        return CssPropertyId.INHERITED[id];
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
        CssValue[] x = a.values, y = b.values;
        for (int i = 0; i < x.length; i++) {
            if (x[i] == y[i]) continue; // 同じ文字列は同じ値オブジェクト
            if (x[i] != null && y[i] != null && x[i].text.equals(y[i].text)) continue;
            visitor.changed(CssPropertyId.name(i), CssPropertyId.PAINT_ONLY[i], CssPropertyId.INHERITED[i]);
        }
        if (a.extras == null && b.extras == null) return;
        Map<String, String> ex = a.extras != null ? a.extras : Map.of();
        Map<String, String> ey = b.extras != null ? b.extras : Map.of();
        if (ex == ey) return;
        // forEach は読み取り専用の包みが項目ごとに作る Map.Entry を介さない
        ex.forEach((k, v) -> {
            if (!java.util.Objects.equals(v, ey.get(k))) visitor.changed(k, false, true);
        });
        ey.forEach((k, v) -> {
            if (!ex.containsKey(k)) visitor.changed(k, false, true);
        });
    }

    /**
     * カスケードの初期値として、親の算出スタイルから継承するロングハンドと {@code --*} を {@code target} へ写す。
     * {@code parent} が算出スタイルのビューなら、{@code Map.Entry} を作らず、固定の添字表（継承するか）で配列を直接走査する。
     * 走査順は {@code parent.entrySet()} と同じ（登録順 → 添字を持たない宣言）なので、{@code target} の並びも変わらない。
     *
     * @return 算出スタイルのビューとして処理した。false なら何も書いておらず、呼び出し側が従来の走査をする
     */
    static boolean copyInheritable(Map<String, String> parent, Map<String, String> target) {
        if (!(parent instanceof StyleMap view)) return false;
        CssValue[] v = view.owner.values;
        boolean[] inherits = CssPropertyId.INHERITS_AS_LONGHAND;
        StyleTable table = target instanceof StyleTable t ? t : null;
        for (int i = 0; i < v.length; i++) {
            if (v[i] == null || !inherits[i]) continue;
            if (table != null) table.putValueById(i, v[i]);
            else target.put(CssPropertyId.NAMES[i], v[i].text);
        }
        Map<String, String> ex = view.owner.extras;
        if (ex != null) {
            for (Map.Entry<String, String> e : ex.entrySet()) {
                if (e.getKey().startsWith("--")) target.put(e.getKey(), e.getValue());
            }
        }
        return true;
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
            if (id != CssPropertyId.NONE) {
                CssValue v = owner.values[id];
                return v != null ? v.text : null;
            }
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
                            CssValue[] v = owner.values;
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
                                return new java.util.AbstractMap.SimpleImmutableEntry<>(CssPropertyId.name(id), owner.values[id].text);
                            }
                            if (!hasNext()) throw new java.util.NoSuchElementException();
                            return extra.next();
                        }
                    };
                }
            };
        }
    }

    /** 作業表 {@code owned} の配列をそのまま引き取る（呼び出し側はこの後 {@code owned} を触らない）。{@link #of} と同じ算出。 */
    static ComputedStyle ofOwned(StyleTable owned, float parentFontSize, float rootFontSize) {
        float own = parentFontSize;
        String fontSize = owned.textById(CssProperty.Id.FONT_SIZE);
        if (fontSize != null) {
            own = ValueComputer.resolveFontSize(fontSize,
                    new ValueComputer.Context(Map.of(), parentFontSize, rootFontSize, 0, 0));
        }
        return new ComputedStyle(owned, own, rootFontSize, true);
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
        if (id != CssPropertyId.NONE) return length(id, maxPixels, rootW, rootH);
        String val = rawAt(id, propertyName);
        if (val == null) return 0;
        return TypedValueCache.length(val).resolve(maxPixels, rootW, rootH, elementFontSize, rootFontSize);
    }

    /**
     * プロパティ名の固定の添字（登録に無い名前は -1）。熱い経路は、クラスの初期化で一度だけこれを引いて
     * {@code static final int} に持ち、名前の代わりに添字を取る {@link #get(int)} / {@link #keywordOf(int, Class)} /
     * {@link #length(int, float, float, float)} などで引く（文字列のハッシュと表引きを毎回しない）。
     */
    public static int idOf(String propertyName) {
        return CssPropertyId.of(propertyName);
    }

    /**
     * どれかの値が、ビューポート（{@code vw vh vi vb vmin vmax} とその {@code s/l/d} 版）またはコンテナ（{@code cqw cqh cqi cqb cqmin cqmax}）
     * 基準の単位を使っているか。レイアウトが箱の外の寸法を読む可能性があるかの、保守的な判定（数値の直後の単位だけを見る）。
     */
    public boolean usesViewportUnits() {
        byte known = relativeUnits;
        if (known >= 0) return known == 1;
        boolean uses = false;
        for (CssValue v : values) {
            if (v != null && v.usesRelativeUnit()) {
                uses = true;
                break;
            }
        }
        if (!uses && extras != null) {
            for (String v : extras.values()) {
                if (v != null && ValueComputer.mayContainDimension(v) && scanRelativeUnit(v)) {
                    uses = true;
                    break;
                }
            }
        }
        relativeUnits = (byte) (uses ? 1 : 0);
        return uses;
    }

    static boolean scanRelativeUnit(String v) {
        int n = v.length();
        for (int i = 0; i < n; i++) {
            char c = v.charAt(i);
            if (c < '0' || c > '9') continue;
            // 数値（整数・小数・指数の手前まで）を読み飛ばし、直後の英字の並びを単位として見る
            int j = i + 1;
            while (j < n && ((v.charAt(j) >= '0' && v.charAt(j) <= '9') || v.charAt(j) == '.')) j++;
            int k = j;
            while (k < n && Character.isLetter(v.charAt(k))) k++;
            if (k > j && isRelativeUnit(v, j, k)) return true;
            i = Math.max(i, k - 1);
        }
        return false;
    }

    /** {@code vw} 系（{@code s/l/d} 接頭辞つきを含む）と {@code cq*} か。単位ごとに文字列を作らず、大小文字を無視して照合する。 */
    static boolean isRelativeUnit(String v, int from, int to) {
        int len = to - from;
        if (len < 2 || len > 6) return false;
        char c0 = Character.toLowerCase(v.charAt(from));
        if (c0 == 'c') return len >= 3 && Character.toLowerCase(v.charAt(from + 1)) == 'q' && isAxisUnit(v, from + 2, to);
        int start = from;
        if ((c0 == 's' || c0 == 'l' || c0 == 'd') && len >= 3) {
            start = from + 1;
            c0 = Character.toLowerCase(v.charAt(start));
        }
        return c0 == 'v' && isAxisUnit(v, start + 1, to);
    }

    /** {@code w h i b min max}（{@code vw} の {@code w} など、単位の後半）か。 */
    private static boolean isAxisUnit(String v, int from, int to) {
        int n = to - from;
        if (n == 1) {
            char c = Character.toLowerCase(v.charAt(from));
            return c == 'w' || c == 'h' || c == 'i' || c == 'b';
        }
        return n == 3 && (v.regionMatches(true, from, "min", 0, 3) || v.regionMatches(true, from, "max", 0, 3));
    }

    /** {@link #idOf} の添字で宣言の文字列を引く。{@code get(name)} と同じ結果。添字は登録済みのものに限る。 */
    public String get(int id) {
        CssValue v = values[id];
        return v != null ? v.text : null;
    }

    /** 添字版の {@link #length(String, float, float, float)}。 */
    public float length(int id, float maxPixels, float rootW, float rootH) {
        CssValue v = values[id];
        if (v == null) return 0;
        return v.length().resolve(maxPixels, rootW, rootH, elementFontSize, rootFontSize);
    }

    /** 添字版の {@link #getOrDefault(String, String)}。 */
    public String getOrDefault(int id, String defaultValue) {
        CssValue v = values[id];
        return v != null ? v.text : defaultValue;
    }

    /** 添字版の {@link #containsKey(String)}。 */
    public boolean containsKey(int id) {
        return values[id] != null;
    }

    /** 添字版の {@link #lengthOrDefault(String, float, float, float, float)}。 */
    public float lengthOrDefault(int id, float defaultPixels, float maxPixels, float rootW, float rootH) {
        if (values[id] == null) return defaultPixels;
        return length(id, maxPixels, rootW, rootH);
    }

    /** 添字版の {@link #color(String, int)}。 */
    public int color(int id, int defaultColor) {
        CssValue v = values[id];
        return v != null ? v.color() : defaultColor;
    }

    /** 添字版の {@link #number(String, float)}。 */
    public float number(int id, float defaultValue) {
        CssValue v = values[id];
        return v != null ? v.number(defaultValue) : defaultValue;
    }

    /** 添字版の {@link #keyword(String, String)}。 */
    public String keyword(int id, String defaultValue) {
        CssValue v = values[id];
        return v != null ? v.lowered() : defaultValue;
    }

    /** 添字版の {@link #is(String, String)}。 */
    public boolean is(int id, String expected) {
        return expected.equals(keyword(id, null));
    }

    /** 添字版の {@link #keywordFresh(String, Class)}。 */
    <E extends Enum<E> & CssKeywords.Keyword> E keywordFresh(int id, Class<E> type) {
        CssValue v = values[id];
        E parsed = v != null ? v.keyword(type) : null;
        return parsed != null ? parsed : PropertyRegistry.initialKeyword(CssPropertyId.name(id), type);
    }

    /** 添字版の {@link #keywordOf(String, Class)}。 */
    public <E extends Enum<E> & CssKeywords.Keyword> E keywordOf(int id, Class<E> type) {
        CssValue v = values[id];
        E parsed = v != null ? v.keyword(type) : null;
        return parsed != null ? parsed : initialKeyword(id, type);
    }

    /** 宣言が無い（または知らない値の）ときの初期値。添字ごとに 1 回だけ解いて全スタイルで共有する。 */
    private static <E extends Enum<E> & CssKeywords.Keyword> E initialKeyword(int id, Class<E> type) {
        Object hit = INITIAL_KEYWORDS[id];
        if (hit != null && type.isInstance(hit)) return type.cast(hit);
        E initial = PropertyRegistry.initialKeyword(CssPropertyId.name(id), type);
        INITIAL_KEYWORDS[id] = initial;
        return initial;
    }

    /** 添字（{@link CssPropertyId#NONE} なら名前）で宣言を引く。{@link StyleMap#get} と同じ結果。 */
    private String rawAt(int id, String name) {
        if (id != CssPropertyId.NONE) return get(id);
        return extras != null ? extras.get(name) : null;
    }

    /** 宣言が無い場合に {@code defaultPixels} を返す {@link #length}。 */
    public float lengthOrDefault(String propertyName, float defaultPixels, float maxPixels, float rootW, float rootH) {
        if (!properties.containsKey(propertyName)) return defaultPixels;
        return length(propertyName, maxPixels, rootW, rootH);
    }

    /** 色を ARGB に解決する。宣言が無ければ {@code defaultColor}。 */
    public int color(String propertyName, int defaultColor) {
        int id = CssPropertyId.of(propertyName);
        if (id != CssPropertyId.NONE) return color(id, defaultColor);
        String val = rawAt(id, propertyName);
        if (val == null) return defaultColor;
        return TypedValueCache.color(val);
    }

    /** 単位なしの数値（{@code opacity} / {@code flex-grow} / {@code z-index} など）。 */
    public float number(String propertyName, float defaultValue) {
        int id = CssPropertyId.of(propertyName);
        if (id != CssPropertyId.NONE) return number(id, defaultValue);
        String val = rawAt(id, propertyName);
        if (val == null) return defaultValue;
        return TypedValueCache.number(val, defaultValue);
    }

    /**
     * キーワードだけを値に取るプロパティの算出値を、{@link PropertyRegistry} が宣言した enum で返す。
     * 書かれていない値・知らない値はそのプロパティの初期値。
     */
    public <E extends Enum<E> & CssKeywords.Keyword> E keywordOf(String propertyName, Class<E> type) {
        int id = CssPropertyId.of(propertyName);
        if (id != CssPropertyId.NONE) return keywordOf(id, type);
        E parsed = CssKeywords.parse(type, rawAt(id, propertyName));
        return parsed != null ? parsed : PropertyRegistry.initialKeyword(propertyName, type);
    }

    /** {@link #keywordOf(String, Class)} と同じ値（事実の算出用の別名。以前は記憶表を避けるために分けていた）。 */
    <E extends Enum<E> & CssKeywords.Keyword> E keywordFresh(String propertyName, Class<E> type) {
        int id = CssPropertyId.of(propertyName);
        if (id != CssPropertyId.NONE) return keywordFresh(id, type);
        E parsed = CssKeywords.parse(type, rawAt(id, propertyName));
        return parsed != null ? parsed : PropertyRegistry.initialKeyword(propertyName, type);
    }

    /** {@code box-sizing: border-box} か。 */
    public boolean isBorderBox() {
        return layoutFacts().borderBox;
    }

    // ==========================================
    // 型付きの事実（算出スタイルごとに一度だけ決まる値）
    // ==========================================
    //
    // レイアウトが箱ごと・測定ごとに繰り返し読む値を、文字列を読み直さず（分割・小文字化・パースをせず）に引けるようにする。
    // 算出スタイルは不変なので、最初の参照で作った値を使い回してよい。

    /** 行の高さの指定の種類。 */
    public static final byte LH_NORMAL = 0, LH_PX = 1, LH_PERCENT = 2, LH_EM = 3, LH_NUMBER = 4;


    private volatile StyleFacts facts;

    /** 型付きの事実。最初の参照で作る（競って 2 回作っても同じ値）。 */
    public StyleFacts facts() {
        StyleFacts f = facts;
        if (f == null) {
            f = buildFacts();
            facts = f;
        }
        return f;
    }

    /**
     * 型付きの事実に、測定・整形が読む宣言の値（{@link StyleFacts#widthStr} など）まで揃えて返す。
     * 重いので、overflow などの基本の事実（{@link #facts()}）だけで足りる読み手は呼ばない。
     */
    public StyleFacts layoutFacts() {
        StyleFacts f = facts();
        if (!f.extrasReady) {
            f.fillExtras(this);
            f.extrasReady = true;
        }
        return f;
    }

    private StyleFacts buildFacts() {
        String raw = get(CssProperty.LINE_HEIGHT);
        byte kind = LH_NORMAL;
        float value = 0f;
        if (raw != null) {
            String v = raw.trim();
            if (!v.isEmpty() && !v.equalsIgnoreCase("normal")) {
                try {
                    if (v.endsWith("px")) {
                        value = Float.parseFloat(v.substring(0, v.length() - 2).trim());
                        kind = LH_PX;
                    } else if (v.endsWith("%")) {
                        value = Float.parseFloat(v.substring(0, v.length() - 1).trim()) / 100f;
                        kind = LH_PERCENT;
                    } else if (v.endsWith("em")) {
                        value = Float.parseFloat(v.substring(0, v.length() - 2).trim());
                        kind = LH_EM;
                    } else {
                        value = Float.parseFloat(v);
                        kind = LH_NUMBER;
                    }
                } catch (NumberFormatException e) {
                    kind = LH_NORMAL;
                }
            }
        }
        StyleFacts built = new StyleFacts(computeOverflowAxis(CssProperty.OVERFLOW_X, 0), computeOverflowAxis(CssProperty.OVERFLOW_Y, 1),
                com.nishiyu.enderframelib.core.layout.model.LayoutMetrics.numericWeight(get(CssProperty.FONT_WEIGHT)),
                kind, value,
                (containment() & (CONTAIN_SIZE | CONTAIN_INLINE_SIZE)) != 0
                        || keywordFresh(CssProperty.CONTAINER_TYPE, CssKeywords.ContainerType.class).containsInlineSize(),
                (containment() & CONTAIN_SIZE) != 0
                        || keywordFresh(CssProperty.CONTAINER_TYPE, CssKeywords.ContainerType.class).containsBlockSize(),
                keywordFresh(CssProperty.FLOAT, CssKeywords.FloatSide.class) != CssKeywords.FloatSide.NONE,
                "none".equals(getOrDefault(CssProperty.DISPLAY, "block")),
                definiteDimension(DIM_WIDTH, CssProperty.WIDTH), definiteDimension(DIM_HEIGHT, CssProperty.HEIGHT));
        return built;
    }

    /** 横方向の {@code overflow}。{@code overflow-x} が無ければショートハンド {@code overflow} の最初の値。 */
    public Overflow overflowX() {
        return facts().overflowX;
    }

    /** 縦方向の {@code overflow}。{@code overflow-y} が無ければショートハンドの 2 つ目（1 つなら同じ値）。 */
    public Overflow overflowY() {
        return facts().overflowY;
    }

    /** {@link #edgeLength} の添字: margin（上右下左）・padding（上右下左）・border-width（上右下左）・row-gap・column-gap。 */
    public static final int EDGE_MARGIN_T = 0, EDGE_MARGIN_R = 1, EDGE_MARGIN_B = 2, EDGE_MARGIN_L = 3,
            EDGE_PADDING_T = 4, EDGE_PADDING_R = 5, EDGE_PADDING_B = 6, EDGE_PADDING_L = 7,
            EDGE_BORDER_T = 8, EDGE_BORDER_R = 9, EDGE_BORDER_B = 10, EDGE_BORDER_L = 11,
            EDGE_ROW_GAP = 12, EDGE_COLUMN_GAP = 13;

    /**
     * 縁と同じ {@code long[]} に詰める、寸法・オフセット系の宣言の添字（{@link #edgeLength} / {@link #edgeHasPercent} に渡す）。
     * 宣言が無ければ NONE。{@code auto} / {@code none} / 内在サイズのキーワードは 0 px に詰まる（% を含まない）。
     */
    public static final int DIM_WIDTH = 14, DIM_HEIGHT = 15, DIM_MIN_WIDTH = 16, DIM_MAX_WIDTH = 17,
            DIM_MIN_HEIGHT = 18, DIM_MAX_HEIGHT = 19, DIM_LEFT = 20, DIM_RIGHT = 21, DIM_TOP = 22, DIM_BOTTOM = 23,
            DIM_FLEX_BASIS = 24;

    private static final int[] EDGE_IDS = {
            idOf(CssProperty.MARGIN_TOP), idOf(CssProperty.MARGIN_RIGHT), idOf(CssProperty.MARGIN_BOTTOM), idOf(CssProperty.MARGIN_LEFT),
            idOf(CssProperty.PADDING_TOP), idOf(CssProperty.PADDING_RIGHT), idOf(CssProperty.PADDING_BOTTOM), idOf(CssProperty.PADDING_LEFT),
            idOf(CssProperty.BORDER_TOP_WIDTH), idOf(CssProperty.BORDER_RIGHT_WIDTH), idOf(CssProperty.BORDER_BOTTOM_WIDTH),
            idOf(CssProperty.BORDER_LEFT_WIDTH), idOf(CssProperty.ROW_GAP), idOf(CssProperty.COLUMN_GAP),
            idOf(CssProperty.WIDTH), idOf(CssProperty.HEIGHT), idOf(CssProperty.MIN_WIDTH), idOf(CssProperty.MAX_WIDTH),
            idOf(CssProperty.MIN_HEIGHT), idOf(CssProperty.MAX_HEIGHT), idOf(CssProperty.LEFT), idOf(CssProperty.RIGHT),
            idOf(CssProperty.TOP), idOf(CssProperty.BOTTOM), idOf(CssProperty.FLEX_BASIS)};

    private long[] compileEdges() {
        long[] out = new long[EDGE_IDS.length];
        for (int i = 0; i < out.length; i++) {
            CssValue val = values[EDGE_IDS[i]];
            out[i] = val == null ? com.nishiyu.enderframelib.core.kernel.style.PackedCssValue.NONE
                    : "auto".equalsIgnoreCase(val.text.trim()) ? PackedLengths.AUTO
                    : PackedLengths.pack(val.length());
        }
        return out;
    }

    /**
     * 箱の縁の長さ（{@code EDGE_*}）を px にする。{@link #length(int, float, float, float)} と同じ結果を、
     * 記憶表を引かずに返す。宣言が無ければ 0。
     */
    public float edgeLength(int edge, float maxPixels, float rootW, float rootH) {
        return PackedLengths.resolve(packedEdges()[edge], maxPixels, rootW, rootH, elementFontSize, rootFontSize);
    }

    /** 縁・寸法の宣言（{@code EDGE_*} / {@code DIM_*}）が % を含むか。文字列を探さず、詰めた値の単位（式は式表の原文）で答える。 */
    boolean edgeHasPercent(int edge) {
        return PackedLengths.hasPercent(packedEdges()[edge]);
    }

    long packedEdge(int edge) {
        return packedEdges()[edge];
    }

    /** 縁・寸法を詰めた列。{@link #facts()} を組む途中でも引けるよう、事実の外（このスタイル）に持つ。競って 2 回作っても同じ値。 */
    private volatile long[] packedEdges;

    private long[] packedEdges() {
        long[] edges = packedEdges;
        if (edges == null) {
            edges = compileEdges();
            packedEdges = edges;
        }
        return edges;
    }

    /** {@code width} / {@code height} が確定した長さか。詰めた値で決め、0 px に潰れた値と式（内在サイズのキーワードを含む）だけ原文で判る。 */
    private boolean definiteDimension(int edge, String property) {
        long v = packedEdge(edge);
        if (v == com.nishiyu.enderframelib.core.kernel.style.PackedCssValue.NONE || PackedLengths.isAuto(v)) return false;
        if (PackedLengths.isZeroPx(v) || PackedLengths.isExpression(v)) return definiteLength(get(property));
        return !PackedLengths.hasPercent(v);
    }

    private static boolean definiteLength(String value) {
        if (value == null) return false;
        String v = value.trim().toLowerCase(java.util.Locale.ROOT);
        return !v.isEmpty() && !v.equals("auto") && !v.contains("%")
                && !com.nishiyu.enderframelib.core.layout.model.LayoutMetrics.isIntrinsicSizeKeyword(v);
    }

    private Overflow computeOverflowAxis(String property, int shorthandIndex) {
        Overflow declared = declaredOverflowAxis(property, shorthandIndex);
        // paint containment は中身を padding box で切る（overflow: clip と同じ。スクロールコンテナにはならない）
        if (declared == Overflow.VISIBLE && (containment() & CONTAIN_PAINT) != 0) return Overflow.CLIP;
        return declared;
    }

    private Overflow declaredOverflowAxis(String property, int shorthandIndex) {
        if (properties.containsKey(property)) return keywordFresh(property, Overflow.class);
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
