package com.nishiyu.enderframelib.core.css;

import java.util.Collections;
import java.util.Map;

/**
 * 登録外の宣言（{@code --*} のカスタムプロパティなど）の不変の表。持つ要素（サブツリー）だけ非 null で、
 * 持たなければ {@link Style#custom} は null。値等価と参照同一性の両方で照合できる。
 */
public final class CustomProperties {
    private final Map<String, String> map;

    CustomProperties(Map<String, String> map) {
        this.map = Collections.unmodifiableMap(map);
    }

    public String get(String name) { return map.get(name); }

    public Map<String, String> asMap() { return map; }

    public int size() { return map.size(); }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof CustomProperties o && map.equals(o.map));
    }

    @Override
    public int hashCode() { return map.hashCode(); }
}
