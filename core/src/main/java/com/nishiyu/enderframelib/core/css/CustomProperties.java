package com.nishiyu.enderframelib.core.css;

import java.util.Collections;
import java.util.Map;

/**
 * 登録外の宣言（{@code --*} のカスタムプロパティなど）の不変の表。持つ要素（サブツリー）だけ非 null で、
 * 持たなければ {@link Style#custom} は null。値等価と参照同一性の両方で照合できる。
 */
public final class CustomProperties {
    private final Map<String, String> map;
    private final boolean hasNonCustom;

    CustomProperties(Map<String, String> map) {
        this.map = Collections.unmodifiableMap(map);
        boolean non = false;
        for (String k : map.keySet()) {
            if (!k.startsWith("--")) { non = true; break; }
        }
        this.hasNonCustom = non;
    }

    public String get(String name) { return map.get(name); }

    public Map<String, String> asMap() { return map; }

    public int size() { return map.size(); }

    public boolean hasOnlyCustom() { return !hasNonCustom; }

    /** {@code --*} のカスタムプロパティだけを残した不変表（テキスト・匿名子孫用）。無ければ null。 */
    public CustomProperties onlyCustom() {
        if (!hasNonCustom) return this;
        Map<String, String> out = null;
        for (var e : map.entrySet()) {
            if (e.getKey().startsWith("--")) {
                if (out == null) out = new java.util.LinkedHashMap<>();
                out.put(e.getKey(), e.getValue());
            }
        }
        return out == null ? null : new CustomProperties(out);
    }

    /** {@code --*} 以外の未登録プロパティ（非継承扱い）が同じか。 */
    public static boolean sameNonInherited(CustomProperties a, CustomProperties b) {
        if (a == b) return true;
        boolean ha = a != null && a.hasNonCustom, hb = b != null && b.hasNonCustom;
        if (!ha && !hb) return true;
        if (!ha || !hb) return false;
        for (var e : a.map.entrySet()) {
            String k = e.getKey();
            if (!k.startsWith("--") && !java.util.Objects.equals(e.getValue(), b.map.get(k))) return false;
        }
        for (var e : b.map.entrySet()) {
            String k = e.getKey();
            if (!k.startsWith("--") && !a.map.containsKey(k)) return false;
        }
        return true;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof CustomProperties o && map.equals(o.map));
    }

    @Override
    public int hashCode() { return map.hashCode(); }
}
