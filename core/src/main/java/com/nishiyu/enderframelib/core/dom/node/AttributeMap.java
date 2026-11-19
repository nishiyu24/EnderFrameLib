package com.nishiyu.enderframelib.core.dom.node;

import com.nishiyu.enderframelib.core.dom.*;
import com.nishiyu.enderframelib.core.dom.node.*;
import com.nishiyu.enderframelib.core.dom.parser.*;
import com.nishiyu.enderframelib.core.dom.parser.*;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 要素ごとの属性数を 0〜数個想定で抑える線形ペア配列マップ。
 * {@link LinkedHashMap} と同じ挿入順を保ちつつ、空のテキスト節では追加配列を確保しない。
 */
final class AttributeMap extends AbstractMap<String, String> {

    /** 属性の持ち主（A8 で {@code HtmlNode} の内部クラスから分けた。以前の暗黙の外側参照と同じ 1 欄）。 */
    private final HtmlNode owner;

    AttributeMap(HtmlNode owner) {
        this.owner = owner;
    }

    private String[] pairs;
    private int count;

    private int indexOfKey(Object key) {
        for (int i = 0; i < count; i++) {
            if (Objects.equals(pairs[i << 1], key)) return i;
        }
        return -1;
    }

    private String rawGet(Object key) {
        int idx = indexOfKey(key);
        return idx >= 0 ? pairs[(idx << 1) + 1] : null;
    }

    private String rawPut(String key, String value) {
        int idx = indexOfKey(key);
        if (idx >= 0) {
            int vIdx = (idx << 1) + 1;
            String prev = pairs[vIdx];
            pairs[vIdx] = value;
            return prev;
        }
        int needed = (count + 1) << 1;
        if (pairs == null) {
            pairs = new String[2];
        } else if (pairs.length < needed) {
            int nextCap = pairs.length == 2 ? 4 : pairs.length + 4;
            pairs = Arrays.copyOf(pairs, nextCap);
        }
        int base = count << 1;
        pairs[base] = key;
        pairs[base + 1] = value;
        count++;
        return null;
    }

    private void removeAt(int idx) {
        int last = count - 1;
        int tail = last - idx;
        if (tail > 0) {
            System.arraycopy(pairs, (idx + 1) << 1, pairs, idx << 1, tail << 1);
        }
        int base = last << 1;
        pairs[base] = null;
        pairs[base + 1] = null;
        count = last;
    }

    private String rawRemove(Object key) {
        int idx = indexOfKey(key);
        if (idx < 0) return null;
        String prev = pairs[(idx << 1) + 1];
        removeAt(idx);
        return prev;
    }

    private void rawClear() {
        if (pairs != null) {
            Arrays.fill(pairs, 0, count << 1, null);
        }
        count = 0;
    }

    @Override
    public int size() {
        return count;
    }

    @Override
    public boolean isEmpty() {
        return count == 0;
    }

    @Override
    public boolean containsKey(Object key) {
        return indexOfKey(key) >= 0;
    }

    @Override
    public String get(Object key) {
        return rawGet(key);
    }

    @Override
    public void forEach(BiConsumer<? super String, ? super String> action) {
        Objects.requireNonNull(action);
        for (int i = 0; i < count; i++) {
            int base = i << 1;
            action.accept(pairs[base], pairs[base + 1]);
        }
    }

    boolean hasEventAttribute() {
        for (int i = 0; i < count; i++) {
            String k = pairs[i << 1];
            if (k != null && k.regionMatches(true, 0, "on", 0, 2)) return true;
        }
        return false;
    }

    private <R> R trackingId(Supplier<R> mutation) {
        owner.attrVersion++;
        String before = rawGet("id");
        String classBefore = rawGet("class");
        R result = mutation.get();
        String after = rawGet("id");
        if (!Objects.equals(before, after)) {
            owner.derivedKey = null;
            owner.idAttributeChanged(before, after);
            owner.identityAttributeChanged();
        } else if (!Objects.equals(classBefore, rawGet("class"))) {
            owner.identityAttributeChanged();
        }
        return result;
    }

    @Override
    public String put(String key, String value) {
        return trackingId(() -> rawPut(key, value));
    }

    @Override
    public String putIfAbsent(String key, String value) {
        return trackingId(() -> {
            int idx = indexOfKey(key);
            if (idx >= 0) {
                String cur = pairs[(idx << 1) + 1];
                if (cur != null) return cur;
                pairs[(idx << 1) + 1] = value;
                return null;
            }
            return rawPut(key, value);
        });
    }

    @Override
    public void putAll(Map<? extends String, ? extends String> entries) {
        if (entries.isEmpty()) return;
        trackingId(() -> {
            for (Map.Entry<? extends String, ? extends String> e : entries.entrySet()) {
                rawPut(e.getKey(), e.getValue());
            }
            return null;
        });
    }

    @Override
    public String remove(Object key) {
        return trackingId(() -> rawRemove(key));
    }

