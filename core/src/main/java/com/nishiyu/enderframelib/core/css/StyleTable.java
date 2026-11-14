package com.nishiyu.enderframelib.core.css;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * カスケード・算出の作業用の宣言表。{@code Map<String, String>} として振る舞うが、登録済みプロパティは
 * {@link ComputedStyle} と同じ固定の添字（{@code CssPropertyId}）の {@code String[]} に持つので、
 * {@code LinkedHashMap} のように宣言ごとの節点を確保せず、複製も配列のコピー 1 回で済む。
 * {@code --*} などの添字を持たない宣言だけは遠回りの小さなマップに入れる（無ければ確保しない）。
 *
 * 走査順は登録順 → 添字を持たない宣言の挿入順（{@link ComputedStyle} の見え方と同じ）。値 null は「宣言なし」。
 * スレッドセーフではない（1 回のカスケードの中だけで使う）。
 */
final class StyleTable extends AbstractMap<String, String> {
    CssValue[] values;
    Map<String, String> extras;
    /**
     * {@link #extras} を別の表と共有している（複製は書き込み時にコピーする）。{@code --*} などの添字を持たない宣言は、
     * 継承のたびに表ごと複製されるが、書き換えは稀なので、複製の時点では写さずに共有し、最初の書き込みで初めて写す。
     */
    private boolean extrasShared;
    /** {@link #extras} のどれかの値がビューポート基準か（1 = はい、0 = いいえ、-1 = 未確定）。{@link #extras} の書き換えに合わせて更新する。 */
    byte extrasViewport = 0;
    /** {@link #values} のどれかの値がビューポート基準か（1 = はい、0 = いいえ、-1 = 未確定）。添字つきの値の書き換えに合わせて更新する。 */
    byte valuesViewport = 0;
    int size;
    /**
     * 算出（{@code ValueComputer.compute}）が、どれかの値がビューポート・コンテナ基準の単位を使うと決めた結果
     * （1 = 使う、0 = 使わない、-1 = 未確定）。書き込みのたびに未確定へ戻す。算出スタイルが引き取って、値を走査し直さずに答える。
     */
    byte relativeUnits = -1;

    StyleTable() {
        values = new CssValue[ComputedStyle.CssPropertyId.count()];
    }

    /** {@code source} の複製。算出スタイルのビュー・別の {@link StyleTable}・普通のマップのどれでもよい。 */
    static StyleTable copyOf(Map<String, String> source) {
        StyleTable t = new StyleTable();
        if (source instanceof StyleTable other) {
            System.arraycopy(other.values, 0, t.values, 0, t.values.length);
            t.size = other.size;
            t.valuesViewport = other.valuesViewport;
            if (other.extras != null) {
                t.extras = other.extras;
                t.extrasShared = true;
                t.extrasViewport = other.extrasViewport;
                other.extrasShared = true;
            }
        } else if (!ComputedStyle.copyStyleMapInto(source, t)) {
            t.putAll(source);
        }
        return t;
    }

    /** 作業表の {@link #extras} を他と共有したと印を付ける（次に書くとき自分の分を写す）。 */
    void markExtrasShared() {
        extrasShared = true;
    }

    /** 空の表へ、{@code source}（変更されない表）の {@code --*} を丸ごと共有して取り込む。 */
    void shareExtrasFrom(Map<String, String> source) {
        shareExtrasFrom(source, (byte) -1);
    }

    void shareExtrasFrom(Map<String, String> source, byte viewport) {
        extras = source;
        extrasShared = true;
        extrasViewport = viewport;
        size += source.size();
    }

    /** {@link #extras} を自分だけのものにする（共有中なら写す）。書く前に呼ぶ。 */
    private void ownExtras() {
        if (extrasShared) {
            extras = new LinkedHashMap<>(extras);
            extrasShared = false;
        }
    }

    /** どれかの値がビューポート基準か。添字つきの値は {@link CssValue} が覚えた答えを引き、{@code --*} などは文字列を走査する。 */
    boolean anyValueDependsOnViewport() {
        byte v = valuesViewport;
        if (v < 0) {
            v = 0;
            CssValue[] arr = values;
            for (int i = 0; i < arr.length; i++) {
                CssValue value = arr[i];
                if (value != null && value.dependsOnViewport()) {
                    v = 1;
                    break;
                }
            }
            valuesViewport = v;
        }
        if (v == 1) return true;
        if (extras == null) return false;
        byte e = extrasViewport;
        if (e < 0) {
            e = 0;
            for (String value : extras.values()) {
                if (value != null && StyleResolver.valueDependsOnViewport(value)) {
                    e = 1;
                    break;
                }
            }
            extrasViewport = e;
        }
        return e == 1;
    }

    /** 添字（{@code CssPropertyId}）で直接書く（文字列は正規の値にする）。 */
    void putById(int id, String value) {
        putValueById(id, CssValue.of(value));
    }

