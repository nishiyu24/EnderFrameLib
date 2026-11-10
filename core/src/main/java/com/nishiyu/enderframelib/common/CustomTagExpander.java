package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.dom.node.TagMacro;
import com.nishiyu.enderframelib.core.layout.HostElementProvider;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * ホストが登録したカスタムタグ（{@link TagMacro}）を、パース後の DOM の部分木差し替えで素の HTML 要素に展開する。
 * エンジン本体は具体的なタグ名を持たない。マクロは {@link HostElementProvider#tagMacros()}（ServiceLoader）で宣言される。
 *
 * 以前は HTML 文字列に対する正規表現置換で、パースより前に行われていた。
 * そのため属性値の中の {@code >} や入れ子で壊れ、生成した HTML を再度パースし直していた。
 */
public final class CustomTagExpander {

    /** マクロが入れ子を生むことはないが、万一の循環で無限に展開しないための上限。 */
    private static final int MAX_PASSES = 4;

    private static volatile Map<String, TagMacro> macros = load();

    private CustomTagExpander() {}

    private static Map<String, TagMacro> load() {
        Map<String, TagMacro> m = new HashMap<>();
        for (HostElementProvider p : ServiceLoader.load(HostElementProvider.class, HostElementProvider.class.getClassLoader())) {
            for (Map.Entry<String, TagMacro> e : p.tagMacros().entrySet()) {
                if (e.getKey() == null || e.getKey().isEmpty() || e.getValue() == null) {
                    throw new IllegalStateException(p.getClass().getName() + " のタグマクロの宣言が不正です: " + e.getKey());
                }
                m.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
            }
        }
        return Map.copyOf(m);
    }

    /** タグマクロを足す（同名は上書き）。テスト・動的登録用。 */
    public static synchronized void register(String tag, TagMacro macro) {
        if (tag == null || tag.isEmpty() || macro == null) throw new IllegalArgumentException("tag/macro");
        Map<String, TagMacro> m = new HashMap<>(macros);
        m.put(tag.toLowerCase(Locale.ROOT), macro);
        macros = Map.copyOf(m);
    }

    /** プロバイダ（SPI）の宣言だけの状態へ戻す。テストの後始末用。 */
    public static synchronized void resetToDefaults() {
        macros = load();
    }

    /** 文書全体のカスタムタグを展開する。 */
    public static void expand(HtmlNode root) {
        if (root == null) return;
        Map<String, TagMacro> table = macros;
        if (table.isEmpty()) return;
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            if (!expandOnce(root, table)) return;
        }
    }

    /** 展開が 1 つでも起きたら true。 */
    private static boolean expandOnce(HtmlNode parent, Map<String, TagMacro> table) {
        boolean expanded = false;

        for (int i = 0; i < parent.children.size(); i++) {
            HtmlNode child = parent.children.get(i);
            if (child.nodeType != NodeType.ELEMENT) continue;

            TagMacro macro = table.get(child.tag.toLowerCase(Locale.ROOT));
            List<HtmlNode> replacement = macro == null ? null : macro.expand(child);

            if (replacement != null) {
                parent.children.remove(i);
                for (int k = 0; k < replacement.size(); k++) {
                    HtmlNode node = replacement.get(k);
                    node.parent = parent;
                    parent.children.add(i + k, node);
                }
                i += replacement.size() - 1;
                expanded = true;
            } else {
                expanded |= expandOnce(child, table);
            }
        }
        return expanded;
    }
}