    @Override
    public boolean remove(Object key, Object value) {
        return trackingId(() -> {
            int idx = indexOfKey(key);
            if (idx < 0 || !Objects.equals(pairs[(idx << 1) + 1], value)) return false;
            removeAt(idx);
            return true;
        });
    }

    @Override
    public void clear() {
        trackingId(() -> {
            rawClear();
            return null;
        });
    }

    @Override
    public String replace(String key, String value) {
        return trackingId(() -> {
            int idx = indexOfKey(key);
            if (idx < 0) return null;
            int vIdx = (idx << 1) + 1;
            String prev = pairs[vIdx];
            pairs[vIdx] = value;
            return prev;
        });
    }

    @Override
    public boolean replace(String key, String oldValue, String newValue) {
        return trackingId(() -> {
            int idx = indexOfKey(key);
            if (idx < 0 || !Objects.equals(pairs[(idx << 1) + 1], oldValue)) return false;
            pairs[(idx << 1) + 1] = newValue;
            return true;
        });
    }

    @Override
    public void replaceAll(BiFunction<? super String, ? super String, ? extends String> function) {
        Objects.requireNonNull(function);
        trackingId(() -> {
            for (int i = 0; i < count; i++) {
                int base = i << 1;
                pairs[base + 1] = function.apply(pairs[base], pairs[base + 1]);
            }
            return null;
        });
    }

    @Override
    public String computeIfAbsent(String key, Function<? super String, ? extends String> mapping) {
        Objects.requireNonNull(mapping);
        return trackingId(() -> {
            int idx = indexOfKey(key);
            if (idx >= 0) {
                String cur = pairs[(idx << 1) + 1];
                if (cur != null) return cur;
            }
            String v = mapping.apply(key);
            if (v != null) rawPut(key, v);
            return v;
        });
    }

    @Override
    public String computeIfPresent(String key, BiFunction<? super String, ? super String, ? extends String> remapping) {
        Objects.requireNonNull(remapping);
        return trackingId(() -> {
            int idx = indexOfKey(key);
            if (idx < 0) return null;
            String old = pairs[(idx << 1) + 1];
            if (old == null) return null;
            String next = remapping.apply(key, old);
            if (next != null) {
                pairs[(idx << 1) + 1] = next;
            } else {
                removeAt(idx);
            }
            return next;
        });
    }

    @Override
    public String compute(String key, BiFunction<? super String, ? super String, ? extends String> remapping) {
        Objects.requireNonNull(remapping);
        return trackingId(() -> {
            int idx = indexOfKey(key);
            String old = idx >= 0 ? pairs[(idx << 1) + 1] : null;
            String next = remapping.apply(key, old);
            if (next != null) {
                if (idx >= 0) {
                    pairs[(idx << 1) + 1] = next;
                } else {
                    rawPut(key, next);
                }
            } else if (idx >= 0) {
                removeAt(idx);
            }
            return next;
        });
    }

    @Override
    public String merge(String key, String value, BiFunction<? super String, ? super String, ? extends String> remapping) {
        Objects.requireNonNull(remapping);
        Objects.requireNonNull(value);
        return trackingId(() -> {
            int idx = indexOfKey(key);
            String old = idx >= 0 ? pairs[(idx << 1) + 1] : null;
            String next = old == null ? value : remapping.apply(old, value);
            if (next == null) {
                if (idx >= 0) removeAt(idx);
            } else if (idx >= 0) {
                pairs[(idx << 1) + 1] = next;
            } else {
                rawPut(key, next);
            }
            return next;
        });
    }

    @Override
    public Set<Map.Entry<String, String>> entrySet() {
        return new AbstractSet<>() {
            @Override
            public int size() {
                return count;
            }

            @Override
            public void clear() {
                AttributeMap.this.clear();
            }

            @Override
            public Iterator<Map.Entry<String, String>> iterator() {
                return new Iterator<>() {
                    private int cursor;
                    private int last = -1;

                    @Override
                    public boolean hasNext() {
                        return cursor < count;
                    }

                    @Override
                    public Map.Entry<String, String> next() {
                        if (cursor >= count) throw new NoSuchElementException();
                        last = cursor++;
                        int base = last << 1;
                        String k = pairs[base];
                        return new Map.Entry<>() {
                            @Override
                            public String getKey() {
                                return k;
                            }

                            @Override
                            public String getValue() {
                                return rawGet(k);
                            }

                            @Override
                            public String setValue(String value) {
                                return AttributeMap.this.put(k, value);
                            }

                            @Override
                            public boolean equals(Object o) {
                                if (!(o instanceof Map.Entry<?, ?> other)) return false;
                                return Objects.equals(k, other.getKey())
                                        && Objects.equals(getValue(), other.getValue());
                            }

                            @Override
                            public int hashCode() {
                                return Objects.hashCode(k) ^ Objects.hashCode(getValue());
                            }
                        };
                    }

                    @Override
                    public void remove() {
                        if (last < 0) throw new IllegalStateException();
                        int idx = last;
                        trackingId(() -> {
                            removeAt(idx);
                            return null;
                        });
                        cursor = idx;
                        last = -1;
                    }
                };
            }
        };
    }
}