    /** 添字で、値オブジェクトをそのまま書く（文字列を引き直さない）。熱い経路用。null は宣言の削除。 */
    void putValueById(int id, CssValue value) {
        relativeUnits = -1;
        CssValue prev = values[id];
        if (value == null) {
            if (prev != null) {
                size--;
                values[id] = null;
                if (valuesViewport == 1 && prev.dependsOnViewport()) valuesViewport = -1;
            }
            return;
        }
        if (prev == null) size++;
        values[id] = value;
        if (value.dependsOnViewport()) {
            valuesViewport = 1;
        } else if (valuesViewport == 1 && prev != null && prev.dependsOnViewport()) {
            valuesViewport = -1;
        }
    }

    /** 添字で、まだ宣言が無いときだけ書く（{@code putIfAbsent}）。 */
    void putIfAbsentById(int id, String value) {
        if (values[id] == null && value != null) putValueById(id, CssValue.of(value));
    }

    /** 添字で、まだ宣言が無いときだけ値オブジェクトを書く。 */
    void putIfAbsentValueById(int id, CssValue value) {
        if (values[id] == null && value != null) putValueById(id, value);
    }

    /** 添字の宣言の文字列（無ければ null）。 */
    String textById(int id) {
        CssValue v = values[id];
        return v != null ? v.text : null;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public boolean isEmpty() {
        return size == 0;
    }

    @Override
    public String get(Object key) {
        if (!(key instanceof String name)) return null;
        int id = ComputedStyle.CssPropertyId.of(name);
        if (id != ComputedStyle.CssPropertyId.NONE) return textById(id);
        return extras != null ? extras.get(name) : null;
    }

    @Override
    public boolean containsKey(Object key) {
        return get(key) != null;
    }

    @Override
    public String put(String key, String value) {
        relativeUnits = -1;
        int id = ComputedStyle.CssPropertyId.of(key);
        if (id != ComputedStyle.CssPropertyId.NONE) {
            String old = textById(id);
            putById(id, value);
            return old;
        }
        if (value == null) return remove(key);
        if (extras == null) {
            extras = new LinkedHashMap<>();
            extrasViewport = 0;
        } else {
            ownExtras();
        }
        String old = extras.put(key, value);
        if (old == null) size++;
        if (StyleResolver.valueDependsOnViewport(value)) {
            extrasViewport = 1;
        } else if (extrasViewport == 1 && old != null && StyleResolver.valueDependsOnViewport(old)) {
            extrasViewport = -1;
        }
        return old;
    }

    @Override
    public String remove(Object key) {
        relativeUnits = -1;
        if (!(key instanceof String name)) return null;
        int id = ComputedStyle.CssPropertyId.of(name);
        if (id != ComputedStyle.CssPropertyId.NONE) {
            CssValue prev = values[id];
            if (prev != null) {
                values[id] = null;
                if (valuesViewport == 1 && prev.dependsOnViewport()) valuesViewport = -1;
                size--;
                return prev.text;
            }
            return null;
        }
        if (extras == null) return null;
        if (!extras.containsKey(name)) return null;
        ownExtras();
        String old = extras.remove(name);
        if (old != null) {
            size--;
            if (extrasViewport == 1 && StyleResolver.valueDependsOnViewport(old)) extrasViewport = -1;
        }
        return old;
    }

    @Override
    public void clear() {
        relativeUnits = -1;
        valuesViewport = 0;
        java.util.Arrays.fill(values, null);
        extras = null;
        extrasShared = false;
        extrasViewport = 0;
        size = 0;
    }

    @Override
    public Set<Entry<String, String>> entrySet() {
        return new AbstractSet<>() {
            @Override
            public int size() {
                return size;
            }

            @Override
            public Iterator<Entry<String, String>> iterator() {
                return new Iterator<>() {
                    private int next = advance(0);
                    private int last = -1;
                    private Iterator<Entry<String, String>> extra;
                    private boolean inExtras;
                    private String lastExtraKey;

                    private int advance(int from) {
                        CssValue[] v = values;
                        while (from < v.length && v[from] == null) from++;
                        return from;
                    }

                    @Override
                    public boolean hasNext() {
                        if (next < values.length) return true;
                        if (extra == null) {
                            extra = extras != null ? extras.entrySet().iterator() : java.util.Collections.emptyIterator();
                        }
                        return extra.hasNext();
                    }

                    @Override
                    public Entry<String, String> next() {
                        if (next < values.length) {
                            int id = next;
                            next = advance(id + 1);
                            last = id;
                            inExtras = false;
                            return new SimpleImmutableEntry<>(ComputedStyle.CssPropertyId.name(id), values[id].text);
                        }
                        if (!hasNext()) throw new NoSuchElementException();
                        inExtras = true;
                        Entry<String, String> e = extra.next();
                        lastExtraKey = e.getKey();
                        return e;
                    }

                    @Override
                    public void remove() {
                        if (inExtras) {
                            extrasViewport = -1;
                            if (extrasShared) {
                                // 共有中の表を走査の途中で書き換えない。写しから消す（走査は元の表のまま続く）
                                if (lastExtraKey == null) throw new IllegalStateException();
                                ownExtras();
                                extras.remove(lastExtraKey);
                                lastExtraKey = null;
                            } else {
                                extra.remove();
                            }
                            size--;
                        } else {
                            if (last < 0 || values[last] == null) throw new IllegalStateException();
                            values[last] = null;
                            valuesViewport = -1;
                            size--;
                            last = -1;
                        }
                    }
                };
            }
        };
    }
}
