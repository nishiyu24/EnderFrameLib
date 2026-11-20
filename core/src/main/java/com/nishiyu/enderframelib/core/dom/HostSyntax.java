package com.nishiyu.enderframelib.core.dom;

import com.nishiyu.enderframelib.core.dom.HostElementProvider;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * ホストが持ち込むテンプレート構文（属性の別名・Markdown ブロックのタグ）の台帳。{@link HostElementProvider}
 * の宣言（SPI。起動後は変わらない）を {@link ServiceLoader} で集め、面ごとの追加（{@link Extras}。A13）を重ねる。
 * エンジン本体は {@code mc-if} などの具体名を持たない。読み取りは不変スナップショットの参照 1 回（ロック・確保なし）。
 * 以前は static の登録表（{@code registerAttributeAlias} / {@code registerMarkdownTag} / {@code resetToDefaults}）で、面ごとに変えられなかった。
 */
public final class HostSyntax {

    private static final String[] NO_ALIASES = new String[0];

    private record Snapshot(Map<String, String[]> aliasesByCanonical, Map<String, String> canonicalByAlias, Set<String> markdownTags) {}

    /** SPI（ServiceLoader）が宣言した構文。起動後は変わらない。 */
    private static final Snapshot SPI = load();

    /** 文脈が差し込む構文の追加（SPI に足す）。不変で、SPI と合わせた結果を作ったときに持つ（読むたびに合成しない）。 */
    public static final class Extras {
        /** 追加のない状態。 */
        public static final Extras NONE = new Extras(Map.of(), Set.of());

        private final Map<String, String> aliasToCanonical;
        private final Set<String> markdownTags;
        private final Snapshot merged;

        private Extras(Map<String, String> aliasToCanonical, Set<String> markdownTags) {
            this.aliasToCanonical = aliasToCanonical;
            this.markdownTags = markdownTags;
            Map<String, String> a = new HashMap<>(SPI.canonicalByAlias());
            a.putAll(aliasToCanonical);
            Set<String> md = new HashSet<>(SPI.markdownTags());
            md.addAll(markdownTags);
            this.merged = build(a, md);
        }

        /** 属性の別名を 1 つ足した新しい追加（同名は上書き）。 */
        public Extras withAttributeAlias(String alias, String canonical) {
            check(null0(), alias, canonical);
            Map<String, String> m = new HashMap<>(aliasToCanonical);
            m.put(alias.toLowerCase(Locale.ROOT), canonical.toLowerCase(Locale.ROOT));
            return new Extras(Map.copyOf(m), markdownTags);
        }

        /** Markdown ブロックのタグを 1 つ足した新しい追加。 */
        public Extras withMarkdownTag(String tag) {
            if (tag == null || tag.isEmpty()) throw new IllegalArgumentException("tag");
            Set<String> md = new HashSet<>(markdownTags);
            md.add(tag.toLowerCase(Locale.ROOT));
            return new Extras(aliasToCanonical, Set.copyOf(md));
        }
    }

    /** 文脈が持つ追加の読み口（{@code HostServices} が実装する）。null なら追加なし。 */
    public interface Source {
        Extras syntaxExtras();
    }

    private static volatile java.util.function.Supplier<Source> source = () -> () -> null;

    /** 「このスレッドの文脈の値」を引く口を結ぶ（{@code EngineContext} が 1 回だけ呼ぶ）。 */
    public static void bindSource(java.util.function.Supplier<Source> contextSource) {
        source = contextSource == null ? () -> () -> null : contextSource;
    }

    private static Snapshot snap() {
        Extras x = source.get().syntaxExtras();
        return x == null ? SPI : x.merged;
    }

    private HostSyntax() {}

    private static Snapshot load() {
        Map<String, String> aliasToCanonical = new HashMap<>();
        Set<String> md = new HashSet<>();
        for (HostElementProvider p : ServiceLoader.load(HostElementProvider.class, HostElementProvider.class.getClassLoader())) {
            for (Map.Entry<String, String> e : p.attributeAliases().entrySet()) {
                check(p, e.getKey(), e.getValue());
                aliasToCanonical.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().toLowerCase(Locale.ROOT));
            }
            for (String t : p.markdownTags()) {
                if (t == null || t.isEmpty()) throw new IllegalStateException(p.getClass().getName() + " の markdownTags が不正です");
                md.add(t.toLowerCase(Locale.ROOT));
            }
        }
        return build(aliasToCanonical, md);
    }

    private static void check(HostElementProvider p, String alias, String canonical) {
        if (alias == null || alias.isEmpty() || canonical == null || canonical.isEmpty() || alias.equalsIgnoreCase(canonical)) {
            throw new IllegalStateException(p.getClass().getName() + " の属性別名の宣言が不正です: " + alias + " -> " + canonical);
        }
    }

    private static Snapshot build(Map<String, String> aliasToCanonical, Set<String> md) {
        Map<String, List<String>> by = new HashMap<>();
        aliasToCanonical.forEach((a, c) -> by.computeIfAbsent(c, k -> new java.util.ArrayList<>()).add(a));
        Map<String, String[]> arr = new HashMap<>();
        by.forEach((c, l) -> arr.put(c, l.toArray(new String[0])));
        return new Snapshot(Map.copyOf(arr), Map.copyOf(aliasToCanonical), Set.copyOf(md));
    }

    private static HostElementProvider null0() {
        return new HostElementProvider() {};
    }

    /** 正規名に対するホストの別名（無ければ空配列）。 */
    public static String[] aliasesOf(String canonical) {
        String[] a = snap().aliasesByCanonical().get(canonical);
        return a == null ? NO_ALIASES : a;
    }

    /** 属性名を正規名へ寄せる（別名でなければそのまま）。 */
    public static String canonicalOf(String attrName) {
        String c = snap().canonicalByAlias().get(attrName);
        return c == null ? attrName : c;
    }

    /** 正規名の属性値。無ければホストの別名を順に探す。どちらも無ければ null。 */
    public static String attr(Map<String, String> attrs, String canonical) {
        String v = attrs.get(canonical);
        if (v != null) return v;
        for (String a : aliasesOf(canonical)) {
            v = attrs.get(a);
            if (v != null) return v;
        }
        return null;
    }

    /** ホストが Markdown ブロックとして宣言したタグ。 */
    public static Set<String> markdownTags() {
        return snap().markdownTags();
    }

    public static boolean isMarkdownTag(String tag) {
        return snap().markdownTags().contains(tag);
    }
}
