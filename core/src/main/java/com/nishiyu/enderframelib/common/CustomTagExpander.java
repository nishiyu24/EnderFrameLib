package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.dom.node.TagMacro;
import com.nishiyu.enderframelib.core.dom.HostElementProvider;

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

    /** 文書全体のカスタムタグを展開する。先に文書内の {@code <template component>}、次にホストのタグマクロ。 */
    public static void expand(HtmlNode root) {
        if (root == null) return;
        expandComponents(root);
        Map<String, TagMacro> table = macros;
        if (table.isEmpty()) return;
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            if (!expandOnce(root, table)) return;
        }
    }

    // ---- <template component="名前"> と <slot>

    /** 入れ子のコンポーネントが互いを呼ぶ循環・暴走を止める深さの上限。 */
    private static final int MAX_COMPONENT_DEPTH = 16;

    /**
     * 文書内の {@code <template component="my-card">…</template>} を部品の定義として集め（定義は木から外す）、
     * {@code <my-card>} の要素を、定義の写しに差し替える。ホストの要素（属性・class・id・イベント）はそのまま残し、
     * 子だけを定義の写しにする。ホストの元の子（ライト DOM）は {@code <slot>} へ投影する：
     * {@code <slot name="x">} には {@code slot="x"} の要素、名前の無い {@code <slot>} には残り（空白だけの文字を除く）が入り、
     * 何も投影されなければ {@code <slot>} の中身（既定の内容）が残る。
     *
     * LIMITATION: 影の木（スタイルの封じ込め）は作らない（見た目の範囲は {@code @scope (my-card)} で絞る）。
     * 定義名はハイフンを含む名前だけ（素のタグの上書きを避ける）。文書をパースするときだけ働き、
     * {@code innerHTML} などのフラグメントには定義が無いので働かない。
     */
    static void expandComponents(HtmlNode root) {
        Map<String, HtmlNode> defs = new HashMap<>();
        collectDefinitions(root, defs);
        if (defs.isEmpty()) return;
        instantiate(root, defs, new java.util.ArrayDeque<>());
    }

    private static void collectDefinitions(HtmlNode parent, Map<String, HtmlNode> defs) {
        for (int i = 0; i < parent.children.size(); i++) {
            HtmlNode child = parent.children.get(i);
            if (child.nodeType != NodeType.ELEMENT) continue;
            String name = "template".equals(child.tag) ? child.attrs.get("component") : null;
            if (name != null) {
                String key = name.trim().toLowerCase(Locale.ROOT);
                if (key.indexOf('-') > 0) defs.put(key, child);
                parent.children.remove(i--);
            } else if (!"template".equals(child.tag)) {
                collectDefinitions(child, defs);
            }
        }
    }

    private static void instantiate(HtmlNode parent, Map<String, HtmlNode> defs, java.util.Deque<String> active) {
        for (HtmlNode child : new java.util.ArrayList<>(parent.children)) {
            if (child.nodeType != NodeType.ELEMENT) continue;
            String key = child.tag.toLowerCase(Locale.ROOT);
            HtmlNode def = defs.get(key);
            if (def == null || active.contains(key) || active.size() >= MAX_COMPONENT_DEPTH) {
                instantiate(child, defs, active);
                continue;
            }
            List<HtmlNode> light = new java.util.ArrayList<>(child.children);
            child.children.clear();
            // template の中身は要素の子ではなく content の子
            HtmlNode source = def instanceof com.nishiyu.enderframelib.core.dom.node.HtmlElement e && e.getContent() != null
                    ? e.getContent() : def;
            for (HtmlNode node : new java.util.ArrayList<>(source.children)) {
                HtmlNode copy = node.cloneNode(true);
                copy.parent = child;
                child.children.add(copy);
            }
            project(child, light);
            active.push(key);
            instantiate(child, defs, active);
            active.pop();
        }
    }

    /** {@code host} の中の {@code <slot>} を、ライト DOM（{@code light}）か既定の内容に置き換える。 */
    private static void project(HtmlNode host, List<HtmlNode> light) {
        for (HtmlNode node : new java.util.ArrayList<>(host.children)) {
            if (node.nodeType != NodeType.ELEMENT) continue;
            if (!"slot".equals(node.tag)) {
                project(node, light);
                continue;
            }
            String name = node.attrs.getOrDefault("name", "");
            List<HtmlNode> assigned = new java.util.ArrayList<>();
            boolean content = false;
            for (HtmlNode l : light) {
                boolean element = l.nodeType == NodeType.ELEMENT;
                String target = element ? l.attrs.getOrDefault("slot", "") : "";
                if (!target.equals(name)) continue;
                assigned.add(l);
                content |= element || !l.getTextContent().isBlank();
            }
            List<HtmlNode> replacement;
            if (content) {
                replacement = assigned;
                light.removeAll(assigned);
            } else {
                project(node, light); // 既定の内容の中にも <slot> があり得る
                replacement = new java.util.ArrayList<>(node.children);
            }
            int at = host.children.indexOf(node);
            host.children.remove(at);
            for (int k = 0; k < replacement.size(); k++) {
                HtmlNode r = replacement.get(k);
                r.parent = host;
                host.children.add(at + k, r);
            }
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
