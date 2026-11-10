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
 * の宣言を {@link ServiceLoader} で集める。エンジン本体は {@code mc-if} などの具体名を持たない。
 * 読み取りは不変スナップショットの参照 1 回（ロック・確保なし）。
 */
public final class HostSyntax {

    private static final String[] NO_ALIASES = new String[0];

    private record Snapshot(Map<String, String[]> aliasesByCanonical, Map<String, String> canonicalByAlias, Set<String> markdownTags) {}

    private static volatile Snapshot snap = load();

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

    /** 別名を足す（テスト・動的登録用）。 */
    public static synchronized void registerAttributeAlias(String alias, String canonical) {
        check(null0(), alias, canonical);
        Snapshot s = snap;
        Map<String, String> m = new HashMap<>(s.canonicalByAlias());
        m.put(alias.toLowerCase(Locale.ROOT), canonical.toLowerCase(Locale.ROOT));
        snap = build(m, s.markdownTags());
    }

    /** Markdown ブロックのタグを足す（テスト・動的登録用）。 */
    public static synchronized void registerMarkdownTag(String tag) {
        if (tag == null || tag.isEmpty()) throw new IllegalArgumentException("tag");
        Snapshot s = snap;
        Set<String> md = new HashSet<>(s.markdownTags());
        md.add(tag.toLowerCase(Locale.ROOT));
        snap = build(s.canonicalByAlias(), md);
    }

    /** プロバイダ（SPI）の宣言だけの状態へ戻す。テストの後始末用。 */
    public static synchronized void resetToDefaults() {
        snap = load();
    }

    private static HostElementProvider null0() {
        return new HostElementProvider() {};
    }

    /** 正規名に対するホストの別名（無ければ空配列）。 */
    public static String[] aliasesOf(String canonical) {
        String[] a = snap.aliasesByCanonical().get(canonical);
        return a == null ? NO_ALIASES : a;
    }

    /** 属性名を正規名へ寄せる（別名でなければそのまま）。 */
    public static String canonicalOf(String attrName) {
        String c = snap.canonicalByAlias().get(attrName);
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
        return snap.markdownTags();
    }

    public static boolean isMarkdownTag(String tag) {
        return snap.markdownTags().contains(tag);
    }
}
