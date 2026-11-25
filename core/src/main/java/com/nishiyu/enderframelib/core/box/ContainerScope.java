package com.nishiyu.enderframelib.core.box;

import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.css.model.ContainerBox;
import com.nishiyu.enderframelib.core.css.model.ContainerType;
import com.nishiyu.enderframelib.core.css.model.ElementState;
import com.nishiyu.enderframelib.core.css.model.StateLookup;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * コンテナ問い合わせ（{@code @container}・{@code cqw} などの単位）に答える {@link StateLookup}（Engine v3 M2b-5）。要素の状態
 * （{@code :hover} など）は元の問い合わせ先へ渡し、{@link #containerOf} はコンパイル中の祖先の列から答える。
 *
 * <p>{@link BoxTreeCompiler} は根から下へ辿るので、解いている節の祖先は必ずこの列に載っている（{@link #push} / {@link #pop}）。
 * 祖先の種類・名前・カスタムプロパティは列の算出スタイル、寸法は前のレイアウトで測った値（{@link InteractionState#setContainerSize}。
 * 1 ビューの状態で、旧経路と同じ置き場所）。測っていなければ NaN で、寸法の問い合わせは偽（旧経路の最初のパスと同じ）。
 * 節 → スタイルの表は作らない（列はコンパイルの間だけ）。</p>
 */
final class ContainerScope implements StateLookup {
    private final StateLookup base;
    private final List<HtmlNode> nodes = new ArrayList<>();
    private final List<Style> styles = new ArrayList<>();

    ContainerScope(StateLookup base) {
        this.base = base;
    }

    void push(HtmlNode n, Style computed) {
        nodes.add(n);
        styles.add(computed);
    }

    void pop() {
        nodes.removeLast();
        styles.removeLast();
    }

    @Override
    public ElementState stateOf(HtmlNode node) {
        return base.stateOf(node);
    }

    @Override
    public ContainerBox containerOf(HtmlNode node) {
        for (int i = nodes.size() - 1; i >= 0; i--) {
            if (nodes.get(i) == node) return box(node, styles.get(i));
        }
        return null;
    }

    private ContainerBox box(HtmlNode node, Style st) {
        ContainerType type = st.containerType();
        float w = Float.NaN, h = Float.NaN;
        if (type != ContainerType.NORMAL && base instanceof InteractionState is) {
            w = is.containerSize(node, false);
            h = is.containerSize(node, true);
        }
        return new ContainerBox(w, h, type, names(st.rare.containerName), st.custom != null ? st.custom.asMap() : Map.of());
    }

    private static List<String> names(String list) {
        if (list == null || list.isBlank() || "none".equalsIgnoreCase(list.trim())) return List.of();
        List<String> out = new ArrayList<>();
        for (String name : list.trim().toLowerCase(java.util.Locale.ROOT).split("\\s+")) {
            if (!name.isEmpty() && !name.equals("none")) out.add(name);
        }
        return out;
    }
}
