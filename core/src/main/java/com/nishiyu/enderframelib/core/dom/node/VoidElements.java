package com.nishiyu.enderframelib.core.dom.node;

import java.util.Set;

/**
 * 空要素（閉じタグを持たない要素）の定義。
 * 複数箇所にあった定義を1本化します。
 */
public final class VoidElements {
    // HTML標準の空要素（§13.1.2）。ホスト独自の空要素は HostElementProvider（SPI）から加わる
    public static final Set<String> TAGS = Set.of(
            "area", "base", "basefont", "bgsound", "br", "col", "embed", "frame", "hr", "img", "input",
            "keygen", "link", "meta", "param", "source", "track", "wbr"
    );

    private static volatile Set<String> host = loadHost();

    private static Set<String> loadHost() {
        Set<String> m = new java.util.HashSet<>();
        for (var p : java.util.ServiceLoader.load(com.nishiyu.enderframelib.core.dom.node.HostElementProvider.class,
                com.nishiyu.enderframelib.core.dom.node.HostElementProvider.class.getClassLoader())) {
            for (String t : p.voidElements()) m.add(t.toLowerCase(java.util.Locale.ROOT));
        }
        return Set.copyOf(m);
    }

    /** プロバイダ（SPI）の宣言だけの状態へ戻す。テストの後始末用。 */
    public static synchronized void resetHost() {
        host = loadHost();
    }

    /** ホスト独自の空要素を足す（テスト・動的登録用）。 */
    public static synchronized void registerHost(String tag) {
        if (tag == null || tag.isEmpty()) throw new IllegalArgumentException("tag");
        Set<String> m = new java.util.HashSet<>(host);
        m.add(tag.toLowerCase(java.util.Locale.ROOT));
        host = Set.copyOf(m);
    }

    public static boolean isVoid(String tagName) {
        String t = tagName != null ? tagName.toLowerCase() : "";
        return TAGS.contains(t) || host.contains(t);
    }
}
